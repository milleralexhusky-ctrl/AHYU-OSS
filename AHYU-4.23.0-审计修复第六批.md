# AHYU 4.23.0 —— 审计修复第六批（1 高 + 6 中）

以 4.21.9 为基线逐条核实。**H1 与 [中] 1–6 全部属实，条条对源码验过。**

（[中] 7–10 是上一批 4.22.0 已修的四条，本次不重复。）

## [高] H1 —— handleGemini 把字面量 "auto" 发给了上游

### 确认

`Server.java` handleGemini：

```java
Config.Provider p = pick(effective);      // 用对了
String um = upstreamModel(p, model);      // ← 这里用的是原始名
```

`upstreamModel`（1994 行）对不含 `/` 的名字**原样返回**，于是 `"auto"` 直接透传。

### 为什么上次没测出来（审计的分析完全正确）

- 我当时测的是 `→ WorkBuddy 1/hy3-x`。WorkBuddy 把 model 塞进 **JSON body**，
  上游容忍 `"auto"`，所以返回 200。
- 但原生 Gemini 上游是**拼 URL** 的（`Upstreams.java:910`）：
  ```java
  url(base, "v1beta/models/" + model + ":streamGenerateContent")
  ```
  拼出 `models/auto:streamGenerateContent` → **上游 404**。
- 结论：**Gemini 前端 + 原生 Gemini 上游 + 虚拟模型 = 100% 全灭**，原测试覆盖不到。

### 修法

```java
String um = upstreamModel(p, effective);
```

### 最小复现验证（`/tmp/repro523/GeminiUrl.java`）

```
修复前: upstreamModel(p, "auto") → "auto"
        URL: .../v1beta/models/auto:streamGenerateContent  → 含 /models/auto: 是 → 404
修复后: upstreamModel(p, "gemini-2.5-flash") → "gemini-2.5-flash"
        URL: .../v1beta/models/gemini-2.5-flash:streamGenerateContent → 正常
RESULT: PASS
```

### 备注：当前配置走不到这条路径

本机配置里**没有原生 Gemini 通道**（`type=gemini` 为 0），现有 7 种类型中也没有。
所以这条目前是"潜伏"状态——**一旦加 Google Gemini 账号，那条路径立刻全灭**。
真机回归只能验到"Gemini 前端 + 虚拟模型"能通（200），验不到原生上游那一跳。

## [中] 1 —— pick() 直连路径完全绕过熔断

### 确认

```java
private Config.Provider pick(String model) {
    ...
    if (p.enabled && p.matches(model)) matches.add(p);   // 只看 enabled + matches
    ...
    return matches.get(i);                                // 轮询挑一个
}
```

对比虚拟模型路径 `Router.candidates()`：那里**认真查熔断三态**
（`cooldownUntil` / `throttleUntil` / `openCount` / `probingAt`）。

而 `recordFail` 的调用点全在竞速相关代码里（2487/2598/2634），
**直连路径从来不记失败** → 熔断对"客户端直接写具体模型名"这条路径**完全不存在**。

### 修法

新增 `Router.available(key)`（查 cooldownUntil / throttleUntil），`pick()` 优先挑可用的：

```java
List<Config.Provider> usable = new ArrayList<>();
for (Config.Provider p : matches) {
    if (Router.available(p.name + "/" + model)) usable.add(p);
}
if (!usable.isEmpty()) matches = usable;
else Log.w("直连路径：模型 " + model + " 的所有候选都在冷却/限流回避中，仍按原样放行一个");
```

**保底是刻意的**：如果全在冷却，仍然返回一个，否则整个服务会变成 404 ——
那比偶尔撞一次熔断更糟。

## [中] 2 —— auto-race token 回填漏两处 CURRENT.get()

### 确认

**代码自己的注释就写着**（2226 行）：
> 竞速（auto-race）里 serve 跑在 RACE_POOL 线程上，那里的 ThreadLocal 从来没被 set 过

`serve(...)` 有一个「显式传 Conn」的重载（2220 行已用对），
但 2271、2288 两处**还在 `CURRENT.get()`** → 竞速线程里恒 null → **token 用量永远记 0**。

### 修法

两处都改用显式传入的 `cur`。

## [中] 3 —— 客户端中断记成上游失败

### 确认

`serve()` 的兜底 `catch (Exception e)` **不分异常类型**，一律写 `p.lastError` 并 rethrow，
上层再 `recordFail`。客户端断开（Broken pipe / Connection reset）同样落进这里。

移动网络闪断、用户取消、切后台 → 三次就把健康账号打进 60 秒→10 分钟熔断。

### 修法

新建 `ClientGone extends IOException`，在**写客户端失败的那一层**识别
（`ChunkedStream.flushChunk` 里 `out.write` 抛 IOException 时转换）：

```java
} catch (java.io.IOException e) {
    throw new ClientGone("写客户端失败：" + e.getMessage());
}
```

上层：`serve()` 里单独 catch（**不写 lastError**，否则账号页显示假故障），
三处 `recordFail` 前也排除。

## [中] 4 —— 上游 4xx 记成提供商失败（且被 failover 放大）

### 确认

`Http.call`(194) / `Http.stream`(278) 的分类条件是
`throttled(code) || code == 401 || 402 || 403`，
而 `upstreamError`(92) **多一个 `quota(body)`**。对比清楚：

```java
// upstreamError（Cline/Xai 走这条）
if (code == 429 || code == 402 || code == 401 || code == 403
        || throttled(code) || quota(body)) { ... }
```

400 这类多半是**客户端请求本身**的问题（参数不合法、模型名不存在），
换哪个账号都一样，却会被记成"这个通道坏了"；
而虚拟模型的 failover 会对每个候选各记一次 → **一次坏请求打残整组**。

### 修法

新增 `InvalidRequest extends IOException`；`Http.call` / `Http.stream`
在 4xx（非限流/额度/凭证）时抛它；三处 `recordFail` 前排除（不记 failstreak，
但仍把错误如实返回客户端）。

## [中] 5 —— provider id 100 秒一轮回

### 确认，且范围比审计说的更广

**11 处**（不是"DeepSeek 等"）：

```java
target.id = "xx" + Math.abs(System.currentTimeMillis() % 100000);
```

毫秒对 100000 取模，**周期恰好 100 秒**。而且 Claude 和 Cline **都用 "cl" 前缀**，
跨类型也会撞。

后果（审计指出的对）：`WbTaskChores` 的账号级重入 guard 用 `p.id` 做 key，
两个不同账号 id 相同 → **误判 already running → 签到/任务奖励静默丢失**。

### 修法

`Config` 里加统一生成器：

```java
private static final AtomicLong ID_SEQ = new AtomicLong(0);
public static String newProviderId(String prefix) {
    long n = ID_SEQ.incrementAndGet();
    long tail = System.currentTimeMillis() % 10000L;
    return prefix + Long.toString(tail, 36) + Long.toString(n, 36);
}
```

同一进程内序列号保证不重复。11 处全部替换，并把 Claude 的前缀从 `"cl"` 改成 `"cd"`，
与 Cline 区分。核对后 **11 个前缀全部唯一**（ag/cd/cl/cx/db/ds/km/lo/tr/wb/xg）。

## [中] 6 —— Http.stream/call 缺 quota(body) 检查

与 [中] 4 同一处，一并修了（`Http.call` 与 `Http.stream` 的主路径都补上 `|| quota(...)`）。

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | 手动分步构建（aapt2 compile/link → javac 87 文件 → d8 → 打包 → 签名） |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 532 / versionName 4.23.0 |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |
| 三协议 auto | Gemini 200 / Anthropic 200 / OpenAI 200 |
| H1 最小复现 | PASS（修复前拼 /models/auto → 404，修复后正常） |

### 反编译核对（jadx）

- H1：`upstreamModel(providerPick, strResolveVirtual)` —— 用的是解析结果，不再是 "auto"
- ①1：`Router.available(...)` 在 pick 里
- ①3：`ClientGone` 6 处
- ①4：`instanceof InvalidRequest` 3 处

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 532 / 4.23.0）
- 副本 `/sdcard/Download/cpa/AHYU-4.23.0-com.ahyu.apk`

## 未实测 / 遗留

- **H1 的原生 Gemini 那一跳**验不到（本机无 `type=gemini` 通道），
  只能用最小复现证因果；建议你加了 Google Gemini 账号后再跑一次
- [中] 1 的保底行为（全冷却时仍放行）是有意设计，未构造全冷却场景实测
- [中] 3 的客户端断开场景未实测（需要构造断网中断）
- **保活那条尾巴仍待真机确认**（4.21.7 遗留）：重启手机或等一次真实 15 分钟周期，
  logcat 里找 `保活任务：检测到网关未运行，重新拉起` 且没有
  `ForegroundServiceStartNotAllowedException`
- [低] 约 30 条（[DONE] 缺失五处、Limits 非原子、半开试探自锁、recordOk 洗暂避、
  Stat 字段锁外读写、auto-race 名不副实等）未动
