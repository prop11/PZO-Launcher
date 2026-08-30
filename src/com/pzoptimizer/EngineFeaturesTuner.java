package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Project Zomboid Engine Features & Architecture Tuner.
 * Automatically unlocks multi-threaded pathfinding, native navigation, and engine worker pools.
 */
public class EngineFeaturesTuner {

    public static void initializeEngineFeatures() {
        try {
            // 1. Unlock Multi-Threaded Pathfinding in Build 42 (DebugOptions.instance.threadPathfinding)
            try {
                Class<?> debugOptionsClass = Class.forName("zombie.debug.DebugOptions");
                Field instanceField = debugOptionsClass.getField("instance");
                Object debugOptionsInstance = instanceField.get(null);

                if (debugOptionsInstance != null) {
                    Field threadPathfindingField = debugOptionsClass.getField("threadPathfinding");
                    Object threadPathfinding = threadPathfindingField.get(debugOptionsInstance);
                    if (threadPathfinding != null) {
                        Method setValueMethod = threadPathfinding.getClass().getMethod("setValue", boolean.class);
                        setValueMethod.invoke(threadPathfinding, true);
                        PZOLogger.success("EngineFeaturesTuner: Multi-Threaded Pathfinding Unlocked [Threading.Pathfinding = true]");
                    }

                    // Native code pathfinding acceleration
                    try {
                        Field nativeCodeField = debugOptionsClass.getField("pathfindUseNativeCode");
                        Object nativeCode = nativeCodeField.get(debugOptionsInstance);
                        if (nativeCode != null) {
                            Method setValueMethod = nativeCode.getClass().getMethod("setValue", boolean.class);
                            setValueMethod.invoke(nativeCode, true);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable e) {
                PZOLogger.info("EngineFeaturesTuner: Threaded pathfinding hook skipped (B41 fallback or class not found)");
            }

            // 2. Tune PerformanceSettings Defaults
            try {
                Class<?> perfClass = Class.forName("zombie.core.PerformanceSettings");
                
                // Ensure new roof hiding is active
                try {
                    Field newRoofField = perfClass.getField("newRoofHiding");
                    newRoofField.setBoolean(null, true);
                } catch (Throwable ignored) {}

                // Enable lighting thread
                try {
                    Field lightThreadField = perfClass.getField("lightingThread");
                    lightThreadField.setBoolean(null, true);
                } catch (Throwable ignored) {}

                // Auto 3D zombies optimization
                try {
                    Field auto3DField = perfClass.getField("auto3DZombies");
                    auto3DField.setBoolean(null, true);
                } catch (Throwable ignored) {}

                PZOLogger.success("EngineFeaturesTuner: Core Engine PerformanceSettings Optimized");
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            PZOLogger.warn("EngineFeaturesTuner error: " + t.getMessage());
        }
    }
}
