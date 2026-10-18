# AHYU 4.30.22 — 单一状态源 + 本地原因枚举

版本：4.30.22 / versionCode 605
日期：2026-10-18

## 起因

4.30.18 / 4.30.19 / 4.30.20 三轮修复表面是三个 bug，实际是**同一个结构问题的三种表现**：
"网关是否在运行"这件事，有太多地方各自回答。

排查结果：这个事实当时由 **10 个量**共同表达

| 量 | 位置 |
|---|---|
| `Gateway.server` | Gateway |
| `Server.running` | Server |
| `Server.socket` | Server |
| `Server.acceptThread` | Server |
| `GatewayService.instance` | GatewayService |
| `Config.keepAlive` | Config |
| `Config.userStopped` | Config（4.30.19 新增） |
| 主界面按钮文案 | MainActivity |
| 通知文案 | GatewayService |
| 磁贴 STATE | GatewayTileService |

**读取判断点 20 余处，分布在 11 个文件。** 每加一个维度就要在所有地方对齐一次。

另一条线：**中文被当成逻辑的输入**（31 处）。典型是
`Server` 抛 `new Throttled(..., "并发已满")`，`Detect` 再 `w.contains("并发已满")` 认回来——
同一条错误被翻译成中文、再被反解回原因，改一个字就静默失效。

## 改动一：GatewayState 单一状态源

新增 `core/GatewayState.java`：

```
STOPPED_BY_USER   用户主动停的，保活不许拉
STOPPED_IDLE      没在跑但不是用户停的，保活应该拉
STARTING          正在绑端口
RUNNING           在服务
FAILED            上次启动失败，等重试
```

关键设计：

- **只有 `Gateway` 能写**（`start()` / `stop()` / `stopByUser()` 内部改），其它全部只读
- `Gateway.isRunning()` **保留**，内部改成 `state.isServing()` —— 20 余处读取点一行没改
- 非法组合在类型层面消失：不可能出现 `userStopped=true` 且 `isRunning()=true`
  （4.30.19 真发生过）
- `Gateway.stopByUser()` 与 `Gateway.stop()` 分开，意图由状态表达，不再需要
  `Config.userStopped` 这个第三方布尔

状态转移现在有日志可查：

```
I CPA: 网关状态：STOPPED_IDLE → STARTING
I CPA: 网关状态：STARTING → RUNNING
I CPA: 网关状态：RUNNING → STOPPED_BY_USER
```

## 改动二：LocalReason 枚举替代中文反解

新增 `core/LocalReason.java`（BUSY / QUOTA / GATEWAY_DOWN / CIRCUIT_OPEN / COOLDOWN /
PROBE_TIMEOUT / OTHER）。

`Throttled` 增加 `local` 字段（null 表示与本地无关），保留原有两个构造器做兼容。

`Detect` 新增 `isTransient(Throwable)`：**优先读枚举**，读不到再退回文本匹配。

`Server` 的两个抛出处改为带枚举：

```java
// 本地限额
throw new Throttled(429, ..., 0L, "local", LocalReason.QUOTA, blocked);
// 并发满（顺带修正：原先 kind 写成 "rate"，那是在说是上游限流，其实是本地拦的）
throw new Throttled(429, 5000L, 0L, "local", LocalReason.BUSY, "并发已满");
```

`Detect.isTransient(String)` 里那段中文字面量**保留为兜底**（给异常已摊平成字符串的
调用点用，典型是 `probeWithTimeout` 超时），但注明定位已降级，不再是主路径。

## 改动三：磁贴与指示灯

**磁贴**（`GatewayTileService`）原来：

```java
boolean running = Gateway.isRunning();
svc.setAction(running ? ACTION_STOP : ACTION_START);
```

这和 4.30.19 主界面那个 bug **是同一个形状**，只是还没被踩到：保活任务若刚好抢先拉起，
用户点磁贴想"启动"、实际发出的是停止。改为按状态语义判断：
`wantStart = !Gateway.state().isAlive()`。

**指示灯**（`dotFor`）原来靠 `s.contains("运行中")` 从中文文案反解颜色。改为优先比对
`GatewayState.label()`；文本分支只保留给"重启失败：xxx"这类临时消息（它们确实
不对应某个稳定状态）。

**通知文案** `runningText()` 改为 `Gateway.state().label()`，和磁贴、指示灯同一个词源。

## 改动四：自检加固（防止回退）

`Selftest` 新增两项共 6 条断言：

- **状态机语义**：`STOPPED_BY_USER` 不许自动拉起、`STOPPED_IDLE`/`FAILED` 必须能、
  `RUNNING`/`STARTING` 不许被拉起、只有 `RUNNING` 算 isServing、每个状态都有文案
- **本地原因枚举**：用**故意与旧文案不同**的文字（"当前并发已到上限，请稍候"）
  验证判断走枚举而非字面量；反例验证没有 `local` 标记的异常不会被误判成暂时失败

如果有人把 `Throttled.local` 删掉、只剩中文匹配，自检会失败。

## 真机验证

| 项目 | 结果 |
|---|---|
| 编译 | javac 零错误，BUILD_OK |
| 安装 | 4.30.22 / 605 覆盖成功 |
| **自检** | **19 项全部通过**（含新增的状态机与 LocalReason 断言）|
| 启动 | `STOPPED_IDLE → STARTING → RUNNING`，health=200 |
| 用户停止 | `RUNNING → STOPPED_BY_USER`，health=000 |
| 用户停止后触发保活 | **不拉起**（job 已被 cancel，符合预期）|
| 进程被杀后 | 新进程 `STOPPED_IDLE`，强制跑保活 job 4711 → 正常拉起 |
| 顺序 stop→start 三轮 | 每轮 health=200 |
| 真实请求 | `deepseek-v4.1-flash` 正常返回 |

## 教训

前三次修复分别补了一个条件：4.30.18 补 accept 线程退出、4.30.19 补 userStopped、
4.30.20 补周期刷新。每一次都对，但每一次都在**同一个结构缺陷上打补丁**。

判据：如果同一个概念需要在 N 个地方各写一遍判断，那不是 N 个 bug 的风险，
而是"这个概念还没有单一归属"的信号。把它收成一个状态、其余全部只读，
后续所有相关 bug 的种类会整体消失，而不是逐个消失。
