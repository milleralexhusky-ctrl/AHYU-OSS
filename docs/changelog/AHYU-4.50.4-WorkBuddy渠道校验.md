# 4.50.4 — WorkBuddy 国际网关的会话头与请求级风控

## 起因

DeepSeek Harness 的 Android 客户端（`com.dsh.client`）走 AHYU 时，第一轮就失败：

```
400 INVALID_REQUEST
upstream HTTP 400 {"code":11128,"msg":"Illegal API invocation from an unapproved channel",
 "displayMsg":{"en":"Request blocked. Please send it again", ...}}
```

排查分两步。

### 1. 出站缺会话头（2026-10-15 起）

WorkBuddy 国际网关（`www.codebuddy.ai` / `www.workbuddy.ai`）要求聊天请求带官方
客户端的 `X-Conversation-*` 家族。AHYU 只在 body 里放了 `conversation_id`，没发这组头。
国内网关（`copilot.tencent.com`）不校验这一组，所以以前只有国际号会撞上。

### 2. 11128 被当成了"坏请求"

`Http` 把 HTTP 400 一律抛成 `InvalidRequest`，`FailScope` 据此判成 `REQUEST`：
纯调用直接失败、轮换整轮中止。一个号被风控拦住，连本来健康的别的模型/渠道都不再尝试。

补上头之后，用调试包的 `/v1/debug/capture` 抓到了客户端真实请求（125KB、45 个工具、
37 条消息）。逐段重放后确认：

- 不是缺头：带齐头仍然会被拦；
- 也不是某一句固定文案：同一段 system 提示词/同一份完整请求，换个时间发就能过，
  不同模型的触发阈值也不同（同一句话在 `hy3-c` 上稳定被拦、在
  `deepseek-v4-pro` / `glm-5.3` 上正常）；
- 它更像**请求级风控**：上游自己的提示就是 "Request blocked. Please send it again"，
  等一两秒原样重发就可能放行。

## 改动

- **补 `X-Conversation-*` 头**（`WorkBuddy.applyConversationHeaders`）：
  - `X-Conversation-ID`：32 位小写十六进制，由 system + 首条 user 正文派生，
    同一会话跨轮次稳定；
  - `X-Conversation-Request-ID`：由会话 id + 最后一条 user 正文派生，
    重试和工具循环内稳定，新的一轮才变化；
  - `X-Conversation-Message-ID`：每次调用新值；`X-Request-ID` 与它相同。
  值按内容派生而不是每次随机，避免上游用量面板把同一段对话拆碎。
- **区分两种 11128**（`Http.channel` / `Http.softChannel`）：
  - `channel_soft`：`Illegal API invocation from an unapproved channel` /
    “请重新发送” —— 请求级风控；
  - `channel`：`conversation id missing` / `request illegal` —— 账号被标记。
  前者走短回避（20 秒），后者仍按 6 小时（换号或重新登录）。
- **请求级风控最多重发 2 次**（同账号、递增间隔 500ms / 1.5s）。400ms 一次实测不够，
  会被同一波风控再挡；两次之后还不放行就交给换号/换模型。
- **失败作用域**（`FailScope`）：渠道校验先于 `InvalidRequest` 判断，归 `ACCOUNT`。
  纯调用换同模型的下一个号；轮换跳过这个号，不中止整轮。
- **请求级风控不占轮换预算**：`auto*` 命中 `channel_soft` 时额外多试最多 3 个模型。
  抓包实测同一份 125KB 请求在 `deepseek-v4-pro` / `deepseek-v4.1-flash` / `hy3-c` 上被拦，
  换 `glm-5.3` / `kimi-k2.6` / `minimax-m3` 就正常返回 —— 只换两个候选时整轮会误判“全挂”。
- 请求级风控**不写"试到底"标记**，也不改账号画像：下一个请求照常尝试。
- 调试包的请求体抓取上限 64KB → 256KB：带工具的 agent 请求里 messages 很容易占满
  64KB，`tools` 排在后面就永远看不到。
- 新增自检「WorkBuddy 会话头与新版风控归类」（第 34 项）。

## 验证

- 自检 34 / 34 通过（真机）。
- 抓包确认客户端出站请求带齐四个会话头，值为 32 位十六进制；
  同一会话/同一轮稳定，工具循环不换轮次，新 user 消息才换。
- 用客户端真实请求体（125KB）重放：修改前稳定 400；修改后同账号重发可放行，
  仍失败时只短回避 20 秒，不再把模型锁 6 小时。
- 逐段 bisect：单独 system 段、10KB 填充提示词、45 个工具（换通用 system）
  都能通过 —— 排除"固定文案"和"请求大小"这两个单因素，确认是上游风控的组合判定。

## 未做 / 保留

- 没有改写客户端的 system 提示词。上游会按内容做风控，但触发条件不是某一句固定文案，
  改写既不能稳定解决，又会改变 agent 的行为。
- 国际网关还可能校验 `X-IDE-*` / `X-Agent-*` 身份头；本次只补了与 11128 直接相关的
  会话家族，其余保持原样，避免引入新的非官方特征。
