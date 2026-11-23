package com.pzoptimizer.pzoe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

final class FireAtlas {
    private static final int SIDE = 48, FRAMES = 12;
    private static final int CHANNELS = 4;
    static final int UNIT = 6;
    private static int texture;
    private static boolean unavailable;

    private FireAtlas() {}

    static boolean bind() {
        if (unavailable) return false;
        int active = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        try {
            if (texture == 0 && !load()) return false;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            return true;
        } finally {
            GL13.glActiveTexture(active);
        }
    }

    private static boolean load() {
        byte[] bytes;
        try (var resource = FireAtlas.class.getResourceAsStream("fire-flow.rgba8.gz.b64")) {
            if (resource == null) return unavailable();
            try (var stream = new GZIPInputStream(Base64.getMimeDecoder().wrap(resource))) {
                bytes = stream.readNBytes(SIDE * SIDE * FRAMES * CHANNELS + 1);
            }
        } catch (IOException e) {
            return unavailable();
        }
        if (bytes.length != SIDE * SIDE * FRAMES * CHANNELS) return unavailable();
        int rowBytes = SIDE * CHANNELS;
        ByteBuffer pixels = ByteBuffer.allocateDirect(rowBytes * (SIDE + 2) * FRAMES);
        for (int frame = 0; frame < FRAMES; frame++) {
            for (int y = -1; y <= SIDE; y++) {
                int row = frame * SIDE + Math.floorMod(y, SIDE);
                pixels.put(bytes, row * rowBytes, rowBytes);
            }
        }
        pixels.flip();
        texture = GL11.glGenTextures();
        if (texture == 0) return unavailable();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIDE, (SIDE + 2) * FRAMES, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        return true;
    }

    private static boolean unavailable() {
        unavailable = true;
        return false;
    }
}
