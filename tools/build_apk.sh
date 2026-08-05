#!/usr/bin/env bash
# ============================================================
# ساخت APK برنامه «اسکنر کریپتو» — بدون نیاز به اندروید استودیو
# این اسکریپت با ابزارهایی که در tools/fetch_toolchain.sh
# دانلود می‌شوند، APK امضا شده می‌سازد.
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="${TOOLS_DIR:-$ROOT/tools/toolchain}"
OUT="$ROOT/bin"

# ---------- ابزارها ----------
JAVA="$TOOLS/jdk4py/bin/java"
KEYTOOL="$TOOLS/jdk4py/bin/keytool"
AAPT2="$TOOLS/aapt2/bin/Linux/aapt2"
JAVAC_JAR="$TOOLS/tools.jar"
D8_JAR="$TOOLS/d8.jar"
APKSIGNER_JAR="$TOOLS/apksigner.jar"
ANDROID_JAR="$TOOLS/android.jar"

KEYSTORE="$TOOLS/cryptoscanner.keystore"
KS_PASS="cryptoscanner123"
KS_ALIAS="cryptoscanner"

VERSION_CODE="${VERSION_CODE:-$(grep -o "APP_VERSION_CODE = [0-9]*" "$ROOT/app/js/version.js" | grep -o "[0-9]*")}"
VERSION_NAME="${VERSION_NAME:-$(grep -o "APP_VERSION = '[^']*'" "$ROOT/app/js/version.js" | grep -o "[0-9.]*")}"
APP_LABEL="اسکنر کریپتو"
PKG="com.cryptoscanner.app"

# ---------- پاکسازی ----------
BUILD="$ROOT/build_apk_tmp"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dexout" "$OUT"

echo "[1/6] کامپایل منابع (aapt2 compile)"
"$AAPT2" compile --dir "$ROOT/android/res" -o "$BUILD/res.flata"

echo "[2/6] لینک منابع و مانیفست (aapt2 link)"
"$AAPT2" link \
    -o "$BUILD/app-unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/android/AndroidManifest.xml" \
    --min-sdk-version 21 \
    --target-sdk-version 34 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    -A "$ROOT/app" \
    "$BUILD/res.flata"

echo "[3/6] کامپایل جاوا (javac از tools.jar)"
"$JAVA" -cp "$JAVAC_JAR" com.sun.tools.javac.Main \
    -source 1.8 -target 1.8 \
    -bootclasspath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    "$ROOT/android/src/com/cryptoscanner/app/MainActivity.java"

echo "[4/6] تبدیل به dex (d8)"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release \
    --lib "$ANDROID_JAR" \
    --min-api 21 \
    --output "$BUILD/dexout" \
    $(find "$BUILD/classes" -name '*.class')

echo "[5/6] افزودن classes.dex به APK"
(cd "$BUILD/dexout" && zip -q -X "$BUILD/app-unsigned.apk" classes.dex)

echo "[6/6] امضای APK (apksigner v1+v2+v3)"
if [ ! -f "$KEYSTORE" ]; then
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=Crypto Scanner, OU=Scanner, O=CryptoScanner, C=IR" >/dev/null 2>&1
fi
"$JAVA" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/cryptoscanner-$VERSION_NAME.apk" \
    "$BUILD/app-unsigned.apk"

echo "--- بررسی امضا ---"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$OUT/cryptoscanner-$VERSION_NAME.apk"
echo "--- مشخصات APK ---"
"$AAPT2" dump badging "$OUT/cryptoscanner-$VERSION_NAME.apk" | head -12

rm -rf "$BUILD"
echo "✔ APK نهایی: $OUT/cryptoscanner-$VERSION_NAME.apk"
