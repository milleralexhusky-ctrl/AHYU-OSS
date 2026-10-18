# AHYU 4.24.1 —— 启动明文自愈接入 + 前台通知刷新修复

versionCode 536 / versionName 4.24.1
签名 SHA-256 45a99822b5bca88892d6b58b6f758aa246ac3d481e490d85ba5db749a3a223db（与历史一致）

## 一、migratePlaintextIfPossible() 接入启动流程（补上 4.24.0 的遗留项）

4.24.0 遗留里写着"该方法作为显式入口已就绪，但还没在启动流程里调用一次"。本次补上。

### 为什么必须由启动主动调

Secret.seal(JSONObject) 的重试只发生在保存时。一个装好之后再也不打开设置页的用户，
Keystore 就算恢复了，明文也会一直躺到下一次保存为止——可能永远不触发。启动是天然的检查点。

### 实现

- 新增 Secret.migratePlaintextOnStartup(File)：直接读磁盘文件，不依赖 Config 内存对象，
  所以"Config 还没加载完"这种时序问题不会让它读到半成品。
- 新增 Secret.writeSealed(File, JSONObject)：原子写回（临时文件 + rename，失败退回直写）。
- Gateway.schedulePlaintextMigration(ctx)：在 Gateway.init() 里 Config 加载完成之后调度，
  走 Threads.io()（后台 IO 池）执行；用 migrationScheduled 标记保证只跑一次
  （init 会被 MainActivity 和 GatewayService 各调一次）。

### 三条约束（调用方不必额外判断）

1. 未就绪不污染 lastSealFailed：文件不存在／读不出／解析失败 → 返回
   MIGRATE_NOT_READY(-1)，不碰 lastSealFailed，避免把一次时序问题误记成一次真实失败。
2. 不写业务字段的值：只把明文换成密文。
3. 失败一定显性：真读到明文且迁不动时，才置位并打 ERROR 日志。

### 真机验证（三场景）

| 场景 | 结果 |
|---|---|
| 配置里有明文 | 日志"启动自愈：发现 1 个明文敏感字段…" → "明文已全部迁回密文并落盘"；文件里 PLAINTEXT_TEST_KEY_12345 变回 enc:v1:…，grep 明文计数 0 |
| 文件不存在（全新安装） | "敏感字段已全部为密文，无需迁移"（Config.load 会先建空配置）——未记失败 |
| 配置损坏（非法 JSON） | W 级"启动自愈：配置读取失败，本次跳过（不记失败）"——明确不记失败 |
| 线程 | 日志线程号 ≠ 主线程，确认跑在后台 |
| 迁移后 | 真实配置完整恢复，10 账号 / 242 模型，服务正常 |

## 二、前台通知卡在「正在启动」不刷新（真机复现并修复）

### 现象

通知栏显示「正在启动」，应用内主页已是「运行中」，服务日志 service started, port 8321。
两者不矛盾，是通知没跟上。

### 排查过程中的两次错误方向（记录以免重蹈）

1. 先以为是旧版遗留（源码已修、设备未装）→ 核对 md5 后发现设备与工作区是同一个包，判断错误。
2. 再以为修复没生效 → 按时间轴逐秒观察才看清：启动路径那次 notify() 确实调了、
   也确实没效果，但看门狗那一路（45 秒后）是生效的。

教训：动代码前先确认"测的是不是这个包"（md5 对齐），否则会在错误的版本上分析半天。

### 根因（以实验为准，不是推断）

系统对刚由 startForeground() 提交的前台通知有一小段节流窗口，此期间同一 id 的
普通 notify() 更新会被直接丢弃。

实验证据：

- 冷启动 T+2s 服务就绪，通知直到 T+46s（≈ 一个看门狗周期）才翻成「运行中」
- 手动触发第二次 onStartCommand（此时通知已存在、不在窗口内）→ 立刻变「运行中」

因此 force=true（跳过去重）对启动路径无效——它跳过的是应用层去重，跳不过系统层节流。
这条推翻了"启动后立刻 force 刷新即可"的假设。

### 修复

```java
notifyStatus(runningText(), true);          // 立刻刷一次（覆盖多数情况）
Threads.BG.schedule(() -> {                  // 3 秒后再补一次（避开节流窗口）
    if (watching) notifyStatus(runningText(), true);
}, 3, TimeUnit.SECONDS);
```

- lastNotifText = "正在启动" 在 startForeground() 前同步，保证紧随其后的调用不被去重误拦
- 看门狗（45 秒）继续用 notifyStatus(runningText(), true) 兜底，原样保留
- if (watching) 守卫：停止服务后延时任务不会再把通知刷回来

### 真机验证

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 冷启动通知对齐 | T+46s | T+4s（两次冷启动一致） |
| 停止服务 | 通知移除 | 通知移除，未被延时任务刷回 |
| 回归 | 10 账号 / 242 模型 | 一致，health 401（需认证，符合预期） |

## 三、未做 / 遗留

- auto-race 的实际延迟排序效果仍未做对比实测（需多次调用积累 latencyEwma）
- 剪贴板真机两用例仍缺工具，未验（逻辑已证）
