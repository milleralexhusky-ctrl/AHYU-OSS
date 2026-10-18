# AHYU 4.21.4 —— 锁定竖屏

## 问题

App 没有方向锁定（所有历史版本的 AndroidManifest 里 `screenOrientation` 计数都是 0，
从来没做过这个声明）。所以 App 会跟随系统旋转。一横屏，主题/背景就崩掉。

## 为什么横屏会掉主题

`BackdropTex` 的背景纹理是**按当前屏幕宽高缓存**的：

```java
"backdrop-" + dm.widthPixels + "x" + dm.heightPixels + (Ui.LIGHT ? "-light" : "") + ".webp"
```

- 竖屏：`1302x2772` 这类尺寸，缓存和预置图都命中 → 正常
- 横屏：尺寸变了 → 缓存失效 → 走"现画"路径
  （`底纹：现画（无预置/无缓存）`），而 `GlassBackground.ensurePhoto()`
  加载的是打包在 APK 里的固定尺寸 `R.drawable.app_backdrop`，
  被拉伸去填横屏画布 → 看起来就是"主题掉了"

浅色模式更明显：`warmSync` 里 `Bitmap seed = Ui.LIGHT ? null : ...`，
**浅色下直接跳过预置图**，必然走现画+拉伸。

## 修法

给全部 **19 个 Activity** 加上 `android:screenOrientation="portrait"`。
App 永远不横屏，就永远走已验证好的竖屏渲染路径。

## 实测验证

装机后强制测试（关掉系统自动旋转、`user_rotation 1` 请求横屏）：

```
overrideOrientation=SCREEN_ORIENTATION_PORTRAIT
requestedOrientation=SCREEN_ORIENTATION_PORTRAIT
```

系统层面确认锁定生效，App 保持竖屏不跟随旋转。

## 改动

- `AndroidManifest.xml`：19 个 `<activity>` 各加一行 `android:screenOrientation="portrait"`

（这是本次唯一改动。res/、主题配置、代码逻辑均未触碰。）

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | BUILD_OK，6 步全过 |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 525 / versionName 4.21.4 / com.ahyu |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |
| 方向锁定 | 系统报告 PORTRAIT，强制横屏测试不跟随 |
| 主题配置 | accentTheme=rose 保持原样，未被改动 |

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 525 / 4.21.4）
- 副本 `/sdcard/Download/cpa/AHYU-4.21.4-com.ahyu.apk`

## 附带确认

本次一并复核了前几版的修复仍在包里：
- Trae 模型拉取（`get_detail_param`）→ 92 个模型可用
- 启动自动刷新积分 → 实测 Trae 1 / Trae 2 积分都正常回填（4762.45 / 4761.02）
