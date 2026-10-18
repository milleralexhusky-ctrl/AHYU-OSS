# AHYU 4.29.2 — 源码复审修复

本轮是对 4.29.0 源码树的一次复审（通读 + 定向静态检查 + 真机验证）。结论：整体质量很高，
核心链路（HTTP 解析与限额、连接池、竞速取消、配置原子写、密钥定长比较、Keystore 加密）
没有发现问题；找到 **2 处真实缺陷**（都已修）和 **2 处文档与代码不符**（已同步）。
另外确认了 3 处**已知结构性风险**，本轮**故意不动**（要改需先定产品策略，见文末）。

## 一、真实缺陷（已修）

### 1. `ToolFix.tidy()` 吃掉字符串里的撇号，整条 tool_call 报废

**问题**（`core/ToolFix.java`）：

```java
String t = s.replace('\'', '"');   // 原实现
```

本意是"单引号 JSON 换成合法的双引号 JSON"，但它是**全局无差别**替换：字符串**内容**里的
撇号也被换成双引号，把字符串从中间截断，整个 JSON 直接失效。

真机验证（编译原实现、直接调用）：

```
in : {"text": "it's fine"}
out: {"text": "it"s fine"}        → PARSE FAIL
in : {"msg": "he said 'hello' loudly"}
out: {"msg": "he said "hello" loudly"}  → PARSE FAIL
```

**影响为什么不小**：`repairArgs()` 返回 null 时，`Fronts.fixToolArgs()` 会**整条丢弃**这次
tool_call（只记日志）。而撇号在工具参数里极其常见 —— `write_file` / shell 命令 / 英文文本
的参数几乎必带 `it's`、`don't`、`'x'`。命中时表现为"模型不会调工具"，而不是报错，
排查方向会被带偏。

**修法**：改成**字符串感知的单次扫描**，只把"确实处于分隔符位置"的单引号翻成双引号：

- 双引号字符串里的内容**原样保留**（含 `'`），不再被误伤；
- 单引号字符串里的 `\'` 转义 → 翻成 `\"`；
- 孤立撇号（`it's`）用启发式判定：向右看第一个非空白字符，是 `, } ] :` 之一才算字符串
  结束符，否则按内容撇号处理。

**回归断言**：进内置自检（第 13 项），在真实 `repairArgs()` 上钉死 4 种形状
（双引号带撇号 / 单引号 JSON / shell 命令参数 / `O'Neil`）。

```
自检：全部 13 项通过（能力参数 / 图片 / 思维链 / 标签 / 限流解析）
```

### 2. 备份导入未夹 PBKDF2 迭代数上界（可被拖死）

**问题**（`core/Backup.java`）：

```java
int iter = o.optInt("iterations", ITERATIONS);   // 来自不可信文件
...
PBEKeySpec spec = new PBEKeySpec(pw, salt, Math.max(1, iterations), KEY_BITS);
```

`iterations` 是**备份文件里的字段**，而 `derive()` 只夹了下界（`Math.max(1, …)`）、
没夹上界。PBKDF2 的耗时与迭代数成正比，一个把 `iterations` 写成 20 亿的（伪造或损坏的）
备份，导入时会一直算下去，用户看到的就是卡死。

**修法**：在 `importFrom()` 里校验 `1000 ≤ iter ≤ 2000000`，超范围**直接拒绝**并给出明确
原因，而不是静默夹到上限 —— 静默夹会把"参数异常"伪装成"密码不正确"，反而更难排查。
上限留了 10 倍余量（当前 200000），未来版本提高强度仍可解。

## 二、文档与代码不符（已同步）

这两处不影响运行，但会误导后续维护，一起改掉：

1. **`core/Threads.java`**：IO 池注释写的是"4 个常驻、峰值 32、无界队列"，实际代码是
   `selfReleasing(2, 16, 30L)` + `SynchronousQueue` + `CallerRunsPolicy`。三处都对不上。
   已改成与代码一致的描述，并补上一条**容易忽略的耦合**：`Threads.io()` 会被 UI 线程直接
   调用，池满时那一次任务会落到 UI 线程上同步执行，所以 IO 池上限不能定得太小。
2. **`core/Detect.java`**：`PROBE_TIMEOUT_MS` 是 15000，注释论证的却是"8 秒的依据"。
   已把注释改成 15 秒并说明为什么从 8 秒放宽。

## 三、验证

- 全量构建通过（`./build.sh`，aapt2 → javac → d8 → apksigner），发布包
  `/v1/debug/*` 已确认关闭；
- 真机安装（ColorOS，Android 16）：无 `FATAL EXCEPTION`，11 个账号 / 316 个模型正常加载；
- 内置自检 13/13 通过（含新增的撇号回归项）；
- 反编译核对 `classes.dex`，两处修复的代码与字符串均已在发布包内
  （`looksLikeStringEnd`、`加密参数异常`）。

## 四、确认无需改动

以下都是**审过、确认当前实现正确**的点，记下来免得下次重复怀疑：

- **访问 Key 比较**：`Config.constantTimeEquals` 是定长时间实现（长度不同也走满循环），
  挡统计时间侧信道；`acceptsKey` 同时认 current + aliases。
- **并发槽释放**：`Limits.acquire` 的 `slot.release()` 在 `finally` 里，不会泄漏
  （泄漏会让账号被自己的并发上限永久挡住）。
- **分帧编码**：`ChunkedStream.flushChunk` 的 chunk 头 / 结尾 `0\r\n\r\n` 都正确，
  且写失败转 `ClientGone`（不记失败、不进熔断）。
- **配置落盘**：`writeAtomically` 是 tmp + fsync + rename，且写前挡"空配置覆盖有账号的磁盘"；
  另有 `gateway.accounts.json` 快照兜底。
- **请求体上限**：`Content-Length` 与 chunked 两条路**都**挡了（单块 + 累计），
  开局域网时的 OOM 面已封。
- **竞速句柄隔离**：`RaceCancels` 按场次开 bucket + `DEAD` 集合挡"迟到 bind 重建表"，
  并发竞速不会互相掐错。
- **探测限流**：`Detect.sweep` 提交前先抢信号量，避免 `SynchronousQueue` 直接拒绝，
  也避免 `CallerRunsPolicy` 让提交循环被阻塞。

## 五、已知结构性风险（本轮**故意不动**）

这三条在之前的记录里就有，本轮再次确认仍然存在。它们**不是 bug**，改动会影响产品行为，
需要先定策略再动：

1. **图片能力按账号判断，不按模型**：`Router.candidates` 用的是 `p.vision`（账号级开关），
   于是"账号里有能看图的模型"和"账号整体标了 vision=false"会互相打架。要改需要产品决定
   是"按模型"还是"按账号"为权威。
2. **9 个模型只有单账号、无兜底**：某些模型全库只有一条通道，该通道挂了就直接失败，
   虚拟模型也换不出去。
3. **`contextLimit` 全 0，不做本地上下文预检**：`Router` 里的长度预检代码是通的，
   但没有任何账号配了值，等于从来没生效。要开需要给账号批量填值 + 想清楚超限后的行为
   （截断还是换号）。
