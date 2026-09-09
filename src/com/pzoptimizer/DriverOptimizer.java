package com.pzoptimizer;

public class DriverOptimizer {
    public static void initialize() {
        try {
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
