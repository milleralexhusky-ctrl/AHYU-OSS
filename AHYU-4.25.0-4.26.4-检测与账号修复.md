# AHYU 4.25.0 ~ 4.26.4 —— 检测/任务/账号一批修复

这段时间的改动，按主题归类。每一处都写了"为什么"，方便接手时判断能否回退。

## 一、一键接入检测：慢、结果吓人、进度卡死

### 1. 线程池配置错误（根因，性能提升 3 倍）

四处线程池都用了**无界队列**：

```java
new ThreadPoolExecutor(2, 8, 30L, SECONDS, new LinkedBlockingQueue<>());
```

Java 线程池的扩展顺序是：**核心线程满 → 任务进队列 → 队列满 → 才创建新线程**。
`LinkedBlockingQueue` 无界，"队列满"永远不发生，于是 **maxPoolSize 永远用不上**：

| 池 | 写的 | 实际的 |
| --- | --- | --- |
| PROBE | 2~8 | **2** |
| CATALOG | 1~2 | **1** |
| SWEEP | 2~8 | **2** |
| IO | 2~16 | **2** |

**实测**：12 账号 × 2 模型 = 24 探测，修复前 27.1 秒 / 23 通过 1 失败；
修复后 **9.1 秒 / 24 通过 0 失败**。日志线程号证明修复后是 8 路并行。

改法：队列换 `SynchronousQueue`。**但注意**：无界队列原本是**有意**用的
（注释写着"改无界是为了不丢任务"），所以配套加了 `CallerRunsPolicy` 兜底。

### 2. CallerRunsPolicy 本身是错的（我引入又修掉）

`CallerRunsPolicy` 让被拒任务由**提交线程**同步执行，而提交线程就是 sweep
的 for 循环——池一满，整个调度被一个探测阻塞，时序全乱。
改成**信号量限流 + 默认拒绝策略**：先抢许可再提交，池里永远有空位。

### 3. 计数不是原子的

```java
volatile int done;  done++;   // 读-改-写，并发下丢更新
```
8 线程并发时进度数字永远追不上 total，看起来像"卡死"。
改 `AtomicInteger`。

### 4. P.running 从来没被设成 true

全工程只有 `P.running = false`（sweep 的 finally 里），**没有一处设 true**。
后果：
- `status().running` 恒为 false → 界面轮询第一轮就 break，**弹窗永远显示
  "完成 · 用时 0.0s · 通过 0 / 失败 0"**，而检测其实在后台正常跑
- `autoIfDue()` 的防重入失效
- **`stop()` 一直是空操作**

### 5. 探测超时

`probeDirect` 走 `serve()`，读超时 300 秒、首 token 看门狗 90 秒——那是给真实
对话设计的，探活不需要。加 `PROBE_TIMEOUT_MS`（先 8s，实测误杀慢模型，定 **15s**）。

**收尾时不要 `f.cancel(true)`**：中断会沿调用链把异常变成 `interrupted`，
既引出假故障，又让被 cancel 的探测不计数（出现 done 26 < total 36）。

### 6. 自动巡检规模过大

`autoIfDue()` 用 `start(40, 8)`——**每账号探 40 个模型**，12 账号 = 274 次探测。
它和手点检测**共用同一套状态**，后台跑时用户手点会被挡住、看到的是后台的中间快照。
改成 `start(2, 6)`，与手动检测口径一致。

## 二、账号"一会好一会坏"、模型"说用不了"

**两处都把"临时失败"当成了"永久故障"：**

### 1. 任何异常都写 lastError

```java
} catch (Exception e) {
    p.lastError = ...;                    // 包括 429 限流
    p.lastErrorAt = now;
}
```
而 `unusableReason()` 让账号在**接下来 30 分钟**显示"不可用"。
免费池账号被上游限流一次就挂半小时，下次成功又清掉——**反复横跳**。

**实测**：同一批 12 账号连跑三轮，失败数 2 / 0 / 1，变动的全是免费池的 429。

改法：新增 `Detect.isTransient()`，临时失败（429 / 超时 / 5xx / 连不上 /
**interrupt**）**不写 lastError**。

### 2. 任何失败都拉黑模型

```java
} else if (!p.badModels.contains(model)) {
    p.badModels.add(model);   P.hidden++;      // 从界面隐藏
}
```
`classify()` 明明已识别出"被限流（稍后自动恢复）"，但这个分类**只用于显示、
没用于决策**。改法：`isTransient(why)` 时不拉黑，只记一条日志。

### 3. interrupt 会被误判

探测超时用 `Future.cancel(true)` 中断线程，异常信息变成
`thread interrupted`——**这不是上游的问题**。真机上因此给 3 个账号扣了假故障。
`isTransient` 里补上 `interrupt` 关键词（同时收尾不再 cancel）。

## 三、Antigravity（谷歌账号）

### 1. tier 永久缓存 → 改 TTL 刷新

原来 `ensureProject()` 里：
```java
if (!acc.projectId.isEmpty()) return acc;   // 解析过一次就永远不再问谷歌
```
订阅变化（充值 AI Pro、升级、到期）后仍按旧档位请求。
改成按 `TIER_TTL_SECONDS`（1 天）过期重查。`ensureFresh()` 刷新 token 时
**必须一并保留 `tierCheckedAt`**，否则 TTL 每次归零 → 每请求都打谷歌。

### 2. 模型清单是写死的（5 个）→ 改探测式

原来硬编码 `gemini-3-pro-preview / gemini-3.8-flash / gemini-3-flash /
gemini-2.5-pro / gemini-2.5-flash`。**这不是上游给的，是本地常量**。

Antigravity 协议只有 3 个端点（OAuth / loadCodeAssist / streamGenerateContent），
**没有"列模型"接口**。改成：
- 注册时放入 13 个候选名（覆盖各代 Gemini 命名）
- **后台异步探测**：逐个试，能通的留下
- **一个都不通时保持原样**（不清空），避免地区/额度问题把清单清没了

### 3. 诊断能力

`loadCodeAssist` 响应加了一行摘要日志，能一眼看出：
```
antigravity 档位：standard-tier · 可用档位=[standard-tier]
  · 不合格档位=[?:Your current account is not eligible...not available in your location.]
```

**实测结论**：该账号 `currentTier=standard-tier`、`paidTier=g1-pro-tier (Google AI Pro)`，
但所有请求持续 `429 RESOURCE_EXHAUSTED`。
响应里 `ineligibleTiers` 只针对 `free-tier`（免费档在所在地区不开放），
而 `paidTier.availableCredits[].minimumCreditAmountForUsage = "50"` —— 订阅积分要累积到门槛。

## 四、WorkBuddy 国际版

### 1. 签到：上游没开这个活动

- `checkin-activity-status` 实测：国内版 `active=true`，国际版 **`active=false`**
- `daily-checkin` 两国都返回 code 10001，但 msg 不同：
  国内"今天已签到，请明天再来"、国际"**签到活动未开启或已过期**"

**`code=10001` 有两种完全相反的含义**，原代码只按 code 判定 → 国际版被误报成"已签到"。
改用 `active` 字段区分，新增 `unsupported` / `active` / `streakDays` 输出。

### 2. 界面不再静默抹掉

`renderChores` 原来把"活动没有的账号"整条清空，用户只看到空格。
改成明确显示「**本区域暂无签到活动**」，并且**不再靠匹配上游文案**
（腾讯改文案就失效）。

### 3. 成长中心接口实测（12 接口 × 4 账号）

| 接口 | 国内 | 国际 |
| --- | --- | --- |
| credits / tasks / streak / heatmap / redeemSummary | ✅ | ✅ |
| lotteryChances / buddyQuota / energy | ✅ | ✅ |
| travelStatus / travelConfig | ✅ | ✅（dataKeys 为空）|
| daily-checkin | 活动开启 | **活动未开启** |

**结论：国际版只有"签到"未开放；任务、连登、兑换、抽奖等全部可用。**

### 4. 加积分的时机

原来只在**网关启动后 3 秒**刷新一次，刚登录的账号要等下次启动才有数字。
改成 `registerProvider` 里登录成功后顺手拉一次，和模型列表一起落盘。

## 五、界面 / 交互

- **登录页只有国内版入口**：`vendorLoginIntent` 对 WorkBuddy 是"从已有账号取
  一个 realm 就进登录页"（默认 cn，无区域选择）。分组内点「＋ 添加」改为
  跳回主界面**弹区域选择**（国内 / 国际）。
- **两个状态条**：`LoginPageBase` 已在标题栏下建了状态条，
  而 `TraeLoginActivity` / `AntigravityLoginActivity` 又各建了一个。
  基类那个文字恒为空 → "一个圆一直转、没有文字"。删掉自建的，统一走基类
  `setStatus(text, busy)`。
- **截屏限制**：`Ui.dialog()` 这个**通用对话框工厂**无条件加了 `FLAG_SECURE`
  → **整个应用所有弹窗都不能截屏**，用户没法截图反馈问题。
  改成由真正含凭据的对话框显式调 `Ui.secureDialog()`（TokenAdd / ProviderEditor）。
  `SettingsActivity` / `LoginPageBase` / `WebLoginActivity` 的 `Ui.secure()`
  按用户要求全部放开（代码里留了注释，想恢复取消注释即可）。
- **参数显示误导**：`一键做任务` 原来显示 `withTasks`（今天真有任务可做的账号数），
  任务都完成时显示"0 个账号"，用户以为没识别到账号。改成显示**实际遍历数**。

## 六、Codex

模型清单**是刻意写死的**：ChatGPT 的 Codex 后端只暴露
`/backend-api/codex/responses` 一个端点，没有列模型接口；官方 CLI 也是
内置清单 + 按套餐过滤。已在注释里写明**更新时机**（GPT 出新模型时对照 codex-tui 的模型表）。
