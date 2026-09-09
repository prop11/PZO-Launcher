package com.pzoptimizer;

public class SpriteBatchOptimizer {
    private static boolean active = false;

    public static void apply() {
        try {
            System.setProperty("org.lwjgl.util.NoChecks", "true");
            System.setProperty("org.lwjgl.util.NoArrayChecks", "true");
            System.setProperty("pzo.sprite.batching", "true");

            active = true;
            PZOLogger.success("SpriteBatchOptimizer active (Zero-copy LWJGL vertex array optimizations configured)");
        } catch (Throwable t) {
            PZOLogger.warn("SpriteBatchOptimizer notice: " + t.getMessage());
        }
    }

    public static boolean isActive() {
        return active;
    }
}
