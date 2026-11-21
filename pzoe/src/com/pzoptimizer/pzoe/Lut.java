package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import java.io.BufferedReader;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * The player's own colour LUT for PZO Enhancements' Colour-Blind Filter and Custom LUT (screen.frag declares PZOLut).
 *
 *   uniform sampler2D PZOLut;    the cube as a strip: N slices of blue side by side, red across each, green down
 *   uniform vec4 PZOLutInfo;     size N, 0, 0, 1 = loaded
 *
 * A standard .cube file (LUT_3D_SIZE 2 to 64, red changing fastest) at Zomboid/PZOE/lut.cube, read when the card asks
 * (modApi "lut") and again whenever the file changes. Kept 2D: a 3D sampler next to the game's 2D ones fails its
 * program validation. Unit 4 in the screen pass.
 */
final class Lut {

    // Main thread parses, render thread uploads
    private static volatile FloatBuffer pending;
    private static volatile int pendingSize;
    private static volatile boolean wanted;
    private static long lastModified = -1, lastCheckNs;
    // Render thread
    private static int tex, size;

    private Lut() {}

    static File file() {
        return new File(com.pzoptimizer.ZomboidDir.sub("PZOE"), "lut.cube");
    }

    /** Lua (main thread): the card's LUT is on or off. */
    static void want(boolean on) {
        wanted = on;
        if (on) check(true);
    }

    /** Main thread, from ScreenPass.main: picks up a changed file every couple of seconds. */
    static void frame() {
        if (!wanted) return;
        long now = System.nanoTime();
        if (now - lastCheckNs < 2_000_000_000L) return;
        lastCheckNs = now;
        check(false);
    }

    private static void check(boolean force) {
        File f = file();
        long mod = f.isFile() ? f.lastModified() : 0L;
        if (!force && mod == lastModified) return;
        lastModified = mod;
        if (mod == 0L) {
            pendingSize = 0;
            pending = null;
            return;
        }
        try (BufferedReader r = java.nio.file.Files.newBufferedReader(f.toPath())) {
            int n = 0, k = 0;
            float[] data = null;
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("LUT_3D_SIZE")) {
                    n = Integer.parseInt(line.substring(11).trim());
                    if (n < 2 || n > 64) throw new IllegalArgumentException("LUT_3D_SIZE " + n);
                    data = new float[n * n * n * 3];
                    continue;
                }
                if (data == null || !(Character.isDigit(line.charAt(0)) || line.charAt(0) == '-' || line.charAt(0) == '.')) continue;
                String[] p = line.split("\\s+");
                if (p.length < 3 || k + 3 > data.length) continue;
                data[k++] = Float.parseFloat(p[0]);
                data[k++] = Float.parseFloat(p[1]);
                data[k++] = Float.parseFloat(p[2]);
            }
            if (data == null || k != data.length) throw new IllegalArgumentException("expected " + (n * n * n) + " entries");
            // Strip: x = r + b * n, y = g. The file runs r fastest, then g, then b.
            FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            float[] row = new float[n * n * 3];
            for (int g = 0; g < n; g++) {
                for (int b = 0; b < n; b++) {
                    for (int rr = 0; rr < n; rr++) {
                        int src = ((b * n + g) * n + rr) * 3, dst = (b * n + rr) * 3;
                        row[dst] = data[src];
                        row[dst + 1] = data[src + 1];
                        row[dst + 2] = data[src + 2];
                    }
                }
                fb.put(row);
            }
            fb.flip();
            pendingSize = n;
            pending = fb;
            PZOLogger.info("[PZOE] colour LUT loaded: " + f.getName() + ", " + n + " cube");
        } catch (Throwable t) {
            pendingSize = 0;
            pending = null;
            PZOLogger.warn("[PZOE] colour LUT " + f + " not usable: " + t.getMessage());
        }
    }

    /** Render thread, in the screen pass: binds the LUT on unit 4 and fills PZOLutInfo. */
    static void upload(int locSampler, int locInfo) {
        FloatBuffer p = pending;
        if (p != null) {
            pending = null;
            if (tex == 0) tex = GL11.glGenTextures();
            int n = pendingSize;
            int prev = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB16F, n * n, n, 0, GL11.GL_RGB, GL11.GL_FLOAT, p);
            GL13.glActiveTexture(prev);
            size = n;
        } else if (pendingSize == 0 && size != 0 && !wanted) {
            size = 0;
        }
        boolean ok = tex != 0 && size > 0 && pendingSize > 0;
        int prev = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, ok ? tex : 0);
        GL13.glActiveTexture(prev);
        if (locSampler >= 0) GL20.glUniform1i(locSampler, 4);
        GL20.glUniform4f(locInfo, Math.max(2, size), 0f, 0f, ok ? 1f : 0f);
    }
}
