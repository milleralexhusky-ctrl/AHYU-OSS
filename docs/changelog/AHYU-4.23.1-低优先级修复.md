# AHYU 4.23.1 —— [低] 项核实与修复

先对 4.21.9 源码**逐条核实**了审计列的 [低] 项（此前只有 [高]/[中] 做过逐条验证）。

## 核实结果：11 条里 3 条不成立

| # | 项目 | 核实 |
|---|---|---|
| 1 | 流内错误缺 `[DONE]` | ✅ **成立** |
| 2 | `Limits` 非原子 | ❌ **不成立** |
| 3 | 半开试探自锁 | ✅ **成立** |
| 4 | `recordOk` 洗掉暂避 | ✅ **成立** |
| 5 | `Stat` 字段锁外读写 | ✅ **成立** |
| 6 | auto-race 名不副实 | ⚠️ 成立但是**设计问题** |
| 7 | `Secret` 回退明文 | ⚠️ 成立但是**有意取舍**（注释已写明） |
| 8 | 剪贴板残留 | ✅ 成立（本轮未改，需定时效决策） |
| 9 | 明文输入框 | ✅ **成立** |
| 10 | 端口 hint 写死 | ❌ **不成立** |
| 11 | Doubao token 前 8 位 | ✅ 已在 4.21.9 修掉 |

### 两条"不成立"的依据

**2（`Limits` 非原子）**：`acquire()` 的检查+递增整个在 `synchronized (w)` 里
（`Limits.java:143-148`），`record()` 也全程持锁（152-163）。读写是原子的。

**10（端口 hint 写死）**：`SettingsActivity.java:59`
`Ui.field(this, "8321", String.valueOf(cfg.port), false)` ——
`"8321"` 是 **placeholder（空值时的提示）**，实际值读 `cfg.port`。
不是写死。这也印证了 4.19.4 那次审计的结论（"与现状不符，没改"）。

## 已修五条

### 第 1 条：流内错误缺 `[DONE]`（位置比审计说的更多）

`failResponse()` 里**其实补了** `[DONE]`（1896-1899），但：

1. **只在 `chunkedEncoding == true` 时补**，`else` 分支只写错误 payload；
2. **各协议自己手写的错误分支全都漏了** —— 这是主要问题：
   - OpenAI（2394-2405）：写错误 payload 后只 `flush()`，无 `[DONE]`
   - Anthropic（2793-2799）：写 error 事件后结束

修法：OpenAI 错误分支补 `data: [DONE]\n\n`；
`failResponse` 的 `else` 分支同样补。

**注意**：Anthropic 用 `message_stop` 事件结束、Gemini 无特殊标记，
**只有 OpenAI 协议需要 `[DONE]`**，所以没有改另两个（改了反而不符合协议）。

### 第 3 条：半开试探自锁

`candidates()` 在 HALF_OPEN 时给 key 打 `probingAt`，而清除它的地方**只有**
`recordOk` / `recordFail`。但试探完全可能走到**既非成功也非失败的出口**：

- `ClientGone`（客户端断开）
- `InvalidRequest`（请求本身非法）
- `RaceLost`（竞速落败）
- `handle.cancelled`（被掐线的输家）

这些路径都不记成功也不记失败 → `probingAt` 永远留着 →
该 key 之后每次都被 90 秒宽限期挡住 → **退避计数永不归零**。

修法：新增 `Router.abortProbe(key)`（只放试探标记，**不动熔断状态**——本次试探没结论，
冷却该继续就继续），在上述四类跳过路径调用，共 8 处。

### 第 4 条：`recordOk` 洗掉暂避

```java
s.throttleUntil = 0;   // 删掉
```

上游说过"限流，等 60 秒"，一次成功不代表该撤销 —— 那个成功可能是别的模型、
也可能只是抢到了配额。抹掉回避后下一个请求立刻又撞上去，
这是"免费池反复 429"的成因之一。

`throttleUntil` 本来就是**到点自动失效的绝对时间**，留着它会自然过期，不需要手动清。

### 第 5 条：`Stat` 字段锁外读写

`stat(key)` 只保证"取/建对象"原子，返回后所有字段读写都在锁外：

```java
Stat s = stat(key);     // 锁到此为止
s.fail++;               // ← 锁外
```

并发下会丢更新，而且 `recordFail` 里
`if (s.streak >= FAIL_STREAK_LIMIT)` 判断后 `s.openCount++` 不是原子单元，
两个失败可能只加一次、或同时过阈值重复开熔断。

修法：`recordOk` / `recordFail` / `recordThrottled` 的字段读改写全部放进
`synchronized (STATS)`（与 `stat()` 用同一把锁）。反编译核对共 6 处。

### 第 9 条：密钥输入框明文显示

全工程**没有一处** `TYPE_TEXT_VARIATION_PASSWORD` / `PasswordTransformation`，
`SettingsActivity` 的"客户端密钥"框是明文。

修法：默认遮蔽 + 一个「显示/隐藏」切换按钮。
（必须有切换 —— 否则用户没法核对是否填对。切换时把光标挪回末尾，避免跳到开头。）

## 未修（有意）

- **第 6 条（auto-race 名不副实）**：`raceCandidates` 取 `candidates(AUTO)` 的前 n 个
  不同账号，是"按分数路由"的合理设计，不是 bug。改名或改行为都需要先定义期望语义。
- **第 7 条（`Secret` 回退明文）**：代码注释已明确这是取舍（"可用性优先"），
  且会记 ERROR 并设 `lastSealFailed` 标志供界面提示。改它需要先定策略。
- **第 8 条（剪贴板）**：`setPrimaryClip` 后无清除。自动清会让"复制了过会儿再粘贴"
  失败，**需要先定保留时长**，所以本轮没动。
- **第 2、10 条**：核实不成立，无需改动。

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | 分步构建（aapt2 compile/link → javac → d8 → 打包 → 签名） |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 533 / versionName 4.23.1 |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |
| 三协议回归 | OpenAI 200 / Gemini 200 / Anthropic 200 |
| APK 体积 | 1349174 → 1353270（新代码进包） |

### 反编译核对（jadx）

- 第 4 条：`throttleUntil` 只剩"写入"（153）与"读取判断"（112/300/459），
  **`recordOk` 里已无清零语句**
- 第 5 条：`synchronized (STATS)` 共 6 处
- 第 3 条：`abortProbe` 定义在 192 行
- 第 9 条：`keyMasked` 字段 + `applyKeyMask` 调用

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 533 / 4.23.1）
- 副本 `/sdcard/Download/cpa/AHYU-4.23.1-com.ahyu.apk`

## 遗留

- 第 8 条（剪贴板）待定保留时长后再做
- 第 6、7 条是否处理待定
- **保活那条已闭**（见《AHYU-保活真机验证记录.md》：
  加急任务成功拉起网关的端到端证据已取得）
- ~~**新发现待处理**：`autostart` 关闭时，重启后 job 不会自动重登记，
  保活归零直到用户手动打开一次 App（详见保活记录）~~
  **已在 4.23.2 修掉**（`BootReceiver` 里 `autostart` 关着也照样登记保活任务，
  见《AHYU-4.23.2-保活缺口与剪贴板.md》与保活记录）。这条留在这里是当时的状态，
  不要当成本轮的待办。
