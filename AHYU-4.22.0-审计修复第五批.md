# AHYU 4.22.0 —— 审计问题修复（第五批）

四条 [中]，逐条对源码核过，条条属实。

## ① Upstreams：Anthropic 流内 error 事件没做分类

### 位置与根因

`Upstreams` 的 Anthropic SSE 解析里：

```java
} else if ("error".equals(type)) {
    JSONObject err = ev.optJSONObject("error");
    throw new IllegalStateException("Anthropic 错误：" + ...);
}
```

裸抛 → 被上层记成**普通失败** → 3 次进熔断。

**与 4.21.5 修过的"200 错误体误分类"是同一个坑**：
Kimi / Claude 这类通道在上游吃紧时会在**流中途**回 error 事件，
mid-stream 限流被当故障记，健康账号被误杀。

### 修法

Anthropic 的 `error.type` 是**结构化**的，比文本匹配可靠，优先用它：

```java
static String throttleKindFromAnthropicType(String type) {
    if (t.contains("quota") || t.contains("billing") || t.contains("payment")) return "quota";
    if (t.contains("rate_limit") || t.contains("overloaded")
            || t.contains("too_many") || t.contains("capacity")) return "rate";
    return null;
}
```

error 事件处理改为：**先看结构化 type，再退回文本匹配**（复用 4.21.5 的
`throttleKindFromBody`），命中就抛 `Throttled`（只退避不计失败），否则维持原行为。

## ② GatewayService.onTaskRemoved 裸调 startForegroundService

### 位置与根因

```java
public void onTaskRemoved(Intent rootIntent) {
    if (Gateway.config().keepAlive) {
        Intent restart = ...;
        if (Build.VERSION.SDK_INT >= O) startForegroundService(restart);   // ← 无 try/catch
        else startService(restart);
    }
    ...
}
```

**这是保活路径上唯一没有兜底的一处。**
而"用户从最近任务划掉"这个场景下，Android 12+ 拒绝后台起前台服务是常态；
异常从这里冒出去会直接打到系统 —— **表现为进程崩溃，比"没保住"更糟**。

### 修法

包 `try/catch`，捕获后降级到那个一次性加急任务（`schedule` + `scheduleExpeditedPull`），
与 BootReceiver 的兜底策略一致。

## ③ 电池白名单：后台 startActivity 被静默拦掉

### 位置与根因

```java
private void ensureBatteryWhitelist() {
    ...
    startActivity(it);          // ← 无论服务在前台还是后台
    } catch (Throwable t) { Log.w("请求电池白名单失败：" + t); }
}
```

Android 10+ 对**后台进程启动 Activity** 有硬限制。而开机自启、job 拉起、
任务被移除后重启这些场景本服务都在后台 → 弹框被系统静默拦掉 →
异常被 catch 吃掉 → **用户永远等不到那个授权框**。

### 修法（分两条路）

- **判断服务是否在前台**（`ActivityManager.getMyMemoryState().importance`）
- **前台** → 直接弹系统授权页
- **后台** → **不弹**，改发一条可点的通知（`BATTERY_NOTIF_ID = 8323`，
  与账号告警 8322 分开），点击回到 SettingsActivity 引导手动授权

拿不准时按后台处理（后台路线只会少弹一次，不会崩）。

## ④ 凭据页没有 FLAG_SECURE

### 位置与根因

全工程的 `FLAG_SECURE` **只有 `Ui.secure(Activity)` 一处**，用在 3 个页面上：

```
WebLoginActivity:132    Ui.secure(this);
SettingsActivity:175    Ui.secure(this);
LoginPageBase:71        Ui.secure(this);
```

而 `TokenAdd`（填令牌）和 `ProviderEditor`（编辑账号凭据）**都是 Dialog** ——
**Dialog 有自己的 Window，Activity 上设的 FLAG_SECURE 管不到它**，
所以凭据会留在"最近任务"的缩略图里。

### 修法

两个凭据对话框**都走 `Ui.dialog()` 这个工厂**，所以在工厂里统一加，一处覆盖两处：

```java
d.setContentView(box);
try {
    d.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE);
} catch (Throwable ignored) {
}
```

（核对确认：`TokenAdd:102` 和 `ProviderEditor:264` 都是 `Ui.dialog(...)`，
没有绕过工厂自己 new Dialog 的地方。顺带把 4.21.9 新加的
LoginWebView 外部协议确认框也一并覆盖了。）

## 实测与验证

### 反编译核对（jadx，比 grep 可靠）

`FLAG_SECURE` 是常量，编译时内联成 `8192`，在 dex 里没有字符串，
所以用 jadx 反编译验证：

```java
// Ui.java:312  原有的（Activity 用）
static void secure(Activity activity) { activity.getWindow().setFlags(8192, 8192); }
// Ui.java:805  本批新增的（Dialog 用）
    dialog.getWindow().setFlags(8192, 8192);
```

其余三条同样用 jadx 确认：

| # | 验证点 | 结果 |
|---|---|---|
| ① | `throttleKindFromAnthropicType` | 481 行定义、930 行调用 ✅ |
| ② | `onTaskRemoved` 内 `try/catch` | 421 行 ✅ |
| ③ | `notifyBatteryPrompt` | 120 行调用、126 行定义 ✅ |
| ④ | `getWindow().setFlags(8192, 8192)` | Ui.java:805 ✅ |

### 真机功能回归

| 项 | 结果 |
|---|---|
| 构建 | BUILD_OK |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 531 / versionName 4.22.0 |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |
| 基础对话 | OVH/gpt-oss-20b → 200 |
| 三协议 auto | Gemini 200 / Anthropic 200（行为仍一致） |

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 531 / 4.22.0）
- 副本 `/sdcard/Download/cpa/AHYU-4.22.0-com.ahyu.apk`

## 未实测 / 遗留

- ④ 的"缩略图里看不到凭据"**没法用自动化验证**（要看最近任务的实际快照），
  已用反编译确证 flag 设上去了
- ③ 的"后台发通知"路径未实测（需要构造后台启动服务的场景）
- ② 的崩溃场景未复现（原问题是"边缘情况下崩"，构造成本高）
- **保活那条尾巴仍待真机确认**（4.21.7 遗留）：重启手机或等一次真实 15 分钟周期，
  logcat 里找 `保活任务：检测到网关未运行，重新拉起` 且没有
  `ForegroundServiceStartNotAllowedException`
- 审计剩余（影响面小）：Limits 非原子、Secret 回退明文、流内错误缺 [DONE]、
  剪贴板、明文输入框、端口 hint
