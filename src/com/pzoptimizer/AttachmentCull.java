package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.IincInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.TypeInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;

/**
 * Settings screen "Distant zombie attachment culling" (Horde_CullDistantAttachments).
 *
 * Every frame ModelSlotRenderData.initModelInst collects a character's sub-models for drawing, including the models of
 * items worn on belts, backs and holsters (ModelSlot.attachedModels). For zombies farther than CULL_TILES from every
 * local player those models are left out of that frame's render data: a few pixels each, but a separate draw with its
 * own skinning per item across a horde. Nothing about the zombie or its items changes, and the next frame it is in
 * range they are drawn again; items in the hands are never skipped.
 */
public final class AttachmentCull {

    public static volatile boolean active = true;
    private static final float CULL_TILES = 25.0f;

    // Loaded lazily: patchRenderData runs while ModelSlotRenderData is being defined
    private static final class H {
        static final VarHandle ATTACHED = attached();

        private static VarHandle attached() {
            try {
                Class<?> slot = Class.forName("zombie.core.skinnedmodel.ModelManager$ModelSlot");
                return MethodHandles.privateLookupIn(slot, MethodHandles.lookup())
                    .findVarHandle(slot, "attachedModels", Class.forName("zombie.characters.AttachedItems.AttachedModels"));
            } catch (Throwable t) {
                PZOLogger.warn("[AttachmentCull] attached models not readable, culling off: " + t);
                return null;
            }
        }
    }

    private AttachmentCull() {}

    /** Prefix of each sub-model in initModelInst: true leaves this model out of the frame. */
    public static boolean skip(Object renderData, Object modelInstance) {
        if (!active || H.ATTACHED == null) return false;
        zombie.core.skinnedmodel.model.ModelSlotRenderData data = (zombie.core.skinnedmodel.model.ModelSlotRenderData) renderData;
        if (!(data.character instanceof zombie.characters.IsoZombie z) || data.modelSlot == null) return false;
        List<?> attached = (List<?>) H.ATTACHED.get(data.modelSlot);
        if (attached == null || attached.isEmpty() || !attached.contains(modelInstance)) return false;
        return nearestPlayerDistSq(z) > CULL_TILES * CULL_TILES;
    }

    private static float nearestPlayerDistSq(zombie.characters.IsoZombie z) {
        float best = Float.MAX_VALUE;
        zombie.characters.IsoPlayer[] players = zombie.characters.IsoPlayer.players;
        if (players == null) return 0.0f;
        for (zombie.characters.IsoPlayer p : players) {
            if (p == null) continue;
            float dx = z.getX() - p.getX();
            float dy = z.getY() - p.getY();
            best = Math.min(best, dx * dx + dy * dy);
        }
        return best == Float.MAX_VALUE ? 0.0f : best;
    }

    /**
     * In initModelInst's loop, right after "ModelInstance modelInstance = modelInstances.get(i)", inserts
     * "if (AttachmentCull.skip(this, modelInstance)) continue;" by jumping to the loop's i++.
     */
    static boolean patchRenderData(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, mn -> "initModelInst".equals(mn.name));
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
                    || !"zombie/core/skinnedmodel/model/ModelInstance".equals(cast.desc)) continue;
            AbstractInsnNode store = cast.getNext();
            AbstractInsnNode get = cast.getPrevious();
            AbstractInsnNode loadIndex = get == null ? null : get.getPrevious();
            if (!(store instanceof VarInsnNode st) || st.getOpcode() != Opcodes.ASTORE
                    || !(get instanceof MethodInsnNode gm) || !"get".equals(gm.name)
                    || !(loadIndex instanceof VarInsnNode li) || li.getOpcode() != Opcodes.ILOAD) continue;
            IincInsnNode inc = null;
            for (AbstractInsnNode s = store.getNext(); s != null; s = s.getNext()) {
                if (s instanceof IincInsnNode ii && ii.var == li.var) inc = ii;
            }
            if (inc == null) return false;
            LabelNode cont = new LabelNode();
            m.instructions.insertBefore(inc, cont);
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
            pre.add(new VarInsnNode(Opcodes.ALOAD, st.var));
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/AttachmentCull", "skip",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
            pre.add(new JumpInsnNode(Opcodes.IFNE, cont));
            m.instructions.insert(store, pre);
            return true;
        }
        return false;
    }
}
