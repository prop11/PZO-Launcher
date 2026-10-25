#!/bin/bash
# PZO Version Downgrader for macOS

set -u

echo "================================================================="
echo " Project Zomboid Build 42 Engine Optimizer (PZO)"
echo " macOS Version Downgrader"
echo "================================================================="

if [ "$(uname -s)" != "Darwin" ]; then
    echo "[!] This script is macOS-only. Use pzo_downgrade.sh on Linux."
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Locate Project Zomboid app bundle
APP_BUNDLE=""
for p in \
    "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app" \
    "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/ProjectZomboid.app" \
    "$HOME/Library/Application Support/Steam/steamapps/common/Project Zomboid/Project Zomboid.app" \
    "$HOME/Library/Application Support/Steam/steamapps/common/Project Zomboid/ProjectZomboid.app" ; do
    [ -d "$p" ] && APP_BUNDLE="$p" && break
done

if [ -z "$APP_BUNDLE" ]; then
    echo "[-] Could not locate Project Zomboid.app automatically."
    read -r -p "Drag & drop your Project Zomboid.app here and press Enter: " APP_BUNDLE
fi

APP_BUNDLE="${APP_BUNDLE%/}"
APP_BUNDLE="${APP_BUNDLE#\'}"; APP_BUNDLE="${APP_BUNDLE%\'}"
APP_BUNDLE="${APP_BUNDLE#\"}"; APP_BUNDLE="${APP_BUNDLE%\"}"

JAVA_DIR="$APP_BUNDLE/Contents/Java"
MACOS_DIR="$APP_BUNDLE/Contents/MacOS"
INSTALLED_JAR="$JAVA_DIR/PZOptimEngine.jar"

if [ ! -d "$APP_BUNDLE" ] || [ ! -d "$JAVA_DIR" ]; then
    echo "[ERROR] Invalid Project Zomboid app bundle: $APP_BUNDLE"
    exit 1
fi

echo "[+] Target game bundle: $APP_BUNDLE"

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
    echo "[ERROR] Failed to fetch releases from GitHub."
    exit 1
fi

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
    echo "[!] No prior stable versions available for downgrade."
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

BACKUP_DIR="$APP_BUNDLE/../Installer_Backups/Downgrade_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

# Backup current installed files
[ -f "$INSTALLED_JAR" ] && cp -f "$INSTALLED_JAR" "$BACKUP_DIR/"
[ -f "$JAVA_DIR/libpzo_native64.dylib" ] && cp -f "$JAVA_DIR/libpzo_native64.dylib" "$BACKUP_DIR/"

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
if ! download_file "$JAR_URL" "$INSTALLED_JAR"; then
    echo "[ERROR] Could not download PZOptimEngine.jar for $CHOSEN_TAG."
    [ -f "$BACKUP_DIR/PZOptimEngine.jar" ] && cp -f "$BACKUP_DIR/PZOptimEngine.jar" "$INSTALLED_JAR"
    exit 1
fi
echo "[+] Successfully downloaded PZOptimEngine.jar"

echo "[*] Downloading libpzo_native64.dylib ($CHOSEN_TAG)..."
DYLIB_URL="https://github.com/prop11/PZO-Launcher/releases/download/$CHOSEN_TAG/libpzo_native64.dylib"
if download_file "$DYLIB_URL" "$JAVA_DIR/libpzo_native64.dylib"; then
    chmod +x "$JAVA_DIR/libpzo_native64.dylib"
    cp -f "$JAVA_DIR/libpzo_native64.dylib" "$MACOS_DIR/libpzo_native64.dylib" 2>/dev/null || true
    echo "[+] Successfully downloaded and installed libpzo_native64.dylib"
else
    echo "[!] Notice: Native DYLIB was unavailable for $CHOSEN_TAG."
fi

# Re-sign app bundle to pass Gatekeeper
echo "[*] Re-signing Project Zomboid bundle..."
xattr -dr com.apple.quarantine "$APP_BUNDLE" 2>/dev/null || true
if codesign --force --sign - "$APP_BUNDLE" 2>/dev/null; then
    echo "[+] Bundle re-signed successfully."
elif codesign --force --deep --sign - "$APP_BUNDLE" 2>/dev/null; then
    echo "[+] Bundle deep re-signed successfully."
else
    echo "[!] codesign failed. If the game crashes on launch, run:"
    echo "    codesign --force --deep --sign - \"$APP_BUNDLE\""
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
echo "Game:    $APP_BUNDLE"
echo "Backups: $BACKUP_DIR"
echo ""
