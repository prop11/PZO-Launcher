package com.pzoptimizer;

import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

public final class EnhancedRenderTelemetry {

    public static class MetricsSnapshot {
        public boolean isUnstableActive;
        public boolean avx2SpatialActive;
        public long drawsCulled;
        public long subterraneanTilesCulled;
        public long boneTransformsSaved;
        public long glCallsFiltered;
        public int hordeZombiesTracked;
        public int hordeCulledOffscreen;
        public int hordeHibernating;
        public long throttledTownZombies;
        public long parallelChunksStreamed;
        public long parallelSimulatedEntities;
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
        snap.boneTransformsSaved = ModelSkinningGovernor.getSavedCount() + HordeAnimationLODGovernor.getBoneTransformsSaved() + com.pzoptimizer.multicore.PZOMultiCoreEngine.getBonesSaved();
        snap.glCallsFiltered = GLStateOptimizer.getGlCallsFiltered();
        snap.hordeZombiesTracked = HordeSpatialCuller.lastTrackedZombieCount.get();
        snap.hordeCulledOffscreen = HordeSpatialCuller.lastCulledOffscreenCount.get();
        snap.hordeHibernating = HordeSpatialCuller.lastHibernatingCount.get();
        snap.throttledTownZombies = VehicleTravelOptimizer.throttledTownZombies.get();
        snap.parallelChunksStreamed = com.pzoptimizer.multicore.PZOMultiCoreEngine.getParallelChunksStreamed();
        snap.parallelSimulatedEntities = com.pzoptimizer.multicore.PZOMultiCoreEngine.getParallelSimulatedEntities();
        snap.multiCoreWorkers = com.pzoptimizer.multicore.PZOMultiCoreEngine.getWorkerCount();

        // Assumed per-operation costs in milliseconds, not measured timings.
        snap.estimatedCpuMsSaved = (snap.drawsCulled * 0.0015) + (snap.boneTransformsSaved * 0.0006) + (snap.glCallsFiltered * 0.0010) + (snap.throttledTownZombies * 0.0040);
        snap.estimatedGpuMsSaved = (snap.drawsCulled * 0.0012) + (snap.subterraneanTilesCulled * 0.0035);

        // Compare the estimate with a fixed 16.6 ms frame budget.
        double frameSavings = Math.min(8.0, (snap.estimatedCpuMsSaved + snap.estimatedGpuMsSaved) / 1000.0);
        snap.estimatedFpsGainPercent = Math.min(75.0, (frameSavings / 16.6) * 100.0);

        return snap;
    }

    public static String toJson() {
        MetricsSnapshot s = getSnapshot();
        return String.format(Locale.US,
            "{\"unstable_active\":%b,\"avx2_spatial\":%b,\"draws_culled\":%d,\"subterranean_culled\":%d,\"bones_saved\":%d,\"gl_filtered\":%d,\"horde_tracked\":%d,\"horde_culled\":%d,\"horde_hibernating\":%d,\"throttled_town_zombies\":%d,\"parallel_chunks\":%d,\"parallel_simulated\":%d,\"workers\":%d,\"cpu_saved_ms\":%.2f,\"gpu_saved_ms\":%.2f,\"fps_gain_pct\":%.1f}",
            s.isUnstableActive, s.avx2SpatialActive, s.drawsCulled, s.subterraneanTilesCulled, s.boneTransformsSaved, s.glCallsFiltered,
            s.hordeZombiesTracked, s.hordeCulledOffscreen, s.hordeHibernating, s.throttledTownZombies,
            s.parallelChunksStreamed, s.parallelSimulatedEntities, s.multiCoreWorkers,
            s.estimatedCpuMsSaved, s.estimatedGpuMsSaved, s.estimatedFpsGainPercent);
    }

    public static String getTelemetryReport() {
        MetricsSnapshot s = getSnapshot();
        if (!s.isUnstableActive) {
            return "- **Enhanced Render Telemetry**: Inactive (Runs exclusively on Unstable / Beta builds)\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#### Enhanced Rendering & Multi-Core Scaling Telemetry (Unstable Channel)\n");
        sb.append(String.format(Locale.US, "- **P-Core Performance Workers**: %d Threads (Affinity Mask Active)\n", s.multiCoreWorkers));
        sb.append(String.format(Locale.US, "- **Parallel Chunks Streamed**: %,d chunks (Zero-Void Multi-Core Streaming)\n", s.parallelChunksStreamed));
        sb.append(String.format(Locale.US, "- **Spatial Island Parallel Simulation**: %,d entity updates\n", s.parallelSimulatedEntities));
        sb.append(String.format(Locale.US, "- **SIMD AVX2 Spatial Processor**: %s\n", s.avx2SpatialActive ? "ACTIVE (8-wide YMM registers)" : "SCALAR FALLBACK"));
        sb.append(String.format(Locale.US, "- **Horde Zombies Monitored**: %,d (Offscreen Culled: %,d | Hibernating: %,d)\n",
            s.hordeZombiesTracked, s.hordeCulledOffscreen, s.hordeHibernating));
        sb.append(String.format(Locale.US, "- **Driving Town Zombies Throttled**: %,d\n", s.throttledTownZombies));
        sb.append(String.format(Locale.US, "- **Screen-Space Draws Culled**: %,d\n", s.drawsCulled));
        sb.append(String.format(Locale.US, "- **Subterranean Z-Tiles Culled**: %,d\n", s.subterraneanTilesCulled));
        sb.append(String.format(Locale.US, "- **Off-Screen Bone Transforms Bypassed**: %,d\n", s.boneTransformsSaved));
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
