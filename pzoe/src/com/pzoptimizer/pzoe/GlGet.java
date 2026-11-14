package com.pzoptimizer.pzoe;

import org.lwjgl.opengl.GL11;

/**
 * glGetInteger without garbage. The game's LWJGL wraps a new native segment around the stack buffer on every
 * convenience getter (fmac measured gigabytes of short-lived objects from these in Atmospherics), the array overload
 * doesn't. Render thread only.
 */
final class GlGet {

    private static final int[] out = new int[1];

    private GlGet() {}

    static int integer(int pname) {
        GL11.glGetIntegerv(pname, out);
        return out[0];
    }
}
