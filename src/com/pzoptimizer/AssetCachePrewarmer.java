package com.pzoptimizer;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Non-blocking Background Texture & Asset Cache Pre-Warmer.
 * Reads texture pack headers into OS page cache at boot and immediately releases file handles.
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
                        ByteBuffer buffer = ByteBuffer.allocate(131072); // 128KB direct sample
                        int count = 0;
                        for (File f : files) {
                            if (f.isFile() && f.canRead()) {
                                try (FileInputStream fis = new FileInputStream(f);
                                     FileChannel channel = fis.getChannel()) {
                                    buffer.clear();
                                    channel.read(buffer);
                                    count++;
                                } catch (Throwable ignored) {}
                            }
                            if (count >= 24) break;
                        }
                        PZOLogger.info("AssetCachePrewarmer: Pre-warmed " + count + " texture pack headers into OS memory cache");
                    }
                }
            } catch (Throwable ignored) {}
        });
        prewarmer.setName("PZO-AssetPrewarmer");
        prewarmer.setDaemon(true);
        prewarmer.setPriority(Thread.MIN_PRIORITY);
        prewarmer.start();
    }
}
