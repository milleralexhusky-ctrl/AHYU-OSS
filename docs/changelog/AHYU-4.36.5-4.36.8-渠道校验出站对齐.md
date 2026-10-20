# AHYU 4.36.5–4.36.8：渠道校验出站对齐（11128）

## 起因

WorkBuddy 3（国内 realm）推理请求持续 `HTTP 400 {"code":11128,"msg":"request illegal"}`，
上游 `displayTips` 写明"这次调用没通过渠道校验，可能不是官方客户端发出的"。

## 4.36.5：请求头按官方实现对齐

对照真机可跑通的 wb2api 1.6.3（`defpackage/eb2.java` 的 `a()`）逐项比对，改 4 处：

| 头 | 改前 | 改后 |
|---|---|---|
| `X-Machine-ID` | `"wb-" + uid.hashCode()` | `SHA-256("wb2a:machine:"+uid)` 前 18 字节 base64url |
| `X-Session-ID` | 每次新建随机 UUID 前 16 位 | `SHA-256("wb2a:session:"+uid)` 同上，按 uid **固定** |
| `X-Device-Token` | 总是带，无值为空串 | 有值才带（官方就是 `if(isEmpty) return;`） |
| `X-No-Enterprise-Id` / `X-No-Department-Info` | `"true"` | `"1"` |
| `X-Request-ID`、`X-Agent-Purpose`、`X-IDE-Name/Type`、`X-Product` | 额外带上 | **删除**（官方不发） |

**实测：WorkBuddy 2 通过，WorkBuddy 3 仍是 11128。** 结论：头不是成因，
错误关掉又重试"的方向，不能重复。

## 4.36.6：渠道校验收手策略收敛

- `CONV_HOPELESS` / `INTL_HOPELESS` 原来只在内存，重启就没 → 落到 `gateway.json`
  的 `provider.hopeless`（取值 `conv` / `intl`），写盘用 `Gateway.persistSoon()` 合并。
- 新增 `CONV_HOPELESS_SHAPES_KNOWN = true`：遇到 11128 直接收手，不再逐形状打 5 次。
  依据是 4.31.1 两轮实验里 5 次回包 `requestId` 恒定 —— 形状不是变量。
- 单请求最坏重发次数 **2×5=10 → 2**。

## 4.36.7：补上官方 body 的两个内容派生字段（关键）

反编译 `mn.java:793-841` + `tl1.java:36-75` 得到官方的做法：

1. `messages[0].role` 不是 system 就补 `{"role":"system","content":"You are a helpful assistant."}`
2. `conversation_id` = `"d-" + Base64URL(SHA-256(所有 system content + 第一条 user content，`|` 分隔)[0..7])`
3. `prompt_cache_key` = `"wb2a-" + Base64URL(SHA-256("cache:"+uid+":"+model)[0..11])`

而本项目以前**默认不发** `conversation_id`，发的时候用的是 `"wb-" + 时间戳`（形态完全不同），
`prompt_cache_key` 完全没发。这是 4.31.1 形状实验没覆盖到的唯一变量 —— 当时试的**值形态**
就不是官方那个，所以怎么试都过不了。

4.36.7 按官方派生补上，且**默认就带**。

## 4.36.8：自检补一条

`Selftest.checkOfficialBody()`：钉住 `conversation_id` 必须是 `d-` + 11 字符（共 13）、
内容变了值要变（证明 system 拼进去了）、`prompt_cache_key` 必须是 `wb2a-` + 16 字符（共 21）。

写这条自检时第一版期望值算错（写成 11/20），真机自检当场报错
`conversation_id 形态不对（应 d-+11 字符）：d-FSfL4V2IEzk` —— 代码对、断言错，已修正。

## 复用手法

- 构建：`set -a && . ./.env && set +a && ./build.sh`（`.env` 未被 git 跟踪）
  注意：`source .env` 若在 `set -a` 之前，变量不会导出给子进程，签名步骤会报"没有提供签名密钥口令"。
- 装机：`pm install` 不能读 `/sdcard`（fuse 上下文），先 `cp` 到 `/data/local/tmp/`。
- 覆盖安装后旧进程可能不会自动重启，**必须 `am force-stop` 再 `am start`**，否则日志里
  永远是上一版的自检结果（本轮为此多绕了一圈）。
- frida 在本机（KernelSU + 17.23.3）启动后几秒静默退出，`--version` 正常但监听端口拿不到；
  放到 `/data/adb/` 也一样。想抓实时请求头这条路目前不通，只能靠源码比对。

## 尚未证实的部分

11128 是否由 `conversation_id` / `prompt_cache_key` 缺失或形态不符触发，**仍是假设**。
4.36.7 是把它补上让请求更像官方客户端，不是证明。要判定需要看 WorkBuddy 3 的下一次实测回包。
