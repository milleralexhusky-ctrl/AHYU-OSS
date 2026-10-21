# 4.51.0 — Anthropic Messages 上游与多端并发

## 起因

两件事：

1. 不少中转/官方文档会给一个“Claude Code 接入”的地址（Anthropic Messages
   协议）。用户把这个地址填进网关后，请求不是拼错路径，就是被中转当成普通客户端
   拒掉；Claude Code 带来的 `anthropic-beta` 和 `cache_control` 也在网关里丢了。
2. 本机上同时开多个应用（编辑器、浏览器、桌面壳、自动化脚本）时，并发连接会
   互相挤掉，表现为偶发 503、首字变慢。

## 改动

### Anthropic Messages 上游

- **地址拼接**（`Upstreams.anthropicMessagesUrl`）兼容文档里的四种写法：
  - `https://api.example.com`
  - `https://api.example.com/v1`
  - `https://api.example.com/api/anthropic`
  - `https://api.example.com/api/anthropic/v1/messages`（完整端点，原样使用）

  以前完整端点会被拼成 `.../v1/messages/v1/messages`。

- **出站头**（`Upstreams.anthropicHeaders`）：
  - 同时带 `x-api-key` 和 `Authorization: Bearer`，兼容只认 `ANTHROPIC_AUTH_TOKEN`
    的 Claude Code 中转；
  - `anthropic-version: 2023-06-01`；
  - `user-agent: claude-cli/...`、`x-app: cli`，部分中转会检查这两个头；
  - 客户端带来的 `anthropic-beta` 原样转发（1M 上下文、细粒度工具流、
    interleaved thinking 都挂在这个头里）；客户端没带就不凭空加，避免官网 400。

- **system 保真**：Anthropic 入口 → Anthropic 上游时，`system` 按原始块数组转发，
  `cache_control` 不再被拍平丢失。Claude Code 十几 KB 的 system 前缀可以继续
  命中上游缓存，延迟和计费都更低。跨协议仍使用拼平文本。

- 账号编辑器提示更新：根地址、`/v1`、完整 `/v1/messages` 都可直接粘贴。

### 多端并发

- **回环地址不再共用一个客户端配额**。原来按来源 IP 记连接数，本机所有应用都是
  `127.0.0.1`，共用 `max(8, 37.5% × 建议容量)` 条连接，两个应用各开几条
  keep-alive 就会互相触发 503。现在回环 peer 单独给到 `MAX_CONN - 8`（56/64），
  仍保留上限防止单个泄漏连接的本地应用吃光整池；局域网客户端继续用原配额。

- **容量统计按请求而不是按连接**。`Capacity` 以前在连接建立时 +1、连接关闭时
  -1，并把整条连接的生命周期记成一次“请求耗时”：一条空闲 60 秒的 keep-alive
  连接会被当成“一个跑了 60 秒的请求”，把排队论算出的容量和压力值全部带偏。
  现在进入 `route` 前 +1、结束 -1，只记录真实请求的耗时；后台探活
  （`x-cpa-ephemeral`）不计入容量样本。

## 验证

- 自检 36 / 36 通过（新增两项：Anthropic 上游地址/头/cache_control；
  回环 peer 识别）。
- 本机 20 个并发连接同时请求 `/v1/models`：20 / 20 返回 200，
  日志中没有“同一客户端连接过多”。
- `/v1/capacity` 在空闲时 `in-flight` 回到 0，不再把空闲 keep-alive 算成在飞请求。
- 地址 / 头 / system 块由进程内断言覆盖；**没有真实中转 Key，未做线上中转联调**。

## 未做

- 消息内容块（尤其最后一条 tool_result）上的 `cache_control` 仍是拍平后丢失。
  同协议转发要完整保留它，需要把原始 messages 一并带走，会绕过 canonical 的
  工具配对整理，风险更大；system 前缀缓存先解决，消息级缓存留待后续评估。
- 没有新增“一键 Claude Code”预设，仍从「用 API 密钥 → Claude（API Key）」
  进入后粘贴地址。
