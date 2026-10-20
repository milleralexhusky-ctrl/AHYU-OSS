# AHYU 4.49.0：写路径性能 + 参数保真

versionCode 642。

## 一、写响应慢 100 倍的根因

压测 `/v1/models`（21.7KB）时 p50 约 136ms，`/health`（2.4KB）约 21ms，
`/v1/capacity`（121B）约 2ms——时间几乎完全跟响应体大小线性增长。

分段计时后确认：**`out.write(body)` 本身花了 241ms**，head/flush 都是 0。
根因是 `CountingStream extends FilterOutputStream`；在 Android 上
`FilterOutputStream.write(byte[],off,len)` 会退化成逐字节转发，
21KB 变成两万多次小写入。

改成直接委托底层 `OutputStream`（不继承 FilterOutputStream）后：

| 接口 | 修复前 p50 | 修复后 p50 |
| --- | --- | --- |
| `/v1/models` | 136.1ms | **0.77ms** |
| `/health` | 21.6ms | **0.99ms** |
| `/v1/capacity` | 2.8ms | **1.18ms** |

150 次 keep-alive 请求、真机实测。

## 二、未知参数不再被吞掉（避免降智）

入口协议里没有单独建模的字段现在会进入 `Proto.Req.extraBody`，
**同协议出口原样并回**：

- OpenAI：`top_k`、`logprobs`、`top_logprobs`、`service_tier`、`metadata`、
  `store`、`n`、`user`、`modalities`、`audio`、`prediction` 等；
- Anthropic：`top_k`、`metadata`、`service_tier`、`container`、`mcp_servers`、
  `context_management` 等；
- Gemini：顶层 `safetySettings`、`cachedContent` 等，
  `generationConfig` 里的 `topK` / `seed` / `candidateCount` 等。

跨协议**不合并**：字段语义不同，硬塞只会 400。自检新增
「同协议未知参数透传」，并断言 OpenAI 的 `top_k` 不会发给 Anthropic。

## 三、keep-alive 收尾

第 200 条响应（`KEEPALIVE_MAX_REQUESTS`）开始明确回
`Connection: close`。以前只写 `Keep-Alive: max=200`，
忽略这个头的客户端会复用一条即将关闭的连接，下一条就 `Connection reset`。

## 验证

- 自检 **27/27**（新增未知参数透传）。
- 压测数据见上表；写路径分段计时确认修复。
- 4.48.0 的 IPv6、半截流检测、Realtime 空闲/分片测试继续通过。

## 未做

- `logprobs` 等字段虽然转发给上游，但响应侧仍按 canonical 只取 `choices[0]`，
  多 choice / logprobs 的客户端拿不到完整体；要彻底保真需要同协议原始透传。
- 跨协议 `top_k` 没有等价翻译；按协议语义只透传同协议。
