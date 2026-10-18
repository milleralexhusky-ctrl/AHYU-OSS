# AHYU 4.29.1 — 外部审计改进

本轮基于一次外部代码审计（反编译 + 源码通读）落地。**审计提出的 12 条里，有 4 条经源码核对
发现当前版本已经实现**，所以实际改动 7 项、确认无需改动 4 项、明确不做 1 项。

## 一、本次实际改动

### 1. 收紧明文流量（安全，推荐优先发布）

**问题**：`AndroidManifest.xml` 里 `android:usesCleartextTraffic="true"` 是全局开关，等于允许
任何域名走明文 HTTP。审计视角下这是"托管凭据的应用允许明文上行"的红色项。

**核对结论**：上游全是 HTTPS，硬编码的明文地址只有 127.0.0.1 / localhost（本机网关自身、
OAuth 回调 `localhost:1455` / `localhost:54545` / `127.0.0.1:51120`）。所以白名单可以收得很紧。

**改动**：
- 新增 `res/xml/network_security_config.xml`：`base-config` 禁止明文，只对
  `127.0.0.1` / `localhost` / `::1` 开 `domain-config` 例外。
- Manifest 用 `android:networkSecurityConfig="@xml/network_security_config"` 替换原开关。

**副作用与注意**：
- 局域网模式（`lanAccess`）不受影响 —— 那是**本机对外提供**服务，由 `Server.java` 强制
  要求 API Key 控制，走的是这套配置管不到的入站方向。
- 如果将来用户要在本机连**局域网内其它明文服务**（如自建 http 中转站），需要把那个域名/IP
  追加进 `domain-config`。这是本改动唯一的可用性代价，写在配置文件注释里了。

### 2. 访问 Key 比较改为常量时间

**问题**：`Config.acceptsKey` 用 `String.equals`，长度相同则逐字节比较并在首个不同处返回，
局域网模式下可被时间侧信道逐位猜测。

**改动**：新增 `constantTimeEquals`，长度不同也跑完整循环（异或累积、不 break），
耗时只与较长串长度相关。`acceptsKey` 全部走它（含 `apiKeyAliases`）。

**边界说明**：这不是 MAC 校验，挡的是统计时间侧信道，暴力破解仍由 Key 自身长度承担。
代码注释里写明了这个取舍，避免后来者误当成密码学校验。

### 3. 失败原因分类（凭证健康度）

**问题**：UI 上只有一句「最近调用失败：<上游原文>」，而中间层会把异常包装多层，
原文常是 `Throttled: auth: HTTP 401 ...`。用户第一反应是"账号是不是封了"，但真正的处置
动作（重登 / 等一会儿 / 查网络）完全不同。

**改动**：`Http.java` 新增 `FailureKind` 枚举与 `classifyFailure(code, throwable)`，
分 AUTH / QUOTA / RATE / NETWORK / UPSTREAM / REQUEST / UNKNOWN 七类；
`MainActivity.unusableReason` 改为先给"该做什么"、括号里再附原文。

**验证**：26 条真实错误样本（各厂商措辞 + 中文文案 + 混合文本）分类全对。
其中"混合文本优先级"是刻意设计：`401 Unauthorized (连接已重置)` 判 AUTH 而非 NETWORK ——
因为账号问题需要人动手，网络问题等等就好，宁可让人多做一步也别漏报。

### 4. 凭证到期提前提醒

**问题**：上游 access_token 有的约 28 天、有的 1 小时且没有刷新接口，到期表现是一连串调用失败，
用户往往在客户端突然报错时才发现。

**改动**：`MainActivity.credentialExpiringSoon` 从 `credentials` blob 里读到期时间
（兼容 `expires_at` / `expiresAt` / 秒与毫秒 / `accounts[]` 嵌套 / JWT payload 的 `exp`），
剩余 ≤3 天时给提示。

**为什么只提醒、不列入"暂不可用"**：还剩 3 天时账号完全可用，放进不可用列表是误报，
会让那个页面失去可信度。

### 5. 加密配置导出 / 导入（换机迁移）

**问题**：`allowBackup=false` 是对的（不让系统云备份顺走账号 token），代价是换机时
所有账号、Key、供应商都要重来 —— 对攒了十几个账号的用户这是最劝退的一步。

**改动**：新增 `core/Backup.java` + 设置页「数据」卡片两个入口。

格式：外层可读 JSON（便于确认"这是 AHYU 备份"），内层 AES-256-GCM，
密钥由 PBKDF2-HMAC-SHA256（20 万次迭代，随机 salt）从**用户自己的密码**派生。

**几个刻意选择**：
- 用用户密码而不是 Keystore：Keystore 密钥只在本机、本签名下有效，卸载即失效 ——
  这正是 `Secret` 不能用于迁移的原因，搬到新机必然解不开。
- 迭代数写进文件：以后提高强度仍能解开旧备份（只增不减）。
- "密码错"与"文件损坏"归为同一句话：否则这个接口就成了密码验证器。
- 导出走临时文件 + rename + `fsync`：中途被杀不会留下"看起来存在、其实截断"的备份 ——
  后者比没有备份更危险，用户会以为已经备份好了。
- 导入前把当前配置另存 `gateway.json.before-import.bak`：用户"导错文件"的概率远高于
  "备份损坏"，可回滚比严格校验更救命。
- 导入后 stop → 落盘（`Secret` 用本机密钥重新加密）→ 重新 load → start：
  不能把备份里的 JSONObject 直接当内存 Config 用，那会绕过所有字段级容错。

**验证**：真实加解密往返测试通过 ——
往返一致、中文/JSON 保真、错误密码拒绝（AEADBadTagException）、密文篡改拒绝、
同一配置两次导出密文不同（随机 salt/iv）；手机上加密约 205ms、解密约 85ms。

### 6. 包名说明

审计提出的 `com.ahyu` 外壳 + `com.cpa.gateway` 内部包的"不一致"**不是遗留问题**：
`build.sh` 里 `--rename-manifest-package com.ahyu` + `--custom-package com.cpa.gateway`
是有意的 —— 对外包名与代码命名空间解耦。**本次未改**，改了会导致
`pm install -r` 因签名/包名不一致或数据目录迁移而失败，收益为零。

### 7. 成本统计（未做，说明原因）

`Prices.java` 已有 1684 条 LiteLLM 价格表和三级匹配，`Usage.java` 已有 TSV 用量记录。
缺的只是"把两者相乘并在图表上显示"。这属于纯 UI 增量，需要设计稿和交互决定，
不适合在没有界面确认的情况下直接写进 `UsageChart`，故留作独立任务。

## 二、审计提出但经核对已实现（无需改动）

| 审计条目 | 实际情况 |
|---|---|
| 凭证存储加密 | `Secret.java` 已用 Android Keystore AES-256-GCM，含加密失败显性告警 + 启动自愈迁移 |
| 本地网关只绑 loopback | 已实现：默认 `127.0.0.1`，`lanAccess` 才绑 `0.0.0.0`，且强制要求 API Key |
| 调试接口泄露风险 | 已解决：`Debug.ON` 编译期常量，`build.sh` 无条件强制发布包为 `false` |
| 保活耗电 | 已实现空闲 2 分钟释放 WakeLock、有请求自动拿回、看门狗每轮续期 |

这四项做得比审计预设的更细（例如 `Secret` 的"降级可用但显性 + 自愈"策略、
导入导出的原子写、看门狗区分"监听挂了"与"进程在但被限流"），所以本轮只做增量。

## 三、明确不做

- **开源**：这是产品与商业决策，不是技术改进，审计无权代答。技术上的替代方案是
  出一份安全白皮书说明凭据流向，可在需要时单独做。
- **包名统一**：理由见上，改了是负收益。

## 四、构建与验证

```
ANDROID_SDK=/opt/android-sdk ./build.sh
→ BUILD_OK，产出 cpa-gateway.apk（1.37 MB，签名 SHA-1 3a1f280e…与现有版本一致）
```

- `aapt2` 打包后确认：Manifest 已无 `usesCleartextTraffic`，
  `res/xml/network_security_config.xml` 已进包。
- 备份加解密：宿主机 8 项断言全通过。
- 失败分类：26 条样本全通过。
- 发布构建确认 `/v1/debug/*` 已关闭。

**未做真机运行验证**：以上是编译期 + 逻辑层验证。备份导入会在磁盘上
写/替换配置并重启网关，建议在真机上先用一份无关紧要的备份试一次再正式使用。

---

## 五、补充：成本统计（修正上一版判断）

本文件第二节曾把"成本统计"列为"未做"。**这个判断是错的**，属于只读了
`Prices.java` 头部与 `Usage.java` 前 50 行就下结论。实际：

- `Usage.summary()` 已在按模型维度累加成本，写入 `Summary.costUsd`；
- `Usage.line()` 已在输出末尾追加「成本 ≈$…」；
- `LogActivity.usageSection(..., withCost=true)` 已给「按模型」那一组标 `≈$`。

本节补的是三个真实缺口：

### 1. `Usage.costOf(byModel)` — 把"成本只能按模型算"这条约束显性化

原来这段累加逻辑内联在 `summary()` 里。抽成方法并加注释，是因为这条约束很容易踩：
价格是**按模型**定的，而 `byApi` / `byProvider` / `byDay` / `byKey` 分组里的 token
已经混了多个模型，事后拿分组名查价格表必然查不到（"按渠道 +8 次"该用什么单价？）。
所以界面上只有「按模型」能给成本，其余分组给不了 —— 现在界面也补了这句说明。

### 2. `Usage.dailyCost(days)` — 逐日成本

`byDay` 同样混了模型，所以逐日成本只能重新扫一遍原始行，按「天 × 模型」二维聚合。
这是本次新增的解析代码，验证覆盖：

- 跨模型求和正确（deepseek-chat + claude-3-5-sonnet 同日累加）
- 价格表查不到的模型被**排除**，而不是当成 $0 —— 后者会让"这天没花钱"和
  "这天花了但算不出来"变得无法区分
- 列数不足的坏行、token 全 0 的行被跳过

### 3. 用量弹窗新增「成本」区块

近 7 天合计放标题下方（这是用户最想看的数字，不埋在最后一行），
逐日明细按**金额从大到小**排（看"哪天最费"比看时间顺序有用）。
查不到价格的天不出现在列表里，另附一行说明有几天未计入。

### 边界

`dailyCost` 与 `summary()` 是两次独立扫文件。不用缓存是因为它只在用量弹窗打开时调一次，
一次扫文件比维护二维缓存的失效逻辑便宜。若将来用量页变成实时刷新，这里需要合并成一次扫描。

---

## 六、补充：`POST /v1/messages/count_tokens`

### 为什么加

Anthropic 官方客户端（含 Claude Code 这类 CLI）在长对话、自动压缩上下文、
以及"发之前先数一下装不装得下"时都会调这个接口。之前没有这个路由，
走到 `Server.java` 末尾的 `writeError(out, path, 404, "unknown route")`。
客户端的常见反应是要么放弃这一轮，要么退化成按最大上下文硬截断 ——
表现就是"明明装得下，却被截了"。

### 实现

`Server.handleCountTokens()`，注册在 `/v1/messages` 路由旁。复用
`Classifier.estimateTokens()` 的口径，与 `Router` 的上下文过滤、`Limits` 的 TPM/TPD
预检用的是同一套估算，不会出现"这里说够、那里说不够"。

口径与官方对齐的部分：响应 `{"input_tokens": N}`、只算输入不算输出、
未知模型照样返回估算值（官方也是，因为调用方要的是"够不够装下"这个判断）。

### 必须说清楚的偏差

手机端跑不起真正的分词器，用的是 ASCII 4 字符≈1 token、非 ASCII 1 字符≈1 token、
图片每张 1200 的粗估。**与官方分词结果会有几个百分点差异**，所以：

- 拿它判断"能不能装进上下文"是安全的（本来就保守估大）；
- 拿它做计费对账**不可靠**。

响应里额外带 `"estimated": true` 明示这一点。官方没有这个字段，客户端会忽略，
但人看日志时能立刻分辨这是估算值。

### 解析失败时的行为

`Fronts.AnthropicFront.parse` 抛异常时不返回 400，而是退回按原始 body 长度粗估。
理由：这个端点的语义是"给个估算"，客户端拿一个偏保守的数字比拿 400 有用。
（这一点与 `/v1/messages` 主路径不同 —— 那里 JSON 坏了必须报错，因为没法转发。）

### 验证

- 编译通过。
- token 估算边界：纯英文、纯中文、带图（含 1200 图片基数）、system+多轮、
  空请求（只剩 16 的 JSON 结构开销）、坏 JSON 兜底、100 万字符不溢出 —— 全部符合预期。
- 响应形状 `{"input_tokens":N,"estimated":true}` 正确。
**真机端到端验证已通过（4.29.1）**：临时注入了一个 `test-alias-count-tokens-7f3a9b`
别名后实测，输入"你好，请帮我解释这段代码的作用"返回 `{"input_tokens":39,"estimated":true}`；
别名撤销后同请求被正确拒绝（401）。临时注入已清理、原始配置已恢复。

下面这条命令仍可在你需要时自查：

  ```sh
  curl -s http://127.0.0.1:8321/v1/messages/count_tokens \
    -H 'content-type: application/json' \
    -d '{"model":"claude-3-5-sonnet","messages":[{"role":"user","content":"你好"}]}'
  # 期望：{"input_tokens":<数字>,"estimated":true}
  ```


---

## 七、全量源码通读（4.29.1 第二轮）

按"不再依赖 README 与类名、逐文件读代码"的方式把 88 个 Java 文件（约 31,000 行）
过了一遍。结论分三类。

### A. 发现并已修：我自己上一轮引入的回归

**问题**：4.29.1 把 `usesCleartextTraffic` 换成 network security config 之后，
`WebLoginActivity` 的登录 WebView 也受影响。`normalize()` 会**保留**用户粘贴的
`http://`（只有裸域名才补 `https://`），而新配置禁止非回环的明文，
于是手输一个 http 中转站会以 `net::ERR_CLEARTEXT_NOT_PERMITTED` 失败 ——
一句用户完全读不懂的错误，只会以为"这个站坏了"。

这是收紧明文的直接代价，且是我上轮没考虑到的路径（当时只核对了上游请求，
没核对登录用的 WebView）。

**修法**：新增 `WebLoginActivity.warnIfCleartext()`，在 `loadSite()`（手输）
与 `openStation()`（点常用站点）两处入口，对非回环的 `http://` 给出人话提示：
"这是明文 http 站点，已被安全设置拦截（只放行 https 和本机地址），请改用 https://"。

**刻意只提示、不拦截，仍然继续加载**：
- WebView 是否遵守 networkSecurityConfig 在不同 ROM 上有差异（遵守是 Android 7+
  的官方行为，但没必要把可用性赌在这一点上）；
- 万一以后配置改回去，这里也不会变成一道假的硬墙。
能加载就正常用，加载不了也先看到了原因。

### B. 值得你知道、但我没有改的

**1. `Persona` 在改写上游模型的输出内容**

`core/Persona.java` 会把回答开头的"我是 DeepSeek / 作为一个人工智能"这类自我介绍
删掉，理由是虚拟模型背后会换不同厂商，"开口就被用户看穿"。

它作用于 OpenAI 与 Anthropic 两条流的正文（`Fronts.Collector` / `Fronts.Streamer`），
**不碰 `thinking`、不碰 `tool_call`、不动用量统计** —— 边界划得是干净的。
但它意味着"客户端收到的正文 ≠ 上游原始输出"。

（这是产品的有意设计，不是缺陷。但值得在文档或设置页里写一句，
免得将来有人对着一份"和上游不一致"的日志排查半天。）

**2. `Persona` 在流式下不一定生效（可验证的行为细节）**

`Stripper.clean()` 在**第一个非空 delta** 上就把 `done` 置真：

```java
String clean(String delta) {
    if (done || delta == null || delta.isEmpty()) return delta;
    done = true;
    String out = strip(delta);
```

于是只有在"整句自我介绍恰好落在第一个分片里"时才删得掉。若上游把开头切成
`"我"` + `"是 DeepSeek，由…"` 两个分片，第一片不匹配任何 `INTROS` 正则、
`done` 已置真，后面的自我介绍就原样发出去了。

也就是说：这个特性**依上游分片粒度而时灵时不灵**，而且不生效时是静默的。
作者注释里写了"只处理第一次输出，这样不增加首字延迟"是有意的取舍 ——
如果希望它稳定，需要把前若干片缓存起来做前缀匹配（代价是首字延迟），
或者在首次不匹配但疑似截断时再等一片。**这属于需要你权衡的设计选择，我没有动。**

**3. 登录 WebView 的 `MIXED_CONTENT_ALWAYS_ALLOW`**

`LoginWebView.create()` 里为了 OAuth 回调能落到 `http://localhost:<port>`，
开了 `MIXED_CONTENT_ALWAYS_ALLOW`。代价是：登录页加载的**任意**明文子资源
都可能被中间人改写，而该 WebView 同时开着 JS 并会抓取 Cookie。

注释里已说明为什么要这么开，取舍是清楚的。若想收窄，可考虑
`MIXED_CONTENT_COMPATIBILITY_MODE`（仍允许部分混合内容）。**我没有改** ——
这直接关系到 OAuth 回调能不能走通，改了需要你在真机上把各家登录流程都走一遍，
风险不该由我替你承担。

### C. 核对通过、做得好的地方（抽样）

- **日志只记长度不记值**：`KimiLoginActivity`、`DeepSeekLoginActivity`、
  `DoubaoLoginActivity` 里抓 token/cookie 的地方一律记"长度 N"，
  没有一处把凭据本身写进日志。这条在审计里很容易踩坑，这里守住了。
- **凭据不落 `/panel`**：`panelHtml()` 里不含 apiKey/cookie/token 字段，
  且 `/panel`、`/health` 都在鉴权之后（未带 Key 一律 401，与实测一致）。
- **`Limits` 的三条语义**（0 ＝ 不限制、先预检后记账、预检不通过按限流处理
  而非失败）与上游一致，且注释把"为什么"写清了 —— 这三条是最容易写错的地方。
- **`Detect` 的并发计数用了 `AtomicInteger`**，注释直接写明"volatile int 的 ++
  是真机上进度卡住的原因"。这类并发细节通常要出过故障才补，这里补上了。
- **`Repeats` / `RaceCancels` / `RelayWatch`** 都是针对**真实发生过的故障**
  写的（卡死重试刷额度、竞速输家占满线程池、离线站拖慢探活），
  不是预留的防御性代码。这类"从故障里长出来的"模块最有价值。
- 全量扫描：没有用 `==` 比较字符串的地方；核心路径（Router）零空 catch；
  空 catch 集中在"尝试-失败即放弃"的恢复路径，抽样看都有合理理由。

---

## 八、不让模型"被用傻"：能力参数透传（本轮重点）

### 起因

同一个账号、同一个模型，直连上游正常，走网关却明显变笨 —— 而且**不报错**。
这类"静默降智"比报错难查得多。逐段核对请求链路后，找到五处会让模型失去能力的丢失点。

### 修复的五处

**1. `tool_choice` 从未被解析（三条前端都是）**

`Fronts` 里搜不到 `tool_choice`。客户端发 `"required"` / 指定函数时，网关当没看见，
上游按默认 `auto` 处理 —— agent 类客户端于是拿不到工具调用，表现为"模型突然不会用工具了"。

**2. 推理参数从未被解析**

`reasoning_effort`（OpenAI o 系列/GPT-5）、`thinking`（Anthropic extended thinking）、
`thinkingConfig`（Gemini 2.5）全都没读。**这是最字面意义上的降智**：
客户端明明要求模型思考，网关却让它按默认档位回答。

**3. `response_format` 从未被解析**

JSON 模式 / 结构化输出丢掉后，模型返回散文，客户端解析必炸。

**4. Anthropic 与 Gemini 上游会**丢掉 URL 图片**

`Upstreams` 里两处 `if (mimeType.startsWith("url:")) continue;` —— 用 URL 传的图整张丢弃，
模型只能凭文字瞎答。
- Anthropic 侧：改为发原生 `source:{type:"url"}`；
- Gemini 侧：Gemini 没有 URL 图源，改为**下载后内联**（新增 `fetchImageAsBase64`，
  带协议白名单、8MB 上限、15 秒超时、`image/*` 类型校验；失败记日志而不是静默）。

**5. Anthropic 形状的图片块在解析阶段就被丢掉**

`Proto.parseOpenAiContent` 只认 `image_url.url` 和顶层 `url`，
而 Anthropic 客户端的图片是 `{"type":"image","source":{"type":"url"|"base64",...}}` ——
两个分支都取不到值，取到空串后 `addDataUrl` 直接返回。**这条是最隐蔽的**：
客户端按自己的协议发的图，网关自己把它丢了。

### 设计取舍

- **同协议原样透传，不"理解"**：OpenAI 客户端配 OpenAI 兼容上游时，参数原封不动送出去，
  经过的理解越少越不容易出错。
- **跨协议只翻有确定映射的**。`tool_choice` 三种语义两边能一一对应，就翻
  （`required`↔`{type:any}`、指定函数↔`{type:tool,name}`）；`"none"` 在 Anthropic
  无等价物，**不翻也不假装成 auto**（详见 `XParams` 注释）。
- **没有可靠映射的坚决不猜**。`budget_tokens` 与 `reasoning_effort` 之间没有可信换算，
  跨协议时**不传**，并记一条日志（`Upstreams.note`）说明是哪个字段、来自哪个协议。
  宁可回到"没有这个能力"，也不要给上游一个错误档位把模型带偏。

### 真机端到端验证（不是只编译通过）

临时注入两个指向假上游的测试通道（`PROBE-OPENAI` → 本机 29101、`PROBE-ANTHROPIC` → 29102，
由 Linux 环境的守护进程充当），让网关把真实请求发给它们，**抓取实际发出的请求体**：

| 测试 | 期望 | 实测结果 |
|---|---|---|
| OpenAI 协议 → OpenAI 上游 | 四项原样透传 | `tool_choice="required"`、`response_format={"type":"json_object"}`、`reasoning_effort="high"`、`parallel_tool_calls=false` **全部在场** ✅ |
| Anthropic 协议 → OpenAI 上游（跨协议） | `{type:any}` 翻成 `required` | `tool_choice="required"` ✅ |
| Anthropic 协议 → Anthropic 上游 | 原样透传 | `tool_choice={"type":"tool","name":"get_weather"}`、`thinking={"type":"enabled","budget_tokens":4000}` ✅ |
| Anthropic URL 图 → Anthropic 上游 | 保留 URL 图源 | `{"type":"image","source":{"type":"url","url":"https://example.com/a.png"}}` ✅ |
| Anthropic base64 图 → OpenAI 上游 | 转成 data URL | `{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}` ✅ |

**修复前的对照**：同一组请求里，`tool_choice` / `response_format` / `reasoning_effort` 全部缺席，
URL 图被整张丢弃 —— 即"降智"是实测存在、不是推测。

`XParams` 跨协议翻译另有 15 项断言全通过（含 `"none"` 无等价时不翻、
Gemini 来源不硬翻、thinking 不做无依据的档位换算）。

### 测试环境已完全恢复

原始配置（11 个通道、无测试别名）已还原，临时注入的别名与测试通道已清除并经 401 复核失效，
抓包产物与临时文件已删除。验证过程中的一次教训：**首次抓包为空是因为设备上装的还是
参数改动之前的包** —— 下载目录里的 APK 停留在 14:31 的构建，而改动是 14:35 编译的。
这也说明"装了什么"和"改了什么"必须逐次核对版本，不能凭印象。

---

## 九、真实故障："好多模型都用不了"（Kimi 为例）

### 现象

用户反馈大量模型不可用。日志显示失败集中在 `kimi-k3-1`，上游返回：

```
HTTP 429 {"code":6004,"msg":"您的使用量已超出频率限制，将在 2026-10-16 01:24:11 UTC+8 重置，
您也可以切换其他模型继续使用。"}
```

但关键不在 429（那是上游对某个账号的限流，网关无权绕过），而在于**换号没有发生**：

```
上游限流：暂避 300 秒（不计失败、不进熔断）→ WorkBuddy 1/kimi-k3-1
upstream error（1 个候选都失败）...
```

`kimi-k3-1` 明明有**两个**账号提供（WorkBuddy 1、WorkBuddy 2），却只有 1 个候选。

### 定位

`pickAll()` 对带前缀的请求直接返回单候选：

```java// 带前缀的显式路由：只有一个候选，保持原语义
int slash = model.indexOf('/');
if (slash > 0) { ... return singletonList(byShort); }
```

而 `allModels()`（即 `/v1/models` 对外给出的模型列表）**只提供带前缀的名字**：

```javalist.add(shortLabel(p) + "/" + m);      // 形如 WB1/kimi-k3-1
```

两者叠加的后果：**客户端手里根本没有裸名可选**，用户从列表里挑一个模型，
拿到的恰恰是"永远只用那一个账号"的那种名字 —— 于是自动换号在所有真实用法下都是失效的，
一个账号被限流就等于这个模型"用不了"。

### 实测证据（同一模型、同一时刻）

| 请求的模型名 | 结果 |
|---|---|
| `kimi-k3-1`（裸名） | 日志 `非流式可换号，重试下一个候选` → **换号成功，拿到回复** |
| `WB1/kimi-k3-1`（前缀名） | 单候选 → 直接 429 报错，**不可用** |

### 修复（两处，缺一不可）

**1. 前缀语义从"锁定"改为"优先"**（`pickAll` → `pinnedThenFallbacks`）

点名的账号排第一（正常时行为与以前完全一致），失败后按同一套打分用其它账号兜底。
既保留"我想先用这个号"的表达能力，又不会因为一个号被限流就报错。

**2. 兜底时必须剥掉指向别的账号的前缀**（`upstreamModel`）

第一处修完仍然失败：日志已打出"可换号"，但第二个账号收到的是 `WB1/kimi-k3-1`，
上游回 400 `model [WB1/kimi-k3-1] service info not found`。
原 `upstreamModel` 只在本 provider 与前缀匹配时才剥前缀，兜底到别的账号时不剥。

判断依据改为"这个前缀能解析成某个已知账号"（`byShortLabel`），
而不是见到斜杠就切 —— 正常模型名里带斜杠不该被误伤。

### 验证

修复后同一请求（`WB1/kimi-k3-1`）：

```
点名 WorkBuddy 1 的 kimi-k3-1：另有 1 个账号也有这个模型，已作为失败时的兜底候选
上游限流：暂避 300 秒 → WorkBuddy 1/kimi-k3-1
非流式可换号，重试下一个候选：WB1/kimi-k3-1
→ 返回 200，内容来自 WorkBuddy 2
```

### 仍然存在的边界（无法用换号解决）

全库 147 个模型里有 **24 个只被单一账号提供**。这类模型一旦该账号被限流/失效，
就没有兜底可换 —— 这是账号覆盖的问题，不是网关的问题。
另外上游的限流是按账号计的（`kimi-k3-1` 的 WorkBuddy 1 要到次日 01:24 才重置），
网关能做的是**不让一个号的限流拖垮整个模型**。

### 测试痕迹已清理

注入的别名与测试通道已移除、配置回到 11 个账号、我在 `usage.tsv` 里产生的 8 行测试记录
已删除（原文件备份在 `/data/local/tmp/usage.bak`），旧别名复核为 401。

---

## 十、全账号 / 全模型逐个体检

### 一、11 个账号实测（每个账号发 1 token 的真实请求）

| 账号 | 类型 | 探活模型 | 结果 |
|---|---|---|---|
| WB1 | workbuddy | kimi-k3-1 | 账号被限流 → **自动换到 WB2 成功** |
| WB2 | workbuddy | kimi-k3-1 | ✅ 直接成功 |
| WB3 | workbuddy | kimi-k3 | ✅ 直接成功 |
| WB4 | workbuddy | kimi-k3 | ✅ 直接成功 |
| Trae1 | trae | kimi-k3 | ✅ 直接成功 |
| Trae2 | trae | kimi-k3 | ✅ 直接成功 |
| Loomy1 | loomy | Kimi-k2.6 | ✅ 直接成功 |
| Loomy2 | loomy | Kimi-k2.6 | ✅ 直接成功 |
| DS | deepseek | deepseek-chat | ✅ 直接成功 |
| 豆包 | doubao | doubao | ✅ 直接成功 |
| Kilo | kilo | kilo-auto/free | ✅ 直接成功 |

**结论：11 个账号全部健康**，没有任何一个是"凭证坏了"。

顺带纠正一处我自己先前的误判：Kilo 的 `credentials` 字段是空的，我一度据此判断
"16 个模型全部不可用"——**这是错的**。Kilo 是**零凭据免费池**（设计如此，
仓库里就有 `AHYU-4.20.0-Kilo零凭证免费池.md`），空凭据正是它的正常状态。
体检这类判断必须以实测为准，不能只看字段。

### 二、又找到并修掉一个真问题：27 条非聊天模型挂在对外列表里

`/v1/models` 原本挂着 **315 条**，其中 27 条是生图 / 生视频 / 补全 / 重写类模型。
客户端把这些列给用户选，选中必然失败 —— 实测：

| 模型 | 上游返回 |
|---|---|
| `WB1/kling-video-v3` | 400 `Backend [maas] is not supported` |
| `WB3/gpt-image-2.5-sunburst` | 400 `Backend [maas] is not supported` |
| `WB1/codewise-completions` | 400 `model [completion-077] service info not found` |
| `Loomy2/qwen-image-3.0-pro` | 404 `该模型暂未开放，请切换模型` |
| `Loomy1/Hy-Image-3.5-preview` | 404 `该模型暂未开放，请切换模型` |

**修复**：`allModels()` 过滤掉这些条目，判据直接复用选路用的 `Router.junk()`
（新暴露为 `Router.nonChat()`），避免"选路当垃圾、列表又推荐"的自相矛盾。
对外条目 **315 → 288**。

被隐藏的 27 条已逐条核对，**全部**是生图/生视频/补全/重写类，没有误伤任何聊天模型：

```
Hy-Image-3.5-preview ×2      codewise-completions ×2     completion-gf ×2
doubao-seedream-5-lite ×2    codewise-jump ×2            deepseek-v3-0324-taco-completion ×2
qwen-image-3.0-pro ×2        codewise-nes-a4-027-aide ×2 hunyuan-image-alpha(-edit) ×4
kling-video-v3 ×2            codewise-rewrite ×2         minimax-video-h3 ×2
gpt-image-2.5-sunburst ×2
```

注意这是**只影响对外列表**：手动点名（`WB1/kling-video-v3`）仍然可用，
真想试的人不会被挡住。

### 三、仍然存在的结构性风险（我改不了的）

**1. 24 个模型只被一个账号提供** —— 该账号被限流/失效时没有兜底可换。
其中 15 个来自 Kilo 免费池，其余 9 个值得注意：

```
deepseek-chat / deepseek-reasoner   ← 唯一来自 DeepSeek 账号
doubao / doubao-think               ← 唯一来自豆包账号
hy4-preview-ctrl / -exp / -x        ← 唯一来自 WB1（且 WB1 正在被限流）
loomy-default                       ← 唯一来自 Loomy3
```
这 9 个是"真正单点"：想消除风险，需要在别的账号上找到同名模型，或者接受它。

**2. 图片能力是"按账号"判断，不是"按模型"**

`Router` 只看 `p.vision`（账号级开关），11 个账号**全部** `vision=true`。
于是带图请求会发往任何账号的**任何模型** —— 如果那个模型本身看不了图，
就会由上游报错。
`ModelCaps` 其实**知道每个模型是否支持图片**（`ModelCaps.VISION` 位），
但选路没有用它。**这一点我没有改**：改成按模型过滤会改变"用户点名某个模型"的语义
（点名一个不支持图片的模型，到底该拦下来还是照发让上游报错？），属于产品决策。
建议：至少在 UI 上把"该模型不支持图片"标出来。

**3. 所有账号的 `contextLimit` 都是 0（不限）**

本地不会预检上下文长度，超大请求会直接发给上游、由上游拒绝。
`ModelCaps` 里有每个模型的上下文长度，但没接进预检。同样是"接上就行"的增量，
需要先定策略（超限是截断、报错、还是换模型）。

### 四、测试痕迹已清理

注入的 `probe3-alias` 别名已移除（复核 401）、配置回到 11 个账号、
我在 `usage.tsv` 里产生的 17 行测试记录已删除（备份 `/data/local/tmp/usage2.bak`）、
临时文件与抓取产物已删除。

---

## 十一、账号标签固化 / 限流可见 / 退避对齐上游

### 1. 账号短标签不再漂移（已修，已实测）

**问题**：客户端模型名的前缀来自 `ShortName.of()`，而它按"**当前启用**的同类型账号排第几"现算。
于是停用一个账号会把后面的全部重新编号：

```
现在（4 个都启用）      停用 WorkBuddy 1 之后
WB1/kimi-k3-1  ←WB1     WB1/kimi-k3-1  ← 变成 WorkBuddy 2！
WB2/kimi-k3-1  ←WB2     WB2/kimi-k3-1  ← 变成 WorkBuddy 3！
WB3/kimi-k3-1  ←WB3     WB3/kimi-k3-1  ← 变成 WorkBuddy 4！
WB4/kimi-k3-1  ←WB4     （消失）
```

用户只是想"先停一个号省额度"，结果客户端里存的 `WB2/xxx` **静默指向了另一个账号**、
`WB4/xxx` 直接失效。没有任何提示，排查时只会以为"模型坏了"。

**修复**：`Config.Provider` 增加落盘字段 `label`，首次加载时用 `ShortName.ensureLabels()`
按**当时的启用顺序**分配（与旧行为逐字一致，客户端无需改动），此后只增不改；
账号停用/启用/新增都不再让已有标签变化。取不到稳定标签时才回退旧算法。

首次分配刻意按"**启用中的账号**"编号，而不是"全部账号"——否则配置里只要有一个停用账号
排在前面，启用中的账号就会整体错位一号，等于把用户的客户端配置全改坏。

**实测**：装上后 11 个账号标签落盘为
`WB1 WB2 WB3 WB4 / Trae1 Trae2 / Loomy1 Loomy2 / 豆包 / DS / Kilo`，
与原名字逐字一致；随后**停用 WB1 并重启**，客户端看到的仍是 `WB2/ WB3/ WB4/`（未错位），
而修复前这里会变成 `WB1/ WB2/ WB3/`。

### 2. 限流状态终于对用户可见（已修）

**问题**：被限流的账号 `lastError` 是**空的** —— 因为限流被刻意排除在失败记录之外
（否则一次 429 就把健康账号挂 30 分钟"不可用"）。设计是对的，但代价是：
用户看到"模型用不了"，点进账号详情一切正常，只能反复试。

**修复**：新增两个**纯展示**字段 `throttleUntil` / `throttleNote`（不参与"是否可用"判定），
在限流发生时写入并在成功一次后清除。界面上：

- 账号总览的分组卡片：`⏳ N 个限流中（可换号）`（琥珀色）
- 账号详情页：`⏳ 被上游限流，上游称 10-16 01:24 恢复（换账号/换模型可继续用）`

用词刻意是"限流中"而不是"不可用"：账号是好的，只是暂时别去撞。

### 3. 退避对齐上游给出的恢复时刻（已修，已实测）

**问题**：限流一律按默认退避（429 → 5 分钟），而上游经常直接写明恢复时刻。
实测那条 429 的正文里写着"将在 2026-10-16 01:24:11 UTC+8 重置"（约 106 分钟后），
按 5 分钟退避意味着这段时间里反复白撞。

**修复**，三处缺一不可：

1. `Http.parseResetAtMs()` 解析两种写法（绝对时刻含 UTC±N 换算、英文相对时长）。
2. **解析放在 `Throttled` 构造函数里**，而不是某一个出口 ——
   因为构造 `Throttled` 的地方有 7 处（WorkBuddy 的业务码、Codex、DeepSeek、
   Upstreams×2、Server×2），只改 `Http.upstreamError` 会漏。
   `WorkBuddy.java:576` 就是自己 new 的，第一版修复正是因此没生效。
3. `Router.recordThrottled` 原先把退避**统一截断在 1 小时**，把上游说的 106 分钟
   削成 60 分钟。现在区分来源：来自上游时刻表的最多认 6 小时，其余仍按 1 小时截断。

**实测**（同一账号、同一上游）：

```
修复前：上游限流：暂避 300 秒   → 3600 秒（被 1 小时上限削掉）
修复后：上游限流：暂避 6246 秒  → 与上游说的 01:24 一致
落盘：  "throttleUntil": 1792085051  = 2026-10-16 01:24:11 +08:00  ← 分毫不差
```

解析逻辑另有 10 项断言通过：UTC+8 / UTC-5 / 无时区 / 英文 in-after 秒分时 / 无信息时返回 0。

### 尚未做的

第四项"内置自检装置"（假上游 + 逐字段比对，把这次的验证手段变成回归测试）**还没做** ——
它是这轮里工作量最大的一项，我先把前面三项做完验证再动它。

### 测试痕迹

`thr-alias` 已移除（复核 401）、配置回到 11 个账号 11 个标签、
`usage.tsv` 里本次产生的测试行已删除、临时文件已清理。

---

## 十二、内置自检装置（第四项，已完成）

### 为什么做这个

排查"同一账号直连上游正常、走网关就变笨"时，靠的是一次性的手工验证：临时搭假上游、
抓包、逐字段比对。查出来的 5 处静默丢字段 + 若干一致性缺陷有一个共同点：**都不报错**。
丢了字段请求照样 200，只是模型表现变差，所以真机测试也照不出来。

手工做过一次的东西就该固化成回归测试，否则下次改协议、加上游还会再踩。
所以：`core/Selftest.java`，**纯进程内、零网络、零额度**。

### 覆盖的 10 项

| 检查 | 防的是什么 |
|---|---|
| 能力参数透传（OpenAI→OpenAI） | tool_choice / response_format / reasoning_effort / parallel_tool_calls 被丢 |
| 能力参数透传（Anthropic→Anthropic） | tool_choice / thinking（含 budget_tokens）被丢 |
| 跨协议翻译 tool_choice | `{type:any}` 没翻成 `required` |
| 图片形状（OpenAI + Anthropic 两种入口） | Anthropic 的 `source.url` 形状在解析阶段被整段丢掉 |
| 思维链块保真 | thinking 被降级成 text、signature 丢失（多轮带工具会 400） |
| Gemini generationConfig | thinkingConfig / responseMimeType 被忽略 |
| 限流恢复时刻解析 | 上游明说的重置时刻没被采用（含 UTC+8 换算校验） |
| 非聊天模型判定 | 生图/生视频/补全类混进对外列表 |
| 账号标签稳定性 | 停用一个账号导致其它账号改名（客户端配置静默指向别人） |
| 账号前缀识别 | 换号兜底时没剥掉 `WB1/` 前缀（上游 400 model not found） |

### 从哪看

- **启动日志一行**：`自检：全部 10 项通过（…）`；有失败则 ERROR 级列出**每一条失败项和原因**
  （不是只说"失败"）。放后台线程，毫秒级。
- **设置页 → 数据 → 「自检：请求有没有被削薄」**：列出逐项结果，失败项标红并给出期望/实际。

### 验证：不仅"能通过"，还证明"能抓到"

**正向**：恢复版实测 `自检：全部 10 项通过`。

**反向（关键）**：故意把 `tool_choice` 的透传改成不发（`if (false && …)`），重新构建安装，
自检立刻报出 **2 项失败**，且定位准确：

```
自检：有 2/10 项失败 ——
  能力参数透传（OpenAI→OpenAI） → 字段 tool_choice 丢失（渲染后的 body 里没有它）；
  跨协议翻译 tool_choice（Anthropic→OpenAI） → 字段 tool_choice 丢失（渲染后的 body 里没有它）
```

同协议与跨协议两条路径都被抓到，报错直接指出是哪个字段。随后已恢复正确实现并复核 10/10。
**没有这一步，"测试通过"只能说明测试没报错，不能说明测试有效。**

### 过程中自检抓到的一次假失败（记录一下）

第一版断言用 `contains("example.com/a.png")` 判断图片 URL 是否保留，结果报失败。
查下来是**断言写错了**：Android 的 `JSONObject.toString()` 会把 `/` 转义成 `\/`
（配置文件里 `https:\/\/anyrouter.top` 就是它干的），字面量永远匹配不上。
已改为统一经过 `flat()`（还原转义）再比对，并顺手补了反向检查
（OpenAI 形状的 data URL 渲染给 OpenAI 上游时仍须是 image_url）。

顺带说明为什么值得记下来：**假失败比没测试更糟** —— 它会让人开始忽略自检输出。

### 边界（说清楚，别高估它）

自检只覆盖**纯函数**部分（解析 + 渲染 + 判定）。需要真实网络的行为
（URL 图下载、上游探活、限流实际退避）不在范围内 ——
那些仍然要用第八、九节记录的"假上游 + 抓包"方法验证，步骤已写在文档里。

---

## 十三、推理模型专项：kimi-k3 / kimi-k3-1 用不了

用户反馈：只有 **kimi-k3 与 kimi-k3-1** 这两个模型用起来报"重连失败 / 运行失败"，
其它模型正常。这两个模型的共同点是**会先输出思考内容**（reasoning），
所以问题一定出在"思考内容"这条只有它们会走的路径上。查到两处，都属于响应侧。

### 1. Anthropic 协议下思考块缺必填的 `signature`（已修）

`Fronts` 里渲染 Anthropic 响应的两处：

```java// 非流式（原代码）
content.put(new JSONObject().put("type","thinking").put("thinking", c.thinking.toString()));
// 流式（原代码）
openBlock("thinking", new JSONObject().put("thinking", ""));
delta("thinking_delta", …);
```

Anthropic 协议里 `thinking` 块的 `signature` 是**必填字段**。用官方 SDK 的客户端
（Claude Code 这类）解析缺字段的块会直接报错 —— 而**普通模型根本不会产生思考块**，
走不到这两行，所以症状是"就这两个模型用不了"，很容易误判成"模型本身有问题"。

修法（非流式 + 流式起始块 + 关闭前的 `signature_delta` 三处都给上）：

- 发**空串**而不是伪造签名：空串能过类型校验（SDK 里是 string），
  伪造的签名会被真正的上游拒；而网关这条路的上游并不校验签名。
- 流式还要补 `signature_delta`：官方流里签名是这个时机下发的，
  客户端在 `content_block_stop` 时才把签名并进完整块，缺了它最终块仍然没有签名。

### 2. OpenAI 协议下"只有思考"时 `content` 被写成 null（已修）

```java// 原代码
msg.put("content", c.text.length() == 0 ? JSONObject.NULL : c.text.toString());
```

规范上 `content` 为 null **只用于"这一轮在调工具"**。但如果既没有正文、又没调工具
（只有一个推理模型的思考内容），再发 null 就会被严格类型的客户端判成非法响应。
**这种响应只有推理模型会产生**，症状同样落在 k3 / k3-1 上。

改成三段判断：有正文给正文；没正文但有工具调用给 null（规范如此）；
两者都没有给空串。

### 验证

两条都进了内置自检（现 **12 项**），在真实渲染函数上断言：

```
自检：全部 12 项通过（能力参数 / 图片 / 思维链 / 标签 / 限流解析）
```

新增的两条：
- `Anthropic 思考块带必填的 signature`：非流式响应里必须有 `signature`，
  流式里必须有 `signature_delta`；
- `只有思考时的 content 不是 null`：只有思考时 `content` 不能是 null，
  且**有工具调用时仍必须是 null**（防止这条修改把规范行为带偏）。

### 仍未确认的部分

这两处是**代码层面的确定问题**（协议字段缺失/非法），也都在推理模型才会走的路径上，
与"只有 k3 / k3-1 出问题"高度吻合。但我**没能做端到端复现**：
这次临时注入的测试 key 没有被应用接受（顶层 `apiKeyAliases` 在存盘时被省略，
我手工插入的那条没能生效），所以没能在真机上用客户端的方式跑一遍
k3 / k3-1 并观察客户端反应。

要彻底定位，需要客户端报错的**原文**（"重连失败/Runtime 运行失败"是客户端自己的措辞，
不同客户端对应完全不同的失败点）。
