package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import gnu.trove.list.array.TIntArrayList;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import zombie.core.SpriteRenderer;
import zombie.core.PerformanceSettings;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBOLinesShader;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.weather.fx.IsoWeatherFX;
import zombie.iso.weather.fx.ParticleRectangle;
import zombie.iso.weather.fx.WeatherParticle;
import zombie.iso.weather.fx.WeatherParticleDrawer;

/**
 * Weather particles (rain, snow, clouds, fog) as one tile repeated across the screen.
 *
 * ParticleRectangle.render draws its particle set once per screen cell, offset by the cell origin: with ~20 x 10
 * cells every particle is added to the drawer ~200 times on the main thread and turned into ~200 quads on the
 * render thread. Every cell shows the same particles, so here the set is added once at the origin and the drawer
 * keeps the list of cell positions. The render thread packs those quads into one vertex buffer and draws it once per
 * origin with the cell offset folded into the ModelViewProjection uniform (the same VBORenderer shader and vertex
 * layout). Per texture the draws go cell by cell in stock order, so blending matches; particles that stock culls
 * per cell as off screen are clipped by the GPU instead. Rain's per-cell double arithmetic can differ from the
 * GPU offset by float rounding (well under a thousandth of a pixel).
 */
public final class WeatherTileBatcher {

    private static volatile boolean active = !System.getProperty("os.name", "").toLowerCase().contains("mac");
    public static final AtomicLong tilesBuilt = new AtomicLong();
    public static final AtomicLong tileDraws = new AtomicLong();

    private static final int SLOTS = 8;
    private static final int STRIDE = 36; // position 3, colour 4, uv 2 floats, as VBORenderer.formatPositionColorUv

    // The handles below load game classes, so they live in a holder: the agent calls this class's patch methods
    // while one of those classes is being defined, and loading it again from there is a duplicate definition
    private static final class H {
        private static final VarHandle PR_WIDTH, PR_HEIGHT, PR_PARTICLES, PR_ID, P_ALPHA;
        private static final VarHandle D_BUFFER, D_TEXTURES, D_BY_TEXTURE, D_TILES, FX_INSTANCE, FX_DEBUG_BOUNDS;
        static {
            VarHandle w = null, h = null, parts = null, id = null, alpha = null, buf = null, tex = null, byTex = null, tiles = null;
            VarHandle fxInstance = null, fxDebug = null;
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                MethodHandles.Lookup pr = MethodHandles.privateLookupIn(ParticleRectangle.class, l);
                w = pr.findVarHandle(ParticleRectangle.class, "width", int.class);
                h = pr.findVarHandle(ParticleRectangle.class, "height", int.class);
                parts = pr.findVarHandle(ParticleRectangle.class, "particles", WeatherParticle[].class);
                id = pr.findVarHandle(ParticleRectangle.class, "id", int.class);
                alpha = MethodHandles.privateLookupIn(WeatherParticle.class, l).findVarHandle(WeatherParticle.class, "renderAlpha", float.class);
                MethodHandles.Lookup d = MethodHandles.privateLookupIn(WeatherParticleDrawer.class, l);
                buf = d.findVarHandle(WeatherParticleDrawer.class, "particleBuffer", ByteBuffer.class);
                tex = d.findVarHandle(WeatherParticleDrawer.class, "textures", ArrayList.class);
                byTex = d.findVarHandle(WeatherParticleDrawer.class, "particlesByTexture", TIntArrayList[].class);
                tiles = d.findVarHandle(WeatherParticleDrawer.class, "pzo$tiles", Object.class);
                MethodHandles.Lookup fx = MethodHandles.privateLookupIn(IsoWeatherFX.class, l);
                fxInstance = fx.findStaticVarHandle(IsoWeatherFX.class, "instance", IsoWeatherFX.class);
                fxDebug = fx.findStaticVarHandle(IsoWeatherFX.class, "debugBounds", boolean.class);
            } catch (Throwable t) {
                PZOLogger.warn("[WeatherTileBatcher] fields unavailable, tiles disabled: " + t);
                active = false;
            }
            PR_WIDTH = w;
            PR_HEIGHT = h;
            PR_PARTICLES = parts;
            PR_ID = id;
            P_ALPHA = alpha;
            D_BUFFER = buf;
            D_TEXTURES = tex;
            D_BY_TEXTURE = byTex;
            D_TILES = tiles;
            FX_INSTANCE = fxInstance;
            FX_DEBUG_BOUNDS = fxDebug;
        }
    }

    private WeatherTileBatcher() {}

    public static void setActive(boolean value) {
        active = value && H.D_TILES != null;
    }

    /** One ParticleRectangle's particles for one frame: per texture slot the particle buffer offsets, and the cell positions. */
    private static final class Tile {
        final int[][] positions = new int[SLOTS][];
        final int[] counts = new int[SLOTS];
        float[] cellXY = new float[64];
        int cellCount;
    }

    /** Per drawer and frame, written on the main thread, read on the render thread for the same render state. */
    private static final class Batch {
        final ArrayList<Tile> tiles = new ArrayList<>();
    }

    private static Batch batch(WeatherParticleDrawer drawer, boolean create) {
        Object o = H.D_TILES.get(drawer);
        if (o == null && create) {
            o = new Batch();
            H.D_TILES.set(drawer, o);
        }
        return (Batch) o;
    }

    // ---- main thread ----

    // WeatherParticleDrawer.startFrame: the drawer's particle buffer is being reset, so are its tiles
    public static void onStartFrame(WeatherParticleDrawer drawer) {
        if (H.D_TILES == null) return;
        Batch b = batch(drawer, false);
        if (b != null) b.tiles.clear();
    }

    /** Replaces the body of ParticleRectangle.render when it returns true. */
    public static boolean renderTiled(ParticleRectangle rect) {
        if (!active || !PerformanceSettings.fboRenderChunk || (boolean) H.FX_DEBUG_BOUNDS.get()) return false;
        WeatherParticle[] particles = (WeatherParticle[]) H.PR_PARTICLES.get(rect);
        if (particles == null) return false;
        int width = (int) H.PR_WIDTH.get(rect);
        int height = (int) H.PR_HEIGHT.get(rect);

        // Cell grid exactly as stock computes it
        int player = IsoCamera.frameState.playerIndex;
        int screenW = IsoCamera.frameState.offscreenWidth;
        int screenH = IsoCamera.frameState.offscreenHeight;
        int cellsW = (int) Math.ceil(screenW / width) + 2;
        int cellsH = (int) Math.ceil(screenH / height) + 2;
        int gridOffsetX = IsoCamera.frameState.offX >= 0.0F
            ? (int) IsoCamera.frameState.offX % width
            : width - (int) Math.abs(IsoCamera.frameState.offX) % width;
        int gridOffsetY = IsoCamera.frameState.offY >= 0.0F
            ? (int) IsoCamera.frameState.offY % height
            : height - (int) Math.abs(IsoCamera.frameState.offY) % height;
        int baseX = -gridOffsetX;
        int baseY = -gridOffsetY;
        SpriteRenderer.instance.StartShader(0, player);

        WeatherParticleDrawer drawer = ((IsoWeatherFX) H.FX_INSTANCE.get()).getDrawer((int) H.PR_ID.get(rect));
        TIntArrayList[] byTexture = (TIntArrayList[]) H.D_BY_TEXTURE.get(drawer);
        int[] start = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) start[i] = byTexture[i].size();

        for (WeatherParticle p : particles) {
            if (p != null && !((float) H.P_ALPHA.get(p) <= 0.0F)) {
                p.render(0.0F, 0.0F);
            }
        }

        // Move this rectangle's entries out of the drawer's own lists so its stock pass does not draw them too
        Tile tile = new Tile();
        boolean any = false;
        for (int i = 0; i < SLOTS; i++) {
            int n = byTexture[i].size() - start[i];
            tile.counts[i] = n;
            if (n > 0) {
                tile.positions[i] = byTexture[i].toArray(start[i], n);
                byTexture[i].remove(start[i], n);
                any = true;
            }
        }
        if (!any) return true;
        for (int cy = -1; cy < cellsH; cy++) {
            for (int cx = -1; cx < cellsW; cx++) {
                if (tile.cellCount * 2 + 2 > tile.cellXY.length) tile.cellXY = Arrays.copyOf(tile.cellXY, tile.cellXY.length * 2);
                tile.cellXY[tile.cellCount * 2] = baseX + cx * width;
                tile.cellXY[tile.cellCount * 2 + 1] = baseY + cy * height;
                tile.cellCount++;
            }
        }
        batch(drawer, true).tiles.add(tile);
        tilesBuilt.incrementAndGet();
        return true;
    }

    // ---- render thread ----

    private static VBOLinesShader shader;
    private static int vbo;
    private static ByteBuffer staging;
    private static final Matrix4f BASE = new Matrix4f();
    private static final Matrix4f MVP = new Matrix4f();

    // WeatherParticleDrawer.render, after its GL state is pushed and before its own VBORenderer pass
    public static void drawTiles(WeatherParticleDrawer drawer) {
        if (H.D_TILES == null) return;
        Batch b = batch(drawer, false);
        if (b == null || b.tiles.isEmpty()) return;
        ByteBuffer particles = (ByteBuffer) H.D_BUFFER.get(drawer);
        @SuppressWarnings("unchecked")
        ArrayList<Texture> textures = (ArrayList<Texture>) H.D_TEXTURES.get(drawer);
        VBORenderer.getInstance().flush();

        ShaderProgram program = null;
        try {
            if (shader == null) shader = new VBOLinesShader("vboRenderer_PositionColorUV");
            program = shader.getProgram();
        } catch (Throwable t) {
            program = null;
        }
        if (program == null || !program.isCompiled()) {
            drawWithVboRenderer(b, particles, textures);
            return;
        }

        if (vbo == 0) vbo = GL15.glGenBuffers();
        program.Start();
        VertexBufferObject.setModelViewProjection(program);
        BASE.set(program.projection).mul(program.modelView);
        program.setValue("userDepth", 0.0F);
        GL11.glDisable(2929);
        GL11.glEnable(3553);
        GL13.glActiveTexture(33984);
        GL15.glBindBuffer(34962, vbo);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glEnableVertexAttribArray(2);
        GL20.glDisableVertexAttribArray(3);
        GL20.glDisableVertexAttribArray(4);
        GL20.glVertexAttribPointer(0, 3, 5126, false, STRIDE, 0L);
        GL20.glVertexAttribPointer(1, 4, 5126, true, STRIDE, 12L);
        GL20.glVertexAttribPointer(2, 2, 5126, false, STRIDE, 28L);

        // Texture-major, then tile, then cell, then particle: the order stock's per-texture lists hold
        for (int slot = 0; slot < SLOTS && slot < textures.size(); slot++) {
            Texture texture = textures.get(slot);
            for (Tile tile : b.tiles) {
                int n = tile.counts[slot];
                if (n <= 0) continue;
                packQuads(particles, tile.positions[slot], n, texture);
                GL15.glBufferData(34962, staging, 35040);
                texture.getTextureId().bind();
                for (int o = 0; o < tile.cellCount; o++) {
                    MVP.set(BASE).translate(tile.cellXY[o * 2], tile.cellXY[o * 2 + 1], 0.0F);
                    program.setValue("ModelViewProjection", MVP);
                    GL11.glDrawArrays(7, 0, n * 4);
                }
                tileDraws.addAndGet(tile.cellCount);
            }
        }

        // setModelViewProjection skips the upload while the stack matrices are unchanged, so put the uniform back
        program.setValue("ModelViewProjection", BASE);
        GL15.glBindBuffer(34962, 0);
        for (int i = 0; i < 5; i++) GL20.glEnableVertexAttribArray(i);
        GL11.glEnable(2929);
        SpriteRenderer.ringBuffer.restoreVbos = true;
        SpriteRenderer.ringBuffer.restoreBoundTextures = true;
    }

    private static void packQuads(ByteBuffer particles, int[] positions, int n, Texture texture) {
        int bytes = n * 4 * STRIDE;
        if (staging == null || staging.capacity() < bytes) staging = BufferUtils.createByteBuffer(Math.max(bytes, 1 << 16));
        staging.clear();
        float u0 = texture.getXStart(), v0 = texture.getYStart(), u1 = texture.getXEnd(), v1 = texture.getYEnd();
        for (int j = 0; j < n; j++) {
            int p = positions[j];
            float r = particles.getFloat(p + 32), g = particles.getFloat(p + 36), bl = particles.getFloat(p + 40), a = particles.getFloat(p + 44);
            vertex(particles.getFloat(p), particles.getFloat(p + 4), r, g, bl, a, u0, v0);
            vertex(particles.getFloat(p + 8), particles.getFloat(p + 12), r, g, bl, a, u1, v0);
            vertex(particles.getFloat(p + 16), particles.getFloat(p + 20), r, g, bl, a, u1, v1);
            vertex(particles.getFloat(p + 24), particles.getFloat(p + 28), r, g, bl, a, u0, v1);
        }
        staging.flip();
    }

    private static void vertex(float x, float y, float r, float g, float b, float a, float u, float v) {
        staging.putFloat(x).putFloat(y).putFloat(0.0F).putFloat(r).putFloat(g).putFloat(b).putFloat(a).putFloat(u).putFloat(v);
    }

    // No usable shader: the same quads through VBORenderer, one per cell, as stock would have added them
    private static void drawWithVboRenderer(Batch b, ByteBuffer particles, ArrayList<Texture> textures) {
        VBORenderer vbor = VBORenderer.getInstance();
        for (int slot = 0; slot < SLOTS && slot < textures.size(); slot++) {
            Texture texture = textures.get(slot);
            for (Tile tile : b.tiles) {
                int n = tile.counts[slot];
                if (n <= 0) continue;
                vbor.startRun(vbor.formatPositionColorUv);
                vbor.setMode(7);
                vbor.setTextureID(texture.getTextureId());
                for (int o = 0; o < tile.cellCount; o++) {
                    float ox = tile.cellXY[o * 2], oy = tile.cellXY[o * 2 + 1];
                    for (int j = 0; j < n; j++) {
                        int p = tile.positions[slot][j];
                        vbor.addQuad(
                            ox + particles.getFloat(p), oy + particles.getFloat(p + 4), texture.getXStart(), texture.getYStart(),
                            ox + particles.getFloat(p + 8), oy + particles.getFloat(p + 12), texture.getXEnd(), texture.getYStart(),
                            ox + particles.getFloat(p + 16), oy + particles.getFloat(p + 20), texture.getXEnd(), texture.getYEnd(),
                            ox + particles.getFloat(p + 24), oy + particles.getFloat(p + 28), texture.getXStart(), texture.getYEnd(),
                            0.0F, particles.getFloat(p + 32), particles.getFloat(p + 36), particles.getFloat(p + 40), particles.getFloat(p + 44));
                    }
                }
                vbor.endRun();
            }
        }
        vbor.flush();
    }

    // ---- ASM ----

    static boolean patchParticleRectangle(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "render", "()V");
        if (m == null) return false;
        InsnList head = new InsnList();
        LabelNode stock = new LabelNode();
        head.add(new VarInsnNode(Opcodes.ALOAD, 0));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/WeatherTileBatcher", "renderTiled",
            "(Lzombie/iso/weather/fx/ParticleRectangle;)Z", false));
        head.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        head.add(new InsnNode(Opcodes.RETURN));
        head.add(stock);
        m.instructions.insert(head);
        return true;
    }

    static boolean patchDrawer(ClassNode node) {
        MethodNode render = AsmSupport.findMethod(node, "render", "()V");
        MethodNode startFrame = AsmSupport.findMethod(node, "startFrame", "()V");
        if (render == null || startFrame == null) return false;
        MethodInsnNode firstVbor = null;
        for (AbstractInsnNode insn = render.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "zombie/core/opengl/VBORenderer".equals(mi.owner) && "getInstance".equals(mi.name)) {
                firstVbor = mi;
                break;
            }
        }
        if (firstVbor == null) return false;
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT, "pzo$tiles", "Ljava/lang/Object;", null, null));

        InsnList draw = new InsnList();
        draw.add(new VarInsnNode(Opcodes.ALOAD, 0));
        draw.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/WeatherTileBatcher", "drawTiles",
            "(Lzombie/iso/weather/fx/WeatherParticleDrawer;)V", false));
        render.instructions.insertBefore(firstVbor, draw);

        InsnList reset = new InsnList();
        reset.add(new VarInsnNode(Opcodes.ALOAD, 0));
        reset.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/WeatherTileBatcher", "onStartFrame",
            "(Lzombie/iso/weather/fx/WeatherParticleDrawer;)V", false));
        startFrame.instructions.insert(reset);
        return true;
    }
}
