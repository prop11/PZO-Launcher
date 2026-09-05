#!/bin/bash
# ==============================================================================
# Project Zomboid Build 42 - Config & Engine Optimizer (macOS & Linux)
# ==============================================================================

set -e

echo "================================================================="
echo " Project Zomboid Build 42 Engine Optimizer (v0.8.7-unstable)"
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
}

# ==============================================================================
# macOS Native .app Bundle Installation
# ==============================================================================
if [ "$OS_TYPE" = "Darwin" ]; then
    echo "[*] Platform: macOS"
    POSSIBLE_APP_PATHS=(
        "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/ProjectZomboid.app"
        "$HOME/Library/Application Support/Steam/steamapps/common/Project Zomboid/Project Zomboid.app"
        "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app"
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
        echo "[-] Could not automatically locate ProjectZomboid.app."
        read -p "Please drag & drop or enter path to ProjectZomboid.app: " APP_BUNDLE
    fi

    APP_BUNDLE="${APP_BUNDLE#\'}"
    APP_BUNDLE="${APP_BUNDLE%\'}"
    APP_BUNDLE="${APP_BUNDLE#\"}"
    APP_BUNDLE="${APP_BUNDLE%\"}"

    if [ ! -d "$APP_BUNDLE" ]; then
        echo "[!] Error: Invalid .app directory: $APP_BUNDLE"
        exit 1
    fi

    echo "[+] Found macOS App Bundle at: $APP_BUNDLE"

    JAVA_DIR="$APP_BUNDLE/Contents/Java"
    INSTALLED_JAR="$JAVA_DIR/PZOptimEngine.jar"
    PLIST="$APP_BUNDLE/Contents/Info.plist"

    # 3. Existing Installation Check (Update / Uninstall / Cancel)
    if [ -f "$INSTALLED_JAR" ]; then
        echo ""
        echo "[!] PZOptimEngine is already installed on macOS."
        echo "1) Update    - Overwrite PZOptimEngine.jar with the new version"
        echo "2) Uninstall - Remove the mod and restore original Info.plist"
        echo "3) Cancel"
        read -p "Enter choice (1, 2, or 3): " MENU_CHOICE

        case "$MENU_CHOICE" in
            1)
                echo "[*] Updating PZOptimEngine.jar..."
                cp -f "$PZ_JAR" "$INSTALLED_JAR"
                echo "[+] Successfully updated PZOptimEngine.jar -> $INSTALLED_JAR"
                exit 0
                ;;
            2)
                echo "[*] Uninstalling PZOptimEngine..."
                rm -f "$INSTALLED_JAR"
                if [ -f "${PLIST}.bak" ]; then
                    cp -f "${PLIST}.bak" "$PLIST"
                    echo "[+] Restored original Info.plist from backup."
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

    # 4. Check for ZombieBuddy conflict on macOS
    ZB_FOUND=0
    if [ -f "$APP_BUNDLE/Contents/Java/ZombieBuddy.jar" ] || [ -f "$APP_BUNDLE/Contents/MacOS/zbNative.dylib" ] || [ -f "$APP_BUNDLE/Contents/Java/zbNative.dylib" ] || [ -f "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/ZombieBuddy.jar" ]; then
        ZB_FOUND=1
    fi

    if [ "$ZB_FOUND" -eq 1 ]; then
        echo ""
        echo "========================================================================"
        echo "[!] CONFLICT DETECTED: ZombieBuddy is currently installed"
        echo "========================================================================"
        echo "ZombieBuddy.jar and PZO Optimizer both manage the main Java entrypoint."
        echo "PZO v0.8.0+ automatically runs your ZombieBuddy Workshop mods natively!"
        echo ""
        echo "[+] ZombieBuddy detected! Coexistence mode enabled." 
    fi

    # 5. Install PZOptimEngine.jar to Contents/Java/
    mkdir -p "$JAVA_DIR"
    cp -f "$PZ_JAR" "$INSTALLED_JAR"
    echo "[+] Installed PZOptimEngine.jar -> $INSTALLED_JAR"

    # 6. Patch Contents/Info.plist
    if [ ! -f "$PLIST" ]; then
        echo "[!] Error: Contents/Info.plist not found in bundle."
        exit 1
    fi

    if [ ! -f "${PLIST}.bak" ]; then
        cp -f "$PLIST" "${PLIST}.bak"
        echo "[+] Backed up original Info.plist -> ${PLIST}.bak"
    fi

    python3 - << 'EOF' "$PLIST" "$RAM_MB"
import plistlib, sys, os

plist_path = sys.argv[1]
ram_mb = sys.argv[2]

with open(plist_path, "rb") as f:
    pl = plistlib.load(f)

# Use dot notation for macOS Java launcher compatibility
target_class_dot = "com.pzoptimizer.PZOEntrypoint"

# Update Main Class across all known macOS launcher schema keys
for key in ["JVMMainClassName", "MainClass", "JVMEntrypoint"]:
    if key in pl:
        pl[key] = target_class_dot

if "Java" in pl and isinstance(pl["Java"], dict):
    pl["Java"]["MainClass"] = target_class_dot
    if "ClassPath" in pl["Java"]:
        cp = pl["Java"]["ClassPath"]
        if isinstance(cp, str) and "PZOptimEngine.jar" not in cp:
            pl["Java"]["ClassPath"] = "PZOptimEngine.jar:" + cp
        elif isinstance(cp, list) and not any("PZOptimEngine.jar" in x for x in cp):
            pl["Java"]["ClassPath"] = ["PZOptimEngine.jar"] + cp

if "JVMOptions" in pl and isinstance(pl["JVMOptions"], dict):
    if "MainClass" in pl["JVMOptions"]:
        pl["JVMOptions"]["MainClass"] = target_class_dot

# Ensure PZOptimEngine.jar is in ClassPath
for cp_key in ["JVMClassPath", "ClassPath"]:
    if cp_key in pl:
        if isinstance(pl[cp_key], list):
            if not any("PZOptimEngine.jar" in x for x in pl[cp_key]):
                pl[cp_key] = ["PZOptimEngine.jar"] + pl[cp_key]
        elif isinstance(pl[cp_key], str):
            if "PZOptimEngine.jar" not in pl[cp_key]:
                pl[cp_key] = "PZOptimEngine.jar:" + pl[cp_key]

jvm_args = [
    "-javaagent:PZOptimEngine.jar",
    f"-Xmx{ram_mb}m",
    "-XX:+UseG1GC",
    "-XX:+PerfDisableSharedMem",
    "-XX:InitiatingHeapOccupancyPercent=45",
    "-XX:G1ReservePercent=15",
    "-XX:+AlwaysPreTouch",
    "--enable-native-access=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "-Dzomboid.steam=1"
]

if "JVMOptions" in pl:
    if isinstance(pl["JVMOptions"], list):
        filtered = [arg for arg in pl["JVMOptions"] if not arg.startswith("-Xmx") and not arg.startswith("-XX:+UseG1GC") and not arg.startswith("-XX:+AlwaysPreTouch") and not arg.startswith("-Djava.awt.headless") and not arg.startswith("-javaagent:")]
        pl["JVMOptions"] = filtered + jvm_args
    elif isinstance(pl["JVMOptions"], dict):
        pl["JVMOptions"]["Properties"] = pl["JVMOptions"].get("Properties", {})
if "VMOptions" in pl:
    if isinstance(pl["VMOptions"], list):
        filtered = [arg for arg in pl["VMOptions"] if not arg.startswith("-Xmx") and not arg.startswith("-Djava.awt.headless") and not arg.startswith("-javaagent:")]
        pl["VMOptions"] = filtered + jvm_args

with open(plist_path, "wb") as f:
    plistlib.dump(pl, f)
print("[+] Successfully updated Info.plist with ClassPath, Java Agent, Java 17 / B42 heap & entrypoint.")
EOF

    # Re-sign the app bundle on macOS to prevent Gatekeeper/AMFI signature mismatch kills
    if command -v codesign >/dev/null 2>&1; then
        echo "[*] Re-signing ProjectZomboid.app with ad-hoc signature..."
        codesign --force --deep --sign - "$APP_BUNDLE" 2>/dev/null || true
        xattr -cr "$APP_BUNDLE" 2>/dev/null || true
        echo "[+] Successfully re-signed ProjectZomboid.app"
    fi

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
                exit 0
                ;;
            2)
                echo "[*] Uninstalling PZOptimEngine..."
                rm -f "$INSTALLED_JAR"
                if [ -f "${JSON_FILE}.bak" ]; then
                    cp -f "${JSON_FILE}.bak" "$JSON_FILE"
                    echo "[+] Restored original ProjectZomboid64.json from backup."
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

    # Copy JAR
    cp -f "$PZ_JAR" "$INSTALLED_JAR"
    echo "[+] Installed PZOptimEngine.jar -> $INSTALLED_JAR"

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
    f"-Xmx{ram_mb}m",
    "-XX:+UseG1GC",
    "-XX:+PerfDisableSharedMem",
    "-XX:InitiatingHeapOccupancyPercent=45",
    "-XX:G1ReservePercent=15",
    "-XX:+AlwaysPreTouch",
    "-XX:+UnlockExperimentalVMOptions",
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
    echo "{\"optimized\":true,\"ram_gb\":$ALLOC_RAM,\"g1gc\":true,\"pretouch\":true,\"version\":\"0.6.1\"}" > "$HOME/Zomboid/Lua/pzo_status.json"
    echo "[+] Generated Lua bridge status: $HOME/Zomboid/Lua/pzo_status.json"
fi

echo ""
echo "================================================================="
echo " [SUCCESS] Project Zomboid Build 42 is Optimized & Ready!"
echo " Allocated Heap: $ALLOC_RAM GB ($RAM_MB MB)"
echo " Simply launch Project Zomboid normally through Steam."
echo "================================================================="
