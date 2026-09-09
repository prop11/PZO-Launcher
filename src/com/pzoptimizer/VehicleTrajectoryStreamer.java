package com.pzoptimizer;

/** Compatibility entry point delegating to PredictiveChunkStreamer. */
public final class VehicleTrajectoryStreamer {

    public static void start() {
        PredictiveChunkStreamer.start();
    }

    public static void stop() {
        PredictiveChunkStreamer.stop();
    }
}
