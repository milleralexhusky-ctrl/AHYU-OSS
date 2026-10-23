# AHYU 4.50.2：Antigravity 登录修复 + 坏模型/坏号/坏请求分开

versionCode 645。

## 一、Antigravity 登录不进去：占位串的锅

`Antigravity.CLIENT_SECRET` 一直是占位串：

```
static final String CLIENT_SECRET = "在此填入你自己的 OAuth Client Secret";
```

授权页能打开、也能拿到 `code`，但下一步用这个假 secret 去
`https://oauth2.googleapis.com/token` 换 token，Google 只会回 `invalid_client`。
用户看到的就是"Google 账号登录不进去"。

现在改成构建期注入：

- 新增 `BuildSecrets.java`，公库里是空串；
- `build.sh` 从 `.private/signing.env` 读取 `ANTIGRAVITY_CLIENT_SECRET`，临时写进去，
  构建结束由 trap 还原（和 `Debug.java` 同一套机制）；
- `Antigravity.clientSecret()` 优先用注入值，没有才退回占位提示。

私库的 `.private/signing.env` 已放入 Antigravity CLI 使用的公开 native-app
OAuth 客户端凭据。它按 Google 的定义不属于机密（native app 的 secret 不提供保密性，
安全性靠回环重定向），但仍按私密内容管理，**不出现在公库源码里**。

验证：

- 构建日志显示"已注入 Antigravity OAuth Client Secret"；
- `classes.dex` 里能查到 `GOCSPX-`（确认确实编进包了）；
- 构建结束后 `BuildSecrets.java` 恢复为空串，`git status` 不出现密钥；
- 登录链路本身需要用户点一次 Google 授权，代码侧能做的到此为止。

## 二、坏模型 / 坏号 / 坏请求：不再当一回事

以前所有失败都走同一条路：记这个「账号/模型」失败，然后换下一个**账号**。
这会把三种完全不同的东西混在一起：

| 失败 | 正确处置 | 以前 |
| --- | --- | --- |
| 凭证失效 / 额度 / 限流 / 渠道校验 | 换号 | 换号 ✓ |
| 工具参数不是合法 JSON、模型输出结构坏 | **换模型**，换号没用 | 把同模型的所有账号逐个打一遍 |
| 模型不存在 / 账号无权限 | 轮换时换模型；纯调用可换号确认 | 同上 |
| 请求本身不合法（消息序列、上下文超长、参数不认） | 直接告诉客户端 | 换号重试，甚至记账号失败 |

新增 `FailScope`，把异常归到六类，并明确两个模式：

- **纯调用**（客户端点名模型，含 `账号/模型` 前缀）：只换同模型的账号；
  模型行为坏了或请求不合法就**立刻停**，不换模型，返回明确的 4xx；
- **轮换**（`auto` / `auto-code` / `auto-long` / `auto-race`）：可以换号也可以换模型；
  某个模型坏了就只把**这个模型**从本轮候选里跳过，不牵连同模型的其它账号。

同时修了两处会"冤枉账号"的地方：

1. `serve()` 以前对所有非暂时性异常都写 `p.lastError`，账号详情就显示"最近调用失败"。
   现在模型/请求类失败只记日志与用量，不写账号错误；
2. 坏工具参数现在记进**模型**的工具画像（`ToolAbility.note(..., false)`），
   以后带工具的请求会绕开这个模型，而不是绕开账号。

错误响应也带上了类别：

```json
{"error":{"message":"工具参数不是合法 JSON（bash）：totally broken",
          "type":"model_error","code":"model_error","kind":"model"}}
```

### 验证

假上游 + 两个同模型账号，实测请求次数：

| 场景 | 上游请求次数 | 响应 | 账号 lastError |
| --- | --- | --- | --- |
| 坏工具参数（`BadToolCall`） | **1**（不是 2） | 400 `kind=model` | 空 |
| `model ... not found` | 2（两个号都确认） | 400 `kind=model` | 空 |
| `maximum context length exceeded` | **1**（不换号） | 400 `kind=badreq` | 空 |

`/health` 的调试账号 `badModels` 也保持为空：没有把模型问题写成账号问题。

自检新增「失败作用域」一项，真机 **30/30**。

## 三、仍未做

- **带工具的 logprobs 保真**：仍不开启。工具参数是分片到达的，要修复/换模型就得
  先缓存重写，和"原样透传"冲突；需要单独设计两段式路径。
- ~~**非 OpenAI 协议的 `auto`**：目前仍是"先解析成一个具体模型，再在同模型的账号间
  failover"，不会在失败后换模型。~~ **这条已过时，分两次追平（此处如实记录现状）**：
  - **Anthropic（`/v1/messages`，即 Claude Code 走的那条）已是完整跨模型轮换** ——
    4.51.1 起 `Server.handleAnthropic` 的虚拟分支直接取 `Router.candidates()`，
    流式也能从回空候选换到有内容的模型（`Server.java:6179`）。
  - **Gemini / Responses** 仍是"先解析成一个具体模型"，但已不再是 404：
    两者都改走 `resolveVirtual()`，`auto` 至少能落到一个具体候选上
    （`Server.java:6361`、`Server.java:6620`）。要变成完整轮换仍需换成
    `Router.Pick` 列表。这三个入口里，实际用得最多的是 Anthropic 那条。
- Realtime 音频、vector stores、file search、code interpreter：上游没有对应能力，
  不做空壳。
