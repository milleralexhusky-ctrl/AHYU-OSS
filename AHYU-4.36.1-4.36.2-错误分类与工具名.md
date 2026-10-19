# AHYU 4.36.1–4.36.2：错误分类 + 工具名清单

versionCode 623–624。

对照 OpenTelemetry GenAI 规范补的最后两项：`error.type` 和
`gen_ai.tool.definitions`。

## 4.36.1 错误分类（ErrorKind）

以前 `usage.tsv` 的失败只记 `0`，不知道**为什么**。而不同错误的正确应对
完全相反：

| 类别 | 含义 | 正确应对 |
|---|---|---|
| `rate` | 限流 | 等一会儿，换号也能过 |
| `quota` | 额度/余额耗尽 | 换号，等没用 |
| `auth` | 凭证失效 | 重新登录，重试没用 |
| `channel` | 渠道校验（11128） | 账号被标记，改请求形状也不会好 |
| `badreq` | 请求非法 | **我们**发错了，换号更没用 |
| `timeout` / `net` | 超时 / 连不上 | 网络问题 |
| `empty` | 上游回了但没内容 | 可能偶发 |
| `other` | 其他 | — |

判据**直接复用现有实现**，不造第二套：`Throttled.kind`、
`LocalReason`、`InvalidRequest`。

### 4.36.2 修正：writeError 路径

第一版漏了一类：模型名不存在这类错误是 **`writeError` 正常返回一个错误响应**，
不抛异常，所以 catch 里拿不到，被误归成 `empty`。

修法：在 `writeError` 里按 HTTP 状态补默认归类
（400/404/422 → `badreq`，401/403 → `auth`，429 → `rate`，5xx → `other`），
已有明确 kind 的优先用 kind。

## 工具名清单（第 22 列）

以前只记工具**个数**（第 14 列）。数量对上不代表定义对上，
某个特定工具名可能正是上游拒绝的原因。

只记名字、逗号分隔、超 300 字符截断——完整 schema 太长，
落盘会把用量文件撑爆。

## usage.tsv 现在 22 列

| 列 | 内容 |
|---|---|
| 19 | 上游响应 ID |
| 20 | 会话 ID |
| 21 | 错误类别 |
| 22 | 工具名清单 |

## 验证

离线单测 11 项全过（含 11128 → `channel`、本地并发 → `rate`）。

真机验证（4.36.2 / 624）：

- 带两个工具：`工具名=[get_weather,get_location]`，错误类为空
- 模型名不存在：`质量=empty 错误类=[badreq]`（修正前是 `empty`）

## 和 OTel 规范的对照结论

38 个属性里已对上 15 项。剩下来的多是 embeddings / retrieval / memory
这些当前用不到的部分。

规范里真正有价值的补充是 `response.id` 和 `conversation.id`（4.36.0 已做）
以及 `error.type`（本轮）。其余部分属于「别人系统的复杂性」，不必照搬。
