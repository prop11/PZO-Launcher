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
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoPlayer;
import zombie.entity.GameEntity;

/**
 * Only checks the entities somebody is using (JVM_UsingPlayerTrack).
 *
 * Every update UsingPlayerUpdateSystem walks every object entity in the world (crafting stations, containers with
 * components...) just to clear "in use by" on the few whose player walked more than 10 tiles away, changed floor or
 * died. That walk was ~5% of the game thread in a bench. I note each entity whenever GameEntity writes its usingPlayer
 * field (setUsingPlayer, reset and the server's network update all write it), and the update checks only those.
 * The tests and what they do are the stock ones.
 */
public final class UsingPlayerTracker {

    public static volatile boolean active = true;
    // Set by the agent once GameEntity really carries the hook; without it the stock walk has to run
    static volatile boolean entityHooked;
    public static final AtomicLong checks = new AtomicLong();

    // Written from the game thread and, on a server, the network thread; weak so a removed entity doesn't linger
    private static final Set<GameEntity> using = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private UsingPlayerTracker() {}

    /** After every write of GameEntity.usingPlayer. Always records, so switching the setting on later misses nothing. */
    public static void note(GameEntity e) {
        try {
            if (e != null && e.getUsingPlayer() != null) using.add(e);
        } catch (Throwable ignored) {
        }
    }

    /** Start of UsingPlayerUpdateSystem.update(); true skips the stock walk. */
    public static boolean update() {
        if (!active || !entityHooked) return false;
        if (zombie.network.GameClient.client) return true;
        Object[] list;
        synchronized (using) {
            if (using.isEmpty()) return true;
            list = using.toArray();
        }
        for (Object o : list) {
            GameEntity e = (GameEntity) o;
            IsoPlayer p = e.getUsingPlayer();
            if (p == null) {
                using.remove(e);
                continue;
            }
            // Stock leaves entities outside the engine alone, so they stay listed until they're back or freed
            if (!e.isValidEngineEntity()) continue;
            checks.incrementAndGet();
            if (p.getX() < e.getX() - 10.0F || p.getX() > e.getX() + 10.0F || p.getY() < e.getY() - 10.0F
                    || p.getY() > e.getY() + 10.0F || p.getZ() != e.getZ() || p.isDead()) {
                e.setUsingPlayer(null);
                using.remove(e);
            }
        }
        return true;
    }

    /** GameEntity: note the entity after each putfield of usingPlayer. */
    static boolean patchGameEntity(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            if ((m.access & Opcodes.ACC_STATIC) != 0) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD && "usingPlayer".equals(fi.name)
                        && "zombie/entity/GameEntity".equals(fi.owner)) {
                    InsnList after = new InsnList();
                    after.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/UsingPlayerTracker", "note",
                        "(Lzombie/entity/GameEntity;)V", false);
                    after.add(call);
                    m.instructions.insert(fi, after);
                    insn = call;
                    n++;
                }
            }
        }
        // setUsingPlayer, reset and receiveUpdateUsingPlayer at least; without the setter the tracking would be blind
        return n >= 2;
    }

    /** UsingPlayerUpdateSystem.update(): return early when the tracker handled it. */
    static boolean patchSystem(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "update", "()V");
        if (m == null) return false;
        LabelNode go = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/UsingPlayerTracker", "update", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, go));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(go);
        m.instructions.insert(pre);
        return true;
    }
}
