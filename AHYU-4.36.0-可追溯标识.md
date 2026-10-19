# AHYU 4.36.0：可追溯标识（上游 ID + 会话 ID）

versionCode 622。

## 起因

对照 OpenTelemetry GenAI 语义规范，发现 18 列里缺两个关键标识：

- `gen_ai.response.id` —— 上游返回的 requestId
- `gen_ai.conversation.id` —— 会话标识

**上游 requestId 的实战价值**：之前排查 11128 渠道校验，就是靠发现
"五次回包的 requestId 一模一样"，才断定上游根本不看我们发的东西。
但那个 ID 以前只活在内存日志里（500 条环形缓冲），重启即失，
每次排查都得重新抓包。

## 改动

### usage.tsv 18 列 → 20 列

| 列 | 内容 |
|---|---|
| 19 | 上游响应 ID（requestId） |
| 20 | 会话 ID |

### `Proto.Sink.responseId()` 新回调

不强制实现——有些渠道压根不返回 ID。各渠道解析到就上报一次：

- `Upstreams.openAiStream`：读 chunk 的 `id` 字段
- `WorkBuddy`：优先 `requestId`，其次 `request_id`，最后 `id`
  （业务错误 11128 整条挂在 requestId 上）
- `Trae`：读 `requestId` / `request_id`，错误帧回退到 `id`

### `Conversation.id()`

对「system + 全部消息内容 + role + 工具信息」取 FNV-1a 64 哈希，
前缀 `cv_`。

设计取舍：**不追求跨会话稳定**。用户改一个字就是新会话——这是对的，
要的是"这一串请求属于同一次连续交互"，不是"同一个用户"。
多轮工具循环里，前一跳是后一跳的前缀，所以每跳指纹不同但能靠内容关联。

## 验证（真机 4.36.0 / 622）

```
上游ID=[cmb-6a1dcad7cb1b11f1b215f61c1cf7eae9]  会话=[cv_748741f534153ca6]
```

两条都有值。注意 WB 的上游 ID 是 `cmb-` 前缀（不是 OpenAI 的 `chatcmpl-`），
说明拿到的确实是上游原始 ID，不是网关自己生成的那个。

## 未做

- Anthropic / Gemini 路径的 responseId 上报还没接（走的是另外的解析函数）
- 会话 ID 目前只做标识，还没有界面入口按会话聚合查看
