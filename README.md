# Project Zomboid Build 42 - Config & Engine Optimizer (PZO)

A lightweight, high-performance engine wrapper and configuration optimizer engineered specifically for **Project Zomboid Build 42** supporting **Windows, macOS, and Linux / Steam Deck**.

---

## Key Features & Optimizations

- **128KB Direct NIO Memory & Stream Booster**: High-throughput direct chunk stream buffers that eliminate driving auto-save micro-freezes and road streaming hitches.
- **Windows High-Precision Timer (1.0ms)**: Locks OS multimedia timer resolution to 1.0ms, eliminating frame pacing micro-stutter.
- **Pre-Boot Directory Guard**: Automatically verifies and creates required user directories (`mods/`, `Lua/`, `db/`, `Server/`) to prevent B42 `DebugFileWatcher` crashes.
- **Dedicated JVM Heap Scaling (`-Xmx` 4GB - 16GB)**: Automatically profiles system RAM and provisions hardware-tailored memory heaps.
- **Tuned Low-Latency G1 / ZGC Garbage Collection**: Advanced GC tuning (`-XX:+AlwaysPreTouch`, `-XX:InitiatingHeapOccupancyPercent=45`, `-XX:G1ReservePercent=15`) to eliminate stop-the-world collection pauses.
- **Zero-Allocation Math & Vector Pooling**: Fast precomputed trigonometry tables and reusable vector caches that reduce GC churn.
- **OpenGL State Cache & Horde Physics Optimization**: Deduplicates GPU draw state transitions and optimizes distant cluster physics.
- **String & Asset Deduplication Interning**: 16,384-entry string intern pool lowering memory overhead.
- **Real-Time JMX Telemetry Bridge**: Background monitoring bridge communicating directly with the in-game F10 Control Center.

---

## Quick Start

### Windows
1. Download and extract the latest **`PZO-Optimizer-Windows.zip`** from [Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Ensure Project Zomboid is closed.
3. Double-click **`Install.bat`**.
   - The installer automatically detects your game directory and RAM, backs up your original settings, installs `PZOptimEngine.jar`, and applies the optimal profile.
   - *(If already installed, running the script presents quick options to **Update** or **Uninstall** and restore stock settings).*
4. Launch Project Zomboid normally through Steam.

### macOS & Linux / Steam Deck
1. Download **`PZO_Optimizer_macOS_Linux.zip`** from [Releases](https://github.com/prop11/PZO-Launcher/releases) or the repository root.
2. Run the optimization script:
   - **Linux / Steam Deck**: Run `chmod +x pzo_optimizer.sh && ./pzo_optimizer.sh` in Konsole / Terminal.
   - **macOS**: Double-click `pzo_optimizer.command` or run `bash pzo_optimizer.sh` in Terminal.
3. Launch Project Zomboid normally through Steam.

*Note: You only need to run the installer once. All settings persist across game launches and game updates.*

---

## Compatibility

- **Project Zomboid**: Build 42 (Java 17 64-bit).
- **Steam Workshop Mods**: 100% compatible with all standard Lua, vehicle, clothing, weapon, map, and audio mods.
- **ZombieBuddy Coexistence (v0.4.6+)**: Fully compatible with **ZombieBuddy** (`zbNative`). PZO automatically detects and preserves `"-agentlib:zbNative"` in `ProjectZomboid64.json` without modifying or removing ZombieBuddy files. You can run ZombieBuddy's bytecode modding alongside PZO's hardware, timer, GC, and direct I/O optimizations seamlessly.

---

## Workshop Mod Integration

When combined with the **Project Zomboid Optimiser** Steam Workshop mod, running this optimizer unlocks the dedicated **[+] JVM Engine (Tab 7)** inside the in-game F10 Control Center:
- Zero-Stutter Background GC Mode
- Deep RAM Chunk Cache (Zero Disk I/O Lag)
- Asynchronous 3D Model Compilation
- Distant Horde Spatial Hibernation
- Live JVM Heap, Pause Time & CPU Telemetry Monitoring

---

## Building from Source

### Compile Java 17 Engine Wrapper
```cmd
javac --release 17 -d bin src\com\pzoptimizer\*.java
jar -cfm dist\PZOptimEngine.jar src\META-INF\MANIFEST.MF -C bin .
```
