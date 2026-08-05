#!/usr/bin/env bash
# ============================================================
# دانلود ابزارهای ساخت APK (فقط از pypi / npm / github)
# خروجی: tools/toolchain/
# ============================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/tools/toolchain"
mkdir -p "$TC" && cd "$TC"

echo "== jdk4py (Java runtime + keytool) =="
if [ ! -d jdk4py ]; then
  pip3 download jdk4py --no-deps -q -d jdk4py_dl
  unzip -q jdk4py_dl/*.whl -d jdk4py_x
  mv jdk4py_x/jdk4py/java-runtime jdk4py
  chmod +x jdk4py/bin/java jdk4py/bin/keytool
  rm -rf jdk4py_dl jdk4py_x
fi

echo "== aapt2 (از pypi) =="
if [ ! -d aapt2 ]; then
  pip3 download aapt2 --no-deps -q -d aapt2_dl
  unzip -q aapt2_dl/*.whl -d aapt2_x
  mv aapt2_x/aapt2 aapt2
  chmod +x aapt2/bin/Linux/aapt2
  rm -rf aapt2_dl aapt2_x
fi

echo "== tools.jar (javac) از npm =="
if [ ! -f tools.jar ]; then
  npm pack dataslope-tools-jar --silent >/dev/null
  tar -xzf dataslope-tools-jar-*.tgz package/tools.jar
  mv package/tools.jar . && rm -rf package dataslope-tools-jar-*.tgz
fi

echo "== d8.jar + apksigner.jar (build-tools 35) از github =="
if [ ! -f d8.jar ] || [ ! -f apksigner.jar ]; then
  git clone -q --depth 1 --filter=blob:none --sparse https://github.com/simonkdev/meta_xr_deployer mxd
  (cd mxd && git sparse-checkout set --no-cone \
      "/build-tools/android-sdk/build-tools/35.0.0/lib/d8.jar" \
      "/build-tools/android-sdk/build-tools/35.0.0/lib/apksigner.jar" >/dev/null)
  cp mxd/build-tools/android-sdk/build-tools/35.0.0/lib/d8.jar .
  cp mxd/build-tools/android-sdk/build-tools/35.0.0/lib/apksigner.jar .
  rm -rf mxd
fi

echo "== android.jar (API 35) از github =="
if [ ! -f android.jar ]; then
  git clone -q --depth 1 --filter=blob:none --sparse https://github.com/simonkdev/meta_xr_deployer mxd2
  (cd mxd2 && git sparse-checkout set --no-cone \
      "/build-tools/android-sdk/platforms/android-35/android.jar" >/dev/null)
  cp mxd2/build-tools/android-sdk/platforms/android-35/android.jar .
  rm -rf mxd2
fi

echo "✔ ابزارها آماده‌اند: $TC"
ls -la "$TC"
