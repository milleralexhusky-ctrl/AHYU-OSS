# 4.59.0 — 两处 Anthropic 出站报不同的客户端版本 / OVH 自报版本落后 38 版

## 起因

盘"客户端版本号落后"这一项时，把仓库里所有写死的版本串列了一遍：

| 位置 | 写的版本 | 实际情况 |
| --- | --- | --- |
| `Claude.USER_AGENT` | `claude-cli/2.1.280` | npm latest 2.1.310 |
| `Upstreams.anthropicHeaders` | `claude-cli/2.1.280` | **同上，但另一份字面量** |
| `Codex.USER_AGENT` | `codex-tui/0.154.0` | npm latest 0.164.0 |
| `Cline.UA` | `Cline/3.0.65` | npm latest 3.0.73 |
| `Trae.IDE_VERSION` | `0.1.52` / `20260811` | 查不到公开来源 |
| `Ovh.UA` | `AHYU/4.21.0` | 本机已是 4.59.0 |

看完之后分了两类，**只动了一类**。

## 改了什么

### 一、两处 Anthropic 出站共用同一个 UA（真缺陷）

`Claude.java`（OAuth 上游）和 `Upstreams.anthropicHeaders()`（Anthropic 中转出站）
**各写了一遍** `"claude-cli/2.1.280 (external, cli)"` 字面量。改一处漏一处，
上游看到的就是"同一个网关、两条路径报了不同的客户端版本"——像两台机器。

现在只有一份：`Claude.USER_AGENT`，`Upstreams` 引用它。并统一到 2.1.310
（npm `@anthropic-ai/claude-code` 的 latest，2026-10-23 发布）。

自检里加了一条：`Upstreams.anthropicHeaders()` 发出的 UA 必须**等于**
`Claude.USER_AGENT`。以后谁再改单边，自检直接红。

### 二、OVH 的自报版本改成运行时读取（纯准确性问题）

`Ovh.UA` 写死 `"AHYU/4.21.0"`，到 4.59.0 已落后 38 个版本——我们在上游日志里
一直自称一个不存在的旧版本。改成 `AHYU/<运行时版本>`，取不到就退回 `AHYU`。

这不是冒充官方客户端（OVH 是零凭证公开池，没有官方客户端可冒充），
只是如实说明"谁在调用"。

## 没改什么，以及为什么

**Codex / Cline / Trae / Loomy / Antigravity / WorkBuddy 的版本串一个都没动。**

它们和上面两处性质不同：那些是**冒充官方客户端的指纹**，上游完全可能按它做门禁：

- `Xai.java` 的注释写得很明确——"代理那边的校验是**下限**（min gate），写旧了会被判版本过旧"；
- `Trae.java` 写着"upstream gates model availability on X-Ide-Version"；
- WorkBuddy 的 `2.137.1` / `2.148.0` 是从反编译出来的 wb2api 里抄的实测值。

这类值**"看起来旧"不等于"该更新"**。手上没有任何证据表明 Anthropic / OpenAI / Cline
按版本拒绝请求，同样也没有证据表明它们不会。盲目跟 npm latest 追，是拿一条
现在能用的渠道去赌一个没人验证过的数。

特别说明 Trae：本机 `Trae 1` 在 `IDE_VERSION=0.1.52` 下 **522 次 ok / 58 次 empty**，
工作正常。这说明当前值没有被门禁拦住——也就没有理由动它。

## 验证

- 真机自检 **62/62 通过**（4.59.0）。
- 新增断言：两处 Anthropic 出站 UA 必须一致。
- 没有端到端验证 UA 版本从 2.1.280 变 2.1.310 的影响：本机没有 Claude 账号，
  Anthropic 渠道是**零配置、零调用**状态（当前只配了 2 个 WorkBuddy 账号）。
  这次改动在能验证的范围内只证明"两处一致"，不证明"版本更对"。

## 未做 / 边界（重要）

- **2.1.280 → 2.1.310 这一步没有证据支撑它是必要的**，也没有证据说它有害。
  改它的理由是"同一份常量总得选一个值，选当前 latest 比选一年前的更不容易被
  按版本门禁的上游拒绝"。如果后续发现 Anthropic 中转按版本拒绝，应当回退或做配置项，
  而不是继续往上追。
- Codex / Cline 的落后版本保持原样，理由见上。
- 没有做"版本号自动跟随上游"的机制。这类指纹一旦自动化，上游一改判据就会
  在无人察觉时把所有渠道同时改坏。

## 产物

- `AHYU-4.59.0.apk`（versionCode 673）
