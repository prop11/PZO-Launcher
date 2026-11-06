package com.pzoptimizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The Java version the game's launcher starts, for launch options that only some runtimes know. Build 42 ships Java 25
 * (25.0.1 on 42.20 and 42.21, 25.0.5 on 42.22), but an option a runtime doesn't know, or one it knows with a bad value,
 * can stop the game starting, so nothing is assumed.
 *
 * Read from jre64/release in the game folder, which is the runtime ProjectZomboid64.json is used with. Without that file
 * (installs that run their own Java) it falls back to the JVM this code runs in, which is the game's own when PZO runs.
 */
final class JvmRuntime {

    /** Smaller object headers (8 bytes instead of 12-16). A supported option from Java 25; experimental before. */
    static final String COMPACT_HEADERS = "-XX:+UseCompactObjectHeaders";

    private JvmRuntime() {}

    static int featureVersion(File gameDir) {
        if (gameDir != null) {
            for (String dir : new String[] {"jre64", "jre"}) {
                int v = fromReleaseFile(new File(new File(gameDir, dir), "release"));
                if (v > 0) return v;
            }
        }
        return Runtime.version().feature();
    }

    // JAVA_VERSION="25.0.5" -> 25, "1.8.0_402" -> 8, -1 if unreadable
    static int fromReleaseFile(File release) {
        if (!release.isFile()) return -1;
        try {
            for (String line : Files.readAllLines(release.toPath(), StandardCharsets.UTF_8)) {
                if (!line.startsWith("JAVA_VERSION=")) continue;
                String v = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                String[] parts = v.split("[._+-]");
                int major = Integer.parseInt(parts[0]);
                if (major == 1 && parts.length > 1) major = Integer.parseInt(parts[1]);
                return major;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    static boolean wantCompactHeaders(File gameDir) {
        return PZOConfig.isCompactHeadersEnabled() && featureVersion(gameDir) >= 25;
    }

    // Collector choice. Stock B42 runs ZGC; PZO used to force untargeted G1 on most PCs, and bench recordings showed
    // 60-120 ms young pauses with it against 0.2 ms worst on stock ZGC. Generational ZGC (the only kind from Java 23)
    // pauses well under a millisecond but has no compressed pointers and runs background GC threads, so low-end PCs keep
    // G1, now with a pause target.
    static final String G1_PAUSE_TARGET = "-XX:MaxGCPauseMillis=20";
    // Startup otherwise runs a few GCs just because class metadata passed the default 20 MB threshold
    static final String METASPACE_SIZE = "-XX:MetaspaceSize=256m";
    static final int ZGC_MIN_THREADS = 5;
    // A "16 GB" PC reports a little under 16
    static final double ZGC_MIN_RAM_GB = 15.0;

    static boolean wantZgc(File gameDir) {
        String mode = PZOConfig.getGcMode();
        if ("g1".equals(mode) || featureVersion(gameDir) < 23) return false;
        if ("zgc".equals(mode)) return true;
        if (CompatMode.isActive()) return false;
        return Runtime.getRuntime().availableProcessors() >= ZGC_MIN_THREADS && CompatMode.totalRamGb() >= ZGC_MIN_RAM_GB;
    }
}
