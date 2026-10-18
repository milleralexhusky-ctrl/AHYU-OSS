# AHYU — Android AI 网关

把多家 AI 服务商的能力聚合到一台 Android 手机上，本机作为网关对外提供 OpenAI 兼容接口。

- 包名：`com.ahyu`（源码包 `com.cpa.gateway`）
- 当前版本：**4.30.21**（versionCode 604）
- 语言：纯 Java，**不使用 Gradle**，构建脚本即 `build.sh`
- 目标 SDK 35，最低 26

## 构建

```bash
export KEYPASS='你的keystore口令'   # 或用 KEYS=<path> 指定已有密钥
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
| `KEYS` | `./keystore.jks` | 签名密钥路径 |
| `KEYPASS` | 无 | 密钥口令；未提供且无现成密钥时，脚本会生成一把**仅供本地测试**的 |

需要 aapt2 / javac / d8 / apksigner / keytool / python3。在 ARM64 机器上若 aapt2 只有 x86_64 版，可用 `/usr/local/bin/aapt2-x86_64-on-arm64` 包装（qemu）。

## 签名

**本仓库不包含签名密钥，这是有意的。** Android 的升级链依赖同一个签名密钥：
换 key 就意味着已安装用户必须卸载重装、数据全丢；而 key 泄露则意味着任何人都能
签发一个"看起来是官方"的包。所以请自己生成一把密钥并妥善保管：

```bash
keytool -genkeypair -keystore my-release.jks -alias cpa \
  -keyalg RSA -keysize 2048 -validity 10000
export KEYS=$PWD/my-release.jks KEYPASS='你的口令'
./build.sh
```

## 目录

```
AndroidManifest.xml          清单
build.sh                     构建 + 签名脚本
src/com/cpa/gateway/         UI / Activity 层
src/com/cpa/gateway/core/    网关核心（Server、Router、Upstreams、Config…）
res/                         资源（layout / drawable / values / raw 模型价格表）
docs/task-research/          一批 Python 实验脚本
AHYU-4.xx.y-*.md             逐版本改动说明，按版本号可追溯
```

## 代码规模

95 个 Java 文件，约 3.6 万行。主要文件：

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `core/Server.java` | 3957 | HTTP 服务、路由分发、协议转换 |
| `MainActivity.java` | 1794 | 主界面 |
| `core/Upstreams.java` | 1320 | 16 家上游的协议适配 |
| `Ui.java` | 1276 | 视觉系统（模数、玻璃材质） |
| `core/Router.java` | 1104 | 选路打分与重试预算 |
| `core/Config.java` | 988 | 配置模型与落盘 |

## 设计取向

这个项目的核心决策尽量写成**可解释、可验证、可调参的判据**，而不是 if 堆：

- `Router.score()`：贝叶斯平滑的成功率估计 + 延迟可信度折扣 + 成本对数项
- `Stats`：时间衰减加权的历史样本聚合（半衰期 3 天）
- `Capacity` / `Perf`：并发容量与帧预算按当前负载计算，而非静态常量
- `Router.prior()`：从模型名解析版本号、尺寸档、实验标记来推先验，**不用型号清单**
  （清单会过期且不报错，详见 `AHYU-4.30.21`）

协议适配（`Upstreams` 与各厂商类）保持直白的 if——那里没有目标函数，
强行数学化只会让适配更难维护。

## 版本历史

根目录下的 `AHYU-*.md` 从 4.20.0 到 4.30.21 逐一记录了改动背景、根因和验证方式，
是新接手时最可靠的信息来源。建议按版本号顺序读最近十份。

## 注意

- `out/` 是构建产物，已在 `.gitignore` 中排除。
- `Debug.ON` 必须为 `false` 才是发布包；`build.sh` 会强制改写该开关。
