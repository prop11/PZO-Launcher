#!/bin/bash
# PZO installer for macOS and Linux

set -e

echo "================================================================="
echo " Project Zomboid Build 42 Engine Optimizer (v1.5.9)"
echo " macOS & Linux Installation, Update & Recovery Utility"
echo "================================================================="

OS_TYPE="$(uname -s)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PZ_JAR=""

# If running without a terminal in Linux desktop environment (e.g. Steam Deck Dolphin double-click),
# relaunch in Konsole/terminal so user can see output and interact with prompts.
if [ "$OS_TYPE" != "Darwin" ] && [ ! -t 0 ] && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
    if command -v konsole >/dev/null 2>&1; then
        exec konsole -e bash "$0" "$@"
    elif command -v xterm >/dev/null 2>&1; then
        exec xterm -e bash "$0" "$@"
    fi
fi

# Steam Deck / sudo fallback: if run under sudo, map HOME back to original user
if [ "$EUID" -eq 0 ] && [ -n "$SUDO_USER" ] && [ "$SUDO_USER" != "root" ]; then
    USER_HOME=$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6 || eval echo "~$SUDO_USER")
    if [ -n "$USER_HOME" ] && [ -d "$USER_HOME" ]; then
        echo "[*] Notice: Running under sudo for user '$SUDO_USER'."
        echo "    Aligning target environment to user home: $USER_HOME"
        HOME="$USER_HOME"
    fi
fi

# Locate or download PZOptimEngine.jar
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

# Total RAM in GB
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
    # Also clean Proton prefix bridge files
    for pfx in "$HOME/.local/share/Steam/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid" \
               "$HOME/.steam/steam/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid" \
               "$HOME/.steam/root/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid" \
               "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid" \
               "/run/media/mmcblk0p1/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid" \
               "/run/media/deck"/*/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid \
               "/run/media"/*/*/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid \
               "/media"/*/*/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid; do
        if [ -d "$pfx/Lua" ]; then
            rm -f "$pfx/Lua"/pzo_* "$pfx/Lua/pzo_status.json" "$pfx/Lua/pzo_update.json" "$pfx/Lua/pzo_telemetry.json" "$pfx/Lua/pzo_engine.log"
            rm -f "$pfx"/pzo_*
            echo "[+] Purged PZO bridge files from Proton prefix: $pfx/Lua/"
        fi
    done
}

# macOS: install into the .app bundle
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
    printf '{"optimized":true,"ram_gb":%s,"g1gc":true,"pretouch":false,"version":"1.5.9"}\n' "$ALLOC_RAM" \
        > "$HOME/Zomboid/Lua/pzo_status.json"

    resign_bundle

# Linux Steam installation
else
    echo "[*] Platform: Linux"

    POSSIBLE_PATHS=(
        "$HOME/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid"
        "$HOME/.local/share/Steam/steamapps/common/ProjectZomboid"
        "$HOME/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid"
        "$HOME/.steam/steam/steamapps/common/ProjectZomboid"
        "$HOME/.steam/root/steamapps/common/ProjectZomboid/projectzomboid"
        "$HOME/.steam/root/steamapps/common/ProjectZomboid"
        "/run/media/mmcblk0p1/steamapps/common/ProjectZomboid/projectzomboid"
        "/run/media/mmcblk0p1/steamapps/common/ProjectZomboid"
        "/run/media/deck"/*/steamapps/common/ProjectZomboid/projectzomboid
        "/run/media/deck"/*/steamapps/common/ProjectZomboid
        "/run/media"/*/*/steamapps/common/ProjectZomboid/projectzomboid
        "/run/media"/*/*/steamapps/common/ProjectZomboid
        "/media"/*/*/steamapps/common/ProjectZomboid/projectzomboid
        "/media"/*/*/steamapps/common/ProjectZomboid
    )

    for vdf in "$HOME/.local/share/Steam/steamapps/libraryfolders.vdf" \
               "$HOME/.steam/root/steamapps/libraryfolders.vdf" \
               "$HOME/.steam/steam/steamapps/libraryfolders.vdf" \
               "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/libraryfolders.vdf"; do
        if [ -f "$vdf" ]; then
            while IFS= read -r lib; do
                POSSIBLE_PATHS+=(
                    "$lib/steamapps/common/ProjectZomboid/projectzomboid"
                    "$lib/steamapps/common/ProjectZomboid"
                )
            done < <(sed -n 's/^[[:space:]]*"path"[[:space:]]*"\(.*\)"/\1/p' "$vdf")
        fi
    done

    PZ_DIR=""
    for path in "${POSSIBLE_PATHS[@]}"; do
        if [ -d "$path" ]; then
            if [ -f "$path/ProjectZomboid64.json" ] || [ -f "$path/projectzomboid.jar" ]; then
                PZ_DIR="$path"
                break
            elif [ -d "$path/projectzomboid" ] && { [ -f "$path/projectzomboid/ProjectZomboid64.json" ] || [ -f "$path/projectzomboid/projectzomboid.jar" ]; }; then
                PZ_DIR="$path/projectzomboid"
                break
            fi
        fi
    done

    if [ -z "$PZ_DIR" ]; then
        for path in "${POSSIBLE_PATHS[@]}"; do
            if [ -d "$path" ]; then
                if [ -d "$path/projectzomboid" ]; then
                    PZ_DIR="$path/projectzomboid"
                else
                    PZ_DIR="$path"
                fi
                break
            fi
        done
    fi

    if [ -z "$PZ_DIR" ]; then
        echo "[-] Could not automatically locate Project Zomboid."
        read -p "Please enter the full path to your Project Zomboid installation: " PZ_DIR
    fi

    if [ -d "$PZ_DIR/projectzomboid" ] && { [ -f "$PZ_DIR/projectzomboid/ProjectZomboid64.json" ] || [ -f "$PZ_DIR/projectzomboid/projectzomboid.jar" ]; }; then
        PZ_DIR="$PZ_DIR/projectzomboid"
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
                rm -f "$INSTALLED_JAR" "$PZ_DIR/pzo_native64.dll" "$PZ_DIR/win64/pzo_native64.dll" "$PZ_DIR/libpzo_native64.so" "$PZ_DIR/linux64/libpzo_native64.so" "$PZ_DIR/natives/libpzo_native64.so"
                if [ "$(basename "$PZ_DIR")" = "projectzomboid" ]; then
                    PARENT_DIR="$(dirname "$PZ_DIR")"
                    rm -f "$PARENT_DIR/PZOptimEngine.jar" "$PARENT_DIR/pzo_native64.dll" "$PARENT_DIR/win64/pzo_native64.dll" "$PARENT_DIR/libpzo_native64.so" "$PARENT_DIR/linux64/libpzo_native64.so"
                fi
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

    # Only the Windows depot ships ProjectZomboid64.exe. A win64/ folder is no evidence: older PZO
    # installers created one on native Linux too, which flipped later runs into Proton mode.
    IS_PROTON=0
    if [ -f "$PZ_DIR/ProjectZomboid64.exe" ] || [ -f "$(dirname "$PZ_DIR")/ProjectZomboid64.exe" ]; then
        IS_PROTON=1
        echo "[+] Environment Detected: Windows Depot under Steam Deck Proton / Wine"
        echo "    Using Windows binaries (pzo_native64.dll) and win64/ library paths."
    else
        echo "[+] Environment Detected: Native Linux"
        echo "    Using Linux binaries (libpzo_native64.so) and linux64/ library paths."
        # Remove the Windows companion earlier installers dropped here
        CLEAN_DIRS=("$PZ_DIR")
        [ "$(basename "$PZ_DIR")" = "projectzomboid" ] && CLEAN_DIRS+=("$(dirname "$PZ_DIR")")
        for c_dir in "${CLEAN_DIRS[@]}"; do
            if [ -f "$c_dir/win64/pzo_native64.dll" ] || [ -f "$c_dir/pzo_native64.dll" ]; then
                rm -f "$c_dir/win64/pzo_native64.dll" "$c_dir/pzo_native64.dll"
                rmdir "$c_dir/win64" 2>/dev/null || true
                echo "[+] Removed stray Windows native companion from $c_dir"
            fi
        done
    fi

    # Check for ZombieBuddy conflict / coexistence
    IS_ZB_ACTIVE=0
    if [ -f "$PZ_DIR/ZombieBuddy.jar" ] || [ -f "$PZ_DIR/win64/zbNative.dll" ] || [ -f "$PZ_DIR/zbNative.dll" ] || [ -f "$PZ_DIR/zbNative.so" ] || [ -f "$PZ_DIR/zbNative.dylib" ] || [ -f "$PZ_DIR/zombiebuddy.json" ] || [ -f "$(dirname "$PZ_DIR")/ZombieBuddy.jar" ]; then
        IS_ZB_ACTIVE=1
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

    # Companion native libraries: pzo_native64.dll for Windows / Proton depots
    DLL_SRC=""
    for d_c in "$SCRIPT_DIR/pzo_native64.dll" "$SCRIPT_DIR/dist/pzo_native64.dll" "$SCRIPT_DIR/../dist/pzo_native64.dll" "$SCRIPT_DIR/native/pzo_native64.dll" "$PWD/pzo_native64.dll"; do
        if [ -f "$d_c" ]; then
            DLL_SRC="$d_c"
            break
        fi
    done

    if [ -z "$DLL_SRC" ] && [ "$IS_PROTON" -eq 1 ]; then
        echo "[*] Downloading pzo_native64.dll from GitHub Releases for Proton..."
        TARGET_DLL="$SCRIPT_DIR/pzo_native64.dll"
        DLL_URL="https://github.com/prop11/PZO-Launcher/releases/latest/download/pzo_native64.dll"
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL -A "PZO-Installer" "$DLL_URL" -o "$TARGET_DLL" || true
        elif command -v wget >/dev/null 2>&1; then
            wget -q -U "PZO-Installer" "$DLL_URL" -O "$TARGET_DLL" || true
        fi
        [ -f "$TARGET_DLL" ] && DLL_SRC="$TARGET_DLL"
    fi

    if [ "$IS_PROTON" -eq 1 ] && [ -n "$DLL_SRC" ]; then
        cp -f "$DLL_SRC" "$PZ_DIR/pzo_native64.dll"
        mkdir -p "$PZ_DIR/win64" 2>/dev/null || true
        cp -f "$DLL_SRC" "$PZ_DIR/win64/pzo_native64.dll" 2>/dev/null || true
        if [ "$(basename "$PZ_DIR")" = "projectzomboid" ]; then
            cp -f "$DLL_SRC" "$(dirname "$PZ_DIR")/pzo_native64.dll" 2>/dev/null || true
            mkdir -p "$(dirname "$PZ_DIR")/win64" 2>/dev/null || true
            cp -f "$DLL_SRC" "$(dirname "$PZ_DIR")/win64/pzo_native64.dll" 2>/dev/null || true
        fi
        echo "[+] Installed Windows / Proton native companion: pzo_native64.dll"
    fi

    # libpzo_native64.so for native Linux
    SO_SRC=""
    for so_c in "$SCRIPT_DIR/libpzo_native64.so" "$SCRIPT_DIR/dist/libpzo_native64.so" "$SCRIPT_DIR/../dist/libpzo_native64.so" "$SCRIPT_DIR/native/libpzo_native64.so" "$PWD/libpzo_native64.so"; do
        if [ -f "$so_c" ]; then
            SO_SRC="$so_c"
            break
        fi
    done

    if [ -z "$SO_SRC" ] && [ "$IS_PROTON" -eq 0 ]; then
        echo "[*] Downloading libpzo_native64.so from GitHub Releases for Linux..."
        TARGET_SO="$SCRIPT_DIR/libpzo_native64.so"
        SO_URL="https://github.com/prop11/PZO-Launcher/releases/latest/download/libpzo_native64.so"
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL -A "PZO-Installer" "$SO_URL" -o "$TARGET_SO" || true
        elif command -v wget >/dev/null 2>&1; then
            wget -q -U "PZO-Installer" "$SO_URL" -O "$TARGET_SO" || true
        fi
        [ -f "$TARGET_SO" ] && SO_SRC="$TARGET_SO"
    fi

    if [ "$IS_PROTON" -eq 0 ] && [ -n "$SO_SRC" ]; then
        cp -f "$SO_SRC" "$PZ_DIR/libpzo_native64.so"
        mkdir -p "$PZ_DIR/linux64" "$PZ_DIR/natives" 2>/dev/null || true
        cp -f "$SO_SRC" "$PZ_DIR/linux64/libpzo_native64.so" 2>/dev/null || true
        cp -f "$SO_SRC" "$PZ_DIR/natives/libpzo_native64.so" 2>/dev/null || true
        if [ "$(basename "$PZ_DIR")" = "projectzomboid" ]; then
            cp -f "$SO_SRC" "$(dirname "$PZ_DIR")/libpzo_native64.so" 2>/dev/null || true
            mkdir -p "$(dirname "$PZ_DIR")/linux64" 2>/dev/null || true
            cp -f "$SO_SRC" "$(dirname "$PZ_DIR")/linux64/libpzo_native64.so" 2>/dev/null || true
        fi
        echo "[+] Installed Linux native companion: libpzo_native64.so"
    fi

    if [ "$(basename "$PZ_DIR")" = "projectzomboid" ]; then
        PARENT_DIR="$(dirname "$PZ_DIR")"
        if [ -f "$PARENT_DIR/ProjectZomboid64.json" ] || [ -f "$PARENT_DIR/projectzomboid.sh" ] || [ -f "$PARENT_DIR/ProjectZomboid64.exe" ]; then
            cp -f "$PZ_JAR" "$PARENT_DIR/PZOptimEngine.jar" 2>/dev/null || true
            echo "[+] Mirrored PZOptimEngine.jar to parent Steam directory: $PARENT_DIR"
        fi
    fi

    if [ -f "$JSON_FILE" ] && [ ! -f "${JSON_FILE}.bak" ]; then
        cp -f "$JSON_FILE" "${JSON_FILE}.bak"
        echo "[+] Backed up original JSON config -> ${JSON_FILE}.bak"
    fi

    # Safely update JSON while preserving existing classpath and libraries using Python
    python3 - << 'EOF' "$JSON_FILE" "$ALLOC_RAM" "$RAM_MB" "$IS_PROTON" "$IS_ZB_ACTIVE"
import sys, json, os

json_file = sys.argv[1]
alloc_ram = int(sys.argv[2])
ram_mb = int(sys.argv[3])
is_proton = (len(sys.argv) > 4 and sys.argv[4] == "1")
is_zb_active = (len(sys.argv) > 5 and sys.argv[5] == "1")

data = {}
if os.path.exists(json_file):
    try:
        with open(json_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception:
        data = {}

# Set optimized main entrypoint
data["mainClass"] = "com/pzoptimizer/PZOEntrypoint"

# Preserve existing classpath and ensure PZOptimEngine.jar and projectzomboid.jar are present
cp = data.get("classpath", [])
if not isinstance(cp, list):
    cp = []
if "." not in cp:
    cp.insert(0, ".")
if "PZOptimEngine.jar" not in cp:
    dot_idx = cp.index(".")
    cp.insert(dot_idx + 1, "PZOptimEngine.jar")
if is_zb_active and "ZombieBuddy.jar" not in cp:
    pzo_idx = cp.index("PZOptimEngine.jar")
    cp.insert(pzo_idx + 1, "ZombieBuddy.jar")
if "projectzomboid.jar" not in cp:
    cp.append("projectzomboid.jar")
data["classpath"] = cp

# Filter and update vmArgs
existing_args = data.get("vmArgs", [])
if not isinstance(existing_args, list):
    existing_args = []

DROP_PREFIXES = (
    "-Xmx", "-Xms",
    "-agentlib:pzo_native64", "-agentlib:zbNative",
    "-XX:+UseZGC", "-XX:-UseZGC", "-XX:+ZGenerational", "-XX:-ZGenerational", "-XX:ZCollection", "-XX:ZAllocationSpikeTolerance",
    "-XX:+UseG1GC", "-XX:-UseG1GC", "-XX:+UseParallelGC", "-XX:+UseSerialGC", "-XX:+UseShenandoahGC",
    "-XX:+AlwaysPreTouch", "-XX:-AlwaysPreTouch",
    "-XX:+UseNUMA", "-XX:-UseNUMA",
    "-XX:+UseSuperWord", "-XX:-UseSuperWord",
    "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders",
    "-XX:+PerfDisableSharedMem",
    "-XX:InitiatingHeapOccupancyPercent",
    "-XX:G1ReservePercent",
    "-XX:MaxInlineLevel",
    "-XX:InlineSmallCode",
    "--enable-native-access",
    "--add-exports=java.base/jdk.internal.misc",
    "-Djava.awt.headless",
    "-Dzomboid.steam",
    "-Dzomboid.znetlog",
    "-XX:-CreateCoredumpOnCrash",
    "-XX:-OmitStackTraceInFastThrow",
)

filtered_args = []
has_lib_path = False
for arg in existing_args:
    arg_str = str(arg).strip()
    if not arg_str:
        continue
    if any(arg_str.startswith(p) for p in DROP_PREFIXES):
        continue
    if "pzo_native64" in arg_str or "zbNative" in arg_str:
        continue

    # Library path handling
    if arg_str.startswith("-Djava.library.path="):
        if is_proton:
            if "win64" not in arg_str:
                arg_str = "-Djava.library.path=win64/;natives/;."
        else:
            # ';' is the Windows separator; on Linux the JVM would read the whole value as one folder
            if "win64" in arg_str or ";" in arg_str:
                arg_str = "-Djava.library.path=linux64/:natives/:."
        has_lib_path = True
    filtered_args.append(arg_str)

if not has_lib_path:
    if is_proton:
        filtered_args.append("-Djava.library.path=win64/;natives/;.")
    else:
        filtered_args.append("-Djava.library.path=linux64/:natives/:.")

# Build pzo_args
pzo_args = []
# A ZombieBuddy jar/json alone does not mean its agent library exists for this platform;
# -agentlib:zbNative with no loadable library makes the JVM refuse to start (issue #40)
game_dir = os.path.dirname(os.path.abspath(json_file))
if is_proton:
    zb_agent_names = ["zbNative.dll"]
else:
    zb_agent_names = ["libzbNative.so", "libzbNative.dylib"]
zb_agent_found = any(
    os.path.isfile(os.path.join(game_dir, d, n))
    for d in ("win64" if is_proton else "linux64", "natives", ".")
    for n in zb_agent_names
)
if is_zb_active and zb_agent_found:
    pzo_args.append("-agentlib:zbNative")
pzo_args.append("-agentlib:pzo_native64")

if is_proton:
    pzo_args.extend([
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        f"-Xms{min(4096, ram_mb // 2)}m",
        f"-Xmx{ram_mb}m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseSuperWord",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2500",
        "-XX:+UseNUMA",
        "-XX:+AlwaysPreTouch"
    ])
    # Platform sub-blocks for Windows 6.1 and 10.0 (sanitizing any duplicate agentlib entries)
    data["windows"] = {
        "6.1": {
            "vmArgs": [
                "-XX:+UseG1GC"
            ]
        },
        "10.0.17134": {
            "vmArgs": [
                "-XX:+UseG1GC",
                "-XX:InitiatingHeapOccupancyPercent=45",
                "-XX:G1ReservePercent=15",
                "-XX:+UseStringDeduplication"
            ]
        }
    }
else:
    # Native Linux. The vanilla flags stripped by DROP_PREFIXES are re-added; without zomboid.steam the
    # game skips Steam init and the Workshop mod list comes up empty
    pzo_args.extend([
        f"-Xmx{ram_mb}m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+UseG1GC",
        "-XX:+PerfDisableSharedMem",
        "-XX:InitiatingHeapOccupancyPercent=45",
        "-XX:G1ReservePercent=15",
        "-XX:+AlwaysPreTouch",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseSuperWord",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2500",
        "-XX:+UseNUMA",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
    ])
    if "windows" in data:
        del data["windows"]

data["vmArgs"] = filtered_args + pzo_args

with open(json_file, 'w', encoding='utf-8') as f:
    json.dump(data, f, indent=4)

if is_proton:
    print("[+] Successfully configured ProjectZomboid64.json for Windows Depot (Steam Deck Proton / Wine).")
else:
    print("[+] Successfully configured ProjectZomboid64.json for Native Linux.")
EOF
    echo "[+] Updated ProjectZomboid64.json with B42 heap & entrypoint."

    if [ "$(basename "$PZ_DIR")" = "projectzomboid" ]; then
        PARENT_DIR="$(dirname "$PZ_DIR")"
        if [ -f "$PARENT_DIR/ProjectZomboid64.json" ]; then
            cp -f "$JSON_FILE" "$PARENT_DIR/ProjectZomboid64.json" 2>/dev/null || true
            echo "[+] Synced ProjectZomboid64.json to parent Steam directory: $PARENT_DIR"
        fi
    fi
    if [ -d "$PZ_DIR/projectzomboid" ]; then
        cp -f "$PZ_JAR" "$PZ_DIR/projectzomboid/PZOptimEngine.jar" 2>/dev/null || true
        cp -f "$JSON_FILE" "$PZ_DIR/projectzomboid/ProjectZomboid64.json" 2>/dev/null || true
        if [ "$IS_PROTON" -eq 1 ]; then
            mkdir -p "$PZ_DIR/projectzomboid/win64" 2>/dev/null || true
            [ -n "$DLL_SRC" ] && cp -f "$DLL_SRC" "$PZ_DIR/projectzomboid/pzo_native64.dll" 2>/dev/null || true
            [ -n "$DLL_SRC" ] && cp -f "$DLL_SRC" "$PZ_DIR/projectzomboid/win64/pzo_native64.dll" 2>/dev/null || true
        else
            mkdir -p "$PZ_DIR/projectzomboid/linux64" "$PZ_DIR/projectzomboid/natives" 2>/dev/null || true
            [ -n "$SO_SRC" ] && cp -f "$SO_SRC" "$PZ_DIR/projectzomboid/libpzo_native64.so" 2>/dev/null || true
            [ -n "$SO_SRC" ] && cp -f "$SO_SRC" "$PZ_DIR/projectzomboid/linux64/libpzo_native64.so" 2>/dev/null || true
            [ -n "$SO_SRC" ] && cp -f "$SO_SRC" "$PZ_DIR/projectzomboid/natives/libpzo_native64.so" 2>/dev/null || true
        fi
        echo "[+] Synced engine and configs to projectzomboid subfolder."
    fi
    mkdir -p "$HOME/Zomboid/Lua"
    echo "{\"optimized\":true,\"ram_gb\":$ALLOC_RAM,\"g1gc\":true,\"pretouch\":true,\"version\":\"1.5.9\"}" > "$HOME/Zomboid/Lua/pzo_status.json"
    echo "[+] Generated Lua bridge status: $HOME/Zomboid/Lua/pzo_status.json"

    # Also sync to Proton prefixes across all drives & SD cards on Steam Deck
    PROTON_CANDIDATES=(
        "$HOME/.local/share/Steam/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid"
        "$HOME/.steam/steam/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid"
        "$HOME/.steam/root/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid"
        "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid"
        "/run/media/mmcblk0p1/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid"
        "/run/media/deck"/*/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid
        "/run/media"/*/*/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid
        "/media"/*/*/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid
    )
    for vdf in "$HOME/.local/share/Steam/steamapps/libraryfolders.vdf" \
               "$HOME/.steam/root/steamapps/libraryfolders.vdf" \
               "$HOME/.steam/steam/steamapps/libraryfolders.vdf" \
               "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/libraryfolders.vdf"; do
        if [ -f "$vdf" ]; then
            while IFS= read -r lib; do
                PROTON_CANDIDATES+=(
                    "$lib/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid"
                )
            done < <(sed -n 's/^[[:space:]]*"path"[[:space:]]*"\(.*\)"/\1/p' "$vdf")
        fi
    done
    for pfx in "${PROTON_CANDIDATES[@]}"; do
        if [ -d "$pfx" ] || [ -d "$(dirname "$pfx")" ]; then
            mkdir -p "$pfx/Lua" 2>/dev/null || true
            cp -f "$HOME/Zomboid/Lua/pzo_status.json" "$pfx/Lua/pzo_status.json" 2>/dev/null || true
            echo "[+] Synced Lua bridge status to Proton prefix: $pfx/Lua/pzo_status.json"
        fi
    done

    # Restore correct ownership if executed under sudo on Linux / Steam Deck
    if [ -n "$SUDO_USER" ] && [ "$SUDO_USER" != "root" ]; then
        chown -R "$SUDO_USER:$SUDO_USER" "$HOME/Zomboid" "$PZ_DIR/PZOptimEngine.jar" "$PZ_DIR/ProjectZomboid64.json"* "$PZ_DIR/libpzo_native64.so"* "$PZ_DIR/pzo_native64.dll"* 2>/dev/null || true
    fi
fi

echo ""
echo "================================================================="
echo " [SUCCESS] Project Zomboid Build 42 is Optimized & Ready!"
echo " Allocated Heap: $ALLOC_RAM GB ($RAM_MB MB)"
echo " Simply launch Project Zomboid normally through Steam."
echo "================================================================="

if [ -t 0 ]; then
    echo ""
    read -r -p "Press [Enter] to exit..." dummy 2>/dev/null || true
fi
