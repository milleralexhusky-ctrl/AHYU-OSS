# AHYU 4.30.18 — 网关「不断重连」根因修复

版本：4.30.18 / versionCode 601
日期：2026-10-18

## 现象

客户端一直重连，服务端没有任何响应。真机取证：

- `com.ahyu` 进程存活（CPU 时间仅 0:01，刚起来或反复被重建）
- 端口 8321 **没有任何 LISTEN socket**
- 线程表里 `cpa-accept` 存在，但该进程的 11 个 socket fd 里没有一个对得上 `/proc/net/tcp`
- 换句话说：**线程活着、监听没了**——客户端能感知到"端口不通/连上无响应"，于是无限重连

## 四个根因（都在这一条链上）

### 1. `Server.acceptLoop()` 在 `socket == null` 上空转（`core/Server.java`）

```java
final Socket s = socket.accept();     // 每轮从字段读
```

`stop()` 会把 `socket` 置 null。之后这个线程只要还醒着，每轮都抛 NPE，被
`catch (Throwable e)` 吞掉，又因为 `running` 已是 false 连日志都不打，`sleep(120)` 继续转。
这就是"线程活着但没有监听"的假活来源。

**修法**：先取局部 `ServerSocket sock = socket`，`null`/已关闭时直接 `return` 退出；
catch 里 `if (!running) return`，不再空转。

### 2. `Server.start()` bind 失败留下半成品（`core/Server.java`）

原来 `socket = new ServerSocket()` 后直接 `bind()`。一旦 bind 抛异常（端口占用/权限），
这个**未绑定**的 ServerSocket 留在字段上，`port()` 返回 -1、`healthy()` 因 `!running` 为 false。
看门狗把启动记成失败，客户端继续重连，日志里只剩一条 bind 异常。

**修法**：局部变量建好、绑好，全部成功后才发布到字段；失败时 close 局部对象再抛。

### 3. `GatewayService.onDestroy()` 无条件杀掉网关（`GatewayService.java`）★ 最关键

```java
instance = null;      // 可能抹掉**新实例**的登记
Gateway.stop();       // 把**新实例**刚绑好的监听关掉
```

`onStartCommand` 每次都会 `instance = this`，而旧实例的 `onDestroy()` 常常在新实例
已经建好之后才被系统调用（划掉任务、stopSelf 与 START 抢占、ROM 延迟回收都会这样）。
于是形成死循环：

1. `onTaskRemoved()` → 拉起 `ACTION_START` 新实例
2. 旧实例 `onDestroy()` → `Gateway.stop()` → 杀掉新实例刚绑的 server
3. 新实例看门狗 45 秒后判 unhealthy → 重启
4. 回到 2

`SettingsActivity` 注释里描述的"START 处理完之后旧实例的 onDestroy 把新 server 干掉"
说的正是这个，但那条路径只做了一次临时规避。

**修法**：只有 `instance == this`（自己仍是当前接管者）时才清 `instance`、才
`Gateway.flushPersist() + Gateway.stop()`；否则只释放自己的 `wakeLock`/`watching`，
"旧服务实例退出（已有新实例接管，不动网关）"。

### 4. `Gateway.stop()` 先停实例后摘字段（`core/Gateway.java`）

原来 `s.stop()` 在前、`server = null` 在后。中间那段窗口里 `server` 仍指向一个
已关监听的实例，`selfHealthy()` 会去问它、它答 false，看门狗再记一次失败、再触发重启；
而 `MainActivity.toggleGateway()` 那条路径还会单独再调一次 `Gateway.stop()`，两次 stop 相撞。

**修法**：先 `server = null` 再 `s.stop()`，任何并发看到的都是"没有网关"。

### 5. `MainActivity.toggleGateway()` 停止动作做了两遍

`startService(ACTION_STOP)` 之后又紧跟一句 `Gateway.stop()`；前者内部已经会
`Gateway.stop() + stopForeground + stopSelf`。同一件事被做两遍，第二次撞进已被清空的
静态字段上，在"正在重启"窗口里还会和看门狗抢锁。

**修法**：只发 Intent，由 Service 自己停自己。

## 附带改动

`Server.stop()` 现在有界等待 accept 线程退场（`join(500)`），避免"先停后起"时留下两个
`cpa-accept` 线程并存的窗口（两边都能 accept，谁抢到连接不确定，出问题极难复现）。
超时只记一条 warn，不阻塞。

## 真机验证（全部通过）

| 项目 | 结果 |
|---|---|
| 编译 | javac 零错误，BUILD_OK |
| 新字符串进 DEX | "旧服务实例退出（已有新实例接管，不动网关）"、"accept 线程未在 500ms 内退出" 均命中 |
| 安装 | 4.30.18 / 601 覆盖成功 |
| 启动后端口 | 8321 LISTEN 确认（修前完全没有） |
| `/health` | 200，完整 provider 列表 |
| `/v1/models` | 200，模型元数据正常 |
| 顺序 stop→start 三轮 | 每轮 health=200 |
| **并发连发 stop+start 五次** | health=200，**`cpa-accept` 线程数 = 1**（修前会留空转线程） |
| 模拟旧 onDestroy 场景 | 重启后 health=200，网关存活 |
| 跨两个看门狗周期（100 秒） | health 恒 200，PID 恒定，**零误重启** |
| 真实对话请求 | `deepseek-v4.1-flash` 正常返回"收到" |

## 备份

改动前四个文件已备份到 `/data/local/tmp/eta/bak/*.pre-reconnect`：

- `Server.java.pre-reconnect`
- `Gateway.java.pre-reconnect`
- `GatewayService.java.pre-reconnect`
- `MainActivity.java.pre-reconnect`

## 教训

"线程还活着"从来不是健康判据。`healthy()` 早就算对了（检查 socket != null && isBound），
但 `acceptLoop` 自己会在 socket 置空后空转，等于制造了一个 healthy() 能看见、
却没人处理的状态。**产生异常状态的代码和检测异常状态的代码必须一起改**，
只做检测不改产生源，就变成了"每 45 秒发现一次、每 5 分钟重启一次"的慢性病。
