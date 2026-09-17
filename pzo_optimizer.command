#!/bin/bash
# ==============================================================================
# Project Zomboid Build 42 - PZO Optimizer  |  macOS installer (FIXED)
# Rewritten macOS branch: safe JVM flags, no main-class swap, no -agentlib,
# absolute -javaagent path, arch-checked dylib, proper re-signing.
# ==============================================================================

set -u

PZO_VERSION="0.9.8-unstable"

echo "================================================================="
echo " PZO Optimizer - macOS installer ($PZO_VERSION)"
echo "================================================================="

if [ "$(uname -s)" != "Darwin" ]; then
    echo "[!] This script is macOS-only. Use pzo_optimizer.sh on Linux."
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ------------------------------------------------------------------ engine jar
PZ_JAR=""
for c in "$SCRIPT_DIR/PZOptimEngine.jar" "$SCRIPT_DIR/dist/PZOptimEngine.jar" "$PWD/PZOptimEngine.jar"; do
    [ -f "$c" ] && PZ_JAR="$c" && break
done

if [ -z "$PZ_JAR" ] || [ ! -f "$PZ_JAR" ]; then
    echo ""
    echo "[!] PZOptimEngine.jar not found next to this script."
    echo "    Attempting automatic download from GitHub Releases..."
    TARGET_DOWNLOAD="$SCRIPT_DIR/PZOptimEngine.jar"
    DOWNLOAD_URL="https://github.com/prop11/PZO-Launcher/releases/latest/download/PZOptimEngine.jar"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -A "PZO-Installer" "$DOWNLOAD_URL" -o "$TARGET_DOWNLOAD" || true
    elif command -v wget >/dev/null 2>&1; then
        wget -q -U "PZO-Installer" "$DOWNLOAD_URL" -O "$TARGET_DOWNLOAD" || true
    fi
    if [ -f "$TARGET_DOWNLOAD" ]; then
        PZ_JAR="$TARGET_DOWNLOAD"
        echo "[+] Successfully downloaded latest PZOptimEngine.jar!"
    else
        echo "[!] Error: Could not download or locate PZOptimEngine.jar."
        exit 1
    fi
fi
echo "[+] Engine package: $PZ_JAR"

# ------------------------------------------------------------------ app bundle
APP_BUNDLE=""
for p in \
    "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app" \
    "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/ProjectZomboid.app" \
    "$HOME/Library/Application Support/Steam/steamapps/common/Project Zomboid/Project Zomboid.app" \
    "$HOME/Library/Application Support/Steam/steamapps/common/Project Zomboid/ProjectZomboid.app" ; do
    [ -d "$p" ] && APP_BUNDLE="$p" && break
done

if [ -z "$APP_BUNDLE" ]; then
    echo "[-] Could not locate the game bundle automatically."
    read -r -p "Drag & drop Project Zomboid.app here and press Enter: " APP_BUNDLE
fi
APP_BUNDLE="${APP_BUNDLE%/}"
APP_BUNDLE="${APP_BUNDLE#\'}"; APP_BUNDLE="${APP_BUNDLE%\'}"
APP_BUNDLE="${APP_BUNDLE#\"}"; APP_BUNDLE="${APP_BUNDLE%\"}"

PLIST="$APP_BUNDLE/Contents/Info.plist"
JAVA_DIR="$APP_BUNDLE/Contents/Java"
INSTALLED_JAR="$JAVA_DIR/PZOptimEngine.jar"

if [ ! -f "$PLIST" ]; then
    echo "[!] Invalid bundle (no Contents/Info.plist): $APP_BUNDLE"
    exit 1
fi
echo "[+] Game bundle: $APP_BUNDLE"

# --------------------------------------------------------------- menu (repeat)
MODE="install"
if [ -f "$INSTALLED_JAR" ] || [ -f "${PLIST}.bak" ]; then
    echo ""
    echo "1) Install / Repair  (re-apply a clean, safe configuration)"
    echo "2) Uninstall         (restore the stock Info.plist)"
    echo "3) Cancel"
    read -r -p "Choice: " CH
    case "$CH" in
        1) MODE="install" ;;
        2) MODE="uninstall" ;;
        *) echo "[-] Cancelled."; exit 0 ;;
    esac
fi

restore_debug_options() {
    DEBUG_OPT="$HOME/Zomboid/debug-options.ini"
    if [ -f "$DEBUG_OPT" ]; then
        sed -i '' \
            -e 's/FBORenderChunk.CorpsesInChunkTexture=true/FBORenderChunk.CorpsesInChunkTexture=false/g' \
            -e 's/FBORenderChunk.ItemsInChunkTexture=true/FBORenderChunk.ItemsInChunkTexture=false/g' \
            -e 's/Lighting.SplitUpdate=true/Lighting.SplitUpdate=false/g' \
            -e 's/Threading.Lighting=true/Threading.Lighting=false/g' \
            "$DEBUG_OPT" 2>/dev/null || true
        echo "[+] Reset experimental FBO / lighting render flags in debug-options.ini"
    fi
}

resign_bundle() {
    xattr -dr com.apple.quarantine "$APP_BUNDLE" 2>/dev/null || true
    if codesign --force --sign - "$APP_BUNDLE" 2>/dev/null; then
        echo "[+] Bundle re-signed (ad-hoc)."
    elif codesign --force --deep --sign - "$APP_BUNDLE" 2>/dev/null; then
        echo "[+] Bundle re-signed (ad-hoc, deep)."
    else
        echo "[!] codesign failed. If the game is killed instantly on launch, run:"
        echo "    codesign --force --deep --sign - \"$APP_BUNDLE\""
    fi
    codesign --verify --deep "$APP_BUNDLE" 2>/dev/null \
        && echo "[+] Signature verified." \
        || echo "[!] Signature verification reported problems (usually still playable)."
}

# ------------------------------------------------------------------- uninstall
if [ "$MODE" = "uninstall" ]; then
    rm -f "$INSTALLED_JAR" "$JAVA_DIR/libpzo_native64.dylib" "$APP_BUNDLE/Contents/MacOS/libpzo_native64.dylib"
    if [ -f "${PLIST}.bak" ]; then
        cp -f "${PLIST}.bak" "$PLIST"
        echo "[+] Stock Info.plist restored."
    else
        echo "[!] No Info.plist backup found - verify game files through Steam."
    fi
    rm -f "$HOME/Zomboid/Lua"/pzo_* "$HOME/Zomboid"/pzo_* 2>/dev/null || true
    restore_debug_options
    resign_bundle
    echo "[+] Uninstall complete."
    exit 0
fi

# ------------------------------------------------------- runtime probe (arch/version)
LAUNCHER_BIN=""
for b in "$APP_BUNDLE/Contents/MacOS/JavaAppLauncher" "$APP_BUNDLE/Contents/MacOS"/*; do
    [ -f "$b" ] && [ -x "$b" ] && LAUNCHER_BIN="$b" && break
done

APP_ARCH="unknown"
if [ -n "$LAUNCHER_BIN" ] && command -v lipo >/dev/null 2>&1; then
    APP_ARCH="$(lipo -archs "$LAUNCHER_BIN" 2>/dev/null | tr ' ' ',')"
fi
echo "[+] Launcher architecture: ${APP_ARCH:-unknown}"

JAVA_BIN=""
for j in "$APP_BUNDLE/Contents/PlugIns"/*/Contents/Home/bin/java "$APP_BUNDLE/Contents/Home/bin/java"; do
    [ -x "$j" ] && JAVA_BIN="$j" && break
done

JAVA_MAJOR=17
if [ -n "$JAVA_BIN" ]; then
    RAW="$("$JAVA_BIN" -version 2>&1 | head -n1)"
    V="$(echo "$RAW" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
    [ -n "$V" ] && JAVA_MAJOR="$V"
    echo "[+] Bundled runtime: $RAW  (major $JAVA_MAJOR)"
else
    echo "[!] Bundled JRE not found - assuming Java $JAVA_MAJOR (conservative flags)."
fi

# ------------------------------------------------------------------------- RAM
RAM_BYTES="$(sysctl -n hw.memsize 2>/dev/null || echo 8589934592)"
TOTAL_RAM=$((RAM_BYTES / 1024 / 1024 / 1024))
if   [ "$TOTAL_RAM" -ge 32 ]; then ALLOC_RAM=8
elif [ "$TOTAL_RAM" -ge 16 ]; then ALLOC_RAM=6
elif [ "$TOTAL_RAM" -ge 8  ]; then ALLOC_RAM=4
else                               ALLOC_RAM=3
fi
RAM_MB=$((ALLOC_RAM * 1024))
echo "[+] System RAM ${TOTAL_RAM}GB -> heap ${ALLOC_RAM}GB (-Xmx${RAM_MB}m)"

# --------------------------------------------------------------- install files
mkdir -p "$JAVA_DIR"
cp -f "$PZ_JAR" "$INSTALLED_JAR"
echo "[+] Installed $INSTALLED_JAR"

DYLIB_SRC=""
for d in "$SCRIPT_DIR/libpzo_native64.dylib" "$SCRIPT_DIR/dist/libpzo_native64.dylib" "$SCRIPT_DIR/native/libpzo_native64.dylib"; do
    [ -f "$d" ] && DYLIB_SRC="$d" && break
done

rm -f "$JAVA_DIR/libpzo_native64.dylib" "$APP_BUNDLE/Contents/MacOS/libpzo_native64.dylib"
if [ -n "$DYLIB_SRC" ]; then
    DYLIB_ARCH="$(lipo -archs "$DYLIB_SRC" 2>/dev/null | tr ' ' ',')"
    MATCH=0
    case ",$APP_ARCH," in
        *",arm64,"*) case ",$DYLIB_ARCH," in *",arm64,"*) MATCH=1 ;; esac ;;
    esac
    case ",$APP_ARCH," in
        *",x86_64,"*) case ",$DYLIB_ARCH," in *",x86_64,"*) MATCH=1 ;; esac ;;
    esac
    if [ "$MATCH" -eq 1 ]; then
        cp -f "$DYLIB_SRC" "$JAVA_DIR/libpzo_native64.dylib"
        mkdir -p "$APP_BUNDLE/Contents/MacOS"
        cp -f "$DYLIB_SRC" "$APP_BUNDLE/Contents/MacOS/libpzo_native64.dylib"
        echo "[+] Native companion installed (dylib $DYLIB_ARCH)."
    else
        echo "[!] Skipping libpzo_native64.dylib: built for [$DYLIB_ARCH], the game runs as [$APP_ARCH]."
        echo "    The Java engine works without it; only the native governor is disabled."
    fi
fi

# ------------------------------------------------------------------ plist work
[ -f "${PLIST}.bak" ] || { cp -f "$PLIST" "${PLIST}.bak"; echo "[+] Backed up stock Info.plist -> Info.plist.bak"; }
cp -f "$PLIST" "${PLIST}.pzo-prev"

/usr/bin/python3 - "$PLIST" "${PLIST}.bak" "$RAM_MB" "$JAVA_MAJOR" "$INSTALLED_JAR" <<'PYEOF'
import plistlib, sys

plist_path, backup_path, ram_mb, java_major, jar_abs = sys.argv[1:6]
java_major = int(java_major)

with open(plist_path, "rb") as f:
    pl = plistlib.load(f)
try:
    with open(backup_path, "rb") as f:
        stock = plistlib.load(f)
except Exception:
    stock = {}

MAIN_KEYS = ["JVMMainClassName", "MainClass", "JVMEntrypoint"]

def repair_main_class(container, stock_container):
    """Never point the launcher at PZOEntrypoint. JavaAppLauncher feeds this
    string straight to JNI FindClass, which needs slash notation; a dotted name
    (or any wrong name) makes the launcher die before a window appears."""
    for k in MAIN_KEYS:
        if k in container and "pzoptimizer" in str(container[k]).lower():
            container[k] = stock_container.get(k, "zombie/gameStates/MainScreenState")
            print("[+] Repaired %s -> %s" % (k, container[k]))

repair_main_class(pl, stock)
if isinstance(pl.get("Java"), dict):
    repair_main_class(pl["Java"], stock.get("Java", {}) if isinstance(stock.get("Java"), dict) else {})
if isinstance(pl.get("JVMOptions"), dict):
    repair_main_class(pl["JVMOptions"], {})

# Undo a classpath injection from an earlier run (the -javaagent jar is added to
# the system classpath by the JVM itself, so this entry is never needed).
for key in ("JVMClassPath", "ClassPath"):
    for holder in (pl, pl.get("Java") if isinstance(pl.get("Java"), dict) else {}):
        if key in holder:
            v = holder[key]
            if isinstance(v, list):
                holder[key] = [x for x in v if "PZOptimEngine.jar" not in str(x)]
            elif isinstance(v, str):
                holder[key] = ":".join(p for p in v.split(":") if "PZOptimEngine.jar" not in p)

DROP_PREFIXES = (
    "-Xmx", "-Xms",
    "-agentlib:pzo_native64",
    "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:-ZGenerational", "-XX:ZCollection",
    "-XX:+UseG1GC", "-XX:+UseParallelGC", "-XX:+UseSerialGC", "-XX:+UseShenandoahGC",
    "-XX:+AlwaysPreTouch", "-XX:+UseNUMA", "-XX:+UseSuperWord",
    "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders",
    "-XX:+PerfDisableSharedMem", "-XX:InitiatingHeapOccupancyPercent",
    "-XX:G1ReservePercent", "-XX:MaxInlineLevel", "-XX:InlineSmallCode",
    "--enable-native-access", "--add-exports=java.base/jdk.internal.misc",
    "-Djava.awt.headless", "-Dzomboid.steam", "-Dpzo.",
)

def is_pzo_managed(a):
    a = str(a)
    if a.startswith("-javaagent:") and "PZOptimEngine.jar" in a:
        return True
    return any(a.startswith(p) for p in DROP_PREFIXES)

args = [
    "-javaagent:%s" % jar_abs,          # absolute: the launcher's CWD is not the bundle
    "-Xmx%sm" % ram_mb,
    "-XX:+UseG1GC",                     # only GC selector in the list, no ZGC clash
    "-XX:+PerfDisableSharedMem",
    "-XX:InitiatingHeapOccupancyPercent=45",
    "-XX:G1ReservePercent=15",
    "-XX:MaxInlineLevel=15",
    "-XX:InlineSmallCode=2500",
    "-Djava.awt.headless=true",         # AWT on macOS steals the AppKit run loop -> black window
    "-Dzomboid.steam=1",
]
if java_major >= 17:
    args.append("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
if java_major >= 22:
    args.append("--enable-native-access=ALL-UNNAMED")
if java_major >= 24:
    args += ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders"]

def patch_options(holder, key):
    if key in holder and isinstance(holder[key], list):
        holder[key] = [a for a in holder[key] if not is_pzo_managed(a)] + args
        return True
    return False

patched = False
for holder in (pl, pl.get("Java") if isinstance(pl.get("Java"), dict) else {}):
    for key in ("JVMOptions", "VMOptions"):
        patched = patch_options(holder, key) or patched

if not patched:
    pl["JVMOptions"] = args
    print("[!] No existing JVMOptions array found - created one.")

with open(plist_path, "wb") as f:
    plistlib.dump(pl, f)
print("[+] Info.plist patched (Java %d profile, heap %sm, stock entrypoint preserved)." % (java_major, ram_mb))
PYEOF

if [ $? -ne 0 ]; then
    echo "[!] Patching failed - rolling back Info.plist."
    cp -f "${PLIST}.pzo-prev" "$PLIST"
    exit 1
fi

restore_debug_options
mkdir -p "$HOME/Zomboid/Lua" "$HOME/Zomboid/mods" "$HOME/Zomboid/db" "$HOME/Zomboid/Server"
printf '{"optimized":true,"ram_gb":%s,"g1gc":true,"pretouch":false,"version":"%s"}\n' "$ALLOC_RAM" "$PZO_VERSION" \
    > "$HOME/Zomboid/Lua/pzo_status.json"

resign_bundle

echo ""
echo "================================================================="
echo " Done. Heap: ${ALLOC_RAM}GB | Launch Project Zomboid from Steam."
echo " If anything misbehaves, run this script again and pick Uninstall."
echo "================================================================="
