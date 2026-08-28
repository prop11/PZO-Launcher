package com.pzoptimizer;

public class StreamBufferBooster {
    public static final int OPTIMIZED_BUFFER_SIZE = 65536;

    public static void applyStreamTweaks() {
        try {
            System.setProperty("sun.nio.ch.bugLevel", "1.4");
            System.setProperty("jdk.io.File.enableDirectIO", "true");
        } catch (Exception ignored) {}
    }
}
