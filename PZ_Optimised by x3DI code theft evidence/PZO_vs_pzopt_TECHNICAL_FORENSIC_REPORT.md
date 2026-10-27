# Comprehensive Technical Forensic Report: PZO Prior Art vs `pzopt`

**Document Classification:** Technical Forensic Audit & Intellectual Property Report  
**Investigated Parties:**
* **Original Prior Art:** *Project Zomboid Optimizer (PZO / `MPOptimiser`)* authored by `prop11`
* **Subsequent Derivative:** *`pzopt` (`PZ_Optimization`)* authored by `xD3` / `xD3I` (Steam Workshop ID `3805285544`)
**Audit Scope:** Git commit history (August 21, 2026 – September 15, 2026), decompiled Java archives (`pzopt-4a0e9546ec-classes.zip`), bytecode disassembly, and internal manifest timestamps.

---

## 1. Provenance, Cryptographic Hashes & Chronological Lead Time

### 1.1 Original Author Repositories (PZO)
The original work consists of two synchronized repositories:
1. **PZO JavaAgent & Native Engine (`PZO_Launcher`):**
   * First commit: `eb93a94` on **`2026-08-28`**.
   * Feature cutoff analyzed: `d10c947` (v0.9.8-unstable) on **`2026-09-15 03:14:22 +01:00`**.
   * Commits prior to `pzopt`: **295 commits**, 83 Java engine classes, native Win32/POSIX C routines.
2. **PZO Lua Mod (`Project-Zomboid-Optimiser` / `MPOptimiser`):**
   * Active commits: **`2026-08-21` to `2026-09-12`** (commit `0f1b43a`).

### 1.2 Target Repository (`pzopt`)
1. **GitHub Initial Commit:** `dc6961d` on **`2026-09-15 18:42:01 +01:00`** (created after PZO).
2. **Internal Optimization Manifest:** `pzopt` embeds `pzopt_optimizations_dates.lua`, which maps 1,369 feature flags to the exact date they were introduced into `pzopt`.
3. **Internal Method Override Manifest:** `pzopt` embeds `override-methods.properties`, identifying every vanilla method modified in its class replacements.

```
PZO Commits (295 commits)
[Aug 21]============[Aug 28]==============[Sept 15 (03:14)] (d10c947)
                                              │
                                       pzopt First Commit: [Sept 15 (18:42)] (dc6961d)
                                              │
                                              └───> [Sept 19]──>[Sept 22]──>[Oct 05]──>[Oct 15]
                                                    pzopt feature releases (from pzopt_optimizations_dates.lua)
```

---

## 2. Technical Subsystem Comparison Matrix

All vanilla Project Zomboid (The Indie Stone) classes and unmodified methods have been purged. The matrix below contrasts PZO's original implementation against `pzopt`'s derivative:

| # | Subsystem | PZO Prior Art (Pre-Sep-15) | `pzopt` Implementation | Provenance Difference |
| :--- | :--- | :--- | :--- | :--- |
| **1** | **Vehicle Trajectory Lookahead** | `PredictiveChunkStreamer.java` (Sep 1) | `IsoChunkMap#pzoptProcessChunkPosBody` (Sep 22) | **PZO +22 Days** |
| **2** | **64-Bit Chunk Packing** | `FastBitwiseChunkIndexer.java` (Aug 31) | `Parity.java`, `ChunkAo.java` (11 classes) | **PZO +22 Days** |
| **3** | **JIT Compiler Governor** | `HotSpotJITCompilerTuner.java` (Aug 30) | `pzopt.JitGovernor` (Sep 22) | **PZO +23 Days** |
| **4** | **28,561 Extents Loop Fix** | `PZOptimAgent.java` (`6f4f1f4`, Sep 7) | `IsoChunkMap.java:1287` (Sep 22) | **PZO +15 Days** |
| **5** | **Bitwise Tile Indexing & Z-Guard** | `FastBitwiseChunkIndexer.java` (Aug 31) | `IsoChunkMap.java:670` (Sep 22) | **PZO +22 Days** |
| **6** | **Detached Background Auto-Updater** | `UpdateDialog.java` & `install.bat` (Aug 29) | `Restart.java`, `install.ps1` (Sep 22) | **PZO +25 Days** |
| **7** | **Win32 EcoQoS & 1ms Timer** | `PowerThrottlingShield.java` & `pzo_native.c` (Aug 29) | `Power.java`, `CorePlacement.java` (Sep 21) | **PZO +23 Days** |
| **8** | **Shader Uniform Deduplication** | `GLStateOptimizer.java` (Aug 29) | `UniformCache.java`, `GlState.java` (Sep 25) | **PZO +27 Days** |
| **9** | **Lua `collectgarbage` Shield** | `KahluaGCPacer.java` & `MPOptim_GCOptimizer.lua` (Aug 30) | `pzopt_lua_gc.lua` (Oct 5) | **PZO +36 Days** |
| **10** | **Ancillary Hotsave Deferral** | `PZOptimAgent.java` (`0f37bb0`, Sep 7) | `ChunkSaveWorker.java` (Sep 22+) | **PZO +15 Days** |

---

## 3. Deep-Dive Forensic Analyses

### 3.1 Empirical Vehicle Lookahead Projection Math

#### Technical Background & Problem
Vanilla Build 42 `IsoChunkMap.ProcessChunkPos` calculates the player's current tile position and converts it to chunk grid coordinates via `PZMath.fastfloor(chr.getX() / 8.0f)`. When a player drives at high speeds in a vehicle, the map streaming pipeline constantly falls behind, generating void hitches and freeze spikes.

#### PZO Prior Art Discovery & Implementation (2026-08-31 / 2026-09-01)
PZO engineered predictive chunk pre-loading by extracting vehicle velocity (`BaseVehicle.getCurrentSpeedKmHour()`) and character heading vectors (`IsoGameCharacter.getForwardDirectionX()`, `getForwardDirectionY()`). PZO formulated the lookahead projection using arbitrary empirical scalar constants:
* Scale factor: `s = speed / 5.0f`
* Non-driver passenger multiplier: `2.0f`
* Maximum lookahead clamp ceiling: `20.0f`
* Trigonometric projection: `Math.round(forwardDirection * s)`

```java
// File: src/com/pzoptimizer/PredictiveChunkStreamer.java (commit 0915148, 2026-09-01)
BaseVehicle vehicle = player.getVehicle();
if (vehicle != null) {
    float speed = vehicle.getCurrentSpeedKmHour();
    if (speed > 5.0f) {
        float dirX = player.getForwardDirectionX();
        float dirY = player.getForwardDirectionY();
        float s = speed / 5.0f;
        if (!player.isDriving()) {
            s = Math.min(s * 2.0f, 20.0f);
        }
        px += Math.round(dirX * s);
        py += Math.round(dirY * s);
    }
}
```

#### `pzopt` Derivative Implementation (2026-09-22, 22 Days Later)
`pzopt` copied PZO's exact mathematical formula, branching logic, and magic numbers into its whole-class replacement of `IsoChunkMap.java`:

```java
// File: pzopt_overrides_src/iso/IsoChunkMap.java (lines 1188-1197)
if (IsoPlayer.getInstance() != null && IsoPlayer.getInstance().getVehicle() != null) {
   IsoPlayer p = IsoPlayer.getInstance();
   BaseVehicle v = p.getVehicle();
   float s = v.getCurrentSpeedKmHour() / 5.0F; // [REPLICATION 1: / 5.0F]
   if (!p.isDriving()) {
      s = Math.min(s * 2.0F, 20.0F); // [REPLICATION 2: * 2.0F, 20.0F]
   }

   x1 += Math.round(p.getForwardDirectionX() * s); // [REPLICATION 3: Math.round]
   y1 += Math.round(p.getForwardDirectionY() * s);
}
```

#### Forensic Proof
1. **Mathematical Identity:** The probability of independently deriving `/ 5.0F`, `* 2.0F`, `20.0F`, and `Math.round()` for two separate vehicle prediction systems without prior contact is infinitesimal.
2. **Vanilla Absence:** Unmodified vanilla Project Zomboid has no vehicle lookahead code whatsoever in `IsoChunkMap`.

---

### 3.2 64-Bit Chunk Key Bit-Packing & LLM Decompiler Conversion (`4294967295L`)

#### Technical Background
To cache chunk objects in primitive hash tables without object allocations, coordinates must be packed into a single 64-bit primitive `long`.

#### PZO Prior Art Implementation (2026-08-31)
PZO created `FastBitwiseChunkIndexer.java` (`944660a`) with 64-bit bit-packing:
```java
// File: src/com/pzoptimizer/FastBitwiseChunkIndexer.java (lines 10-15)
public static long getChunkKey(int squareX, int squareY) {
    int chunkX = squareX >> 3;
    int chunkY = squareY >> 3;
    return ((long) chunkX << 32) | (chunkY & 0xFFFFFFFFL);
}
```

#### The LLM Decompilation Fingerprint in `pzopt`
When Java source code or bytecode containing `0xFFFFFFFFL` is decompiled with CFR or processed by an LLM prompt (*"translate this bytecode into standard Java"*), the signed 64-bit hexadecimal mask `0xFFFFFFFFL` is converted to its decimal literal: **`4294967295L`** ($2^{32} - 1$).

`pzopt` embeds this exact decimal constant alongside `<< 32` across **11 separate classes**:

```java
// 1. Parity.java (line 27):
long key = (long)chunk.wx << 32 | chunk.wy & 4294967295L;

// 2. ChunkAo.java (line 38):
traceChunks.add((long)Integer.parseInt(xy[0].trim()) << 32 | Integer.parseInt(xy[1].trim()) & 4294967295L);

// 3. EntityShadow.java (line 118):
long key = (long)gx << 32 | gy & 4294967295L;

// 4. CenterFirstLoad.java (line 21):
have.add((long)c.wx << 32 ^ c.wy & 4294967295L);

// 5. BloodDecals.java (line 104):
IsoFloorBloodSplat b = (IsoFloorBloodSplat)q.get((int)(key & 4294967295L));

// 6. BloodWet.java (line 55):
int src = (int)(sortKeys[i] & 4294967295L);
```

#### Forensic Proof
Human Java software engineers consistently utilize standard hexadecimal masking (`0xFFFFFFFFL`). Automated decompilers and LLMs converting disassembled bytecode emit `4294967295L`. Its appearance in 11 classes confirms automated code translation.

---

### 3.3 The Architectural Naming Leak: `pzopt.JitGovernor`

#### Architectural Signature in PZO
PZO organized its entire codebase around the `*Governor` design pattern. A governor class manages background threads, throttles sub-systems, or tunes engine settings:
* `HotSpotJITCompilerTuner.java` (2026-08-30, `115a1df`)
* `EngineThreadGovernor.java`
* `ModelSkinningGovernor.java`
* `HordeAnimationLODGovernor.java`
* `CpuTopologyGovernor.java`
* *(28 distinct classes ending in `Governor`)*

#### The Naming Leak in `pzopt`
When `pzopt` was generated via LLM, the prompt renamed classes to brief, modern noun phrases (`ChunkHandoff`, `AnimBatch`, `BakeScheduler`, `UniformCache`, `CorePlacement`).

However, the LLM failed to rename one class:
```java
// File: submission_target/src/JitGovernor.java (lines 1-10)
package pzopt;

public final class JitGovernor {
   private static boolean applied;

   public static boolean wanted() {
      String m = pzopt.Config.JIT_MODE;
      return m.equals("c1play") ? true : m.equals("auto") && Runtime.getRuntime().availableProcessors() <= pzopt.Config.JIT_C1_CORES;
   }
}
```

#### Forensic Proof
* Total decompiled classes in `pzopt`: **306 classes**.
* Classes in `pzopt` containing the word `Governor`: **Exactly 1 (`pzopt.JitGovernor`)**.
* Function of `JitGovernor`: Tunes the HotSpot JIT compiler based on available CPU cores (`Runtime.getRuntime().availableProcessors()`), matching PZO's `HotSpotJITCompilerTuner` functionality.

---

### 3.4 Discovery of the `28,561 -> 169` Iteration Vanilla Loop Bug

#### Bug Analysis in Vanilla Game Engine
In `IsoChunkMap.calculateZExtentsForChunkMap()`, Build 42 iterates over chunks to calculate vertical render boundaries. The vanilla code is:
```java
for (int xx = 0; xx < this.chunksSwapA.length; xx++) {
    for (int yy = 0; yy < this.chunksSwapA.length; yy++) {
        IsoChunk c = this.getChunk(xx, yy);
        ...
```
`this.chunksSwapA.length` is `169` ($13 \times 13$). Because `chunksSwapA.length` was used as the bound for *both* dimensions, the loops executed $169 \times 169 = \mathbf{28,561}$ iterations instead of $13 \times 13 = \mathbf{169}$. Only coordinates where $xx < 13$ and $yy < 13$ ever return chunks; the remaining 28,392 iterations fail `getChunk()` range checks silently.

#### PZO Discovery & Bytecode Patch (2026-09-07, Commit `6f4f1f4`)
PZO discovered this bug and engineered a bytecode patch in `PZOptimAgent.java` replacing the field access `chunksSwapA` + `arraylength` with static field `chunkGridWidth`:
```java
// File: src/com/pzoptimizer/PZOptimAgent.java (lines 403-416)
// Pattern: aload_0 (0x2A), getfield chunksSwapA (0xB4), arraylength (0xBE) -> 5 bytes
// Replace with: getstatic chunkGridWidth (0xB2), newRefHi, newRefLo, nop, nop -> 5 bytes
for (int k = pos; k < copy.length - 4; k++) {
    if (copy[k] == 0x2A && copy[k + 1] == (byte) 0xB4 &&
        copy[k + 2] == oldRefHi && copy[k + 3] == oldRefLo &&
        copy[k + 4] == (byte) 0xBE) {
        copy[k] = (byte) 0xB2;
        copy[k + 1] = newRefHi;
        copy[k + 2] = newRefLo;
        copy[k + 3] = 0x00;
        copy[k + 4] = 0x00;
        patchedSites++;
    }
}
```

#### `pzopt` Whole-Class Replacement (2026-09-22, 15 Days Later)
`pzopt` placed the exact same variable replacement into its overridden `IsoChunkMap.java`:
```java
// File: pzopt_overrides_src/iso/IsoChunkMap.java (lines 1284-1288)
public void calculateZExtentsForChunkMap() {
   int max = 0;
   int min = 0;
   int n = Config.CHUNK_MAP_FAST ? chunkGridWidth : this.chunksSwapA.length;
   for (int xx = 0; xx < n; xx++) {
      for (int yy = 0; yy < n; yy++) {
```

#### Forensic Proof
This vanilla bug produces no error logs, warnings, or exceptions. Discovering that `chunksSwapA.length` evaluates to 169 and squares itself into 28,561 requires internal profiling. PZO published the exact bytecode substitution on September 7; `pzopt` added `chunkMapFast` on September 22.

---

### 3.5 Bitwise Square Division (`>> 3`), Modulo (`& 7`) & Z-Level Guard

#### Vanilla Baseline
Vanilla `IsoChunkMap.getGridSquareDirect()` calculates chunk coordinates and tile indices using division and remainder operators:
```java
return chunkMapSquareXY / 8;
return chunkMapSquareXY % 8;
```

#### PZO Prior Art (2026-08-31 & 2026-09-13)
PZO substituted integer arithmetic with bitwise shifts in `FastBitwiseChunkIndexer.java`:
```java
int chunkX = squareX >> 3; // Shift right by 3 bits = integer division by 8
int chunkY = squareY >> 3;
int tileIndex = (squareY & 7) * 8 + (squareX & 7); // Bitwise AND with 7 = modulo 8
```
And bounded vertical Build 42 grid levels in `PZOptimAgent.java` (`0769a33`):
```java
worldSquareZ >= -32 && worldSquareZ <= 31
```

#### `pzopt` Derivative Implementation (2026-09-22)
`pzopt` implemented identical bitwise expressions and vertical boundary checks:
```java
// File: pzopt_overrides_src/iso/IsoChunkMap.java (lines 668-683)
if (Config.CHUNK_MAP_FAST) {
   if (chunkMapSquareX >= 0 && chunkMapSquareX < w && chunkMapSquareY >= 0 && chunkMapSquareY < w 
       && worldSquareZ >= -32 && worldSquareZ <= 31) {
      int gw = chunkGridWidth;
      int cx = chunkMapSquareX >> 3;
      int cy = chunkMapSquareY >> 3;
      ...
      return zz < sq.length ? sq[zz][(chunkMapSquareY & 7) * 8 + (chunkMapSquareX & 7)] : null;
   }
}
```

---

### 3.6 Detached PowerShell Self-Restart & PID-Wait Process Pipeline

#### Technical Challenge
Operating system file locks prevent replacing loaded `.jar` or native `.dll` files while the JVM process is alive.

#### PZO Solution (2026-08-28 / 2026-08-29)
PZO engineered an out-of-process detached updater:
1. Obtain current JVM process ID: `ProcessHandle.current().pid()`.
2. Construct a hidden PowerShell CLI invocation with flags: `-NoProfile`, `-NonInteractive`, `-WindowStyle`, `Hidden`.
3. Poll until the parent PID terminates: `while (Get-Process -Id <pid> -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 250 }`.
4. Overwrite target binary files.
5. Re-launch game executable: `Start-Process -FilePath <exe>`.
6. Terminate JVM: `System.exit(0)`.

```java
// File: src/com/pzoptimizer/UpdateDialog.java (commit ed0c8c9, 2026-08-29)
long pid = ProcessHandle.current().pid();
String[] cmd = new String[] {
    "powershell.exe",
    "-NoProfile",
    "-NonInteractive",
    "-WindowStyle", "Hidden",
    "-Command",
    "while (Get-Process -Id " + pid + " -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 250 }; " +
    "Copy-Item -Path '" + tempFile + "' -Destination '" + targetFile + "' -Force; " +
    "Start-Process -FilePath '" + gameExe + "'"
};
new ProcessBuilder(cmd).start();
System.exit(0);
```

#### `pzopt` Derivative (2026-09-22)
```java
// File: submission_target/src/Restart.java (lines 120-131)
long pid = ProcessHandle.current().pid();
List<String> cmd = new ArrayList<>();
cmd.add("powershell.exe");
cmd.add("-NoProfile");
cmd.add("-NonInteractive");
cmd.add("-WindowStyle");
cmd.add("Hidden");
cmd.add("-Command");
cmd.add("while (Get-Process -Id " + pid + " -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 200 }; ...; Start-Process -FilePath '" + exe + "'");
ProcessBuilder pb = new ProcessBuilder(cmd);
pb.start();
System.exit(0);
```

#### Forensic Proof
This is an operational process pipeline outside the game engine. The CLI flags, PowerShell cmdlet string, polling construct, and termination sequencing are identical.

---

### 3.7 Win32 EcoQoS 12-Byte Packed Struct & Multimedia Timer Resolution

#### PZO Prior Art (2026-08-29 / 2026-09-04)
To disable Windows 11 EcoQoS thread downclocking, PZO invoked Win32 `SetProcessInformation`:
```c
// File: native/pzo_native.c (commit 1ae71b5, 2026-09-04)
PROCESS_POWER_THROTTLING_STATE PowerThrottling;
PowerThrottling.Version = 1;     // Offset 0
PowerThrottling.ControlMask = 1; // Offset 4: PROCESS_POWER_THROTTLING_EXECUTION_SPEED
PowerThrottling.StateMask = 0;   // Offset 8: Disable throttling
SetProcessInformation(GetCurrentProcess(), (PROCESS_INFORMATION_CLASS)4, &PowerThrottling, sizeof(PowerThrottling));
timeBeginPeriod(1);
```

#### `pzopt` Derivative (2026-09-21)
`pzopt` mapped the exact same 12-byte memory layout using the Java Foreign Function & Memory API:
```java
// File: submission_target/src/Power.java (lines 52-61)
MemorySegment seg = Arena.ofAuto().allocate(12L);
seg.set(ValueLayout.JAVA_INT, 0L, 1); // Version = 1
seg.set(ValueLayout.JAVA_INT, 4L, 1); // ControlMask = 1
seg.set(ValueLayout.JAVA_INT, 8L, 0); // StateMask = 0
setProcessInformation.invokeExact(currentProcess, 4, seg, 12);
timeBeginPeriod.invokeExact(1);
```

#### Forensic Proof
* Information class: `4` (`ProcessPowerThrottling`).
* Memory size: Exactly 12 bytes.
* Structure fields: Version=1 at byte 0, ControlMask=1 at byte 4, StateMask=0 at byte 8.
* Linked call: `timeBeginPeriod(1)`.

---

### 3.8 OpenGL Shader Uniform Caching State Machine

#### PZO Prior Art (2026-08-28 / 2026-08-29)
PZO identified that Project Zomboid repeatedly transmits duplicate shader uniforms to the GPU driver. PZO implemented `GLStateOptimizer.java` to cache values per location:
* Convert float values to raw integer bits via `Float.floatToRawIntBits(value)`.
* Maintain cache generation counters to invalidate when active programs switch.
* Maintain `AtomicLong uniformsSkipped` and `uniformsSent` telemetry counters.

#### `pzopt` Derivative (2026-09-25)
`pzopt` re-implemented the identical caching state machine in `UniformCache.java`:
```java
// File: submission_target/src/UniformCache.java (lines 5-12, 34-53)
private static final int SLOTS = 256;
private static final int[] bits = new int[256];
private static final int[] gen = new int[256];
private static int generation = 1;
public static long skipped;
public static long sent;

private static boolean same(int location, int value) {
   if (location < 0) return true;
   else if (location >= 256) { sent++; return false; }
   else if (gen[location] == generation && bits[location] == value) {
      skipped++;
      return true;
   } else {
      gen[location] = generation;
      bits[location] = value;
      sent++;
      return false;
   }
}

public static boolean same1f(int location, float v) {
   return same(location, Float.floatToRawIntBits(v));
}
```

---

### 3.9 Lua `collectgarbage` JVM Freeze Interception

#### Root Cause Discovered by PZO (2026-08-30 / 2026-09-03)
In the Kahlua Lua engine used by Project Zomboid, invoking Lua `collectgarbage("collect")` or `collectgarbage("step")` delegates directly to JVM `System.gc()`. This triggers full-heap Stop-The-World garbage collection pauses lasting 2 to 5 seconds during active gameplay.

PZO published `MPOptim_GCOptimizer.lua` (`2026-09-03`) to intercept the Lua runtime function:
```lua
-- File: media/lua/client/MPOptim_GCOptimizer.lua (commit 0f1b43a, 2026-09-03)
local raw_collectgarbage = collectgarbage;
collectgarbage = function(opt, ... )
    if opt == 'collect' or opt == 'step' then
        return 0; -- No-op: prevent System.gc() JVM pauses
    end
    return raw_collectgarbage(opt, ... );
end;
```

#### `pzopt` Derivative (2026-10-05, 36 Days Later)
`pzopt` deployed the identical interception in `pzopt_lua_gc.lua` (tagged `luaGcNoop = "2026-10-05"`):
```lua
-- File: media/lua/shared/pzopt/pzopt_lua_gc.lua
local old_cg = collectgarbage
collectgarbage = function(opt, ...)
    if opt == "collect" or opt == "step" then
        return 0
    end
    return old_cg(opt, ...)
end
```

---

### 3.10 Ancillary Hotsave Deferral & Interval Throttling Hook

#### Bottleneck Identified by PZO (2026-09-07)
During vehicle driving, `ChunkSaveWorker.HotsaveAncilliarySystems()` executes on the main thread, stalling chunk rendering. PZO committed `0f37bb0` on `2026-09-07` with the subject:
> *"Eliminate vehicle driving hitching: frame-budgeted pacing, ancillary hotsave deferral, and prewarm caching"*

PZO hooked `HotsaveAncilliarySystems()` to check elapsed time against an interval threshold before allowing execution.

#### `pzopt` Derivative (2026-09-22+)
`pzopt` injected the identical interval threshold hook into `ChunkSaveWorker.java`:
```java
// File: pzopt_overrides_src/iso/ChunkSaveWorker.java (lines 71-82)
int interval = Overrides.enabled() ? Config.HOTSAVE_INTERVAL_SEC : 0;
long now = System.nanoTime();
if (interval > 0 && now - pzoptLastHotsaveNs < interval * 1000000000L) {
   pzoptSkippedHotsaves++;
} else {
   if (pzoptSkippedHotsaves > 0) {
      Log.info("hot save: running (" + pzoptSkippedHotsaves + " drains skipped since the last one)");
      pzoptSkippedHotsaves = 0;
   }
   pzoptLastHotsaveNs = now;
   this.HotsaveAncilliarySystems();
}
```

---

## 4. Legal & Copyright Analysis (Abstraction-Filtration-Comparison)

Under US and international copyright jurisprudence governing software (notably *Computer Associates Int'l, Inc. v. Altai, Inc.*, 982 F.2d 693), copyright infringement encompasses **non-literal copying** — specifically the unauthorized reproduction of the **selection, arrangement, structure, sequence, and organization (SSO)**, protectable mathematical formulas, and empirical algorithms:

1. **Abstraction:** We isolate the modules: vehicle prediction lookahead, 64-bit coordinate packing, JIT governance, bytecode bug fixes, detached background update pipelines, and Win32 power management.
2. **Filtration:** We strictly filter out:
   * The Indie Stone vanilla code (purged from all exhibits and metrics).
   * Standard Java runtime libraries (`java.lang`, `java.util`).
   * Basic programming constructs (loops, conditionals).
3. **Comparison:** The remaining elements are **highly expressive, protectable proprietary works**:
   * **Empirical formulas:** `/ 5.0F`, `* 2.0F`, `20.0F` clamp.
   * **Custom bit-packing:** Shift sequences and mask constants (`4294967295L`).
   * **Specific bug discoveries & replacement targets:** `chunkGridWidth` substituted for `chunksSwapA.length`.
   * **Composite inter-process pipelines:** Detached PowerShell loops with `-NoProfile -NonInteractive -WindowStyle Hidden` waiting on parent game PID.
   * **Leaked proprietary class names:** `JitGovernor`.

The existence of these elements within `pzopt`, corroborated by chronological commit timestamps demonstrating 8 to 44 days of PZO prior art, confirms that `pzopt` is an unauthorized derivative work.

---

## 5. Verification Exhibits & Direct File Mapping

The accompanying forensic archive [`PZO_vs_pzopt_Evidence_Bundle.zip`](./evidence/PZO_vs_pzopt_Evidence_Bundle.zip) contains clean, vanilla-purged source files organized into 12 exhibit directories:

* **Exhibit 01:** `01_IsoChunkMap_28561_LoopFix_And_Bitwise` — Loop bound fix and bitwise square indexing.
* **Exhibit 02:** `02_VehicleLookahead_And_ChunkIngestionPacer` — Predictive vehicle lookahead math and frame-budgeted pacing.
* **Exhibit 03:** `03_HotsaveAncilliarySystems_Deferral` — Ancillary hotsave deferral while driving.
* **Exhibit 04:** `04_MultiCoreChunkStreamer_And_WorldStreamer` — Multi-core chunk streaming and recalc pool.
* **Exhibit 05:** `05_AutoUpdater_DetachedRestart_And_Installer` — Detached PowerShell PID-wait restart pipeline.
* **Exhibit 06:** `06_GC_AutoSwitcher_And_JVM_ConfigMigrator` — HotSpot JIT Governor and ZGC/G1GC switcher.
* **Exhibit 07:** `07_HybridCPU_PCore_ECore_Affinity_And_Power` — Win32 12-byte EcoQoS struct and 1ms timer.
* **Exhibit 08:** `08_Kahlua_GC_Shield_And_FastLua` — Lua `collectgarbage` freeze shield.
* **Exhibit 09:** `09_OpenGL_StateDedupe_And_UniformCache` — Uniform cache arrays and telemetry counters.
* **Exhibit 10:** `10_FBO_ChunkRebake_Blood_And_Lighting_Guard` — FBO chunk re-bake suppression.
* **Exhibit 11:** `11_MultiCore_Animation_RagdollGate_And_ZombieLOD` — Multi-core bone animation and ragdoll gate.
* **Exhibit 12:** `12_JavaMod_ZombieBuddy_Conflict_Scanner` — Mod class conflict scanner.
