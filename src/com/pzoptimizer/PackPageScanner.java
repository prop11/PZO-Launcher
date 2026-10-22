package com.pzoptimizer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replaces the TexturePackPage.readIntByte call in TexturePackDevice.readPage. Version 0 .pack files have no page
 * length, so vanilla finds the end of each page's PNG data by reading one byte at a time until the little-endian
 * 0xDEADBEEF terminator. This finds the same terminator by searching blocks of the buffered stream, then leaves the
 * stream positioned just after it, which is exactly where the vanilla loop would stop. Nothing is cached on disk.
 */
public final class PackPageScanner {

    private static final int TERMINATOR = 0xDEADBEEF;
    private static final int BLOCK = 64 * 1024;

    private static volatile boolean active = true;
    public static final AtomicLong pagesScanned = new AtomicLong();
    public static final AtomicLong bytesScanned = new AtomicLong();

    private static final ThreadLocal<byte[]> blocks = ThreadLocal.withInitial(() -> new byte[BLOCK]);

    private PackPageScanner() {}

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isActive() {
        return active;
    }

    public static int readIntByte(InputStream in) throws IOException {
        if (!active || !in.markSupported()) {
            return vanillaReadIntByte(in);
        }
        byte[] block = blocks.get();
        long consumed = 0;
        while (true) {
            in.mark(BLOCK);
            int n = readFully(in, block);
            if (n < 4) {
                throw new EOFException();
            }
            int hit = indexOfTerminator(block, n);
            in.reset();
            if (hit >= 0) {
                skipFully(in, hit + 4);
                pagesScanned.incrementAndGet();
                bytesScanned.addAndGet(consumed + hit + 4);
                return TERMINATOR;
            }
            // Keep the last 3 bytes so a terminator split across two blocks is still found
            skipFully(in, n - 3);
            consumed += n - 3;
        }
    }

    private static int indexOfTerminator(byte[] b, int n) {
        for (int i = 0; i + 3 < n; i++) {
            if (b[i] == (byte) 0xEF && b[i + 1] == (byte) 0xBE && b[i + 2] == (byte) 0xAD && b[i + 3] == (byte) 0xDE) {
                return i;
            }
        }
        return -1;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            left -= s;
        }
    }

    private static int vanillaReadIntByte(InputStream in) throws IOException {
        try {
            Class<?> page = Class.forName("zombie.core.textures.TexturePackPage");
            return (Integer) page.getMethod("readIntByte", InputStream.class).invoke(null, in);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException(e);
        }
    }
}
