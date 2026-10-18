# AHYU — Android AI 网关

把多家 AI 服务商的能力聚合到一台 Android 手机上，本机作为网关对外提供 OpenAI 兼容接口。

- 包名：`com.ahyu`（源码包 `com.cpa.gateway`）
- 当前版本：**4.31.2**（versionCode 609）
- 语言：纯 Java，**不使用 Gradle**，构建脚本即 `build.sh`
- 目标 SDK 35，最低 26

## 下载安装

不想自己编译的话，直接下打包好的 APK：

**[→ 下载最新版 APK](https://github.com/milleralexhusky-ctrl/AHYU-OSS/releases/latest)**

| | |
|---|---|
| 当前版本 | 4.31.2 |
| 要求 | Android 8.0（API 26）及以上 |
| 签名证书 SHA-256 | `45a99822b5bca88892d6b58b6f758aa246ac3d481e490d85ba5db749a3a223db` |

后续版本沿用同一把签名密钥，可直接覆盖升级，不必卸载重装。

## 构建

```bash
./build.sh           # 发布构建（/v1/debug/* 接口关闭）
./build.sh --debug   # 调试构建（/v1/debug/* 接口开启）
```

工具链：`aapt2 → javac → d8 → 打包 → apksigner`，输出 `cpa-gateway.apk`。

可用的环境变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `ANDROID_SDK` | `./android-sdk` → `/opt/android-sdk` | SDK 根目录 |
| `BUILD_TOOLS` | `35.0.0` | build-tools 版本目录 |
| `AAPT2_BIN` | 自动探测 | 指定 aapt2 可执行文件 |

需要 aapt2 / javac / d8 / apksigner / keytool / python3。在 ARM64 机器上若 aapt2 只有 x86_64 版，可用 `/usr/local/bin/aapt2-x86_64-on-arm64` 包装（qemu）。

## 签名

`keystore.jks` 随仓库一起保存（别名 `cpa`，口令在 `build.sh` 里）。**必须保留同一个 keystore**，否则新包无法覆盖升级已安装的旧版本，只能卸载重装、数据全丢。

## 目录

```
AndroidManifest.xml     清单
build.sh                构建 + 签名脚本
src/com/cpa/gateway/    UI / Activity 层
src/com/cpa/gateway/core/  网关核心（Server、Router、Upstreams、Config…）
res/                    资源（layout / drawable / values / raw 模型价格表）
docs/task-research/     一批 Python 实验脚本
AHYU-4.xx.y-*.md        逐版本改动说明，按版本号可追溯
keystore.jks            签名密钥（勿删）
```

## 代码规模

96 个 Java 文件，约 3.6 万行。主要文件：

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `core/Server.java` | 3962 | HTTP 服务、路由分发、协议转换 |
| `MainActivity.java` | 1801 | 主界面 |
| `core/Upstreams.java` | 1320 | 各上游的协议适配 |
| `Ui.java` | 1276 | 视觉系统（模数、玻璃材质） |
| `core/Router.java` | 1104 | 选路打分与重试预算 |
| `core/Config.java` | 990 | 配置模型与落盘 |

## 版本历史

根目录下的 `AHYU-*.md` 从 4.20.0 到 4.30.17 逐一记录了改动背景、根因和验证方式，是新接手时最可靠的信息来源。建议按版本号顺序读最近十份。

## 注意

- `src/com/cpa/gateway/core/*.orig*`、`*.orig2`、`*.orig3` 是历史备份文件，不参与编译（`build.sh` 只编译 `*.java`），可留作对照。
- `out/` 是构建产物，已在 `.gitignore` 中排除。
- `Debug.ON` 必须为 `false` 才是发布包；`build.sh` 会强制改写该开关。
