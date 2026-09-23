#!/usr/bin/env bash
# JVM unit tests for pure-Kotlin logic (no device required)
set -euo pipefail
cd "$(dirname "$0")/.."
source ./tools/env.sh

OUT="build/test-classes"
rm -rf "$OUT"; mkdir -p "$OUT"

"$JAVA_BIN" -cp "$KOTLIN_COMPILER_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -nowarn -d "$OUT" \
    src/com/khodroyar/app/util/Jalali.kt \
    src/com/khodroyar/app/util/Fmt.kt \
    tools/test/RunTests.kt

exec "$JAVA_BIN" -cp "$OUT:$KOTLIN_STDLIB" com.khodroyar.app.test.RunTestsKt
