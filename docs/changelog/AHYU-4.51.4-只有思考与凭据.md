# 4.51.4 — 只有思考的响应、解不开的凭据、thinking 翻译

## 起因

三个问题叠在一起，都是「不报错但结果不对」那一类：

1. **回空**：客户端时不时拿到空回答，agent 类客户端只能重试，表现为「用着用着回空 / 一直重连」。
2. **日志刷屏**：`配置解密失败（…）：AEADBadTagException` 每 10~20 秒一批、每批 6 条，
   真正的线索被淹掉；而且解不开的账号仍然留在池子里显示「可用」。
3. **思考参数被丢**：Claude Code 发 `thinking.budget_tokens`，选中的却是 OpenAI 协议的中转，
   日志里一句「能力参数未透传到上游（跨协议无等价字段）：thinking」——用户开了思考却没思考。

## 一、只有思考的响应不再算成功

真机复现（Loomy 走 OpenAI 协议转 Anthropic 出口）：

```
turn 1  stop=tool_use   tool_use=1        ← 正常
turn 2  stop=end_turn   text=0  tool_use=0  ← 客户端看到的就是空白
```

上游回的是「只有 thinking、`stop_reason=end_turn`、没有正文也没有工具调用」。
判定口径改成**思考不算交付**：

- `Collector.substantive()`：只认正文 / 工具调用 / 原样透传体；纯 thinking 视为空。
- OpenAI `Streamer`：新增 `delivered()`（正文 / 工具调用 / 透传正文）；
  `rawDone` 在没交付时**不写 `[DONE]`、不置 `rawEnded`**，把「这算不算成功」交给上层。
- Anthropic `Streamer`：`thinking` 只 `start()`（照常流给用户看），不置交付；
  `finish` 在没交付时什么都不写。
- `DeferredWriter`：提交条件从裸 `"content"` 改成 `hasDeliveredContent()` ——
  避开 Anthropic `message_start` 里那个空的 `"content":[]`，且 `thinking_delta` 不提交。
- thinking 分片在交付前与 role 一起压在缓冲里，真来正文时**按原顺序**补写（思考本来在正文之前）。

## 二、凭据解不开：日志去重 + 退出派发

- `Secret.open` 的失败日志按异常类型**只打一次**（原来每次读配置/自动保存/刷新都打）。
- 新增 `Provider.credentialsUnreadable`（**只在内存**，不落盘）：加载时对每个账号逐字段判断
  `apiKey` / `credentials` 是否「是密文但解不开」，是就标上并退出派发与探活。
  为什么只放内存：这是「这台机器的 Keystore 打不开这个密文」，不是账号属性；
  Keystore 恢复或用户重新登录后应自动恢复。
- `clearRestricted()`（用户编辑/重新登录账号时调用）同时清掉这个标记。
- 账号页新增「⛔ N 个不可用（凭据解不开或被上游限制）」提示。

## 三、thinking 跨协议近似翻译

`XParams.thinking` 从「跨协议一律返回 null」改为**受限近似**（同协议仍原样透传）：

| 来源 | 目标 | 结果 |
| --- | --- | --- |
| Anthropic `budget_tokens=8000` | OpenAI | `reasoning:{"effort":"medium"}` |
| Anthropic 32000 / 1024 | OpenAI | `high` / `low` |
| Anthropic `budget_tokens=N` | Gemini | `thinkingConfig.thinkingBudget=N` |
| OpenAI `effort:"high"` | Anthropic | `{"type":"enabled","budget_tokens":16384}` |
| 任何 `disabled` | Gemini | `thinkingBudget=0` |

刻意不追求数值等价（`budget_tokens` 与 `reasoning_effort` 之间没有可靠换算），
只保证「开没开、大概多深」不丢。

## 验证

- 真机自检 **38 / 38** 通过（新增 thinking 翻译、凭据/受限退出派发两组断言）。
- 回空复现：同一请求连跑 6 次**全部有正文**（修复前约 1/3 为空）。
- 工具循环：`stop_reason` 正常走 `tool_use → end_turn`。
- thinking 透传：抓包确认出站 body 带 `reasoning:{"effort":"medium"}`，
  「thinking 未透传」告警消失。
- 主应用解密失败 0 条、无可疑标记、账号池完整（改动未误伤正常工作账号）。
- 变体包（独立 Keystore）实测命中「凭据解不开」路径：日志只打一次、账号退出派发。

## 未验证 / 已知限制

- 变体包 `com.juniper.elm` 的凭据解不开是**它自己的 Keystore 与主应用不同**导致的，
  需要在变体里重新登录。这不是代码缺陷，但用户会看到「⛔ 不可用」提示。
- thinking 的档位↔预算换算用的是约定值（1024/4096/16384），没有针对各上游实测校准。
