# 4.56.0 — 工具结果跨协议出站：Anthropic 的 tool_result 被拍成了普通用户文本

## 起因

翻真机日志时发现一个量级明显不对的现象：

```
18:07  [WARN] 工具配对整理：assistant 的 tool_calls 有缺结果的（保留，不删）   ← 单分钟 1261 条
```

同一份日志里这类告警累计 **20514 条**，占全部 WARN 的 92%。同时期还有 12 次
上游 `400 {"code":11148,"msg":"tool calls and tool results do not match"}`。

`11148` 是"assistant 声明调了工具，后面却找不到对应结果"。而网关自己那条告警
说的是同一件事。两边指向同一个地方：**出站的 messages 里，工具结果不见了。**

## 根因：跨协议出站时 tool_result 没有变成 role:tool

三个入口把工具结果放在完全不同的位置：

| 入口 | 工具结果在哪 |
| --- | --- |
| OpenAI | 独立的 `{"role":"tool","tool_call_id":…}` 消息 |
| Anthropic | **user 消息 content 数组里的 `tool_result` 块** |
| Gemini | **user 的 parts 里的 `functionResponse`** |

`Proto.parseOpenAiContent` 把后两者都正确解析成了 `Part.toolResult`，id 也保住了
（4.33.4 修过）。**丢在下一步**：`Upstreams.appendMessages` 只对
`"tool".equals(m.role)` 做"一条结果一条 tool 消息"的拆分，
而 Anthropic/Gemini 解析出来的消息 role 是 **user**，于是走进
`openAiMessage(m)` 的普通分支 —— 那里 `textBuffer()` 把工具结果正文拼进了
`content`，`role` 仍是 `user`，`tool_call_id` 整个消失。

也就是说，Anthropic 客户端的一次工具调用，出站变成这样（真机抓包，修复前）：

```json
{"role":"assistant","content":"","tool_calls":[{"id":"toolu_1", …}]},
{"role":"user","content":"a.txt"}                    ← 工具结果被当成了用户发言
```

上游看到的是"声明调了 bash、结果却没人回"，于是：
- 严格的渠道（WorkBuddy）直接 `400 11148`；
- 宽松的渠道不报错，但模型认为工具没执行，**一遍遍重发同样的调用**；
- 网关自己的 `normalizeToolPairs` 每次都判"缺结果"，刷出那 20514 条告警。

## 改法

`Upstreams.appendMessages` 增加一条分支：**消息 role 不是 tool、但 parts 里有
toolResult 时**，先按顺序把每个结果发成独立的 `role:"tool"` + `tool_call_id`
消息，再把同一轮剩下的正文/图片作为普通消息跟在后边（顺序不能反 ——
tool 消息必须紧跟在声明 `tool_calls` 的那条 assistant 之后）。

修好后同一份请求的出站（真机抓包，修复后）：

```json
{"role":"assistant","content":"","tool_calls":[{"id":"toolu_1", …}]},
{"role":"tool","content":"a.txt","tool_call_id":"toolu_1"},
{"role":"user","content":"继续"}
```

三处同协议路径（OpenAI→OpenAI、Anthropic→Anthropic、Gemini→Gemini）不受影响：
前者本来就是 `role:"tool"`，后两者走各自的出站构造器，不经过这里。

## 验证

**真机抓包对照**（`/v1/debug/capture`，同一份请求体）：

| | 出站消息 |
| --- | --- |
| 修复前 | `{"role":"user","content":"a.txt"}` |
| 修复后 | `{"role":"tool","content":"a.txt","tool_call_id":"toolu_1"}` |

**自检新增断言（第 60 项）**「工具结果跨协议出站：Anthropic/Gemini 的 user 消息要变成 role:tool」：

- 修复前真机自检 **红**：`有 1/60 项失败 —— … Anthropic 的工具结果出站没变成 role:tool`；
- 修复后真机自检 **60/60 通过**。

这条断言同时覆盖 Gemini 形态，并额外钉住"OpenAI 入口本来就是 role:tool，
不许被这次改动改坏"的反向回归。

**日志侧**：修复后同一份带工具历史的请求，`工具配对整理：assistant 的
tool_calls 有缺结果的` 归零。

## 未做 / 边界（重要）

- **没有端到端跑通一次真实的工具循环**。账号当下全部撞在额度/频率上
  （`14018 额度已用尽`、`6004 使用量已超出频率限制`），上游一律 429，
  拿不到一次完整的多轮工具对话。所以本版的证据是
  **抓包对照 + 自检断言 + 日志计数**，不是"真机跑通一轮工具调用"——
  如实记录，别当成实测确认。
- 只改**出站方向（→ OpenAI 家族上游）**。反过来（Anthropic 入 → Anthropic 出）
  本来就是对的，没动。
- 提示词缓存：工具结果现在作为独立 tool 消息发出，它与 assistant 之间的
  边界对上游前缀匹配是否更有利，本版没有测量。

## 产物

- `AHYU-4.56.0.apk`（versionCode 670）
