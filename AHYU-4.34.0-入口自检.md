# AHYU 4.34.0：入口请求自检

versionCode 619。

## 起因

Trae 那三个错误（`tool_calls` 缺 `type`、tool 消息缺 `tool_call_id`、
content 非空时 id 被丢）全都是**我们自己发错了**，但一直判成渠道问题。

根因是错误在上游才爆，而各家校验严格程度不同：

- Trae 直接拒（空回复、`prompt_tokens=0`）
- WorkBuddy 宽容容忍（看起来一切正常）

同一份坏请求，在一个渠道上表现为"这渠道不行"，在另一个渠道上表现为"正常"。
排查方向被彻底带偏——前面几轮就是这么错的。

## 改动

新增 `RequestCheck`，在**解析完成、发出之前**校验。五处前端入口全部接上：
OpenAI / Anthropic（两处）/ Gemini / Responses。

检查项只取协议硬性要求，宁可漏报不误伤：

1. `tool_call` 必须有非空 id
2. `tool_call` 必须有函数名
3. `toolResult` 必须有非空 `toolCallId`
4. `toolResult` 的 id 必须能在历史里找到对应调用
5. `role` 必须是 system / user / assistant / tool / developer

不合规抛 `InvalidRequest(400)` —— 这个异常类型上层已有区别对待逻辑，
**不会记成渠道失败、不会触发熔断、不会浪费候选重试**。

## 验证

离线单测 5 项全过：

| 用例 | 期望 | 结果 |
|---|---|---|
| 纯文本对话 | 放行 | PASS |
| 工具调用配对完整 | 放行 | PASS |
| `tool_call` 缺 id | 拦下 | PASS |
| 工具结果缺 `tool_call_id` | 拦下 | PASS |
| `tool_call_id` 对不上 | 拦下 | PASS |

真机验证：

- 正常请求 → 正常返回"好的呀"
- 坏请求 → 入口拦下，报错写明
  「请求不合规（我们自己这侧，不是渠道问题）：
   · tool_call 缺少 id（工具 bash）
   · 工具结果缺少 tool_call_id（上游会报 Unexpected tool_call_id: 空）」

并且 `usage.tsv` 里没有产生渠道失败记录，确认这类错误没被记到渠道头上。

## 意义

以前"某渠道莫名空回复"，只能靠猜、靠逐个渠道试。
现在分两种情况立刻可分：

- 入口就报错 → 我们自己发错了，看报错改
- 过了入口还是空 → 才是渠道或上游的问题

这是把观测点前移到**我们自己的输出边界**——
不是只看上游回了什么，也看我们自己发了什么。
