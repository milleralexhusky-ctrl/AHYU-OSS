# AHYU 4.21.7 —— 4.21.4 审计问题修复（第二批，第 1→5 条）

按约定的动手顺序 1→5 执行。其中 **第 4 条核实后无需改动**（见下）。

## ① [中] chunked 请求体无上限 —— 已修

审计原判：Content-Length 那条限了 8MB，chunked 分支的 `new byte[size]` 仍然可控。

核对后确认，而且比描述的还严重一点：**单块大小可控 + 累计无上限**，两个洞。

修法（两道都要挡）：
```java
if (size > MAX_BODY_BYTES) { ... throw new IOException("chunk too large: " + size); }
if (sb.length() + size > MAX_BODY_BYTES) { ... throw new IOException("chunked payload too large"); }
```

## ② [中] Cline 的 refresh() 没人调 —— 已接上

审计原判：4xx 分类修了，刷新没接。

核对确认：`Cline.refresh()` 定义了但**全局零调用**。

修法：
- 新增 `Cline.ensureFresh(Config.Provider)`：用 refresh token 换新 access 并回存，
  带 60 秒冷却（避免上游持续 401 时打风暴）；
- `Up.chat` 遇 401/403 时续期一次再重试（**只重试一次，不循环**）；
- 续期失败时返回原 Account，不把"续期失败"伪装成成功。

## ③ [中] WebView 不 destroy —— 7 处全部修完

审计原判：8/11 的 WebView 宿主不调 destroy()。

核对后清单（原来只有 3 处是对的）：

| 文件 | 修复前 | 处理 |
|---|---|---|
| DeepSeekLoginActivity | 无 onDestroy | 上一批已补 |
| Cline / Kimi / Xai | 有 destroy | 无需改 |
| TraeLoginActivity | 有 onDestroy 无 destroy | 补 |
| WebLoginActivity | 同 | 补 |
| WorkBuddyLoginActivity | 同 | 补 |
| OAuthLoginActivity | 同 | 补 |
| AntigravityLoginActivity | 同 | 补 |
| BrowseActivity | 无 onDestroy，web 是局部变量 | 提成字段 + 补 |
| DoubaoLoginActivity | 无 onDestroy | 补 |

## ④ [中] providers 无锁迭代 —— 核实后无需改动

采纳了建议方向（CopyOnWriteArrayList），但**核对发现已经是它了**：

```
opt511 (4.19.4)  Config.java:68  new java.util.concurrent.CopyOnWriteArrayList<>()
opt512 (4.20.0)  Config.java:70  同上
cpa-android (最早) Config.java:55 new ArrayList<>()   ← 只有老工程是裸的
```

即：该条在 4.19.4 之前就修过了，审计报告这一条是过时信息。

顺带扫了一遍安全性：66 处遍历直接对 COW 迭代（安全）；
22 处增删走 COW 自身（安全）；**没有按索引删**。
那些 `new ArrayList<>()` 都是新的局部结果集，不是把 providers 包一层。

## ⑤ [高] Android 12+ 保活 —— 已修，但真机验证不完整（见下）

### 修了什么

**KeepAliveJobService**：
1. 周期任务**不能**标 expedited——这是真机抓出来的：
   ```
   W CPA : 登记保活任务失败：java.lang.IllegalArgumentException:
           An expedited job cannot have a time delay
   ```
   （`setExpedited` 与 `setPeriodic` 互斥，加急作业不允许有 time delay）
2. 因此拆成**两个 job**：
   - `4711` 普通周期任务（15 分钟）：巡检 + 发现该拉活
   - `4712` 一次性加急任务：**只有它有资格在后台起前台服务**
3. `onStartJob` 里区分"被系统拒绝"与"真失败"，被拒时打 ERROR 并提示去开自启动，
   不再静默吞掉
4. `onStopJob` 对一次性加急任务不要求重排期

**BootReceiver**：
- 捕获从 `Exception` 放宽到 `Throwable`；
- 直接拉起失败时**降级兜底**：登记周期任务 + 加急拉活任务；
- 成功/失败都有明确日志。

### 实测（真机）

| 项 | 结果 |
|---|---|
| 修复前 4.21.6 | job 登记**直接失败**（IllegalArgumentException） |
| 修复后 4.21.7 | `I CPA : 已登记保活任务（每 15 分钟检查一次）` |
| 系统侧确认 | `JobStatus{ #u0a365/4711 ... PERIODIC PERSISTED ...}` —— 两个标记都在 |
| 前台服务保护 | `am kill` 杀不掉（进程存活），说明前台服务在受保护 |
| 端口 | 8321 正常监听 |

### 真机验证不完整的部分（如实说明）

**没能端到端验证"进程被杀 → 加急 job 拉起网关"这条链**，卡点：

1. `cmd jobscheduler run -f` 在进程已死时失效：
   ```
   E JobScheduler.Concurrency: Error executing JobStatus{... 4711 ... READY}
   W JobServiceContext: java.lang.IllegalArgumentException: Service not registered
       at JobServiceContext.executeRunnableJob(JobServiceContext.java:504)
       at JobSchedulerShellCommand.runJob(...)
   ```
   堆栈落在 `JobSchedulerShellCommand`，**这是 shell 强制触发命令本身的限制**
   （Service 绑定尚未重建），不是应用代码问题。
2. 真实周期是 15 分钟，等待成本高；
3. `force-stop` 会清掉该应用的 JobScheduler，构造不出"进程被杀但 job 保留"的稳定场景；
4. `STOP` action 按设计会 `KeepAliveJobService.cancel()`，属预期行为。

**结论：job 登记这一环已确证修好（这是 4.21.6 的实际故障点）；
"加急任务成功拉起前台服务"这一环尚未取得端到端证据。**

要补上这一环，建议**重启手机后观察**：
- 若 `autostart` 为 true，看开机后网关是否自动起来（验 BootReceiver）
- 或等一次真实的 15 分钟周期，看 logcat 里是否出现
  `保活任务：检测到网关未运行，重新拉起` 且**没有** `ForegroundServiceStartNotAllowedException`

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | BUILD_OK |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 528 / versionName 4.21.7 |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 528 / 4.21.7）
- 副本 `/sdcard/Download/cpa/AHYU-4.21.7-com.ahyu.apk`

## 尚未处理（审计剩余项）

- [中] LoginWebView 外部协议无确认直接拉起
- [中] 电池白名单后台起 Activity 无声失败
- [中] ProviderEditor 静态 Activity
- [中] WbTaskChores 无重入 guard
- [低] 流内错误缺 [DONE]、?key= 传密钥、STATS 无淘汰、Limits 非原子、
  Doubao 二维码 token 前 8 位进日志、Secret 回退明文、剪贴板/明文输入框/端口 hint
- 存疑：save() 的 400ms 常量、ProviderEditor 静态野引用
