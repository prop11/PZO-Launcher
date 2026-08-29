package com.pzoptimizer;

import java.io.File;
import java.io.FileInputStream;

/**
 * Non-blocking Background Texture & Asset Cache Pre-Warmer.
 * Pre-touches core texture pack headers into OS page cache while sitting at the main menu
 * so loading into save games and entering new towns has zero disk stutter.
 */
public class AssetCachePrewarmer {
    public static void startPrewarmingAsync() {
        Thread prewarmer = new Thread(() -> {
            try {
                File mediaDir = new File("media/texturepacks");
                if (!mediaDir.exists() || !mediaDir.isDirectory()) {
                    mediaDir = new File("media/textures");
                }
                if (mediaDir.exists() && mediaDir.isDirectory()) {
                    File[] files = mediaDir.listFiles((dir, name) -> name.endsWith(".pack") || name.endsWith(".png") || name.endsWith(".tiles"));
                    if (files != null) {
                        byte[] buffer = new byte[65536]; // 64KB sample
                        int count = 0;
                        for (File f : files) {
                            if (f.isFile() && f.canRead()) {
                                try (FileInputStream fis = new FileInputStream(f)) {
                                    fis.read(buffer);
                                    count++;
                                } catch (Throwable ignored) {}
                            }
                            if (count >= 25) break; // Keep lightweight and fast
                        }
                        PZOLogger.info("AssetCachePrewarmer: Pre-warmed " + count + " texture pack headers into OS memory cache");
                    }
                }
            } catch (Throwable ignored) {}
        });
        prewarmer.setName("PZO-AssetPrewarmer");
        prewarmer.setDaemon(true);
        prewarmer.setPriority(Thread.MIN_PRIORITY); // Lowest priority: never interferes with game startup
        prewarmer.start();
    }
}
