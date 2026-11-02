package com.pzoptimizer;

/** OpenGL driver hints for NVIDIA, AMD and Mesa (Linux / Steam Deck). */
public class DriverOptimizer {
    public static void initialize() {
        try {
            System.setProperty("org.lwjgl.opengl.Display.enableHighDPI", "true");
            System.setProperty("org.lwjgl.opengl.Display.noDynamicVSync", "true");
            System.setProperty("org.lwjgl.system.allocator", "system");
            System.setProperty("sun.java2d.opengl", "true");
            System.setProperty("sun.java2d.d3d", "false"); // stop Java2D falling back to D3D on Windows
            System.setProperty("sun.java2d.noddraw", "true");
            
            PZOLogger.success("DriverOptimizer active (OpenGL pipeline hints, AMD driver alignment & High-DPI scaling initialized)");
        } catch (Throwable t) {
            PZOLogger.warn("DriverOptimizer non-fatal fallback: " + t.getMessage());
        }
    }
}
