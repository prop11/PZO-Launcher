package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Project Zomboid Build 42 - Dedicated High-Performance Engine Optimizer & Wrapper.
 * Targets Build 42 Java 17 64-bit runtime with multi-threaded dynamic lighting,
 * 32-story building depth occlusion, GPU terrain instancing, and direct memory streaming.
 */
public class PZOEntrypoint {

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println(" [PZO] Project Zomboid Build 42 Engine Optimizer");
        System.out.println(" [PZO] Version: " + UpdateChecker.CURRENT_VERSION);
        System.out.println(" [PZO] Java 17+ Multi-Threaded Engine Mode Active");
        System.out.println("=================================================");

        System.setProperty("pzo.optimized", "true");
        System.setProperty("pzo.target", "Build42");

        StreamBufferBooster.applyStreamTweaks();
        HighPrecisionTimer.initialize();
        UpdateChecker.checkForUpdatesAsync();

        // Elevate main render thread priority
        try {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        } catch (Throwable ignored) {}

        // Build 42 Engine Watchdog & JMX Telemetry loop
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3000);
                    applyBuild42EngineOptimizations();
                    TelemetryReporter.updateTelemetry();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        });
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.NORM_PRIORITY - 1);
        watchdog.setName("PZO-B42-Watchdog");
        watchdog.start();

        // Launch Project Zomboid Build 42 Main Entrypoint
        try {
            Class<?> mainClass = Class.forName("zombie.gameStates.MainScreenState");
            Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void applyBuild42EngineOptimizations() {
        try {
            // 1. Build 42 PerformanceSettings (Multi-Threaded Rendering & Lighting)
            Class<?> perfClass = Class.forName("zombie.core.PerformanceSettings");
            setField(perfClass, "manualFrameSkips", 1200);
            setField(perfClass, "fboRenderChunk", true);
            setField(perfClass, "lightingThread", true);
            setField(perfClass, "zombieAnimationSpeedFalloffCount", 4);
            setField(perfClass, "numberZombiesBlended", 16);

            // 2. Build 42 DebugOptions (Sub-Pixel Culling & Instancing)
            Class<?> debugClass = Class.forName("zombie.debug.DebugOptions");
            Field instField = debugClass.getDeclaredField("instance");
            instField.setAccessible(true);
            Object debugInst = instField.get(null);

            if (debugInst != null) {
                setDebugOption(debugInst, "threadModelSlotInit", true);
                setDebugOption(debugInst, "cheapOcclusionCount", true);
                setDebugOption(debugInst, "useNewVisibility", true);
                setDebugOption(debugInst, "terrainInstancing", true);
            }

            // 3. Hardware Texture Compression in VRAM (OpenGL LWJGL 3.x)
            try {
                Class<?> texClass = Class.forName("zombie.core.textures.Texture");
                setField(texClass, "bUseCompression", true);
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    private static void setField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Exception ignored) {}
    }

    private static void setDebugOption(Object debugInstance, String optionName, boolean value) {
        try {
            Field f = debugInstance.getClass().getDeclaredField(optionName);
            f.setAccessible(true);
            Object boolOption = f.get(debugInstance);
            if (boolOption != null) {
                Method setValueMethod = boolOption.getClass().getMethod("setValue", boolean.class);
                setValueMethod.setAccessible(true);
                setValueMethod.invoke(boolOption, value);
            }
        } catch (Exception ignored) {}
    }
}
