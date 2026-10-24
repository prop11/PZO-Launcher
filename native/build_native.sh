#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

OS_NAME="$(uname -s)"
ARCH_NAME="$(uname -m)"

echo "================================================================================"
echo " Project Zomboid Optimiser (PZO Zomdroid Edition) - Native Build"
echo " Platform: $OS_NAME ($ARCH_NAME)"
echo "================================================================================"

# Locate JAVA_HOME if not already set
if [ -z "$JAVA_HOME" ]; then
    if [ "$OS_NAME" = "Darwin" ]; then
        if command -v /usr/libexec/java_home >/dev/null 2>&1; then
            JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
        fi
    fi
    if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME/include" ]; then
        for cand in /usr/lib/jvm/java-*-openjdk*/ /usr/lib/jvm/default-java/; do
            if [ -d "${cand}include" ]; then
                JAVA_HOME="${cand%/}"
                break
            fi
        done
    fi
    if [ -z "$JAVA_HOME" ]; then
        JAVA_BIN="$(command -v java 2>/dev/null || true)"
        if [ -n "$JAVA_BIN" ]; then
            JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")")")"
        fi
    fi
fi

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME/include" ]; then
    echo "[!] Error: JAVA_HOME not found or missing include directory."
    exit 1
fi

echo "[+] Using JAVA_HOME: $JAVA_HOME"
mkdir -p "$SCRIPT_DIR/../dist" "$SCRIPT_DIR/../bin"

TARGET_LIB="libpzo_native64.so"
JNI_MD_DIR="$JAVA_HOME/include/linux"

# Prefer Android NDK aarch64-linux-android clang for Zomdroid ARM64 Bionic compatibility
NDK_CLANG=""
for ndk_dir in "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" /usr/local/lib/android/sdk/ndk/*; do
    if [ -n "$ndk_dir" ] && [ -d "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin" ]; then
        for api in 24 26 28 29 30 33 34; do
            cand="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${api}-clang"
            if [ -x "$cand" ]; then
                NDK_CLANG="$cand"
                break 2
            fi
        done
    fi
done

if [ -z "$NDK_CLANG" ] && command -v aarch64-linux-android24-clang >/dev/null 2>&1; then
    NDK_CLANG="aarch64-linux-android24-clang"
fi

if [ -n "$NDK_CLANG" ]; then
    echo "[+] Compiling $TARGET_LIB for Android ARM64 (Bionic + NEON) via $NDK_CLANG..."
    "$NDK_CLANG" -O3 -shared -fPIC -march=armv8-a+simd \
        -I"$JAVA_HOME/include" -I"$JNI_MD_DIR" \
        pzo_native.c -o "$TARGET_LIB" -lm -ldl
elif [ "$ARCH_NAME" = "aarch64" ] || [ "$ARCH_NAME" = "arm64" ]; then
    CC="${CC:-clang}"
    echo "[+] Compiling $TARGET_LIB natively for ARM64 ($CC)..."
    $CC -O3 -shared -fPIC -march=armv8-a+simd \
        -I"$JAVA_HOME/include" -I"$JNI_MD_DIR" \
        pzo_native.c -o "$TARGET_LIB" -lm -lpthread -ldl
elif command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
    echo "[+] Cross-compiling $TARGET_LIB for ARM64 via aarch64-linux-gnu-gcc..."
    aarch64-linux-gnu-gcc -O3 -shared -fPIC -march=armv8-a+simd \
        -I"$JAVA_HOME/include" -I"$JNI_MD_DIR" \
        pzo_native.c -o "$TARGET_LIB" -lm -lpthread -ldl
else
    echo "[!] Error: No Android NDK (aarch64-linux-android-clang) or ARM64 compiler found."
    exit 1
fi

cp -f "$TARGET_LIB" "$SCRIPT_DIR/../dist/$TARGET_LIB"
cp -f "$TARGET_LIB" "$SCRIPT_DIR/../bin/$TARGET_LIB"
echo "[+] Successfully built Android ARM64 native library: $TARGET_LIB"
echo "================================================================================"
