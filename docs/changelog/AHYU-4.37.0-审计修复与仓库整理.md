# AHYU 4.37.0：审计修复、可追溯入口与仓库整理

versionCode 629。这一版没有加新渠道，做的是**把已有功能里真正会出错的地方修掉**，
并把散落的资料收进 `docs/`。

## 起因

接手时仓库根目录有 65 份版本记录、6 个历史备份（`.orig*`、7036 行）、
3 个一次性补丁脚本；README 停在 4.30.17，仍写着"keystore 随仓库、口令在 build.sh"，
与实际实现相反。代码层面做了一次只读审计，确认了 15 个有证据的问题，
其中 5 个会造成数据丢失、鉴权失效或账号注入。

## 一、仓库与构建

| 项 | 改动 |
| --- | --- |
| 版本号 | 新增 `version.properties`，`build.sh` 只从这里读；不再在脚本里写死 628/4.36.8 |
| 产物名 | `AHYU-<version>.apk`，与 Release 资产同名 |
| Debug 开关 | 构建期间临时改写 `Debug.java`，`trap` 保证成功/失败/Ctrl-C 后都还原 |
| 文档 | 65 份记录移入 `docs/changelog/`，生成按版本排序的索引；保活记录移至 `docs/notes/` |
| 清理 | 删除 6 个 `.orig*` 备份、3 个一次补丁脚本（内容已验证进入源码） |
| 配置样例 | 新增 `.env.example`；`.gitignore` 放行样例、继续排除 `.env` 与 `*.jks` |
| README | 按 4.37.0 重写：协议面、渠道、构建、签名、目录、已知限制 |

签名密钥曾进入**私有库**的初始提交。本次已从历史中移除（公开库从未包含）；
构建仍使用原密钥，已安装用户可覆盖升级。

## 二、补完两个 4.36.0 未做项

1. **Anthropic / Gemini 的 responseId**
   - Anthropic：`message_start.message.id`（`msg_...`）上报一次；
   - Gemini：SSE 块里的 `responseId`（新版 API 才有，老版本留空不算错）。
2. **可追溯标识的界面入口**：日志页「画像」改为「行为画像与对账」，
   下方新增最近 20 条记录：时间、渠道/模型、质量、上游 ID、会话 ID、工具数。
   上游 ID 用于和上游对账；会话 ID 相同表示同一份请求内容（换号重试会复用）。

## 三、审计修复

### 高

1. **备份跨设备恢复后凭据全空**（`Config.exportJson`）
   导出时用本机 Keystore 密封，再交给密码加密；换机后 Keystore 密钥不存在，
   解不开就变成空串，导入流程却静默成功。现在导出明文配置、只由用户密码加密；
   导入旧备份若发现解不开的密文，直接报错并说明回原机重新导出。
2. **图片 URL SSRF**（`Upstreams.fetchImageAsBase64`）
   以前只判断 `http(s)://`。现在解析域名并拒绝回环、私有、链路本地、CGNAT、
   组播与 IPv4 映射地址；重定向关闭自动跟随，逐跳重查，最多 3 跳。
3. **OAuth 回调不校验 state**（`OAuth.waitForCode`）
   旧条件 `expectedState != null && state != null && !equals` 允许"不带 state"。
   现在 state 必须存在且定长比较；不匹配的回调继续等待，不再接受 code。
4. **账号模型列表并发修改**（`Config.Provider.models/manualModels/badModels`）
   后台刷新 `clear()+add()` 与请求线程遍历并发，可能抛
   `ConcurrentModificationException` 或让保存静默失败。三个列表改为
   `CopyOnWriteArrayList`。
5. **apiKey 解不开时鉴权被静默关闭**（`Config.load` / `Gateway.reload`）
   `Secret.open()` 先把密文换成空串，导致"保留内存值"的分支永远不执行。
   现在在解密前判断，解不开就生成新 Key 并落盘、保持鉴权开启，日志明确提示；
   `reload` 不会用新值覆盖运行中的可用旧 Key。

### 中

6. **竞速线程池与闸门不匹配**（`Server.serveRace`）
   16 场 × 3 路但只有 8 条线程，排队任务在客户端超时后仍会打上游。
   闸门与线程池按 4 场 / 12 线程对齐；排队晚到的 racer 若已决出胜负直接退出。
7. **三路竞速共写同一个 Conn**（`Server.serveRace`）
   `outChars +=`、`seenTextHint` 拼接等字段无锁竞争，用量记录可能来自输家。
   每路改用独立观测对象，胜者确定后合并（`copyObs`）。
8. **WorkBuddy hopeless 只写不读**（`WorkBuddy`）
   4.36.6 已落盘，但判断仍只查内存 Set，重启后又重打 10 次 400。
   新增 `hopeless(p, conv)`：内存与 `Provider.hopeless` 都查，命中时灌回内存。
9. **`/v1/messages` 漏接 RequestCheck**（`Server.handleAnthropic`）
   4.34.0 声称五个入口全覆盖，实际 Claude Code 走的主路径没有检查，
   `count_tokens` 里反而重复调了两次。已补齐并去重。
10. **容量计数泄漏**（`Server.handle`）
    同一 peer 连接超限的拒绝分支在 try/finally 之前返回，没有 `Capacity.leave()`。
    每拒绝一条，inflight 永久 +1。
11. **chunked 请求体按块解码**（`Server.readRequest`）
     UTF-8 多字节字符被 chunk 边界切开时各自变成 U+FFFD；trailer 只读一行会让
     keep-alive 连接错位。改为按字节累积、最后一次性解码，并读完 trailer 到空行。
12. **180 秒静默检测被自己的 ping 破坏**（`Server.openSse`）
    旧判据看的是"写给客户端的字节数"，ping 自身也算，`stalls` 永远不增长。
     改为记录 `lastUpstreamAt`，只由上游内容回调更新。

### 低

13. `catalogOnly` 在两处失效：`models=["*"]` 的兜底只查 `enabled`；
    `/v1/models` 缓存指纹不含 `catalogOnly`。均已修正。
14. `quality=retry` 只对虚拟模型生效：直连模型从不调用 `Repeats.check()`。
    现在所有推理请求在 `serve()` 里记一次（同一 Req 多候选不重复计数），
    主动拦截仍只在虚拟模型入口做。
15. 调试自检里的 Loomy 固定向量过期（body 改过、期望值没跟着改），
    重新计算后 `pass=true`。
16. `InvalidRequest` 的 HTTP 状态被顶层统一写成 500：RequestCheck 拦下的坏请求
    在客户端看来是"网关故障"，会继续重试。现在使用异常自带的 400。

另外修正了 README 与实现不一致、`Router` 头注释"三个虚拟模型/工具能力恒为 0"、
`RawCap` 注释"按天滚动"（实际是 2MB 重置）、`Usage` 注释"12 列"（实际 22 列）。

## 验证

- 构建：`aapt2 → javac → d8 → apksigner` 全通过，签名 SHA-256
  `45a9…23db`，与已安装 4.36.8 相同；versionCode 629 / versionName 4.37.0。
- 真机（同一台设备，覆盖安装）：启动自检 **22/22 通过**（原 20 项 + 新增
  SSRF 地址判定、重复请求计数）。
- `/v1/debug/selftest`：Loomy 固定向量、Trae 登录 URL、Antigravity 授权参数全部通过。
- 畸形 `/v1/messages` 请求返回 **HTTP 400**（修复前是 500），错误文案写明是
  `tool_call` 缺 id / 工具结果缺 `tool_call_id`，没有触发上游调用。
- 发布包 `/v1/debug/*` 返回 404；覆盖安装后配置与账号保留（3 个账号、126 个模型）。

## 未做 / 残余风险

- WorkBuddy 3 的 `11128` 根因仍未证实；本版没有改它的出站形状。
- Trae 的 raw 回调（`refreshToken/userJwt/userInfo`）没有 OAuth state 可校验，
  只收紧了标准 `waitForCode` 路径。本机应用仍可在登录窗口内注入回调；
  要彻底解决需要上游回调支持 nonce，或改成应用内直接解析 WebView 的 URL。
- SSRF 校验与真正建连之间存在 DNS rebinding 窗口；本机场景接受。
- `CopyOnWriteArrayList` 消除了并发修改异常，但 `clear()+add()` 之间仍有极短的
  "空目录"窗口（微秒级），没有改成整体替换。
