# AHYU 4.30.20 — 状态感知与提示真实性

版本：4.30.20 / versionCode 603
日期：2026-10-18

## 出发点

4.30.19 修掉"第一次按启动无效"的**表现**（按钮按界面文案判断意图），但没解决它的**成因**：
界面显示的状态会长期停留在一个过时的快照上。这一版补上成因，并顺带修掉一个同类问题
——"把尝试当成成功"的提示。

## 问题一：MainActivity 没有任何周期性状态刷新

`refreshStatus()` 原有 8 个调用点，**全部是事件驱动**：
`onCreate`、`onResume`、点按钮后 700ms、从子页返回、账号编辑完成等。

但网关状态会在这些事件之间自己变化：

- 保活 `KeepAliveJobService` 在后台把网关拉起来（用户什么都没点）
- 看门狗判定假活后重启（45 秒周期）
- 用户在通知栏 / 快捷磁贴里点了停止
- 另一个入口（设置页保存）触发了重启

结果是界面长期显示过时状态。**这正是 4.30.19 "第一次按启动无效"的直接成因**：
界面写着「启动网关」，真实状态已经是运行中。

### 改法

在 `onResume` 启动、`onPause` / `onDestroy` 停止的 2 秒轮询：

```java
private static final long STATUS_POLL_MS = 2000L;
private final android.os.Handler pollHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());
private boolean polling;

private final Runnable pollTick = new Runnable() {
    @Override public void run() {
        if (!polling) return;
        try { refreshStatus(); }
        catch (Throwable t) { Log.w("状态轮询失败（不影响使用）：" + t); }
        if (polling) pollHandler.postDelayed(this, STATUS_POLL_MS);
    }
};
```

**为什么 2 秒一次可以接受**：`refreshStatus()` 自带 long 指纹比对，状态没变时
直接 `return`——不拼字符串、不动 View、不触发 requestLayout。绝大多数轮次成本接近零。

**为什么只在可见期跑**：`onPause` 立即 `removeCallbacks`，退到后台不耗电。
`onDestroy` 也调一次，避免 `postDelayed` 持有 Activity 造成泄漏。

## 问题二：`toggleGateway` 抢先报"已停止/已启动"

原来在 `startService(...)` 之后**立刻** `toast("已启动")`。但那只是"请求发出去了"：
网关可能 bind 失败、可能被 ROM 拦掉、也可能几秒后才就绪。用户先看到"已启动"、
再看状态还是「已停止」，比什么都不提示更糟。

### 改法

动作发出后不报结果，等 700ms 让状态落定，按**真实结果**说话：

```java
final boolean wantStop = actuallyRunning;
// ... 发出动作 ...
primaryButton.postDelayed(() -> {
    Ui.setLoading(primaryButton, false, null);
    refreshStatus();
    final boolean now = Gateway.isRunning();
    if (wantStop) {
        if (now) toast("停止未生效，网关仍在运行");
        else toast("已停止");
    } else {
        if (now) toast("已启动");
        else toast("启动未生效，请重试");
    }
}, 700);
```

## 真机验证

| 项目 | 结果 |
|---|---|
| 编译 | javac 零错误，BUILD_OK |
| 新字符串进 DEX | `启动未生效，请重试`、`停止未生效，网关仍在运行`、`状态轮询失败` 均命中 |
| 安装 | 4.30.20 / 603 覆盖成功 |
| 页面停留时后台改状态 | health 200→000 期间无崩溃、无轮询异常 |
| 反复开关 App 5 次 | 进程存活，无 FATAL，无泄漏迹象 |
| 顺序 stop→start 三轮 | 每轮 health=200、`userStopped:false` |
| 真实请求 | `deepseek-v4.1-flash` 正常返回 |

## 一个已知且可接受的行为

并发连发 5 组 stop+start 时，最终落点由系统调度决定，可能停在"已停止"。
这不是回归——真实用户点按钮有 `Ui.setLoading` 防止连点，不会产生这种序列。
顺序执行（真实场景）三轮全部 health=200。

## 教训（延续 4.30.19）

4.30.19 的教训是"意图与事实要分开"。这一版的教训是**事实需要持续观测**：
把两者分开之后，如果"事实"这一侧永远只在事件发生时读一次，它同样会过期。
状态面板的正确形态是"事件驱动 + 低频轮询兜底"，而不是二选一。
前提是刷新本身必须足够便宜（指纹比对），否则轮询就成了负担。
