package com.pzoptimizer;

/** Just forwards to PredictiveChunkStreamer, kept for old callers. */
public final class VehicleTrajectoryStreamer {

    public static void start() {
        PredictiveChunkStreamer.start();
    }

    public static void stop() {
        PredictiveChunkStreamer.stop();
    }
}
