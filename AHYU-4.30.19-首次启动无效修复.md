# AHYU 4.30.19 — 「第一次按启动无效、按第二次才成功」根因修复

版本：4.30.19 / versionCode 602
日期：2026-10-18

## 现象

打开 App 后直接按「启动网关」，第一次无效（按钮没反应/网关没起来），按第二次才成功。

## 取证过程（真机 logcat 抓到的决定性证据）

```
13:44:27.757 W CPA: 保活任务：检测到网关未运行，重新拉起
13:44:27.758 I ActivityManager: Background started FGS: Allowed ... act=com.cpa.gateway.START
13:44:27.783 I CPA: gateway listening on 127.0.0.1:8321
13:44:27.783 I CPA: service started, port 8321
```

打开 App 的**同一秒**，`KeepAliveJobService` 就跑起来了。原因：JobScheduler 里有一个
积压的加急任务（`scheduleExpeditedPull` 登记的 PULL_JOB_ID=4712），App 刚把进程带回
前台、系统立刻放行它执行，于是保活任务看到 `!Gateway.isRunning()` 就自动拉起网关。

## 完整故障链

1. 用户打开 App → `refreshStatus()` 执行，那时网关确实没跑 → 按钮显示「启动网关」
2. **同一秒**，积压的保活任务执行 → 自动把网关拉起来（端口 8321 开始监听）
3. 用户按「启动网关」→ `toggleGateway()` 里 `Gateway.isRunning()` **已经是 true**
   → 走进**停止**分支 → 网关被关掉
4. 用户看到"没起来"，再按一次 → 这次真的启动 → "第二次才好"

验证这一步：网关运行时执行停止分支，`/health` 从 200 变成 000，正是用户看到的表现。

**第二层问题**：`Config.keepAlive` 默认 true，且保活判据只有 `!isRunning()`，
分不清"用户主动停止"和"意外掉线"。所以用户点停止后，下次巡检又会把它拉起来
（表现为"停止无效"）。

## 改动

### 1. `Config` 新增 `userStopped` 字段（`core/Config.java`）

记录"用户**主动**停止网关"的意图。默认 false，参与 save/load。

```java
public boolean userStopped = false;
```

save：`o.put("userStopped", userStopped);`
load：`c.userStopped = o.optBoolean("userStopped", false);`

### 2. 保活任务尊重用户意图（`KeepAliveJobService.java`）

```java
// 原来
boolean shouldRun = cfg.keepAlive && !Gateway.isRunning();
// 现在
boolean shouldRun = cfg.keepAlive && !cfg.userStopped && !Gateway.isRunning();
```

用户主动停过就跳过并记日志：「保活任务：用户已主动停止网关，跳过自动拉起」。

### 3. 标记的写入与清除（`GatewayService.onStartCommand`）

- `ACTION_STOP` 分支：`userStopped = true` + `save()`（放在 `Gateway.stop()` 之后）
- `ACTION_START` 分支：若 `userStopped` 为 true 则清成 false + `save()`
  （放在 `Gateway.init(this)` **之后**——init 可能刚从磁盘重读配置，先清会被覆盖）

### 4. 按钮以「界面上写的字」判断用户意图（`MainActivity.toggleGateway`）

不再用 `Gateway.isRunning()` 决定走启动还是停止——那是"此刻的真实状态"，
可能已经被后台悄悄改过。改成读按钮文案：

```java
final String label = String.valueOf(primaryButton.getText());
final boolean uiSaysRunning = label.contains("停止");
final boolean actuallyRunning = Gateway.isRunning();

if (uiSaysRunning == actuallyRunning) {
    // 认知一致：执行对应动作
} else {
    // 认知不一致（后台替用户把事做了）：不执行任何破坏性动作，只刷成真值
    refreshStatus();
}
```

这样即使保活抢跑，用户按「启动网关」也不会变成"停止"，而是直接告诉他"网关已在运行"。

## 真机验证（全部通过）

| 项目 | 结果 |
|---|---|
| 编译 | javac 零错误，BUILD_OK |
| 新字符串进 DEX | `userStopped`、`保活任务：用户已主动停止网关，跳过自动拉起`、`网关已在运行` 均命中 |
| 安装 | 4.30.19 / 602 覆盖成功 |
| 主动停止 | health 200 → 000，`"userStopped": true` 正确写盘 |
| **打开 App 15 秒观察** | **网关全程无监听，不再被保活抢先拉起** ✓ |
| 第一次按启动 | health=200 **一次成功**，`userStopped` 清回 false |
| 停止→启动状态流转 | true / false 正确切换 |
| 回归：并发 stop/start 五次 | health=200，`cpa-accept` 数=1 |
| 回归：真实请求 | `deepseek-v4.1-flash` 正常返回 |

## 教训

一个布尔标志（`isRunning()`）被两个不同角色读取：用户意图的**表达**，和系统状态的**事实**。
当后台可以在用户不知情时改动"事实"，而界面还停留在旧的"表达"上，两者一错位，
用户的操作就会被翻转成相反的意思。修法是**把两者分开**：意图看界面文案，
事实看运行时状态，不一致时以"不破坏"为原则对齐。
