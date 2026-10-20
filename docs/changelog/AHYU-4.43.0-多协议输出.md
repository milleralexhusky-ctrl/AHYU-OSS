# AHYU 4.43.0：多协议输出

versionCode 636。

## 方向修正

4.42.0 做的是"按账号选认证头 + 端侧模型预设"。用户的真实需求不是这个，
而是：

> 现在是把不同账号/Key 聚合起来、统一成**一个**协议输出；
> 要改成统一成**多个**协议输出，让客户端用它原生的协议就能接。

因此 4.43.0 **回退了 4.42.0 的认证方式 UI 与端侧预设**（代码回到 4.41.0 的
行为），把这一版的时间用在真正的多协议入口上。

## 新增的入口

所有入口共用同一套账号、选路、换号、限流与用量记录，只是协议形状不同。

| 客户端协议 | 路径 |
| --- | --- |
| OpenAI Chat | `POST /v1/chat/completions` |
| OpenAI 旧版 Completions | `POST /v1/completions`（新增） |
| OpenAI Responses | `POST /v1/responses` |
| Anthropic Messages | `POST /v1/messages` |
| Anthropic 计数 | `POST /v1/messages/count_tokens` |
| Gemini | `POST /v1beta/models/{m}:generateContent` / `:streamGenerateContent` |
| Gemini 计数 | `POST /v1beta/models/{m}:countTokens`（新增） |
| Ollama 原生 | `POST /api/chat`、`POST /api/generate`（新增，NDJSON 流） |
| Ollama 目录 | `GET /api/tags`、`GET /api/version`、`GET /api/ps`、`POST /api/show`（新增） |
| Azure OpenAI | `POST /openai/deployments/{deployment}/chat/completions` 与 `/completions`（新增） |

实现要点：

- **Ollama**：请求翻译成 OpenAI body，复用 `OpenAiFront.parse`；工具调用 id 缺失时
  按历史顺序补齐，保证 `RequestCheck` 的配对校验能过；响应按 NDJSON 逐行写，
  `message.content` / `thinking` / `tool_calls` 与 `done_reason` 都按 Ollama 形状。
- **旧版 Completions**：`prompt`（字符串或数组）→ 一条 user 消息，
  输出转回 `text_completion`（含流式 `data:` 与 `[DONE]`）。
- **Azure**：路径改写成标准路径，deployment 兼作 model（支持 `WB/xxx` 这种带斜杠的名字）；
  入站鉴权额外接受 `api-key` 头。
- **Gemini countTokens**：走本地估算，不调用上游，返回 `{"totalTokens":N}`。

## 验证（真机 4.43.0 / 636）

每条都用真实模型 `WB/deepseek-v4.1-flash` 跑过：

| 入口 | 结果 |
| --- | --- |
| `/api/chat` 非流式 | 200，`message.content` 正常 |
| `/api/chat` 流式 | NDJSON 多行 + 最后 `done:true` |
| `/api/generate` 非/流式 | `response` / 逐行 `response` 正常 |
| `/api/tags` | 113 个模型 |
| `/v1/completions` 非/流式 | `text_completion` + `[DONE]` |
| Azure chat（deployment 带斜杠） | 200 |
| Gemini `:countTokens` | 200，`totalTokens=26` |
| 不存在的模型 | 404，Ollama 返回 `{"error":...}` |

自检 **26/26** 通过（新增 Ollama 请求翻译/响应形状用例）。

## 未做

- 没有做 OpenAI Embeddings / Images / Audio 这些非聊天入口；当前 canonical 模型只有聊天。
- Ollama 的 `/api/embed`、`/api/pull` 等模型管理接口不做（我们不是模型仓库）。
- 多协议入口共用同一个端口；如果将来要按协议分端口，需要另加监听器配置。
