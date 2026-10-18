# AHYU 4.21.8 —— 审计问题修复（第三批）

按约定做三件：WbTaskChores 重入 guard、save() 400ms 换显式等待、ProviderEditor 静态字段真修。

## ① WbTaskChores 内部重入 guard

### 根因（比"外层没保护"更具体）

外层 `/v1/tasks/run` 有 `TASKS_RUNNING`，但它的 key 是**传入的 provider 名**：

```java
final String runKey = only.isEmpty() ? "*" : only;
if (!TASKS_RUNNING.add(runKey)) { ... "already running" ... }
```

而 `runTasksInner(only)` 实际是**遍历所有账号**（`only` 只是过滤条件，不是限定）：

```java
for (Config.Provider p : new ArrayList<>(config.providers)) {
    if (!only.isEmpty() && !only.equals(p.name)) continue;   // 过滤，不是限定
```

于是 "全跑"（key=`*`）与 "只跑 A"（key=`A`）用的是两个不同的 key、**能同时进来**，
而"全跑"那次也会处理 A → **同一账号被并发跑两遍**，上游看到的就是行为重复上报（4.19.4 审计提过"重复上报"）。

### 修法

把保护**下沉到账号级**（provider id 为 key）：

```java
private static final java.util.Set<String> RUNNING = ConcurrentHashMap.newKeySet();

public static JSONObject run(Config.Provider p) throws Exception {
    final String key = p.id == null || p.id.isEmpty() ? p.name : p.id;
    if (!RUNNING.add(key)) {
        Log.w("WbTaskChores：账号 " + p.name + " 已在跑任务，跳过本次");
        out.put("skipped", "already running");
        return out;
    }
    try { return runLocked(p, out); } finally { RUNNING.remove(key); }
}
```

原 `run` 主体改为私有 `runLocked`，保证 `finally` 一定释放。

### 验证

**最小复现**（忠实复刻源码逻辑，`/tmp/repro517/Repro.java`）：

```
=== 同一账号并发调用两次（外层 key 不同也能进）===
  调用1: OK:ran
  调用2: SKIPPED:already running
  实际发请求次数: 1（应为 1）

=== 不同账号并发（应各自都能跑）===
  账号1: OK:ran    账号2: OK:ran
  实际发请求次数: 2（应为 2）

=== 串行调用（跑完应能再跑）===
  第一次: OK:ran   第二次: OK:ran
  实际发请求次数: 2（应为 2）

RESULT: PASS
```

真机端另外验了外层 guard：

```
连发两次 POST /v1/tasks/run
第一次 → {"ok":true,"started":true}
第二次 → {"ok":false,"error":"already running"}
```

## ② save() 的 400ms 常量 → 显式等待

### 根因

`SettingsActivity` 重启网关时硬睡一个常量：

```java
Thread.sleep(400);   // 等旧实例的 onDestroy 走完
```

重负载下旧实例 onDestroy 拖过 400ms，旧实例不会回收干净；连点两次保存还会互相交错。

### 修法

`GatewayService.onDestroy()` 的第一行就是 `instance = null` —— 这是现成的、可靠的
"旧实例已销毁"信号。新增：

```java
public static boolean hasInstance() { return instance != null; }
```

`SettingsActivity` 改成轮询它，每 50ms 一次、最多 5 秒，超时也继续往下走
（不能永远卡在重启上）：

```java
for (int i = 0; i < 100; i++) {
    if (!GatewayService.hasInstance()) break;
    Thread.sleep(50);
}
```

## ③ ProviderEditor 静态字段真修

### 根因

三个静态字段都间接持有 Activity Context：

```java
private static EditText pendingCreds;
private static android.app.Dialog dialog;
private static Activity host;
```

而 `release()` 只把字段置 null，**既没摘 dismissListener，也没有任何 Activity 主动调它**——
唯一触发点是对话框自己的 `setOnDismissListener(d -> release())`，Activity 非正常销毁时不保证触发。

### 修法

1. `release()` 改成 `synchronized` 并补完整清场：
   **先置空字段 → 摘 listener → dismiss**（顺序有讲究：先置空避免 dismiss 回头重入）；
2. `MainActivity.onDestroy()` 里主动调 `ProviderEditor.release()`
   （只有 MainActivity 用这个编辑器，已核对）；
3. `handleResult` 里发现编辑框已不在时给明确提示，不再把凭据静默丢掉。

### 为什么不用 WeakReference

`pendingCreds` 必须在 **Activity 从授权页返回之后**（`handleResult`）仍然可用——
"去登录页 → 回来填凭据"这条流程要求它活着，用 WeakReference 会被提前回收、流程断裂。

而跳 `startActivityForResult` 时宿主的 Activity **不会** onDestroy，
所以在 `onDestroy` 里释放不会误伤这条流程。

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | BUILD_OK |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 529 / versionName 4.21.8 |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |
| 外层 tasks guard | 连发两次 → started / already running |
| 内层重入逻辑 | 最小复现 PASS（三场景） |

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 529 / 4.21.8）
- 副本 `/sdcard/Download/cpa/AHYU-4.21.8-com.ahyu.apk`

## 未验证 / 遗留

- 内层 guard 的**真机端**并发验证不完整：本机只有 WorkBuddy 账号，
  任务在后台跑且日志未落到 logcat，没能抓到 `已在跑任务` 那一行。
  逻辑已用最小复现证过，代码已确认进包。
- ProviderEditor 的修复依赖"宿主 onDestroy 一定会被调用"，
  极端情况下（进程被直接 kill）静态字段随进程一起消失，无泄漏。
- 审计剩余项：LoginWebView 外部协议无确认拉起、电池白名单后台起 Activity、
  流内错误缺 [DONE]、?key= 传密钥、STATS 无淘汰、Limits 非原子、
  Doubao 二维码 token 前 8 位进日志、Secret 回退明文、剪贴板/明文输入框/端口 hint。
