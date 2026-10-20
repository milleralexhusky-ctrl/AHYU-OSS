# AHYU 4.46.0：Assistants 工具闭环 + Realtime（文本）

versionCode 639。

## 一、Assistants：requires_action / submit_tool_outputs

4.45.0 遇到模型要工具时只能 failed；现在补上了闭环：

1. 模型返回 `tool_calls` → run 进入 `requires_action`，
   `required_action.submit_tool_outputs.tool_calls` 原样给出；
2. 客户端 `POST /v1/threads/{tid}/runs/{rid}/submit_tool_outputs` 提交结果；
3. 网关把 tool 结果写进**模型历史**，再次调用 chat；
4. 模型继续要工具 → 再次 `requires_action`；给出文本 → `completed`。

状态拆分：

- `messages`：客户端可见的 user/assistant 文本；
- `history`：模型真正看到的消息（含 assistant tool_calls / tool 结果）。
  这样工具调用不会污染客户端消息列表，也不会丢配对 id。

## 二、Realtime：`/v1/realtime` WebSocket（文本）

- 完成 RFC6455 握手、掩码帧解析、ping/pong/close；
- 支持事件：
  - `session.update` → `session.updated`
  - `conversation.item.create` → `conversation.item.created`
  - `input_audio_buffer.commit/clear`
  - `response.create` → `response.created` → `response.output_item.added`
    → `response.content_part.added` → `response.text.delta` → `response.text.done`
    → `response.output_item.done` → `response.completed`
- 后端仍走本机 `/v1/chat/completions`，复用选路/换号/限额。
- **不支持音频**：`modalities` 固定 `["text"]`，`input_audio_buffer.append` 被忽略。
  这样语音客户端会明确看到“只支持文本”，而不是收到坏数据。

## 验证

- Assistants 工具闭环（假上游）：
  - 第一次 run → `requires_action`，`call_1 / get_weather({"city":"SZ"})`；
  - 提交 `sunny 30C` → `completed`，usage 8/1；
  - 消息列表只有 user/assistant 文本；steps 为 `tool_calls` + `message_creation`。
- Realtime（websocat）：
  - `session.created` → `session.updated` → `conversation.item.created`；
  - `response.text.delta` 与 `response.completed` 正常，usage 带入。
- 真机自检 26/26。测试账号已删除，配置恢复 3 个真实账号。

## 未做

- Realtime 音频输入/输出（需要音频编解码与上游实时协议）。
- Vector stores / file search / code interpreter。
- Assistants 异步 run 队列：当前 run 仍同步执行，长任务占用 HTTP 请求。
- Ollama 模型管理（pull/create/delete）。
