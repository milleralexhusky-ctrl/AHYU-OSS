# AHYU 4.31.2 — 魔法阵闪烁 / 卡顿的三个真因（versionCode 609）

用户反馈："矩阵变化时会闪烁，有时候会卡顿。" 去读 `DynamicBackdrop` 的代码后，
找到**三个**具体的、可验证的成因（不是调参问题）。

## 一、闪烁：`LAYER_TYPE_HARDWARE` + 每帧 `drawColor`

原代码：

```java
setLayerType(LAYER_TYPE_HARDWARE, null);   // 构造函数
...
canvas.drawColor(fade(base, 1f));          // onDraw 每帧
```

这个 View 有独立的离屏硬件层（1280×2772 约 13MB）。`drawColor` 每帧覆盖整层，
然后重画几十条弦 —— 也就是说每帧都要重新录制 + 重新合成一整屏纹理。

真正的闪来自两件事叠加：
1. `tick` 里 `if (Perf.degraded())` 时**不 invalidate**，层里留的是旧内容；
2. 一旦窗口合成（下拉通知、状态栏动画、页面切换、输入法弹出）触发该层重整，
   就会先看到"旧内容被清掉 → 新一帧再画上去"这个中间态 —— 眼睛看到的就是**闪一下**。

**改法**：直接不用硬件层。这个 View 只有 8 fps，为它单独挂一个全屏离屏纹理
换来的是 13MB 内存 + 随时可能重整的层，完全不划算。普通绘制每帧自己画，
不存在"层里留旧内容"这种状态。

## 二、卡顿：M 跨整数时在 `onDraw` 里重建骨架，而且白建 5 层

原代码把**状态推进**放在 `onDraw` 里：

```java
double targetM = 3.0 + (DRIFT_N - 4) * busy;
complex += (targetM - complex) * 0.02;
int wantM = (int) Math.floor(complex);
if (layerPath == null || wantM != builtM) { builtM = wantM; layerPath = null; }
```

下一行 `ensureSkeleton()` 会同步重建 `MAX_LAYERS = 8` 条 Path，每条 `N` 是 240/200/150，
逐根 `moveTo`/`lineTo`。而 `onDraw` 里 `final int full = 3` —— **只画 3 层，
却建了 8 层**。每次 M 跨过整数，就有一帧要背着"重建 8 层"这个开销去合成。

**改法**（两条）：
- 新增 `advanceAndRebuild()`，把 `complex` 推进和骨架重建挪到 `tick` 里、绘制之外；
  `onDraw` 变成纯读 `complex`，不再推进任何状态。
- 新增 `VISIBLE_LAYERS = 3`，`ensureSkeleton()` 只建会画到的那 3 层（`full` 也改成引用它）。

实测：`ensureSkeleton` **1.8 ms/次**（3 层）；按旧逻辑建 8 层约 **4.8 ms/次**。
在 16.7ms 的帧预算里，这差距就是"卡一下"和"顺"的区别。

## 三、偶发顿挫：`postDelayed` 不对齐 VSYNC

`tick` 原来用 `postDelayed(this, next)`（125/200ms）。`postDelayed` 相对**上一次投递**计时，
会缓慢漂移，落到跟 VSYNC 无关的时刻，于是魔法阵的绘制经常和别的动画
（列表滑动、按钮果冻 `Jelly`）挤在同一帧。

**改法**：`postOnAnimationDelayed(this, next)`，对齐 Choreographer 的帧边界。

## 四、顺手修的：`UsageChart` 每帧分配 RectF

```java
RectF r = new RectF(...);   // onDraw 的 for 循环里，每根柱子一个
```
改成复用字段 `rect.set(...)`。滚动或面板刷新时不再持续产生小对象，GC 不抖。

## 五、验证（真机，4.31.2 / 609）

- 自检 `runAll()`：**19/19 通过**（回归无破坏）。
- `VISIBLE_LAYERS=3`、`MAX_LAYERS=8` 常量就位；`advanceAndRebuild` 方法存在。
- 强制重建一次：`buildLayer` 调用 **3 次**（改前 8 次）、`buildSunflower` 1 次。
- 形态推进正常：连调 3 次 `advanceAndRebuild`，`complex` 31.44 → 32.83，
  跨过 32 时 `ensureSkeleton` 恰好触发 **2 次**（第一次建、第二次数值跨整数重建）；
  静止时不重建。
- `ensureSkeleton` 基准：**1.8 ms/次**（3 层），旧逻辑估算 4.8 ms/次。

## 六、这轮踩的坑（下次省时间）

- **`Java.choose` 里拿到的实例经常 `isAttachedToWindow()==false`**：Activity 里有好几层
  `glassScaffold` 包出来的 backdrop，只有**真正在显示的那一个**是 attached 的。
  排查时要先过滤 `isAttachedToWindow()`，否则会对着死实例量半天。
- **前台被抢会直接导致 onDraw 计数为 0**：这台机器上抖音会自己启动抢焦点
  （`topResumedActivity` 变成 `com.ss.android.ugc.aweme` / `NotificationShade`），
  那时候应用不绘制，任何"帧计数"都是 0。**先确认 `mCurrentFocus` 是自己的窗口**，
  再做帧相关测量 —— 这轮在这上面浪费了好几轮。
- **frida 的 `setInterval` / `setTimeout` 回调打不出 log**（`-q` + 脚本模式下），
  跨脚本传递状态靠 `System.getProperties()` 塞一个 Java 对象是可行的，
  但**同一个计数器只在安装它的那个脚本里可靠**。
- `frida -l` 一次挂多个类的方法容易静默失败；**要验证代码有没有生效，
  最稳的是直接 `Java.choose` 拿实例、直接调方法、比对字段值**，
  而不是靠 hook 计数。
