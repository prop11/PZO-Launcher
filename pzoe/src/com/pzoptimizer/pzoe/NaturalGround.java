package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.ShaderPatchHost;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderUniformSetter;
import zombie.core.textures.Texture;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;

/**
 * Natural Ground: grass, dirt, sand, clay and gravel floors without the square grid. Everything happens while a chunk
 * is baked, in the game's own floor shader (tileWithDepth / seamFix2, patched by PZOE_Shaders.lua), so it costs
 * nothing per frame.
 *
 * Every floor piece is started in IsoSprite.startTileDepthShader with its own uniforms (depth per square), so one
 * more uniform chain entry tells the shader that this piece is natural ground, where its floor diamond sits in the
 * texture page, which world square it is on, and the art's mean colour. The shader then resamples the game's art in
 * world space (stochastic tiling), reshapes the soft edges of the blend overlays, and adds a slow world-scale drift.
 *
 * Mean colour and coverage of each natural texture are read back once from its texture page on the render thread
 * (one small glReadPixels of the floor's own rectangle). Until a texture is measured it draws without the variance
 * correction; the first measurements trigger one re-bake.
 */
public final class NaturalGround {
    private static final int GL_FLOAT_VEC4 = 35666;

    // Main thread: per sprite what the shader needs. Rebuilt when the sprite's texture changes.
    private static final class Info {
        Texture tex;
        boolean natural;
        float u0, v0, du, dv, texel;
    }

    private static final IdentityHashMap<IsoSprite, Info> infos = new IdentityHashMap<>();

    // Texture -> {r, g, b, lum, coverage, rank}; written by the render thread
    private static final ConcurrentHashMap<Texture, float[]> stats = new ConcurrentHashMap<>();
    private static final ArrayDeque<Texture> toMeasure = new ArrayDeque<>();
    private static final java.util.Set<Texture> queued = ConcurrentHashMap.newKeySet();
    private static volatile boolean measuredNew;

    // Set on commit: the floor patch is live
    static volatile boolean live;
    private static String signature = "";

    // Season and heat dryness, set once a game day; with Drought and Rain the soil moisture moves it, from -0.3 (lush
    // after a wet spell) to 1 (parched)
    static volatile float dryness;
    // Drought and Rain (card option): soil moisture 0..1 from the rain history, saved with the world
    static volatile boolean soilWanted = true;
    private static final String SOIL_KEY = "PZOE_SoilMoisture";
    private static float moisture = -1f;
    private static double soilHours = -1.0, soilSaved = -1.0;
    private static int lastDay = Integer.MIN_VALUE;
    private static long lastMeasureRebake;

    static volatile long draws, naturalDraws;
    private static long lastLog, loggedDraws, loggedNatural;
    // After a commit: re-bake again once the floor program has been rebuilt with (or without) the uniforms
    private static boolean awaitRebuild;
    private static int awaitFrames;

    private NaturalGround() {}

    /** After ShaderPatchHost.commit: whether the patch is live, and a re-bake when its look changed. */
    static void commit() {
        boolean on = ShaderPatchHost.liveTextContains("PZOGroundUV");
        String sig = on + "|" + ShaderPatchHost.fillCommitted("${PZOE_NG_TILE}|${PZOE_NG_EDGES}|${PZOE_NG_MACRO}");
        live = on;
        if (!sig.equals(signature)) {
            boolean first = signature.isEmpty() && !on;
            signature = sig;
            lastDay = Integer.MIN_VALUE;
            if (!first) {
                BakePass.rebakeWanted = true;
                awaitRebuild = true;
                awaitFrames = 0;
                PZOLogger.info("[NaturalGround] " + (on ? "on" : "off") + ", loaded chunks re-bake");
            }
        }
    }

    /** Main thread, once a frame (PixelLight.globalFrame). */
    static void frame() {
        // Uniform locations are looked up again every frame: a rebuild can keep the same program object and id
        depthRef.ground = -1;
        seamRef.ground = -1;
        if (awaitRebuild) {
            boolean has = programHasGround();
            if (has == live) {
                awaitRebuild = false;
                BakePass.rebakeWanted = true;
            } else if (++awaitFrames > 900) {
                awaitRebuild = false;
            }
        }
        if (!live) return;
        long now = System.currentTimeMillis();
        if (now - lastLog > 30000) {
            lastLog = now;
            long d = draws - loggedDraws, nd = naturalDraws - loggedNatural;
            loggedDraws = draws;
            loggedNatural = naturalDraws;
            if (d > 0) PZOLogger.info("[NaturalGround] last 30 s: " + nd + " natural floor pieces of " + d + " floor draws, "
                + stats.size() + " textures measured, dryness " + dryness);
        }
        zombie.GameTime gt = zombie.GameTime.getInstance();
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        if (gt != null && cm != null) {
            int day = gt.getNightsSurvived();
            boolean newDay = day != lastDay;
            boolean first = lastDay == Integer.MIN_VALUE;
            if (newDay) {
                lastDay = day;
                seasonBase = seasonDryness(gt.getMonth(), cm.getTemperature());
            }
            boolean soilMoved = soilWanted && soil(gt, cm);
            boolean toggled = soilWanted != soilWas;
            soilWas = soilWanted;
            if (newDay || soilMoved || toggled) {
                float d = seasonBase;
                if (soilWanted && moisture >= 0f) {
                    // Weeks without rain parch it past the season; a wet spell greens it beyond
                    d += 0.35f * smooth(0.35f, 0.05f, moisture) - 0.3f * smooth(0.65f, 0.95f, moisture);
                }
                d = Math.round(Math.max(-0.3f, Math.min(1f, d)) * 20f) / 20f;
                if (Math.abs(d - dryness) > 0.04f) {
                    dryness = d;
                    if (!first) BakePass.rebakeSoon = true;
                }
            }
        }
        // New measurements: re-bake once, at most every few seconds while textures keep coming in
        if (measuredNew) {
            if (now - lastMeasureRebake > 3000) {
                measuredNew = false;
                lastMeasureRebake = now;
                BakePass.rebakeSoon = true;
            }
        }
    }

    private static float seasonBase;
    private static boolean soilWas;

    // Rain soaks the soil (an hour of downpour about 0.05, a wet day 0.2 to 0.8); sun, heat and the hours dry it
    // slowly, about 0.05 a hot sunny day, so a drought takes two or three dry weeks. Worked out each quarter hour.
    // True when it moved enough to change the grass.
    private static boolean soil(zombie.GameTime gt, zombie.iso.weather.ClimateManager cm) {
        double hours = gt.getWorldAgeHours();
        if (moisture < 0f) {
            moisture = 0.5f;
            try {
                Object v = gt.getModData().rawget(SOIL_KEY);
                if (v instanceof Double dv) moisture = Math.max(0f, Math.min(1f, dv.floatValue()));
            } catch (Throwable ignored) {
            }
            soilHours = hours;
            soilSaved = hours;
            return true;
        }
        double dh = hours - soilHours;
        if (dh < 0.25) {
            if (dh < 0) soilHours = hours;
            return false;
        }
        soilHours = hours;
        float h = (float) Math.min(dh, 48.0);
        float p = cm.getPrecipitationIntensity();
        float rain = cm.getPrecipitationIsSnow() ? 0.25f * p : p;
        float sun = cm.getDayLightStrength() * (1f - cm.getCloudIntensity());
        float dry = 0.0006f + 0.0018f * sun + Math.max(0f, cm.getTemperature() - 18f) * 0.0001f;
        float before = moisture;
        moisture = Math.max(0f, Math.min(1f, moisture + h * (rain * 0.05f * (1f - moisture * 0.5f) - (rain > 0.02f ? 0f : dry))));
        if (hours - soilSaved > 1.0) {
            soilSaved = hours;
            try {
                gt.getModData().rawset(SOIL_KEY, Double.valueOf(moisture));
            } catch (Throwable ignored) {
            }
        }
        return Math.abs(moisture - before) > 0f;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    // Kentucky: brown dead grass in winter, green spring, drying through a hot summer into autumn
    private static final float[] MONTH_DRY = {0.45f, 0.4f, 0.25f, 0.05f, 0f, 0.05f, 0.2f, 0.35f, 0.4f, 0.5f, 0.55f, 0.5f};

    static float seasonDryness(int month, float temp) {
        float d = MONTH_DRY[Math.floorMod(month, 12)];
        if (temp > 24f) d += Math.min(0.3f, (temp - 24f) / 40f);
        return Math.round(Math.min(1f, d) * 20f) / 20f;
    }

    // Bytecode: IsoSprite.startTileDepthShader starts the floor shader with its uniform chain; one more entry goes
    // in front. startTileDepthShader2 (walls, fences) starts the same program without a chain, so it gets one that
    // clears the natural flag, else it would keep the last floor's.

    static final String SPRITE = "zombie/iso/sprite/IsoSprite";

    static boolean patchSprite(ClassNode node) {
        MethodNode start = com.pzoptimizer.AsmSupport.findMethod(node, "startTileDepthShader", "(Lzombie/iso/IsoObject;FFFFZ)V");
        MethodNode start2 = com.pzoptimizer.AsmSupport.findMethod(node, "startTileDepthShader2", "(IIFFFFZ)V");
        if (start == null || start2 == null) return false;
        String self = "com/pzoptimizer/pzoe/NaturalGround";
        int n = 0;
        for (AbstractInsnNode insn = start.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && "zombie/IndieGL".equals(mi.owner)
                    && "StartShader".equals(mi.name) && "(ILzombie/core/opengl/ShaderUniformSetter;)V".equals(mi.desc)) {
                InsnList args = new InsnList();
                args.add(new VarInsnNode(Opcodes.ALOAD, 0));
                args.add(new VarInsnNode(Opcodes.ALOAD, 1));
                args.add(new VarInsnNode(Opcodes.FLOAD, 2));
                args.add(new VarInsnNode(Opcodes.FLOAD, 3));
                start.instructions.insertBefore(mi, args);
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "startShader",
                    "(ILzombie/core/opengl/ShaderUniformSetter;Lzombie/iso/sprite/IsoSprite;Lzombie/iso/IsoObject;FF)V", false);
                start.instructions.set(mi, call);
                insn = call;
                n++;
            }
        }
        if (n == 0) return false;
        int m = 0;
        for (AbstractInsnNode insn = start2.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && "zombie/IndieGL".equals(mi.owner)
                    && "StartShader".equals(mi.name) && "(I)V".equals(mi.desc)) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "startShaderPlain", "(I)V", false);
                start2.instructions.set(mi, call);
                insn = call;
                m++;
            }
        }
        return m > 0;
    }

    private static boolean programHasGround() {
        try {
            Shader s = zombie.core.SceneShaderStore.tileDepthShader;
            zombie.core.opengl.ShaderProgram p = s == null ? null : s.getProgram();
            return p != null && p.getUniform("PZOGround", GL_FLOAT_VEC4, false) != null;
        } catch (Throwable t) {
            return live;
        }
    }

    private static ShaderProgramRef program(int id) {
        Shader s = zombie.core.SceneShaderStore.tileDepthShader;
        if (s != null && s.getID() == id) return ref(s);
        s = zombie.core.SceneShaderStore.tileSeamShader;
        if (s != null && s.getID() == id) return ref(s);
        return null;
    }

    // Uniform locations of one program, looked up again after any shader rebuild
    private static final class ShaderProgramRef {
        Object program;
        int ground = -1, uv = -1, mean = -1, env = -1;
    }

    private static final ShaderProgramRef depthRef = new ShaderProgramRef(), seamRef = new ShaderProgramRef();

    private static ShaderProgramRef ref(Shader s) {
        ShaderProgramRef r = s == zombie.core.SceneShaderStore.tileDepthShader ? depthRef : seamRef;
        zombie.core.opengl.ShaderProgram p = s.getProgram();
        if (p == null) return null;
        if (r.program != p || r.ground < 0) {
            r.program = p;
            r.ground = loc(p, "PZOGround");
            r.uv = loc(p, "PZOGroundUV");
            r.mean = loc(p, "PZOGroundMean");
            r.env = loc(p, "PZOGroundEnv");
        }
        return r.ground < 0 ? null : r;
    }

    private static int loc(zombie.core.opengl.ShaderProgram p, String name) {
        zombie.core.opengl.ShaderProgram.Uniform u = p.getUniform(name, GL_FLOAT_VEC4, false);
        return u == null ? -1 : u.loc;
    }

    /** Replaces IndieGL.StartShader(id, uniforms) in IsoSprite.startTileDepthShader. Main thread. */
    public static void startShader(int id, ShaderUniformSetter uniforms, IsoSprite sprite, IsoObject obj, float x, float y) {
        if (live) {
            try {
                uniforms = withGround(id, uniforms, sprite, x, y);
            } catch (Throwable t) {
                live = false;
                PZOLogger.warn("[NaturalGround] off after an error: " + t);
            }
        }
        zombie.IndieGL.StartShader(id, uniforms);
    }

    /** Replaces IndieGL.StartShader(id) in IsoSprite.startTileDepthShader2. Main thread. */
    public static void startShaderPlain(int id) {
        if (live) {
            ShaderProgramRef r = program(id);
            if (r != null) {
                zombie.IndieGL.StartShader(id, ShaderUniformSetter.uniform4f(r.ground, 0f, 0f, 0f, 0f));
                return;
            }
        }
        zombie.IndieGL.StartShader(id);
    }

    private static ShaderUniformSetter withGround(int id, ShaderUniformSetter uniforms, IsoSprite sprite, float x, float y) {
        ShaderProgramRef r = program(id);
        if (r == null) return uniforms;
        draws++;
        Info info = sprite == null ? null : info(sprite);
        ShaderUniformSetter head;
        if (info == null || !info.natural) {
            head = ShaderUniformSetter.uniform4f(r.ground, 0f, 0f, 0f, 0f);
        } else {
            naturalDraws++;
            float[] st = stats.get(info.tex);
            if (st == null) queue(info.tex);
            // An overlay is a piece that doesn't cover its whole diamond (the soft-edged blends between materials)
            float kind = st != null && st[4] < 0.97f ? 2f : 1f;
            float rank = st == null ? 0.5f : st[5];
            float wx = (float) Math.floorMod((int) Math.floor(x), 8192), wy = (float) Math.floorMod((int) Math.floor(y), 8192);
            head = ShaderUniformSetter.uniform4f(r.ground, kind, wx, wy, rank);
            ShaderUniformSetter tail = head.setNext(ShaderUniformSetter.uniform4f(r.uv, info.u0, info.v0, info.du, info.dv));
            if (r.mean >= 0) {
                tail = st != null ? tail.setNext(ShaderUniformSetter.uniform4f(r.mean, st[0], st[1], st[2], st[3]))
                    : tail.setNext(ShaderUniformSetter.uniform4f(r.mean, 0f, 0f, 0f, -1f));
            }
            if (r.env >= 0) tail = tail.setNext(ShaderUniformSetter.uniform4f(r.env, dryness, info.texel, 0f, 0f));
            if (uniforms != null) tail.setNext(uniforms);
            return head;
        }
        if (uniforms != null) head.setNext(uniforms);
        return head;
    }

    private static Info info(IsoSprite sprite) {
        Texture tex = sprite.getTextureForCurrentFrame(zombie.iso.IsoDirections.N);
        Info info = infos.get(sprite);
        if (info != null && info.tex == tex) return info;
        if (info == null) infos.put(sprite, info = new Info());
        info.tex = tex;
        info.natural = false;
        if (tex == null || !isNaturalName(sprite.getName())) return info;
        if (sprite.getProperties() != null && sprite.getProperties().has(zombie.iso.SpriteDetails.IsoFlagType.water)) return info;
        int wo = tex.getWidthOrig(), ho = tex.getHeightOrig(), w = tex.getWidth(), h = tex.getHeight();
        if (wo <= 0 || ho <= 0 || w <= 0 || h <= 0 || ho < wo / 2) return info;
        // The floor diamond fills the frame's width and the bottom half-width of its height
        float diamondTop = ho - wo * 0.5f;
        float su = (tex.getXEnd() - tex.getXStart()) / w, sv = (tex.getYEnd() - tex.getYStart()) / h;
        info.u0 = tex.getXStart() + (0f - tex.getOffsetX()) * su;
        info.v0 = tex.getYStart() + (diamondTop - tex.getOffsetY()) * sv;
        info.du = wo * su;
        info.dv = wo * 0.5f * sv;
        info.texel = 1.5f / wo;
        info.natural = true;
        return info;
    }

    static boolean isNaturalName(String name) {
        if (name == null) return false;
        return name.startsWith("blends_natural_") || name.startsWith("floors_exterior_natural_");
    }

    private static void queue(Texture tex) {
        if (tex != null && queued.add(tex)) {
            synchronized (toMeasure) {
                toMeasure.add(tex);
            }
        }
    }

    // Render thread: a few textures per bake, read back from their page

    private static int fbo;
    private static ByteBuffer readBuf;

    /** Start of a chunk bake (PixelLight.bakeBegin), render thread. */
    static void renderThread() {
        if (!live) return;
        Texture tex;
        int budget = 6;
        while (budget-- > 0) {
            synchronized (toMeasure) {
                tex = toMeasure.poll();
            }
            if (tex == null) return;
            float[] st;
            try {
                st = measure(tex);
            } catch (Throwable t) {
                st = null;
                PZOLogger.warn("[NaturalGround] couldn't measure " + tex.getName() + ": " + t);
            }
            // A failed read still gets neutral values, so it isn't tried every bake
            stats.put(tex, st != null ? st : new float[] {0.5f, 0.5f, 0.5f, -1f, 1f, 0.5f});
            measuredNew = true;
        }
    }

    static float[] measure(Texture tex) {
        int page = tex.getID(), pw = tex.getWidthHW(), ph = tex.getHeightHW();
        int w = tex.getWidth(), h = tex.getHeight();
        if (page <= 0 || pw <= 0 || ph <= 0 || w <= 0 || h <= 0 || w > 1024 || h > 1024) return null;
        int x0 = Math.round(Math.min(tex.getXStart(), tex.getXEnd()) * pw);
        int y0 = Math.round(Math.min(tex.getYStart(), tex.getYEnd()) * ph);
        boolean flipped = tex.getYEnd() < tex.getYStart();
        int prevRead = GlGet.integer(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevPack = GlGet.integer(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int prevAlign = GlGet.integer(GL11.GL_PACK_ALIGNMENT);
        if (fbo == 0) fbo = GL30.glGenFramebuffers();
        if (readBuf == null || readBuf.capacity() < w * h * 4) readBuf = BufferUtils.createByteBuffer(Math.max(w * h * 4, 128 * 128 * 4));
        readBuf.clear();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, page, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) return null;
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            org.lwjgl.opengl.GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(x0, y0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readBuf);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            org.lwjgl.opengl.GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPack);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlign);
        }
        int wo = tex.getWidthOrig(), ho = tex.getHeightOrig();
        float ox = tex.getOffsetX(), oy = tex.getOffsetY(), top = ho - wo * 0.5f;
        double r = 0, g = 0, b = 0, aSum = 0;
        for (int j = 0; j < h; j++) {
            int row = flipped ? h - 1 - j : j;
            float v = (oy + row + 0.5f - top) / (wo * 0.5f);
            if (v < 0f || v > 1f) continue;
            for (int i = 0; i < w; i++) {
                float u = (ox + i + 0.5f) / wo;
                if (Math.abs(u - 0.5f) + Math.abs(v - 0.5f) > 0.5f) continue;
                int k = (j * w + i) * 4;
                // Pages hold premultiplied colour, so the sums are already alpha-weighted
                r += (readBuf.get(k) & 0xff) / 255.0;
                g += (readBuf.get(k + 1) & 0xff) / 255.0;
                b += (readBuf.get(k + 2) & 0xff) / 255.0;
                aSum += (readBuf.get(k + 3) & 0xff) / 255.0;
            }
        }
        double area = wo * (double) wo / 4.0;
        if (aSum < 1.0) return new float[] {0.5f, 0.5f, 0.5f, -1f, 0f, 0.5f};
        float mr = (float) (r / aSum), mg = (float) (g / aSum), mb = (float) (b / aSum);
        float lum = 0.3f * mr + 0.59f * mg + 0.11f * mb;
        return new float[] {mr, mg, mb, lum, (float) Math.min(1.0, aSum / area), rank(mr, mg, mb, lum)};
    }

    // Which material wins at an edge: grass over dirt over clay over gravel over sand (1 .. 0)
    static float rank(float r, float g, float b, float lum) {
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float sat = max <= 0f ? 0f : (max - min) / max;
        if (g >= r && g > b * 1.05f && sat > 0.12f) return 1f;
        if (sat < 0.12f) return 0.3f;
        if (r > g * 1.15f && lum < 0.5f) return 0.5f;
        if (lum > 0.45f) return 0.1f;
        return 0.75f;
    }

    static String stateText() {
        return "naturalGround=" + (live ? 1 : 0) + ";ngDraws=" + naturalDraws + "/" + draws + ";ngTextures=" + stats.size()
            + ";ngDry=" + Math.round(dryness * 100) + ";soil=" + Math.round(moisture * 100);
    }
}
