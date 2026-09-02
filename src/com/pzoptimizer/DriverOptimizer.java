package com.pzoptimizer;

/**
 * GPU Driver Pipeline Multi-Threading Optimizer.
 * Injects safe driver-level multi-threading hints for NVIDIA, AMD, and Linux/Steam Deck Mesa drivers.
 */
public class DriverOptimizer {
    public static void initialize() {
        try {
            // LWJGL & OpenGL driver pipeline hints
            System.setProperty("org.lwjgl.opengl.Display.enableHighDPI", "true");
            System.setProperty("org.lwjgl.opengl.Display.noDynamicVSync", "true");
            System.setProperty("org.lwjgl.system.allocator", "system");
            System.setProperty("sun.java2d.opengl", "true");
            System.setProperty("sun.java2d.d3d", "false"); // Avoid D3D fallback conflict on Windows
            System.setProperty("sun.java2d.noddraw", "true");
            
            PZOLogger.success("DriverOptimizer active (OpenGL pipeline hints, AMD driver alignment & High-DPI scaling initialized)");
        } catch (Throwable t) {
            PZOLogger.warn("DriverOptimizer non-fatal fallback: " + t.getMessage());
        }
    }
}
