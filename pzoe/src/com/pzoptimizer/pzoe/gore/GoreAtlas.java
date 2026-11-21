package com.pzoptimizer.pzoe.gore;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.Type;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.IdentityHashMap;
import java.util.Map;

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
    private static final String SELF = "com/pzoptimizer/pzoe/gore/GoreAtlas";

    private static final Map<Object, GoreState> byParams = new IdentityHashMap<>();
    private static GoreState drawing;
    // Corpses drawn lately, for flies, rot and pools near the player (GoreFx)
    public static final Map<zombie.iso.objects.IsoDeadBody, Boolean> seen = new java.util.WeakHashMap<>();

    private GoreAtlas() {}

    /** The record of whatever the atlas is drawing right now, or null. */
    public static GoreState current() {
        return drawing;
    }

    /** Start of BodyParams.init(IsoDeadBody) / init(IsoZombie) / the others (body null). */
    public static void from(Object params, Object body) {
        GoreState g = null;
        if (body instanceof zombie.iso.objects.IsoDeadBody b) {
            g = GoreCore.corpseRecord(b);
            seen.put(b, Boolean.TRUE);
            if (g != null) {
                float d = decayOf(b);
                if (Math.min(4, (int) (d * 5f)) != Math.min(4, (int) (g.decay * 5f))) g.version++;
                g.decay = d;
            }
        } else if (body != null) g = GoreState.of(body);
        if (g == null || (g.count == 0 && g.cut == 0 && g.slice == 0 && !g.crush && g.decay < 0.2f)) byParams.remove(params);
        else byParams.put(params, g);
    }

    /** Start of BodyParams.init(BodyParams src): copies carry the record. */
    public static void copy(Object dst, Object src) {
        GoreState g = byParams.get(src);
        if (g == null) byParams.remove(dst);
        else byParams.put(dst, g);
    }

    /** Return of DeadBodyAtlas.getBodyKey(params). */
    public static String key(String k, Object params) {
        GoreState g = byParams.get(params);
        return g == null || k == null ? k : k + g.signature();
    }

    /** Around RenderJob.renderMain: params = the job's own BodyParams. */
    public static void begin(Object params) {
        drawing = byParams.get(params);
    }

    public static void end() {
        drawing = null;
    }

    // 0 at death, 1 after a week; only while Decay is on (GoreFx sets it)
    public static volatile boolean decayOn;

    static float decayOf(zombie.iso.objects.IsoDeadBody b) {
        if (!decayOn) return 0f;
        try {
            float dt = b.getDeathTime();
            if (dt < 0f) return 0f;
            double h = zombie.GameTime.getInstance().getWorldAgeHours() - dt;
            return (float) Math.max(0.0, Math.min(1.0, h / 168.0));
        } catch (Throwable t) {
            return 0f;
        }
    }

    // Bytecode

    public static boolean patchParams(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            if (!m.name.equals("init")) continue;
            Type[] args = Type.getArgumentTypes(m.desc);
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
            if (args.length == 1 && args[0].getInternalName().equals(PARAMS)) {
                pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "copy", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            } else {
                boolean body = args.length == 1 && args[0].getSort() == Type.OBJECT
                    && (args[0].getInternalName().equals("zombie/iso/objects/IsoDeadBody") || args[0].getInternalName().equals("zombie/characters/IsoZombie"));
                if (body) pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                else pre.add(new InsnNode(Opcodes.ACONST_NULL));
                pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "from", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            }
            m.instructions.insert(pre);
            n++;
        }
        return n >= 3;
    }

    public static boolean patchAtlas(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "getBodyKey", "(L" + PARAMS + ";)Ljava/lang/String;");
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.ARETURN) continue;
            InsnList l = new InsnList();
            l.add(new VarInsnNode(Opcodes.ALOAD, 1));
            l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "key", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;", false));
            m.instructions.insertBefore(insn, l);
        }
        return true;
    }

    public static boolean patchJob(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderMain", "()Z");
        if (m == null) return false;
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
