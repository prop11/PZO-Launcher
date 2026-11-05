package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bakes distant trees into the chunk texture (JVM_TreeBake, experimental).
 *
 * FBORenderCell.isTreeRenderedEveryFrame is plain "object instanceof IsoTree", so every tree on screen is drawn on its
 * own every frame, wind or not, near or far. The chunk bake already has a tree path behind it (bakes open an
 * FBORenderTrees batch, and checkTreeTranslucency re-bakes a tree's chunk when it starts or stops fading); it's just
 * never taken. Trees in chunks FoliageLod counts as far now take it. Near trees keep the stock per-frame path, since
 * those are the ones that fade, peek or sway around the player, and so do XL trees while the player drives, which stock
 * fades in the car's view cone out to 64 tiles. FoliageLod's near/far flips already re-bake the chunk.
 */
public final class TreeBake {

    public static volatile boolean active = false;
    public static final AtomicLong treesBaked = new AtomicLong();

    private static boolean wasInVehicle;

    private TreeBake() {}

    /** JVM_TreeBake: every loaded chunk re-bakes with the new answer. */
    public static void setActive(boolean value) {
        if (active == value) return;
        active = value;
        FoliageLod.invalidateAllLoaded();
    }

    /** Once per frame from FoliageLod.update: getting in or out of a car changes the XL tree answer. */
    static void frame(zombie.characters.IsoPlayer player) {
        if (!active || player == null) return;
        boolean in = player.getVehicle() != null;
        if (in != wasInVehicle) {
            wasInVehicle = in;
            FoliageLod.invalidateAllLoaded();
        }
    }

    /** Start of FBORenderCell.isTreeRenderedEveryFrame(object): true answers "not every frame", so the tree bakes. */
    public static boolean bakes(Object object) {
        if (!active || !(object instanceof zombie.iso.objects.IsoTree tree)) return false;
        zombie.iso.IsoGridSquare sq = tree.square;
        if (sq == null || sq.chunk == null || !FoliageLod.isFarChunk(sq.chunk)) return false;
        if (wasInVehicle && tree.sprite != null && tree.sprite.name != null && tree.sprite.name.contains("XL")) return false;
        treesBaked.incrementAndGet();
        return true;
    }

    static boolean patchRenderCell(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "isTreeRenderedEveryFrame", "(Lzombie/iso/IsoObject;)Z");
        if (m == null) return false;
        LabelNode stock = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/TreeBake", "bakes", "(Ljava/lang/Object;)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(new InsnNode(Opcodes.ICONST_0));
        pre.add(new InsnNode(Opcodes.IRETURN));
        pre.add(stock);
        m.instructions.insert(pre);
        return true;
    }
}
