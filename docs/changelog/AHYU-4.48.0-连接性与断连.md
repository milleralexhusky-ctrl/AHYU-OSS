# AHYU 4.48.0：连接性与断连体验

versionCode 641。

## 一、上游半截流不再当成功

以前上游在 `finish_reason` / `[DONE]` / `message_stop` / `finishReason` 之前
断开，解析器会当成正常结束：客户端收到一段没有结束标记的半截回答，网关还记成功。

现在三种流式解析器都记录"终止标记"：

| 协议 | 终止标记 |
| --- | --- |
| OpenAI | `finish_reason` 或 `data: [DONE]` |
| Anthropic | `message_stop` 或 `stop_reason` |
| Gemini | `finishReason` |

缺了就抛 `IOException`：

- 还没吐内容 → 上层正常换号重试；
- 已经吐了内容 → 写一条 SSE 错误帧 + `[DONE]`，客户端知道这轮被截断，
  而不是把半截答案当完整结果。

## 二、IPv6 回环

本机模式同时监听 `127.0.0.1` 和 `::1`。客户端把 `localhost` 解析成 IPv6 时
不再"地址没错但连不上"；IPv6 绑定失败不影响 IPv4 主监听。局域网模式不绑
`::`，避免和 `0.0.0.0` 的 dual-stack 冲突。

`/health` 新增实时字段：`connections`、`capacity`、`ipv6_loopback`
（这些不进缓存，配置缓存仍保留）。

## 三、Realtime WebSocket 连接体验

- 升级后取消 HTTP 的 60 秒空闲超时（否则空闲的 Realtime 连接会被误杀）；
- 服务端每 30 秒发 ping，180 秒收不到任何客户端帧才关闭；
- 支持分片文本帧（continuation）；
- 连接关闭/异常时停止 ping 线程。

## 验证

| 场景 | 结果 |
| --- | --- |
| 假上游发 `half` 后直接断开（流式） | 客户端收到正文 + `{"error":"upstream stream ended before finish_reason"}` + `[DONE]` |
| 同样情况（非流式） | 不再记成功，返回错误 |
| `curl http://[::1]:8321/health` | 200，`ipv6_loopback:true` |
| Realtime 空闲 65 秒后再发事件 | 连接仍在，收到第二个 `session.updated` |
| 分片 WebSocket 帧（FIN=0 + continuation） | 正确拼成 `session.update` → `session.updated` |

真机自检 26/26；测试账号与假上游已清理。

## 未做

- 上游首 token 后的长时间静默仍受 Http 读超时约束（300 秒）；没有单独的可配置流空闲阈值。
- Realtime 音频、Vector stores、异步 run 队列。
