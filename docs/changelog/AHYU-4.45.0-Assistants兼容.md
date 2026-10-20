# AHYU 4.45.0：Assistants / Threads 兼容层

versionCode 638。

## 起因

Assistants 客户端不是一次 chat 调用，而是一组有状态的
`assistants / threads / messages / runs / steps`。只提供 chat 时，这类 SDK
第一步就 404。这一版补上最小的可用闭环。

## 实现

- 状态存放在应用私有目录的 `assistants.json`（原子写；线程/消息/步骤有上限，
  防止无限增长）。
- 支持：

| 方法 | 路径 |
| --- | --- |
| GET/POST | `/v1/assistants` |
| GET/DELETE | `/v1/assistants/{id}` |
| POST | `/v1/threads` |
| GET/DELETE | `/v1/threads/{id}` |
| GET/POST | `/v1/threads/{id}/messages` |
| GET | `/v1/threads/{id}/messages/{mid}` |
| GET/POST | `/v1/threads/{id}/runs` |
| GET | `/v1/threads/{id}/runs/{rid}` |
| POST | `/v1/threads/{id}/runs/{rid}/cancel` |
| GET | `/v1/threads/{id}/runs/{rid}/steps` |
| POST | `/v1/threads/runs`（建线程并跑） |

- run 是**同步执行**：创建后立即把 assistant instructions + 线程消息拼成
  chat 请求，走本机 `/v1/chat/completions`（复用选路/换号/限额），完成后
  写回一条 assistant 消息，返回 `status=completed` 和 `usage`。
- `stream=true` 时返回 SSE：
  `thread.run.created` → `thread.run.in_progress` → `thread.run.completed`
  → `done: [DONE]`。
- 模型要求工具调用时，run 标记 `failed`，`last_error` 写明
  “Assistants 兼容层暂不支持工具调用”，不伪装成功。

## 验证

用本地假上游（流式 SSE）跑完整闭环：

1. 建 assistant（instructions=be brief）；
2. 建 thread，写入 user 消息；
3. 建 run → `completed`，usage `5/2`；
4. 线程消息列表：`user "hello"` → `assistant "fake reply"`；
5. steps 里有一条 `message_creation`；
6. `stream=true` 能收到 created / in_progress / completed 三个事件；
7. 删除 thread / assistant 返回 deleted。

测试账号和假上游已清理，配置恢复 3 个真实账号。自检 26/26。

## 未做

- `requires_action` / `submit_tool_outputs`（assistant 工具调用闭环）。
- Vector stores、file search、code interpreter。
- 后台异步 run/queue：当前是同步执行，长任务会占用这个 HTTP 请求。
- Realtime WebSocket 仍未接入（需要 WebSocket 服务端 + 上游实时协议）。
