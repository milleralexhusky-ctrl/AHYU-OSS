# AHYU 4.44.0：非聊天协议补全

versionCode 637。4.43.0 多协议输出的继续：把聊天之外的常用端点也接上。

## 新增端点

### OpenAI 兼容（原样转发）

| 路径 | 说明 |
| --- | --- |
| `POST /v1/embeddings` | 向量化；响应原样返回 |
| `POST /v1/moderations` | 内容审核 |
| `POST /v1/images/generations` | 文生图 |
| `POST /v1/audio/transcriptions` | 语音转写（multipart 原样转发） |
| `POST /v1/audio/translations` | 语音翻译 |
| `POST /v1/audio/speech` | 语音合成（二进制响应原样返回） |

这些端点不做 canonical 转换：canonical 请求只描述聊天，硬套会把
embedding 数组、multipart、二进制音频弄坏。做法是按 model 选一个
OpenAI 兼容账号，把**原始字节 + Content-Type** 转发到
`<baseUrl>/embeddings` 等路径，再把上游状态码/响应体原样带回；
最多试 3 个候选，取第一个 2xx。

### Ollama

| 路径 | 说明 |
| --- | --- |
| `POST /api/embed` | 转成 OpenAI embeddings 再转回 `{"embeddings":[[...]]}` |
| `POST /api/embeddings` | 旧版单条接口，返回 `{"embedding":[...]}` |
| `/api/pull`、`/api/create`、`/api/copy`、`/api/delete`、`/api/blobs/*` | 明确返回 501：只做推理转发，不管理远程模型库 |

### Gemini

| 路径 | 说明 |
| --- | --- |
| `POST /v1beta/models/{m}:embedContent` | 原样转发给 Gemini 账号 |
| `POST /v1beta/models/{m}:batchEmbedContents` | 同上 |

## 底层改动

- `Req` 新增 `bodyBytes`：请求体不再只保留 UTF-8 字符串，multipart/音频
  可以按原始字节转发。
- `Http.callBinary()`：二进制响应（音频）不再经过文本解码。
- 非聊天端点选账号时只考虑 OpenAI 兼容类型（openai / kilo / ovh），
  不会把 embeddings 发给 WorkBuddy 这类账号。

## 验证

用本地假上游（127.0.0.1:9999）逐项跑，验证转发路径、认证头、原始字节：

| 端点 | 结果 |
| --- | --- |
| `/v1/embeddings` | 200，上游收到 `Bearer test-key`，响应原样返回 |
| `/v1/moderations` | 200 |
| `/v1/images/generations` | 200 |
| `/v1/audio/transcriptions`（multipart） | 200，上游确认收到 multipart |
| `/v1/audio/speech` | 200，`audio/mpeg`，二进制 `ID3FAKEAUDIO` 完整 |
| `/api/embed` | 200，OpenAI 数组转成 Ollama `embeddings` |
| Gemini `:embedContent` | 200，`?key=` 正确带到上游 |

测试用的临时账号（`debug-*`）在测试后已删除，配置恢复为 3 个真实账号。
自检 26/26。

## 仍未做

- OpenAI Assistants / Threads（有状态，无法简单转发）。
- OpenAI Realtime（WebSocket）。
- Ollama 模型管理（pull/create/delete）——我们不是模型仓库。
- Cohere / Jina 等非 OpenAI 形状的 embeddings/rerank；需要时再加协议适配。
