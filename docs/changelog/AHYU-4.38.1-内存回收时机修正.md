# AHYU 4.38.1：内存回收时机修正

versionCode 631。4.38.0 的跟进，只改一处判断。

## 问题

4.38.0 在 `onTrimMemory(level >= TRIM_MEMORY_RUNNING_CRITICAL)` 时释放底纹位图。
但 `RUNNING_CRITICAL`（15）的语义是**应用可见、但内存吃紧**——此时界面还在画，
`NavGlassDrawable.draw()` 下一帧就会调用 `BackdropTex.get()` 重新画一张，
于是变成"释放 → 3.5MB 重分配 → 再释放"的抖动，实际一兆都省不下来。

## 改动

只有同时满足以下两条才释放：

1. `level >= TRIM_MEMORY_UI_HIDDEN`（整个 UI 已隐藏）；
2. `startedActivities == 0`（没有任何 Activity 处于 STARTED）。

`onLowMemory` 同样加了 `startedActivities == 0` 的条件。

UI 隐藏路径（4.38.0 的主优化）不变：Activity 计数归零立即释放，
下次打开重画 6–11ms。

## 验证

- 启动自检 22/22 通过。
- 无界面 PSS 与 4.38.0 一致（约 27MB）。
- 界面可见时不再反复分配/释放底纹（内存关键档位下尤其重要）。
