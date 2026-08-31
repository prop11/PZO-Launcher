# 🚀 Project Zomboid Optimizer (PZO Launcher & Server Engine Suite)

A high-performance Java engine optimizer, configuration booster, and dedicated server suite engineered for **Project Zomboid (Build 42)** across **Windows, macOS, Linux / Steam Deck, and Dedicated Server Hosts**.

---

## 📑 Table of Contents
- [⚡ Client Optimization Engine (`PZOptimEngine.jar`)](#-client-optimization-engine-pzoptimenginejar)
- [🖥️ Client Installation & Quick Start](#️-client-installation--quick-start)
- [🌐 Dedicated Server Engine (`PZOServerEngine.jar`)](#-dedicated-server-engine-pzoserverenginejar)
- [📦 Dedicated Server Installation (Hosts & Panels)](#-dedicated-server-installation-hosts--panels)
- [🎮 Steam Workshop Mod Integration](#-steam-workshop-mod-integration)
- [🤝 Compatibility & ZombieBuddy Coexistence](#-compatibility--zombiebuddy-coexistence)
- [🔨 Building from Source](#-building-from-source)

---

## ⚡ Client Optimization Engine (`PZOptimEngine.jar`)

* **💾 128KB Direct NIO Memory & Stream Booster**: High-throughput direct chunk stream buffers that eliminate driving auto-save micro-freezes and road streaming hitches.
* **⏱️ Windows High-Precision Timer (1.0ms)**: Locks OS multimedia timer resolution to 1.0ms, eliminating frame pacing micro-stutter.
* **🧠 Dedicated JVM Heap Scaling (`-Xmx 4GB - 16GB`)**: Automatically profiles system RAM and provisions hardware-tailored memory heaps.
* **🧹 Low-Latency G1GC Tuning**: Advanced Garbage Collection parameters (`-XX:+AlwaysPreTouch`, `-XX:InitiatingHeapOccupancyPercent=45`, `-XX:G1ReservePercent=15`) that eliminate stop-the-world collection pauses.
* **⚡ Zero-Allocation Math & Vector Pooling**: Fast precomputed trigonometry tables and reusable vector caches that reduce GC churn during large horde combat.
* **🎨 OpenGL State Deduplication & 3D Model Lighting**: Deduplicates GPU draw state transitions and optimizes distant cluster rendering.
* **🛡️ Pre-Boot Directory Guard**: Automatically verifies and creates required user directories (`mods/`, `Lua/`, `db/`, `Server/`) to prevent B42 `DebugFileWatcher` crashes.
* **🔗 Direct Kahlua Java-to-Lua Bridge (`PZOEngineBridge`)**: Zero-overhead in-memory bridge exposing native engine diagnostics and browser launching directly to the game's Lua runtime.

---

## 🖥️ Client Installation & Quick Start

### 🪟 Windows
1. Download the latest **`PZO-Optimizer-Windows.zip`** from [GitHub Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Ensure Project Zomboid is closed.
3. Run **`Install.bat`**.
   - The installer automatically detects your game directory, checks system RAM, backs up your original configuration, installs `PZOptimEngine.jar`, and applies the optimal profile.
   - *(If already installed, running the script presents quick options to **Update** or **Uninstall**).*
4. Launch Project Zomboid normally through Steam.

### 🍏 macOS & 🐧 Linux / Steam Deck
1. Download **`PZO_Optimizer_macOS_Linux.zip`** from [GitHub Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Run the optimization script:
   - **Linux / Steam Deck**: Run `chmod +x pzo_optimizer.sh && ./pzo_optimizer.sh` in Konsole / Terminal.
   - **macOS**: Double-click `pzo_optimizer.command` or run `bash pzo_optimizer.sh` in Terminal.
3. Launch Project Zomboid normally through Steam.

*Note: You only need to run the installer once. All settings persist across game launches and game updates.*

---

## 🌐 Dedicated Server Engine (`PZOServerEngine.jar`)

Engineered specifically for **3rd-Party Hosted Servers & Dedicated Server Nodes** (G-Portal, Nitrado, GTXGaming, Indifferent Broccoli, BisectHosting, Pterodactyl panels, Hetzner, OVH, Docker).

1. **🌐 Zero-Rubberband Networking (`ServerNetworkTuner`)**:
   - Pools off-heap direct NIO memory buffers for 10–64+ concurrent players.
   - Allocates 4096 high-capacity UDP datagram sockets to eliminate ping spikes and desync during gunfire, driving, and horde combat.
2. **🧟 Multi-Core Zombie Pathfinding (`ServerHordeSimEngine`)**:
   - Dynamically scales server-side pathfinding and migration across all host CPU cores (up to 32/64 threads).
   - Hibernates distant zombie AI simulations when no players are in range.
3. **💾 Zero-Lag World Save Booster (`ServerChunkStreamBooster`)**:
   - 256KB asynchronous disk write buffering for SQLite `.db` tables (`players.db`, `vehicles.db`) and `map_*.bin` chunk saves.
   - Eliminates the periodic *"Server Saving World... Lag Spike"* that freezes active players.
4. **📊 Live Server Telemetry Bridge (`ServerTelemetryBridge`)**:
   - Streams live JSON metrics to `pzo_server_telemetry.json` (Real Server TPS, Heap MB, GC Pause ms, Thread counts) every 5 seconds. Perfect for Discord bot integrations and server monitoring dashboards.

---

## 📦 Dedicated Server Installation (Hosts & Panels)

### Step 1: Upload `PZOServerEngine.jar`
Using your hosting panel's **File Manager** or **SFTP/FTP client** (FileZilla / WinSCP):
1. Upload **`PZOServerEngine.jar`** into your server's root directory (the same folder containing `ProjectZomboid64.json` / `StartServer64.bat` / `projectzomboid.jar`).

### Step 2: Configure `ProjectZomboid64.json`
Open `ProjectZomboid64.json` in your File Manager:
1. Change `"mainClass"` to:
   ```json
   "mainClass": "com/pzoptimizer/server/PZOServerEntrypoint",
   ```
2. Add `"PZOServerEngine.jar"` to the top of your `"classpath"` array:
   ```json
   "classpath": [
       "PZOServerEngine.jar",
       "projectzomboid.jar"
   ],
   ```
3. Save the file and restart your server from your host web panel!

---

## 🎮 Steam Workshop Mod Integration

When combined with the **Project Zomboid Optimiser** Steam Workshop mod, running this engine unlocks the dedicated **[+] JVM Engine (Tab 7)** inside the in-game F10 Control Center:
- Zero-Stutter Background GC Mode
- Deep RAM Chunk Cache (Zero Disk I/O Lag)
- Asynchronous 3D Model & Vehicle Compilation
- Distant Horde Spatial Hibernation
- Live JVM Heap, Pause Time & CPU Telemetry Monitoring

---

## 🤝 Compatibility & ZombieBuddy Coexistence

- **Project Zomboid**: Build 42 & Build 41 (Java 17 / 21 / 25 64-bit).
- **Steam Workshop Mods**: 100% compatible with all standard Lua, vehicle, clothing, weapon, map, and audio overhaul mods.
- **ZombieBuddy Coexistence**: Fully compatible with **ZombieBuddy** (`zbNative`). PZO automatically detects and preserves `"-agentlib:zbNative"` in `ProjectZomboid64.json`. You can run ZombieBuddy's bytecode modding alongside PZO's hardware, timer, GC, and direct I/O optimizations seamlessly.

---

## 🔨 Building from Source

### Requirements
- JDK 17+ (e.g. OpenJDK 17, 21, or 25)

### Build Command
```cmd
javac --release 17 -d bin -sourcepath src src\com\pzoptimizer\*.java src\com\pzoptimizer\server\*.java
jar -cfm dist\PZOptimEngine.jar src\META-INF\MANIFEST.MF -C bin .
```
