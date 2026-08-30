package com.pzoptimizer;

import java.io.File;
import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-blocking Background Texture & Asset Cache Pre-Warmer.
 * Uses NIO Memory-Mapped FileChannels (mmap) to pre-warm texture packs and chunk tables
 * into the OS unified virtual memory page cache for stutter-free tile and town streaming.
 */
public class AssetCachePrewarmer {
    private static final List<MappedByteBuffer> pinnedBuffers = new ArrayList<>();

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
                        int count = 0;
                        for (File f : files) {
                            if (f.isFile() && f.canRead()) {
                                long len = f.length();
                                if (len > 0) {
                                    try (FileInputStream fis = new FileInputStream(f);
                                         FileChannel channel = fis.getChannel()) {
                                        long mapSize = Math.min(len, 262144L); // 256KB header slice
                                        MappedByteBuffer mbb = channel.map(FileChannel.MapMode.READ_ONLY, 0, mapSize);
                                        mbb.load(); // Request OS kernel to fault pages into RAM
                                        synchronized (pinnedBuffers) {
                                            if (pinnedBuffers.size() < 32) {
                                                pinnedBuffers.add(mbb);
                                            }
                                        }
                                        count++;
                                    } catch (Throwable ignored) {}
                                }
                            }
                            if (count >= 32) break;
                        }
                        PZOLogger.info("AssetCachePrewarmer: Memory-mapped " + count + " core texture pack headers into OS page cache");
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
