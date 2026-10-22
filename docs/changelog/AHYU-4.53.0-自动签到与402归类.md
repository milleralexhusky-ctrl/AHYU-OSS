# 4.53.0 — 每天自动签到领额度，以及"加免费渠道"必须先修的那个坑

## 起因

用户提的是：**「我希望你再加上更多有免费余额和可以签到或者做任务获取余额的。」**

顺着这条往下查，发现三件事，其中第二件是**加任何新免费渠道之前必须先修的前置条件**。

---

## 一、自动签到：额度是按天发的，不领就作废

WorkBuddy / Trae / Loomy 的免费额度**当天有效**：签到才有、任务当天不接就过期。
而网关本来就是常驻的 —— 让它顺手在后台领掉，用户不必记着每天点一次「一键做任务」。

原先这三类平台只有用户手动点按钮才会跑，自动链路里**一次都没有**。

### 为什么这一条可以自动做，而"删账号"之类不行

它是**幂等**的。真机实测重复执行的结果：

```
00:14:26  自动签到：WorkBuddy 1 · 余额 600.0 → 600.0（本次 +0.0） · 今天已签到
```

上游回 `code=10001`（今天已签到），没有任何副作用。全项目其它写操作都要用户明确点，
正是因为它们不幂等。所以"自动做"的边界不是"是不是写操作"，而是**重复执行有没有害**。

### 实测确实领到了

第一次自动跑的时候余额 **500 → 600（+100）**：

```
23:58  GET /v1/debug/checkin → "credits": 600     （此前一直是 500）
```

### 跨天怎么判

不按"本地零点"，按**固定间隔 20 小时**。跨天的边界在上游不在本机，
按本地日历日会被时区、夏令时、以及"用户把手机时间改了"带偏。
20 < 24 保证每天至少覆盖一次；幂等保证多跑无害。

### 顺手收掉一个重复判据

`LOOMY || TRAE || WORKBuddy` 这个集合原先散在**四处**：`Server.runTasksInner`、
`Server.runAccountChores` 的分派、`MainActivity.doesChores`、以及这次新加的自动签到。
收成一个 `Server.hasChores(type)`，三处改调它。

这不是洁癖：**这版第一稿就漏了 Loomy**（只写了 workbuddy/trae），
表现是"Loomy 的每日宠物打工积分悄悄不会自动领"——不报错，只是少一块额度。

---

## 二、加免费渠道之前必须先修的坑：402 ≠ 额度耗尽

我按用户的要求去实测候选的免费池。研究阶段的结论是"Pollinations 零凭证可用"，
但**实测把它推翻了**（`gen.` 域名匿名一律 401）。继续测到老域名，发现它能用，
同时撞到这个坑：

```
HTTP 402 {"code":"FREE_LIMIT",
          "message":"Free limit reached: 1 reply every 30 seconds per IP. Retry after 26s, …"}
```

它用 **402（Payment Required）表达一个 30 秒的速率限制**，而且**响应头里没有 Retry-After**。

按原来的代码这条路会怎样：

| 环节 | 原行为 | 后果 |
| --- | --- | --- |
| `Http.kindOf(402, body)` | 一律 `quota` | 归类错 |
| `Router.recordThrottled` | quota → 抬到**最少 30 分钟** | 26 秒的窗口锁 30 分钟 |
| `Router.accountScoped` | quota 是**账号级**事实 | 整个池子所有模型一起回避 |
| `Http.defaultBackoffMs(402)` | **1 小时** | 比上面还长 |

也就是说：**把这个池子加进来，会比不加更差** —— 一次 26 秒的限流把免费池锁一小时。

### 修法是两层

**归类层**（`kindOf`）：正文承诺了短窗口（`< 1 小时`）的 402 算 `rate` 不算 `quota`。
但**上游点名的事实优先于措辞推测** —— `insufficient_quota` 的报文里也常带一句
客套的 "please try again later"，那种必须仍算额度耗尽，否则 4.52.2 刚修的问题
会以另一种形式回来。这条单独有断言。

**时长层**：退避必须跟着**归类**取，不能再看状态码。
这里有个很容易漏的点 —— 只改归类不改时长，会得到"日志、归类、文案全对，
只有等待时间是错的"，是最难查的一类 bug。
同时新增 `throttleBackoffMs`：**先认上游正文里明说的窗口**（`parseResetAtMs` 其实
早就认 "retry after 26s" 这个格式，只是三个抛异常的地方都只查 HTTP 头、没人调它），
比默认值更近也要用它 —— 原来"只在比默认更远时才采用"的规则会把 26 秒盖成 1 小时。

### 最后没有新增 Pollinations 这个类型

我先按"零凭证池"的模板写了 `core/Pollinations.java`（约 150 行），
然后用一条真请求验证：**走现有的通用 OpenAI 兼容路径 + 空 Key，同样能发出去**
（3.0 秒返回真回答、带 usage）。因为 `Upstreams.OpenAiUp` 本来就是
`if (!p.apiKey.isEmpty()) headers.put("Authorization", …)` —— 空 Key 自然就是零凭证。

而预置列表里**早就有** "Pollinations（免注册）"，地址就是实测能用的那个
`https://text.pollinations.ai/openai`。

所以那个类是**冗余代码**，删了。加一条类型要动 14 个触点（dispatch、fetchModels、
openAiCompatible、ShortName、VendorHome、Ui 的三个映射、ProviderEditor.ORDER…），
每一处都是将来的维护面；能靠"空 Key"解决的就不该新开一个类型。
用户要的"更多免费余额"这件事，靠上面的坑修好 + 已有的预置 + 自动签到，
比多一个只支持 1 个模型、30 秒一次的池子实际得多。

---

## 三、清掉一个已经死掉的预置

预置列表里有 **GitHub Models**，地址 `https://models.inference.ai.azure.com`。
实测：TCP 连上了但 **TLS 握手永远完成不了**（`Cipher is (NONE)`），同一个网络上
`models.github.ai` / `api.github.com` 的 TLS 都正常 —— 所以不是本机网络问题。

官方文档确认：

> As of July 30, 2026, GitHub Models has been fully retired. … the playground, model
> catalog, inference API, and bring your own key (BYOK) are no longer available to any customer.

用户选它会卡到一个超时报一个看不懂的错。已删。

**这里有个比"删错了"更危险的坑**：`names[]` 和 `bases[]` 是**两个平行数组按下标配对**的，
只删一个元素会让**后面所有预置的名字和地址整体错位** ——
于是"选了 Groq 实际填进 Mistral 的地址"这类事会发生，而且不报错。
删完立刻校验两边长度（33/33）并对了几对已知的对应关系。

顺带发现 `Cerebras` 也名不副实：官方要求**绑卡**才给一次 $5 且 30 天过期，
不是"免费"。这一条**这次没动**（删它同样要处理平行数组，而且有些用户就是绑了卡要用），
记在这里。

---

## 验证

- 真机自检 **52 / 52**
- 自动签到实跑：`自动签到：WorkBuddy 1 · 余额 600.0 → 600.0 · 今天已签到`（幂等成立）
- 第一次跑：余额 **500 → 600（+100）**，真领到了
- `hasChores` 生效：调试端点的 `candidates` 里现在能看到 loomy 类型账号
  （此前 workbuddy/trae 之外一律不列）
- 402 归类的断言在 `checkQuotaKind` 里，共 7 组正反用例（含优先级那一组）
- 通用 OpenAI 路径 + 空 Key 打 Pollinations 实测通过（3065ms，带 usage）

## 改动清单

- `core/Config.java`：`autoCheckin`（默认开）/ `lastCheckinAt` / `CHECKIN_INTERVAL_S`
- `GatewayService.java`：`handleAutoCheckin()` 挂进看门狗循环（和自动更新同一处）
- `core/Server.java`：`hasChores()` 收成唯一判据；调试端点 `GET/POST /v1/debug/checkin`
  （查看状态 / 清日限立刻真跑一次，便于验证这条定时链路）
- `core/Http.java`：`transientWindow()` + `kindOf` 优先级 + `defaultBackoffMs(code, kind)`
  + `throttleBackoffMs()`（三处抛 `Throttled` 的地方统一走它）
- `MainActivity.java`：`doesChores` 改调 `hasChores`；删掉已退役的 GitHub Models 预置
- `core/Selftest.java`：`checkQuotaKind` 扩到 7 组断言

## 未验证 / 已知限制

- **Trae / Loomy 的自动签到没有真机实跑过**：本机账号池里只有一个 WorkBuddy 账号，
  没有 Trae / Loomy 账号可试。分派走的是与"一键做任务"同一个 `runAccountChores`，
  那条路是用户日常在用的；但"自动"这一侧只有 WorkBuddy 被实测覆盖。
- Pollinations 匿名池**只有 1 个模型、每 IP 约 30 秒 1 次**，当兜底可以，不能当主力。
- `Cerebras` 需要绑卡这个事实没在界面上说明（只记在这份文档里）。
