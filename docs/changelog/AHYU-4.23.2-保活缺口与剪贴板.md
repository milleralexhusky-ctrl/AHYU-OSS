# AHYU 4.23.2 —— 保活缺口修复 + 剪贴板时效

## 一、保活缺口：关掉「开机自启」会连带废掉「保活」

### 问题

两个开关的语义在设置页写得很清楚：

| 开关 | 文案 |
|---|---|
| `autostart` | **"开机自动启动"** |
| `keepAlive` | **"被系统杀掉后自动拉起"** |

但实测发现它们被耦合了。`BootReceiver` 里：

```java
Config cfg = Config.load(context);
if (!cfg.autostart) return;      // ← 直接返回，保活也不登记了
```

而 `KeepAliveJobService.schedule()` 的**全部调用点**都要求 App 被启动过：

| 调用点 | 触发条件 |
|---|---|
| `BootReceiver:30/31` | 只在 `autostart == true` 时 |
| `GatewayService:97` | 服务启动时 |
| `GatewayService:489/490` | `onTaskRemoved` 兜底 |
| `SettingsActivity:193` | 用户改设置时 |

**后果**：`autostart` 关着时，手机重启 → JobScheduler 里的任务全没了 →
**保活归零**，直到用户手动打开一次 App。

（本次重启后实测确认：重启 28 分钟后，`dumpsys jobscheduler` 里没有任何 AHYU 的 job，
网关需要手动拉起。）

### 判断：这不是"逻辑写错了"，而是"两个开关耦合了"

`autostart` 管的是"**开机那一刻是否主动拉起**"，
`keepAlive` 管的是"**之后要不要维持运行**"。两者语义不冲突 ——
关掉"开机自启"不应该连带废掉"被杀后自动拉起"。

### 修法

`autostart` 关着时，**仍登记保活任务**（只要 `keepAlive` 是开的）：

```java
if (!cfg.autostart) {
    if (cfg.keepAlive) {
        Log.i("未开启开机自启：仍登记保活任务（keepAlive 打开）");
        KeepAliveJobService.schedule(context);
    } else {
        Log.i("未开启开机自启且未开启保活：不做任何动作");
    }
    return;
}
```

job 到点后会走 `onStartJob` 里原有的 `shouldRun` 判定，行为与运行时一致。

### 实测（手动触发 BOOT_COMPLETED 验证新分支）

环境：`autostart = false`、`keepAlive = true`

```
I CPA : 未开启开机自启：仍登记保活任务（keepAlive 打开）
I CPA : 已登记保活任务（每 15 分钟检查一次）
```

**修复前**这一段会直接 return、什么都不做。**修复后**保活任务被正常登记。

## 二、剪贴板时效：复制后 60 秒自动清除

### 问题

`MainActivity.copyText()` 和 `ModelsActivity.copy()` 都是直接
`setPrimaryClip(...)`，之后**没有任何清除机制** ——
密钥/地址会留在剪贴板里，跟着剪贴板历史（以及某些输入法的云同步）
跑到用户看不见的地方。

### 修法（`Ui.copyWithAutoClear()`）

- **延时 60 秒清除**，不是立即清：立即清会让"复制了过会儿再粘贴"失败；
- **只清自己写进去的那一份**：拿 `ClipData` 的 label 比对（本应用用 `"cpa"`），
  用户中途复制了别的内容就**绝不碰**（否则会把用户自己的东西清掉，那更糟）；
- API 28+ 用 `clearPrimaryClip()`，更低版本写一个空 clip。

两处调用点统一改走这个入口。

### 关于 60 秒这个值

审计只提了"剪贴板残留"，没给时长。选 60 秒的理由：
足够完成"复制 → 切到别的应用 → 粘贴"，又不会长期驻留。
这个值如果要改，只动 `Ui.copyWithAutoClear()` 里的一处。

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | 分步构建（aapt2 compile/link → javac → d8 → 打包 → 签名） |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 534 / versionName 4.23.2 |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |
| 三协议回归 | OpenAI 200 / Gemini 200 / Anthropic 200 |
| **BootReceiver 新分支** | ✅ 日志确认"仍登记保活任务" |

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 534 / 4.23.2）
- 副本 `/sdcard/Download/cpa/AHYU-4.23.2-com.ahyu.apk`

## 遗留

- 剪贴板自动清除的**实际行为未做端到端实测**（需要复制后等 60 秒再读剪贴板验证）；
  代码路径已确认进包，且"只清自己那份"的判断逻辑与原有 label 一致
- 上一批遗留：`auto-race` 语义（设计问题）、`Secret` 回退明文（有意取舍）
- 保活链路已闭环（见《AHYU-保活真机验证记录.md》）
