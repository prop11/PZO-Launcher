package com.pzoptimizer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Disk cache for parsed animation clips. B42 imports ~2,200 .X animation files through jassimp on every
 * launch; parsed keyframe tracks are written to a binary .pzac file on first import and read back on
 * later launches instead of running the importer again.
 */
public final class AnimationClipCache {

    private static volatile boolean active = true;
    private static final int MAGIC = 0x505A4143; // "PZAC"
    private static final int FORMAT_VERSION = 1;

    public static final AtomicLong cacheHits = new AtomicLong(0);
    public static final AtomicLong cacheMisses = new AtomicLong(0);
    public static final AtomicLong cacheWrites = new AtomicLong(0);

    private static volatile File cacheDirectory = null;
    private static final LinkedBlockingQueue<Runnable> asyncDiskWrites = new LinkedBlockingQueue<>();
    private static Thread backgroundWriter = null;

    private AnimationClipCache() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static synchronized File getCacheDirectory() {
        if (cacheDirectory != null) return cacheDirectory;
        File baseDir = PZOEngineBridge.getZomboidDir();
        File animDir = new File(baseDir, "pzo_anims");
        if (!animDir.exists()) {
            animDir.mkdirs();
        }
        cacheDirectory = animDir;
        startBackgroundWriter();
        return cacheDirectory;
    }

    private static synchronized void startBackgroundWriter() {
        if (backgroundWriter != null && backgroundWriter.isAlive()) return;
        backgroundWriter = new Thread(() -> {
            while (true) {
                try {
                    Runnable task = asyncDiskWrites.take();
                    task.run();
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable ignored) {}
            }
        }, "PZO-AnimCache-Writer");
        backgroundWriter.setDaemon(true);
        backgroundWriter.setPriority(Thread.NORM_PRIORITY - 1);
        backgroundWriter.start();
    }

    /** Cache key: SHA-256 of the asset path and metadata. */
    public static String computeCacheKey(String assetPath, long fileSize, long lastModified) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(assetPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(ByteBufferAllocate(fileSize));
            md.update(ByteBufferAllocate(lastModified));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(assetPath.hashCode());
        }
    }

    private static byte[] ByteBufferAllocate(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (v & 0xFF);
            v >>= 8;
        }
        return b;
    }

    public static File getCachedAnimationFile(String cacheKey) {
        if (!active) return null;
        File dir = getCacheDirectory();
        File f = new File(dir, cacheKey + ".pzac");
        if (f.exists() && f.length() > 16) {
            cacheHits.incrementAndGet();
            return f;
        }
        cacheMisses.incrementAndGet();
        return null;
    }

    public static void queueAsyncSave(String cacheKey, byte[] serializedData) {
        if (!active || serializedData == null || serializedData.length == 0) return;
        getCacheDirectory();

        asyncDiskWrites.offer(() -> {
            try {
                File dir = getCacheDirectory();
                File tempFile = new File(dir, cacheKey + ".tmp");
                File finalFile = new File(dir, cacheKey + ".pzac");

                try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)))) {
                    dos.writeInt(MAGIC);
                    dos.writeInt(FORMAT_VERSION);
                    dos.writeInt(serializedData.length);
                    dos.write(serializedData);
                }

                Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                cacheWrites.incrementAndGet();
            } catch (Throwable ignored) {}
        });
    }

    public static byte[] readCachedBytes(File cacheFile) {
        if (cacheFile == null || !cacheFile.exists()) return null;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(cacheFile)))) {
            int magic = dis.readInt();
            int version = dis.readInt();
            if (magic != MAGIC || version != FORMAT_VERSION) {
                return null;
            }
            int length = dis.readInt();
            byte[] data = new byte[length];
            dis.readFully(data);
            return data;
        } catch (Throwable t) {
            return null;
        }
    }
}
