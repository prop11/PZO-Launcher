package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import zombie.core.textures.Mask;
import zombie.core.textures.Texture;
import zombie.iso.IsoDirections;
import zombie.iso.objects.IsoTree;
import zombie.iso.sprite.IsoSpriteInstance;

/** Reusable light-facing silhouettes sampled from the texture pack's CPU-side sprite masks. */
final class TreeShadowProfiles {
    private static final int WIDTH = 32, HEIGHT = 64, COLS = 8, CAPACITY = 256;
    private static final int BROADLEAF = 1, CONIFER = 2;
    private static final float ISO_WIDTH = 90.51f, LEVEL_HEIGHT = 192f;
    private static final IdentityHashMap<Texture, IdentityHashMap<Texture, Profile>> known = new IdentityHashMap<>();
    private static final ConcurrentLinkedQueue<Profile> pending = new ConcurrentLinkedQueue<>();
    private static int next = 3;

    private static final ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
    private static final byte[] sharp = new byte[WIDTH * HEIGHT * 2];
    private static int texture, ready;

    private record SpriteMask(Mask mask, int x, int y, int width, int height) {
        SpriteMask(Texture texture) {
            this(texture.getMask(), (int) texture.getOffsetX(), (int) texture.getOffsetY(), texture.getWidth(), texture.getHeight());
        }

        boolean opaque(int px, int py) {
            int sx = px - x, sy = py - y;
            return sx >= 0 && sx < width && sy >= 0 && sy < height && mask.get(sx, sy);
        }
    }

    private record Profile(int slot, SpriteMask wood, SpriteMask leaves, int left, int bottom, int width, int height) {}

    private TreeShadowProfiles() {}

    /** Main thread. Tree map bytes are profile, level + 1, height and half-width in 1/32 world units. */
    static int describe(IsoTree tree, int level) {
        Texture wood = tree.sprite.getTextureForCurrentFrame(IsoDirections.N);
        ArrayList<IsoSpriteInstance> attached = tree.getAttachedAnimSprite();
        Texture leaves = attached == null || attached.isEmpty() ? null
            : attached.get(0).parentSprite.getTextureForCurrentFrame(IsoDirections.N);
        int size = Math.max(1, Math.min(8, tree.getSize()));
        String name = tree.sprite.getName();
        boolean conifer = name != null && (name.contains("pine") || name.contains("spruce")
            || name.contains("cedar") || name.contains("hemlock"));
        int slot = conifer ? CONIFER : BROADLEAF;
        float height = 0.5f + 0.48f * size;
        float halfWidth = 0.2f + 0.16f * size;

        if (wood != null && wood.isReady() && wood.getMask() != null) {
            if (leaves != null && (!leaves.isReady() || leaves.getMask() == null || leaves == wood)) leaves = null;
            IdentityHashMap<Texture, Profile> variants = known.get(wood);
            Profile profile = variants == null ? null : variants.get(leaves);
            if (profile == null && next < CAPACITY) {
                profile = profile(next, wood, leaves);
                if (profile != null) {
                    if (variants == null) {
                        variants = new IdentityHashMap<>();
                        known.put(wood, variants);
                    }
                    variants.put(leaves, profile);
                    pending.add(profile);
                    next++;
                }
            }
            if (profile != null) {
                slot = profile.slot;
                height = profile.height / LEVEL_HEIGHT;
                halfWidth = profile.width / (2f * ISO_WIDTH);
            }
        }
        int h = Math.round(Math.max(0.35f, Math.min(7.9f, height)) * 32f);
        int w = Math.round(Math.max(0.16f, Math.min(1.43f, halfWidth)) * 32f);
        return slot | ((level + 1) << 8) | (h << 16) | ((w | (conifer ? 128 : 0)) << 24);
    }

    private static Profile profile(int slot, Texture wood, Texture leaves) {
        int woodX = (int) wood.getOffsetX(), woodY = (int) wood.getOffsetY();
        int bottom = woodY + wood.getHeight();
        int sumX = 0, count = 0;
        Mask mask = wood.getMask();
        for (int y = wood.getHeight() - 1; y >= Math.max(0, wood.getHeight() - 12); y--) {
            for (int x = 0; x < wood.getWidth(); x++) {
                if (mask.get(x, y)) {
                    sumX += x;
                    count++;
                }
            }
            if (count > 0 && y < wood.getHeight() - 3) break;
        }
        if (count == 0) return null;
        int center = woodX + Math.round((float) sumX / count);
        int top = woodY, left = woodX, right = woodX + wood.getWidth();
        if (leaves != null) {
            top = Math.min(top, (int) leaves.getOffsetY());
            left = Math.min(left, (int) leaves.getOffsetX());
            right = Math.max(right, (int) leaves.getOffsetX() + leaves.getWidth());
        }
        int radius = Math.max(center - left, right - center) + 2;
        return new Profile(slot, new SpriteMask(wood), leaves == null ? null : new SpriteMask(leaves),
            center - radius, bottom + 1, radius * 2, bottom + 1 - top);
    }

    /** Render thread, on the profile atlas texture unit. */
    static int bind() {
        if (texture == 0) {
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, COLS * WIDTH, CAPACITY / COLS * HEIGHT,
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            fallback(BROADLEAF);
            fallback(CONIFER);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }
        // Texture packs can reveal many variants at once when a forest enters the loaded area.
        for (int i = 0; i < 6; i++) {
            Profile profile = pending.poll();
            if (profile == null) break;
            rasterize(profile);
        }
        return ready;
    }

    private static void rasterize(Profile profile) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int wood = 0, leaves = 0;
                for (int dy = 0; dy < 2; dy++) {
                    int sy = (int) (profile.bottom - (y + (dy + 0.5f) * 0.5f) * profile.height / HEIGHT);
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = (int) (profile.left + (x + (dx + 0.5f) * 0.5f) * profile.width / WIDTH);
                        if (profile.wood.opaque(sx, sy)) wood++;
                        if (profile.leaves != null && profile.leaves.opaque(sx, sy)) leaves++;
                    }
                }
                int at = (y * WIDTH + x) * 2;
                sharp[at] = (byte) (wood * 255 / 4);
                sharp[at + 1] = (byte) (leaves * 255 / 4);
            }
        }
        upload(profile.slot);
    }

    private static void fallback(int slot) {
        for (int y = 0; y < HEIGHT; y++) {
            float h = (y + 0.5f) / HEIGHT;
            for (int x = 0; x < WIDTH; x++) {
                float side = (x + 0.5f) / WIDTH * 2f - 1f;
                float a = Math.abs(side);
                boolean trunk = a < 0.085f - h * 0.06f && h < 0.78f;
                boolean branch = h > 0.35f && h < 0.82f && a < 0.65f
                    && Math.abs(a - (h - 0.37f) * 1.6f) < 0.035f;
                boolean crown = ellipse(side + 0.34f, h - 0.63f, 0.57f, 0.25f)
                    || ellipse(side - 0.39f, h - 0.68f, 0.48f, 0.23f)
                    || ellipse(side + 0.05f, h - 0.78f, 0.55f, 0.21f);
                boolean pine = h > 0.19f && a < (1f - h) * (1.07f - 0.15f * ((y / 7) % 2));
                int at = (y * WIDTH + x) * 2;
                sharp[at] = (byte) ((trunk || branch || (slot == CONIFER && pine)) ? 255 : 0);
                sharp[at + 1] = (byte) (slot == BROADLEAF && crown ? 215 : 0);
            }
        }
        upload(slot);
    }

    private static boolean ellipse(float x, float y, float rx, float ry) {
        return x * x / (rx * rx) + y * y / (ry * ry) < 1f;
    }

    private static void upload(int slot) {
        pixels.clear();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int at = (y * WIDTH + x) * 2;
                pixels.put(sharp[at]).put(sharp[at + 1]);
                int wood = 0, leaves = 0;
                for (int dy = Math.max(0, y - 1); dy <= Math.min(HEIGHT - 1, y + 1); dy++) {
                    for (int dx = Math.max(0, x - 1); dx <= Math.min(WIDTH - 1, x + 1); dx++) {
                        int near = (dy * WIDTH + dx) * 2;
                        wood += Byte.toUnsignedInt(sharp[near]);
                        leaves += Byte.toUnsignedInt(sharp[near + 1]);
                    }
                }
                pixels.put((byte) (wood / 9)).put((byte) (leaves / 9));
            }
        }
        pixels.flip();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, slot % COLS * WIDTH, slot / COLS * HEIGHT,
            WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        ready = slot;
    }
}
