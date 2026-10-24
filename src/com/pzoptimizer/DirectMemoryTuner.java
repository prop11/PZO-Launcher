package com.pzoptimizer;

/** Direct NIO buffer cache sizing. */
public class DirectMemoryTuner {
    public static void initialize() {
        try {
            boolean mobile = EngineGLStateGovernor.isMobileGLEnvironment();
            String maxCached = mobile ? "131072" : "524288"; // 128KB on mobile (LMK safe), 512KB on desktop
            System.setProperty("sun.nio.PageAlignDirectMemory", "true");
            System.setProperty("jdk.nio.maxCachedBufferSize", maxCached);
            System.setProperty("zomboid.io.buffersize", "131072");        // 128KB chunk stream buffer
            
            PZOLogger.success("DirectMemoryTuner active (" + (mobile ? "128KB mobile" : "512KB") + " page-aligned NIO memory caching enforced)");
        } catch (Throwable t) {
            PZOLogger.warn("DirectMemoryTuner non-fatal fallback: " + t.getMessage());
        }
    }
}
