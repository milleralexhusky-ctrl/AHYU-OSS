# AHYU 4.47.0：rerank / embed / 模型查询 / Ollama 管理

versionCode 640。继续补"客户端可能用到"的协议面。

## 新增

| 路径 | 说明 |
| --- | --- |
| `POST /v1/rerank` | Cohere / Jina 形状的重排，原样转发给 OpenAI 兼容上游 |
| `POST /v1/embed` | Cohere 形状的向量化，原样转发 |
| `GET /v1/models/{id}` | 单个模型查询（URL 编码的 `WB/xxx` 也支持） |
| `POST /api/pull`、`/api/create`、`/api/copy`、`/api/delete`、`/api/push`、`/api/blobs/*` | 代理到**本机 Ollama** |

Ollama 管理的判据：账号的 `baseUrl` 含 `:11434` 或名称含 `ollama`。
找不到这样的账号时返回 501 并说明原因，不再笼统地拒绝。

## 验证（本地假上游）

| 入口 | 结果 |
| --- | --- |
| `/v1/rerank` | 200，`{"results":[{"index":0,"relevance_score":0.9}]}` |
| `/v1/embed` | 200，`{"embeddings":[[0.1,0.2]]}` |
| `/v1/models/WB/hy3` | 200，返回 model 对象 |
| `/api/pull`（假 Ollama :11434） | 200，NDJSON 两行原样返回 |
| `/api/delete` | 200，`{"status":"success"}` |
| 没有本机 Ollama 账号时 `/api/pull` | 501 + 明确说明 |

测试账号已删除，配置恢复 3 个真实账号。真机自检 26/26。

## 未做

- Realtime 音频。
- Vector stores / file search / code interpreter。
- Assistants 异步 run 队列（run 仍同步执行）。
