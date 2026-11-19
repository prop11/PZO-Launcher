package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * Progress bar in the bottom right corner while model shaders are made ahead, on the loading screen and in game if
 * the player clicks through first. "Building shader cache" when something had to be compiled, "Loading shader cache"
 * when it all came from disk. Bottom right because Viewpoint puts its own loading bar bottom left.
 *
 * Drawn from GameLoadingState.render and IngameState.renderFrameUI, just before each EndFrameUI, which also drives
 * ModelShadersAhead.tick once a frame. Main thread.
 */
public final class ShaderCacheBar {

    private static final int WIDTH = 260, HEIGHT = 6, MARGIN = 24;
    private static final long FADE_NANOS = 1_500_000_000L;
    private static long finishedAt;
    private static String building, loading;

    private ShaderCacheBar() {}

    public static void draw() {
        try {
            ModelShadersAhead.tick();
            int total = ModelShadersAhead.total();
            if (total <= 0) return;
            long now = System.nanoTime();
            float alpha = 1.0f;
            if (!ModelShadersAhead.busy()) {
                if (finishedAt == 0) finishedAt = now;
                long since = now - finishedAt;
                if (since >= FADE_NANOS) return;
                alpha = 1.0f - (float) since / FADE_NANOS;
            } else {
                finishedAt = 0;
            }
            int done = ModelShadersAhead.done();
            String label = (ModelShadersAhead.allFromCache() ? loadingText() : buildingText()) + "  " + done + " / " + total;

            zombie.core.Core core = zombie.core.Core.getInstance();
            zombie.ui.TextManager tm = zombie.ui.TextManager.instance;
            int fontH = tm.getFontHeight(zombie.ui.UIFont.Small);
            int x = core.getScreenWidth() - MARGIN - WIDTH;
            int y = core.getScreenHeight() - MARGIN - HEIGHT;
            zombie.core.SpriteRenderer sr = zombie.core.SpriteRenderer.instance;
            sr.renderi(null, x - 1, y - 1, WIDTH + 2, HEIGHT + 2, 0.0f, 0.0f, 0.0f, 0.6f * alpha, null);
            sr.renderi(null, x, y, WIDTH, HEIGHT, 0.25f, 0.25f, 0.25f, 0.8f * alpha, null);
            int fill = (int) ((long) WIDTH * done / total);
            if (fill > 0) sr.renderi(null, x, y, fill, HEIGHT, 0.45f, 0.75f, 0.35f, alpha, null);
            tm.DrawStringRight(zombie.ui.UIFont.Small, x + WIDTH, y - fontH - 4, label, 1.0, 1.0, 1.0, 0.9 * alpha);
        } catch (Throwable ignored) {
        }
    }

    private static String buildingText() {
        if (building == null) building = text("UI_MPOptim_ShaderCache_Building", "PZO: building shader cache");
        return building;
    }

    private static String loadingText() {
        if (loading == null) loading = text("UI_MPOptim_ShaderCache_Loading", "PZO: loading shader cache");
        return loading;
    }

    private static String text(String key, String fallback) {
        try {
            String t = zombie.core.Translator.getTextOrNull(key);
            return t == null || t.isEmpty() || t.equals(key) ? fallback : t;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // A call to draw() before every Core.EndFrameUI in the method
    static boolean patchBeforeEndFrameUI(ClassNode node, String method, String desc) {
        MethodNode m = AsmSupport.findMethod(node, method, desc);
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "com/pzoptimizer/ShaderCacheBar".equals(mi.owner)) return false;
        }
        java.util.List<MethodInsnNode> sites = new java.util.ArrayList<>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "zombie/core/Core".equals(mi.owner) && "EndFrameUI".equals(mi.name)) sites.add(mi);
        }
        if (sites.isEmpty()) return false;
        // The Core instance is already on the stack at the call, so draw() goes in before the load of it: the
        // getInstance call right before
        for (MethodInsnNode mi : sites) {
            AbstractInsnNode at = mi.getPrevious();
            while (at != null && at.getOpcode() < 0) at = at.getPrevious();
            if (!(at instanceof MethodInsnNode gi && "getInstance".equals(gi.name) && "zombie/core/Core".equals(gi.owner))) return false;
            m.instructions.insertBefore(at, new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ShaderCacheBar", "draw", "()V", false));
        }
        return true;
    }
}
