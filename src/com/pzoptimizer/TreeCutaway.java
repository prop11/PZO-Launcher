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
import zombie.characters.IsoPlayer;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoTree;

/** Keeps the separate trunk and crown draws during fade-in and trims the visibility work for aiming cutaways. */
public final class TreeCutaway {
    private static final String TREE = "zombie/iso/objects/IsoTree";
    private static final String SELF = "com/pzoptimizer/TreeCutaway";
    private static final IsoDirections[] DIRECTIONS = IsoDirections.values();

    private TreeCutaway() {}

    public static int visibleNeighbours(IsoTree tree, int playerIndex, boolean treeVisible) {
        if (treeVisible) return 0;
        String name = tree.sprite.name;
        if (name == null || !name.contains("XL")) return 0;
        IsoPlayer player = IsoPlayer.getPlayer(playerIndex);
        if (player == null || !player.isAnyAimKeyDown()) return 0;
        IsoGridSquare square = tree.square;

        int count = 0;
        for (IsoDirections direction : DIRECTIONS) {
            IsoGridSquare neighbour = square.getAdjacentSquare(direction);
            if (neighbour != null && neighbour.lighting[playerIndex].bCanSee() && ++count == 4) break;
        }
        return count;
    }

    static boolean patchTree(ClassNode node) {
        MethodNode submit = AsmSupport.findMethod(node, "render", "(FFFLzombie/core/textures/ColorInfo;ZIZ)V");
        MethodNode render = AsmSupport.findMethod(node, "render", "(FFFLzombie/core/textures/ColorInfo;ZZLzombie/core/opengl/Shader;)V");
        if (submit == null || render == null || !keepSplitDuringRecovery(submit) || !patchVisibility(render)) return false;

        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) initializeFade(method);
        }

        return true;
    }

    private static boolean patchVisibility(MethodNode render) {
        for (AbstractInsnNode insn : render.instructions) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(TREE)
                || !call.name.equals("countObscuredSeenSquaresOriginal") || !call.desc.equals("(IZ)I")) continue;
            AbstractInsnNode debug = previousOpcode(call);
            if (debug == null || debug.getOpcode() != Opcodes.ICONST_0) continue;
            AbstractInsnNode add = nextOpcode(call);
            if (add == null || add.getOpcode() != Opcodes.IADD) continue;
            if (!(nextOpcode(add) instanceof VarInsnNode count) || count.getOpcode() != Opcodes.ISTORE) continue;
            // The value under the call arguments is the count's seed, 1 if the tree itself is visible.
            AbstractInsnNode playerIndex = previousOpcode(debug);
            if (!(playerIndex instanceof VarInsnNode player) || player.getOpcode() != Opcodes.ILOAD) continue;
            AbstractInsnNode tree = previousOpcode(playerIndex);
            if (!(tree instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0) continue;
            if (!(previousOpcode(receiver) instanceof VarInsnNode seed)
                || seed.getOpcode() != Opcodes.ILOAD || seed.var != count.var) continue;

            render.instructions.set(debug, new VarInsnNode(Opcodes.ILOAD, seed.var));
            render.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "visibleNeighbours",
                "(L" + TREE + ";IZ)I", false));
            return true;
        }
        return false;
    }

    private static boolean keepSplitDuringRecovery(MethodNode render) {
        for (AbstractInsnNode insn : render.instructions) {
            if (!(insn instanceof FieldInsnNode field) || !isCutawayAlpha(field, Opcodes.GETFIELD)) continue;
            AbstractInsnNode one = nextOpcode(field);
            if (one == null || one.getOpcode() != Opcodes.FCONST_1) continue;
            AbstractInsnNode compare = nextOpcode(one);
            if (compare == null || compare.getOpcode() != Opcodes.FCMPL) continue;
            if (!(nextOpcode(compare) instanceof JumpInsnNode alphaGate) || alphaGate.getOpcode() != Opcodes.IFEQ) continue;
            if (!(nextOpcode(alphaGate) instanceof FieldInsnNode jumbos) || jumbos.getOpcode() != Opcodes.GETSTATIC
                || !jumbos.owner.equals("zombie/iso/IsoTreeJumbo") || !jumbos.name.equals("Jumbos")) continue;
            AbstractInsnNode loadTree = previousOpcode(field);
            if (!(loadTree instanceof VarInsnNode tree) || tree.getOpcode() != Opcodes.ALOAD || tree.var != 0) continue;
            if (!(previousOpcode(loadTree) instanceof JumpInsnNode gate) || gate.getOpcode() != Opcodes.IFEQ
                || gate.label != alphaGate.label) continue;
            if (!(previousOpcode(gate) instanceof VarInsnNode transparent) || transparent.getOpcode() != Opcodes.ILOAD
                || transparent.var != 7) continue;

            // The crown can still be below its target after the game has stopped requesting a cutaway.
            LabelNode drawSplit = new LabelNode();
            InsnList recovery = new InsnList();
            recovery.add(new VarInsnNode(Opcodes.ILOAD, transparent.var));
            recovery.add(new JumpInsnNode(Opcodes.IFNE, drawSplit));
            recovery.add(new VarInsnNode(Opcodes.ALOAD, 0));
            recovery.add(new FieldInsnNode(Opcodes.GETFIELD, TREE, "cutawayAlpha", "F"));
            recovery.add(new VarInsnNode(Opcodes.ALOAD, 0));
            recovery.add(new VarInsnNode(Opcodes.ILOAD, 6));
            recovery.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TREE, "getTargetAlpha", "(I)F", false));
            recovery.add(new InsnNode(Opcodes.FCMPG));
            recovery.add(new JumpInsnNode(Opcodes.IFGE, gate.label));
            recovery.add(drawSplit);
            render.instructions.insert(alphaGate, recovery);
            render.instructions.remove(gate);
            render.instructions.remove(transparent);
            return true;
        }
        return false;
    }

    private static void initializeFade(MethodNode constructor) {
        for (AbstractInsnNode insn : constructor.instructions) {
            if (insn instanceof FieldInsnNode field && isCutawayAlpha(field, Opcodes.PUTFIELD)) return;
        }
        // Match reset(), so a new tree does not look like it is already recovering from a cutaway.
        for (AbstractInsnNode insn : constructor.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList init = new InsnList();
            init.add(new VarInsnNode(Opcodes.ALOAD, 0));
            init.add(new InsnNode(Opcodes.FCONST_1));
            init.add(new FieldInsnNode(Opcodes.PUTFIELD, TREE, "cutawayAlpha", "F"));
            constructor.instructions.insertBefore(insn, init);
        }
    }

    private static boolean isCutawayAlpha(FieldInsnNode field, int opcode) {
        return field.getOpcode() == opcode && field.owner.equals(TREE) && field.name.equals("cutawayAlpha")
            && field.desc.equals("F");
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode insn) {
        do {
            insn = insn.getNext();
        } while (insn != null && insn.getOpcode() < 0);
        return insn;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode insn) {
        do {
            insn = insn.getPrevious();
        } while (insn != null && insn.getOpcode() < 0);
        return insn;
    }
}
