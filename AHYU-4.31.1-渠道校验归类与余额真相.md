# AHYU 4.31.1 — 渠道校验归为独立类别 + 余额真相（versionCode 608）

本轮从「WB4 的 11128 怎么修」开始，最后证明**修不了**（结论与 4.29.10/4.29.11 一致，但新增了
5 条决定性证据），于是把力气改用在两件真能改善体验的事上：**11128 不再冒充限流**，
以及**「额度耗尽」这句话到底是什么意思**。

## 一、WB4 的 11128：又试了 4 类新假设，全部否决

用 wb2api 1.6.3（com.wb.api，2026-10-17 更新）反编译出的**真实链路**当参照：
`sd2.invokeSuspend` 打 `{base}/v2/chat/completions`，头由 `ae2.a()` 构造。逐条对齐后测试：

| 假设 | 来源 | 结果 |
| --- | --- | --- |
| 缺 `X-Device-Token`（我们是"不带"，官方是"带空串"） | ae2.a() 无条件 `c("X-Device-Token", ...)` | WB4 仍 11128；WB3 仍过 |
| UA 要三段式 `WorkBuddy/5.5.4 WorkBuddy AI/5.5.4 CLI/2.137.1` | su1.x() | 仍 11128 |
| body 要 `conversation_id` | wb2api 支持这个入参 | 仍 11128 |
| body 要 `prompt_cache_key`（`wb2a-`+24hex） | do1.b() | 仍 11128 |
| 换域名 `www.codebuddy.ai`（config.endpoint 自己写的） | `/v3/config` 的 `endpoint` 字段 | 仍 11128 |
| 极简头（只有 Authorization + X-User-Id） | 排除"我们头带多了" | **WB4 仍 11128，WB3 一次就过（429 额度）** |

**关键对照**：同一个请求、同一组头、同一个 realm，WB3 → `429 14018`（过了渠道校验），
WB4 → `400 11128`。所以：**不是 realm 的策略、不是请求形状、不是缺头**，是账号本身。
可疑点最集中的是 `device_token`：wb2api 从 OAuth 响应里拿 `device_token`/`device_id`
（`AuthEntry` 有这两个字段，登录代码 `ud2` 会读），而我们的账号登录后这两个字段都是空的。

### 另一个必须记住的发现：11128 是**按模型**的

同一天、同一台设备、同一份代码：

```
WorkBuddy 4 / glm-5.3        → 正常出字 ✅
WorkBuddy 4 / fast-model     → 正常出字 ✅
WorkBuddy 4 / default-model  → 14018 Credits exhausted
WorkBuddy 4 / gpt-5.5        → 400 11128
WorkBuddy 3 / default-model  → 400 11128（同一时刻 WB3 的其它模型也能出字）
```

所以「WB4 被整体标记」这个说法也不准确：**是"某些模型 × 某些账号"这个组合被拦**。
它看起来像上游的推理路由（`-model` 别名 → 后端）上的风控。
`usage.tsv` 里 4.29 时代 WB3/WB4 用 `gpt-6-sol` 是**成功**的（10-15 那天几十条 ok），
说明这个拦截是后来上线的、且与模型名有关。

### 所以"修"这件事的结论

网关侧改请求**治不好**，别再试第 6 种字段名。可做的只有：把它**正确归类**、
**别让它退避**（退避没用）、**别让它冒充限流**、并给用户一句能照做的话。这就是下面第二节。

## 二、11128 从"限流"里拆出来（本轮主要改动）

以前 `bizKind()` 把 11128 归成 `rate`，后果有三个：
1. Router 按限流退避（1 分钟起、指数涨），而这个错误**退避多久都不会好**；
2. 界面/日志读起来像"被限流了，等一会儿"，用户会一直等；
3. 快速失败（`confirmedBlockUntil`）只认"上游给的确切时刻"，11128 没有时刻，于是永不快速失败。

现在：
- `WorkBuddy.bizKind()` → `code 11128 / "channel verification" / "request illegal"` 归 **`channel`**；
  `14018 Credits exhausted` 归 **`quota`**（以前落到 `rate` 兜底）。`Http.kindOf()` 加 `channel(body)`。
- `Router.recordThrottled()` 对 `channel` 给**固定 6 小时**回避并写一条明确日志
  （"改请求形状无效，要换号或重新登录"），不用指数递增（那是对限流的做法）。
- `Router.Stat` 新增 `throttleKind`，并补 `throttleUntil(key)` / `isChannelBlocked(key)`。
- `Server.confirmedBlock()`（原 `confirmedBlockUntil`）改为返回 `{until, channel}`：
  这批候选**全**是 channel 时才挡，否则照发。`writeAllThrottled()` 据此分流：
  channel → **403** + "请换号或重新登录，等下去不会自己恢复"；其余仍是 429 + 恢复时刻。
- `writeError()` 现在把 `kind` 一起写进错误体（`error.kind = channel|rate|quota|…`）——
  以前"要换号 / 要充值 / 等一会儿"在客户端看来一模一样的 `api_error`。

## 三、"额度耗尽"到底是什么意思（本轮最有价值的发现）

WB3/WB4 的推理都回 `14018 Credits exhausted`，但 `get-user-resource` 一看就有余额：

| 账号 | TotalDosage | 明细 |
| --- | --- | --- |
| WB3 | 100 | Bonus Pack 250/已用 250/剩 **0**（到 10-22）；Free Plan 100/**剩 100**（到 10-31）|
| WB4 | 115 | Bonus Pack 250/已用 234.43/剩 **15.57**（到 10-21）；Free Plan 100/**剩 100** |
| WB1 | 1978 | 20 个包，最大一张个人体验版 500 未动 |

也就是说：**"耗尽"指的是当前被计费的那张包，不是整个账号**；还有没被动用的包就白摆着。
用户只看到"额度不足"，第一反应是充值 —— 而正确答案往往是"换号"或"别再撞这个组合"。

于是加 `WbTasks.balance(p)`（只读，只调 `get-user-resource`）+ 账号详情页一行提示：
「💰 上游仍显示有 N credits 可用（先到期：XXX 剩 M，到 YYYY-MM-DD）；
但推理仍被判"额度耗尽"——通常是被计费的那张包用完了，换号比充值更快见效」。
`total` 优先用上游 `TotalDosage`，拿不到才用各包之和。

## 四、顺手修的两处真 bug

1. **`endpoint` 以前没人用**：`/v3/config` 自己写着 `endpoint: https://www.codebuddy.ai`，
   而我们一直打 `www.workbuddy.ai`。现在 `WorkBuddy.endpointFor()` 按账号缓存上游自报的
   endpoint（带 realm 校验，global 只认 codebuddy.ai/workbuddy.ai，cn 只认 .cn/tencent.com），
   chat / 拉目录 / 探测全走它；拿不到才回落 `primaryBase()`。
2. **`catalogOnly` + 空目录 = 永久没模型**：检测里"仅目录账号不用刷目录"这个优化没检查
   `models` 是否为空。目录从没成功写过的账号（新建、上次刷新失败）会一直零模型、永不探测、
   连手动选都选不到。现在只有目录非空才跳过。

## 五、验证（真机，4.31.1 / 608）

- `Selftest.runAll()`：**19/19 通过**（含既有 `checkConfirmedThrottle` / `checkResetParsing`）。
- `bizKind(14018) = quota`、`bizKind(11128) = channel`；`Http.kindOf(429,"Credits exhausted") = rate`
  （非 WorkBuddy 的通用路径保持原样）、`kindOf(400, 11128) = channel`。
- `confirmedBlock()` 直接调：未标记 → 不挡；标记 channel → 挡 6h 且 `channel=true`；
  改成 quota → 不挡（因为不是"上游给时刻"）；换未标记的模型 → 不挡。
- `writeError(...,'channel')` 输出 `{"error":{…,"kind":"channel"}}`，`'rate'` 输出 `"kind":"rate"`。
- `WbTasks.balance()`：WB1=1978、WB3=100、WB4=115，`soonest` 指向正确的那张包。
- 真请求：`WorkBuddy 4/glm-5.3` → 正常出字；`WorkBuddy 4/default-model` → 14018；
  `WorkBuddy 3/default-model` → 11128（与上表一致）。

## 六、调试踩的坑（下次省时间）

- **一次 `frida -H … -l` 里只能可靠地挂一个 `implementation`**：第二个起静默不生效
  （日志完全没有），别怀疑方法被内联。要挂多个就分多次运行。
- `Java.use('…Upstreams$LineSink')` 能在 frida 里取到类，但 `Java.registerClass` 实现它会被
  包内可见性挡住（`IllegalAccessError: inaccessible`）；而 `com.cpa.gateway.core.Http` 是
  包内类，在 frida 里 `HC.call(...)` 会 `TypeError: not a function`。
  → 想在进程内构造上游请求，得用**已经导出的方法**（如 `Server.probeOne`），
  或干脆在 Linux 侧用导出的凭据直接打（本轮就是这么对齐 wb2api 的）。
- `frida -e "..."` 这种内联脚本打不出来（只进 REPL），必须落成 `.js` 文件再 `-l`。
- MIUI 上 `pm install` 之后 `am start` 常常不创建进程（返回成功），
  老办法仍然有效：`cmd statusbar add-tile` + `click-tile com.ahyu/com.cpa.gateway.GatewayTileService`，
  起来后再用 frida 调 `Gateway.start(ctx)`。
- 排查"我改的代码到底跑没跑"，最有效的是**直接调方法看返回值**
  （`bizKind` / `confirmedBlock` / `balance`），比 hook 调用点可靠 —— 本轮五六次就是这么定论的。
