package com.pzoptimizer.pzoe.water;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

final class WaterSpectrum {
    private static final int GRID = 32, FRAMES = 16, BORDERED = GRID + 2;
    private static final int SIZE = BORDERED * 4;
    private static final int UNIT = 5;
    private static CompletableFuture<byte[]> bake;
    private static int texture;
    private static boolean failed;

    private WaterSpectrum() {}

    static void bind(int sampler, int ready, float wind) {
        GL20.glUniform1i(sampler, UNIT);
        GL20.glUniform1f(ready, 0f);
        if (failed || wind <= 0.48f) return;
        if (texture == 0) {
            if (bake == null) {
                bake = CompletableFuture.supplyAsync(WaterSpectrum::atlas);
                return;
            }
            if (!bake.isDone()) return;
            if (bake.isCompletedExceptionally()) {
                failed = true;
                bake = null;
                return;
            }
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        try {
            if (texture == 0) {
                byte[] payload = bake.join();
                bake = null;
                ByteBuffer pixels = BufferUtils.createByteBuffer(payload.length);
                pixels.put(payload).flip();
                texture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, SIZE, SIZE, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, pixels);
            } else {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            }
            GL20.glUniform1f(ready, 1f);
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
        }
    }

    static byte[] atlas() {
        byte[] out = new byte[SIZE * SIZE * 2];
        double[] baseReal = new double[GRID * GRID], baseImag = new double[GRID * GRID];
        double[] real = new double[GRID * GRID], imag = new double[GRID * GRID];
        int[] harmonic = new int[GRID * GRID];
        Random random = new Random(0x505A4F45);
        for (int y = 0; y < GRID; y++) {
            int ky = y < GRID / 2 ? y : y - GRID;
            for (int x = 0; x < GRID; x++) {
                int i = y * GRID + x;
                int kx = x < GRID / 2 ? x : x - GRID;
                double k2 = kx * kx + ky * ky;
                if (k2 == 0) continue;
                double spectrum = (0.22 + 0.78 * kx * kx / k2) * Math.exp(-3.8 / k2 - 0.018 * k2) / (k2 * k2);
                double amplitude = Math.sqrt(spectrum * 0.5);
                baseReal[i] = random.nextGaussian() * amplitude;
                baseImag[i] = random.nextGaussian() * amplitude;
                // Quantized deep-water dispersion closes the animation loop exactly.
                harmonic[i] = Math.max(1, (int) Math.round(Math.sqrt(Math.sqrt(k2)) * 1.5));
            }
        }
        for (int frame = 0; frame < FRAMES; frame++) {
            for (int y = 0; y < GRID; y++) {
                for (int x = 0; x < GRID; x++) {
                    int i = y * GRID + x;
                    int paired = ((-y) & (GRID - 1)) * GRID + ((-x) & (GRID - 1));
                    double phase = harmonic[i] * frame * (2.0 * Math.PI / FRAMES);
                    double cos = Math.cos(phase), sin = Math.sin(phase);
                    real[i] = (baseReal[i] + baseReal[paired]) * cos - (baseImag[i] + baseImag[paired]) * sin;
                    imag[i] = (baseReal[i] - baseReal[paired]) * sin + (baseImag[i] - baseImag[paired]) * cos;
                }
            }
            for (int i = 0; i < GRID; i++) inverse(real, imag, i * GRID, 1);
            for (int i = 0; i < GRID; i++) inverse(real, imag, i, GRID);
            writeFrame(out, real, frame);
        }
        return out;
    }

    private static void writeFrame(byte[] out, double[] wave, int frame) {
        int ox = frame % 4 * BORDERED, oy = frame / 4 * BORDERED;
        for (int y = 0; y < BORDERED; y++) {
            for (int x = 0; x < BORDERED; x++) {
                int ix = (x - 1) & (GRID - 1), iy = (y - 1) & (GRID - 1);
                int i = ((oy + y) * SIZE + ox + x) * 2;
                out[i] = encode((sample(wave, ix - 1, iy) - sample(wave, ix + 1, iy)) * 2.2);
                out[i + 1] = encode((sample(wave, ix, iy - 1) - sample(wave, ix, iy + 1)) * 2.2);
            }
        }
    }

    private static double sample(double[] wave, int x, int y) {
        return wave[(y & (GRID - 1)) * GRID + (x & (GRID - 1))];
    }

    private static byte encode(double slope) {
        return (byte) Math.round(128.0 + 127.0 * Math.max(-1.0, Math.min(1.0, slope)));
    }

    private static void inverse(double[] real, double[] imag, int from, int stride) {
        for (int i = 0; i < GRID; i++) {
            int j = Integer.reverse(i) >>> 27;
            if (j <= i) continue;
            int a = from + i * stride, b = from + j * stride;
            double re = real[a], im = imag[a];
            real[a] = real[b];
            imag[a] = imag[b];
            real[b] = re;
            imag[b] = im;
        }
        for (int block = 2; block <= GRID; block *= 2) {
            int half = block / 2;
            for (int k = 0; k < half; k++) {
                double angle = 2.0 * Math.PI * k / block;
                double cos = Math.cos(angle), sin = Math.sin(angle);
                for (int i = k; i < GRID; i += block) {
                    int a = from + i * stride, b = a + half * stride;
                    double re = real[b] * cos - imag[b] * sin;
                    double im = imag[b] * cos + real[b] * sin;
                    real[b] = real[a] - re;
                    imag[b] = imag[a] - im;
                    real[a] += re;
                    imag[a] += im;
                }
            }
        }
    }
}
