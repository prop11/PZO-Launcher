package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.io.BufferedInputStream;
import java.util.Base64;
import java.util.WeakHashMap;
import zombie.IndieGL;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.objects.IsoWindow;
import zombie.iso.weather.ClimateManager;

public final class WindowCondensation {
    private static final String OWNER = "com/pzoptimizer/pzoe/WindowCondensation";
    private static final int STRIPS = 8;
    private static final long SAMPLE_INTERVAL = 200_000_000L;
    static volatile boolean enabled;
    private static final WeakHashMap<IsoWindow, Moisture> panes = new WeakHashMap<>();
    private static Texture mask;
    private static boolean failed;

    private WindowCondensation() {}

    private static final class Moisture {
        long last;
        float amount;
    }

    public static void render(FBORenderCell renderer, IsoObject object) {
        if (!enabled || failed || !(object instanceof IsoWindow window)) return;
        try {
            if (renderer.renderTranslucentOnly && !renderer.renderWindowFrameOutline && FBORenderCell.instance == renderer
                && object.getSquare() != null) render(window);
        } catch (Exception | LinkageError problem) {
            failed = true;
            PZOLogger.warn("[PZOE] Window condensation unavailable: " + problem);
        }
    }

    private static void render(IsoWindow window) throws Exception {
        var fs = IsoCamera.frameState;
        var square = window.getSquare();
        if (window.isSmashed() || window.isDestroyed() || window.isGlassRemoved()) {
            panes.remove(window);
            return;
        }
        boolean open = window.isOpen();
        Moisture state = panes.get(window);
        long now = System.nanoTime();
        if (state == null || now - state.last >= SAMPLE_INTERVAL) {
            float target = 0f;
            var climate = ClimateManager.getInstance();
            float outside = climate.getTemperature();
            if (!open && outside < 13f && window.isExterior()) {
                float gain = climate.getAirTemperatureForSquare(window.getIndoorSquare()) - outside;
                target = clamp((gain - 3f) / 14f) * clamp((13f - outside) / 15f)
                    * (0.45f + 0.55f * clamp(climate.getHumidity()));
            }
            if (state == null) {
                state = new Moisture();
                state.amount = target;
                panes.put(window, state);
            } else {
                float seconds = Math.min(zombie.GameTime.isGamePaused() ? 0f : 30f, (now - state.last) * 1e-9f);
                float duration = target > state.amount ? 8f : 3.5f;
                state.amount += (target - state.amount) * (1f - (float) Math.exp(-seconds / duration));
            }
            state.last = now;
        }
        if (open || state.amount < 0.006f || window.getAlpha(fs.playerIndex) < 0.85f || window.isBarricaded()) return;
        var curtain = window.HasCurtains();
        if (curtain != null && !curtain.IsOpen()) return;

        if (mask == null) {
            try (var source = WindowCondensation.class.getResourceAsStream("window-condensation.png.b64")) {
                if (source == null) throw new IllegalStateException("window condensation mask missing");
                try (var image = new BufferedInputStream(Base64.getMimeDecoder().wrap(source))) {
                    mask = new Texture("PZOE window condensation", image, false);
                    mask.setUseAlphaChannel(true);
                }
            }
        }
        if (!mask.isReady()) return;
        float light = 0.20f + 0.60f * ClimateManager.getInstance().getDayLightStrength();
        var lighting = square.lighting[fs.playerIndex].lightInfo();
        draw(window, state.amount * 0.68f, Math.max(light, lighting.r), Math.max(light, lighting.g),
            Math.max(light, lighting.b), fs);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static void draw(IsoWindow window, float alpha, float r, float g, float b, IsoCamera.FrameState fs) {
        var square = window.getSquare();
        var camera = IsoCamera.cameras[fs.playerIndex];
        float x = square.x + camera.fixJigglyModelsSquareX;
        float y = square.y + camera.fixJigglyModelsSquareY;
        float top = square.z + 0.78f, bottom = square.z + 0.40f;
        boolean north = window.isNorth();
        float dx = north ? 1f : 0f, dy = north ? 0f : 1f;
        float offX = camera.getOffX(), offY = camera.getOffY();
        var chunk = IsoDepthHelper.getChunkDepthData((int) Math.floor(fs.camCharacterX / 8f),
            (int) Math.floor(fs.camCharacterY / 8f), Math.floorDiv(square.x, 8), Math.floorDiv(square.y, 8), square.z);
        float chunkDepth = chunk.depthStart * 2f;
        IndieGL.StartShader(0);
        IndieGL.glDepthMask(false);
        IndieGL.glDepthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        for (int i = 0; i < STRIPS; i++) {
            float left = 0.17f + 0.66f * i / STRIPS, right = 0.17f + 0.66f * (i + 1) / STRIPS;
            float x1 = x + dx * left, y1 = y + dy * left;
            float x2 = x + dx * right, y2 = y + dy * right;
            float sx1 = IsoUtils.XToScreen(x1, y1, top, 0) - offX, sx2 = IsoUtils.XToScreen(x2, y2, top, 0) - offX;
            float sy1 = IsoUtils.YToScreen(x1, y1, top, 0) - offY, sy2 = IsoUtils.YToScreen(x2, y2, top, 0) - offY;
            float by1 = IsoUtils.YToScreen(x1, y1, bottom, 0) - offY, by2 = IsoUtils.YToScreen(x2, y2, bottom, 0) - offY;
            float u1 = mask.getXStart() + (mask.getXEnd() - mask.getXStart()) * (north ? i : STRIPS - i) / STRIPS;
            float u2 = mask.getXStart() + (mask.getXEnd() - mask.getXStart()) * (north ? i + 1 : STRIPS - i - 1) / STRIPS;
            float v1 = mask.getYStart(), v2 = mask.getYEnd();
            // Use the near edge of each strip and a small bias so the glass depth does not hide its own moisture.
            TextureDraw.nextZ = (IsoDepthHelper.calculateDepth(x2, y2, top) - 0.0002f) * 2f - 1f;
            TextureDraw.nextChunkDepth = chunkDepth;
            if (north) SpriteRenderer.instance.renderPoly(mask, sx1, sy1, sx2, sy2, sx2, by2, sx1, by1,
                r, g, b, alpha, u1, v1, u2, v1, u2, v2, u1, v2);
            else SpriteRenderer.instance.renderPoly(mask, sx2, sy2, sx1, sy1, sx1, by1, sx2, by2,
                r, g, b, alpha, u2, v1, u1, v1, u1, v2, u2, v2);
        }
        IndieGL.EndShader();
    }

    static boolean patch(ClassNode node) {
        var method = AsmSupport.findMethod(node, "renderTranslucent", "(Lzombie/iso/IsoObject;)V");
        if (method == null) return false;
        int count = 0;
        for (var instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call) || !call.desc.equals("(Lzombie/iso/IsoObject;)V")
                || !(call.name.equals("renderMinusFloor_DoorOrWall") || call.name.equals("renderMinusFloor_NotDoorOrWall"))) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "render",
                "(Lzombie/iso/fboRenderChunk/FBORenderCell;Lzombie/iso/IsoObject;)V", false));
            method.instructions.insert(call, hook);
            count++;
        }
        return count > 0;
    }
}
