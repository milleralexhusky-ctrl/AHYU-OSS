# AHYU 4.30.15 refreshStatus 增量判据

versionCode 598

## 问题

`refreshStatus()` 有 **8 个调用点**，全部跑在主线程。每次都：

1. 遍历全部账号跑 `unusableReason()`
2. 无条件打一条 `Log.i("界面看到：...")` —— 日志有 I/O 成本
3. 无条件 `setText` —— 即使内容没变，也会触发 requestLayout/invalidate

而绝大多数时候状态根本没变。冷启动时它被调用 3 次，就全量执行 3 遍。

## 改法：指纹短路

在函数最前面算一个 long 指纹，覆盖所有会影响界面文字的量：

    running / port / apiKey.hashCode() / lanAccess / providers.size()
    + 每个 provider 的 enabled / models.size() / badModels.size() / 是否可用

指纹相同就直接 return —— 一个 view 都不碰，一条日志都不打。

用 long 而不是 String 拼：拼字符串本身就是要避免的开销。
各项用 `print = print*31 + x` 混合（标准 hash combine），冲突概率足够低。

## 顺带消除的重复计算

指纹循环和计数循环都要 `unusableReason(p)`，而它内部可能要拼字符串。
原先改完会算**两遍**；改成一次算完存 `usableArr[]`，两处复用。

## 真机验证（4.30.15）

    启动后 "界面看到" 日志次数：1        （改之前是 3）
    数字正确：12 个账号（可用 11，暂不可用 1）· 模型 379（可用 373）
    切到账户页再返回：日志变 2 次        （状态变化时不会被误挡）

第二条是关键的**反向验证**：不能只测"不刷新"，还要测"该刷新时会刷新"。

## 没做

- 8 个调用点里仍有几个是高频的（onResume、积分回调）。目前靠指纹挡住重复计算，
  但没有做"同一帧内多次调用合并成一次"（真要合并得 post 到下一帧，
  会引入一次额外的界面延迟，收益不明显）。
- 概览页的账号列表仍是每次重建视图，未改成 DiffUtil 式的增量更新。
