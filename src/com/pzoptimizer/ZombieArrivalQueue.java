package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoDirections;
import zombie.popman.ZombiePopulationManager;
import zombie.popman.ZombieStateFlags;

/**
 * Settings screen "Pace zombie arrivals" (JVM_ZombieArrivalPacing).
 *
 * When chunks next to a horde load, ZombiePopulationManager.updateMain receives the whole batch from the native
 * population manager and creates every zombie in that frame (dressing, animation set, model slot), a frame of several
 * milliseconds. The creations are queued here instead and made within BUDGET_NS a frame, oldest first:
 * - a zombie within NEAR_TILES of a local player is created at once, as before;
 * - while a local player drives faster than DRIVING_KMH everything is created at once, so nothing pops in ahead of
 *   the car;
 * - nothing waits longer than MAX_WAIT_NS;
 * - for GRACE_NS after the world starts everything is created at once (the loading burst is hidden anyway);
 * - before a chunk unloads, its queued zombies are created first, so stock virtualises them as usual;
 * - when the game saves (GameWindow.save, on the game thread) the queue is emptied first, so every zombie is saved.
 * The checks stock makes before creating (new chunk rooms, loaded-area edge) still run when the batch arrives; only
 * the creation call waits. Single player only (the server streams zombies to clients by itself).
 */
public final class ZombieArrivalQueue {

    public static volatile boolean active = true;
    public static final AtomicLong queued = new AtomicLong();
    public static final AtomicLong immediate = new AtomicLong();

    private static final long BUDGET_NS = 1_000_000L;
    private static final long MAX_WAIT_NS = 500_000_000L;
    private static final long GRACE_NS = 3_000_000_000L;
    private static final float NEAR_TILES = 25.0f;
    private static final int MIN_PER_FRAME = 2;
    private static final float DRIVING_KMH = 15.0f;

    private static final class Arrival {
        float x, y, z;
        IsoDirections dir;
        int descriptor, persistentId, targetX, targetY;
        ZombieStateFlags state;
        boolean moving;
        long at;
    }

    private static final ArrayDeque<Arrival> pending = new ArrayDeque<>();
    private static final ArrayDeque<Arrival> spare = new ArrayDeque<>();
    private static long worldStart = 0L;
    private static boolean wasInGame = false;

    private static final class H {
        static final MethodHandle STANDING = find("addZombieStanding",
            MethodType.methodType(void.class, float.class, float.class, float.class, IsoDirections.class, int.class, ZombieStateFlags.class, int.class));
        static final MethodHandle MOVING = find("addZombieMoving",
            MethodType.methodType(void.class, float.class, float.class, float.class, IsoDirections.class, int.class, ZombieStateFlags.class,
                int.class, int.class, int.class));

        private static MethodHandle find(String name, MethodType type) {
            try {
                return MethodHandles.privateLookupIn(ZombiePopulationManager.class, MethodHandles.lookup())
                    .findVirtual(ZombiePopulationManager.class, name, type);
            } catch (Throwable t) {
                PZOLogger.warn("[ZombieArrivalQueue] " + name + " not reachable, arrivals created at once: " + t);
                return null;
            }
        }
    }

    private ZombieArrivalQueue() {}

    private static boolean deferring(float x, float y) {
        if (!active || zombie.network.GameServer.server || zombie.network.GameClient.client) return false;
        if (H.STANDING == null || H.MOVING == null || !FrameStamp.onGameThread()) return false;
        boolean inGame = zombie.GameWindow.states.current == zombie.gameStates.IngameState.instance;
        if (inGame && !wasInGame) worldStart = System.nanoTime();
        wasInGame = inGame;
        if (!inGame || System.nanoTime() - worldStart < GRACE_NS) return false;
        zombie.characters.IsoPlayer[] players = zombie.characters.IsoPlayer.players;
        for (int i = 0; i < zombie.characters.IsoPlayer.numPlayers; i++) {
            zombie.characters.IsoPlayer p = players[i];
            if (p == null) continue;
            // At driving speed a queued zombie could appear right in front of the car; create everything at once
            zombie.vehicles.BaseVehicle v = p.getVehicle();
            if (v != null && v.getCurrentAbsoluteSpeedKmHour() > DRIVING_KMH) return false;
            float dx = p.getX() - x, dy = p.getY() - y;
            if (dx * dx + dy * dy <= NEAR_TILES * NEAR_TILES) return false;
        }
        return true;
    }

    private static Arrival take() {
        Arrival a = spare.poll();
        return a != null ? a : new Arrival();
    }

    /** Replaces this.addZombieStanding(...) in ZombiePopulationManager.updateMain. */
    public static void standing(ZombiePopulationManager pm, float x, float y, float z, IsoDirections dir, int descriptor,
                                ZombieStateFlags state, int persistentId) throws Throwable {
        if (!deferring(x, y)) {
            immediate.incrementAndGet();
            H.STANDING.invoke(pm, x, y, z, dir, descriptor, state, persistentId);
            return;
        }
        Arrival a = take();
        a.x = x; a.y = y; a.z = z; a.dir = dir; a.descriptor = descriptor; a.state = state; a.persistentId = persistentId;
        a.moving = false;
        a.at = System.nanoTime();
        pending.add(a);
        queued.incrementAndGet();
    }

    /** Replaces this.addZombieMoving(...) in ZombiePopulationManager.updateMain. */
    public static void moving(ZombiePopulationManager pm, float x, float y, float z, IsoDirections dir, int descriptor,
                              ZombieStateFlags state, int targetX, int targetY, int persistentId) throws Throwable {
        if (!deferring(x, y)) {
            immediate.incrementAndGet();
            H.MOVING.invoke(pm, x, y, z, dir, descriptor, state, targetX, targetY, persistentId);
            return;
        }
        Arrival a = take();
        a.x = x; a.y = y; a.z = z; a.dir = dir; a.descriptor = descriptor; a.state = state; a.persistentId = persistentId;
        a.targetX = targetX; a.targetY = targetY;
        a.moving = true;
        a.at = System.nanoTime();
        pending.add(a);
        queued.incrementAndGet();
    }

    private static void create(ZombiePopulationManager pm, Arrival a) {
        try {
            if (a.moving) {
                H.MOVING.invoke(pm, a.x, a.y, a.z, a.dir, a.descriptor, a.state, a.targetX, a.targetY, a.persistentId);
            } else {
                H.STANDING.invoke(pm, a.x, a.y, a.z, a.dir, a.descriptor, a.state, a.persistentId);
            }
        } catch (Throwable t) {
            PZOLogger.warn("[ZombieArrivalQueue] queued zombie not created: " + t);
        }
        a.dir = null;
        a.state = null;
        spare.add(a);
    }

    /** Start of ZombiePopulationManager.updateMain: last frame's queue, within the frame budget. */
    public static void drain(ZombiePopulationManager pm) {
        if (pending.isEmpty() || !FrameStamp.onGameThread()) return;
        long start = System.nanoTime();
        int made = 0;
        Arrival a;
        while ((a = pending.peek()) != null) {
            boolean overdue = start - a.at >= MAX_WAIT_NS;
            if (!overdue && made >= MIN_PER_FRAME && System.nanoTime() - start >= BUDGET_NS) break;
            pending.poll();
            create(pm, a);
            made++;
        }
    }

    /** Start of GameWindow.save, before any chunk is written. */
    public static void beforeSave() {
        if (pending.isEmpty() || !FrameStamp.onGameThread()) return;
        ZombiePopulationManager pm = ZombiePopulationManager.instance;
        Arrival a;
        while ((a = pending.poll()) != null) create(pm, a);
    }

    /** Start of ZombiePopulationManager.removeChunkFromWorld: the chunk's queued zombies are made before it unloads. */
    public static void beforeChunkUnload(ZombiePopulationManager pm, zombie.iso.IsoChunk chunk) {
        if (pending.isEmpty() || chunk == null) return;
        int n = pending.size();
        for (int i = 0; i < n; i++) {
            Arrival a = pending.poll();
            if ((int) Math.floor(a.x) >> 3 == chunk.wx && (int) Math.floor(a.y) >> 3 == chunk.wy) {
                create(pm, a);
            } else {
                pending.add(a);
            }
        }
    }

    /** Start of ZombiePopulationManager.stop: the world is gone, so is the queue. */
    public static void clear(Object ignored) {
        pending.clear();
        wasInGame = false;
    }

    static boolean patchPopulationManager(ClassNode node) {
        MethodNode main = AsmSupport.findMethod(node, "updateMain", "()V");
        MethodNode unload = AsmSupport.findMethod(node, "removeChunkFromWorld", "(Lzombie/iso/IsoChunk;)V");
        MethodNode stop = AsmSupport.findMethod(node, "stop", "()V");
        if (main == null || unload == null || stop == null) return false;
        String owner = "zombie/popman/ZombiePopulationManager";
        int s = AsmSupport.redirectCalls(main, owner, "addZombieStanding", "(FFFLzombie/iso/IsoDirections;ILzombie/popman/ZombieStateFlags;I)V",
            "com/pzoptimizer/ZombieArrivalQueue", "standing",
            "(Lzombie/popman/ZombiePopulationManager;FFFLzombie/iso/IsoDirections;ILzombie/popman/ZombieStateFlags;I)V");
        int m = AsmSupport.redirectCalls(main, owner, "addZombieMoving", "(FFFLzombie/iso/IsoDirections;ILzombie/popman/ZombieStateFlags;III)V",
            "com/pzoptimizer/ZombieArrivalQueue", "moving",
            "(Lzombie/popman/ZombiePopulationManager;FFFLzombie/iso/IsoDirections;ILzombie/popman/ZombieStateFlags;III)V");
        if (s != 1 || m != 1) return false;
        AsmSupport.prependCall(main, "com/pzoptimizer/ZombieArrivalQueue", "drain", "(Lzombie/popman/ZombiePopulationManager;)V");
        AsmSupport.prependCall(stop, "com/pzoptimizer/ZombieArrivalQueue", "clear", "(Ljava/lang/Object;)V");
        // removeChunkFromWorld(chunk): load this and the chunk argument
        com.pzoptimizer.shaded.asm.tree.InsnList pre = new com.pzoptimizer.shaded.asm.tree.InsnList();
        pre.add(new com.pzoptimizer.shaded.asm.tree.VarInsnNode(com.pzoptimizer.shaded.asm.Opcodes.ALOAD, 0));
        pre.add(new com.pzoptimizer.shaded.asm.tree.VarInsnNode(com.pzoptimizer.shaded.asm.Opcodes.ALOAD, 1));
        pre.add(new com.pzoptimizer.shaded.asm.tree.MethodInsnNode(com.pzoptimizer.shaded.asm.Opcodes.INVOKESTATIC,
            "com/pzoptimizer/ZombieArrivalQueue", "beforeChunkUnload", "(Lzombie/popman/ZombiePopulationManager;Lzombie/iso/IsoChunk;)V", false));
        unload.instructions.insert(pre);
        return true;
    }

    static boolean patchGameWindow(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "save", "(Z)V");
        if (m == null) return false;
        com.pzoptimizer.shaded.asm.tree.InsnList pre = new com.pzoptimizer.shaded.asm.tree.InsnList();
        pre.add(new com.pzoptimizer.shaded.asm.tree.MethodInsnNode(com.pzoptimizer.shaded.asm.Opcodes.INVOKESTATIC,
            "com/pzoptimizer/ZombieArrivalQueue", "beforeSave", "()V", false));
        pre.add(new com.pzoptimizer.shaded.asm.tree.MethodInsnNode(com.pzoptimizer.shaded.asm.Opcodes.INVOKESTATIC,
            "com/pzoptimizer/ZombieTravelTally", "flush", "()V", false));
        m.instructions.insert(pre);
        return true;
    }
}
