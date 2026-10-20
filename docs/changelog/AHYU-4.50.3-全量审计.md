# AHYU 4.50.3：全量审计修复

versionCode 646。

这轮按三个方向做了只读审计：HTTP 服务与资源管理、协议转换与最近改动、配置与密钥安全。
下面只列**已修复并验证**的项；没修的单独放在最后，不假装做完了。

## 一、认证、跨源与连接生命周期

- **浏览器跨源防护**：没设 API Key 时，`Origin` 为外部 http(s) 或 `null`
  （sandbox iframe / data: / file:）的请求直接 403；回环、`*.localhost`、
  原生客户端（不带 Origin）不受影响。Realtime 的 WebSocket 握手同样检查。
  以前默认无 Key + `Access-Control-Allow-Origin: *`，任意网页都能直接消耗账号额度。
- **stop() 关闭活跃连接**：记录所有已建立 socket，停止时主动关闭。
  以前只关监听 socket，keep-alive 连接还能用**旧 Config** 继续请求
  （关局域网 / 换 Key 后旧策略仍生效）。
- **keep-alive 循环检查 running**：停止后不再处理下一条请求。
- **OAuth 回调**：
  - 只接受回环来源（监听仍兼容 localhost 解析成 ::1，但非回环连接直接丢弃）；
  - 每条 accepted socket 设 10 秒读超时，慢连接不再让登录永久挂起；
  - 请求行最多 8KB；
  - `error` 回调也必须带正确的 state，不能再用 `?error=access_denied` 打断登录；
  - **Antigravity 加 PKCE（S256）**：code 被本机别的应用截走也换不了 token。
- **写客户端失败转 ClientGone**：非流式响应写一半客户端断开时，不再记账号失败、
  不再换号重发一次完整 completion。

## 二、资源上限（防内存耗尽）

| 位置 | 以前的洞 | 现在 |
| --- | --- | --- |
| 请求行/头 | 超 64KB 只截断不报错，剩余字节被当成下一请求 | 超限直接断开，头区未以空行结束也拒绝 |
| 请求头总量 | 可发无限条合法长度头 | 最多 256 条 / 256KB |
| chunked | 非法 size 直接 break，连接错位 | 非法/负数/截断/坏 CRLF 一律抛错关闭 |
| Content-Length | 负数/非数字按 0、重复 CL 后者覆盖、TE+CL 并存 | 必须唯一、纯十进制；TE 只认 `chunked`；TE+CL 拒绝 |
| 上游响应体 | `readAll` 无上限 | 单响应最多 32MB |
| Realtime | 单帧 16MB、分片/会话消息无总上限 | 单帧 2MB、消息 4MB、500 条/2MB 上下文 |
| 每来源连接表 | 计数归零后表项永不删除 | 归零即删 |
| 调试抓包 | 12 份 8MB body + 同步写日志 | 每份截到 64KB |
| 备份导入 | 先整份读入再检查大小 | 边读边限 8MB |

## 三、路由与失败语义

- **坏模型/坏请求不再记 Router 失败**：`recordDirectOutcome` 与虚拟选路都先看
  `FailScope`；`MODEL_BAD` / `MODEL_ACCESS` / `REQUEST` 只作废半开试探，
  不累加 `fail/streak`，不会把健康账号打进 60s-600s 冷却。
- **轮换不再因一个账号的 404 封杀整个模型**：`MODEL_ACCESS` 只跳过当前候选，
  只有 `MODEL_BAD` 才按模型排除。纯调用仍可换同模型的其它账号确认权限。
- **截断导致的坏工具参数按请求问题处理**：`BadToolCall` 带 `finish_reason`，
  `length` 截断不再把模型标成"不会用工具"。
- **confirmedBlock 看全部候选**：以前只看前 `tries`（2-4）个，后面的可用账号
  会被"全部被限流"误伤。
- **后台巡检改走进程内 probeDirect**：不再占本机 HTTP 连接线程，
  自己连接池满返回的 503 不会被当成"模型坏了"。
- **probeOne 空响应体不再产生空原因**：避免空字符串写进 badModels。

## 四、协议保真

- **raw 换号残留**：Streamer 增加 `resetAttempt()`，换号前清掉上一个候选压着的
  role/音频分片，不会把两个上游的流拼进同一条响应。
- **`[DONE]` 之后不再换号重发**：`committed()` 计入 `rawEnded`；
  `rawOpenAiStream` 在收到 finish_reason/[DONE] 后忽略后续 I/O 异常。
- **只有 role 的空流**：`rawDone()` 先冲刷 pending，不再只发一个 `[DONE]`。
- **音频/拒答算实质内容**：`substantive()` 认 `delta.audio` / `delta.refusal`，
  不再整段压进 pending。
- **带工具时去掉 n/logprobs/音频字段**：不再出现"上游按 n 份计费、客户端只收到
  一个 choice"；日志里明确记录本次降级。
- **top_k 入口补齐**：Ollama `options.top_k`、Responses 的
  `temperature/top_p/top_k`、旧版 `/v1/completions` 的 `top_k` 都进入 canonical。
- **raw 回退可观测**：渠道不支持原样透传时写一条明确日志。

## 五、密钥、日志与配置

- **日志入口统一脱敏**：`Log.scrub()` 处理 URL/query 里的
  `code/state/refreshToken/userJwt/...`、JSON token 字段、Bearer、JWT、
  `GOCSPX-/ghp_/sk-/AIza`；日志落盘与诊断包因此不再带凭据。
  业务码（如 `{"code":14018}`）不会被误脱敏。
- **删掉危险的原始日志**：Trae 回调内容、WorkBuddy token 响应体、
  网页 Authorization 前缀、打不开的外部 URL 都不再写完整内容。
- **BuildSecrets 移出源码树**：Antigravity secret 现在生成到 `out/gen`
  （未跟踪），不再 `sed` 改写被跟踪的 `BuildSecrets.java`。
  SIGKILL/断电不会把密钥留在工作区，也不会被 `sync-public.sh` 带走。
- **Keystore 解不开时不再写空凭据**：`Secret.restoreUnreadable()` 把解不开的
  `enc:v1:` 原值保留在内存 JSON 里，后续保存原样写回；以前一次自动保存就能把
  所有 refresh_token 永久清空。
- **明文统计覆盖 `apiKeyAliases`**：Keystore 失败时别名也会被计数并告警。
- **备份导入强制关闭局域网**：备份是外部输入，恢复后 `lanAccess=false`，
  需要时再手动打开。
- **WebView 凭据清理**：登录页销毁时清空内存里的明文 Authorization。
- **刷新锁改用 `p.id`**：不再用可改、可能重名的 `p.name`，避免重复账号并发刷新
  轮换型 refresh_token。

## 六、验证

- 自检 **33/33**（新增 HTTP 上限、浏览器跨源、日志脱敏，以及 raw 换号残留/
  空流/音频四项断言）。
- 真机 Origin 实测：无 Origin 200、`http://127.0.0.1:3000` 200、
  `https://evil.example` 403、`Origin: null` 403。
- 假上游实测：`n=2 + logprobs` 流式/非流式仍拿到两个 choice 与完整 logprobs；
  带 tools 的同类请求，上游 body 里已无 `n/logprobs/top_logprobs`。
- 真实渠道 `deepseek-v4.1-flash` 普通请求正常返回。
- 构建验证：`BuildSecrets.java` 不再出现在 `src/`，只生成在 `out/gen/`；
  APK 内仍包含 Antigravity 凭据（私库构建）。

## 七、明确未修 / 残余风险

- **私库里的 keystore、口令、GH_TOKEN、Antigravity secret 没有移除**：
  这是用户明确要求的"私库是保险库"设计，不是遗漏。前提是 `AHYU` 必须保持
  private；一旦私库被共享或转 public，应按 `keystore` 泄露处理（换密钥需要用户拍板，
  会导致老用户必须重装）。公库 `AHYU-OSS` 已核对不含这些内容。
- **Trae 的 raw 回调仍没有 nonce/state**：已限制回环+超时+长度，但本机恶意应用
  在登录窗口内仍可能抢先注入自己的账号。加 nonce 需要改上游回调 URL，
  没有真机登录验证前不动，避免把正常 Trae 登录改坏。
- **旧客户端 Key 别名没有撤销 UI**：改 Key 后旧 Key 仍有效，需要时可在设置页
  增加"清空旧 Key"入口（本轮未做）。
- **备份里的 provider baseUrl/API Key 仍被信任**：只强制关了局域网；
  导入他人备份前仍应确认来源。
- **ProviderEditor 的凭据文件导入还没有大小上限**。
- **图片 URL 的 DNS rebinding 残余风险**：已有逐跳公网校验，但解析后重连仍存在
  TOCTOU；彻底修复需要自己解析并连 IP，改动较大。
- **带工具的 logprobs 仍不保真**（4.50.1 起已记录）。
