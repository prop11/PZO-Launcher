package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Three per-character calls rewritten without their allocations (JVM_HotPathTrims). Same answers.
 *
 * Stats.get: getOrDefault boxes the stat's default into a new Float on every call, hit or miss, and every character
 * reads its stats many times a frame. A plain get with the default only on a miss.
 *
 * AnimationPlayer.isBoneReparented: a captured lambda through PZArrayUtil.contains per call. A plain loop.
 *
 * StateMachine.getMinimumSimulationLevel: a for-each over the substates, so an Iterator per zombie per frame from the
 * update scheduler. An indexed loop.
 *
 * Each is a check at the top of the stock method, so turning the option off runs stock again.
 */
public final class HotPathTrims {

    public static volatile boolean active = true;

    private HotPathTrims() {}

    public static boolean on() {
        return active;
    }

    public static float stat(Map<?, ?> stats, Object stat) {
        Object v = stats.get(stat);
        return v != null ? (Float) v : ((zombie.characters.CharacterStat) stat).getDefaultValue();
    }

    public static boolean reparented(ArrayList<?> bindings, int boneIdx) {
        for (int i = 0, n = bindings.size(); i < n; i++) {
            if (((zombie.core.skinnedmodel.animation.AnimationBoneBindingPair) bindings.get(i)).getBoneIdxA() == boneIdx) return true;
        }
        return false;
    }

    public static zombie.UpdateSchedulerSimulationLevel minLevel(Object current, List<?> subStates) {
        if (current == null) return zombie.UpdateSchedulerSimulationLevel.minimum();
        zombie.UpdateSchedulerSimulationLevel level = ((zombie.ai.State) current).getMinimumSimulationLevel();
        for (int i = 0, n = subStates.size(); i < n; i++) {
            zombie.ai.StateMachine.SubstateSlot slot = (zombie.ai.StateMachine.SubstateSlot) subStates.get(i);
            if (!slot.isEmpty()) level = level.max(slot.getState().getMinimumSimulationLevel());
        }
        return level;
    }

    private static boolean already(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "com/pzoptimizer/HotPathTrims".equals(mi.owner)) return true;
        }
        return false;
    }

    // "if (HotPathTrims.on()) return <body>;" in front of the stock code
    private static void prefix(MethodNode m, InsnList body, int returnOp) {
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/HotPathTrims", "on", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(body);
        pre.add(new InsnNode(returnOp));
        pre.add(stock);
        m.instructions.insert(pre);
    }

    static boolean patchStats(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "get", "(Lzombie/characters/CharacterStat;)F");
        if (m == null || already(m)) return false;
        InsnList b = new InsnList();
        b.add(new VarInsnNode(Opcodes.ALOAD, 0));
        b.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "stats", "Ljava/util/Map;"));
        b.add(new VarInsnNode(Opcodes.ALOAD, 1));
        b.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/HotPathTrims", "stat", "(Ljava/util/Map;Ljava/lang/Object;)F", false));
        prefix(m, b, Opcodes.FRETURN);
        return true;
    }

    static boolean patchAnimationPlayer(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "isBoneReparented", "(I)Z");
        if (m == null || already(m)) return false;
        InsnList b = new InsnList();
        b.add(new VarInsnNode(Opcodes.ALOAD, 0));
        b.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "reparentedBoneBindings", "Ljava/util/ArrayList;"));
        b.add(new VarInsnNode(Opcodes.ILOAD, 1));
        b.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/HotPathTrims", "reparented", "(Ljava/util/ArrayList;I)Z", false));
        prefix(m, b, Opcodes.IRETURN);
        return true;
    }

    static boolean patchStateMachine(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "getMinimumSimulationLevel", "()Lzombie/UpdateSchedulerSimulationLevel;");
        if (m == null || already(m)) return false;
        InsnList b = new InsnList();
        b.add(new VarInsnNode(Opcodes.ALOAD, 0));
        b.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "currentState", "Lzombie/ai/State;"));
        b.add(new VarInsnNode(Opcodes.ALOAD, 0));
        b.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "subStates", "Ljava/util/List;"));
        b.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/HotPathTrims", "minLevel",
            "(Ljava/lang/Object;Ljava/util/List;)Lzombie/UpdateSchedulerSimulationLevel;", false));
        prefix(m, b, Opcodes.ARETURN);
        return true;
    }
}
