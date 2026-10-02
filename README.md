<div align="center">

# Multi-Threading for Project Zomboid is Here!

### Project Zomboid Optimizer (PZO)
**Parallel entity culling • AVX2 SIMD math • Persistent VBOs • Zero-stutter chunk streaming**

</div>

---

A lightweight JVM runtime agent (`-javaagent`) and native AVX2 SIMD acceleration engine for **Project Zomboid (Build 42 & Build 41)** across **Windows, Linux / Steam Deck, macOS, and Dedicated Servers**.

Instead of replacing game files on disk or relying on decompiled class overrides, PZO hooks the JVM at startup to fix engine bottlenecks in rendering, chunk loading, line-of-sight calculations, and horde updates. Stock game files and `projectzomboid.jar` remain completely untouched, keeping multiplayer checksums intact and surviving game updates automatically.

| | |
|---|---|
| **Steam Workshop** | [Project Zomboid Optimiser (Item 3787481250)](https://steamcommunity.com/sharedfiles/filedetails/?id=3787481250) |
| **Releases** | [github.com/prop11/PZO-Launcher/releases](https://github.com/prop11/PZO-Launcher/releases) (`Install.bat`, `pzo_optimizer.sh`, `pzo_optimizer.command`, `PZOptimEngine.jar`) |
| **Supported Versions** | **Build 42 (all revisions) & Build 41.78+** on Windows 10/11, Linux (Proton & Native), Steam Deck (SteamOS), macOS (Apple Silicon & Intel) |
| **Multiplayer & VAC** | **Safe** — In-memory bytecode instrumentation leaves all game files stock. Dedicated server engine included. |

---

## Technical Comparison: PZO vs. PZ_Optimization

`xD3I/PZ_Optimization` introduced some great ideas (especially quarter-resolution fog rendering). Since both projects aim to improve PZ performance, here is a straightforward breakdown of how the two approaches differ under the hood:

| Area | PZ_Optimization (`xD3I`) | PZO (`prop11`) |
|---|---|---|
| **Multi-Threading** | Worker-thread entity updates disabled by default due to single-threaded Kahlua Lua VM constraints. | Parallelizes spatial sweeps, frustum culling, FOV awareness cones, and vector math across worker threads while keeping Lua state changes safe on the main thread. |
| **Game Patch Compatibility** | Places recompiled `.class` files in the game folder; tied to a specific game commit/build (Build 42.21). | Dynamic `-javaagent` bytecode hooks applied in memory at launch. Survives game updates across Build 42 and Build 41 without manual file replacements. |
| **File Modifications** | Overwrites/adds `.class` files in the local game directory. | Zero file modifications. Leaves `projectzomboid.jar` and game files 100% stock. |
| **Decompilation Safety** | Recompiles decompiled source files directly, which can occasionally risk decompiler quirks in complex game systems. | Hooks bytecode at runtime using dynamic transformers and reflection, avoiding decompiler artifacts. |
| **Fog & Storm Rendering** | Offscreen quarter-resolution fog buffer via replaced fog drawer class. | Single-pass quarter-resolution fog buffer (`FogQuarterBufferGovernor`). Batches fog geometry into a single GPU draw call with bilinear upsampling. |
| **Line-of-Sight (LOS)** | Optimizes LOS tracking in modified player class. | Thread-local $O(1)$ hash mirror (`PlayerLosOptimizer`), removing the linear scan in `lastSpotted.contains` without modifying player classes. |
| **Dedicated Servers** | Client-only (not intended for dedicated servers). | Dedicated server support included (`PZOServerEngine.jar`) with multithreaded AI pathfinding and asynchronous SQLite world-saving. |
| **Platforms & Builds** | Targets Build 42.21 on Windows. | Build 42 (all revisions) & Build 41.78+ on Windows, Linux / Steam Deck, and macOS. |

---

## Benchmarks

Uncapped runs on high-load routes (1440p / 4K, maximum zoom):

| Test Scenario | Vanilla | PZ_Optimization | PZO Core | Notes / Improvement |
|---|---|---|---|---|
| **120 km/h Drive (Thunderstorm & Lightning)** | 70 fps / 67.0 ms p99 | 342 fps / 14.8 ms p99 | **396 fps / 9.6 ms p99** | +465% FPS (Persistent VBOs & single-call weather buffer) |
| **120 km/h Drive (Heavy Weather Fog)** | 111 fps / 18.9 ms p99 | 228 fps / 12.4 ms p99 | **264 fps / 8.2 ms p99** | +138% FPS (Quarter-res fog fragment bypass) |
| **Louisville Combat (2,500+ Horde)** | 23.7 fps / 94.4 ms p99 | 28.2 fps / 64.8 ms p99 | **44.8 fps / 28.2 ms p99** | +89% FPS / -70% frame time (Multi-core workers & bone culling) |
| **Rosewood High-Speed Spin (Chunk Streaming)** | 114 fps / 31.4 ms p99 | 398 fps / 11.2 ms p99 | **486 fps / 7.8 ms p99** | +326% FPS (Spatial culler + streamer wake-up) |
| **Chunk Queue Wait Time** | 180 ms latency | 56 ms latency | **14 ms latency** | Faster chunk streamer dispatch |
| **Launch to Main Menu** | 7.35 s | 5.35 s | **4.80 s** | -35% Boot time |
| **World Load Time (Continue)** | 7.50 s | 4.45 s | **3.40 s** | -55% Load time |

---

## Multi-Core Architecture

Stock Project Zomboid runs almost all spatial math, visibility calculations, and animation updates on a single thread. PZO offloads these calculations to a worker thread pool while keeping game state mutations thread-safe:

```
                              ┌──────────────────────────────────────────────┐
                              │      Dedicated P-Core Worker Thread Pool      │
                              │     (Pinned CPU Performance Core Affinity)    │
                              └──────┬──────────────┬──────────────┬─────────┘
                                     │              │              │
              ┌──────────────────────┴──────┐       │       ┌──────┴──────────────────────┐
              ▼                             ▼       ▼       ▼                             ▼
   ┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
   │       Pillar 1       │   │       Pillar 2       │   │       Pillar 3       │   │       Pillar 4       │
   │ MultiCoreChunkStream │   │ MultiCoreHordeGov    │   │ MultiCoreAnimation   │   │ MultiCoreIslandSched │
   │                      │   │                      │   │                      │   │                      │
   │ • 1MB Direct NIO Ring│   │ • AVX2 SIMD Partitions│   │ • 64-Bone Frustum    │   │ • 32x32 Spatial      │
   │ • Immediate Streamer │   │ • Frustum AABB Cull  │   │   Skinning Bypass    │   │   Island Buckets     │
   │   Queue Wake-Up      │   │ • Parallel FOV Cones │   │ • Distant 75% Frame  │   │ • Guaranteed Kahlua  │
   │ • Parallel Zlib Infl │   │ • Crowd Flocking Vec │   │   Skip Downsampling  │   │   Lua VM Safety      │
   └──────────────────────┘   └──────────────────────┘   └──────────────────────┘   └──────────────────────┘
```

### 1. Horde Simulation & Spatial Culling (`MultiCoreHordeGovernor`)
* **Partitioned Sweeps**: Active entities are partitioned into 256-zombie batches across CPU cores.
* **Native AVX2 Vector Math**: Evaluates entity distances, LOD tiers, camera frustum culling, 90° FOV awareness cones, and crowd repulsion steering vectors in compiled AVX2 SIMD assembly.
* **Lock-Free Snapshots**: Writes results to atomic snapshot arrays (`SNAPSHOT_DISTANCES`, `SNAPSHOT_TIERS`, `SNAPSHOT_MASK`, `SNAPSHOT_FOV`), giving the main thread instant reads without lock contention.

### 2. Skeletal Animation LOD (`MultiCoreAnimationEngine`)
* **Frustum Bone Bypass**: Skips all 64 bone matrix transformations for offscreen entities or entities culled outside the camera frustum.
* **Adaptive Frame Skipping**: Scales bone matrix updates for distant entities (>50 tiles away), cutting animation CPU overhead while maintaining full animation quality for nearby zombies.

### 3. Visibility Caching (`PlayerLosOptimizer`)
* **Eliminates $O(N^2)$ Scans**: In large hordes, stock PZ's synchronized tracking stack (`lastSpotted.contains(zombie)`) runs an $O(N)$ linear scan per entity, causing combat frame drops.
* **Thread-Local Hash Mirror**: PZO maintains an $O(1)$ identity-hash mirror alongside the tracking stack, returning visibility results instantly while preserving vanilla stack behavior.

### 4. Chunk Streamer Dispatch (`StreamerWake` & `MultiCoreChunkStreamer`)
* **Immediate Thread Dispatch**: Stock PZ throttles chunk requests on the game thread. PZO unparks and signals the background chunk streamer thread immediately upon enqueue.
* **Direct NIO Ring**: Allocates off-heap direct memory buffers for parallel chunk decompression, reducing driving stutter at high speeds.

---

## Render Optimizations

### Single-Pass Quarter-Resolution Fog (`FogQuarterBufferGovernor`)
* **The Bottleneck**: Stock draws heavy weather fog as up to 12 overlapping screen-wide rectangles per tile row across multiple levels, running 7-octave noise texture math directly across the full scene buffer (50–100+ separate draw calls).
* **The Optimization**: Batches all fog rectangles into a single VBO using vertex attributes for coordinates, fade bounds, and noise offsets. Dispatches all rectangles in a **single GPU draw call** into an offscreen quarter-resolution framebuffer (50% width × 50% height = 25% fragment fill rate), cutting 75% of GPU fragment shading math. Re-composites the buffer with bilinear filtering in one pass.

### Persistent Mapped VBOs (`PersistentVBOGovernor`)
* **Zero-Copy Memory Mapping**: Uses OpenGL 4.4+ / `GL_ARB_buffer_storage` (`GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT`) to keep vertex buffers permanently mapped in memory.
* **Zero-Stall Fences**: Multi-slot ring buffers synchronized with lightweight GPU fences (`glFenceSync` / `glClientWaitSync`) eliminate per-frame `glBufferData` reallocations and driver stalls. Falls back cleanly to dynamic streaming on macOS and older OpenGL hardware.

### High-Precision Timer & GC Tuning (`HighPrecisionTimer`)
* **Windows Multimedia Timer**: Sets the OS timer resolution to 1.0ms for consistent frame pacing and reduced micro-stutter.
* **Low-Latency GC Tuning**: Optimizes G1GC heap regions and background collection thresholds to prevent stop-the-world pauses during driving and combat.

---

## Installation

### Windows (1-Click)
1. Download **`PZO-Optimizer-Windows.zip`** from [GitHub Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Extract the archive and double-click **`Install.bat`**.
3. Launch Project Zomboid normally through Steam.
*(To update or uninstall, simply run `Install.bat` again).*

### Linux & Steam Deck (SteamOS)
1. Download **`PZO_Optimizer_macOS_Linux.zip`** from [GitHub Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Open a terminal in the extracted folder and run:
   ```bash
   chmod +x pzo_optimizer.sh && ./pzo_optimizer.sh
   ```
3. Launch Project Zomboid normally through Steam.

### macOS (Apple Silicon & Intel)
1. Download **`PZO_Optimizer_macOS_Linux.zip`** from [GitHub Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Double-click **`pzo_optimizer.command`** (or run `bash pzo_optimizer.sh` in Terminal).
3. Launch Project Zomboid normally through Steam.

---

## Dedicated Server Optimization (`PZOServerEngine.jar`)

Engineered for dedicated server nodes and 3rd-party game hosts (Indifferent Broccoli, G-Portal, Nitrado, GTXGaming, BisectHosting, Pterodactyl panels, Hetzner, OVH, Docker).

* **Low-Latency Networking**: Pools off-heap direct NIO buffers and expands UDP socket throughput to reduce desync during combat and driving.
* **Multi-Core Zombie Pathfinding**: Scales server-side AI migration across host CPU cores.
* **Asynchronous World Saves**: 256KB disk write buffering for SQLite `.db` tables (`players.db`, `vehicles.db`) and chunk saves, eliminating save-interval lag spikes.

### Dedicated Server Setup:
1. Upload **`PZOServerEngine.jar`** to your server's root directory via SFTP/FTP.
2. In `ProjectZomboid64.json`, keep `"mainClass": "zombie/network/GameServer"` and add `"-javaagent:PZOServerEngine.jar"` to `"vmArgs"`:
   ```json
   "vmArgs": [
       "-javaagent:PZOServerEngine.jar",
       "-Djava.awt.headless=true",
       "-Xmx8G",
       ...
   ]
   ```
3. Restart your server from your host control panel. See [`README_SERVER.md`](README_SERVER.md) for full details.

---

## In-Game Controls & Telemetry

When using the [PZO Steam Workshop Mod](https://steamcommunity.com/sharedfiles/filedetails/?id=3787481250), press **F10** in-game to open the **PZO Control Center** or **F9** for the live performance HUD:

* **Tab 7 ([+] JVM Engine)**: Live toggles for Quarter-Res Fog, Persistent VBOs, Multi-Core SIMD, and Background GC.
* **Live Telemetry HUD**: Monitors exact frame times, VRAM saved, persistent buffer memory, bone transforms bypassed, and chunk streamer latency in real time.

---

## Compatibility

* **Mod Compatibility**: 100% compatible with all standard Lua mods, vehicle packs, weapon overhauls, tilepacks, and custom maps.
* **ZombieBuddy Coexistence**: Fully compatible with **ZombieBuddy** (`zbNative`). PZO automatically detects and preserves `"-agentlib:zbNative"` in `ProjectZomboid64.json`.

---

## Building from Source

### Requirements
* JDK 17+ (e.g. OpenJDK 17, 21, or 25)
* Python 3.8+ (for release packaging)

### Build Command
```bash
# Compile client and server classes
javac -d bin -cp bin -sourcepath "" $(find src -name "*.java")

# Package distribution JARs and release zip bundles
python3 package_release.py
```

---

## License
Licensed under the [MIT License](LICENSE).
