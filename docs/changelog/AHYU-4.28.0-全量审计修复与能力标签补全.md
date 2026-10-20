# AHYU 4.28.0（versionCode 557）— 全量代码深度审计修复与模型能力标签补全

本版本对 `AHYU-4.27.0` 工程全部 75 个 Java 源码文件进行了 100% 逐行深度审计，一次性完成 **一、核心网关与流式协议/并发竞态修复**、**二、上游通道/一键做任务/探活检测/UI 交互缺陷修复**、**三、模型能力标签算法修复与自定义/国产模型补全及工程清理** 三大方向的全部改造。

---

## 一、核心网关、流式协议与并发/熔断机制修复（6 项）

1. **`Upstreams.anthropicStream` 工具调用参数 `input_json_delta` 覆盖丢块修复（高危）**
   - **原因**：`Upstreams.java` 在处理 Anthropic SSE 的 `content_block_delta`（`type = "input_json_delta"`）时，直接执行了 `blockInfo.put(idx, new String[]{info[0], info[1], partial})`，用最后一个分片**覆盖**了前面所有分片，导致 Claude / Kimi Code / Anthropic 兼容渠道只要工具调用参数超过 1 个分片就会被截断成残缺 JSON。
   - **修复**：改为追加拼接 `prev + partial`，并在流结束分发 `sink.toolCall` 前统一经过 `ToolFix.repairArgs()` 修复与 `ToolFix.nameFromArgs()` 兜底。

2. **`Server.serve()` 限流/配额耗尽（`Throttled`）在竞速与内部探活线程下丢失模型名修复（高危）**
   - **原因**：`serve()` 虽然已接收显式的 `Config.Provider p` 与 `String upstreamModel`，但在 `catch (Throttled t)` 分支中仍通过 `Conn c = CURRENT.get()` 拼接限流 Key。在 `auto-race`（`RACE_POOL` 线程）和 `Detect` 内部探活（`PROBE_TIMERS` 线程）中，`CURRENT.get()` 恒为 `null`，导致限流退避被写到死键 `p.name`（无 `/model`），而 `Router.candidates()` 与 `Router.available()` 查询的均是 `p.name + "/" + model`，致使 429 退避完全失效。
   - **修复**：直接使用入参构造 `routeKey = p.name + "/" + upstreamModel` 记录 `Router.recordThrottled()`；同时在 `Router.recordThrottled()` 内清理半开标记 `s.probingAt = 0`，防止半开试探遇 429 后被死锁 90 秒。

3. **`Router.candidates()` 遍历候选时批量误锁所有 `HALF_OPEN` 模型 90 秒 + `Stat` 锁外读竞争修复（高危）**
   - **原因**：`Router.candidates()` 在枚举候选池时，对每一个冷却到期的半开（`openCount > 0`）候选直接赋了 `st.probingAt = nowMs`。一次选路只取前 1～3 个候选（而 `describe()` 或 `raceCandidates()` 更会连调 3 次 `candidates()`），导致所有未被选中的半开模型在未发任何请求的情况下全部自锁 `PROBE_GRACE_MS`（90 秒）；同时 `score()` 与 `candidates()` 在 `synchronized (STATS)` 锁外裸读 `Stat` 字段。
   - **修复**：将 `candidates()`、`score()`、`available()` 中的 `Stat` 读取全部纳入 `synchronized (STATS)` 保护；`candidates()` 仅做只读判断，新增 `Router.markProbingIfHalfOpen(key)` 并在 `Server.serve()` 真正发起非探活请求时才打上 `probingAt` 标记。

4. **直连路径与 Anthropic / Gemini / Responses 协议补齐 `Router` 熔断与首字延迟反馈（中危）**
   - **原因**：`pick()` / `pickAll()` / `resolveVirtual()` 虽然读取了 `Router` 的分值与熔断状态，但只有虚拟模型在 `/v1/chat/completions` 上调用了 `Router.recordOk` / `recordFail` / `abortProbe`。客户端直连具体模型名或走 `/v1/messages`、`/v1beta/models/...`、`/v1/responses` 时，熔断器永远拿不到成功/失败反馈，EWMA 延迟也恒为 0。
   - **修复**：新增 `Server.recordDirectOutcome()` 并在 OpenAI 直连、Anthropic、Gemini、Responses 的流式与非流式路径统一回写 `recordOk` / `recordFail` / `abortProbe`；同时让 `resolveVirtual(model, req)` 传入真实 `Proto.Req`，使非 OpenAI 协议上的虚拟模型同样具备工具感知、图片视觉与上下文长度过滤能力。

5. **`Router.raceCandidates()` 补齐图片视觉（`vision`）与上下文长度（`contextLimit`）过滤（中危）**
   - **原因**：`serveRace()` 调用 `Router.raceCandidates(RACE_N)` 时未传入 `Proto.Req req`，内部调 `candidates(pool, false, null)`，导致带图请求或超长上下文请求在 `auto-race` 下绕过了 `!p.vision` 与 `p.contextLimit` 过滤。
   - **修复**：新增重载 `Router.raceCandidates(int n, Proto.Req req)` 并在 `serveRace()` 中传入 `req`。

6. **`Server.openSse()` 上游 180 秒无字节心跳超时补发 `data: [DONE]`（中危）**
   - **原因**：`stalls >= 18` 超时分支只写出了 `upstream_timeout` 错误 JSON 事件便停止了心跳，未发送 `data: [DONE]\n\n`，导致严格等待 `[DONE]` 的官方 OpenAI SDK 客户端继续挂起。
   - **修复**：在写出错误帧后紧跟写出 `data: [DONE]\n\n` 再 `flush()`。

---

## 二、上游通道、一键做任务、探活检测与 UI 交互修复（9 项）

7. **「一键做任务」（`MainActivity.runDailyTasks`）弹窗空白与积分恒为 `+0` 彻底修复（高危）**
   - **原因**：4.21.8 将 `/v1/tasks/run` 改为异步立即返回 `{"ok":true,"started":true}`，而 `MainActivity.runDailyTasks()` 仍在同步读取 `resp.optJSONArray("ran")`（恒为 `null`），导致弹窗明细永远为空、积分累计恒为 `+0`；且若网关处于停止状态时点击「一键做任务」，回环 HTTP 请求直接报 `Connection refused`。
   - **修复**：在 `Server` 中暴露进程内静态方法 `Server.runAccountChores(Config.Provider p)`（同时在 `/v1/tasks/run` 支持 `"sync": true` 参数），由 `MainActivity.runDailyTasks()` 在 3 路并发池中直接调用，不依赖网关 HTTP 端口是否运行，实时渲染每个账号的签到、任务、连登兑换、抽奖、猫猫旅行及讯飞每日打工结果。

8. **`WbTaskRunner` 国际版 WorkBuddy（`realm="global"`）硬编码国内域名修复（高危）**
   - **原因**：`WbTaskRunner.billCall()`（行为上报 `/v2/report`）与 `claim()`（领奖 `/activity/growth/tasks/%s/claim`）硬编码了国内域名 `www.codebuddy.cn` 与 `www.workbuddy.cn`，导致国际版账号行为上报与领奖跨区鉴权失败。
   - **修复**：新增 `billBase(acc)` 与 `webBase(acc)`，当 `acc.realm` 为 `global` 时自动切换为 `https://www.workbuddy.ai`。

9. **`Upstreams.fetchModels(p)` 与 `RelayWatch.isApiChannel(p)` 漏接 `Cline` / `xAI` 等通道修复（高危）**
   - **原因**：
     1. `Upstreams.fetchModels(Config.Provider p)` 漏掉了 `TYPE_CLINE` 与 `TYPE_XAI`，落入底部通用 `fetchModels(p.type, p.baseUrl, p.apiKey)`（因 OAuth 账号 `p.apiKey` 为空而必败），导致「一键检测」与账号详情页「刷新模型」对 Cline 和 Grok 账号永远报错。
     2. `RelayWatch.isApiChannel(p)` 仅排除了 7 种早期账号类型，漏排了 `deepseek`、`doubao`、`xai`、`cline`、`kilo`、`ovh`，导致每日中转站巡检把它们当作普通 API Key 中转站去探测并在连续失败后自动停用（`p.enabled = false`）。
   - **修复**：在 `Xai.java` 新增带鉴权头与兜底清单的 `Xai.fetchModels(p)`，在 `Upstreams.fetchModels(p)` 接入 `TYPE_CLINE` 与 `TYPE_XAI`，并在 `RelayWatch.isApiChannel(p)` 排除全部内置账号与零凭证免费池类型。

10. **`Detect.sweep()` 第一阶段目录刷新计数 `P.refreshed` 重复递增修复（中危）**
    - **原因**：`Detect.java` 在 `cataloguePool.submit` 的 `try` 块末尾已调用一次 `P.refreshed.incrementAndGet()`，而在 `catch` 块之后又多调了一次，导致成功账号被計 2 次、失败账号也被计入 `refreshed`。
    - **修复**：删去 `try/catch` 块外重复的 `P.refreshed.incrementAndGet()`。

11. **`DeepSeek` 多账号共用单一 `sharedSessionId` 串号、锁不一致及限流异常分类修复（中危）**
    - **原因**：`DeepSeek.java` 用单一静态变量 `sharedSessionId` 在所有账号的探活请求间共用，多账号并发探活时 B 账号拿 A 账号的 `sessionId` 请求上游直接报错；且 `sessionFor` 锁在 `FreshLocks.forKey("deepseek-shared-session")`，`maybeRetireSharedSession` 却锁在 `DeepSeek.class`。
    - **修复**：改为按账号 `uid`/token 尾号隔离的 `SHARED_SESSION_IDS` / `SHARED_SESSION_ATS` 映射，统一锁在 `FreshLocks.forKey("deepseek-shared-" + skey)`；并对限流/配额错误抛出 `Throttled` 而非普通异常。

12. **`Codex` 并行工具调用参数串台修复 & `Cline` 工具调用/推理内容/Token 用量解析补齐（中危）**
    - **原因**：`Codex.Up.chat` 开启了 `parallel_tool_calls: true` 却共用单一 `StringBuilder args`；`Cline.Up.chat` 只取了 `message.content` 并硬编码空 `Usage`，丢弃了 `reasoning_content`、`tool_calls` 与 `usage`；且 `Cline.ensureFresh` 使用了类级 `synchronized`。
    - **修复**：`Codex` 改为按 `item_id`/`call_id` 独立累积参数并接入 `ToolFix.repairArgs` 与 `Throttled` 识别；`Cline` 改为按账号锁 `FreshLocks.forKey(p.name)` 并完整解析 `reasoning_content`、`tool_calls`、`finish_reason` 与 `usage`。

13. **`Config` / `Secret` / `SettingsActivity` 密钥别名持久化、64 位时间戳截断与短读修复（中危）**
    - **原因**：`Config.toJson()` 漏写了顶层 `apiKeyAliases`（且 `SettingsActivity.save()` 改密钥时未将旧密钥归档进 `apiKeyAliases`）；`lastErrorAt`、`creditsAt`、`lastRelayCheck` 等 `long` 字段用 `Proto.optInt` 读取有 32 位截断风险；`Config.readAll()` 与 `countProvidersOnDisk()` 使用单次 `in.read(buf)` 存在短读隐患。
    - **修复**：在 `Proto` 新增 `optLong` 并全面替换 `long` 字段读取；补齐顶层 `apiKeyAliases` 的序列化、加密/解密与修改归档；将 `readAll()` 改为循环读满 EOF 并复用于 `countProvidersOnDisk()`。

14. **`ProviderEditor` 主页导入账号主线程网络崩溃（`NetworkOnMainThreadException`）与下拉框漏项修复（高危）**
    - **原因**：从主页「添加账号 → 导入」选择文件后，此时并未打开 `ProviderEditor`（`pendingCreds == null`），旧代码先弹出一句错误的「编辑窗口已关闭，凭据未写入」，紧接着在 **UI 主线程**直接同步调用 `WorkBuddy.registerProvider()` 发起网络请求，触发 Android `NetworkOnMainThreadException`；此外类型下拉框 `ORDER` 漏掉了 `xai`、`cline`、`kilo`、`ovh` 4 种类型，编辑这些账号并保存会静默把类型篡改为 `openai`。
    - **修复**：将 `WorkBuddy.registerProvider()` 移入 `Threads.io()` 后台线程执行并刷新主页状态，去掉误导提示；在 `ProviderEditor.ORDER` 补齐全部 16 种类型。

15. **`AccountsActivity`「全部」筛选失真、`MainActivity`/`Ui` 厂商归类缺失与 `FlowRow` 换行间距修复（中危）**
    - **原因**：
      1. `AccountsActivity` 在 `scope == 0`（「全部」）时仍调用了 `usable()`，导致「全部」与「可用」完全相同，停用或故障账号在厂商分组里消失。
      2. `Ui.typeLabel()` 漏了 `trae`、`loomy`、`antigravity`、`anthropic`、`cline`、`kilo`、`ovh`，导致这些账号在列表里全部显示成「OpenAI 兼容」；`MainActivity.vendorOf()` 漏了 `doubao`、`xai`、`cline`、`kilo`、`ovh`，导致它们全被分进「OpenAI 兼容中转」。
      3. `FlowRow` 多行能力标签换行时没有垂直行间距（`y += lineH`），上下两行标签紧贴在一起。
      4. `AccountDetailActivity` 点击复制模型名未走 `Ui.copyWithAutoClear`（Android 13+ 敏感/自动清理统一封装）。
    - **修复**：逐一修正上述 4 处 UI 逻辑，所有 16 种通道类型在分组、头像配色、类型名称、官网跳转与下拉框中全部对齐。

---

## 三、模型能力标签（`ModelCaps` / `Prices`）修复补全与工程清理（3 项）

16. **`ModelCaps.lookup()` 第 4 步「去版本后缀回退」100% 死代码修复 + 异步无锁化加载（高危）**
    - **原因**：`ModelCaps.lookup()` 先对输入调用了 `String n = norm(model)`（把所有 `-`、`.`、`/` 全部删光），随后对 `t = n` 查找 `t.lastIndexOf('-')`——永远返回 `-1`，导致注释里写的「逐段砍去 `-0528`、`-preview` 等版本后缀再匹配」**一行都未执行过**！且 `lookup(model)` 未剥离输入里的 `provider/` 前缀与 `:free` 标签；`Gateway.init()` 在主线程同步解析 272KB JSON 并向静态 `HashMap` 边写边读存在并发竞态。
    - **修复**：
      - 先在局部变量 `built` 中完成构建，最后一次性原子赋值给 `volatile Map<String, Caps> TABLE`，并通过 `Threads.io(ModelCaps::loadSync)` 后台预热（与 `Prices` 一致）。
      - 在 `lookup()` 中支持剥离 `provider/` 前缀与 `:free` 等冒号标签，并在**保留连字符 `-` 的原始小写模型名**上执行后缀剥离（支持版本号/日期段及 `free`、`preview`、`latest`、`exp`、`beta`、`lkeap`、`online`、`search`、`instruct`、`chat`、`it`）。
      - `Prices.java` 同步改为原子发布 `builtTable`，并补上剥离 `provider/` 前缀的裸模型名索引。

17. **内置补全 WorkBuddy 自定义模型、腾讯混元、豆包网页版、阶跃星辰、Loomy、Kimi、GLM-5、DeepSeek-V4、Codex、Grok 等能力标签**
    - 在 `ModelCaps.registerBuiltins()` 与 `registerChannelOverrides()` 中精准补齐了 LiteLLM 未收录或易被通用前缀误导的模型能力：
      - **网关虚拟模型**：`auto`、`auto-code`、`auto-long`（1M 上下文）、`auto-race`
      - **WorkBuddy / 腾讯混元系列**：`balanced-model`、`deep-model`、`summary`、`codewise-jump`、`space-bunny`、`aquila`、`hy3`、`hy3-x`、`hunyuan-t1`、`hunyuan-turbos`、`hunyuan-large`、`hunyuan-standard`
      - **豆包网页版专属修正**：`doubao`、`doubao-think`、`doubao-seed-evolving`（准确标记支持多模态与联网、**不支持**工具调用，避免被火山引擎 API 前缀误标为支持工具）、`doubao-1.5-pro`、`doubao-1.5-thinking-pro`
      - **国产主力与新模型**：`step-5-preview`、`step-5-preview-free`、`step-3.5-flash`、`step-2-16k`、`kimi-for-coding`、`kimi-latest`、`kimi-k2` / `k2.5` / `k2.6` / `k3`、`glm-5` / `5.2` / `5.3` / `5.3-flash`、`deepseek-chat`、`deepseek-reasoner`、`deepseek-v4` / `v4-pro` / `v4-flash` / `v4.1-flash`、`minimax-m2` / `m3`、`loomy-default`
      - **海外与免费池**：`codex-mini-latest`、`gpt-5-codex`、`gpt-5.1-codex`、`gpt-5.1-codex-mini`、`grok-4.1-thinking`、`kilo-auto`、`kilo-auto/free`

18. **清理全工程 46 个 `.bak*` 历史备份文件**
    - 已彻底删除根目录 `AndroidManifest.xml.bak513`、`src/com/cpa/gateway/*.bak*` 及 `src/com/cpa/gateway/core/*.bak*` 共 **46 个**历史备份文件，保持源码树整洁。
