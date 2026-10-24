<div align="center">

# Project Zomboid Optimizer (PZO) - Zomdroid Edition

### Standalone JVM Engine for Project Zomboid on Android (Zomdroid)
**Multi-core spatial culling | Skeletal animation LOD | Mobile GL4ES / Turnip state optimization**

</div>

---

This branch (`zomdroid`) is a dedicated edition of **Project Zomboid Optimizer (PZO)** engineered exclusively for running **Project Zomboid (Build 42 & Build 41.78+)** on **Android (ARM64)** via the [Zomdroid](https://github.com/udarmolota/zomdroid) launcher.

Instead of replacing game classes on disk or relying on desktop-only native hooks, PZO Zomdroid Edition hooks the JVM at startup (`-javaagent:PZOptimEngine.jar`) and patches game bytecode in memory to reduce CPU overhead, animation skinning cost, chunk streaming stutter, and redundant OpenGL calls over mobile translation layers (GL4ES / Freedreno Turnip / Zink + Box64).

> **Important - No Workshop Mod Required:**
> Do **not** install or enable the desktop PZO Steam Workshop Lua mod on Zomdroid. This edition runs 100% standalone through `PZOptimEngine.jar` with all mobile optimizations active automatically at startup.

| | |
|---|---|
| **Target Platform** | **Android (ARM64)** via [Zomdroid Launcher](https://github.com/udarmolota/zomdroid) |
| **Supported Game Versions** | **Build 42 (all revisions) & Build 41.78+** (Linux game depot inside Zomdroid) |
| **Releases** | [github.com/prop11/PZO-Launcher/releases](https://github.com/prop11/PZO-Launcher/releases) (`PZOptimEngine.jar`) |
| **Workshop Mod** | **Not used on Zomdroid** (standalone `-javaagent` engine only) |

---

## Mobile Multi-Core Architecture

Stock Project Zomboid executes almost all spatial math, visibility checks, and skeletal animation updates on a single thread, which quickly causes thermal throttling and frame drops on mobile ARM64 SoCs. PZO offloads these workloads across worker threads while keeping game state and Kahlua Lua VM access strictly thread-safe:

```text
                               +----------------------------------------------+
                               |       Multi-Core Worker Thread Pool          |
                               |     (ARM64 Performance / Prime Cores)        |
                               +------+--------------+--------------+---------+
                                      |              |              |
               +----------------------+------+       |       +------+----------------------+
               v                             v       v       v                             v
    +----------------------+   +----------------------+   +----------------------+   +----------------------+
    |       Pillar 1       |   |       Pillar 2       |   |       Pillar 3       |   |       Pillar 4       |
    | MultiCoreChunkStream |   | MultiCoreHordeGov    |   | MultiCoreAnimation   |   | MultiCoreIslandSched |
    |                      |   |                      |   |                      |   |                      |
    | * 1MB Direct NIO Ring|   | * 256-Zombie Batches |   | * 64-Bone Frustum    |   | * 32x32 Spatial      |
    | * Immediate Streamer |   | * Frustum AABB Cull  |   |   Skinning Bypass    |   |   Island Buckets     |
    |   Queue Wake-Up      |   | * Parallel FOV Cones |   | * Distant 75% Frame  |   | * Main-Thread Lua VM |
    | * Parallel Zlib Infl |   | * Crowd Flocking Vec |   |   Skip Downsampling  |   |   Safety Guarantees  |
    +----------------------+   +----------------------+   +----------------------+   +----------------------+
```

### 1. Horde Simulation & Spatial Culling (`MultiCoreHordeGovernor`)
* **Partitioned Sweeps**: Active zombies are partitioned into 256-entity batches across available CPU cores.
* **Parallel Vector Math**: Evaluates entity distances, LOD tiers, camera frustum culling, 90-degree FOV awareness cones, and crowd repulsion vectors in parallel across worker threads with full ARM64 support.
* **Lock-Free Snapshots**: Writes results to atomic snapshot arrays (`SNAPSHOT_DISTANCES`, `SNAPSHOT_TIERS`, `SNAPSHOT_MASK`, `SNAPSHOT_FOV`), allowing the main game thread to read spatial results without lock contention.

### 2. Skeletal Animation LOD (`MultiCoreAnimationEngine`)
* **Frustum Bone Bypass**: Skips all 64 bone matrix transformations for offscreen zombies or entities culled outside the camera frustum.
* **Adaptive Frame Skipping**: Scales down bone matrix updates for distant entities (>50 tiles away), cutting CPU skinning overhead and heat generation while keeping full animation fidelity for nearby zombies.

### 3. Chunk Streamer Dispatch (`StreamerWake` & `MultiCoreChunkStreamer`)
* **Immediate Thread Dispatch**: Stock PZ throttles chunk requests on the main thread. PZO unparks and wakes the background chunk streamer thread immediately upon enqueue.
* **Direct NIO Ring**: Pools off-heap direct memory buffers for parallel chunk decompression, reducing stutter when driving or sprinting across mobile flash storage.

### 4. Mobile OpenGL / GL4ES State Governor (`EngineGLStateGovernor`)
* **Automatic Zomdroid Detection**: Automatically detects the Zomdroid / GL4ES environment (`LIBGL_ES`, `ZOMDROID`, `/storage/emulated/0`, or `-Dpzoptimizer.mobile=true`).
* **L1 Shadow-State Filtering**: Tracks bound texture IDs, shader programs, and blend states in Java shadow registers to eliminate redundant JNI calls (`glBindTexture`, `glUseProgram`, `glBlendFunc`) across the GL4ES / Turnip translation bridge.
* **Mobile Shader & Texture Unit Safety**: Avoids desktop-only FBO/shader overrides that exceed mobile OpenGL ES texture image unit limits or cause red/black model artifacts.

---

## Zomdroid Installation Guide

### Prerequisites
1. **Android Device**: ARM64 device with at least **6 GB RAM** (8 GB+ recommended for Build 42).
2. **Zomdroid Launcher**: Installed from [udarmolota/zomdroid](https://github.com/udarmolota/zomdroid) with a working Project Zomboid instance unpacked from the **Linux** game depot.
3. **No Workshop Mod**: If you previously copied the PZO Workshop mod (`MPOptimiser`) into your Zomdroid `Zomboid/mods/` or `Zomboid/Workshop/` folder, remove or disable it.

---

### Step 1: Copy `PZOptimEngine.jar` into Your Zomdroid Instance

1. Download **`PZOptimEngine.jar`** (either standalone or inside `PZO_Optimizer_macOS_Linux.zip`) from [GitHub Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Locate your Zomdroid instance's game folder on your device (using a file manager such as **ZArchiver**, **MT Manager**, Android's built-in **Files** app via storage provider, or over USB/ADB):
   * Typical shared storage path:
     ```text
     /storage/emulated/0/zomdroid/instances/<YourInstanceName>/game/
     ```
   * App-scoped storage path (depending on your Zomdroid build):
     ```text
     /storage/emulated/0/Android/data/com.udarmolota.zomdroid/files/instances/<YourInstanceName>/game/
     ```
   *(Look for the directory that contains `ProjectZomboid64.json` and the `java/` folder).*
3. Place **`PZOptimEngine.jar`** directly into that directory alongside `ProjectZomboid64.json`.

---

### Step 2: Attach the PZO Agent in Zomdroid

Depending on your Zomdroid version, JVM flags are read either from the Zomdroid app's **JVM Arguments** setting or from `ProjectZomboid64.json`. Applying both ensures PZO loads across all Zomdroid releases.

#### A. Zomdroid App Settings (Recommended)
1. Open the **Zomdroid** app.
2. Open your instance settings (or go to **Settings -> Advanced -> JVM arguments**).
3. Add `-javaagent:PZOptimEngine.jar` and `-Dpzoptimizer.mobile=true` to the JVM arguments:
   ```text
   -javaagent:PZOptimEngine.jar -Dpzoptimizer.mobile=true -XX:+UseG1GC -Xms1536M -Xmx3072M
   ```

#### B. `ProjectZomboid64.json`
1. Open `ProjectZomboid64.json` (in the same folder where you placed `PZOptimEngine.jar`) with a text editor.
2. Add `"PZOptimEngine.jar"` to the `"classpath"` list, and add `"-javaagent:PZOptimEngine.jar"` and `"-Dpzoptimizer.mobile=true"` at the top of `"vmArgs"`:
   ```json
   {
     "mainClass": "zombie/gameStates/MainScreenState",
     "classpath": [
       "PZOptimEngine.jar",
       "java/.",
       "java/projectzomboid.jar"
     ],
     "vmArgs": [
       "-javaagent:PZOptimEngine.jar",
       "-Dpzoptimizer.mobile=true",
       "-Djava.awt.headless=true",
       "-XX:+UseG1GC",
       "-Xms1536m",
       "-Xmx3072m",
       "-XX:-OmitStackTraceInFastThrow"
     ]
   }
   ```
3. Save `ProjectZomboid64.json`.

---

### Step 3: Recommended Mobile RAM (`-Xmx`) & Driver Settings

On Android, setting `-Xmx` too high starves the native Box64 translator and GPU driver (GL4ES / Turnip) of memory, causing Android's Low Memory Killer (LMK) to terminate the app without a Java crash log. Use the table below based on your device's total physical RAM:

| Device Total RAM | Recommended `-Xms` / `-Xmx` | Notes |
|---|---|---|
| **6 GB RAM** | `-Xms1024M -Xmx2048M` | Best for Build 41; keep texture compression enabled in PZ options. |
| **8 GB RAM** | `-Xms1536M -Xmx3072M` | Recommended baseline for Build 42 on 8 GB phones/tablets. |
| **12 GB+ RAM** | `-Xms2048M -Xmx4096M` | Up to `-Xmx5120M` if running heavy mod lists on 12-16 GB devices. |

* **Garbage Collector**: Use `-XX:+UseG1GC` on Zomdroid rather than `-XX:+UseZGC`. G1GC has significantly lower native memory overhead under Box64/ARM64 on Android.
* **Graphics Driver**:
  * **Snapdragon (Adreno GPUs)**: Use **Turnip / Zink** where supported (especially for Build 42 shaders), or **GL4ES** for Build 41 compatibility.
  * **Mali / Immortalis / Xclipse GPUs**: Use **GL4ES** (or **LTW / VirGL** depending on your Zomdroid build).

---

### Step 4: Verifying Installation

1. Launch your instance from Zomdroid.
2. To confirm PZO is active, check `Zomboid/console.txt` (or `Zomboid/Lua/pzo_engine.log`) inside your instance's user folder:
   * Look for:
     ```text
     [EngineGLStateGovernor] Mobile / GL4ES execution environment detected (Zomdroid)
     ```
   * Along with `[PZO]` startup confirmation lines at the top of `console.txt`.

---

### Uninstalling on Zomdroid

1. Remove `-javaagent:PZOptimEngine.jar` and `-Dpzoptimizer.mobile=true` from your Zomdroid **JVM arguments** and `ProjectZomboid64.json`.
2. Delete `PZOptimEngine.jar` from your Zomdroid instance folder.

---

## Building from Source

### Requirements
* JDK 17+ (OpenJDK 17, 21, or 25)
* Python 3.8+

### Build Commands
```bash
# Compile Java sources and package PZOptimEngine.jar
python build_all.py

# Or package release archives from compiled classes in bin/
python package_release.py
```

---

## License
Source-available, all rights reserved. Versions up to and including V1.0.2.1 were released under GPL-3.0; every later version is covered by the [PZO Source-Available License](LICENSE), which prohibits redistribution, derivative works and any use of this project by AI systems.
