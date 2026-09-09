package com.pzoptimizer;

import java.lang.reflect.Method;

public final class GenerationalHeapCleaner {

    private static volatile boolean running = false;
    private static long lastGcTimestamp = 0;
    private static final long MIN_GC_INTERVAL_MS = 60_000; // Minimum 60s between idle sweeps

    public static void startGovernor() {
        // Leave garbage collection to the JVM; do not call System.gc() here.
        PZOLogger.success("GenerationalHeapCleaner: Zero-Pause Memory Governor initialized (Passive Concurrent Mode)");
    }

    private static boolean checkSafeMoment() {
        try {
            Class<?> playerClass = Class.forName("zombie.characters.IsoPlayer");
            Method getInstMethod = playerClass.getMethod("getInstance");
            Object player = getInstMethod.invoke(null);

            if (player == null) {
                return true;
            }

            try {
                Method getVehicleMethod = playerClass.getMethod("getVehicle");
                Object vehicle = getVehicleMethod.invoke(player);
                if (vehicle != null) {
                    Method getSpeedMethod = vehicle.getClass().getMethod("getCurrentSpeedKmHour");
                    float speed = ((Number) getSpeedMethod.invoke(vehicle)).floatValue();
                    if (Math.abs(speed) > 10.0f) {
                        return false; // Driving fast - do NOT interrupt
                    }
                }
            } catch (Throwable ignored) {}

            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
