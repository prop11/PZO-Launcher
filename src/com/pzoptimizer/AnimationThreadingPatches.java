package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.Type;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.FieldNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.LdcInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.TypeInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.HashMap;
import java.util.Map;

/**
 * Class rewrites for BoneBatch and PerThreadScratch. Kept apart from those runtime classes on purpose: these run
 * inside the agent while AnimationPlayer, HelperFunctions or the scheduler is being defined, and this class refers
 * to no game type, so verifying or initialising it can never load one of the classes being rewritten.
 */
final class AnimationThreadingPatches {

    // Slot numbers are handed out at class-load time, on whatever thread loads the class
    private static final Map<String, Integer> slots = new HashMap<>();

    private AnimationThreadingPatches() {}


    private static final String AP = "zombie/core/skinnedmodel/animation/AnimationPlayer";
    private static final String ME = "com/pzoptimizer/BoneBatch";

    static boolean patchAnimationPlayer(ClassNode node) {
        MethodNode standard = AsmSupport.findMethod(node, "updateAnimation_StandardAnimation", "(F)V");
        MethodNode bones = AsmSupport.findMethod(node, "updateMultiTrackBoneTransformsInternal", "(F)V");
        if (standard == null || bones == null) return false;
        for (String[] m : new String[][] {{"updateLayerBlendWeightings", "()V"}, {"DoAngles", "(F)V"},
                {"calculateDeferredMovement", "()V"}, {"updateTwistBone", "()V"}, {"applyBoneReParenting", "()V"},
                {"updateModelTransforms", "()V"}, {"UpdateSkinTransforms", "()V"}}) {
            if (AsmSupport.findMethod(node, m[0], m[1]) == null) return false;
        }

        // pzo$bonesNoBlend(F)V: updateMultiTrackBoneTransformsInternal without its updateLayerBlendWeightings call,
        // which the game thread already made in pzo$deferMain
        MethodNode noBlend = new MethodNode(Opcodes.ACC_PRIVATE, "pzo$bonesNoBlend", "(F)V", null, null);
        bones.accept(noBlend);
        noBlend.name = "pzo$bonesNoBlend";
        noBlend.access = Opcodes.ACC_PRIVATE;
        int removed = 0;
        for (AbstractInsnNode insn = noBlend.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof MethodInsnNode mi && "updateLayerBlendWeightings".equals(mi.name) && AP.equals(mi.owner)) {
                AbstractInsnNode load = mi.getPrevious();
                while (load != null && load.getOpcode() < 0) load = load.getPrevious();
                if (load == null || load.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) load).var != 0) return false;
                noBlend.instructions.remove(load);
                noBlend.instructions.remove(mi);
                removed++;
            }
            insn = next;
        }
        if (removed != 1) return false;
        node.methods.add(noBlend);

        node.interfaces.add("com/pzoptimizer/BoneBatch$Deferrable");
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT, "pzo$queueSlot", "I", null, null));

        MethodNode getSlot = new MethodNode(Opcodes.ACC_PUBLIC, "pzo$queueSlot", "()I", null, null);
        getSlot.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getSlot.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, AP, "pzo$queueSlot", "I"));
        getSlot.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(getSlot);

        MethodNode setSlot = new MethodNode(Opcodes.ACC_PUBLIC, "pzo$setQueueSlot", "(I)V", null, null);
        setSlot.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setSlot.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        setSlot.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, AP, "pzo$queueSlot", "I"));
        setSlot.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(setSlot);

        MethodNode main = new MethodNode(Opcodes.ACC_PUBLIC, "pzo$deferMain", "(F)V", null, null);
        InsnList c = main.instructions;
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, AP, "updateLayerBlendWeightings", "()V", false));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new VarInsnNode(Opcodes.FLOAD, 1));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, AP, "DoAngles", "(F)V", false));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, AP, "calculateDeferredMovement", "()V", false));
        c.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(main);

        MethodNode work = new MethodNode(Opcodes.ACC_PUBLIC, "pzo$deferBones", "(F)V", null, null);
        c = work.instructions;
        LabelNode done = new LabelNode();
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, AP, "isReady", "()Z", false));
        c.add(new JumpInsnNode(Opcodes.IFEQ, done));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new VarInsnNode(Opcodes.FLOAD, 1));
        c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, AP, "pzo$bonesNoBlend", "(F)V", false));
        for (String name : new String[] {"updateTwistBone", "applyBoneReParenting", "updateModelTransforms"}) {
            c.add(new VarInsnNode(Opcodes.ALOAD, 0));
            c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, AP, name, "()V", false));
        }
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, AP, "UpdateSkinTransforms", "()V", false));
        c.add(done);
        c.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(work);

        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new VarInsnNode(Opcodes.FLOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ME, "tryDefer", "(Ljava/lang/Object;F)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(stock);
        standard.instructions.insert(pre);

        int cancels = 0;
        for (MethodNode m : node.methods) {
            boolean lifecycle = ("reset".equals(m.name) && "()V".equals(m.desc)) || ("onReleased".equals(m.name) && "()V".equals(m.desc))
                || ("setModel".equals(m.name) && m.desc.startsWith("(Lzombie/core/skinnedmodel/model/Model;)"));
            if (!lifecycle || (m.access & Opcodes.ACC_STATIC) != 0) continue;
            InsnList cancel = new InsnList();
            cancel.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cancel.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ME, "cancel", "(Ljava/lang/Object;)V", false));
            m.instructions.insert(cancel);
            cancels++;
        }
        if (cancels < 2) return false;

        return redirectScratch(node, AP + "$L_");
    }

    /** MovingObjectUpdateScheduler.postupdate: begin() first, flush() before every return. */
    static boolean patchScheduler(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "postupdate", "()V");
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, ME, "flush", "()V", false));
            }
        }
        m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, ME, "begin", "()V", false));
        return true;
    }

    private static synchronized int slotFor(String key) {
        Integer s = slots.get(key);
        if (s == null) {
            s = slots.size();
            if (s >= PerThreadScratch.MAX_SLOTS) return -1;
            slots.put(key, s);
        }
        return s;
    }

    /** Routes every GETSTATIC of an object field on one of the given holder-class prefixes through get(). */
    static boolean redirectScratch(ClassNode node, String holderPrefix) {
        int redirected = 0;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.GETSTATIC) continue;
                FieldInsnNode f = (FieldInsnNode) insn;
                if (!f.owner.startsWith(holderPrefix) || f.desc.charAt(0) != 'L') continue;
                int slot = slotFor(f.owner + "." + f.name);
                if (slot < 0) return false;
                InsnList after = new InsnList();
                after.add(new LdcInsnNode(slot));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/PerThreadScratch", "get",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;", false));
                after.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getType(f.desc).getInternalName()));
                m.instructions.insert(insn, after);
                redirected++;
            }
        }
        return redirected > 0;
    }

    /** HelperFunctions.getMatrix()/returnMatrix(Matrix4f): a worker uses its own stack instead of the locked one. */
    static boolean patchHelperFunctions(ClassNode node) {
        MethodNode get = AsmSupport.findMethod(node, "getMatrix", "()Lorg/lwjgl/util/vector/Matrix4f;");
        MethodNode ret = AsmSupport.findMethod(node, "returnMatrix", "(Lorg/lwjgl/util/vector/Matrix4f;)V");
        if (get == null || ret == null) return false;

        InsnList g = new InsnList();
        LabelNode stockGet = new LabelNode();
        g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/PerThreadScratch", "workerMatrix", "()Ljava/lang/Object;", false));
        g.add(new InsnNode(Opcodes.DUP));
        g.add(new JumpInsnNode(Opcodes.IFNULL, stockGet));
        g.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/lwjgl/util/vector/Matrix4f"));
        g.add(new InsnNode(Opcodes.ARETURN));
        g.add(stockGet);
        g.add(new InsnNode(Opcodes.POP));
        get.instructions.insert(g);

        InsnList r = new InsnList();
        LabelNode stockRet = new LabelNode();
        r.add(new VarInsnNode(Opcodes.ALOAD, 0));
        r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/PerThreadScratch", "returnWorkerMatrix", "(Ljava/lang/Object;)Z", false));
        r.add(new JumpInsnNode(Opcodes.IFEQ, stockRet));
        r.add(new InsnNode(Opcodes.RETURN));
        r.add(stockRet);
        ret.instructions.insert(r);
        return true;
    }
}
