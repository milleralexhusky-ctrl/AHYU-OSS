# AHYU 4.29.7 — 一键检测的归类、采样与「事后无据可查」

## 一、起因

用户截了一张「一键接入并检测」的图：

```
❌ WorkBuddy 3 · 0/2 可用 · 目录 28 个
   ✗ default-model：鉴权失效（账号要重新登录）(upstream HTTP429 {"error":{"data":{"code":14018,"msg":"Cred…
   ✗ hy4-preview ：鉴权失效（账号要重新登录）(upstream HTTP429 {…})
❌ WorkBuddy 4 · 0/2 可用 · 目录 28 个
   ✗ default-model：上游拒绝这个请求（参数/能力不匹配）(upstream HTTP400 {"code":11128,"msg":"conversation id missi…})
   ✗ hy4-preview ：上游拒绝这个请求（参数/能力不匹配）(upstream HTTP400 {…})
⚠️ WorkBuddy 5 · 1/2 可用 · 目录 57 个
   ✗ minimax-video-h3：该模型上游不支持 (upstream HTTP400 {"code":11103,"msg":"Backend [maas] is not…})
```

查下来是三类问题，一类在归类、一类在采样、一类是"查不动"。

## 二、归类：业务码里的数字冒充了 HTTP 状态码

`Detect.classify()` 原来是**整段 `contains()`**：

```java
if (w.contains("401") || w.contains("403") || w.contains("unauthor") …) return "鉴权失效（账号要重新登录）";
if (w.contains("429") || …) return "被限流（稍后自动恢复）";
```

而 WorkBuddy 那条错误的原文里写着 HTTP**429** + `"code":14018` ——
**"14018" 里含子串 "401"**，鉴权分支排在 429 分支前面，于是先命中：

| 原文 | 旧结果 | 4.29.7 |
| --- | --- | --- |
| `HTTP429 … "code":14018,"msg":"too many requests…"` | 鉴权失效 ✗ | 被限流（稍后自动恢复） |
| `HTTP429 … "code":14018,"msg":"Credential has expired…"` | 鉴权失效（碰巧对） | 鉴权失效（账号要重新登录） |
| `HTTP401 …` | 鉴权失效 ✓ | 鉴权失效 ✓ |

同理 "403" / "404" / "400" / "502" / "503" / "504" 都可能命中别的数字。

**改法**：不再全文 `contains`，而是先把两样东西取出来——

- `statusOf()`：只认带边界的写法（`HTTP429`、`"status":429`），取不到返回 -1；
- `messageOf()`：只取上游自己那句 `msg` / `message`（取到的还是套了一层的原始报文就退回整段）；
- `bizCodeOf()`：业务码单独按 `code` 精确比对（11103 非聊天后端、11128 参数类）。

鉴权类措辞（`credential … expired/invalid/missing`、`token expired`、`sign in`、`重新登录`…）
只在**上游那句话**上匹配，所以 "Credits exhausted"（额度）不会被当成 "Credential expired"（凭据）。

`isTransient()` 同步改成先看真实状态码：`408/425/429/5xx` 才算暂时失败；
另外补上**本地原因**（`并发已满`、`本地限额`、`网关未运行`、`熔断/暂避`）也算暂时失败——
这些和上游、和账号都没关系，原来一样会给模型盖"坏"章。

> 注意一处**有意保留的不对称**：`HTTP429 + 上游明说凭据过期` 会显示"鉴权失效"（文案按上游的话），
> 但因为状态码是 429，`isTransient` 仍判为暂时失败（**不拉黑模型**）。状态码是上游自己的信号，优先信它；
> 归类只影响文案。真正到期要重登的账号由 `Router` 的"凭证失效暂避"负责绕开。

## 三、采样：抽到了生视频模型

`pickModels()` 直接从账号原始目录 `p.models` 里等距抽，会抽到
生图 / 生视频 / 补全类（它们在本网关里**必然失败**：`Backend [maas] is not supported`）。
WorkBuddy 5 就是这么被显示成 `1/2 可用` 的。对外列表早就过滤了这类名字
（`Server.allModels` + `Router.nonChat`），但检测没跟着过滤——"列表里不推荐、探测又拿它当代表"。

**改法**：抽样只在聊天模型里做，判据与对外列表共用 `Router.nonChat`；
兜底逻辑：万一整个目录都是非聊天模型（免费池小目录出现过），退回原列表——
宁可测出"不支持"，也不要因为没样本把账号显示成"没测"。

界面上补一句实话：`每账号只抽 N 个模型当样本（生图/生视频这类非聊天模型已跳过），不代表该账号全部模型`
（`perAccount` 由 `Detect.status()` 给出）。

## 四、查不动：探测原文没有落盘

弹窗只显示 120 字符（原来 60），`core/Log` 又只是内存里 500 行环形缓冲、进程重启即失。
修上面那个归类误判时，能拿到的只有截图里那 60 个字符——**剩下的永远找不回来了**。

**改法**：每轮检测把每个探测的**完整** why 原文追加到 `filesDir/detect.log`（一探一行，TSV）：

```
# 1792146486	一键检测启动	账号 12	每账号 2 个模型	并发 8
1792146501	WorkBuddy 3	default-model	fail	412ms	鉴权失效（账号要重新登录） | upstream HTTP429 {"error":…全文…}
1792146503	Trae 1		hy3	ok	1434ms	
1792146519	WorkBuddy 5	minimax-video-h3	fail	2136ms	该模型上游不支持 | upstream HTTP400 {"code":11103,…}
# 1792146520	一键检测结束	完成	通过 21	失败 3	新隐藏 2	用时 34.2s
```

单文件上限 512KB，超了改名成 `detect.log.old`（只留一份）。写日志吞掉所有异常——记日志绝不能影响检测本身。

## 五、自检回归（设置页「自检」）

新增两条：

- 检测失败归类（业务码里的数字不算 HTTP 状态码）：14018+限流≠鉴权、14018+凭据过期=鉴权、真 401 仍是鉴权、`Credits exhausted` 不是鉴权、11128 仍是"上游拒绝这个请求"，以及 `isTransient` 的四种情况。
- 检测采样跳过非聊天模型：`minimax-video-h3` 不能被抽中；全垃圾目录要兜底非空；空目录不报错。

两条都已在开发机上用 `Detect.java` 的原样代码单独编译跑过（8 个归类断言 + 7 个行为断言全绿），
见提交时附带的验证输出。

## 六、这次**没有**动的（避免误解成"检测修好了就没问题"）

- **WorkBuddy 4 的 `conversation id missing`（HTTP400 / code11128）大概率是真的**：
  网关给 WorkBuddy 构造 body 时从不写任何 conversation id（探测和真流量共用同一条
  `probeDirect → serve → WorkBuddy.Up.chat → openAiBody`），所以真请求同样会给这个错。
  归属：需要按上游要求补会话字段，属于**渠道适配**问题，不是检测问题。
- 每账号只抽 2 个模型这件事本身没变（`Detect.start(2, 8)`）：抽样只够判断"账号通不通"，
  不足以判断"这个账号的某个模型好不好"。界面现在如实标注了这是抽样。
- `usage.tsv` 显示 WorkBuddy 4 在 10-16 凌晨还通过（`gpt-6-sol` 37 次 ok=1，同段 54 次 ok=0），
  所以它本来就时好时坏——最终定性要靠 4.29.7 之后的新一轮检测（`detect.log` 里有全文）。

## 七、版本

`versionName 4.29.7` / `versionCode 573`（`build.sh` 的 aapt2 link 参数）。
