# AHYU 4.42.0：按账号选认证头 + 端侧模型预设

versionCode 635。

## 起因

上游不是一个接口能打通的：

- 多数第三方中转要 `Authorization: Bearer <key>`；
- Anthropic 风格（官方、部分中转）要 `x-api-key: <key>`；
- 本机模型（llama.cpp / Ollama / 端侧小模型）**完全不要认证**。

旧实现按 type 写死：OpenAI 只发 Bearer，Anthropic 同时发 `x-api-key` + `Bearer`。
遇到"只认其中一种"的中转时，要么 401，要么被当成非法头；本机模型则会收到一个
不该有的 Bearer。UI 上也没法改。

## 改动

### 1. `Provider.authStyle`

新增账号级字段，四个取值：

| 值 | 发送的头 | 适用 |
| --- | --- | --- |
| `bearer` | `Authorization: Bearer <key>` | 多数第三方中转（默认） |
| `x-api-key` | `x-api-key: <key>` | Anthropic 官方 / Anthropic 风格中转 |
| `both` | 两个都发 | 兼容性兜底 |
| `none` | 不发认证头 | 本机模型、无鉴权的局域网服务 |

- 老配置没有这个字段时按 type 取默认：Anthropic → `both`（保持旧行为），
  其余 → `bearer`。所以升级不会改变现有账号的请求。
- 应用到：OpenAI 兼容 chat、Anthropic chat、拉取模型列表。
- 账号编辑页新增「认证方式」下拉；「自动获取模型列表」也用同一选择。
- Claude（API Key）预设直接设为 `x-api-key`，与官方一致。
- Gemini 仍用 `?key=` / `x-goog-api-key`，不受这个字段影响（已在 UI 提示）。

### 2. 端侧模型预设

OpenAI 兼容预置列表新增两项：

| 预设 | 地址 | 认证 |
| --- | --- | --- |
| 端侧模型 · llama.cpp | `http://127.0.0.1:8080/v1` | 无 |
| 端侧模型 · Ollama | `http://127.0.0.1:11434/v1` | 无 |

AHYU 的 `network_security_config` 本来就允许回环明文，所以这两项可以直接工作；
端口/模型名在编辑页改。已有的 DeepSeek / 智谱 GLM / 通义千问 / Kimi 等
OpenAI 兼容预设保持原样（默认 Bearer）。

## 验证

- 新增自检「上游认证头按账号选择」：四种风格逐项断言，并验证老 Anthropic
  配置仍得到 both；真机 **26/26** 通过。
- 构建通过；原有 3 个账号的配置没有变化（默认值与原行为一致）。
- 签名不变，可覆盖升级。

## 未做

- 认证头之外的自定义（例如某些中转要 `api-key` 或查询参数）还没有做成通用配置；
  需要时再抽象成 `authHeaderName` + `authInQuery`。
- 端侧模型只做了 OpenAI 兼容入口；llama.cpp / Ollama 的非 OpenAI 原生 API 不接。
