package com.pzoptimizer;

/** Delegates to PredictiveChunkStreamer; kept for existing callers. */
public final class VehicleTrajectoryStreamer {

    public static void start() {
        PredictiveChunkStreamer.start();
    }

    public static void stop() {
        PredictiveChunkStreamer.stop();
    }
}
