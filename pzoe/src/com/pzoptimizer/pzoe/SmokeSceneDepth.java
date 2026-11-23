package com.pzoptimizer.pzoe;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import zombie.core.textures.TextureFBO;

final class SmokeSceneDepth {
    static final int UNIT = 7;
    private static int framebuffer, texture, width, height;
    private static boolean unavailable;

    private SmokeSceneDepth() {}

    static boolean bind(TextureFBO scene, int w, int h, int x0, int y0, int x1, int y1) {
        if (scene == null || scene.getBufferId() <= 0 || x0 >= x1 || y0 >= y1) return false;
        int draw = GlGet.integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (draw == scene.getBufferId()) return false;
        int active = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        try {
            var direct = scene.getDepthTexture();
            if (direct != null && direct.getID() > 0 && direct.getWidthHW() == w && direct.getHeightHW() == h) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, direct.getID());
                return true;
            }
            if (unavailable) return false;
            int read = GlGet.integer(GL30.GL_READ_FRAMEBUFFER_BINDING);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            try {
                if (!target(w, h)) return false;
                if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scene.getBufferId());
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                GL30.glBlitFramebuffer(x0, y0, x1, y1, x0, y0, x1, y1, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
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
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) return fail();
        width = w;
        height = h;
        return true;
    }

    private static boolean fail() {
        unavailable = true;
        return false;
    }
}
