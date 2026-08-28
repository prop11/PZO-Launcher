package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PZOEntrypoint {

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println(" [PZO] Engine Optimization Wrapper Active");
        System.out.println(" [PZO] FastMath, Audio Throttler & Async Preloader Ready");
        System.out.println("=================================================");

        System.setProperty("pzo.optimized", "true");

        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3000);
                    applyEngineOptimizations();
                    TelemetryReporter.updateTelemetry();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        });
        watchdog.setDaemon(true);
        watchdog.setName("PZO-Engine-Watchdog");
        watchdog.start();

        try {
            Class<?> mainClass = Class.forName("zombie.gameStates.MainScreenState");
            Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void applyEngineOptimizations() {
        try {
            Class<?> perfClass = Class.forName("zombie.core.PerformanceSettings");
            setField(perfClass, "manualFrameSkips", 1200);
            setField(perfClass, "fboRenderChunk", true);
            setField(perfClass, "lightingThread", true);
            setField(perfClass, "zombieAnimationSpeedFalloffCount", 4);
            setField(perfClass, "numberZombiesBlended", 16);

            Class<?> debugClass = Class.forName("zombie.debug.DebugOptions");
            Field instField = debugClass.getDeclaredField("instance");
            instField.setAccessible(true);
            Object debugInst = instField.get(null);

            if (debugInst != null) {
                setDebugOption(debugInst, "threadModelSlotInit", true);
                setDebugOption(debugInst, "cheapOcclusionCount", true);
                setDebugOption(debugInst, "useNewVisibility", true);
            }
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
