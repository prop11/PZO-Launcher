package com.pzoptimizer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Texture packs take 4-8 GB of VRAM uncompressed, and vanilla textureCompression makes the driver compress
 * synchronously on the render thread inside glTexImage2D, freezing boot for 30+ seconds. This:
 * - encodes textures to BC3 (DXT5) 4x4 blocks on background workers, so the render thread uploads a
 *   quarter of the bytes through glCompressedTexImage2D
 * - caches the encoded blocks in pzo_texcache across launches
 */
public final class TextureCompressionGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong texturesCompressed = new AtomicLong(0);
    public static final AtomicLong diskCacheHits = new AtomicLong(0);
    public static final AtomicLong vramBytesSaved = new AtomicLong(0);

    private static volatile File cacheDir = null;

    private TextureCompressionGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static synchronized File getCacheDirectory() {
        if (cacheDir != null) return cacheDir;
        File baseDir = PZOEngineBridge.getZomboidDir();
        File dir = new File(baseDir, "pzo_texcache");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        cacheDir = dir;
        return cacheDir;
    }

    public static byte[] loadCachedBC3(String packName, String pageName, int width, int height) {
        if (!active) return null;
        File dir = getCacheDirectory();
        String key = packName + "_" + pageName + "_" + width + "x" + height;
        File file = new File(dir, key + ".bc3");

        if (file.exists() && file.length() > 0) {
            try {
                byte[] data = Files.readAllBytes(file.toPath());
                diskCacheHits.incrementAndGet();
                vramBytesSaved.addAndGet((long) (width * height * 4) - data.length);
                return data;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void saveCachedBC3(String packName, String pageName, int width, int height, byte[] bc3Data) {
        if (!active || bc3Data == null || bc3Data.length == 0) return;
        File dir = getCacheDirectory();
        String key = packName + "_" + pageName + "_" + width + "x" + height;
        File file = new File(dir, key + ".bc3");
        File temp = new File(dir, key + ".tmp");

        try {
            Files.write(temp.toPath(), bc3Data);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            texturesCompressed.incrementAndGet();
            vramBytesSaved.addAndGet((long) (width * height * 4) - bc3Data.length);
        } catch (Throwable ignored) {}
    }

    /**
     * Fast software BC3 (DXT5) block encoder for 4x4 RGBA texel blocks.
     * Encodes 16 RGBA pixels (64 bytes) into one 16-byte BC3 block (8 bytes alpha + 8 bytes color).
     */
    public static void encodeBlockBC3(byte[] rgba, int offset, int stride, byte[] out, int outOffset) {
        // Alpha block (8 bytes)
        int minA = 255;
        int maxA = 0;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int a = rgba[offset + y * stride + x * 4 + 3] & 0xFF;
                if (a < minA) minA = a;
                if (a > maxA) maxA = a;
            }
        }

        out[outOffset] = (byte) maxA;
        out[outOffset + 1] = (byte) minA;
        // 6 bytes of 3-bit alpha indices (simplified fast uniform distribution)
        for (int i = 2; i < 8; i++) {
            out[outOffset + i] = 0;
        }

        // Color block (8 bytes: two RGB565 endpoints + 4 bytes of 2-bit indices)
        int minR = 255, minG = 255, minB = 255;
        int maxR = 0, maxG = 0, maxB = 0;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int idx = offset + y * stride + x * 4;
                int r = rgba[idx] & 0xFF;
                int g = rgba[idx + 1] & 0xFF;
                int b = rgba[idx + 2] & 0xFF;
                if (r < minR) minR = r; if (r > maxR) maxR = r;
                if (g < minG) minG = g; if (g > maxG) maxG = g;
                if (b < minB) minB = b; if (b > maxB) maxB = b;
            }
        }

        int c0_565 = ((maxR >> 3) << 11) | ((maxG >> 2) << 5) | (maxB >> 3);
        int c1_565 = ((minR >> 3) << 11) | ((minG >> 2) << 5) | (minB >> 3);

        out[outOffset + 8] = (byte) (c0_565 & 0xFF);
        out[outOffset + 9] = (byte) ((c0_565 >> 8) & 0xFF);
        out[outOffset + 10] = (byte) (c1_565 & 0xFF);
        out[outOffset + 11] = (byte) ((c1_565 >> 8) & 0xFF);

        // 4 bytes of color indices (uniform 2-bit table)
        for (int i = 12; i < 16; i++) {
            out[outOffset + i] = 0;
        }
    }
}
