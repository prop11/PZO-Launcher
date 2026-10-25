#!/bin/bash
# PZO Version Downgrader for Linux and Steam Deck

set -e

echo "================================================================="
echo " Project Zomboid Build 42 Engine Optimizer (PZO)"
echo " Linux & Steam Deck Version Downgrader"
echo "================================================================="

OS_TYPE="$(uname -s)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Relaunch in Konsole/terminal if launched without a tty (e.g. Steam Deck desktop)
if [ "$OS_TYPE" != "Darwin" ] && [ ! -t 0 ] && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
    if command -v konsole >/dev/null 2>&1; then
        exec konsole -e bash "$0" "$@"
    elif command -v xterm >/dev/null 2>&1; then
        exec xterm -e bash "$0" "$@"
    fi
fi

# Steam Deck / sudo fallback
if [ "$EUID" -eq 0 ] && [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then
    USER_HOME=$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6 || eval echo "~$SUDO_USER")
    if [ -n "$USER_HOME" ] && [ -d "$USER_HOME" ]; then
        HOME="$USER_HOME"
    fi
fi

# Check for running Project Zomboid process
if pgrep -i "ProjectZomboid" >/dev/null 2>&1; then
    echo ""
    echo "[!] Warning: Project Zomboid is currently running."
    echo "    Please close Project Zomboid to prevent file permission locks."
    read -r -p "    Press Enter once the game is closed to continue..."
fi

# Locate Project Zomboid installation
PZ_DIR=""
POSSIBLE_DIRS=(
    "$HOME/.local/share/Steam/steamapps/common/ProjectZomboid"
    "$HOME/.steam/steam/steamapps/common/ProjectZomboid"
    "$HOME/.steam/root/steamapps/common/ProjectZomboid"
    "/home/deck/.local/share/Steam/steamapps/common/ProjectZomboid"
    "/media/mmcblk0p1/steamapps/common/ProjectZomboid"
    "/run/media/deck/*/steamapps/common/ProjectZomboid"
    "$SCRIPT_DIR"
)

# Parse libraryfolders.vdf if present
VDF_PATH="$HOME/.local/share/Steam/steamapps/libraryfolders.vdf"
[ ! -f "$VDF_PATH" ] && VDF_PATH="$HOME/.steam/steam/steamapps/libraryfolders.vdf"
if [ -f "$VDF_PATH" ]; then
    while IFS= read -r line; do
        if [[ "$line" =~ \"path\"[[:space:]]+\"([^\"]+)\" ]]; then
            p="${BASH_REMATCH[1]}/steamapps/common/ProjectZomboid"
            POSSIBLE_DIRS+=("$p")
        fi
    done < "$VDF_PATH"
fi

for candidate in "${POSSIBLE_DIRS[@]}"; do
    if [ -d "$candidate" ] && [ -f "$candidate/projectzomboid.jar" ]; then
        PZ_DIR="$candidate"
        break
    fi
done

if [ -z "$PZ_DIR" ]; then
    echo "[!] Could not automatically locate Project Zomboid."
    read -r -p "Please enter the full path to your ProjectZomboid directory: " PZ_DIR
    if [ ! -d "$PZ_DIR" ] || [ ! -f "$PZ_DIR/projectzomboid.jar" ]; then
        echo "[ERROR] Invalid Project Zomboid directory. Exiting."
        exit 1
    fi
fi

echo "[+] Game directory: $PZ_DIR"

ZOMBOID_LUA="$HOME/Zomboid/Lua"
STATUS_FILE="$ZOMBOID_LUA/pzo_status.json"
CURRENT_VERSION="Unknown"

if [ -f "$STATUS_FILE" ]; then
    CURRENT_VERSION=$(grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' "$STATUS_FILE" 2>/dev/null | cut -d'"' -f4 || echo "Unknown")
fi
echo "[+] Currently installed PZO version: $CURRENT_VERSION"

# Fetch releases from GitHub API
echo "[*] Fetching releases from GitHub..."
RELEASES_JSON=""
if command -v curl >/dev/null 2>&1; then
    RELEASES_JSON=$(curl -fsSL -H "User-Agent: PZO-Downgrader" "https://api.github.com/repos/prop11/PZO-Launcher/releases?per_page=30" || true)
elif command -v wget >/dev/null 2>&1; then
    RELEASES_JSON=$(wget -q -U "PZO-Downgrader" -O- "https://api.github.com/repos/prop11/PZO-Launcher/releases?per_page=30" || true)
fi

if [ -z "$RELEASES_JSON" ]; then
    echo "[ERROR] Failed to fetch releases from GitHub. Please check your internet connection."
    exit 1
fi

# Filter releases with python3 if available, or awk
FILTER_SCRIPT="
import sys, json

data = json.loads(sys.stdin.read())
current = sys.argv[1].lstrip('vV').strip().lower()
eligible = []

for r in data:
    if r.get('draft') or r.get('prerelease'):
        continue
    tag = r.get('tag_name', '')
    tl = tag.lower()
    if 'unstable' in tl or 'beta' in tl or 'alpha' in tl or 'rc' in tl:
        continue
    clean_tag = tag.lstrip('vV').strip().lower()
    if current != 'unknown' and clean_tag == current:
        continue
    pub = r.get('published_at', '')[:10]
    eligible.append((tag, pub))
    if len(eligible) >= 5:
        break

for i, (t, p) in enumerate(eligible):
    print(f'{i+1}|{t}|{p}')
"

OPTIONS_RAW=""
if command -v python3 >/dev/null 2>&1; then
    OPTIONS_RAW=$(echo "$RELEASES_JSON" | python3 -c "$FILTER_SCRIPT" "$CURRENT_VERSION" 2>/dev/null || true)
fi

if [ -z "$OPTIONS_RAW" ]; then
    echo "[!] No prior stable versions found to downgrade to."
    exit 0
fi

echo ""
echo "Available stable versions for downgrade:"
declare -a TAG_LIST=()
while IFS='|' read -r num tag pub; do
    [ -z "$tag" ] && continue
    TAG_LIST+=("$tag")
    printf "  [%s] %-10s (Published: %s)\n" "$num" "$tag" "$pub"
done <<< "$OPTIONS_RAW"
echo "  [C] Cancel"

echo ""
read -r -p "Enter choice (1-${#TAG_LIST[@]} or C): " CHOICE

if [ -z "$CHOICE" ] || [ "$CHOICE" = "C" ] || [ "$CHOICE" = "c" ]; then
    echo "Operation cancelled."
    exit 0
fi

if ! [[ "$CHOICE" =~ ^[0-9]+$ ]] || [ "$CHOICE" -lt 1 ] || [ "$CHOICE" -gt "${#TAG_LIST[@]}" ]; then
    echo "[ERROR] Invalid selection. Exiting."
    exit 1
fi

CHOSEN_TAG="${TAG_LIST[$((CHOICE - 1))]}"
echo "[*] Downgrading to $CHOSEN_TAG..."

BACKUP_DIR="$PZ_DIR/Installer_Backups/Downgrade_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

# Backup existing files
[ -f "$PZ_DIR/PZOptimEngine.jar" ] && cp -f "$PZ_DIR/PZOptimEngine.jar" "$BACKUP_DIR/"
[ -f "$PZ_DIR/libpzo_native64.so" ] && cp -f "$PZ_DIR/libpzo_native64.so" "$BACKUP_DIR/"
[ -f "$PZ_DIR/linux64/libpzo_native64.so" ] && cp -f "$PZ_DIR/linux64/libpzo_native64.so" "$BACKUP_DIR/"
[ -f "$PZ_DIR/ProjectZomboid64.json" ] && cp -f "$PZ_DIR/ProjectZomboid64.json" "$BACKUP_DIR/"

download_file() {
    local url="$1"
    local dest="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -A "PZO-Downgrader" "$url" -o "$dest"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -U "PZO-Downgrader" "$url" -O "$dest"
    fi
}

echo "[*] Downloading PZOptimEngine.jar ($CHOSEN_TAG)..."
JAR_URL="https://github.com/prop11/PZO-Launcher/releases/download/$CHOSEN_TAG/PZOptimEngine.jar"
if ! download_file "$JAR_URL" "$PZ_DIR/PZOptimEngine.jar"; then
    echo "[ERROR] Could not download PZOptimEngine.jar for $CHOSEN_TAG."
    [ -f "$BACKUP_DIR/PZOptimEngine.jar" ] && cp -f "$BACKUP_DIR/PZOptimEngine.jar" "$PZ_DIR/"
    exit 1
fi
echo "[+] Successfully downloaded PZOptimEngine.jar"

echo "[*] Downloading libpzo_native64.so ($CHOSEN_TAG)..."
SO_URL="https://github.com/prop11/PZO-Launcher/releases/download/$CHOSEN_TAG/libpzo_native64.so"
if download_file "$SO_URL" "$PZ_DIR/libpzo_native64.so"; then
    chmod +x "$PZ_DIR/libpzo_native64.so"
    if [ -d "$PZ_DIR/linux64" ]; then
        cp -f "$PZ_DIR/libpzo_native64.so" "$PZ_DIR/linux64/libpzo_native64.so"
        chmod +x "$PZ_DIR/linux64/libpzo_native64.so"
    fi
    echo "[+] Successfully downloaded and installed libpzo_native64.so"
else
    echo "[!] Notice: Native SO download was unavailable for $CHOSEN_TAG."
fi

# Update pzo_status.json
mkdir -p "$ZOMBOID_LUA"
CLEAN_VERSION="${CHOSEN_TAG#v}"
CLEAN_VERSION="${CLEAN_VERSION#V}"
cat <<EOF > "$STATUS_FILE"
{"optimized":true,"version":"$CLEAN_VERSION","downgraded":true,"target_tag":"$CHOSEN_TAG"}
EOF

echo ""
echo "================================================================="
echo " [SUCCESS] PZO successfully downgraded to $CHOSEN_TAG!"
echo "================================================================="
echo "Directory: $PZ_DIR"
echo "Backups:   $BACKUP_DIR"
echo ""
