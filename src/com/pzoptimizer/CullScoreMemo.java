package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Each zombie's cull score worked out once per sort (JVM_CullScoreMemo).
 *
 * IsoWorld.sceneCullZombies sorts the zombies that get a model by IsoWorld.CompScoreToPlayer, and its compare works
 * out both zombies' scores every time it's called: player relevance for up to four players, with square and room
 * lookups, n log n times a frame. Nothing a score reads changes during the sort, so I keep each zombie's score from
 * the start of sceneCullZombies until the next one. Same scores, same order.
 *
 * Only used once sceneCullZombies is hooked; otherwise every compare asks the game.
 */
public final class CullScoreMemo {

    public static volatile boolean active = true;
    static volatile boolean begun;
    public static final AtomicLong reused = new AtomicLong();

    private static Object[] keys = new Object[1024];
    private static float[] scores = new float[1024];
    private static int used;
    private static Thread owner;

    // Lazy: the comparator class is private and loads after IsoWorld
    private static final class H {
        static final MethodHandle SCORE;

        static {
            MethodHandle h = null;
            try {
                Class<?> c = Class.forName("zombie.iso.IsoWorld$CompScoreToPlayer", false, CullScoreMemo.class.getClassLoader());
                h = MethodHandles.privateLookupIn(c, MethodHandles.lookup())
                    .findVirtual(c, "getScore", MethodType.methodType(float.class, zombie.characters.IsoZombie.class))
                    .asType(MethodType.methodType(float.class, Object.class, zombie.characters.IsoZombie.class));
            } catch (Throwable t) {
                PZOLogger.warn("[CullScoreMemo] CompScoreToPlayer.getScore not found: " + t);
            }
            SCORE = h;
        }
    }

    private CullScoreMemo() {}

    /** Start of IsoWorld.sceneCullZombies. */
    public static void begin() {
        if (used > 0) {
            Arrays.fill(keys, null);
            used = 0;
        }
        owner = Thread.currentThread();
        begun = true;
    }

    /** Replaces this.getScore(zombie) in CompScoreToPlayer.compare. */
    public static float score(Object comparator, zombie.characters.IsoZombie z) throws Throwable {
        if (!active || !begun || z == null || Thread.currentThread() != owner) {
            return (float) H.SCORE.invokeExact(comparator, z);
        }
        int mask = keys.length - 1;
        int h = System.identityHashCode(z);
        int i = (h ^ (h >>> 16)) & mask;
        while (keys[i] != null) {
            if (keys[i] == z) {
                reused.incrementAndGet();
                return scores[i];
            }
            i = (i + 1) & mask;
        }
        float s = (float) H.SCORE.invokeExact(comparator, z);
        keys[i] = z;
        scores[i] = s;
        if (++used * 2 > keys.length) grow();
        return s;
    }

    private static void grow() {
        Object[] oldK = keys;
        float[] oldS = scores;
        keys = new Object[oldK.length * 2];
        scores = new float[keys.length];
        int mask = keys.length - 1;
        for (int j = 0; j < oldK.length; j++) {
            if (oldK[j] == null) continue;
            int h = System.identityHashCode(oldK[j]);
            int i = (h ^ (h >>> 16)) & mask;
            while (keys[i] != null) i = (i + 1) & mask;
            keys[i] = oldK[j];
            scores[i] = oldS[j];
        }
    }

    static boolean patchSceneCull(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "sceneCullZombies", "()V");
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "com/pzoptimizer/CullScoreMemo".equals(mi.owner)) return false;
        }
        m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/CullScoreMemo", "begin", "()V", false));
        return true;
    }

    static boolean patchComparator(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "compare", "(Lzombie/characters/IsoZombie;Lzombie/characters/IsoZombie;)I");
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL && node.name.equals(mi.owner)
                    && "getScore".equals(mi.name)) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/CullScoreMemo", "score",
                    "(Ljava/lang/Object;Lzombie/characters/IsoZombie;)F", false);
                m.instructions.set(mi, call);
                insn = call;
                n++;
            }
        }
        return n == 2;
    }
}
