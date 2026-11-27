package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Render-thread shadow coverage, with separate sun and local light channels. */
final class CharacterShadowMask {
    static final int SQUARES_PER_CHUNK = 8;
    static final int CHUNKS = 10;
    static final int TEXELS_PER_SQUARE = 4;
    static final int BASE_LEVEL = -32, WORLD_LEVELS = 64;
    private static final int PAGE = SQUARES_PER_CHUNK * TEXELS_PER_SQUARE;
    private static final int SIDE = CHUNKS * PAGE;
    private static final int COLS = 4;
    private static final int ROWS = (CharShadows.MAX + COLS - 1) / COLS;
    private static int textureUnit;

    static int textureUnit() {
        if (textureUnit == 0) textureUnit = GlGet.integer(GL20.GL_MAX_TEXTURE_IMAGE_UNITS) > 18 ? 18 : 2;
        return textureUnit;
    }

    private final long[] chunkLevels = new long[CHUNKS * CHUNKS];
    private final int[] slots = new int[WORLD_LEVELS];
    private final int[] minX = new int[WORLD_LEVELS], minY = new int[WORLD_LEVELS];
    private final int[] maxX = new int[WORLD_LEVELS], maxY = new int[WORLD_LEVELS];
    private final byte[] pixels = new byte[SIDE * SIDE * 2];
    private final ByteBuffer upload = BufferUtils.createByteBuffer(pixels.length);
    private CharShadows.Snapshot current;
    private int texture;

    int texture() {
        return texture;
    }

    int chunkSlots(int x, int y, int minLevel) {
        int level = minLevel - BASE_LEVEL;
        if (level < 0 || level >= WORLD_LEVELS) return 0;
        // Build 42 composites up to two floors per baked chunk. Slot 0 in either field means no coverage.
        long bits = chunkLevels[y * CHUNKS + x] >>> level;
        int low = (bits & 1L) == 0 ? 0 : slots[level] + 1;
        int high = (bits & 2L) == 0 ? 0 : slots[level + 1] + 1;
        return low | high << 5;
    }

    void update(CharShadows.Snapshot snapshot) {
        if (snapshot == current) return;
        Arrays.fill(chunkLevels, 0);
        Arrays.fill(minX, CHUNKS);
        Arrays.fill(minY, CHUNKS);
        Arrays.fill(maxX, -1);
        Arrays.fill(maxY, -1);
        float[] data = snapshot.casters();
        long activeLevels = 0;
        final int stride = CharShadows.STRIDE;
        for (int i = 0; i < snapshot.count() * stride; i += stride) {
            int level = (int) data[i + 2] - BASE_LEVEL;
            float x = data[i], y = data[i + 1];
            float reach = data[i + 3], dx = data[i + 4], dy = data[i + 5];
            float pad = data[i + 7] < 0f ? Math.max(data[i + 10], data[i + 11]) + 0.7f : data[i + 7] * 1.35f + 0.6f;
            int x0 = chunk(Math.min(x, x + dx * reach) - pad);
            int y0 = chunk(Math.min(y, y + dy * reach) - pad);
            int x1 = chunk(Math.max(x, x + dx * reach) + pad);
            int y1 = chunk(Math.max(y, y + dy * reach) + pad);
            long bit = 1L << level;
            activeLevels |= bit;
            minX[level] = Math.min(minX[level], x0);
            minY[level] = Math.min(minY[level], y0);
            maxX[level] = Math.max(maxX[level], x1);
            maxY[level] = Math.max(maxY[level], y1);
            for (int cy = y0; cy <= y1; cy++) {
                for (int cx = x0; cx <= x1; cx++) chunkLevels[cy * CHUNKS + cx] |= bit;
            }
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit());
        try {
            if (texture == 0) allocateTexture();
            else GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            int slot = 0;
            for (long levels = activeLevels; levels != 0; levels &= levels - 1) {
                int z = Long.numberOfTrailingZeros(levels);
                slots[z] = slot;
                // Pad for bilinear filtering and keep RG8 rows aligned even with eight-byte unpack alignment.
                int x0 = Math.max(0, minX[z] * PAGE - 4), y0 = Math.max(0, minY[z] * PAGE - 4);
                int x1 = Math.min(SIDE, (maxX[z] + 1) * PAGE + 4);
                int y1 = Math.min(SIDE, (maxY[z] + 1) * PAGE + 4);
                int width = x1 - x0, bytes = width * (y1 - y0) * 2;
                Arrays.fill(pixels, 0, bytes, (byte) 0);
                for (int i = 0; i < snapshot.count() * stride; i += stride) {
                    if ((int) data[i + 2] - BASE_LEVEL != z) continue;
                    if (data[i + 7] < 0f) stampBox(data, i, x0, y0, x1, y1);
                    else stamp(data, i, x0, y0, x1, y1);
                }
                upload.clear();
                upload.put(pixels, 0, bytes).flip();
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (slot % COLS) * SIDE + x0, (slot / COLS) * SIDE + y0,
                    width, y1 - y0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, upload);
                slot++;
            }
            current = snapshot;
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    private void allocateTexture() {
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, COLS * SIDE, ROWS * SIDE, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    private static int chunk(float offset) {
        return Math.min(CHUNKS - 1, Math.max(0, (int) Math.floor(offset / SQUARES_PER_CHUNK)));
    }

    private void stamp(float[] data, int i, int x0, int y0, int x1, int y1) {
        float x = data[i], y = data[i + 1], length = data[i + 3];
        float dx = data[i + 4], dy = data[i + 5], width = data[i + 7];
        float darkness = Math.abs(data[i + 6]) * 255f;
        int channel = data[i + 6] < 0f ? 1 : 0;
        float startX = x - 0.3f * dx, endX = x + (length + 0.4f) * dx;
        float startY = y - 0.3f * dy, endY = y + (length + 0.4f) * dy;
        float padX = Math.abs(dy) * (width * 1.25f + 0.14f);
        float padY = Math.abs(dx) * (width * 1.25f + 0.14f);
        int left = Math.max(x0, (int) Math.floor((Math.min(startX, endX) - padX) * TEXELS_PER_SQUARE));
        int right = Math.min(x1, (int) Math.ceil((Math.max(startX, endX) + padX) * TEXELS_PER_SQUARE));
        int top = Math.max(y0, (int) Math.floor((Math.min(startY, endY) - padY) * TEXELS_PER_SQUARE));
        int bottom = Math.min(y1, (int) Math.ceil((Math.max(startY, endY) + padY) * TEXELS_PER_SQUARE));
        for (int py = top; py < bottom; py++) {
            float oy = (py + 0.5f) / TEXELS_PER_SQUARE - y;
            int at = ((py - y0) * (x1 - x0) + left - x0) * 2 + channel;
            for (int px = left; px < right; px++, at += 2) {
                float ox = (px + 0.5f) / TEXELS_PER_SQUARE - x;
                float along = ox * dx + oy * dy;
                if (along <= -0.3f || along >= length + 0.4f) continue;
                float across = Math.abs(ox * dy - oy * dx);
                float t = Math.max(0f, Math.min(1f, along / length));
                float w = width * (1f - 0.25f * t) + 0.1f * t;
                if (across >= w * 1.25f) continue;
                float alpha = (1f - smooth(w * 0.45f, w * 1.25f, across)) * smooth(-0.3f, 0f, along)
                    * (1f - smooth(length * 0.7f, length + 0.4f, along)) * (1f - 0.55f * t);
                int value = Math.round(alpha * darkness);
                if (value > Byte.toUnsignedInt(pixels[at])) pixels[at] = (byte) value;
            }
        }
    }

    // A vehicle: its body box (centre, forward axis, half length, half width) swept along the shadow by its length. A
    // texel is in the shadow when some point of the sweep puts it inside the box; four shrunken boxes soften the edge.
    private void stampBox(float[] data, int i, int x0, int y0, int x1, int y1) {
        float x = data[i], y = data[i + 1], length = data[i + 3];
        float dx = data[i + 4], dy = data[i + 5];
        float fx = data[i + 8], fy = data[i + 9], hl = data[i + 10], hw = data[i + 11];
        float darkness = Math.abs(data[i + 6]) * 255f;
        int channel = data[i + 6] < 0f ? 1 : 0;
        float reach = Math.max(hl, hw) + 0.3f;
        int left = Math.max(x0, (int) Math.floor((Math.min(x, x + dx * length) - reach) * TEXELS_PER_SQUARE));
        int right = Math.min(x1, (int) Math.ceil((Math.max(x, x + dx * length) + reach) * TEXELS_PER_SQUARE));
        int top = Math.max(y0, (int) Math.floor((Math.min(y, y + dy * length) - reach) * TEXELS_PER_SQUARE));
        int bottom = Math.min(y1, (int) Math.ceil((Math.max(y, y + dy * length) + reach) * TEXELS_PER_SQUARE));
        // Light direction in the box's own axes
        float df = dx * fx + dy * fy, ds = dy * fx - dx * fy;
        for (int py = top; py < bottom; py++) {
            float oy = (py + 0.5f) / TEXELS_PER_SQUARE - y;
            int at = ((py - y0) * (x1 - x0) + left - x0) * 2 + channel;
            for (int px = left; px < right; px++, at += 2) {
                float ox = (px + 0.5f) / TEXELS_PER_SQUARE - x;
                float a = ox * fx + oy * fy, b = oy * fx - ox * fy;
                float cover = 0f, first = length;
                for (int k = 0; k < 4; k++) {
                    float shrink = 0.12f * k - 0.12f;
                    float s = sweep(a, df, hl - shrink, b, ds, hw - shrink, length);
                    if (s >= 0f) {
                        cover += 0.25f;
                        first = Math.min(first, s);
                    }
                }
                if (cover <= 0f) continue;
                // Lighter toward the far end, like the people's shadows
                float alpha = cover * (1f - 0.45f * Math.max(0f, Math.min(1f, first / Math.max(0.1f, length))));
                int value = Math.round(alpha * darkness);
                if (value > Byte.toUnsignedInt(pixels[at])) pixels[at] = (byte) value;
            }
        }
    }

    // Smallest s in [0, length] with |a - s da| <= ha and |b - s db| <= hb, or -1 when there's none
    private static float sweep(float a, float da, float ha, float b, float db, float hb, float length) {
        if (ha <= 0f || hb <= 0f) return -1f;
        float lo = 0f, hi = length;
        if (Math.abs(da) < 1e-5f) {
            if (Math.abs(a) > ha) return -1f;
        } else {
            float s0 = (a - ha) / da, s1 = (a + ha) / da;
            lo = Math.max(lo, Math.min(s0, s1));
            hi = Math.min(hi, Math.max(s0, s1));
        }
        if (Math.abs(db) < 1e-5f) {
            if (Math.abs(b) > hb) return -1f;
        } else {
            float s0 = (b - hb) / db, s1 = (b + hb) / db;
            lo = Math.max(lo, Math.min(s0, s1));
            hi = Math.min(hi, Math.max(s0, s1));
        }
        return lo <= hi ? lo : -1f;
    }

    private static float smooth(float a, float b, float value) {
        float t = Math.max(0f, Math.min(1f, (value - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }
}
