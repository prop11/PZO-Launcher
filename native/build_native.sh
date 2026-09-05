#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

OS_NAME="$(uname -s)"
ARCH_NAME="$(uname -m)"

echo "================================================================================"
echo " Project Zomboid Optimiser (PZO) - Native Build"
echo " Platform: $OS_NAME ($ARCH_NAME)"
echo "================================================================================"

# Locate JAVA_HOME if not already set
if [ -z "$JAVA_HOME" ]; then
    if [ "$OS_NAME" = "Darwin" ]; then
        if command -v /usr/libexec/java_home >/dev/null 2>&1; then
            JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
        fi
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
    echo "    Please set JAVA_HOME to an installed JDK (e.g. OpenJDK 17 or 21)."
    exit 1
fi

echo "[+] Using JAVA_HOME: $JAVA_HOME"

CC="${CC:-}"
if [ -z "$CC" ]; then
    if command -v clang >/dev/null 2>&1; then
        CC="clang"
    elif command -v gcc >/dev/null 2>&1; then
        CC="gcc"
    else
        echo "[!] Error: Neither clang nor gcc compiler was found."
        exit 1
    fi
fi

echo "[+] Using Compiler: $CC"

mkdir -p "$SCRIPT_DIR/../dist"

if [ "$OS_NAME" = "Darwin" ]; then
    TARGET_LIB="libpzo_native64.dylib"
    JNI_MD_DIR="$JAVA_HOME/include/darwin"
    
    ARCH_FLAGS=""
    if [ "$ARCH_NAME" = "x86_64" ]; then
        ARCH_FLAGS="-mavx2"
    fi
    
    echo "[*] Compiling $TARGET_LIB for macOS ($ARCH_NAME)..."
    $CC -O3 -shared -fPIC $ARCH_FLAGS         -I"$JAVA_HOME/include" -I"$JNI_MD_DIR"         pzo_native.c -o "$TARGET_LIB" -lm -lpthread
        
    cp -f "$TARGET_LIB" "$SCRIPT_DIR/../dist/$TARGET_LIB"
    echo "[+] Successfully built: $TARGET_LIB -> dist/$TARGET_LIB"

elif [ "$OS_NAME" = "Linux" ]; then
    TARGET_LIB="libpzo_native64.so"
    JNI_MD_DIR="$JAVA_HOME/include/linux"
    
    ARCH_FLAGS=""
    if [ "$ARCH_NAME" = "x86_64" ]; then
        ARCH_FLAGS="-mavx2"
    fi
    
    echo "[*] Compiling $TARGET_LIB for Linux ($ARCH_NAME)..."
    $CC -O3 -shared -fPIC $ARCH_FLAGS         -I"$JAVA_HOME/include" -I"$JNI_MD_DIR"         pzo_native.c -o "$TARGET_LIB" -lm -lpthread
        
    cp -f "$TARGET_LIB" "$SCRIPT_DIR/../dist/$TARGET_LIB"
    echo "[+] Successfully built: $TARGET_LIB -> dist/$TARGET_LIB"

else
    echo "[!] Unsupported OS: $OS_NAME. Use build_native.bat on Windows."
    exit 1
fi

echo "================================================================================"
echo " Native Compilation Complete: $TARGET_LIB"
echo "================================================================================"
