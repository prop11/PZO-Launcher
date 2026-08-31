package com.pzoptimizer.server;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Linux Dedicated Server Steam Native Library Sanitizer.
 * Automatically resolves SteamAPI_Init() failures on Indifferent Broccoli, Pterodactyl,
 * Docker, and Linux game server panels by pre-loading steamclient.so and ensuring
 * ~/.steam/sdk64/steamclient.so is populated.
 */
public class LinuxSteamServerSanitizer {

    public static void sanitize() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux") && !os.contains("unix")) {
            return;
        }

        try {
            PZOServerLogger.info("[Linux Sanitizer] Checking Linux Steam native environment...");

            File workingDir = new File(".").getAbsoluteFile();
            File[] candidateLocations = new File[] {
                new File(workingDir, "linux64/steamclient.so"),
                new File(workingDir, "natives/steamclient.so"),
                new File(workingDir, "natives/linux64/steamclient.so"),
                new File(workingDir, "steamclient.so"),
                new File("/home/server-files/linux64/steamclient.so"),
                new File("/home/steam/linux64/steamclient.so")
            };

            File foundSteamclient = null;
            for (File cand : candidateLocations) {
                if (cand.exists() && cand.isFile()) {
                    foundSteamclient = cand;
                    break;
                }
            }

            if (foundSteamclient != null) {
                PZOServerLogger.info("[Linux Sanitizer] Located steamclient.so at: " + foundSteamclient.getAbsolutePath());

                // 1. Pre-load steamclient.so into process memory so dlopen succeeds immediately
                try {
                    System.load(foundSteamclient.getAbsolutePath());
                    PZOServerLogger.success("[Linux Sanitizer] Pre-loaded steamclient.so into JVM process memory");
                } catch (Throwable t) {
                    PZOServerLogger.info("[Linux Sanitizer] System.load notice: " + t.getMessage());
                }

                // 2. Ensure ~/.steam/sdk64/steamclient.so exists (where SteamAPI_Init looks)
                try {
                    String userHome = System.getProperty("user.home", "");
                    if (userHome != null && !userHome.isEmpty()) {
                        Path sdk64Dir = Paths.get(userHome, ".steam", "sdk64");
                        Files.createDirectories(sdk64Dir);
                        Path targetSteamclient = sdk64Dir.resolve("steamclient.so");

                        if (!Files.exists(targetSteamclient)) {
                            try {
                                Files.createSymbolicLink(targetSteamclient, foundSteamclient.toPath());
                                PZOServerLogger.success("[Linux Sanitizer] Created symlink: " + targetSteamclient + " -> " + foundSteamclient.getAbsolutePath());
                            } catch (Throwable symlinkErr) {
                                Files.copy(foundSteamclient.toPath(), targetSteamclient, StandardCopyOption.REPLACE_EXISTING);
                                PZOServerLogger.success("[Linux Sanitizer] Copied steamclient.so to " + targetSteamclient);
                            }
                        }
                    }
                } catch (Throwable t) {
                    PZOServerLogger.info("[Linux Sanitizer] Home directory steam link notice: " + t.getMessage());
                }
            } else {
                PZOServerLogger.warn("[Linux Sanitizer] steamclient.so not found in standard paths. SteamAPI fallback will be used.");
            }
        } catch (Throwable t) {
            PZOServerLogger.info("[Linux Sanitizer] Sanitizer skipped: " + t.getMessage());
        }
    }
}
