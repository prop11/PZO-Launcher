package com.pzoptimizer.pzoe.gore;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.pzoe.GoreDetached;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.Type;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.IdentityHashMap;
import java.util.Map;
import zombie.core.skinnedmodel.DeadBodyAtlas;
import zombie.iso.objects.IsoDeadBody;

/**
 * Corpses are drawn once into the game's corpse atlas (DeadBodyAtlas) and shown as a picture after that, keyed by how
 * the body looks. So a corpse's wounds, missing limbs and rot have to reach that picture: each BodyParams the game
 * fills from a corpse or zombie remembers that body's gore record, the atlas key gets the record's signature (a gory
 * body gets its own picture instead of sharing one), and while a render job draws, its record is the one the model
 * hooks use. Main thread only, no GL.
 */
public final class GoreAtlas {
    public static final String ATLAS = "zombie/core/skinnedmodel/DeadBodyAtlas", PARAMS = ATLAS + "$BodyParams",
        JOB = ATLAS + "$RenderJob";
    private static final String SELF = "com/pzoptimizer/pzoe/gore/GoreAtlas", PARTS = "com/pzoptimizer/pzoe/GoreDetached";

    private static final Map<Object, GoreState> byParams = new IdentityHashMap<>();
    private static GoreState drawing;
    // Corpses drawn lately, for flies, rot and pools near the player (GoreFx)
    public static final Map<zombie.iso.objects.IsoDeadBody, Boolean> seen = new java.util.WeakHashMap<>();

    private GoreAtlas() {}

    /** The record of whatever the atlas is drawing right now, or null. */
    public static GoreState current() {
        return drawing;
    }

    /** Record the body associated with the atlas's reusable parameter object. */
    public static void from(Object params, Object body) {
        GoreState g = null;
        if (body instanceof zombie.iso.objects.IsoDeadBody b) {
            seen.put(b, Boolean.TRUE);
            if (!GoreCore.woundsLook) {
                byParams.remove(params);
                return;
            }
            g = GoreCore.corpseRecord(b);
            double hours = zombie.GameTime.getInstance().getWorldAgeHours();
            float decay = decayOf(b, hours);
            if (g == null && GoreState.decayStage(decay) > 0) g = GoreState.make(b);
            if (g != null && b.getAtlasTexture() == null) updateStages(g, decay, GoreCore.runoffLook ? hours : Double.POSITIVE_INFINITY);
        } else if (body != null && GoreCore.woundsLook) g = GoreState.of(body);
        if (g == null || (g.count == 0 && g.cut == 0 && g.slice == 0 && !g.crush && g.headStyle == 0 && g.decay < 0.2f && g.corpseFlowStage == 0)) byParams.remove(params);
        else byParams.put(params, g);
    }

    /** Keep the record with the job's own parameter copy. */
    public static void copy(Object dst, Object src) {
        GoreState g = byParams.get(src);
        if (g == null) byParams.remove(dst);
        else byParams.put(dst, g);
    }

    /** Return of DeadBodyAtlas.getBodyKey(params). */
    public static String key(String k, Object params) {
        String detached = GoreDetached.key(params, k);
        if (detached != k) return detached;
        GoreState g = byParams.get(params);
        return g == null || k == null || !GoreCore.woundsLook ? k : k + g.signature() + 'v' + look();
    }

    /** Around RenderJob.renderMain: params = the job's own BodyParams. */
    public static void begin(Object params) {
        drawing = GoreDetached.record(params);
        if (drawing == null) drawing = byParams.get(params);
    }

    public static void end() {
        drawing = null;
    }

    // 0 at death, 1 after a week; only while Decay is on (GoreFx sets it)
    public static volatile boolean decayOn;

    public static int look() {
        return (GoreCore.woundsLook ? 1 : 0) | (GoreCore.dismemberLook ? 2 : 0)
            | (GoreCore.runoffLook ? 4 : 0) | (decayOn ? 8 : 0);
    }

    public static float decayOf(zombie.iso.objects.IsoDeadBody b, double hours) {
        if (!decayOn || b.getDeathTime() < 0f) return 0f;
        return (float) Math.max(0.0, Math.min(1.0, (hours - b.getDeathTime()) / 168.0));
    }

    public static boolean updateStages(GoreState g, float decay, double hours) {
        int step = GoreState.decayStage(decay);
        float stage = step == 4 ? 1f : step * 0.2f;
        int flow = g.flowStage(hours);
        if (g.decay == stage && g.corpseFlowStage == flow) return false;
        g.decay = stage;
        g.corpseFlowStage = flow;
        g.version++;
        return true;
    }

    public static void refreshTexture(IsoDeadBody body) {
        // Atlas entries can be shared. Drop this body's reference so vanilla requests its new key.
        if (AtlasTexture.HANDLE != null) AtlasTexture.HANDLE.set(body, null);
    }

    private static final class AtlasTexture {
        private static final VarHandle HANDLE = find();

        private static VarHandle find() {
            try {
                return MethodHandles.privateLookupIn(IsoDeadBody.class, MethodHandles.lookup())
                    .findVarHandle(IsoDeadBody.class, "atlasTex", DeadBodyAtlas.BodyTexture.class);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
    }

    // Bytecode

    // BodyParams can load during the outer atlas's transformation. Hook the atlas call sites directly.
    private static int patchParamCalls(ClassNode node, boolean copy) {
        int count = 0;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(PARAMS) || !call.name.equals("init")) continue;
                Type[] args = Type.getArgumentTypes(call.desc);
                if (args.length == 1 && args[0].getSort() == Type.OBJECT) {
                    if (copy != args[0].getInternalName().equals(PARAMS)) continue;
                    m.instructions.insertBefore(call, new InsnNode(Opcodes.DUP2));
                    m.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, copy ? "copy" : "from",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                    count++;
                } else if (!copy && args.length == 5) {
                    InsnList clear = new InsnList();
                    clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    clear.add(new FieldInsnNode(Opcodes.GETFIELD, ATLAS, "bodyParams", "L" + PARAMS + ";"));
                    clear.add(new InsnNode(Opcodes.ACONST_NULL));
                    clear.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "from", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                    m.instructions.insert(call, clear);
                    count++;
                }
            }
        }
        return count;
    }

    public static boolean patchAtlas(ClassNode node) {
        MethodNode reset = AsmSupport.findMethod(node, "Reset", "()V");
        if (reset != null) reset.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, PARTS, "reset", "()V", false));
        MethodNode m = AsmSupport.findMethod(node, "getBodyKey", "(L" + PARAMS + ";)Ljava/lang/String;");
        if (m == null || patchParamCalls(node, false) == 0) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.ARETURN) continue;
            InsnList l = new InsnList();
            l.add(new VarInsnNode(Opcodes.ALOAD, 1));
            l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "key", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;", false));
            m.instructions.insertBefore(insn, l);
        }
        return true;
    }

    public static boolean patchDetachedBounds(ClassNode node) {
        MethodNode bounds = AsmSupport.findMethod(node, "calcModelBounds", "([F)V");
        MethodNode size = AsmSupport.findMethod(node, "calcEntrySize", "()V");
        if (bounds == null || size == null) return false;
        InsnList early = new InsnList();
        LabelNode stock = new LabelNode();
        early.add(new VarInsnNode(Opcodes.ALOAD, 0));
        early.add(new VarInsnNode(Opcodes.ALOAD, 0));
        early.add(new FieldInsnNode(Opcodes.GETFIELD, JOB, "body", "L" + PARAMS + ";"));
        early.add(new VarInsnNode(Opcodes.ALOAD, 1));
        early.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PARTS, "bounds", "(Ljava/lang/Object;L" + PARAMS + ";[F)Z", false));
        early.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        early.add(new InsnNode(Opcodes.RETURN));
        early.add(stock);
        bounds.instructions.insert(early);
        for (AbstractInsnNode insn : size.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList fixed = new InsnList();
            for (String field : new String[]{"entryW", "entryH"}) {
                fixed.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fixed.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fixed.add(new FieldInsnNode(Opcodes.GETFIELD, JOB, "body", "L" + PARAMS + ";"));
                fixed.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fixed.add(new FieldInsnNode(Opcodes.GETFIELD, JOB, field, "I"));
                fixed.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PARTS, "dimension", "(L" + PARAMS + ";I)I", false));
                fixed.add(new FieldInsnNode(Opcodes.PUTFIELD, JOB, field, "I"));
            }
            size.instructions.insertBefore(insn, fixed);
        }
        return true;
    }

    public static boolean patchJob(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderMain", "()Z");
        if (m == null || patchParamCalls(node, true) == 0) return false;
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new FieldInsnNode(Opcodes.GETFIELD, JOB, "body", "L" + PARAMS + ";"));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "begin", "(Ljava/lang/Object;)V", false));
        m.instructions.insert(pre);
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.IRETURN) {
                m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "end", "()V", false));
            }
        }
        return true;
    }
}
