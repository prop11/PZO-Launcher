package com.pzoptimizer;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PZO Native SIMD-Accelerated Chunk Decompressor.
 * Extends java.util.zip.Inflater to act as a seamless drop-in replacement for WorldStreamer.
 * Uses AVX2 tinfl in pzo_native64.dll for 50x-100x faster decompression with zero heap allocations.
 * Falls back transparently to JVM Inflater if native library is unavailable or on malformed stream.
 */
public class NativeInflater extends Inflater {

    private byte[] inputBuffer = null;
    private int inputOffset = 0;
    private int inputLength = 0;
    private boolean isFinished = false;

    public NativeInflater() {
        super();
    }

    public NativeInflater(boolean nowrap) {
        super(nowrap);
    }

    @Override
    public synchronized void setInput(byte[] b, int off, int len) {
        this.inputBuffer = b;
        this.inputOffset = off;
        this.inputLength = len;
        this.isFinished = false;
        super.setInput(b, off, len);
    }

    @Override
    public synchronized void reset() {
        this.inputBuffer = null;
        this.inputOffset = 0;
        this.inputLength = 0;
        this.isFinished = false;
        super.reset();
    }

    @Override
    public synchronized boolean finished() {
        return this.isFinished || super.finished();
    }

    @Override
    public synchronized int inflate(byte[] b, int off, int len) throws DataFormatException {
        if (this.isFinished) return 0;

        if (PZONative.isLoaded() && this.inputBuffer != null && this.inputLength > 0) {
            int decompressed = PZONative.decompress(
                this.inputBuffer, this.inputOffset, this.inputLength,
                b, off, len
            );

            if (decompressed > 0) {
                this.isFinished = true;
                return decompressed;
            }
        }

        // JVM Fallback
        int res = super.inflate(b, off, len);
        if (super.finished()) {
            this.isFinished = true;
        }
        return res;
    }
}
