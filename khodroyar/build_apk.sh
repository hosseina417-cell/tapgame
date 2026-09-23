#!/usr/bin/env bash
# =============================================================================
# KhodroYar (خودرویار) — APK build pipeline (no Gradle / no Android SDK needed)
# Pipeline: aapt2 (resources+manifest) -> kotlinc -> d8 -> zip -> zipalign -> apksigner
# Usage: ./build_apk.sh [versionName] [versionCode]
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

source ./tools/env.sh

# --- version: CLI args override version.properties -------------------------
VERSION_NAME_ARG="${1:-}"
VERSION_CODE_ARG="${2:-}"
if [ -f version.properties ] && [ -z "$VERSION_NAME_ARG" ]; then
    VERSION_NAME=$(grep '^versionName=' version.properties | cut -d= -f2)
    VERSION_CODE=$(grep '^versionCode=' version.properties | cut -d= -f2)
fi
VERSION_NAME="${VERSION_NAME_ARG:-${VERSION_NAME:-1.0.0}}"
VERSION_CODE="${VERSION_CODE_ARG:-${VERSION_CODE:-1}}"
OUT_DIR="dist"
WORK="build/work"
PKG="com.khodroyar.app"

echo "==> Building KhodroYar v$VERSION_NAME (code $VERSION_CODE)"

rm -rf "$WORK" "$OUT_DIR"/*.apk.tmp 2>/dev/null || true
mkdir -p "$WORK/classes" "$WORK/dex" "$OUT_DIR"

# ---------------------------------------------------------------- 1) resources
echo "==> [1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir res -o "$WORK/res.zip"

echo "==> [2/6] aapt2 link"
mkdir -p "$WORK/gen"
"$BT/aapt2" link \
    -o "$WORK/base.apk" \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --java "$WORK/gen" \
    --min-sdk-version 21 \
    --target-sdk-version 34 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --auto-add-overlay \
    "$WORK/res.zip"

# ---------------------------------------------------------------- 2) kotlin
echo "==> [3/6] kotlinc"
find src "$WORK/gen" -name "*.kt" -o -name "*.java" > "$WORK/sources.txt"
"$JAVA_BIN" -cp "$KOTLIN_COMPILER_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 1.8 -nowarn \
    -classpath "$PLATFORM:$KOTLIN_STDLIB" \
    -d "$WORK/classes" \
    @"$WORK/sources.txt"

# ---------------------------------------------------------------- 3) dex
echo "==> [4/6] d8 dex"
mapfile -t CLASSES < <(find "$WORK/classes" -name "*.class")
"$JAVA_BIN" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release \
    --lib "$PLATFORM" --min-api 21 --output "$WORK/dex" \
    "$KOTLIN_STDLIB" "${CLASSES[@]}"
test -f "$WORK/dex/classes.dex"

# ---------------------------------------------------------------- 4) package
echo "==> [5/6] package dex into apk"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
cd "$WORK/dex" && zip -q -j ../unsigned.apk classes.dex && cd - >/dev/null

# ---------------------------------------------------------------- 5) align + sign
echo "==> [6/6] zipalign + apksigner"
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

KS="../keystore/khodroyar.keystore"
if [ ! -f "$KS" ]; then
    echo "    keystore missing -> generating"
    mkdir -p ../keystore
    /opt/pytools21/jdk4py/java-runtime/bin/keytool -genkeypair -v \
        -keystore "$KS" -alias khodroyar -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass khodroyar123 -keypass khodroyar123 \
        -dname "CN=KhodroYar, OU=Mobile, O=KhodroYar, L=Tehran, C=IR" >/dev/null 2>&1
fi

FINAL_APK="$OUT_DIR/KhodroYar-v$VERSION_NAME.apk"
"$JAVA_BIN" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    --ks "$KS" --ks-key-alias khodroyar \
    --ks-pass pass:khodroyar123 --key-pass pass:khodroyar123 \
    --min-sdk-version 21 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$FINAL_APK" "$WORK/aligned.apk"

# ---------------------------------------------------------------- 6) verify
echo "==> verifying"
"$BT/zipalign" -c 4 "$FINAL_APK"
"$JAVA_BIN" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify \
    --print-certs "$FINAL_APK" | head -4
"$JAVA_BIN" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify \
    --verbose "$FINAL_APK" | grep -E "^Verifies|v1 scheme|v2 scheme" || true

ls -la "$FINAL_APK"
echo "==> DONE: $FINAL_APK"
