#!/bin/bash
# ==============================================================================
# Project Zomboid Build 42 - Config & Engine Optimizer (macOS & Linux)
# ==============================================================================

set -e

echo "================================================================="
echo " Project Zomboid Build 42 Engine Optimizer (v0.9.8-unstable)"
echo " macOS & Linux Installation, Update & Recovery Utility"
echo "================================================================="

OS_TYPE="$(uname -s)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PZ_JAR=""

# 1. Locate or Auto-Download PZOptimEngine.jar
if [ -f "$SCRIPT_DIR/PZOptimEngine.jar" ]; then
    PZ_JAR="$SCRIPT_DIR/PZOptimEngine.jar"
elif [ -f "$SCRIPT_DIR/dist/PZOptimEngine.jar" ]; then
    PZ_JAR="$SCRIPT_DIR/dist/PZOptimEngine.jar"
elif [ -f "$SCRIPT_DIR/../dist/PZOptimEngine.jar" ]; then
    PZ_JAR="$SCRIPT_DIR/../dist/PZOptimEngine.jar"
elif [ -f "$PWD/PZOptimEngine.jar" ]; then
    PZ_JAR="$PWD/PZOptimEngine.jar"
fi

if [ -z "$PZ_JAR" ] || [ ! -f "$PZ_JAR" ]; then
    echo ""
    echo "[!] 'PZOptimEngine.jar' not found locally next to script."
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

echo "[+] Using engine package: $PZ_JAR"

# 2. Detect Total RAM in GB
TOTAL_RAM=8
if [ "$OS_TYPE" = "Darwin" ]; then
    RAM_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo 8589934592)
    TOTAL_RAM=$((RAM_BYTES / 1024 / 1024 / 1024))
else
    RAM_KB=$(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}' || echo 8388608)
    TOTAL_RAM=$((RAM_KB / 1024 / 1024))
fi

ALLOC_RAM=8
if [ "$TOTAL_RAM" -ge 32 ]; then
    ALLOC_RAM=12
elif [ "$TOTAL_RAM" -ge 16 ]; then
    ALLOC_RAM=8
elif [ "$TOTAL_RAM" -ge 8 ]; then
    ALLOC_RAM=6
else
    ALLOC_RAM=4
fi

RAM_MB=$((ALLOC_RAM * 1024))
echo "[+] Detected $TOTAL_RAM GB System RAM -> Allocating $ALLOC_RAM GB (-Xmx${RAM_MB}m)"

# Helper function to clean bridge files
clean_lua_bridge_files() {
    LUA_DIR="$HOME/Zomboid/Lua"
    if [ -d "$LUA_DIR" ]; then
        rm -f "$LUA_DIR"/pzo_* "$LUA_DIR/pzo_status.json" "$LUA_DIR/pzo_update.json" "$LUA_DIR/pzo_telemetry.json" "$LUA_DIR/pzo_engine.log"
        echo "[+] Purged all PZO bridge and telemetry files from ~/Zomboid/Lua/"
    fi
    rm -f "$HOME/Zomboid"/pzo_*
    DEBUG_OPT="$HOME/Zomboid/debug-options.ini"
    if [ -f "$DEBUG_OPT" ]; then
        sed -i.bak 's/FBORenderChunk.CorpsesInChunkTexture=true/FBORenderChunk.CorpsesInChunkTexture=false/g' "$DEBUG_OPT" 2>/dev/null || true
        sed -i.bak 's/FBORenderChunk.ItemsInChunkTexture=true/FBORenderChunk.ItemsInChunkTexture=false/g' "$DEBUG_OPT" 2>/dev/null || true
        sed -i.bak 's/Lighting.SplitUpdate=true/Lighting.SplitUpdate=false/g' "$DEBUG_OPT" 2>/dev/null || true
        sed -i.bak 's/Threading.Lighting=true/Threading.Lighting=false/g' "$DEBUG_OPT" 2>/dev/null || true
        rm -f "${DEBUG_OPT}.bak"
    fi
}

# ==============================================================================
# macOS Native .app Bundle Installation
# ==============================================================================
if [ "$OS_TYPE" = "Darwin" ]; then
    echo "[*] Platform: macOS"
    POSSIBLE_APP_PATHS=(
        "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app"
        "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/ProjectZomboid.app"
        "$HOME/Library/Application Support/Steam/steamapps/common/Project Zomboid/Project Zomboid.app"
        "$HOME/Library/Application Support/Steam/steamapps/common/Project Zomboid/ProjectZomboid.app"
    )

    APP_BUNDLE=""
    for p in "${POSSIBLE_APP_PATHS[@]}"; do
        if [ -d "$p" ]; then
            APP_BUNDLE="$p"
            break
        fi
    done

    if [ -z "$APP_BUNDLE" ]; then
        echo "[-] Could not automatically locate Project Zomboid.app."
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

    if [ "$MODE" = "uninstall" ]; then
        rm -f "$INSTALLED_JAR" "$JAVA_DIR/libpzo_native64.dylib" "$APP_BUNDLE/Contents/MacOS/libpzo_native64.dylib"
        if [ -f "${PLIST}.bak" ]; then
            cp -f "${PLIST}.bak" "$PLIST"
            echo "[+] Stock Info.plist restored."
        else
            echo "[!] No Info.plist backup found - verify game files through Steam."
        fi
        clean_lua_bridge_files
        resign_bundle
        echo "[+] Uninstall complete."
        exit 0
    fi

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
    for k in MAIN_KEYS:
        if k in container and "pzoptimizer" in str(container[k]).lower():
            container[k] = stock_container.get(k, "zombie/gameStates/MainScreenState")
            print("[+] Repaired %s -> %s" % (k, container[k]))

repair_main_class(pl, stock)
if isinstance(pl.get("Java"), dict):
    repair_main_class(pl["Java"], stock.get("Java", {}) if isinstance(stock.get("Java"), dict) else {})
if isinstance(pl.get("JVMOptions"), dict):
    repair_main_class(pl["JVMOptions"], {})

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
    "-javaagent:%s" % jar_abs,
    "-Xmx%sm" % ram_mb,
    "-XX:+UseG1GC",
    "-XX:+PerfDisableSharedMem",
    "-XX:InitiatingHeapOccupancyPercent=45",
    "-XX:G1ReservePercent=15",
    "-XX:MaxInlineLevel=15",
    "-XX:InlineSmallCode=2500",
    "-Djava.awt.headless=true",
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

    clean_lua_bridge_files
    mkdir -p "$HOME/Zomboid/Lua" "$HOME/Zomboid/mods" "$HOME/Zomboid/db" "$HOME/Zomboid/Server"
    printf '{"optimized":true,"ram_gb":%s,"g1gc":true,"pretouch":false,"version":"0.9.8-unstable"}\n' "$ALLOC_RAM" \
        > "$HOME/Zomboid/Lua/pzo_status.json"

    resign_bundle

# ==============================================================================
# Linux Steam Installation
# ==============================================================================
else
    echo "[*] Platform: Linux"

    POSSIBLE_PATHS=(
        "$HOME/.local/share/Steam/steamapps/common/ProjectZomboid"
        "$HOME/.steam/steam/steamapps/common/ProjectZomboid"
        "$HOME/.steam/root/steamapps/common/ProjectZomboid"
    )

    PZ_DIR=""
    for path in "${POSSIBLE_PATHS[@]}"; do
        if [ -d "$path" ]; then
            PZ_DIR="$path"
            break
        fi
    done

    if [ -z "$PZ_DIR" ]; then
        echo "[-] Could not automatically locate Project Zomboid."
        read -p "Please enter the full path to your Project Zomboid installation: " PZ_DIR
    fi

    if [ ! -d "$PZ_DIR" ]; then
        echo "[!] Error: Invalid directory: $PZ_DIR"
        exit 1
    fi

    echo "[+] Found Linux Project Zomboid at: $PZ_DIR"

    INSTALLED_JAR="$PZ_DIR/PZOptimEngine.jar"
    JSON_FILE="$PZ_DIR/ProjectZomboid64.json"

    # Existing Installation Check on Linux
    if [ -f "$INSTALLED_JAR" ]; then
        echo ""
        echo "[!] PZOptimEngine is already installed on Linux."
        echo "1) Update    - Overwrite PZOptimEngine.jar with the new version"
        echo "2) Uninstall - Remove the mod and restore original settings"
        echo "3) Cancel"
        read -p "Enter choice (1, 2, or 3): " MENU_CHOICE

        case "$MENU_CHOICE" in
            1)
                echo "[*] Updating PZOptimEngine.jar..."
                cp -f "$PZ_JAR" "$INSTALLED_JAR"
                echo "[+] Successfully updated PZOptimEngine.jar -> $INSTALLED_JAR"
                echo "[*] Refreshing native governor and ProjectZomboid64.json configuration..."
                ;;
            2)
                echo "[*] Uninstalling PZOptimEngine..."
                rm -f "$INSTALLED_JAR"
                if [ -f "${JSON_FILE}.bak" ]; then
                    cp -f "${JSON_FILE}.bak" "$JSON_FILE"
                    echo "[+] Restored original ProjectZomboid64.json from backup."
                fi
                if [ -f "$JSON_FILE" ]; then
                    sed -i.bak 's|"com/pzoptimizer/PZOEntrypoint"|"zombie/gameStates/MainScreenState"|g' "$JSON_FILE" 2>/dev/null || true
                    sed -i.bak '/PZOptimEngine.jar/d' "$JSON_FILE" 2>/dev/null || true
                    sed -i.bak '/pzo_native64/d' "$JSON_FILE" 2>/dev/null || true
                    rm -f "${JSON_FILE}.bak"
                fi
                clean_lua_bridge_files
                echo "[+] Uninstallation complete! Restored to stock settings."
                exit 0
                ;;
            *)
                echo "[-] Cancelled."
                exit 0
                ;;
        esac
    fi

    # Check for ZombieBuddy conflict on Linux
    if [ -f "$PZ_DIR/ZombieBuddy.jar" ] || [ -f "$PZ_DIR/zbNative.so" ] || [ -f "$PZ_DIR/zbNative.dylib" ] || [ -f "$PZ_DIR/zombiebuddy.json" ]; then
        echo ""
        echo "========================================================================"
        echo "[!] CONFLICT DETECTED: ZombieBuddy is currently installed"
        echo "========================================================================"
        echo "ZombieBuddy and PZO Optimizer both manage the main Java entrypoint."
        echo "PZO v0.8.0+ automatically runs your ZombieBuddy mods natively!"
        echo ""
        echo "[+] ZombieBuddy detected! Coexistence mode enabled." 
    fi

    # Check for Zed Better FPS NG conflict / redundancy
    if [ -d "$HOME/Zomboid/mods/ZBBetterFPSNG" ] || [ -d "$SCRIPT_DIR/../../workshop/content/108600/3793137588" ]; then
        echo ""
        echo "[!] NOTICE: Zed Better FPS NG detected in Workshop/Mods!"
        echo "    PZO natively includes hardware-level AVX2 culling, multi-core chunk streaming,"
        echo "    and kernel thread scheduling that outperforms and replaces Java-level FPS mods."
        echo "    ZombieBuddy itself and all other gameplay/Lua mods remain 100% compatible."
        echo "    We recommend disabling Zed Better FPS NG to prevent duplicate hook overhead."
        echo ""
    fi

    # Copy JAR
    cp -f "$PZ_JAR" "$INSTALLED_JAR"
    echo "[+] Installed PZOptimEngine.jar -> $INSTALLED_JAR"

    # Install libpzo_native64.so if present
    for so_c in "$SCRIPT_DIR/libpzo_native64.so" "$SCRIPT_DIR/dist/libpzo_native64.so" "$SCRIPT_DIR/../dist/libpzo_native64.so" "$SCRIPT_DIR/native/libpzo_native64.so"; do
        if [ -f "$so_c" ]; then
            cp -f "$so_c" "$PZ_DIR/libpzo_native64.so"
            if [ -d "$PZ_DIR/linux64" ]; then
                cp -f "$so_c" "$PZ_DIR/linux64/libpzo_native64.so"
            fi
            echo "[+] Installed native companion: libpzo_native64.so"
            break
        fi
    done

    if [ -f "$JSON_FILE" ] && [ ! -f "${JSON_FILE}.bak" ]; then
        cp -f "$JSON_FILE" "${JSON_FILE}.bak"
        echo "[+] Backed up original JSON config -> ${JSON_FILE}.bak"
    fi

    # Safely update JSON while preserving existing classpath and libraries using Python
    python3 - << 'EOF' "$JSON_FILE" "$ALLOC_RAM" "$RAM_MB"
import sys, json, os

json_file = sys.argv[1]
alloc_ram = sys.argv[2]
ram_mb = sys.argv[3]

data = {}
if os.path.exists(json_file):
    try:
        with open(json_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception:
        data = {}

# Set optimized main entrypoint
data["mainClass"] = "com/pzoptimizer/PZOEntrypoint"

# Preserve existing classpath and ensure PZOptimEngine.jar is present
cp = data.get("classpath", [])
if not isinstance(cp, list):
    cp = []
if "PZOptimEngine.jar" not in cp:
    cp.insert(0, "PZOptimEngine.jar")
if "." not in cp:
    cp.insert(0, ".")
data["classpath"] = cp

# Filter and update vmArgs
existing_args = data.get("vmArgs", [])
if not isinstance(existing_args, list):
    existing_args = []

filtered_args = []
has_lib_path = False
for arg in existing_args:
    if (not arg.startswith("-Xmx") and 
        not arg.startswith("-Xms") and 
        not arg.startswith("-XX:+UseG1GC") and 
        not arg.startswith("-XX:+AlwaysPreTouch") and
        not arg.startswith("-XX:InitiatingHeapOccupancyPercent") and
        not arg.startswith("-XX:G1ReservePercent") and
        not arg.startswith("-XX:+PerfDisableSharedMem")):
        
        # Automatically sanitize Windows library path if running on Linux
        if arg.startswith("-Djava.library.path="):
            if "win64" in arg:
                arg = "-Djava.library.path=linux64/:natives/:."
            has_lib_path = True
        filtered_args.append(arg)

if not has_lib_path:
    filtered_args.append("-Djava.library.path=linux64/:natives/:.")

pzo_args = [
    "-agentlib:pzo_native64",
    f"-Xmx{ram_mb}m",
    "-XX:+UseG1GC",
    "-XX:+PerfDisableSharedMem",
    "-XX:InitiatingHeapOccupancyPercent=45",
    "-XX:G1ReservePercent=15",
    "-XX:+AlwaysPreTouch",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseCompactObjectHeaders",
    "-XX:+UseSuperWord",
    "-XX:MaxInlineLevel=15",
    "-XX:InlineSmallCode=2500",
    "-XX:+UseNUMA",
    "--enable-native-access=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
]

data["vmArgs"] = filtered_args + pzo_args

with open(json_file, 'w', encoding='utf-8') as f:
    json.dump(data, f, indent=4)

print("[+] Successfully updated ProjectZomboid64.json preserving all game libraries.")
EOF
    echo "[+] Updated ProjectZomboid64.json with B42 heap & entrypoint."
    mkdir -p "$HOME/Zomboid/Lua"
    echo "{\"optimized\":true,\"ram_gb\":$ALLOC_RAM,\"g1gc\":true,\"pretouch\":true,\"version\":\"0.9.8-unstable\"}" > "$HOME/Zomboid/Lua/pzo_status.json"
    echo "[+] Generated Lua bridge status: $HOME/Zomboid/Lua/pzo_status.json"
fi

echo ""
echo "================================================================="
echo " [SUCCESS] Project Zomboid Build 42 is Optimized & Ready!"
echo " Allocated Heap: $ALLOC_RAM GB ($RAM_MB MB)"
echo " Simply launch Project Zomboid normally through Steam."
echo "================================================================="
