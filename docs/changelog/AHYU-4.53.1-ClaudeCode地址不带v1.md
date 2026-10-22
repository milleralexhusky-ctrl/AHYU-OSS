# 4.53.1 — 给 Claude Code 的那条地址不该带 /v1

## 起因

用户指出：

> 要求对端支持 Anthropic Messages 协议（各家文档里写"Claude Code 接入"的那个地址）。
> 地址末尾不要带 `/v1`，claude 会自己补 `/v1/messages`。

**服务端本来就是支持的** —— 实测（本机 8321）：

```
POST /v1/messages      → 200，Anthropic 形状正确
POST /v1/v1/messages   → 200（容错把重复前缀归一化了）
POST /messages         → 404
```

流式也对，Anthropic 该有的六个事件一个不缺：
`message_start` / `content_block_start` / `content_block_delta` /
`content_block_stop` / `message_delta` / `message_stop`。

**问题不在服务端，在我们递给用户的那个地址。**

## 真正的问题：一个地址喂两种协议

首页原来只有一行地址，复制出来的永远是：

```
http://127.0.0.1:8321/v1
```

它对 OpenAI 兼容客户端是对的（各家 SDK 拼法不统一，带 `/v1` 是常见预期）。
但对 Claude Code 是**错的** —— 它拿到地址后会自己补 `/v1/messages`，
于是请求变成 `/v1/v1/messages`。

### 为什么这个错误一直没被发现

因为网关有一条容错能认。`Server.normalizePath` 的注释写着它是为什么加的：

```
Base URL 写成 `http://host/v1` 时，不少 SDK 还会再拼 `/v1/models`，
实际请求是 `/v1/v1/models`；以前网关只回 404，用户以为"加不进去"。
```

**这条容错把错误掩盖成了"能用"** —— 于是"我们给错了地址"这件事
可以长期存在而没有任何症状。容错本身该留（它兜的是老用户已经配好的地址），
但不该拿它当"所以地址给错也没关系"的理由。

## 改动

首页连接卡片从**一行地址**改成**两行**，各自一个复制按钮：

| 客户端 | 地址 | 为什么 |
| --- | --- | --- |
| Claude Code / Anthropic SDK | `http://127.0.0.1:8321` | 它自己补 `/v1/messages`，所以末尾**不带 `/v1`** |
| 其它（OpenAI 兼容） | `http://127.0.0.1:8321/v1` | 各家拼法不统一，带 `/v1` 是常见预期 |

```java
private String openAiBaseUrl()  { return "http://127.0.0.1:" + port + "/v1"; }
private String anthropicBaseUrl() { return "http://127.0.0.1:" + port; }   // 不带 /v1
```

**不改既有行为**：OpenAI 那条的值与原来完全一致，只是从"唯一"变成"上一条"。
老用户已经在用的配置一个字都不用动。

## 验证

- 真机自检 **53 / 53**（新增一条，见下）
- 模拟 Claude Code 的真实请求（`x-app: cli` + `claude-cli/` UA + 不带 `/v1` 的 base）：
  ```
  POST http://127.0.0.1:8321/v1/messages → 200
  {"id":"msg_…","type":"message","role":"assistant","model":"auto",
   "content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn",
   "usage":{"input_tokens":17,"output_tokens":1,…}}
  ```
- 流式同样实测通过（六个 SSE 事件齐全）
- 新文案与两个方法名均确认编译进 APK

## 新增的那条自检钉的是什么

`checkClientBaseUrls` **不测"能不能跑通"** —— 能跑通恰恰是被容错掩盖掉的那件事。
它钉的是：

1. 容错本身还在（`/v1/v1/messages` → `/v1/messages`），删了会让老用户立刻 404；
2. 正常路径没有被容错改坏；
3. **两条地址不许相同**（相同必然有一个是错的）；
4. Anthropic 那条**不以 `/v1` 结尾**、OpenAI 那条**以 `/v1` 结尾**。

这样以后若有人把两个地址合并回一行，或给 Claude Code 那行补上 `/v1`，
自检会红 —— 而不是等到某个用户报"Claude Code 连不上"才发现。

## 未验证

- 没在**真的 Claude Code** 里跑过（本机没有 claude 命令）。
  验证用的是按官方形状手工构造的请求（同样的路径、同样的头、同样的地址形态），
  以及网关自身的 SSE 事件序列比对。
