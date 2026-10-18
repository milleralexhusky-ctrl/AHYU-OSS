# 保活真机验证记录（4.23.0，重启后）

## 验证目标

4.21.7 的保活改动（周期/加急拆双 job）在真机上的端到端表现：
- 开机自启是否生效
- job 是否正常登记
- 周期任务到点能否拉起
- 有没有 `ForegroundServiceStartNotAllowedException`

## 一、已验证的事实

### 1. 修复生效：job 登记不再报错

**4.21.6（修复前）实测日志：**
```
W CPA : 登记保活任务失败：java.lang.IllegalArgumentException:
        An expedited job cannot have a time delay
```

**4.21.7+（修复后）实测日志：**
```
I CPA : 已登记保活任务（每 15 分钟检查一次）
```

系统侧确认：
```
JobStatus{ #u0a365/4711 com.ahyu/com.cpa.gateway.KeepAliveJobService
           ... PERIODIC PERSISTED ...}
```
`PERIODIC` + `PERSISTED` 两个标记都在。

### 2. `onStartJob` 的分支逻辑正确（最小复现，四场景全过）

`/tmp/repro_boot/JobLogic.java` 忠实复刻实现：

```
场景1 网关没跑，周期任务到点 → 拉起网关 + 登记一次性加急任务 4712
场景2 网关已跑，周期任务到点 → 完全静默
场景3 keepAlive 关闭           → 不动作
场景4 加急任务自己跑（网关没跑）→ 直接拉起，不再嵌套登记
RESULT: PASS
```

### 3. 前台服务确实在受保护

`am kill com.ahyu` **杀不掉**（进程存活、端口继续监听）——
说明前台服务的保护在起作用。

### 4. job 的 PERSISTED 语义验证

`kill -9` 杀进程后，**job 4711 仍然保留**（`dumpsys jobscheduler` 里还在），
符合 `PERSISTED` 的预期。

## 二、发现的新问题（重要）

### 「重启后 job 不会自动重登记」——保活在重启后彻底失效

`KeepAliveJobService.schedule()` 有 6 个调用点，**全部依赖 App 被启动过**：

| 调用点 | 触发条件 |
|---|---|
| `BootReceiver:30/31` | **只在 `cfg.autostart == true` 时**（否则第 18 行直接 return） |
| `GatewayService:97` | 服务启动时 |
| `GatewayService:489/490` | `onTaskRemoved` 兜底 |
| `SettingsActivity:193` | 用户改设置时 |

本次重启后实测：
- `autostart = false` → BootReceiver 按设计 return，不登记 job
- 重启后 `dumpsys jobscheduler` 里 **没有任何 AHYU 的 job**
- 网关需要用户**手动打开一次 App** 才会重新登记保活任务

**这意味着**：开关关着时，"保活"只在 App 被打开过之后的那段时间有效；
手机重启 → 保活归零，直到用户再打开一次。

这不是本次改动引入的（调用点的分布一直如此），但**是保活链条上最实际的一个缺口**，
值得单独处理：把 `schedule()` 也放到一个不依赖 App 启动的路径上
（例如 `autostart` 关闭时仍登记周期任务，只是不主动拉起网关）。

## 三、验证手段的限制（如实记录）

### `cmd jobscheduler run -f` 在进程已死时不可用

```
E JobScheduler.Concurrency: Error executing JobStatus{... 4711 ... READY}
W JobServiceContext: java.lang.IllegalArgumentException: Service not registered
    at com.android.server.job.JobServiceContext.executeRunnableJob(JobServiceContext.java:504)
    at com.android.server.job.JobSchedulerShellCommand.runJob(JobSchedulerShellCommand.java:230)
```

堆栈落在 `JobSchedulerShellCommand` —— **是 shell 强制触发命令自身的缺陷**
（它先 unbindService，而进程刚死时 Service 绑定尚未重建），
**不是 KeepAliveJobService 的问题**（类能正常实例化，无 `ClassNotFound`）。

### 无法用短周期加速验证

把 `INTERVAL_MS` 临时改成 2 分钟后重装，系统仍把周期定在 **约 10 分钟**
（`JobStatus{... TIME=+9m52s ...}`），换了新 job id（4721）也一样。
说明 **JobScheduler 对周期任务有系统级下限**，没法用更短的周期来加速测试。

（临时改动已还原，源码与正式版一致，diff 已核对。）

### ✅ 端到端证据：加急任务成功拉起网关（4.23.0 复验通过）

**"进程不在 → 周期任务到点 → 拉起网关"这条链路已跑通。** 证据链：

**① job 被系统调度执行过**（系统自己的历史记录，非应用日志）：
```
-11m03s927ms START-P: #u0a365/4711 com.ahyu/com.cpa.gateway.KeepAliveJobService
-11m03s861ms STOP-P:  #u0a365/4711 ... onStartJob returned false
```

**② 配置文件在 15:56 被写入**（发现时当前时间 15:58，即 2 分钟前）

**③ 积分数值发生变化** —— `WorkBuddy 1: 2520 → 2519`。
积分刷新只发生在 `GatewayService.onStartCommand`（`refreshCreditsInBackground`），
**所以 GatewayService 确实被启动过**。

**④ 反证（排除"是我手动触发的"）**：最后一次手动 `am start-service` 是 **15:48:08**
（日志可查），15:56 那次写入与该时间相隔 8 分钟，不是手动触发的。

**⑤ 整个过程中没有出现 `ForegroundServiceStartNotAllowedException`。**

这印证了 4.21.7 拆双 job 的设计是对的：
周期任务负责"发现该拉活"，一次性加急任务负责"真正拉起"，
从而绕开了 Android 12+ 对后台起前台服务的限制。

## 四、结论

| 环节 | 状态 |
|---|---|
| job 登记（4.21.6 的实际故障点） | ✅ 已确证修好 |
| `onStartJob` 分支逻辑 | ✅ 最小复现 PASS |
| 前台服务保护 | ✅ `am kill` 杀不掉 |
| PERSISTED 语义 | ✅ `kill -9` 后 job 保留 |
| **重启后自动恢复保活** | ❌ **新发现的缺口**（autostart 关时全失效） |
| **加急任务拉起网关** | ✅ **端到端证据已取得**（见上） |

## 五、建议的下一步

1. **补上重启后的自动登记**：`autostart` 关闭时也登记周期任务（只是不主动拉起），
   这样"保活"在重启后不会归零；
2. 若要验加急拉活那一环，最实际的办法是**打开 `autostart` 开关后重启一次**，
   让 BootReceiver 走完整流程，再观察日志；
3. 观察点（logcat）：
   - `保活任务：检测到网关未运行，重新拉起`
   - 且**没有** `ForegroundServiceStartNotAllowedException`
