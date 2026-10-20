# AHYU 4.50.0：响应保真（logprobs / 多 choice 原样回传）

versionCode 643。

## 一、为什么还要做一层"响应侧"透传

4.49.0 把入口里没建模的字段（`n`、`logprobs`、`top_logprobs`、`service_tier`…）
原样发给了上游，请求侧已经不缺字段。但响应侧仍要经过 canonical：

- 一个上游分片被拆成 `text / thinking / toolCall`；
- 回给客户端时重新拼成"一个 choice、一段正文、一次 finish"；
- `choices[1..]`、`logprobs`、`system_fingerprint`、音频分片在重拼时**没有位置可放**。

所以 `n>1` 的客户端只拿到第一个候选，拿 `logprobs` 的客户端拿到的是"正文有了、
概率没了"的响应。这不是报错，是静默降级——正是 4.49.0 记在「未做」里的那一项。

## 二、做法：按请求特性开启，不改变普通流量

只有同时满足下面条件的请求才走原样透传：

- 入口是 OpenAI Chat（`/v1/chat/completions`）；
- 客户端明确要了 canonical 表达不了的东西：`n>1`、`logprobs` / `top_logprobs`、
  `audio` 输出；
- 没有工具（工具参数修复、坏调用换号是网关的安全网）；
- 直接模型（虚拟模型 `auto*` 仍走原来的选路/竞速）。

上游侧也只对**通用 OpenAI 兼容渠道**（自定义接口）生效；WorkBuddy、Trae、Claude、
Gemini 这些私有协议适配器完全不受影响，行为和 4.49.0 一样。

打开后：

| 环节 | 行为 |
| --- | --- |
| 请求体 | 不变，仍是同一份 canonical → OpenAI 渲染结果（含 `n` / `logprobs`） |
| 流式响应 | 上游每个 SSE data 块原样转出，只把 `model` 改写回客户端请求的名字 |
| 非流式响应 | 上游 `stream:false` 的完整 JSON 原样返回，同样改写 `model` |
| 多 choice | `choices[0..n-1]` 全部保留，`index` 不动 |
| logprobs | `logprobs` / `top_logprobs` / `system_fingerprint` 保留 |
| 用量统计 | 仍从分片里解析 `usage` 与响应 `id`，写进 `usage.tsv` |
| 断流检测 | 没等到 `finish_reason` / `[DONE]` 就 EOF，仍判连接中断并补错误帧 |
| 失败换号 | 第一个**实质分片**写出前不提交，换号照常；role 空壳先压住不写 |
| 自报家门清理 | `n>1` / 音频请求照常清理；**要 logprobs 时不清理**——删字会让 token 与概率错位 |

`role` 空壳的压后处理是这次专门补的：上游经常先发一个只有 `{"role":"assistant"}`
的分片，再发正文。如果直接转出去，客户端就"已经看到这条流开始了"，
此时上游失败就没法安静换号。现在这类分片先留在内存，等第一个有正文/工具/结束原因
的分片到了再一起写出；在那之前失败，客户端一个字节都收不到。

## 三、验证

### 自检

`Selftest` 新增「响应保真透传」一项，**28/28 通过**（真机诊断包 `selftest.txt`）：

- 只有 role 的分片不会提前写出；
- 两个 choice、`index:1`、`logprobs` 原样出现；
- `model` 被改写为客户端请求名；
- 要 logprobs 时正文一个字符都不删；
- `[DONE]` 恰好一次，且不补 canonical 收尾分片。

### 假上游 A/B（字节级）

用本地假上游返回"两个 choice + 每 token logprobs + system_fingerprint + usage"：

| 场景 | 结果 |
| --- | --- |
| 流式 `n=2, logprobs` | 两个 choice、logprobs、`system_fingerprint`、usage 分片全部到达；`model=fake-model`；`[DONE]` 一次 |
| 非流式 `n=2, logprobs` | 完整响应体 2 个 choice + logprobs；`model` 改写为请求名 |
| 普通流式（不带 n/logprobs） | 仍走 canonical：`chatcmpl-…`、单 choice、无 logprobs —— 与 4.49.0 一致 |
| 普通非流式 | 仍走上游流式 + canonical 汇总，单 choice —— 与 4.49.0 一致 |
| 上游 200 + 首块 error | 不写任何正文，补 `error` + `[DONE]` |
| 上游吐了正文后断流 | 已写内容保留，补 `error` + `[DONE]`（半截流可被客户端识别） |
| 点名坏账号、同名好账号兜底 | 坏账号连接失败后**安静换号**，客户端只收到好账号的完整流 |

请求侧同时解包验证：假上游收到的 body 里确有 `n=2`、`logprobs=true`、
`top_logprobs=2`；普通请求里没有这些字段。

### 延迟

同一假上游、同一台手机、30 次 keep-alive 请求（假上游关闭 Nagle，排除 delayed ACK 干扰）：

| 路径 | p50 | p95 |
| --- | --- | --- |
| canonical 流式 | 12.62ms | 19.43ms |
| 保真 流式 | **11.98ms** | **14.37ms** |
| canonical 非流式 | 15.19ms | 19.41ms |
| 保真 非流式 | **10.72ms** | **13.92ms** |

保真路径少一次"拆 canonical 再重拼"，在这组测量里没有变慢。注：这是本机回环 +
假上游的数字，只用于确认"没有引入额外一跳"，不代表公网上游的绝对延迟。

### 真实渠道

3 个真实账号（WorkBuddy / Trae / Loomy）在调试包上各跑一次普通请求，
`deepseek-v4.1-flash` 正常返回 `OK`，走的是未改动的 canonical 路径。

期间还修了一个观测问题：非流式保真响应原先没有回填 `seenTextHint`，
用量报表会把正常回答标成 `empty`；现在与流式路径一样统计正文，
复测同一请求标记为 `ok`。

## 四、未做

- 保真透传只接了通用 OpenAI 兼容渠道；DeepSeek / Kimi / xAI 等自带适配器仍走
  canonical。它们的 `n` / `logprobs` 支持情况需要逐家抓包确认，不能靠猜。
- 带工具的请求不开启保真：`logprobs` 与工具调用同时出现的场景极少，
  而工具参数修复/换号更重要。
- 音频输出只是"不丢字段"（`audio` 分片原样回传），没有额外做音频格式校验。
- 跨协议 `top_k` 等价翻译仍未做（4.49.0 已记录）。
