# AHYU 4.29.14 — "怎么知道现在到底能不能用"：真链路体检

## 为什么做

今天被问过两次"能用了吗"。当时能给的证据只有 `/health` 的 401（活着）和单账号手工探测，
而这两种都回答不了"**客户端真的发一条消息，会不会拿到答案**"：
`/health` 只证明进程在听，`Detect` 走的是进程内 `Server.serve`（绕过 HTTP、鉴权和选路）。

## 实现

- 新增 `core/SelfCheck.java`：对每个 `dispatchable()` 的账号**真的发一次 1-token 对话**，
  走的是客户端同一条路——`http://127.0.0.1:<port>/v1/chat/completions` + `Authorization` +
  `X-CPA-Ephemeral`（不计用量、不留会话）→ 鉴权 → 路由/虚拟模型 → 上游 → 回包。
  - 模型选择：优先 `auto`（走打分路由，最贴近真实使用），没有就取目录里第一个聊天模型；
  - 默认 15 秒/账号、3 并发；结果含 `{name, model, ok, ms, why}`，并有
    `summary()`（`体检：9/10 个能出答案 ·成功平均 2.2s`）与 `toJson()`；
  - 仅目录账号（4.29.12 的 `catalogOnly`）自动跳过。
- 设置页「数据」卡片新增按钮 **「体检：每个账号能不能真的出答案（走本地端口）」**，
  弹窗逐行显示 `✅/❌ 账号 · 模型 · 耗时`，失败的下一行给原文原因，末尾一行汇总，
  同时写进应用日志（可随「导出」带走）。
- 日志页支持**长按复制整段**（当前过滤后的内容），比先导出文件快。

## 真机结果（2026-10-17 18:51，4.29.14）

```
OK   WorkBuddy 1 | auto | 2074ms
OK   WorkBuddy 2 | auto | 2130ms
OK   Loomy 2    | auto | 1270ms
OK   Doubao 1   | doubao | 2085ms
OK   DeepSeek 1 | deepseek-chat | 1578ms
FAIL Loomy 3    | auto | 15010ms | timeout
OK   Kilo 免费池 | kilo-auto/free | 1340ms
OK   Trae 1     | Doubao-Seed-2.1-Turbo | 2786ms
OK   Trae 2     | Doubao-Seed-2.1-Turbo | 4169ms
OK   WorkBuddy 5 | auto | 2454ms
SUMMARY 体检：9/10 个账号能出答案 ·成功的平均 2.2s
```

WB3 / WB4 因为设了"仅目录可用"没有出现在列表里——这正是 4.29.12 想要的；
Loomy 3 是当天一直在抖的那个（检测里也反复超时），15 秒没回就如实记 timeout。

## 顺带

- 这次调试还确认了一件事：`frida-server` 长时间附着后会出现 `access violation`，
  重启 frida-server 即恢复（重启后 `Gateway.isRunning()` 仍为 true，网关没受影响）。
  这条写进注释与文档，免得下次误判成应用崩了。
- 版本 `4.29.14`（versionCode 580），自检 19/19。
