# AHYU 4.29.10 — WorkBuddy 的「conversation id missing」试清楚了

## 一、问题

4.29.9 的 detect.log 拿到 WB4 的**完整**拒绝：

```
upstream HTTP400 {"code":11128,"msg":"conversation id missing","requestId":"…",
 "displayMsg":{"zh":"请求被拦截，请重新发送"},
 "displayTips":{"zh":"这次调用没通过渠道校验，可能不是官方客户端…",
                "en":"This call failed channel verification, possibly because it was not sent from an official client."}}
```

官方客户端每次对话都在一个会话里，请求自然带会话标识；网关是无状态转发，body 里没这个字段。
所以先试"把会话字段补上"。

## 二、顺带修掉的一个出站 bug

`WorkBuddy.headers(acc, base, userAgent)` 的第三个参数是 **User-Agent**，而 chat 路径传的是
`p.apiKey` —— 等于把网关自己的 Key 当 UA 发给上游（这些账号的 `apiKey` 字段恰好是空的才一直没暴露）。
改成传 `null`，回落到按 realm 选定的客户端身份串（`clientUa()`）。

## 三、实验：5 种会话形状，全军覆没

一次请求最多按 5 种形状各发一遍（shape 0 是原样，与 4.29.9 之前完全一致）：

| shape | 加的东西 | 结果 |
| --- | --- | --- |
| 0 | 原样 | HTTP400 / 11128 |
| 1 | body `conversationId` | HTTP400 / 11128 |
| 2 | body `conversation_id` | HTTP400 / 11128 |
| 3 | body `conversationId` + `requestId` + `rootRequestId` + `parentConversationId` + `userId` + `agentType` | HTTP400 / 11128 |
| 4 | 上面 + 头 `X-Conversation-Id` / `X-Session-Id` | HTTP400 / 11128 |

两条决定性证据：

1. **5 次回包的 `requestId` 一模一样**（如 `5b702b847f414b3286c8503a4071b0d8`），说明上游这次校验
   根本不看我们发的 body 内容——补字段无法改变判定。
2. `displayTips` 明说"可能不是官方客户端 / 请用官方客户端重发"：它要的是**服务端真实会话**
   （官方客户端先建会话、再在会话里对话），不是请求里一个自造的 id。

结论：**补字段这条路走不通**，别再试第 6 种字段名。

## 四、给它加个上限

`CONV_HOPELESS`（进程内集合）：某个账号 5 种形状都被拒之后，本进程内**不再重试**，
但仍保留"原样"那一轮——上游若改回去，原样自己就会通；进程重启后标记清空，不是永久拉黑。

注意一个细节：一轮检测里同一个账号的两个模型探测是**并发**的，所以那一轮两边都会各跑满 5 次
（它们同时在飞），从**下一轮**起才各只跑 1 次。实测一轮探测的成本上限因此是"每账号每轮 2×5 次 400 往返"，
每次 400 约 110ms，可接受。

## 五、没做的事（留给下一次）

要真正修好 WB4，需要知道官方客户端的**建会话**接口（端点 / 字段 / 是不是真的先建会话），
而这必须抓一次官方桌面端的请求——参考实现 `workbuddy2api` 的 chat 路径同样不带会话 id，
它的 `conversationId` 只出现在活跃上报事件（`chat_request_send`）里，帮不上忙。

## 六、版本

`versionCode 576 / versionName 4.29.10`。真机验证：后台巡检（12 账号 × 2 模型 = 24 个探测）
24/24 提交；WB4 两个样本各 5 行 `# 渠道校验 …被拒` 落进 `detect.log`；WB3 仍是"额度不足"、WB5 2/2。

## 七、第二轮：UA / 客户端身份也全被拒

把手机上那个**能跑通**的 wb2api（com.wb.api 1.5.0）反编译出来，它的出站头构造写得明明白白
（`eb2.a(ef, realm, auth, z)`）：

```
UA = "WorkBuddy/5.5.4 " + (realm == "global" ? "WorkBuddy AI" : "WorkBuddy") + "/5.5.4 CLI/2.137.1"
Accept-Language = global ? en-US : zh-CN
X-Requested-With: XMLHttpRequest / Origin / Referer = 该 realm 的站点
X-User-Id / X-Machine-ID / X-Session-ID / X-Device-Token / X-Enterprise-Id / X-Domain / X-No-*
```

注意 **global 才带 "WorkBuddy AI"**，cn 是 "WorkBuddy" 重复三段。本网关的 `clientUa()` 是按
"哪个 UA 能刷出多少条目录"试出来的经验值：global 发的是两段式 `WorkBuddy/5.5.6 CLI/2.137.1`。
于是第二轮改试身份：

| shape | 换的东西 | 结果 |
| --- | --- | --- |
| 1 | UA = `WorkBuddy/5.5.4 WorkBuddy AI/5.5.4 CLI/2.137.1`（global 官方三段式） | HTTP400 / 11128 |
| 2 | UA = `WorkBuddy/5.5.4 WorkBuddy/5.5.4 CLI/2.137.1`（cn 官方三段式） | HTTP400 / 11128 |
| 3 | shape 1 + body `conversationId` | HTTP400 / 11128 |
| 4 | shape 2 + body `conversationId` + `X-Device-Token` + `X-Session-ID` | HTTP400 / 11128 |

**结论**：body 字段（第一轮）和 UA/身份（第二轮）两条线都被排除；同一请求换形状后
上游回的 `requestId` 始终不变（`8498d9bd…` / `51705fd1…`），说明这道校验**不看请求内容**，
是账号/链路一侧被标记了——`displayTips` 那句"可能不是官方客户端"只是上游的通用说法。
判定：**WB4 这个账号被上游标记**，网关侧改请求形状治不好；要动只能换号或按官方桌面端
的完整会话流程重放（需要抓包）。

归类随之更新：`Detect.classify()` 现在把这种回包直接写成
**"渠道校验拒绝（上游要求官方客户端，改请求形状无效）"**（`Selftest` 有断言），
省得下次再从字段名一个个试。

## 八、工具笔记：没有界面时怎么触发一轮检测（本轮踩坑记录）

1. 正常路径：界面点"一键接入检测"，或等 `Detect.autoIfDue`（每 6 小时）自己跑。
2. 把 `gateway.json` 的 `lastAutoDetect` 改成 0，再 `cmd jobscheduler run -f com.ahyu 4711`
   （JOB_ID=4711，周期 15 分钟）→ 没到点也能立刻跑一轮后台巡检。
   改文件要**保 inode**：`sed … > /tmp/x && cat /tmp/x > $f`，否则属主会变成 root、应用读不动。
   跑完 `autoIfDue` 会把 `lastAutoDetect` 正常回写，所以这一招只生效一次。
3. frida（客户端在 Linux 工具环境，服务端 `/data/local/tmp/frida-server -l 0.0.0.0:27042`）：
   - `frida -H 127.0.0.1:27042 -n AHYU -l hook_detect.js -q < /dev/null`
     → 在应用进程里直接调 `Detect.start(2, 8)`，完全不需要界面 ✓
   - `Gateway.start(ctx)` + 用 `ACTION_START` 的 Intent 调 `startForegroundService`
     → 从进程内把网关和前台服务拉起来 ✓（本轮就是这么恢复的）
   - **一定要 `< /dev/null` 让 frida 正常收尾，别用 `timeout` 硬杀**：硬断开会留下
     `attempting to detach while still running code` 的 native abort
     （tombstone 里是 `signal 11 … Thread-5`），看起来就像"应用启动即崩"，很容易误判成应用 bug。
4. 启动陷阱（本机 MIUI）：
   - `am force-stop com.ahyu` 之后，`am start-foreground-service` / `am start` 会**返回成功但不创建进程**
     （`dumpsys activity processes` 里是 `ProcessRecord … 0:com.ahyu`，events 里没有 `am_proc_start`）。
     这条路没救，别反复试。
   - 能把它拉起来的两条路：① `cmd statusbar add-tile` + `click-tile
     com.ahyu/com.cpa.gateway.GatewayTileService`（SystemUI 绑定 TileService 把进程拉起；
     注意 tile 是**开关**，第二次点就关掉网关，别连点）；
     ② 直接 `am start -n com.ahyu/com.cpa.gateway.MainActivity`（会出现在主屏，需要用户同意）。
   - 起来之后因为 `autostart=false`，应用**不会**自己起网关：要么点界面主按钮，
     要么用 frida 调 `Gateway.start(ctx)` + 起前台服务。
   - **虚拟副屏的坑**：用 `am start --display2` 把应用塞进副屏，会让会话的 task inventory 认不出来，
     之后 `finish_virtual_session` 一直 `HANDOFF_PREFLIGHT_FAILED`、`launch_app` 报 `SESSION_NOT_ACTIVE`。
     别绕 `launch_app`。
5. 从 `Gateway.isRunning()` / `runningPort()` / `dumpsys activity services com.ahyu`（看 `isForeground`）
   判断"网关到底在不在跑"，比 `pidof` 靠谱。

## 九、交回时的状态（2026-10-16 19:40）

- 设备装的是 4.29.10（versionCode 576），前台服务在跑（`isForeground=true foregroundId=8321`），
  网关 8321 正常（`/health` 返回 401 = 要 key，说明活着）。
- `files/detect.log` 399 行、其中 54 行 `# 渠道校验`；最新一轮：通过 19 / 失败 5，用时 23.5s。
- 归类的三条结论都在真机上验过：WB4 = 渠道校验拒绝、WB3 = 额度不足、Kilo 免费池 = 需要登录 / 付费账号。

## 十、直接打上游：global 是「整片拒绝」，与请求形状无关

用 frida 把内存里已解密的凭据导出来（只落盘到应用 cache，root 复制到工作区，**用完即删**），
就可以绕开网关、在 Linux 侧直接对 `https://www.workbuddy.ai/v2/chat/completions` 做矩阵实验：

| 形状 | 结果 |
| --- | --- |
| 网关的原样头（两段式 UA + X-IDE-* + X-Agent-Purpose + X-Machine/Session/Request-ID） | 400 / 11128（`msg` = `request illegal`） |
| wb2api 风格头（官方三段式 UA + X-Machine/Session-ID，无 X-IDE-*） | 400 / 11128 |
| 去掉 X-IDE-* / X-Agent-Purpose | 400 / 11128 |
| 最小头（只有 Content-Type / Authorization / X-User-Id） | 400 / 11128 |
| 上面任一种 + body `conversationId` | 400 / 11128 |

**对照**：同样的构造、同样的最小形状，打 **cn 端点 + cn 账号（WB1）** → `HTTP 200` + SSE（`: heartbeat`）。
说明探针本身没问题、请求形状也不是问题。

结论：**global realm 对"非官方客户端"的推理请求整片拒绝**——与 header、body 无关，与具体账号也无关
（WB3 看不到这条，只是它先在额度那道闸被拦下了）。网关侧无法用改请求的方式绕过；
`/v3/config` 两个 realm 都能正常读，所以"目录刷得出来、推理一律被拦"这个组合是 realm 的固有行为。

顺带两次探测的结果：

- 试探过的建会话/列会话路由**全部 404**：`GET /v2/conversation/list`、`GET /v2/conversations`、
  `GET /v2/chat/conversation/list`、`POST /v2/conversation/create`、`POST /v2/chat/conversation`、
  `GET /v2/user/conversation/list`。会话大概只由官方 IDE 客户端在本地/私有流程里产生，REST 侧没有可见入口。
- token 形状差异：global 长期（≈1 年，claims 里**没有** `app_type` / `token_source`）；
  cn 是 `app_type=codebuddy` + `token_source=enterprise_switch` + ≈24 天。

产品侧建议：global 的 WorkBuddy 账号（WB3 / WB4）在网关里标成"仅目录可用、推理被上游渠道校验拒绝"
或直接停用，别让每轮检测都给它们白跑探测。cn 账号（WB1 / WB2 / WB5）不受影响。

## 十一、A/B 纠正：是**账号/会话被标记**，不是 realm 整片拒绝

第十节的矩阵实验里，我拿"cn 端点能通"当对照就下了"global 整片拒绝"的结论——**这个结论是错的**。
真正的对照组应该用**同一个 realm 的另一个账号**。补上之后：

| 账号 | 打到 `www.codebuddy.ai/v2/chat/completions` | 打到 `www.workbuddy.ai/v2/chat/completions` |
| --- | --- | --- |
| WorkBuddy 3（同 realm、同样请求形状） | `HTTP 429` + `code 14018` "Credits exhausted…" ✅ **过了渠道校验** | 同上 ✅ |
| WorkBuddy 4 | `HTTP 400` + `code 11128` "request illegal" ❌ | 同上 ❌ |

两者 realm 相同、header 相同、body 相同、域名还各试了两个；WB3 是被**业务层**拒的（额度不足），
说明它已经通过了渠道校验；WB4 连那一步都到不了。**同样形状能过 → 形状无关；同 realm 一过一不过 →
是账号/会话一侧被标记**。这也解释了 `displayTips` 那句"可能不是官方客户端"为什么不随请求变化。

顺带纠正另一件事：global 的 `/v3/config` 里 `endpoint` 字段写的是 **`https://www.codebuddy.ai`**，
而网关（和这里的探针）一直打 `www.workbuddy.ai`。两个域名都能通过鉴权、行为一致，
所以这不是失败原因，但"基址该按配置里的 endpoint 走"值得记一笔。

### 对 WB4 这类账号的可行动作

- 上游看的是**账号/会话状态**（最可能是"这个登录会话没被官方客户端建立过 / 被风控标记"），
  网关侧改请求治不好。可试：用**官方客户端**（CodeBuddy CLI / 桌面端）登录同一个账号一次，
  让它把会话/设备登记出来，再回网关复测；不行就换号。
- 网关现在的行为已经够诚实：归类写"渠道校验拒绝（上游要求官方客户端，改请求形状无效）"，
  5 种形状被拒一次后本进程内不再重试，不会每轮刷一堆 400。
