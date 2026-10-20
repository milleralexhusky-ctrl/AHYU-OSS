# AHYU 4.32.0：内容信号与工具补偿

版本 4.32.0 / versionCode 610。源码目录 `AHYU-4.32.0`（由 4.30.17 副本改来）。

## 起因

只读勘察发现：`usage.tsv` 的 `ok` 字段只反映传输层（`routeOk && hadProvider && !responseFailed`），
不检查响应内容。模型回"抱歉我不能"、输出被 `max_tokens` 截成半句话、返回空串 —— 全都记成功。
同时 `stopReason` 在 tap 里收到就丢，`length`（截断）与 `stop`（正常）在数据里无差别。
于是"选路"实际只能按延迟区分渠道，无法区分"慢但有用"和"快但没用"。

## 改动

### 1. 修 `hist` 未使用置信度（Router.java）

`score()` / `failRate()` / `failRateKey()` 三处重复的
`hist = seed == null ? 0.5 : seed.ok` 合并为单一 `smoothedRate()`。

历史成功率现在按样本量向先验收缩：`(w·ok + K·PRIOR) / (w + K)`，`w` 是 `Stats.Seed.w`。
延迟估计早就在用 `trust = w/(w+HIST_K)`，成功率此前没享受同等待遇。

实测影响（进程内无样本、全看历史时）：

| 组合 | 旧 rate | 新 rate |
|---|---|---|
| WorkBuddy1（2352 笔） | 0.999 | 0.998 |
| 2 笔全成功 | 1.000 | 0.667 |
| 5 笔全成功 | 1.000 | 0.778 |
| 20 笔全成功 | 1.000 | 0.917 |

弱样本渠道不再被系统性高估。

### 2. 记录三个内容信号（Usage.java / Server.java / Stats.java）

`usage.tsv` 从 9 列扩到 12 列，新增：

- 第 10 列：输出字符数（从 `tap.text(delta)` 累加）
- 第 11 列：是否工具调用（`sawToolCall()`）
- 第 12 列：结束原因（`finish(stopReason, …)`）

全部零成本：来自已有回调点，不额外请求、不改协议、不等上游。
**关键理由**：上游（尤其中转站）经常不返回 `usage`，`tokOut` 可能长期为 0，
但 `text(delta)` 是逐字过来的，自己数不依赖上游。

旧行 9 列，读取端按 `c.length >= 12` 判断，缺列当"无信号"处理，
聚合时分母只用带信号的行 —— 否则会把"没记录"算成"没内容"。

`Stats.Seed` 新增 `contentRate` / `toolRate` / `truncRate` / `wContent`，
**只记录、暂不参与打分**：`score()` 的权重是多轮真机调过的，
没有对照数据之前改权重等于用未验证信号干扰已验证排序。

### 3. `ToolAbility` 三态改计数（ToolAbility.java / Config.java）

`note()` 原来是覆盖式：一次成功就把模型从 `toolBad` 挪进 `toolNative`，
偶发蒙对就能洗白一个系统性打嘴炮的模型。

新增 `Config.Provider.toolStats`（模型 → [native 次数, bad 次数]），判定改为看比例：
坏样本 ≥ 25% 判 BAD，好样本 ≥ 75% 判 NATIVE，中间地带判 UNKNOWN，少于 2 次观测仍走旧布尔名单。
两个 List 保留，旧配置兼容。

### 4. 工具补偿：正文里的调用转成真 tool_call（新增 ToolCallText.java）

`looksLikeToolCall()` 早就能识别正文里的调用标记，但只用于打分。
现在新增 `ToolCallText`：识别 → 提取 → `ToolFix.repairArgs` 修参数 → `sink.toolCall()`。
一个不会原生调工具的模型，通过这一层就能用了。

支持形态：`{"name":…,"arguments":…}`、`{"tool_name":…,"parameters":…}`、
`{"function":{…}}`、`<tool_call>…</tool_call>`、`<invoke name="…">…</invoke>`、
`fn(city="北京")`，以及围栏包裹和前后带废话的情况。

**只做非流式**：`Collector` 的正文还在内存里、还没写给客户端，换错了能退回去；
流式已经出网，撤不回来。判据保守 —— 请求必须声明了 tools、模型没吐过结构化调用、
提取到的名字必须在声明列表里、正文 < 2000 字符。

`Fronts.Collector` 新增 `replaceText()`，替换成功后清空正文，
否则客户端会同时收到"一段带 JSON 的解释 + 一个调用"，且模型下一轮会读到自己的那段正文。

## 验证

- 编译：`BUILD_OK`，javac 零警告。
- `ToolCallText` 自测 12 项全通过，含 4 个必须拒绝的负例
  （工具名不在声明里、普通 JSON 回答、纯文本、空正文）。
- 新旧格式混读自测：6 行中 4 行带信号，旧 2 行正确排除出分母，PASS。
- 真机端到端（com.ahyu，端口 8321）：
  - 普通对话 → 12 列，`tokOut=1` 对应 `字符=2`，`工具=0`，`结束=stop`
  - 多轮回答 → `tokOut=171` 对应 `字符=335`，比例自洽
  - 带 tools 请求 → 返回结构化 `tool_calls`，`finish_reason=tool_calls`，
    说明补偿层没有干扰原生路径

## 未验证 / 待观察

- 工具补偿的**转换路径**没有在真机触发过：当前渠道原生支持工具调用，
  需要找一个打嘴炮的模型（`ToolAbility` 判为 BAD 的）才能真正验证转换。
- `Stats` 的三个新比率已埋点但未接入打分，需要先攒样本再决定权重。
- 发布构建关闭了 `/v1/debug/*`，应用内自检未跑。

## 顺带修正的一条旧结论

工作区里的 `usage_sample.tsv` 显示 `tokOut` 全为 0，据此曾判断"上游不给 token"。
真机 `usage.tsv` 里 `tokOut` 是有值的（185、718 等），`tokIn` 达 8 万级。
**两份数据不是一回事**，那份样本是旧的/不同来源，不能用来推断线上行为。
