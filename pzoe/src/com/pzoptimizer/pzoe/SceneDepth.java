package com.pzoptimizer.pzoe;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import zombie.characters.IsoPlayer;
import zombie.core.textures.TextureFBO;

/**
 * The world's depth as a texture any later pass can read. Build 42 keeps the scene depth in a renderbuffer, so once a
 * frame, right before the final screen pass, it's copied into a depth texture (one blit of the drawn area). Smoke,
 * breath and glare test against it, and it's the input for anything that needs scene depth (half-res fog compositor,
 * model GTAO).
 *
 * Depth here is the game's own iso depth (IsoDepthHelper): nearer is smaller, and it's an affine function of the world
 * position, so a world point's depth is depthOf(x, y, z) and the shader can compare it with the texture.
 *
 *   uniform sampler2D PZOSceneDepth;   unit 7
 *   uniform vec4 PZOSceneDepthInfo;    x = 1 when live, y = uv scale into the depth texture (world render scale)
 *
 * Single view only for now: in split screen each view's depth would need its own rectangle, which isn't checked.
 */
final class SceneDepth {
    static final int UNIT = 7;

    // A screen shader declares PZOSceneDepthInfo, or smoke wants depth
    static volatile long askedNanos;

    private static int framebuffer, texture, width, height, capturedFrame = Integer.MIN_VALUE, capturedScene;
    private static int boundTexture;
    private static boolean unavailable;
    static volatile long captures, directBinds;

    private SceneDepth() {}

    static boolean asked(long now) {
        return now - askedNanos < 1_000_000_000L;
    }

    /** Main thread: whether this player's view has a depth image the screen pass can read. */
    static boolean usable(int playerIndex) {
        return playerIndex == 0 && IsoPlayer.numPlayers == 1 && zombie.core.PerformanceSettings.fboRenderChunk
            && IsoPlayer.players[0] != null && !unavailable;
    }

    /** Main thread: the scene depth of a world point as player 0's view draws it, or -1 when there's none. */
    static float depthOf(int playerIndex, float x, float y, float z) {
        if (!usable(playerIndex)) return -1f;
        IsoPlayer p = IsoPlayer.players[0];
        return zombie.iso.IsoDepthHelper.getSquareDepthData((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), x, y, z).depthStart;
    }

    /**
     * Render thread: copies the drawn area of the scene's depth (w x h texture, scale = world render scale) into the
     * depth texture once a frame and binds it on UNIT. False when there's no depth to read.
     */
    static boolean capture(TextureFBO scene, int w, int h, float scale, int frame) {
        if (scene == null || scene.getBufferId() <= 0 || w <= 0 || h <= 0) return false;
        int draw = GlGet.integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        // Still drawing into the scene: its depth can't be read as a texture at the same time
        if (draw == scene.getBufferId()) return false;
        int active = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        try {
            var direct = scene.getDepthTexture();
            if (direct != null && direct.getID() > 0 && direct.getWidthHW() == w && direct.getHeightHW() == h) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, direct.getID());
                boundTexture = direct.getID();
                directBinds++;
                return true;
            }
            if (unavailable) return false;
            if (frame == capturedFrame && scene.getBufferId() == capturedScene && texture != 0 && width == w && height == h) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                return true;
            }
            int read = GlGet.integer(GL30.GL_READ_FRAMEBUFFER_BINDING);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            try {
                if (!target(w, h)) return false;
                if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
                int x1 = Math.min(w, (int) Math.ceil(w * Math.max(0.01f, Math.min(1f, scale))));
                int y1 = Math.min(h, (int) Math.ceil(h * Math.max(0.01f, Math.min(1f, scale))));
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scene.getBufferId());
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                GL30.glBlitFramebuffer(0, 0, x1, y1, 0, 0, x1, y1, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                boundTexture = texture;
                capturedFrame = frame;
                capturedScene = scene.getBufferId();
                captures++;
                return true;
            } finally {
                if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            }
        } finally {
            GL13.glActiveTexture(active);
        }
    }

    /** Render thread: the depth texture last captured or bound (0 before the first), for later passes. */
    static int texture() {
        return boundTexture;
    }

    private static boolean target(int w, int h) {
        if (texture != 0 && width == w && height == h) return true;
        if (texture == 0) texture = GL11.glGenTextures();
        if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers();
        if (texture == 0 || framebuffer == 0) return fail();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, w, h, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, 0);
        int prev = GlGet.integer(GL30.GL_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        boolean ok = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        if (!ok) return fail();
        width = w;
        height = h;
        return true;
    }

    private static boolean fail() {
        unavailable = true;
        com.pzoptimizer.PZOLogger.warn("[PZOE] scene depth copy unavailable on this driver; depth-tested effects draw without it");
        return false;
    }

    static String stateText() {
        return "sceneDepthCaptures=" + captures + ";sceneDepthDirect=" + directBinds + ";sceneDepthOff=" + (unavailable ? 1 : 0)
            + ";sceneDepthW=" + width + ";sceneDepthH=" + height;
    }
}
