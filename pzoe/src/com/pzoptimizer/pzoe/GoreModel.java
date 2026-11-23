package com.pzoptimizer.pzoe;

import com.pzoptimizer.ShaderPatchHost;
import com.pzoptimizer.pzoe.gore.GoreAtlas;
import com.pzoptimizer.pzoe.gore.GoreCore;
import com.pzoptimizer.pzoe.gore.GoreImpact;
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
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.SkinningData;

/**
 * PZOE Gore on bodies: wounds where the hits landed, missing limbs and heads, bodies cut in two and rot, drawn inside
 * the game's own character shaders (patched by PZOE_Gore.lua). Nothing is re-textured and nothing extra is drawn.
 *
 * Every rig can use bone-weighted wounds and cuts. Nearby stock bodies also reshape selected vertices around
 * validated mesh-space joints to form head injuries and stumps in the existing draw.
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
    private static final int HEAD_PIVOT = 40, STUMPS = 44, MAX_STUMPS = 5;
    private static final int BODY_FX = STUMPS + MAX_STUMPS * 8, FLOW = BODY_FX + 4, POINTS = FLOW + 4;
    private static final int SQUASH = POINTS + GoreState.MAX_WOUNDS * 4;
    private static final float[] ZERO = new float[SQUASH + 4];
    private static double worldHours;

    // Cut bits to the bone the cut starts at (it and everything under it go)
    private static final int[] LIMB_BIT = {GoreState.HEAD, GoreState.ARM_L_UP, GoreState.ARM_L_LOW, GoreState.ARM_R_UP,
        GoreState.ARM_R_LOW, GoreState.LEG_L_UP, GoreState.LEG_L_LOW, GoreState.LEG_R_UP, GoreState.LEG_R_LOW};
    private static final float[] STUMP_RADIUS = {0.036f, 0.044f, 0.032f, 0.044f, 0.032f, 0.053f, 0.038f, 0.053f, 0.038f};
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
        if (wanted) worldHours = GoreMap.hours();
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace(" ", "");
    }

    private static final class Bones {
        final Map<String, Integer> index = new HashMap<>();
        final long[] below;
        private float[][] pivots;

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

        float[] pivot(SkinningData sd, int bone) {
            if (bone < 0 || bone >= below.length || bone >= sd.boneOffset.size()) return null;
            if (pivots == null) pivots = new float[below.length][];
            float[] result = pivots[bone];
            if (result == null) {
                var offset = sd.boneOffset.get(bone);
                var inverse = offset == null ? null : org.lwjgl.util.vector.Matrix4f.invert(offset, null);
                // The X model importer stores bone offsets in row-vector order.
                result = inverse == null ? new float[0] : new float[] {inverse.m03, inverse.m13, inverse.m23};
                pivots[bone] = result;
            }
            return result.length == 3 && Float.isFinite(result[0]) && Float.isFinite(result[1]) && Float.isFinite(result[2])
                ? result : null;
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
        int version = Integer.MIN_VALUE, quality, flowStage, squash, effects;
        final Map<SkinningData, float[][]> data = new IdentityHashMap<>();
    }

    private static final Map<GoreState, Built> built = new java.util.WeakHashMap<>();
    private static final Map<ModelMesh, Boolean> stockBodies = new IdentityHashMap<>();

    static boolean stockBody(ModelMesh mesh) {
        if (mesh == null) return false;
        return stockBodies.computeIfAbsent(mesh, m -> {
            String name = m.getPath().getPath().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
            return name.equals("skinned/malebody") || name.equals("skinned/femalebody")
                || name.endsWith("/models_x/skinned/malebody.x") || name.endsWith("/models_x/skinned/femalebody.x");
        });
    }

    private static boolean nearBody(Object owner) {
        if (!(owner instanceof zombie.characters.IsoGameCharacter c)) return true;
        for (zombie.characters.IsoPlayer p : zombie.characters.IsoPlayer.players) {
            if (p == null) continue;
            float dx = p.getX() - c.getX(), dy = p.getY() - c.getY();
            if (dx * dx + dy * dy < 18f * 18f) return true;
        }
        return false;
    }

    private static float[] build(GoreState g, Bones b, SkinningData sd, int variant, int flowStage) {
        float[] d = new float[ZERO.length];
        boolean compatible = variant != 0, geometry = variant == 2;
        int max = quality <= 1 ? 4 : GoreState.MAX_WOUNDS, w = 0, used = 0;
        for (int pass = 0; pass < Math.min(g.count, max); pass++) {
            int i = pass;
            if (g.count > max) {
                float priority = -1f;
                for (int j = 0; j < g.count; j++) {
                    if ((used & (1 << j)) != 0) continue;
                    float score = g.size[j] + (g.part[j] == 8 ? 0.45f : 0f) + (g.kind[j] == GoreState.BANDAGE ? 2f : 0f);
                    if (score <= priority) continue;
                    i = j;
                    priority = score;
                }
                used |= 1 << i;
            }
            int p = g.part[i];
            String name = GoreImpact.boneName(p);
            if (name == null) continue;
            int bone = b.bone(name);
            int o = w * 4;
            // A piece that doesn't carry that bone (shoes for an arm wound) keeps the slot empty
            long point = g.point(i);
            d[o] = bone < 0 ? 0f : point == 0L ? bone + 1 : -bone - 1;
            if (bone >= 0 && point != 0L) {
                int at = POINTS + w * 4;
                d[at] = GoreImpact.x(point);
                d[at + 1] = GoreImpact.y(point);
                d[at + 2] = GoreImpact.z(point);
                d[at + 3] = Math.min(0.094f, (g.kind[i] == GoreState.BUCKSHOT ? 0.036f : 0.014f) + g.size[i] * 0.028f);
                d[BODY_FX + 3] = 1f;
            }
            float turn = g.around[i] * 4f;
            float seed = (float) ((g.around[i] * 0.618 + g.along[i] * 0.377 + p * 0.131) % 1.0);
            d[o + 1] = seed + (g.directed[i] ? Math.min(3, Math.max(0, Math.round(turn))) + 1f : 0f);
            d[o + 2] = g.kind[i];
            d[o + 3] = g.size[i];
            w++;
        }
        long bits = 0;
        int head = b.bone("Bip01_Head");
        float[] pivot = GoreCore.dismemberLook && geometry && (g.cut != 0 || g.crush || g.headStyle != 0) ? b.pivot(sd, head) : null;
        // The two stock meshes have the same unit scale and a head joint at y=0.825.
        boolean shapes = pivot != null && pivot[1] > 0.78f && pivot[1] < 0.88f
            && Math.abs(pivot[0]) < 0.06f && Math.abs(pivot[2]) < 0.12f;
        boolean removedHead = g.crush || (g.headStyle == GoreState.HEAD_CRUSHED && !shapes);
        int cut = GoreCore.dismemberLook ? g.cut | (removedHead ? GoreState.HEAD : 0) : 0;
        int slice = GoreCore.dismemberLook ? g.slice : 0;
        for (int k = 0; k < LIMB_BIT.length; k++) {
            if ((cut & LIMB_BIT[k]) != 0) bits |= b.under(LIMB_BONE[k]);
        }
        if (slice >= 1 && slice <= 3 && compatible) d[BODY_FX] = slice + (geometry ? 4f : 0f);
        else if (slice != 0) bits |= b.under("Bip01_Spine1");
        if (compatible && GoreFx.decayOn) d[BODY_FX + 1] = switch (GoreState.decayStage(g.decay)) {
            case 1 -> 0.06f;
            case 2 -> 0.17f;
            case 3 -> 0.12f;
            case 4 -> 0.035f;
            default -> 0f;
        };
        if (flowStage > 0) flow(d, g, b, sd, flowStage);
        d[32] = w;
        d[33] = GoreFx.decayOn ? g.decay : 0f;
        d[35] = bits != 0 ? 1f : 0f;
        d[36] = (float) (bits & 0xFFFFF);
        d[37] = (float) ((bits >>> 20) & 0xFFFFF);
        d[38] = (float) ((bits >>> 40) & 0xFFFFF);
        if (shapes) {
            if ((cut & GoreState.HEAD) == 0 && g.headStyle != 0) {
                System.arraycopy(pivot, 0, d, HEAD_PIVOT, 3);
                d[HEAD_PIVOT + 3] = head + 1;
                d[34] = g.headStyle * 4 + g.headSide;
            }
            d[39] = stumps(d, b, sd, cut, bits);
        }
        return d;
    }

    private static void flow(float[] data, GoreState state, Bones b, SkinningData sd, int stage) {
        long point = state.flowPoint;
        if (point != 0L) {
            data[FLOW] = GoreImpact.x(point);
            data[FLOW + 1] = GoreImpact.y(point);
            data[FLOW + 2] = GoreImpact.z(point);
        } else {
            String name = GoreImpact.boneName(state.flowPart);
            float[] pivot = name == null ? null : b.pivot(sd, b.bone(name));
            if (pivot == null || pivot[1] < 0.025f || pivot[1] > 1.45f) return;
            data[FLOW] = pivot[0];
            data[FLOW + 1] = pivot[1] + 0.042f;
            data[FLOW + 2] = pivot[2];
        }
        data[FLOW + 3] = state.flowSide;
        data[BODY_FX + 2] = state.flowPower * stage / 20f;
    }

    private static boolean calibrated(SkinningData sd, Bones b) {
        float[] head = b.pivot(sd, b.bone("Bip01_Head"));
        return head != null && head[1] > 0.78f && head[1] < 0.88f
            && Math.abs(head[0]) < 0.06f && Math.abs(head[2]) < 0.12f;
    }

    private static int variant(ModelInstance mi, SkinningData sd, Bones b, GoreState g, Object owner) {
        if (g.cut == 0 && g.headStyle == 0 && !g.crush && g.slice == 0 && g.decay <= 0f) return 0;
        if (stockBody(mi.model.mesh) && calibrated(sd, b)) {
            return nearBody(owner) ? 2 : 1;
        }
        if (g.slice == 0 && g.decay <= 0f) return 0;
        for (ModelInstance parent = mi.parent; parent != null; parent = parent.parent) {
            if (parent.model != null && stockBody(parent.model.mesh) && parent.model.tag instanceof SkinningData root
                && calibrated(root, bonesOf(root))) return 1;
        }
        return 0;
    }

    static String detachedBone(int part) {
        int index = Integer.numberOfTrailingZeros(part);
        return index < LIMB_BONE.length ? LIMB_BONE[index] : null;
    }

    private static long detachedBones(Bones b, int root, int omit) {
        long keep = 0L;
        for (int i = 0; i < LIMB_BIT.length; i++) {
            if (root == LIMB_BIT[i]) keep = b.under(LIMB_BONE[i]);
        }
        if (omit != 0) {
            for (int i = 0; i < LIMB_BIT.length; i++) {
                if (omit == LIMB_BIT[i]) keep &= ~b.under(LIMB_BONE[i]);
            }
        }
        return keep;
    }

    private static final float[] HIDE_DETACHED = detached(0L);

    private static float[] detached(long keep) {
        float[] data = new float[ZERO.length];
        data[BODY_FX] = -1f;
        data[35] = 1f;
        data[36] = keep & 0xFFFFFL;
        data[37] = (keep >>> 20) & 0xFFFFFL;
        data[38] = (keep >>> 40) & 0xFFFFFL;
        return data;
    }

    private static int stumps(float[] d, Bones b, SkinningData sd, int cut, long bits) {
        int used = 0;
        for (int i = 0; i < LIMB_BIT.length && used < MAX_STUMPS; i++) {
            if ((cut & LIMB_BIT[i]) == 0) continue;
            int bone = b.bone(LIMB_BONE[i]);
            if (bone < 0) continue;
            int parent = sd.getParentBoneIdx(bone);
            if (parent < 0 || (parent < 60 && (bits & (1L << parent)) != 0)) continue;
            float[] at = b.pivot(sd, bone), from = b.pivot(sd, parent);
            if (at == null || from == null) continue;
            float x = at[0] - from[0], y = at[1] - from[1], z = at[2] - from[2];
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len < 0.012f || len > 0.29f) continue;
            int o = STUMPS + used++ * 8;
            System.arraycopy(at, 0, d, o, 3);
            d[o + 3] = bone + 1;
            d[o + 4] = x / len;
            d[o + 5] = y / len;
            d[o + 6] = z / len;
            d[o + 7] = STUMP_RADIUS[i];
        }
        return used;
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
            GoreState corpse = GoreAtlas.current();
            GoreState g = corpse == null ? GoreState.of(owner) : corpse;
            if (g != null && g.detachedRoot != 0 && mi.model != null) {
                if (mi.model.isStatic) {
                    SkinningData sd = mi.parent != null && mi.parent.model != null && mi.parent.model.tag instanceof SkinningData root ? root : null;
                    long keep = sd == null ? 0L : detachedBones(bonesOf(sd), g.detachedRoot, g.detachedOmit);
                    d = mi.parentBoneName != null && mi.parentBone >= 0 && mi.parentBone < 60
                        && (keep & (1L << mi.parentBone)) != 0 ? ZERO : HIDE_DETACHED;
                } else if (mi.model.tag instanceof SkinningData sd) {
                    Built bt = built.computeIfAbsent(g, unused -> new Built());
                    float[][] cache = bt.data.computeIfAbsent(sd, unused -> new float[3][]);
                    if (cache[0] == null) cache[0] = detached(detachedBones(bonesOf(sd), g.detachedRoot, g.detachedOmit));
                    d = cache[0];
                } else d = HIDE_DETACHED;
            } else if (g != null && mi.model != null && mi.model.tag instanceof SkinningData sd) {
                Built bt = built.get(g);
                if (bt == null) built.put(g, bt = new Built());
                int stage = !GoreCore.runoffLook ? 0 : corpse == null ? g.flowStage(worldHours) : g.corpseFlowStage;
                int effects = (GoreCore.dismemberLook ? 1 : 0) | (GoreFx.decayOn ? 2 : 0);
                int squash = corpse == null && g.squashPart >= 0 ? squashStep(g) : 0;
                if (bt.version != g.version || bt.quality != quality || bt.flowStage != stage || bt.squash != squash || bt.effects != effects) {
                    bt.version = g.version;
                    bt.quality = quality;
                    bt.flowStage = stage;
                    bt.squash = squash;
                    bt.effects = effects;
                    bt.data.clear();
                }
                Bones b = bonesOf(sd);
                int variant = variant(mi, sd, b, g, corpse == null ? owner : null);
                float[][] cache = bt.data.get(sd);
                if (cache == null) {
                    cache = new float[3][];
                    bt.data.put(sd, cache);
                }
                d = cache[variant];
                if (d == null) {
                    cache[variant] = d = build(g, b, sd, variant, stage);
                    if (squash != 0) addSquash(d, sd, b, g.squashPart, squash);
                }
                g.lastSeen = com.pzoptimizer.pzoe.gore.GoreCore.clock();
            }
        } catch (Throwable ignored) {
            d = ZERO;
        }
        data.properties.SetFloatArray(DATA, d);
    }

    private static int squashStep(GoreState g) {
        int step = 6 - Math.max(0, (int) ((com.pzoptimizer.pzoe.gore.GoreCore.clock() - g.squashAt) * 35f));
        if (step > 0 && GoreFx.squashOn) return step;
        g.squashPart = -1;
        return 0;
    }

    private static void addSquash(float[] data, SkinningData sd, Bones b, int part, int step) {
        String name = GoreImpact.boneName(part);
        float[] pivot = name == null ? null : b.pivot(sd, b.bone(name));
        if (pivot == null || pivot[1] < 0.025f || pivot[1] > 1.15f) return;
        System.arraycopy(pivot, 0, data, SQUASH, 3);
        data[SQUASH + 3] = 0.17f * step * step / 36f;
    }

    // Render thread
    private static final Map<Integer, int[]> locs = new HashMap<>();
    private static final Map<Integer, float[]> sent = new HashMap<>();
    private static long locsClearedNs;
    private static final FloatBuffer buf = ByteBuffer.allocateDirect(MAX_STUMPS * 8 * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();

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
                GL20.glGetUniformLocation(prog, "PZOGoreCut"), GL20.glGetUniformLocation(prog, VEH),
                GL20.glGetUniformLocation(prog, "PZOHeadPivot"), GL20.glGetUniformLocation(prog, "PZOStump"),
                GL20.glGetUniformLocation(prog, "PZOBodyFx"), GL20.glGetUniformLocation(prog, "PZOFlowOrigin"),
                GL20.glGetUniformLocation(prog, "PZOImpact"), GL20.glGetUniformLocation(prog, "PZOSquash")};
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
        if (p != null && p.GetValue() instanceof float[] f && f.length == ZERO.length) v = f;
        float[] prior = sent.get(prog);
        if (prior == v) return;
        sent.put(prog, v);
        if (l[0] >= 0) {
            buf.clear();
            buf.put(v, 0, 32).flip();
            GL20.glUniform4fv(l[0], buf);
        }
        GL20.glUniform4f(l[1], v[32], v[33], v[34], v[35]);
        if (l[2] >= 0) GL20.glUniform4f(l[2], v[36], v[37], v[38], v[39]);
        if (l[4] >= 0 && v[34] > 0f) GL20.glUniform4f(l[4], v[40], v[41], v[42], v[43]);
        if (l[5] >= 0 && v[39] > 0f) {
            buf.clear();
            buf.put(v, STUMPS, (int) v[39] * 8).flip();
            GL20.glUniform4fv(l[5], buf);
        }
        if (l[6] >= 0 && (prior == null || v[BODY_FX] != prior[BODY_FX] || v[BODY_FX + 1] != prior[BODY_FX + 1]
            || v[BODY_FX + 2] != prior[BODY_FX + 2] || v[BODY_FX + 3] != prior[BODY_FX + 3])) {
            GL20.glUniform4f(l[6], v[BODY_FX], v[BODY_FX + 1], v[BODY_FX + 2], v[BODY_FX + 3]);
        }
        if (l[7] >= 0 && v[BODY_FX + 2] > 0f) GL20.glUniform4f(l[7], v[FLOW], v[FLOW + 1], v[FLOW + 2], v[FLOW + 3]);
        if (l[9] >= 0 && (v[SQUASH + 3] > 0f || prior == null || prior[SQUASH + 3] > 0f)) {
            GL20.glUniform4f(l[9], v[SQUASH], v[SQUASH + 1], v[SQUASH + 2], v[SQUASH + 3]);
        }
        if (l[8] >= 0 && v[BODY_FX + 3] > 0f) {
            buf.clear();
            buf.put(v, POINTS, (int) v[32] * 4).flip();
            GL20.glUniform4fv(l[8], buf);
        }
    }

    static String stateText() {
        return "goreModels=" + (wanted ? 1 : 0) + ";goreSkeletons=" + bones.size() + ";goreBuilt=" + built.size();
    }
}
