package com.pzoptimizer;

import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

/** Turns PZO's culling and throttling counters into rough CPU/GPU time saved. */
public final class EnhancedRenderTelemetry {

    public static class MetricsSnapshot {
        public boolean isUnstableActive;
        public boolean avx2SpatialActive;
        public long drawsCulled;
        public long subterraneanTilesCulled;
        public long parallelBoneUpdates;
        public long glCallsFiltered;
        public long throttledTownZombies;
        public long parallelChunksStreamed;
        public long parallelActionUpdates;
        public int multiCoreWorkers;
        public double estimatedCpuMsSaved;
        public double estimatedGpuMsSaved;
        public double estimatedFpsGainPercent;
    }

    public static MetricsSnapshot getSnapshot() {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.isUnstableActive = UnstableChannelGuard.isUnstableBuild();
        snap.avx2SpatialActive = PZONative.isLoaded() && PZONative.isAVX2Supported();

        if (!snap.isUnstableActive) {
            return snap;
        }

        snap.drawsCulled = RenderFrustumCuller.getCulledCount();
        snap.subterraneanTilesCulled = ZOcclusionCuller.getCulledCount();
        snap.parallelBoneUpdates = com.pzoptimizer.multicore.PZOMultiCoreEngine.getParallelBoneUpdates();
        snap.glCallsFiltered = GLStateOptimizer.getGlCallsFiltered();
        snap.throttledTownZombies = VehicleTravelOptimizer.throttledTownZombies.get();
        snap.parallelChunksStreamed = com.pzoptimizer.multicore.PZOMultiCoreEngine.getParallelChunksStreamed();
        snap.parallelActionUpdates = com.pzoptimizer.multicore.PZOMultiCoreEngine.getParallelActionUpdates();
        snap.multiCoreWorkers = com.pzoptimizer.multicore.PZOMultiCoreEngine.getWorkerCount();

        // Rough cost per thing saved:
        //   ~0.0015 ms CPU per culled draw call + state check
        //   ~0.0035 ms GPU per skipped underground tile
        //   ~0.0010 ms per redundant GL call (JNI + driver)
        //   ~0.0040 ms CPU per throttled distant zombie AI / pathfind tick
        snap.estimatedCpuMsSaved = (snap.drawsCulled * 0.0015) + (snap.glCallsFiltered * 0.0010) + (snap.throttledTownZombies * 0.0040);
        snap.estimatedGpuMsSaved = (snap.drawsCulled * 0.0012) + (snap.subterraneanTilesCulled * 0.0035);

        // Against a 16.6 ms (60 FPS) frame
        double frameSavings = Math.min(8.0, (snap.estimatedCpuMsSaved + snap.estimatedGpuMsSaved) / 1000.0);
        snap.estimatedFpsGainPercent = Math.min(75.0, (frameSavings / 16.6) * 100.0);

        return snap;
    }

    public static String toJson() {
        MetricsSnapshot s = getSnapshot();
        return String.format(Locale.US,
            "{\"unstable_active\":%b,\"avx2_spatial\":%b,\"draws_culled\":%d,\"subterranean_culled\":%d,\"gl_filtered\":%d,\"throttled_town_zombies\":%d,\"parallel_chunks\":%d,\"parallel_bone_updates\":%d,\"parallel_action_updates\":%d,\"workers\":%d,\"cpu_saved_ms\":%.2f,\"gpu_saved_ms\":%.2f,\"fps_gain_pct\":%.1f}",
            s.isUnstableActive, s.avx2SpatialActive, s.drawsCulled, s.subterraneanTilesCulled, s.glCallsFiltered,
            s.throttledTownZombies,
            s.parallelChunksStreamed, s.parallelBoneUpdates, s.parallelActionUpdates, s.multiCoreWorkers,
            s.estimatedCpuMsSaved, s.estimatedGpuMsSaved, s.estimatedFpsGainPercent);
    }

    public static String getTelemetryReport() {
        MetricsSnapshot s = getSnapshot();
        if (!s.isUnstableActive) {
            return "- **Enhanced Render Telemetry**: Inactive (Runs exclusively on Unstable / Beta builds)\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#### Enhanced Rendering & Multi-Core Scaling Telemetry (Unstable Channel)\n");
        sb.append(String.format(Locale.US, "- **Frame Workers**: %d threads (game thread helps)\n", s.multiCoreWorkers));
        sb.append(String.format(Locale.US, "- **Parallel Chunks Streamed**: %,d chunks\n", s.parallelChunksStreamed));
        sb.append(String.format(Locale.US, "- **Zombie Bone Updates On Workers**: %,d\n", s.parallelBoneUpdates));
        sb.append(String.format(Locale.US, "- **Zombie Action Updates Pre-Read On Workers**: %,d\n", s.parallelActionUpdates));
        sb.append(String.format(Locale.US, "- **AVX2 In Native Library**: %s\n", s.avx2SpatialActive ? "available" : "not available"));
        sb.append(String.format(Locale.US, "- **Driving Town Zombies Throttled**: %,d\n", s.throttledTownZombies));
        sb.append(String.format(Locale.US, "- **Screen-Space Draws Culled**: %,d\n", s.drawsCulled));
        sb.append(String.format(Locale.US, "- **Subterranean Z-Tiles Culled**: %,d\n", s.subterraneanTilesCulled));
        sb.append(String.format(Locale.US, "- **OpenGL JNI State Calls Filtered**: %,d\n", s.glCallsFiltered));
        sb.append(String.format(Locale.US, "- **Estimated Cumulative CPU Work Saved**: %.2f ms\n", s.estimatedCpuMsSaved));
        sb.append(String.format(Locale.US, "- **Estimated Cumulative GPU Work Saved**: %.2f ms\n", s.estimatedGpuMsSaved));
        sb.append(String.format(Locale.US, "- **Estimated Real-World FPS Dividend**: +%.1f%%\n", s.estimatedFpsGainPercent));
        return sb.toString();
    }

    public static void flushToDisk(File luaDir) {
        if (!UnstableChannelGuard.isUnstableBuild()) return;
        if (luaDir == null || !luaDir.exists()) return;

        try {
            File outFile = new File(luaDir, "pzo_render_telemetry.json");
            try (FileWriter fw = new FileWriter(outFile, false)) {
                fw.write(toJson());
            }
        } catch (Throwable ignored) {}
    }
}
