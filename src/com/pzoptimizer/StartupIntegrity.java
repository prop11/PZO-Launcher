package com.pzoptimizer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StartupIntegrity {

    private static volatile String failure = null;
    private static volatile boolean checked = false;
    private static volatile boolean reported = false;

    private StartupIntegrity() {}

    public static synchronized boolean verify() {
        if (checked) return failure == null;
        checked = true;
        failure = run();
        return failure == null;
    }

    private static String run() {
        try {
            Class<?> guard = Class.forName("com.pzoptimizer.IncompatibleModGuard");
            guard.getDeclaredMethod("isPzOptimizationInstalled");
            guard.getDeclaredMethod("detectsIn", File.class);
        } catch (Throwable t) {
            return "S1";
        }
        try {
            if (!IncompatibleModGuard.hasScanned()) return "S2";
        } catch (Throwable t) {
            return "S1";
        }
        String selfTest = selfTest();
        if (selfTest != null) return selfTest;
        try {
            boolean mine = false;
            for (File dir : IncompatibleModGuard.candidateGameDirs()) {
                if (IncompatibleModGuard.detectsIn(dir)) {
                    mine = true;
                    break;
                }
            }
            if (!mine && ClassLoader.getSystemResource("pzopt/BootRepair.class") != null) mine = true;
            if (mine && !IncompatibleModGuard.isPzOptimizationInstalled()) return "S4";
        } catch (Throwable t) {
            return "S1";
        }
        return null;
    }
    private static String selfTest() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("pzo-check");
        } catch (Throwable t) {
            return null;
        }
        try {
            File d = dir.toFile();
            if (IncompatibleModGuard.detectsIn(d)) return "S3";
            File marker = new File(d, "pzopt-installed.txt");
            if (!marker.createNewFile()) return null;
            if (!IncompatibleModGuard.detectsIn(d)) return "S3";
            return null;
        } catch (Throwable t) {
            return "S1";
        } finally {
            try {
                File[] files = dir.toFile().listFiles();
                if (files != null) for (File f : files) f.delete();
                Files.deleteIfExists(dir);
            } catch (Throwable ignored) {}
        }
    }

    public static synchronized void reportAndStandDown() {
        if (reported) return;
        reported = true;
        String code = "PZO-" + (failure != null ? failure : "S0");
        PZOLogger.warn("[PZO] Startup check failed (" + code + "). PZO is disabled for this launch.");
        try {
            TelemetryReporter.writeStatusFile(String.format(
                "{\"optimized\":false,\"disabled\":\"startup_check\",\"code\":\"%s\",\"version\":\"%s\"}",
                code, UpdateChecker.CURRENT_VERSION));
        } catch (Throwable ignored) {}
        try {
            UpdateDialog.showWarningNotice("Project Zomboid Optimiser - Startup Error", message(code));
        } catch (Throwable ignored) {}
    }

    static String message(String code) {
        return "Project Zomboid Optimiser could not start (error " + code + ").\n\n"
            + "The game will start without PZO.\n\n"
            + "Reinstall PZO from the official GitHub release:\nhttps://github.com/prop11/PZO-Launcher/releases";
    }
}
