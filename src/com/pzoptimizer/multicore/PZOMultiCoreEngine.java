package com.pzoptimizer.multicore;

import com.pzoptimizer.PZOLogger;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the chunk streamer and reports what PZO runs across cores. The per-frame work (BoneBatch, ActionParallel)
 * runs on FrameWorkers, which starts itself on first use.
 */
public final class PZOMultiCoreEngine {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static volatile boolean active = false;

    public static synchronized void initialize() {
        if (initialized.get()) return;

        com.pzoptimizer.WorkerRandGovernor.ensureInstalled();
        MultiCoreChunkStreamer.initialize();

        initialized.set(true);
        active = true;

        PZOLogger.success(String.format(
            "[PZOMultiCoreEngine] Running across cores: chunk file reads (MultiCoreChunkStreamer), zombie bone math"
            + " (BoneBatch) and zombie action variable reads (ActionParallel) on up to %d frame workers."
            + " Zombie AI, physics and the world update stay on the game thread.",
            getWorkerCount()
        ));
    }

    public static boolean isMultiCoreActive() {
        return active;
    }

    public static int getWorkerCount() {
        return com.pzoptimizer.FrameWorkers.workerCount();
    }

    public static long getParallelChunksStreamed() {
        return MultiCoreChunkStreamer.getTotalChunksStreamed();
    }

    /** Zombie bone updates that ran through BoneBatch. */
    public static long getParallelBoneUpdates() {
        return com.pzoptimizer.BoneBatch.deferred.get();
    }

    /** Zombie action updates whose variable reads went to the workers. */
    public static long getParallelActionUpdates() {
        return com.pzoptimizer.ActionParallel.deferred.get();
    }

    public static long getPreloadedTrajectoryHits() {
        return com.pzoptimizer.PredictiveChunkStreamer.getPreloadedCacheHits();
    }

    public static String getTelemetryReport() {
        if (!active) {
            return "- **PZO Multi-Core Scaling**: Inactive\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#### PZO Multi-Core Work\n");
        sb.append(String.format(Locale.US, "- **Frame Workers**: %d threads (game thread helps)\n", getWorkerCount()));
        sb.append(String.format(Locale.US, "- **Parallel Chunks Streamed**: %,d chunks (Saved: %,d ms I/O void latency | Trajectory Hits: %,d)\n",
            getParallelChunksStreamed(), MultiCoreChunkStreamer.getTotalStreamTimeSavedMs(), getPreloadedTrajectoryHits()));
        sb.append(String.format(Locale.US, "- **Zombie Bone Updates On Workers**: %,d\n", getParallelBoneUpdates()));
        sb.append(String.format(Locale.US, "- **Zombie Action Updates Pre-Read On Workers**: %,d\n", getParallelActionUpdates()));
        return sb.toString();
    }

    public static void shutdown() {
        active = false;
        MultiCoreChunkStreamer.shutdown();
    }
}
