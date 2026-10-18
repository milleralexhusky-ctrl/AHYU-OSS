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
cd $ROOT
rm -rf $OUT
mkdir -p $OUT/compiled $OUT/gen $OUT/classes $OUT/dex

echo "[1/6] aapt2 compile"
$AAPT2 compile --dir res -o $OUT/compiled/res.zip

echo "[2/6] aapt2 link"
$AAPT2 link -o $OUT/base.apk -I $PLATFORM \
  --manifest AndroidManifest.xml \
  -R $OUT/compiled/res.zip \
  --java $OUT/gen \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code 609 --version-name 4.31.2 \
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
KS=$ROOT/keystore.jks
if [ ! -f $KS ]; then
  keytool -genkeypair -keystore $KS -alias cpa -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android123 -keypass android123 -dname "CN=CPA Gateway, O=Local, C=CN" >/dev/null 2>&1
fi
apksigner sign --ks $KS --ks-pass pass:android123 --key-pass pass:android123 --out $ROOT/cpa-gateway.apk $OUT/aligned.apk 2>&1 | grep -v WARNING || true
apksigner verify --print-certs $ROOT/cpa-gateway.apk 2>&1 | grep -v WARNING | head -6
ls -la $ROOT/cpa-gateway.apk
echo BUILD_OK
