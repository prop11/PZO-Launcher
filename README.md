<div align="center">

# Project Zomboid Optimizer (PZO) - Zomdroid Edition

### Standalone JVM Engine for Project Zomboid on Android (Zomdroid)
**ARM64 NEON SIMD culling | Skeletal animation LOD | Mobile GL4ES / Turnip compatibility**

</div>

---

This branch (`zomdroid`) is a dedicated edition of **Project Zomboid Optimizer (PZO)** engineered specifically for running **Project Zomboid (Build 42 & Build 41.78+)** on **Android (ARM64)** via the [Zomdroid](https://github.com/udarmolota/zomdroid) launcher.

When attached via `-javaagent:PZOptimEngine.jar` in Zomdroid's instance JVM arguments, PZO initializes its full multi-core worker engine and runtime governors directly from `PZOptimAgent.premain`, patches game bytecode in memory, and extracts its bundled Android ARM64 (`aarch64-linux-android` Bionic + NEON) native companion library into Zomdroid's executable app directory.

> **Important - Zomdroid Compatibility Rules:**
> 1. **Do NOT install the Steam Workshop Mod (`MPOptimiser`):** The Zomdroid edition runs 100% standalone through `PZOptimEngine.jar`. Even if the Workshop mod is present in a folder, `ZomdroidShaderShield` automatically blocks its Build 42 replacement shaders so GL4ES / Turnip never fail to compile or link vehicle, wheel, tree, puddle, skybox, or floor shaders.
> 2. **Do NOT edit `ProjectZomboid64.json` or `classpath`:** Zomdroid does not read `ProjectZomboid64.json`, and overriding `-cp` / `classpath` replaces Zomdroid's own classpath and prevents the game from starting. Configure `-javaagent:PZOptimEngine.jar` **only** in Zomdroid's instance **JVM arguments** setting.

| | |
|---|---|
| **Target Platform** | **Android (ARM64)** via [Zomdroid Launcher](https://github.com/udarmolota/zomdroid) |
| **Supported Game Versions** | **Build 42 (all revisions) & Build 41.78+** |
| **Releases** | [github.com/prop11/PZO-Launcher/releases](https://github.com/prop11/PZO-Launcher/releases) (`PZOptimEngine.jar`) |
| **Native Companion** | Android ARM64 Bionic + NEON SIMD (`libpzo_native64.so`, bundled inside `PZOptimEngine.jar`) |
| **Workshop Mod** | **Not used on Zomdroid** (standalone `-javaagent` engine only) |

---

## Mobile Multi-Core Architecture

Stock Project Zomboid executes almost all spatial math, visibility checks, and skeletal animation updates on a single thread, causing thermal throttling and frame drops on mobile ARM64 SoCs. PZO offloads these workloads across worker threads while keeping game state and Kahlua Lua VM access strictly thread-safe:

```text
                               +----------------------------------------------+
                               |       Multi-Core Worker Thread Pool          |
                               |   (ARM64 big.LITTLE / Prime + Big Cores)     |
                               +------+--------------+--------------+---------+
                                      |              |              |
               +----------------------+------+       |       +------+----------------------+
               v                             v       v       v                             v
    +----------------------+   +----------------------+   +----------------------+   +----------------------+
    |       Pillar 1       |   |       Pillar 2       |   |       Pillar 3       |   |       Pillar 4       |
    | MultiCoreChunkStream |   | MultiCoreHordeGov    |   | MultiCoreAnimation   |   | MultiCoreIslandSched |
    |                      |   |                      |   |                      |   |                      |
    | * 1MB Direct NIO Ring|   | * ARM64 NEON SIMD    |   | * 64-Bone Frustum    |   | * 32x32 Spatial      |
    | * Immediate Streamer |   | * Frustum AABB Cull  |   |   Skinning Bypass    |   |   Island Buckets     |
    |   Queue Wake-Up      |   | * Parallel FOV Cones |   | * Distant 75% Frame  |   | * Main-Thread Lua VM |
    | * Parallel Zlib Infl |   | * Crowd Flocking Vec |   |   Skip Downsampling  |   |   Safety Guarantees  |
    +----------------------+   +----------------------+   +----------------------+   +----------------------+
```

### 1. Full Agent-Mode Engine Bootstrap (`PZOptimAgent` -> `PZOEntrypoint.bootstrapEngineFromAgent`)
* Zomdroid starts the game using its own launcher entrypoint rather than `PZOEntrypoint`.
* In this edition, `PZOptimAgent.premain` directly boots `PZOMultiCoreEngine`, `EngineThreadGovernor`, `EngineGLStateGovernor`, `HordeSpatialCuller`, `SpatialBufferPool`, `NativeDirectMemoryPool`, `WorldStreamerBooster`, `GenerationalHeapCleaner`, and deferred `MainThread` tuning (`EngineFeaturesTuner` & `WorkerRandGovernor`) without touching AWT/Swing desktop dialogs.

### 2. Android ARM64 Native Library & NEON SIMD (`PZONative` & `pzo_native.c`)
* **Android Bionic ARM64**: Compiled against Android Bionic (`aarch64-linux-android`) with `sched_setaffinity` and ARMv8-A NEON (`<arm_neon.h>`) vector intrinsics.
* **big.LITTLE / DynamIQ Core Topology**: Reads `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` to identify the high-frequency performance/Prime cores and avoid pinning worker threads to slow LITTLE efficiency cores.
* **Automatic `noexec` Storage Staging**: Because Android mounts `/storage/emulated/0` with `noexec`, `PZONative` automatically extracts `libpzo_native64.so` from `PZOptimEngine.jar` into Zomdroid's executable app-private directory (`java.io.tmpdir` / `user.home`) at startup.

### 3. GL4ES / Turnip Shader Protection (`ZomdroidShaderShield`)
* **Desktop Shader Rewrites Disabled**: Desktop Build 42 shader patches (`ShaderEarlyDepth`, `TranslucentBake`, `WeatherTileBatcher`) are disabled on Zomdroid so GL4ES and Turnip receive unmodified stock shaders.
* **Workshop Shader Shield**: Hooks `IndieFileLoader` so that even if a player has the desktop `MPOptimiser` Workshop mod folder installed, all `media/shaders/` lookups bypass `MPOptimiser` and load stock vanilla game shaders.

---

## Zomdroid Installation Guide

### Prerequisites
1. **Android Device**: ARM64 device with at least **6 GB RAM** (8 GB+ recommended for Build 42).
2. **Zomdroid Launcher**: Installed from [udarmolota/zomdroid](https://github.com/udarmolota/zomdroid) with a working Project Zomboid instance (Build 41 or Build 42).
3. **No Workshop Mod**: Do **not** install or enable the PZO Steam Workshop mod (`MPOptimiser`).

---

### Step 1: Copy `PZOptimEngine.jar` into Your Zomdroid Instance

1. Download **`PZOptimEngine.jar`** from [GitHub Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Copy **`PZOptimEngine.jar`** into the root game directory of your Zomdroid instance (for example, `zomdroid/instances/<YourInstanceName>/game/` or the instance's working directory where the game files are unpacked).
   *(Note: `libpzo_native64.so` is bundled inside `PZOptimEngine.jar` and extracted automatically into Zomdroid's executable temp directory on launch).*

---

### Step 2: Add `-javaagent` to Zomdroid Instance JVM Arguments

> **Warning:** Do **not** edit `ProjectZomboid64.json` (Zomdroid does not read it), and do **not** add `-cp` or `-classpath` arguments (doing so overwrites Zomdroid's own classpath and prevents the game from starting).

1. Open the **Zomdroid** launcher on your Android device.
2. Open your instance settings (or **Settings -> Advanced -> JVM arguments**).
3. Add `-javaagent:PZOptimEngine.jar` (or the full path to `PZOptimEngine.jar` if placed outside the instance working directory) to your **JVM arguments** list:
   ```text
   -javaagent:PZOptimEngine.jar
   ```
   Example combined with mobile heap sizing on an 8 GB RAM device:
   ```text
   -javaagent:PZOptimEngine.jar -XX:+UseG1GC -Xms1536M -Xmx3072M
   ```

---

### Step 3: Recommended Mobile RAM (`-Xmx`) & Renderer Settings

On Android, setting `-Xmx` too high starves Box64 and the GPU driver (GL4ES / Turnip) of native memory, causing Android's Low Memory Killer (LMK) to close the app. Use the table below based on your device's total physical RAM:

| Device Total RAM | Recommended `-Xms` / `-Xmx` | Notes |
|---|---|---|
| **6 GB RAM** | `-Xms1024M -Xmx2048M` | Best for Build 41; keep texture compression enabled in PZ options. |
| **8 GB RAM** | `-Xms1536M -Xmx3072M` | Recommended baseline for Build 42 on 8 GB phones/tablets. |
| **12 GB+ RAM** | `-Xms2048M -Xmx4096M` | Up to `-Xmx5120M` if running heavy mod lists on 12-16 GB devices. |

* **Garbage Collector**: Use `-XX:+UseG1GC` on Zomdroid rather than `-XX:+UseZGC`.
* **Renderer Selection**:
  * **Build 41**: Works with both **GL4ES** and **Turnip / Zink** (on Snapdragon Adreno GPUs).
  * **Build 42**: Use **Turnip / Zink** on Snapdragon Adreno GPUs for full GLSL 330 shader support.

---

### Step 4: Verifying Installation

1. Launch your instance from Zomdroid.
2. Check `Zomboid/console.txt` (or `Zomboid/Lua/pzo_engine.log`) inside your instance's user folder for:
   ```text
   [PZO Agent] Build 42 JVM Instrumentation Agent Active
   [EngineGLStateGovernor] Mobile / GL4ES execution environment detected (Zomdroid)
   [PZOMultiCoreEngine] TRUE MULTI-CORE ENGINE ARMED & OPERATIONAL
   ```

---

### Uninstalling on Zomdroid

1. Open your Zomdroid instance settings and remove `-javaagent:PZOptimEngine.jar` from **JVM arguments**.
2. Delete `PZOptimEngine.jar` from your Zomdroid instance folder.

---

## Building from Source

### Requirements
* JDK 17+ (OpenJDK 17, 21, or 25)
* Android NDK (`aarch64-linux-android24-clang`) for building `libpzo_native64.so`
* Python 3.8+

### Build Commands
```bash
# Build Android ARM64 native library (Linux / CI with Android NDK)
bash native/build_native.sh

# Compile Java classes and package PZOptimEngine.jar
python build_all.py
```

---

## License
Source-available, all rights reserved. Versions up to and including V1.0.2.1 were released under GPL-3.0; every later version is covered by the [PZO Source-Available License](LICENSE), which prohibits redistribution, derivative works and any use of this project by AI systems.
