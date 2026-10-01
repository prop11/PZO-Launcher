package com.pzoptimizer;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import sun.misc.Unsafe;

/**
 * Texture Pack Index Governor & Fast Page Seek Accelerator.
 * In Project Zomboid Build 42, version-0 texture packs (.pack files totaling over 526 MB)
 * do not record pre-computed byte offsets for page boundaries. During startup, the engine
 * scans through the entire 526 MB byte-by-byte via synchronized stream reads (TexturePackPage.readIntByte)
 * searching for the 0xDEADBEEF page terminator, burning 0.5 to 0.6 seconds of boot CPU time.
 * 
 * TexturePackIndexGovernor introduces:
 * 1. Persistent Pack Index Cache (pzo_packs/): Caches exact page boundary offsets (start -> end)
 *    keyed by pack file size, modification timestamp, and PZO build key.
 * 2. Instant Sub-Millisecond Page Seeking: On repeat launches, skips the page byte scan
 *    directly to the end offset via input.skip(), reducing over 500,000 synchronized stream
 *    iterations down to a single seek call per page.
 */
public final class TexturePackIndexGovernor {

    private static volatile boolean active = true;
    private static final int MAGIC = 0x505A5449; // "PZTI" - PZO Texture Index
    private static final int TARGET_TERMINATOR = -559038737; // 0xDEADBEEF

    public static final AtomicLong packHits = new AtomicLong(0);
    public static final AtomicLong packMisses = new AtomicLong(0);
    public static final AtomicLong bytesSkipped = new AtomicLong(0);

    private static final Map<String, PackIndexData> indices = new ConcurrentHashMap<>();
    private static final Map<InputStream, String> streamPathMap = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile File cacheDir = null;
    private static volatile Unsafe unsafeInstance = null;
    private static volatile long filterInOffset = -1;
    private static volatile long fileInputPathOffset = -1;
    private static volatile Method getPositionMethod = null;
    private static volatile Method fallbackReadIntByteMethod = null;
    private static volatile boolean reflectionInitialized = false;

    private TexturePackIndexGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static synchronized File getCacheDirectory() {
        if (cacheDir != null) return cacheDir;
        File baseDir = PZOEngineBridge.getZomboidDir();
        File dir = new File(baseDir, "pzo_packs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        cacheDir = dir;
        return cacheDir;
    }

    private static synchronized void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            unsafeInstance = (Unsafe) theUnsafeField.get(null);

            Field inField = FilterInputStream.class.getDeclaredField("in");
            filterInOffset = unsafeInstance.objectFieldOffset(inField);

            Field pathField = FileInputStream.class.getDeclaredField("path");
            fileInputPathOffset = unsafeInstance.objectFieldOffset(pathField);
        } catch (Throwable t) {
            PZOLogger.warn("[TexturePackIndexGovernor] Notice initializing stream reflection: " + t.getMessage());
        }
    }

    /**
     * Unwraps nested FilterInputStream / BufferedInputStream wrappers to find underlying FileInputStream path.
     */
    public static String extractPackPath(InputStream is) {
        initReflection();
        if (unsafeInstance == null || filterInOffset == -1 || fileInputPathOffset == -1) {
            return null;
        }
        try {
            InputStream cur = is;
            while (cur instanceof FilterInputStream) {
                cur = (InputStream) unsafeInstance.getObject(cur, filterInOffset);
                if (cur == null) break;
            }
            if (cur instanceof FileInputStream) {
                return (String) unsafeInstance.getObject(cur, fileInputPathOffset);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Resolves getPosition() method on PositionInputStream.
     */
    private static long getStreamPosition(InputStream in) {
        try {
            if (getPositionMethod == null) {
                Method m = in.getClass().getMethod("getPosition");
                m.setAccessible(true);
                getPositionMethod = m;
            }
            Object res = getPositionMethod.invoke(in);
            if (res instanceof Number) {
                return ((Number) res).longValue();
            }
        } catch (Throwable ignored) {}
        return -1L;
    }

    /**
     * High-performance stream skipper with zero-return handling.
     */
    private static long skipInput(InputStream input, long length) throws IOException {
        long skipped = 0L;
        while (skipped < length) {
            long n = input.skip(length - skipped);
            if (n > 0L) {
                skipped += n;
            } else {
                if (input.read() == -1) break;
                skipped++;
            }
        }
        return skipped;
    }

    /**
     * Entrypoint called by patched TexturePackDevice.
     */
    public static int readIntByte(InputStream in) throws IOException {
        return interceptReadIntByte(in);
    }

    /**
     * Intercepts TexturePackPage.readIntByte(InputStream in).
     * On cache hit: skips directly to the page end in a single seek call.
     * On cache miss: scans forward in a tight un-synchronized loop and records the page end offset.
     */
    public static int interceptReadIntByte(InputStream in) throws IOException {
        if (!active || in == null) {
            return invokeFallbackReadIntByte(in);
        }

        try {
            String path = streamPathMap.get(in);
            if (path == null) {
                path = extractPackPath(in);
                if (path != null) {
                    streamPathMap.put(in, path);
                }
            }

            if (path == null) {
                return invokeFallbackReadIntByte(in);
            }

            PackIndexData data = getOrLoadIndex(path);
            if (data == null) {
                return invokeFallbackReadIntByte(in);
            }

            long startPos = getStreamPosition(in);
            if (startPos < 0) {
                return invokeFallbackReadIntByte(in);
            }

            // 1. Check for Cache Hit
            Long endPos = data.endByStart.get(startPos);
            if (endPos != null && endPos > startPos) {
                long toSkip = endPos - startPos;
                skipInput(in, toSkip);
                bytesSkipped.addAndGet(toSkip);
                packHits.incrementAndGet();
                return TARGET_TERMINATOR;
            }

            // 2. Cache Miss: Fast JIT-optimized scan in a tight local loop
            int ring = 0;
            while (true) {
                int b = in.read();
                if (b == -1) break;
                ring = (ring << 8) | (b & 0xFF);
                if (ring == TARGET_TERMINATOR) {
                    long recordedEnd = getStreamPosition(in);
                    if (recordedEnd > startPos) {
                        data.endByStart.put(startPos, recordedEnd);
                        data.dirty = true;
                    }
                    packMisses.incrementAndGet();
                    return TARGET_TERMINATOR;
                }
            }

            return invokeFallbackReadIntByte(in);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Throwable t) {
            return invokeFallbackReadIntByte(in);
        }
    }

    private static int invokeFallbackReadIntByte(InputStream in) throws IOException {
        try {
            if (fallbackReadIntByteMethod == null) {
                Class<?> cls = Class.forName("zombie.core.textures.TexturePackPage");
                Method m = cls.getMethod("readIntByte", InputStream.class);
                m.setAccessible(true);
                fallbackReadIntByteMethod = m;
            }
            return (Integer) fallbackReadIntByteMethod.invoke(null, in);
        } catch (Throwable t) {
            if (t.getCause() instanceof IOException) {
                throw (IOException) t.getCause();
            }
            return -1;
        }
    }

    /**
     * Loads or creates PackIndexData for a given .pack file.
     */
    private static PackIndexData getOrLoadIndex(String packPath) {
        PackIndexData existing = indices.get(packPath);
        if (existing != null) return existing;

        try {
            File packFile = new File(packPath);
            if (!packFile.exists()) return null;

            File dir = getCacheDirectory();
            String idxName = packFile.getName() + ".idx";
            File idxFile = new File(dir, idxName);

            long size = packFile.length();
            long mtime = packFile.lastModified();

            PackIndexData data = new PackIndexData(packPath, idxFile, size, mtime);

            if (idxFile.exists() && idxFile.length() > 0) {
                try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(idxFile)))) {
                    if (dis.readInt() == MAGIC && UpdateChecker.CURRENT_VERSION.equals(dis.readUTF()) &&
                        dis.readLong() == size && dis.readLong() == mtime) {
                        int count = dis.readInt();
                        for (int i = 0; i < count; i++) {
                            data.endByStart.put(dis.readLong(), dis.readLong());
                        }
                        PZOLogger.info("[TexturePackIndexGovernor] Loaded " + count + " cached page offsets for " + packFile.getName());
                    }
                } catch (Throwable t) {
                    data.endByStart.clear();
                }
            }

            indices.put(packPath, data);
            return data;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Flushes all dirty pack indices to disk atomically.
     */
    public static synchronized void saveDirtyIndices() {
        for (PackIndexData data : indices.values()) {
            if (data.dirty) {
                data.save();
            }
        }
    }

    public static final class PackIndexData {
        final String packPath;
        final File idxFile;
        final long packSize;
        final long packMtime;
        final Map<Long, Long> endByStart = new ConcurrentHashMap<>();
        volatile boolean dirty = false;

        PackIndexData(String packPath, File idxFile, long packSize, long packMtime) {
            this.packPath = packPath;
            this.idxFile = idxFile;
            this.packSize = packSize;
            this.packMtime = packMtime;
        }

        public synchronized void save() {
            if (!dirty || endByStart.isEmpty()) return;
            try {
                File dir = idxFile.getParentFile();
                if (!dir.exists()) dir.mkdirs();
                File temp = new File(dir, idxFile.getName() + ".tmp");

                try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
                    dos.writeInt(MAGIC);
                    dos.writeUTF(UpdateChecker.CURRENT_VERSION);
                    dos.writeLong(packSize);
                    dos.writeLong(packMtime);
                    dos.writeInt(endByStart.size());
                    for (Map.Entry<Long, Long> e : endByStart.entrySet()) {
                        dos.writeLong(e.getKey());
                        dos.writeLong(e.getValue());
                    }
                }

                Files.move(temp.toPath(), idxFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                dirty = false;
                PZOLogger.success("[TexturePackIndexGovernor] Saved " + endByStart.size() + " page offsets for " + new File(packPath).getName());
            } catch (Throwable t) {
                PZOLogger.warn("[TexturePackIndexGovernor] Failed saving pack index for " + packPath + ": " + t.getMessage());
            }
        }
    }
}
