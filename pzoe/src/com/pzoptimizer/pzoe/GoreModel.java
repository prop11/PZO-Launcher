package com.pzoptimizer.pzoe;

import com.pzoptimizer.ShaderPatchHost;
import com.pzoptimizer.pzoe.gore.GoreAtlas;
import com.pzoptimizer.pzoe.gore.GoreState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL20;
import zombie.core.rendering.ShaderParameter;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.SkinningData;

/**
 * PZOE Gore on bodies: wounds where the hits landed, missing limbs and heads, bodies cut in two and rot, drawn inside
 * the game's own character shaders (patched by PZOE_Gore.lua). Nothing is re-textured and nothing extra is drawn.
 *
 * No rest-pose positions are used anywhere, so modded bodies and skeletons work like the game's own: a wound belongs
 * to a bone (the vertex shader knows how much each vertex hangs on it) and gets its shape from noise in the model's
 * texture coordinates; a lost limb is its bone and everything under it in the hierarchy. Bones are found by name
 * (spaces and underscores ignored), per model, since every piece of clothing has its own bone order.
 *
 *   uniform vec4 PZOWound[8];   bone index + 1 in this model (0 = not on this piece), seed 0..1, kind, size
 *   uniform vec4 PZOGoreInfo;   wound count, decay 0..1, 0, flags (1 = some bones are gone)
 *   uniform vec4 PZOGoreCut;    bones that are gone: bits 0-19, 20-39, 40-59 of this model's bone indices
 */
public final class GoreModel {
    static final String MARK = "PZO_GORE_MODELS", MARK_VEH = "PZO_GORE_VEHICLES", DATA = "PZOGoreData", VEH = "PZOGoreVeh";
    static volatile boolean wanted;
    static volatile int quality = 2;
    private static long lastCheckNs;
    private static final float[] ZERO = new float[40];

    // The bone each body part's wound sits on (BloodBodyPartType order)
    private static final String[] PART_BONE = {"Bip01_L_Hand", "Bip01_R_Hand", "Bip01_L_Forearm", "Bip01_R_Forearm",
        "Bip01_L_UpperArm", "Bip01_R_UpperArm", "Bip01_Spine1", "Bip01_Spine", "Bip01_Head", "Bip01_Neck", "Bip01_Pelvis",
        "Bip01_L_Thigh", "Bip01_R_Thigh", "Bip01_L_Calf", "Bip01_R_Calf", "Bip01_L_Foot", "Bip01_R_Foot", "Bip01_Spine1"};
    // Cut bits to the bone the cut starts at (it and everything under it go)
    private static final int[] LIMB_BIT = {GoreState.HEAD, GoreState.ARM_L_UP, GoreState.ARM_L_LOW, GoreState.ARM_R_UP,
        GoreState.ARM_R_LOW, GoreState.LEG_L_UP, GoreState.LEG_L_LOW, GoreState.LEG_R_UP, GoreState.LEG_R_LOW};
    private static final String[] LIMB_BONE = {"Bip01_Head", "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_R_UpperArm",
        "Bip01_R_Forearm", "Bip01_L_Thigh", "Bip01_L_Calf", "Bip01_R_Thigh", "Bip01_R_Calf"};

    private GoreModel() {}

    static void configure(int q) {
        quality = Math.max(1, Math.min(3, q));
    }

    /** Main thread, once a frame (ModelSnow.frame). */
    static void frame(long now) {
        if (now - lastCheckNs < 1_000_000_000L) return;
        lastCheckNs = now;
        wanted = ShaderPatchHost.liveTextContains(MARK) || ShaderPatchHost.liveTextContains(MARK_VEH);
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace(" ", "");
    }

    private static final class Bones {
        final Map<String, Integer> index = new HashMap<>();
        final long[] below;

        Bones(SkinningData sd) {
            int n = sd.numBones();
            if (sd.boneIndices != null) {
                for (Map.Entry<String, Integer> e : sd.boneIndices.entrySet()) index.put(key(e.getKey()), e.getValue());
            }
            // below[j] = bit mask of j and every bone under it, first 60 bones (the shader's palette)
            below = new long[n];
            for (int i = 0; i < n && i < 60; i++) {
                for (int j = i, guard = 0; j >= 0 && j < n && guard < 128; guard++) {
                    if (j < 64) below[j] |= 1L << i;
                    int p = sd.getParentBoneIdx(j);
                    if (p == j) break;
                    j = p;
                }
            }
        }

        int bone(String name) {
            Integer i = index.get(key(name));
            return i == null || i < 0 || i >= 60 ? -1 : i;
        }

        long under(String name) {
            int i = bone(name);
            return i < 0 || i >= below.length ? 0L : below[i];
        }
    }

    private static final Map<SkinningData, Bones> bones = new IdentityHashMap<>();
    private static boolean logged;

    private static Bones bonesOf(SkinningData sd) {
        Bones b = bones.get(sd);
        if (b == null) {
            b = new Bones(sd);
            bones.put(sd, b);
            if (!logged && b.bone("Bip01_Head") >= 0) {
                logged = true;
                com.pzoptimizer.PZOLogger.info("[PZOE gore] body skeleton: " + sd.numBones() + " bones, head " + b.bone("Bip01_Head")
                    + ", left forearm " + b.bone("Bip01_L_Forearm") + ", spine " + b.bone("Bip01_Spine1"));
            }
        }
        return b;
    }

    // Built uniform blocks, per record and skeleton; replaced (never changed) when the record changes
    private static final class Built {
        int version = Integer.MIN_VALUE;
        final Map<SkinningData, float[]> data = new IdentityHashMap<>();
    }

    private static final Map<GoreState, Built> built = new IdentityHashMap<>();

    private static float[] build(GoreState g, Bones b) {
        float[] d = new float[40];
        int n = Math.min(g.count, quality <= 1 ? 4 : GoreState.MAX_WOUNDS), w = 0;
        for (int i = 0; i < n; i++) {
            int p = g.part[i];
            if (p < 0 || p >= PART_BONE.length) continue;
            int bone = b.bone(PART_BONE[p]);
            int o = w * 4;
            // A piece that doesn't carry that bone (shoes for an arm wound) keeps the slot empty
            d[o] = bone < 0 ? 0f : bone + 1;
            d[o + 1] = (float) ((g.around[i] * 0.618 + g.along[i] * 0.377 + p * 0.131) % 1.0);
            d[o + 2] = g.kind[i];
            d[o + 3] = g.size[i];
            w++;
        }
        long bits = 0;
        int cut = g.cut | (g.crush ? GoreState.HEAD : 0);
        for (int k = 0; k < LIMB_BIT.length; k++) {
            if ((cut & LIMB_BIT[k]) != 0) bits |= b.under(LIMB_BONE[k]);
        }
        // Cut in two: everything from the middle of the spine up goes
        if (g.slice != 0) bits |= b.under("Bip01_Spine1");
        d[32] = w;
        d[33] = g.decay;
        d[34] = 0f;
        d[35] = bits != 0 ? 1f : 0f;
        d[36] = (float) (bits & 0xFFFFF);
        d[37] = (float) ((bits >>> 20) & 0xFFFFF);
        d[38] = (float) ((bits >>> 40) & 0xFFFFF);
        d[39] = 0f;
        return d;
    }

    /** Main thread, from ModelSnow.initModel: the model's gore block, from its owner or the corpse being drawn. */
    static void initModel(AnimatedModel.AnimatedModelInstanceRenderData data, ModelInstance mi, Object owner) {
        if (!wanted || data == null || mi == null) return;
        float[] d = ZERO;
        if (owner instanceof zombie.vehicles.BaseVehicle v) {
            float[] gl = GoreFx.glassOf(v);
            data.properties.SetVector4(VEH, gl == null ? 0f : gl[0], gl == null ? 0f : gl[1], GoreFx.palette, gl == null ? 0f : 1f);
            return;
        }
        try {
            GoreState g = owner != null ? GoreState.of(owner) : GoreAtlas.current();
            if (g != null && mi.model != null && mi.model.tag instanceof SkinningData sd) {
                Built bt = built.get(g);
                if (bt == null) built.put(g, bt = new Built());
                if (bt.version != g.version) {
                    bt.version = g.version;
                    bt.data.clear();
                }
                d = bt.data.get(sd);
                if (d == null) {
                    d = build(g, bonesOf(sd));
                    bt.data.put(sd, d);
                }
                g.lastSeen = com.pzoptimizer.pzoe.gore.GoreCore.clock();
            }
        } catch (Throwable ignored) {
            d = ZERO;
        }
        data.properties.SetFloatArray(DATA, d);
        if (built.size() > 512) built.clear();
    }

    // Render thread
    private static final Map<Integer, int[]> locs = new HashMap<>();
    private static final Map<Integer, float[]> sent = new HashMap<>();
    private static long locsClearedNs;
    private static final FloatBuffer buf = ByteBuffer.allocateDirect(32 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** Program bound (ModelSnow's character, preview and vehicle hooks). */
    static void upload(int prog, AnimatedModel.AnimatedModelInstanceRenderData d) {
        if (!wanted) return;
        long now = System.nanoTime();
        if (now - locsClearedNs > 500_000_000L) {
            locsClearedNs = now;
            locs.clear();
            sent.clear();
        }
        int[] l = locs.get(prog);
        if (l == null) {
            l = new int[] {GL20.glGetUniformLocation(prog, "PZOWound"), GL20.glGetUniformLocation(prog, "PZOGoreInfo"),
                GL20.glGetUniformLocation(prog, "PZOGoreCut"), GL20.glGetUniformLocation(prog, VEH)};
            locs.put(prog, l);
        }
        if (l[3] >= 0) {
            ShaderParameter q = d == null ? null : d.properties.GetParameter(VEH);
            if (q != null && q.GetValue() instanceof org.lwjgl.util.vector.Vector4f g) GL20.glUniform4f(l[3], g.x, g.y, g.z, g.w);
            else GL20.glUniform4f(l[3], 0f, 0f, 0f, 0f);
        }
        if (l[1] < 0) return;
        float[] v = ZERO;
        ShaderParameter p = d == null ? null : d.properties.GetParameter(DATA);
        if (p != null && p.GetValue() instanceof float[] f && f.length >= 40) v = f;
        if (sent.get(prog) == v) return;
        sent.put(prog, v);
        if (l[0] >= 0) {
            buf.clear();
            buf.put(v, 0, 32).flip();
            GL20.glUniform4fv(l[0], buf);
        }
        GL20.glUniform4f(l[1], v[32], v[33], v[34], v[35]);
        if (l[2] >= 0) GL20.glUniform4f(l[2], v[36], v[37], v[38], v[39]);
    }

    static String stateText() {
        return "goreModels=" + (wanted ? 1 : 0) + ";goreSkeletons=" + bones.size() + ";goreBuilt=" + built.size();
    }
}
