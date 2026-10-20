# AHYU 4.38.0：内存优化（后台网关形态）

versionCode 630。

## 起因

AHYU 大多数时间是**后台网关**：开机自启、保活 JobService 拉起、通知栏常驻。
这种形态下根本没有界面，但旧实现会在 `Application.attachBaseContext` 里预热一张
1/2 屏的底纹位图（1280×2772 上约 3.5MB），从进程启动一直留到进程结束。

先量了一下真实分布（`dumpsys meminfo`，同一台设备）：

| 状态 | PSS | Java 堆 | 底纹位图 | GL mtrack |
| --- | --- | --- | --- | --- |
| 无界面（旧） | 30.4MB | ~3.5MB | 8.76MB* | 0 |
| 无界面（新） | 27.2MB | ~3.4MB | 5.29MB* | 0 |
| 打开界面 | ~61–66MB | ~3.4MB | 8.7MB* | ~18MB |

\* `dumpsys meminfo` 的 Bitmap (malloced) 总数，包含系统/ROM 注入的位图；
两版相差的 3.5MB 正好是底纹那张图。

结论：**Java 堆不是问题**（只有 ~3.4MB）；占用大头是界面窗口的 GPU/渲染缓冲，
其次是底纹位图。后台形态唯一值得省、也能安全省的是后者。

## 改动

1. **不再在进程启动时预热底纹**
   `CpaApp.attachBaseContext/onCreate` 里的 `BackdropTex.startWarm()` 删除。
   底纹改为界面第一次 `onCreate` 时由 `Ui.darkSystemBars()` 的 `warmSync()` 画
   （本机实测 6–11ms，首帧无可感延迟）。无界面进程从此不再分配这张位图。

2. **界面隐藏 / 内存压力时释放底纹**
   `CpaApp` 注册 `ActivityLifecycleCallbacks`，用"STARTED Activity 数归零"判定
   整个 UI 已隐藏（不依赖 `onTrimMemory(UI_HIDDEN)`，部分 ROM 不按时送达），
   此时 `BackdropTex.release()` 回收位图；`onTrimMemory` 与 `onLowMemory` 也会释放。
   页面之间跳转不会误释放（旧页 stop 前新页已 start）。
   下次打开界面重画，约 6–11ms。

3. **窗口隐藏时停止背景动画**
   `DynamicBackdrop` 增加 `onWindowVisibilityChanged`，并在 `start()` 里检查
   attach/窗口/View 三层可见性。Activity 进入 STOPPED 后不再投递 8fps 的 tick。

4. **删除死代码**
   `BackdropTex` 里从未被调用的 `startWarm/prewarm/watch/notifyReady` 与
   `cacheFile`（注释声称有磁盘缓存，实际没有任何读写）一起删除，避免下一个人
   误以为存在磁盘缓存而依赖它。

## 验证

- 同一台设备、同一进程形态（`force-stop` 后只启动 `GatewayService`），
  旧包 → 新包背靠背测量：
  - PSS：30.4MB → **27.2MB**（−3.2MB，约 −10.5%）
  - Bitmap (malloced)：8.76MB → **5.29MB**（−3.47MB）
- 界面启动：底纹按配色现画 6–11ms，启动打点"主题+底纹+建界面"38ms（与改前同级）。
- 界面隐藏：日志出现「内存回收：界面已隐藏，释放底纹位图」。
- 启动自检 22/22 通过；签名与原版一致，可覆盖升级。

测量命令（可复现）：

```bash
priv 'am force-stop com.ahyu'
priv 'am start-foreground-service -n com.ahyu/com.cpa.gateway.GatewayService'
sleep 8
priv 'dumpsys meminfo com.ahyu' | grep -E 'TOTAL PSS|Bitmap \(malloced\)'
```

## 未做 / 取舍

- **界面态 ~61MB 的大头是系统管理的窗口 GPU 缓冲（GL mtrack ~18MB）与 HWUI
  渲染缓冲**，Activity 处于 STOPPED 时系统仍会保留。应用层强释放只有两条路：
  ① 隐藏时 `finish()` 掉 UI（可省 ~30MB，但会丢返回栈、影响浏览器登录流程）；
  ② 把窗口改成 RGB_565（可省约一半 GPU 缓冲，但渐变/玻璃会有色带）。
  两者都会改变用户可感知的行为或观感，本版不做。
- 无界面形态里剩余的 5.3MB Bitmap 主要来自系统/ROM 注入与通知 RemoteViews，
  不是应用自己创建的底纹；继续压需要动通知样式，收益小、风险高。
- 线程数 47 中应用自己创建的约 10 条（连接/IO/心跳/竞速），其余是 ART、HWUI
  与 MIUI 注入；未做线程栈裁剪，因为栈的实际 RSS 只有约 0.5MB。
