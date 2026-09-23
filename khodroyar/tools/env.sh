# KhodroYar Android build environment (sandbox-specific toolchain)
# Toolchain is provisioned by tools/setup_env.sh
export ANDROID_TOOLS=/opt/android
export BT=$ANDROID_TOOLS/bt/30.0.3
export PLATFORM=$ANDROID_TOOLS/sable/android-34/android.jar
export JAVA_BIN=/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime/bin/java
export KOTLIN_COMPILER_JAR=$ANDROID_TOOLS/kotlinc/lib/kotlin-compiler.jar
export KOTLIN_STDLIB=$ANDROID_TOOLS/kotlinc/lib/kotlin-stdlib.jar
