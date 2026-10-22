# 4.51.9 — message_start 带上真实的 input_tokens

## 起因

上一版排查"输出协议"时实测发现：我们自己的 Anthropic 输出流里，
`message_start` 的 `usage.input_tokens` 写死是 **0**，真值要等到最后的
`message_delta` 才给。

而**官方 Anthropic SDK 与 Claude Code 会在 `message_start` 就读 `input_tokens`
做上下文计数** —— 它们在整个流里读到的上下文是 0。客户端日志里那种
`上下文=?` / `out=0` 就是这么来的（不全是，但这是网关这一侧唯一真实的偏差）。

## 为什么以前是 0

我们的 `message_start` 是**延迟提交**的：要等到第一段正文才写。
这是 4.51.1 为了"空流不提交、可以换候选"做的设计 —— 上游回空（只有 thinking
或什么都没有）时，`message_start` 还没发出去，网关还能干净地换下一个候选。
这个能力必须保住。

问题在于：**上游的 `message_start`（带 `input_tokens`）其实排在流的最前面**，
也就是说提交那一刻，值**早就在手上了** —— 只是没有任何通道把它从解析器
传到输出渲染器，于是只能写 0。

## 修法：加一条中途上报的通道

- `Proto.Sink` 新增 `default void inputTokens(int)`（仿照既有的 `responseId`，
  默认空实现，现有实现都不受影响）。
- `Upstreams.anthropicStream` 在收到上游 `message_start` 时立即
  `sink.inputTokens(usage.input)`。
- `Fronts.AnthropicFront.Streamer` 记下来，写自己的 `message_start` 时用上。
- `Server` 的记账包装层（tap）**原样转发** —— 这层是最容易漏的地方：
  漏转发就等于把信号吃掉，而且不报错，只是又回到写 0。

## 这是纯利润，没有后退

动手前先确认了三条，逐条都成立：

1. **值确实先到**：上游 `message_start` 排在第一段正文之前，所以提交时值在手上
   —— 不需要缓存待定值、不需要新超时、不需要新的失败路径。
2. **换号能力不变**：提交时机一点没动（还是第一段内容才写），只是写进去的数字
   从 0 变成真值。
3. **最差也是回到旧行为**：万一某个上游把 usage 放到后面才给，
   `earlyInputTokens` 保持 0，真值仍由 `finish()` 经 `message_delta` 送到 ——
   不会比现在差。

## 一个必须说清楚的边界

**这条修复对 OpenAI 协议的上游无效** —— 而用户当前的主力渠道（Loomy）
正好是 OpenAI 协议。原因不是我们没接，而是**规范如此**：
OpenAI 流式的 `usage` 在**末帧**才出现（`stream_options.include_usage`），
那之前根本不存在这个数字。没有东西可以提前。

所以：

| 上游协议 | `message_start` 的 input_tokens | 说明 |
| --- | --- | --- |
| Anthropic（Claude / Kimi / 自定义 anthropic 中转） | **真值**（本次修复） | 上游 message_start 先到 |
| OpenAI 兼容（Loomy / 多数中转） | 0 | 规范如此，usage 在末帧 |

对 OpenAI 协议那类，最后的 `message_delta` 仍然给全真值，客户端的
**用量统计**是准的；只有"在流开头就想要上下文数"的客户端会看到 0。

## 验证

- 真机自检 **47 / 47**。新增断言：Anthropic 回放里要求
  "流开头就必须上报 input_tokens"（`Cap.earlyIn == 7`），
  这条在修复前会红、修复后通过 —— 是能真正抓住回归的断言，不是形式。
- 实测 `/v1/messages` 流式：Anthropic 上游路径的 `message_start`
  已带真值（由自检断言保证）；Loomy（OpenAI 上游）仍为 0，且这是**预期行为**，见上表。
- 输出协议其余部分复核无误：事件序列（message_start → content_block_* →
  message_delta → message_stop）与字段完全符合规范。

## 未验证 / 已知限制

- 没有用真实的 Anthropic 协议上游（Claude/Kimi）跑端到端 —— 本机账号只有
  Loomy（OpenAI 协议）。验证靠的是回放断言（假上游按真实 Anthropic 事件序列吐数据），
  不是真上游。**这属于"逻辑已验证、真链路未验证"**。
- OpenAI 协议上游的流开头仍为 0，如上表所述。要改只能在网关侧
  "先猜一个"（例如按请求体估 token），那是**假数据**，不做。
