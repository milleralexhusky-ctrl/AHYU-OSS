#!/bin/bash
# CLIProxy Android 网关构建脚本（无 Gradle：aapt2 → javac → d8 → 打包 → apksigner）。
#
# 用法：在源码根目录直接 ./build.sh。
# 环境变量（可选）：
#   ANDROID_SDK   Android SDK 根目录（默认：脚本所在目录下的 android-sdk，
#                 再退回 /opt/android-sdk）
#   BUILD_TOOLS   build-tools 版本目录名（默认 35.0.0）
#   AAPT2_BIN     aapt2 可执行文件（默认 $ANDROID_SDK/build-tools/<ver>/aapt2，
#                 x86_64 机器上如果 SDK 里没有再找 /usr/local/bin/aapt2）
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
# 私库里 .private/signing.env 保存签名口令与本机 SDK 路径；公库没有这个目录，
# 所以这段在公库中什么都不做。私库 clone 下来直接 ./build.sh 就能用。
if [ -z "${KS_STOREPASS:-}" ] && [ -f "$ROOT/.private/signing.env" ]; then
    set -a
    . "$ROOT/.private/signing.env"
    set +a
    echo "已加载私库签名配置：.private/signing.env"
fi
if [ -n "$ANDROID_SDK" ]; then
    SDK="$ANDROID_SDK"
elif [ -d "$ROOT/android-sdk" ]; then
    SDK="$ROOT/android-sdk"
else
    SDK=/opt/android-sdk
fi
BT_VER="${BUILD_TOOLS:-35.0.0}"
BT="$SDK/build-tools/$BT_VER"
if [ -n "$AAPT2_BIN" ]; then
    AAPT2="$AAPT2_BIN"
elif [ -x /usr/local/bin/aapt2-x86_64-on-arm64 ]; then
    # 本机专用包装版（ARM 上通过 qemu 跑 x86_64 aapt2）
    AAPT2=/usr/local/bin/aapt2-x86_64-on-arm64
elif [ -x "$BT/aapt2" ]; then
    AAPT2="$BT/aapt2"
else
    echo "找不到可用的 aapt2（设 AAPT2_BIN 指定）"; exit 1
fi
PLATFORM=$SDK/platforms/android-35/android.jar
OUT=$ROOT/out

# 版本号只有一个来源：根目录 version.properties。
# 以前 versionCode/versionName 直接写在这条 aapt2 命令里，改版本要同时改
# 构建脚本、README、Release 说明，忘一处就会出现"装的包和文档对不上"。
VERSION_FILE=${VERSION_FILE:-$ROOT/version.properties}
VERSION_CODE=$(sed -n 's/^version\.code=//p' "$VERSION_FILE" | tr -d '[:space:]')
VERSION_NAME=$(sed -n 's/^version\.name=//p' "$VERSION_FILE" | tr -d '[:space:]')
case "$VERSION_CODE" in
    ''|*[!0-9]*)
        echo "version.properties 里的 version.code 缺失或不是整数：'$VERSION_CODE'"
        exit 1
        ;;
esac
if [ -z "$VERSION_NAME" ]; then
    echo "version.properties 里的 version.name 缺失"
    exit 1
fi

# README 顶部那行「当前版本：X（versionCode N）」必须跟 version.properties 对得上。
# 4.60.0 之前漏更新过：代码已经是 4.60.0/674，README 还写着 4.53.3/664 ——
# 照着 README 走的人会对不上号。这里做成构建期硬校验，忘改就编译失败，
# 而不是等谁发现。README 不在就不查（比如把它删掉的自定义构建）。
README_MD=$ROOT/README.md
if [ -f "$README_MD" ]; then
    R_NAME=$(sed -n 's/.*当前版本：\*\*\([0-9][0-9.]*\)\*\*.*/\1/p' "$README_MD" | head -1)
    R_CODE=$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' "$README_MD" | head -1)
    if [ "$R_NAME" != "$VERSION_NAME" ] || [ "$R_CODE" != "$VERSION_CODE" ]; then
        echo "README 的版本行与 version.properties 对不上："
        echo "  version.properties: $VERSION_NAME ($VERSION_CODE)"
        echo "  README.md:          ${R_NAME:-?} (${R_CODE:-?})"
        echo "把 README 顶部「当前版本：**$VERSION_NAME**（versionCode $VERSION_CODE）」改一致后重跑。"
        exit 1
    fi
fi

cd $ROOT
rm -rf $OUT
mkdir -p $OUT/compiled $OUT/gen $OUT/classes $OUT/dex

# 构建期间会临时改写 Debug.java（发布包必须是 false）。改完必须还原，
# 否则 `./build.sh --debug` 会把 ON=true 留在工作区，下一次提交就可能把
# 19 个 /v1/debug/* 接口带进发布包。trap 保证成功、失败、Ctrl-C 都还原。
DEBUG_SRC=src/com/cpa/gateway/core/Debug.java
cp $DEBUG_SRC $OUT/Debug.java.bak
restore_debug() { cp $OUT/Debug.java.bak $DEBUG_SRC 2>/dev/null || true; }

trap restore_debug EXIT INT TERM

# 构建期私密常量**不放在源码树里**，而是生成到 out/gen（未跟踪）。
# 以前是 sed 改写被跟踪的 BuildSecrets.java，再靠 trap 还原；SIGKILL/断电
# 不会触发 trap，密钥就会留在工作区、被下一次 commit / sync-public 带走。
# 现在生成物只存在于 out/（gitignore），源码树里根本没有这个文件。
write_build_secrets() {
  SECRET_VALUE="${ANTIGRAVITY_CLIENT_SECRET:-}"
  if [ -n "$SECRET_VALUE" ]; then
    # 只允许安全字符，避免把引号/反斜杠带进 Java 字面量。
    case "$SECRET_VALUE" in
      *[!A-Za-z0-9._~-]*)
        echo "[错误] ANTIGRAVITY_CLIENT_SECRET 含有不支持的字符"; exit 1 ;;
    esac
  fi
  mkdir -p "$OUT/gen/com/cpa/gateway/core"
  cat > "$OUT/gen/com/cpa/gateway/core/BuildSecrets.java" <<JAVA
package com.cpa.gateway.core;

/** 构建期生成，不进入源码树；公库构建时为空串。 */
final class BuildSecrets {
    private BuildSecrets() {
    }

    static final String ANTIGRAVITY_CLIENT_SECRET = "$SECRET_VALUE";
}
JAVA
  if [ -n "$SECRET_VALUE" ]; then
    echo "已注入 Antigravity OAuth Client Secret（生成到 out/gen，不进源码树）"
  else
    echo "未提供 ANTIGRAVITY_CLIENT_SECRET：本包无法完成 Antigravity 账号登录（公库默认行为）"
  fi
}

echo "构建版本 $VERSION_NAME ($VERSION_CODE)"
echo "[1/6] aapt2 compile"
$AAPT2 compile --dir res -o $OUT/compiled/res.zip

echo "[2/6] aapt2 link"
$AAPT2 link -o $OUT/base.apk -I $PLATFORM \
  --manifest AndroidManifest.xml \
  -R $OUT/compiled/res.zip \
  --java $OUT/gen \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --rename-manifest-package com.ahyu \
  --custom-package com.cpa.gateway \
  --auto-add-overlay

# 无条件显式设置调试开关：发布包必须是 false（以前只在 --debug 时改成 true，
# 一旦工作区的 Debug.java 被改成 true 就会漏进发布包，19 个 /v1/debug/* 接口全开着）
if [ "$1" = "--debug" ]; then
  sed -i "s/public static final boolean ON = .*/public static final boolean ON = true;/" \
    src/com/cpa/gateway/core/Debug.java
  echo "调试构建：/v1/debug/* 已启用"
else
  sed -i "s/public static final boolean ON = .*/public static final boolean ON = false;/" \
    src/com/cpa/gateway/core/Debug.java
  echo "发布构建：/v1/debug/* 已关闭"
fi

write_build_secrets

echo "[3/6] javac"
find src $OUT/gen -name '*.java' > $OUT/sources.txt
set +e
javac --release 11 -encoding UTF-8 -nowarn \
  -classpath $PLATFORM -d $OUT/classes @$OUT/sources.txt > $OUT/javac.log 2>&1
JRC=$?
set -e
grep -v "^Note:" $OUT/javac.log | head -40
if [ $JRC -ne 0 ]; then echo "JAVAC_FAILED"; exit 1; fi

echo "[4/6] d8"
$BT/d8 --lib $PLATFORM --min-api 26 --output $OUT/dex $(find $OUT/classes -name '*.class')

echo "[5/6] package"
cp $OUT/base.apk $OUT/aligned-base.apk
python3 - "$OUT" <<'PY'
import zipfile, shutil, glob, os, sys
out = sys.argv[1]
shutil.copyfile(out+'/aligned-base.apk', out+'/unsigned.apk')
zf=zipfile.ZipFile(out+'/unsigned.apk','a',zipfile.ZIP_DEFLATED)
for dex in sorted(glob.glob(out+'/dex/*.dex')):
    zf.write(dex, os.path.basename(dex))
    print('  +', os.path.basename(dex))
zf.close()
PY
cp $OUT/unsigned.apk $OUT/aligned.apk

echo "[6/6] sign"
KS=${KS_PATH:-$ROOT/keystore.jks}
# 口令从环境变量读，**不写进这个文件**。
#
# 以前这里硬编码了口令明文，而这个文件是公开仓库的一部分 ——
# 等于把签名密钥的钥匙挂在门上：谁拿到 keystore.jks（私库历史里有过），
# 再从这里读到口令，就能签出同签名的包冒充升级。口令和密钥必须分开存放。
#
# 本地使用：在构建前 export KS_STOREPASS / KS_KEYPASS（或放进未跟踪的 .env 再 source）。
if [ -z "${KS_STOREPASS:-}" ]; then
  cat >&2 <<PASSMSG
[错误] 没有提供签名密钥口令。

口令不写在源码里（build.sh 是公开的），构建前请设置环境变量：

    export KS_STOREPASS='你的口令'
    export KS_KEYPASS='你的口令'     # 与 storepass 相同可省略
    ./build.sh

或写进未跟踪的本地文件（.gitignore 已排除 .env）：

    echo "KS_STOREPASS=..." >> .env && . ./.env && ./build.sh
PASSMSG
  exit 1
fi
KS_KEYPASS=${KS_KEYPASS:-$KS_STOREPASS}

if [ ! -f "$KS" ]; then
  cat >&2 <<KEYMSG
[错误] 找不到签名密钥：$KS

签名密钥不随源码分发（.gitignore 已排除 *.jks），所以从仓库 clone 下来的工程
默认没有它。缺少密钥时这里是直接退出，而不是自动生成一把新的 —— 因为自动生成
出来的密钥签出的包，签名与原版不同，装不到已有安装的手机会报「应用未安装」，
而且构建过程看起来完全正常，非常容易误判成代码问题。

三种选择：
  1) 只想用 App：直接下载 Release 里的 APK，不要自己编译。
  2) 本地调试自用：显式造一把密钥，并清楚它和原版签名不同：
       keytool -genkeypair -keystore keystore.jks -alias cpa -keyalg RSA \
         -keysize 2048 -validity 10000 -storepass "\$KS_STOREPASS" \
         -keypass "\$KS_KEYPASS" -dname "CN=CPA Gateway, O=Local, C=CN"
  3) 要发布可覆盖升级的包：使用你自己的 keystore.jks 与对应口令（口令只存在本地）。
KEYMSG
  exit 1
fi
APK=$ROOT/AHYU-$VERSION_NAME.apk
apksigner sign --ks "$KS" --ks-pass "pass:$KS_STOREPASS" --key-pass "pass:$KS_KEYPASS" --out "$APK" $OUT/aligned.apk 2>&1 | grep -v WARNING || true
apksigner verify --print-certs "$APK" 2>&1 | grep -v WARNING | head -6
ls -la "$APK"
echo BUILD_OK
