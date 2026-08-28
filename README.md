# Project Zomboid Build 42 - Config & Engine Optimizer (PZO)

A lightweight, zero-dependency engine wrapper and configuration optimizer engineered specifically for **Project Zomboid Build 42** supporting **Windows, macOS, and Linux**.

## Build 42 Native Enhancements
- **Multi-Threaded Dynamic Lighting Propagation**: Forces B42 light computation onto dedicated worker threads
- **32-Level Building Depth & Cutaway Occlusion**: Accelerates multistory skyscraper visibility math
- **GPU Hardware Mesh Instancing**: Instanced batch rendering for dense foliage, crops, and terrain
- **Threaded 3D Model Slot Initialization**: Eliminates backpack and armor equip stutters
- **Windows High-Precision Timer (1.0ms)**: Eliminates frame pacing micro-jitter
- **Dedicated JVM Heap Scaling (`-Xmx` 4GB - 16GB)**: Tailored automatically to your system RAM
- **Low-Latency G1 / ZGC Garbage Collection**: Eliminates stop-the-world Lua collection freezes
- **Direct Memory & Stream Booster**: High-throughput NIO chunk buffers for instantaneous road streaming
- **Full In-Game Synchronization**: Integrates directly with the in-game F10 Optimiser Control Center

---

## Quick Start

### Windows
1. Download and extract the latest **`PZO-Optimizer-Windows.zip`** from [Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Ensure Project Zomboid is closed.
3. Double-click **`Install.bat`**.
   - The installer automatically detects your game directory and RAM, backs up your original settings, installs `PZOptimEngine.jar`, and applies the optimal profile.
   - *(If already installed, running the script presents quick options to **Update** or **Uninstall** and restore your stock configuration).*
4. Launch Project Zomboid normally through Steam.

### macOS & Linux
1. Download the repository or **`pzo_optimizer.sh`** and **`dist/PZOptimEngine.jar`**.
2. Run the optimization script:
   - **macOS**: Double-click `pzo_optimizer.command` or run `bash pzo_optimizer.sh` in Terminal.
   - **Linux**: Run `chmod +x pzo_optimizer.sh && ./pzo_optimizer.sh` in Terminal.
3. Launch Project Zomboid normally through Steam.

*Note: You only need to run the installer once. All settings persist across game launches.*

---

## Compatibility
- **Project Zomboid**: Dedicated to **Build 42** (Java 17 64-bit).
- **Steam Workshop Mods**: 100% compatible with all standard Lua, vehicle, clothing, weapon, map, and audio mods.
- **ZombieBuddy & Java Mods**: **Fully Compatible from v0.4.1!** `PZOptimEngine.jar` includes an embedded Java Mod Loader that automatically discovers and hooks ZombieBuddy-dependent mods and ByteBuddy bytecode patches directly from your Workshop folders—**without needing ZombieBuddy to be installed**.

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