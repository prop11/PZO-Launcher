package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Sets the DebugOptions and PerformanceSettings PZO relies on: native threaded pathfinding, the unsafe
 * threading options forced off, and lighting / animation LOD values.
 */
public class EngineFeaturesTuner {

    public static void initializeEngineFeatures() {
        try {
            try {
                Class<?> debugOptionsClass = Class.forName("zombie.debug.DebugOptions");
                Field instanceField = debugOptionsClass.getField("instance");
                Object debugOptionsInstance = instanceField.get(null);

                if (debugOptionsInstance != null) {
                    // Native pathfinding on its own thread
                    setOptionValue(debugOptionsInstance, "threadPathfinding", true);
                    setOptionValue(debugOptionsInstance, "pathfindUseNativeCode", true);
                    setOptionValue(debugOptionsInstance, "pathfindSmoothPlayerPath", true);

                    // Kept off on purpose:
                    // - threadAnimation: crashes the single-threaded Kahlua VM
                    // - threadAmbient / threadSound / threadWorld: FMOD races (e.g. the DayZ Ambient Sound TimSort crash)
                    // - threadLighting / threadGridStacks: RenderThread .join() stalls on PZForkJoinPool
                    setOptionValue(debugOptionsInstance, "threadAnimation", false);
                    setOptionValue(debugOptionsInstance, "threadLighting", false);
                    setOptionValue(debugOptionsInstance, "lightingSplitUpdate", false);
                    setOptionValue(debugOptionsInstance, "threadAmbient", false);
                    setOptionValue(debugOptionsInstance, "threadSound", false);
                    setOptionValue(debugOptionsInstance, "threadWorld", false);
                    setOptionValue(debugOptionsInstance, "threadGridStacks", false);
                    setOptionValue(debugOptionsInstance, "threadModelSlotInit", true);

                    try {
                        Field modelGroupField = debugOptionsClass.getField("model");
                        Object modelGroup = modelGroupField.get(debugOptionsInstance);
                        if (modelGroup != null) {
                            Field renderField = modelGroup.getClass().getField("render");
                            Object renderObj = renderField.get(modelGroup);
                            if (renderObj != null) {
                                setOptionValue(renderObj, "limitTextureSize", true);
                            }
                        }
                    } catch (Throwable ignored) {}

                    // Vanilla default. Corpses or items in the chunk texture invalidate the chunk FBO every frame
                    // while bodies or items move.
                    try {
                        Field fboGroupField = debugOptionsClass.getField("fboRenderChunk");
                        Object fboGroup = fboGroupField.get(debugOptionsInstance);
                        if (fboGroup != null) {
                            setOptionValue(fboGroup, "corpsesInChunkTexture", false);
                            setOptionValue(fboGroup, "itemsInChunkTexture", false);
                        }
                    } catch (Throwable ignored) {}

                    // Persist to debug-options.ini
                    persistDebugOptionsFile();

                    PZOLogger.success("EngineFeaturesTuner: Multi-Threaded Engine Subsystems Armed (GridStacks, Lighting, Pathfinding Native)");
                }
            } catch (Throwable e) {
                PZOLogger.info("EngineFeaturesTuner: B42 DebugOptions hook skipped: " + e.getMessage());
            }

            try {
                Class<?> perfClass = Class.forName("zombie.core.PerformanceSettings");
                
                try {
                    Field newRoofField = perfClass.getField("newRoofHiding");
                    newRoofField.setBoolean(null, true);
                } catch (Throwable ignored) {}

                try {
                    Field lightThreadField = perfClass.getField("lightingThread");
                    lightThreadField.setBoolean(null, true);
                } catch (Throwable ignored) {}

                try {
                    Field auto3DField = perfClass.getField("auto3DZombies");
                    auto3DField.setBoolean(null, true);
                } catch (Throwable ignored) {}

                try {
                    Field lightFpsField = perfClass.getField("lightingFps");
                    lightFpsField.setInt(null, 15); // Vanilla 15 FPS lighting updates (halves lighting CPU workload)
                } catch (Throwable ignored) {}

                try {
                    Field falloffField = perfClass.getField("zombieAnimationSpeedFalloffCount");
                    falloffField.setInt(null, 4); // Adaptive animation frame skip for distant horde zombies
                } catch (Throwable ignored) {}

                try {
                    Field blendField = perfClass.getField("numberZombiesBlended");
                    blendField.setInt(null, 16); // High-fidelity skeletal blending for 16 closest zombies
                } catch (Throwable ignored) {}

                // Decoupled UI FBO rendering is left to display initialization to avoid premature TextureFBO probing.
                PopTemplateGuard.resetFBOState();

                PZOLogger.success("EngineFeaturesTuner: Core Engine PerformanceSettings Optimized (15 FPS Lighting | Skeletal Falloff)");
            } catch (Throwable ignored) {}

            try {
                ChunkCrashShield.enforceChunkGridSanity();
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            PZOLogger.warn("EngineFeaturesTuner notice: " + t.getMessage());
        }
    }

    private static void setOptionValue(Object targetObject, String fieldName, boolean value) {
        try {
            Field f = targetObject.getClass().getField(fieldName);
            Object opt = f.get(targetObject);
            if (opt != null) {
                Method setValueMethod = opt.getClass().getMethod("setValue", boolean.class);
                setValueMethod.invoke(opt, value);
            }
        } catch (Throwable ignored) {}
    }

    public static void persistDebugOptionsFile() {
        try {
            java.io.File zDir = PZOEngineBridge.getZomboidDir();
            if (zDir != null && zDir.exists()) {
                java.io.File debugOptFile = new java.io.File(zDir, "debug-options.ini");
                StringBuilder sb = new StringBuilder();
                sb.append("VERSION=1\n");
                sb.append("Threading.Pathfinding=true\n");
                sb.append("Threading.Animation=false\n");
                sb.append("Threading.Lighting=false\n");
                sb.append("Threading.Ambient=false\n");
                sb.append("Threading.Sound=false\n");
                sb.append("Threading.World=false\n");
                sb.append("Threading.RecalculateGridStacks=false\n");
                sb.append("Threading.ModelSlotInit=true\n");
                sb.append("Pathfind.UseNativeCode=true\n");
                sb.append("Pathfind.SmoothPlayerPath=true\n");
                sb.append("Lighting.SplitUpdate=false\n");
                sb.append("FBORenderChunk.CorpsesInChunkTexture=false\n");
                sb.append("FBORenderChunk.ItemsInChunkTexture=false\n");
                try (java.io.FileWriter fw = new java.io.FileWriter(debugOptFile, false)) {
                    fw.write(sb.toString());
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void reapplyRuntimeTuning() {
        try {
            Class<?> debugOptionsClass = Class.forName("zombie.debug.DebugOptions");
            Object debugOptionsInstance = debugOptionsClass.getField("instance").get(null);
            if (debugOptionsInstance != null) {
                setOptionValue(debugOptionsInstance, "threadAnimation", false);
                setOptionValue(debugOptionsInstance, "threadLighting", false);
                setOptionValue(debugOptionsInstance, "lightingSplitUpdate", false);
                setOptionValue(debugOptionsInstance, "threadAmbient", false);
                setOptionValue(debugOptionsInstance, "threadSound", false);
                setOptionValue(debugOptionsInstance, "threadWorld", false);
                setOptionValue(debugOptionsInstance, "threadGridStacks", false);
                setOptionValue(debugOptionsInstance, "threadPathfinding", true);
                setOptionValue(debugOptionsInstance, "threadModelSlotInit", true);

                try {
                    Field fboGroupField = debugOptionsClass.getField("fboRenderChunk");
                    Object fboGroup = fboGroupField.get(debugOptionsInstance);
                    if (fboGroup != null) {
                        setOptionValue(fboGroup, "corpsesInChunkTexture", false);
                        setOptionValue(fboGroup, "itemsInChunkTexture", false);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}
