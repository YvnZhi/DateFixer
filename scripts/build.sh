#!/usr/bin/env bash
# ============================================================
# DateFixer 手搓构建链（实测主链路：javac → D8 → aapt2 → sign）
# 无需 Android Studio。用法：
#   KEYSTORE=my.jks STOREPASS=mypass ./scripts/build.sh
# 不提供 KEYSTORE 时产出未签名 APK（aligned.apk），自行 apksigner。
#
# 依赖（JDK 8+）：
#   tools/android.jar  ← https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar
#   tools/r8.jar       ← https://maven.aliyun.com/repository/google/com/android/tools/r8/8.3.37/r8-8.3.37.jar
#   aapt2 / zipalign / apksigner ← Android SDK build-tools
# ============================================================
set -eu
cd "$(dirname "$0")/.."

ANDROID_JAR="${ANDROID_JAR:-tools/android.jar}"
R8_JAR="${R8_JAR:-tools/r8.jar}"
KEYSTORE="${KEYSTORE:-}"
STOREPASS="${STOREPASS:-}"
OUT="build"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] javac 编译 Java 源码 ..."
javac -source 8 -target 8 -cp "$ANDROID_JAR" -d "$OUT/classes" \
    app/src/main/java/com/mimo/datefix/*.java

echo "[2/6] D8 编译 dex ..."
java -cp "$R8_JAR" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" --release \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[3/6] aapt2 编译资源 ..."
aapt2 compile --dir app/src/main/res -o "$OUT/compiled.zip"

echo "[4/6] aapt2 链接 APK ..."
aapt2 link -I "$ANDROID_JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    -o "$OUT/base.apk" "$OUT/compiled.zip"

echo "[5/6] 合入 dex + zipalign ..."
(cd "$OUT/dex" && jar uf ../base.apk classes.dex)
zipalign -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] 签名 ..."
if [ -n "$KEYSTORE" ]; then
    apksigner sign --ks "$KEYSTORE" \
        --ks-pass "pass:$STOREPASS" --key-pass "pass:$STOREPASS" \
        --out "$OUT/DateFixer.apk" "$OUT/aligned.apk"
    echo "OK → $OUT/DateFixer.apk"
else
    echo "OK(未签名) → $OUT/aligned.apk"
    echo "（设置 KEYSTORE/STOREPASS 环境变量后自动签名）"
fi