package com.pzoptimizer;

import java.nio.FloatBuffer;
import org.lwjgl.util.vector.Matrix;
import org.lwjgl.util.vector.Matrix4f;

/**
 * Bulk replacements for Matrix4f.store(FloatBuffer) and Matrix4f.load(FloatBuffer) in
 * AnimatedModel$AnimatedModelInstanceRenderData (initMatrixPalette writes every bone of every animated model into a
 * direct buffer each frame, init reads it back). The lwjgl methods do sixteen single-float put/get calls, and on
 * this JDK every access to a direct buffer pays a memory-session validity check. These copy the same sixteen floats
 * in the same order (m00, m01, m02, m03, m10 ... m33, as Matrix4f.store does) through one bulk put or get.
 */
public final class PaletteBuffers {

    public static volatile boolean active = true;

    // The game thread's scratch row; any other thread gets its own array
    private static volatile Thread owner;
    private static final float[] scratch = new float[16];

    private PaletteBuffers() {}

    private static float[] row() {
        Thread t = Thread.currentThread();
        Thread o = owner;
        if (o == null) {
            synchronized (PaletteBuffers.class) {
                if (owner == null) owner = t;
                o = owner;
            }
        }
        return t == o ? scratch : new float[16];
    }

    public static Matrix store(Matrix4f m, FloatBuffer buf) {
        if (!active) return m.store(buf);
        float[] f = row();
        f[0] = m.m00; f[1] = m.m01; f[2] = m.m02; f[3] = m.m03;
        f[4] = m.m10; f[5] = m.m11; f[6] = m.m12; f[7] = m.m13;
        f[8] = m.m20; f[9] = m.m21; f[10] = m.m22; f[11] = m.m23;
        f[12] = m.m30; f[13] = m.m31; f[14] = m.m32; f[15] = m.m33;
        buf.put(f, 0, 16);
        return m;
    }

    public static Matrix load(Matrix4f m, FloatBuffer buf) {
        if (!active) return m.load(buf);
        float[] f = row();
        buf.get(f, 0, 16);
        m.m00 = f[0]; m.m01 = f[1]; m.m02 = f[2]; m.m03 = f[3];
        m.m10 = f[4]; m.m11 = f[5]; m.m12 = f[6]; m.m13 = f[7];
        m.m20 = f[8]; m.m21 = f[9]; m.m22 = f[10]; m.m23 = f[11];
        m.m30 = f[12]; m.m31 = f[13]; m.m32 = f[14]; m.m33 = f[15];
        return m;
    }
}
