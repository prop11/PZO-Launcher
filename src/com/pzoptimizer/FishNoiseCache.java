package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fish points a noise scares, from a per-tile list (JVM_FishNoiseCache).
 *
 * Every world sound calls FishSchoolManager.addSoundNoise with a radius of a sixth of its range, and stock walks every
 * square of the (2r+1)^2 box, working out the seeded fish noise and the no-fish zones for each, to find the few fish
 * points (one square in 200) to quiet for three hours. A gunshot is over a thousand squares. Which squares are fish
 * points only depends on the seed and the no-fish zones, so I list them once per 32x32 tile with the game's own
 * isFishPoint and walk that list. The same squares get the same time as stock.
 *
 * Stock runs when chum is in the water (chum squares count too and change all the time) or the no-fish zones haven't
 * been read from Lua yet. The lists are thrown away when the seed or the zone count changes.
 */
public final class FishNoiseCache {

    public static volatile boolean active = true;
    public static final AtomicLong answered = new AtomicLong();

    private static final int TILE = 32;
    private static final int MAX_TILES = 4096;
    private static final HashMap<Long, int[]> tiles = new HashMap<>();
    private static int keptSeed = Integer.MIN_VALUE, keptZones = -1;

    // Lazy: the patch runs while FishSchoolManager is being defined
    private static final class H {
        static final MethodHandle SEED, ZONES, QUIET, CHUM, FISH_POINT, MINUTES;

        static {
            MethodHandle seed = null, zones = null, quiet = null, chum = null, fish = null, minutes = null;
            try {
                Class<?> c = zombie.iso.FishSchoolManager.class;
                MethodHandles.Lookup l = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                seed = l.findGetter(c, "seed", int.class).asType(MethodType.methodType(int.class, Object.class));
                zones = l.findGetter(c, "noFishZones", ArrayList.class).asType(MethodType.methodType(ArrayList.class, Object.class));
                quiet = l.findStaticGetter(c, "noiseFishPointDisabler", gnu.trove.map.hash.TLongIntHashMap.class);
                chum = l.findStaticGetter(c, "chumPoints", gnu.trove.map.hash.TLongObjectHashMap.class);
                fish = l.findVirtual(c, "isFishPoint", MethodType.methodType(boolean.class, int.class, int.class))
                    .asType(MethodType.methodType(boolean.class, Object.class, int.class, int.class));
                minutes = l.findVirtual(c, "getCurrentGameTimeInMinutes", MethodType.methodType(int.class))
                    .asType(MethodType.methodType(int.class, Object.class));
            } catch (Throwable t) {
                PZOLogger.warn("[FishNoiseCache] FishSchoolManager isn't this build's, off: " + t);
                seed = null;
            }
            SEED = seed;
            ZONES = zones;
            QUIET = quiet;
            CHUM = chum;
            FISH_POINT = fish;
            MINUTES = minutes;
        }
    }

    private FishNoiseCache() {}

    /** Prefix of addSoundNoise: true when the noise was handled and stock must return. */
    public static synchronized boolean addSoundNoise(Object mgr, int x, int y, int radius) {
        if (!active || H.SEED == null) return false;
        try {
            gnu.trove.map.hash.TLongObjectHashMap<?> chum = (gnu.trove.map.hash.TLongObjectHashMap<?>) H.CHUM.invokeExact();
            if (!chum.isEmpty()) return false;
            ArrayList<?> zones = (ArrayList<?>) H.ZONES.invokeExact(mgr);
            if (zones.isEmpty()) return false;
            int seed = (int) H.SEED.invokeExact(mgr);
            if (seed != keptSeed || zones.size() != keptZones || tiles.size() > MAX_TILES) {
                tiles.clear();
                keptSeed = seed;
                keptZones = zones.size();
            }
            gnu.trove.map.hash.TLongIntHashMap quiet = (gnu.trove.map.hash.TLongIntHashMap) H.QUIET.invokeExact();
            int until = -1;
            for (int tx = Math.floorDiv(x - radius, TILE), tx1 = Math.floorDiv(x + radius, TILE); tx <= tx1; tx++) {
                for (int ty = Math.floorDiv(y - radius, TILE), ty1 = Math.floorDiv(y + radius, TILE); ty <= ty1; ty++) {
                    for (int p : points(mgr, tx, ty)) {
                        int i = tx * TILE + p / TILE;
                        int j = ty * TILE + p % TILE;
                        if (i < x - radius || i > x + radius || j < y - radius || j > y + radius) continue;
                        // FishSchoolManager.dist, in floats like stock
                        float dx = (float) i - (float) x;
                        float dy = (float) j - (float) y;
                        if (Math.sqrt(dx * dx + dy * dy) <= radius) {
                            if (until < 0) until = (int) H.MINUTES.invokeExact(mgr) + 180;
                            quiet.put(hash(i, j), until);
                        }
                    }
                }
            }
            answered.incrementAndGet();
            return true;
        } catch (Throwable t) {
            active = false;
            PZOLogger.warn("[FishNoiseCache] failed, off for this session: " + t);
            return false;
        }
    }

    private static int[] points(Object mgr, int tx, int ty) throws Throwable {
        long k = ((long) tx << 32) ^ (ty & 0xFFFFFFFFL);
        int[] pts = tiles.get(k);
        if (pts != null) return pts;
        int[] buf = new int[TILE * TILE];
        int n = 0;
        for (int lx = 0; lx < TILE; lx++) {
            for (int ly = 0; ly < TILE; ly++) {
                if ((boolean) H.FISH_POINT.invokeExact(mgr, tx * TILE + lx, ty * TILE + ly)) buf[n++] = lx * TILE + ly;
            }
        }
        pts = java.util.Arrays.copyOf(buf, n);
        tiles.put(k, pts);
        return pts;
    }

    // FishSchoolManager.coordsToHash
    static long hash(int x, int y) {
        long x1 = x >= 0 ? 2L * x : -2L * x - 1L;
        long y1 = y >= 0 ? 2L * y : -2L * y - 1L;
        long c = (x1 >= y1 ? x1 * x1 + x1 + y1 : x1 + y1 * y1) / 2L;
        return (x >= 0 || y >= 0) && (x < 0 || y < 0) ? -c - 1L : c;
    }

    static boolean patchFishSchoolManager(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "addSoundNoise", "(III)V");
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "com/pzoptimizer/FishNoiseCache".equals(mi.owner)) return false;
        }
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FishNoiseCache", "addSoundNoise", "(Ljava/lang/Object;III)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(stock);
        m.instructions.insert(pre);
        return true;
    }
}
