package com.pzoptimizer;

import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

/**
 * PZO Enhanced Rendering Telemetry & Performance Gain Monitor.
 * 
 * Tracks real-time metrics and estimated hardware resource savings across:
 * - Phase 3 SIMD AVX2 Batch Horde Spatial Culler (HordeSpatialCuller)
 * - 3D Dynamic Skeletal Bone Skinning Governor (HordeAnimationLODGovernor & ModelSkinningGovernor)
 * - 2D Screen-space Draw Call Culling (RenderFrustumCuller)
 * - 32-Level Subterranean Level Occlusion (ZOcclusionCuller)
 * - OpenGL JNI Driver State Filter (GLStateOptimizer)
 * 
 * Only active in Unstable / Beta channel builds.
 * Writes pzo_render_telemetry.json and feeds into live HUD / clipboard diagnostics.
 */
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
        snap.boneTransformsSaved = ModelSkinningGovernor.getSavedCount() + HordeAnimationLODGovernor.getBoneTransformsSaved();
        snap.glCallsFiltered = GLStateOptimizer.getGlCallsFiltered();
        snap.hordeZombiesTracked = HordeSpatialCuller.lastTrackedZombieCount.get();
        snap.hordeCulledOffscreen = HordeSpatialCuller.lastCulledOffscreenCount.get();
        snap.hordeHibernating = HordeSpatialCuller.lastHibernatingCount.get();

        // Hardware cost weightings:
        // ~0.0015 ms CPU time saved per culled draw call + state check
        // ~0.0035 ms GPU raster/vertex time saved per subterranean tile
        // ~0.0006 ms CPU SIMD time saved per 4x4 bone matrix multiplication
        // ~0.0010 ms JNI driver switch time saved per redundant GL call
        snap.estimatedCpuMsSaved = (snap.drawsCulled * 0.0015) + (snap.boneTransformsSaved * 0.0006) + (snap.glCallsFiltered * 0.0010);
        snap.estimatedGpuMsSaved = (snap.drawsCulled * 0.0012) + (snap.subterraneanTilesCulled * 0.0035);

        // Baseline frame budget 16.6ms (60 FPS); compute estimated efficiency dividend
        double frameSavings = Math.min(8.0, (snap.estimatedCpuMsSaved + snap.estimatedGpuMsSaved) / 1000.0);
        snap.estimatedFpsGainPercent = Math.min(75.0, (frameSavings / 16.6) * 100.0);

        return snap;
    }

    public static String toJson() {
        MetricsSnapshot s = getSnapshot();
        return String.format(Locale.US,
            "{\"unstable_active\":%b,\"avx2_spatial\":%b,\"draws_culled\":%d,\"subterranean_culled\":%d,\"bones_saved\":%d,\"gl_filtered\":%d,\"horde_tracked\":%d,\"horde_culled\":%d,\"horde_hibernating\":%d,\"cpu_saved_ms\":%.2f,\"gpu_saved_ms\":%.2f,\"fps_gain_pct\":%.1f}",
            s.isUnstableActive, s.avx2SpatialActive, s.drawsCulled, s.subterraneanTilesCulled, s.boneTransformsSaved, s.glCallsFiltered,
            s.hordeZombiesTracked, s.hordeCulledOffscreen, s.hordeHibernating,
            s.estimatedCpuMsSaved, s.estimatedGpuMsSaved, s.estimatedFpsGainPercent);
    }

    public static String getTelemetryReport() {
        MetricsSnapshot s = getSnapshot();
        if (!s.isUnstableActive) {
            return "- **Enhanced Render Telemetry**: Inactive (Runs exclusively on Unstable / Beta builds)\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#### Enhanced Rendering & Phase 3 SIMD Telemetry (Unstable Channel)\n");
        sb.append(String.format(Locale.US, "- **SIMD AVX2 Spatial Processor**: %s\n", s.avx2SpatialActive ? "ACTIVE (8-wide YMM registers)" : "SCALAR FALLBACK"));
        sb.append(String.format(Locale.US, "- **Horde Zombies Monitored**: %,d (Offscreen Culled: %,d | Hibernating: %,d)\n",
            s.hordeZombiesTracked, s.hordeCulledOffscreen, s.hordeHibernating));
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
