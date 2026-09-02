package com.pzoptimizer;

/**
 * PZO Predictive Vehicle Trajectory & Chunk Stream Accelerator.
 * Subsumed and upgraded by PredictiveChunkStreamer for universal walking, sprinting, and driving acceleration.
 * Maintained as a seamless delegator for backward compatibility.
 */
public final class VehicleTrajectoryStreamer {

    public static void start() {
        PredictiveChunkStreamer.start();
    }

    public static void stop() {
        PredictiveChunkStreamer.stop();
    }
}
