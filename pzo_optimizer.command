#!/bin/bash
# Project Zomboid Config & Engine Optimizer for macOS & Linux

echo "================================================="
echo " Project Zomboid Config & Engine Optimizer (macOS / Linux)"
echo "================================================="

# Detect OS
OS_TYPE="$(uname -s)"
PZ_DIR=""

if [ "$OS_TYPE" = "Darwin" ]; then
    echo "[*] Detected Platform: macOS"
    POSSIBLE_PATHS=(
        "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid"
        "$HOME/Library/Application Support/Steam/steamapps/common/Project Zomboid"
    )
else
    echo "[*] Detected Platform: Linux"
    POSSIBLE_PATHS=(
        "$HOME/.local/share/Steam/steamapps/common/ProjectZomboid"
        "$HOME/.steam/steam/steamapps/common/ProjectZomboid"
        "$HOME/.steam/root/steamapps/common/ProjectZomboid"
    )
fi

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
    echo "[!] Invalid directory: $PZ_DIR"
    exit 1
fi

echo "[+] Found Project Zomboid at: $PZ_DIR"

# Copy JAR if available
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/dist/PZOptimEngine.jar" ]; then
    cp "$SCRIPT_DIR/dist/PZOptimEngine.jar" "$PZ_DIR/PZOptimEngine.jar"
    echo "[+] Installed PZOptimEngine.jar"
elif [ -f "$SCRIPT_DIR/PZOptimEngine.jar" ]; then
    cp "$SCRIPT_DIR/PZOptimEngine.jar" "$PZ_DIR/PZOptimEngine.jar"
    echo "[+] Installed PZOptimEngine.jar"
fi

# Detect total RAM in GB
TOTAL_RAM=8
if [ "$OS_TYPE" = "Darwin" ]; then
    RAM_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo 8589934592)
    TOTAL_RAM=$((RAM_BYTES / 1024 / 1024 / 1024))
else
    RAM_KB=$(grep MemTotal /proc/meminfo | awk '{print $2}')
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

# Find JSON config file
JSON_FILE=""
if [ -f "$PZ_DIR/ProjectZomboid64.json" ]; then
    JSON_FILE="$PZ_DIR/ProjectZomboid64.json"
elif [ -f "$PZ_DIR/ProjectZomboid.json" ]; then
    JSON_FILE="$PZ_DIR/ProjectZomboid.json"
else
    JSON_FILE="$PZ_DIR/ProjectZomboid64.json"
fi

# Backup
if [ -f "$JSON_FILE" ] && [ ! -f "${JSON_FILE}.bak" ]; then
    cp "$JSON_FILE" "${JSON_FILE}.bak"
    echo "[+] Created backup at ${JSON_FILE}.bak"
fi

# Write JSON
cat <<EOF > "$JSON_FILE"
{
    "mainClass": "com/pzoptimizer/PZOEntrypoint",
    "classpath": [
        ".",
        "PZOptimEngine.jar",
        "projectzomboid.jar"
    ],
    "vmArgs": [
        "-Djava.awt.headless=true",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xmx${RAM_MB}m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+UseG1GC",
        "-XX:InitiatingHeapOccupancyPercent=45",
        "-XX:G1ReservePercent=15",
        "-XX:+AlwaysPreTouch"
    ]
}
EOF
echo "[+] Wrote optimized configuration to $(basename "$JSON_FILE")"

# Write status file to Zomboid/Lua/
ZOMBOID_LUA="$HOME/Zomboid/Lua"
mkdir -p "$ZOMBOID_LUA"
cat <<EOF > "$ZOMBOID_LUA/pzo_status.json"
{"optimized": true, "ram_gb": $ALLOC_RAM, "g1gc": true, "pretouch": true}
EOF
echo "[+] Status handshake written to $ZOMBOID_LUA/pzo_status.json"

echo "================================================="
echo " Project Zomboid is now fully optimized!"
echo " Launch the game normally through Steam."
echo "================================================="
