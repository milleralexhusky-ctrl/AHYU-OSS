# AHYU — Android AI 网关

把多家 AI 服务商的能力聚合到一台 Android 手机上，让本机（或同一局域网）以
**OpenAI / Anthropic / Gemini / Ollama / Azure 风格接口**访问这些账号。

- 包名：`com.ahyu`（源码包 `com.cpa.gateway`）
- 当前版本：**4.51.6**（versionCode 654，见 `version.properties`）
- 纯 Java，**不使用 Gradle、没有任何第三方依赖**；构建脚本就是 `build.sh`
- minSdk 26 / targetSdk 35

## 它能做什么

**协议面**

| 入口 | 说明 |
| --- | --- |
| `POST /v1/chat/completions` | OpenAI Chat Completions（含流式、工具调用） |
| `POST /v1/completions` | OpenAI 旧版 Completions（含流式） |
| `POST /v1/responses` | OpenAI Responses（含流式工具调用） |
| `POST /v1/messages`、`/v1/messages/count_tokens` | Anthropic Messages |
| `POST /v1beta/models/{model}:generateContent` / `:streamGenerateContent` / `:countTokens` | Gemini |
| `POST /api/chat`、`/api/generate`、`/api/embed`、`GET /api/tags`、`/api/show` 等 | Ollama 原生协议（NDJSON） |
| `POST /v1/embeddings`、`/v1/moderations`、`/v1/images/generations` | OpenAI 非聊天端点（原样转发） |
| `POST /v1/rerank`、`/v1/embed` | Cohere / Jina 形状的重排与向量化 |
| `GET /v1/models/{id}` | 单个模型查询 |
| `POST /v1/audio/transcriptions`、`/v1/audio/speech` | 语音转写 / 合成（multipart / 二进制） |
| `POST /v1beta/models/{model}:embedContent` / `:batchEmbedContents` | Gemini 向量化 |
| `/v1/assistants`、`/v1/threads`、`.../messages`、`.../runs` | OpenAI Assistants / Threads（含 requires_action 工具闭环） |
| `ws://127.0.0.1:8321/v1/realtime` | OpenAI Realtime（文本；音频未支持） |
| `/openai/deployments/{deployment}/chat/completions` | Azure OpenAI 路径 |
| `GET /v1/models`、`GET /health`、`GET /v1/capacity` | 模型目录、健康、容量 |
| `POST /api/pull`、`/api/delete` 等 | 代理到本机 Ollama（:11434）的模型管理 |
| `GET /panel` | 手机浏览器里的状态面板 |

**账号与渠道**：WorkBuddy（CodeBuddy）、Trae、Loomy、Kimi、Antigravity、Claude、
ChatGPT/Codex、DeepSeek、豆包、Cline、xAI、Kilo、OVH，以及自定义
OpenAI/Anthropic/Gemini 兼容中转。
登录方式按渠道支持 OAuth、设备码、WebView、API Key 等。

**自定义上游协议**：OpenAI 兼容、Anthropic Messages（文档里“Claude Code 接入”的
根地址、`/v1` 或完整 `/v1/messages` 都可直接粘贴）、Gemini。Anthropic 上游会
带上 Claude Code 形状的 UA / `x-app` / `anthropic-beta`，并保留 `system` 块的
`cache_control` 前缀缓存。

**选路与稳定性**：`auto` / `auto-code` / `auto-long` / `auto-race` 虚拟模型；
按成功率、延迟、成本、额度、工具能力打分；首字节前自动换号；限流指数退避、
熔断与半开试探、渠道校验单独回避、本地限额与并发槽。

**两种模式要分清**：点名模型（含 `账号/模型` 前缀）是**纯调用**——只换同模型的账号，
模型行为坏了或请求不合法就立刻返回明确的 4xx，不偷偷换模型；`auto*` 是**轮换**——
可以换号也可以换模型。坏模型、坏号、坏请求由 `FailScope` 分开处理，模型问题不会写成
账号不可用（见 `docs/changelog/AHYU-4.50.2-登录与失败作用域.md`）。

**观测**：每次推理写一行 `usage.tsv`（22 列：来源、工具数、响应形态、解析结果、
质量档、上游响应 ID、会话 ID、错误归类、工具名……）；日志落盘并滚动；
可疑响应留原始样本；日志页内直接看「行为画像」和「最近请求对账」。

**其它**：账号备份/恢复（导出加密）、一键诊断包（脱敏配置/日志/用量/自检）、
前台服务 + 看门狗 + 定时保活、局域网访问（强制 API Key）、IPv4/IPv6 回环、
多套配色、无图片的程序化背景。

**界面**：主页面下拉刷新；日志页**上翻即暂停跟随**（右下角「回到最新」恢复，
不再把正在读历史的人拽回底部）；主题选择器用聚焦语义（选中的放大提亮、
未选中的降饱和缩小）；按压手感走真阻尼弹簧（`Jelly`），帧预算由 `Perf`
自动调档。自检 **41 项**，含手势状态机与配色亮度极差两条"防静默失效"断言。

## 构建

准备 Android SDK（只需要 platform 35 的 `android.jar` 和 build-tools 35 的 `d8`）：

```
android-sdk/
├── platforms/android-35/android.jar
└── build-tools/35.0.0/d8
```

其余工具在 `PATH` 里：`aapt2`、`javac`（JDK 11+）、`apksigner`、`keytool`、`python3`。

```bash
export ANDROID_SDK="$HOME/android-sdk"
export KS_STOREPASS='你的签名口令'
export KS_KEYPASS="$KS_STOREPASS"        # 与 storepass 相同可省略
./build.sh                               # 发布构建：关闭 /v1/debug/*
./build.sh --debug                       # 调试构建：开启 /v1/debug/*
```

产物是根目录下的 `AHYU-<version>.apk`。版本号只改 `version.properties`：

```properties
version.code=629
version.name=4.37.0
```

环境变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `ANDROID_SDK` | `./android-sdk` → `/opt/android-sdk` | SDK 根目录 |
| `BUILD_TOOLS` | `35.0.0` | build-tools 版本目录 |
| `AAPT2_BIN` | 自动探测 | 指定 aapt2 可执行文件 |
| `KS_PATH` | `./keystore.jks` | 签名密钥路径 |
| `KS_STOREPASS` / `KS_KEYPASS` | 无 | **必填**；口令不写进仓库 |

## 签名（重要）

**签名密钥不随仓库分发**，`build.sh` 也不会在缺密钥时自动生成一把新的：
新密钥签出的包与已安装版本签名不同，覆盖安装会失败，只能卸载重装、数据全丢。

- 只想用 App：到 Releases 下载 APK。
- 自己调试：显式生成一把本地密钥（`build.sh` 的报错信息里有完整命令），并清楚它与发布版不兼容。
- 要发布可覆盖升级的包：使用你自己的 `keystore.jks` 与口令，二者都只留在本地
  （`.gitignore` 已排除 `*.jks`、`.env`）。

## 目录

```
AndroidManifest.xml        清单
build.sh                   构建 + 签名
version.properties         版本号唯一来源
src/com/cpa/gateway/       UI / Activity / 自定义 View
src/com/cpa/gateway/core/  网关核心
res/                       资源（布局、图标、model_prices.json）
docs/changelog/            逐版本改动记录（4.20.0 → 现在）
docs/notes/                真机验证记录
docs/research/             协议调查脚本
```

核心类：

| 文件 | 职责 |
| --- | --- |
| `core/Server.java` | 自带 HTTP/1.1 服务、鉴权、路由、四套协议、竞速与失败重试 |
| `core/Router.java` | 虚拟模型、打分选路、冷却、熔断、限流退避 |
| `core/Upstreams.java` | 规范化请求 → 各家上游协议，流式解析与工具调用汇总 |
| `core/Config.java` | 配置模型与持久化（原子写、加密敏感字段、备份） |
| `core/Fronts.java` | OpenAI / Anthropic / Gemini / Responses 入口解析 |
| `core/WorkBuddy.java` 等 | 各渠道的账号与协议实现 |
| `GatewayService.java` | 前台服务、看门狗、保活 |
| `MainActivity.java` / `Ui.java` | 单页主页与共享 UI 组件 |

## 性能与保真

- 150 次 keep-alive 真机压测：`/v1/models` p50 0.77ms、`/health` 0.99ms、`/v1/capacity` 1.18ms。
- 本机多应用并发：回环地址不再共用单客户端连接配额（56/64），
  20 个并发连接实测 20/20 成功；容量模型只统计真实请求，空闲 keep-alive 不计入。
- 流式首字节：响应头与 `: ready` 立即下发（实测 3.7ms），
  轮换中还没轮到的候选首 token 只等 10 秒、最后一个 45 秒；回空模型 45 秒内不再选。
- 流中卡住：上游 60 秒没有任何字节即断开；已经出过内容后 80 秒没有新内容
  就发协议对应的超时错误（Anthropic 用 `event: error`），不再一直停在“思考中”。
- 会话亲和（`auto*`）：同一段对话的后续轮次优先复用上次成功交付的候选，
  保住 prompt cache、风格一致与工具循环不断档。会话键按“system + 首条 user”
  派生（也认客户端自带的 `prompt_cache_key`），所以跨虚拟模型（`auto` → `auto-code`）
  仍然命中；客户端**重发同一条请求**时则让反粘连生效，不硬绑。
  失败的候选不进亲和表，且亲和只调顺序、不绕过熔断。
- 同协议转发时保留未建模字段（`top_k` / `service_tier` / `metadata` / `safetySettings` 等），
  不因网关不认识而丢掉；跨协议不硬塞。
- 客户端要 `n>1`、`logprobs` / `top_logprobs` 或音频输出时，OpenAI 兼容渠道走
  **响应原样透传**：多 choice、token 概率、`system_fingerprint` 不再被 canonical
  合并掉；选路、换号、断流检测、用量统计照旧。普通请求仍走 canonical，行为不变。`top_k` 在 OpenAI / Anthropic / Gemini 之间可等价翻译。

## 资源占用

实测（本机 1280×2772，`dumpsys meminfo com.ahyu`）：

| 状态 | PSS | 说明 |
| --- | --- | --- |
| 无界面（开机自启 / 保活拉起） | ~27MB | Java 堆约 3.4MB；不再预分配底纹位图 |
| 打开界面 | ~61MB | 大头是系统管理的窗口 GPU 缓冲（~18MB）与 HWUI 渲染缓冲 |

底纹位图（约 3.5MB）在界面隐藏或内存压力时释放，下次打开重画（6–11ms）。
界面态继续压缩需要改窗口格式或隐藏时销毁 UI，都会影响观感/返回栈，未默认启用。
详见 `docs/changelog/AHYU-4.38.0-内存优化.md`。

## 接手与排查

1. 根目录 [`docs/changelog/README.md`](docs/changelog/README.md) 有按版本排序的索引。
2. 每个版本记录都写了「起因 / 改动 / 验证 / 未做」，**未验证的部分不会伪装成结论**。
3. 排查顺序：日志页「行为画像」看渠道整体可用率 → 「最近请求对账」拿上游
   responseId 与上游对账 → `docs/changelog` 里找最近动过该渠道的版本。

## 已知限制

- WorkBuddy 国际网关从 2026-10-15 起校验官方客户端的 `X-Conversation-*` 头；
  4.50.4 已按官方形状补齐。新版 11128（`Illegal API invocation from an unapproved
  channel`）是请求级风控、上游提示“请重新发送”：网关最多同账号重发 2 次，
  仍失败只短回避 20 秒，不再把模型锁 6 小时。账号被标记的旧 11128 仍按账号问题处理。
- Antigravity 的 OAuth Client Secret 由私库构建时注入（`.private/signing.env` 的
  `ANTIGRAVITY_CLIENT_SECRET`）；用公库源码自行构建时该项为空，登录会停在 token 交换。
- 多数渠道依赖网页/客户端私有协议，上游改版后可能失效；这类问题优先看
  `docs/changelog` 与 `rawcap.log`。
- 仅供自有账号使用。请遵守各服务商的条款；本项目不分发任何账号或密钥。

## 说明

`Debug.ON` 必须为 `false` 才是发布包；`build.sh` 会在编译期间强制改写，
并在结束时还原工作区（成功、失败、中断都会还原）。
