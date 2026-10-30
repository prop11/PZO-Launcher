package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.concurrent.atomic.AtomicLong;
import zombie.statistics.StatisticCategory;
import zombie.statistics.StatisticType;
import zombie.statistics.StatisticsManager;

/**
 * Settings screen "Zombie travel statistics once a frame" (JVM_ZombieTravelStats).
 *
 * Every moving zombie adds its step to "Zombie's Distance Walked / Crawled / Ran" each frame through
 * StatisticsManager.incrementStatistic, which allocates a capturing lambda for computeIfAbsent, looks the key up
 * twice and then string-compares the statistic against every achievement. The steps are summed here per key and
 * handed to incrementStatistic once per key per game tick (and before a save), so the totals and the achievement
 * checks are the same; only the float rounding of the running total can differ in the last digit.
 */
public final class ZombieTravelTally {

    public static volatile boolean active = true;
    public static final AtomicLong folded = new AtomicLong();

    private static final int SLOTS = 3;
    private static final String[] keys = new String[SLOTS];
    // Object[]: no game class may load while IsoZombie is being patched
    private static final Object[] types = new Object[SLOTS];
    private static final Object[] categories = new Object[SLOTS];
    private static final float[] sums = new float[SLOTS];
    private static final boolean[] dirty = new boolean[SLOTS];
    private static long tick = Long.MIN_VALUE;

    private ZombieTravelTally() {}

    /** Replaces StatisticsManager.incrementStatistic in IsoZombie.updateMovementStatistics. */
    public static void add(StatisticsManager sm, StatisticType type, StatisticCategory category, String key, float amount) {
        if (!active || !FrameStamp.onGameThread()) {
            sm.incrementStatistic(type, category, key, amount);
            return;
        }
        long now = FrameStamp.tick();
        if (now != tick) {
            flush();
            tick = now;
        }
        int slot = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (keys[i] == key || (keys[i] != null && keys[i].equals(key))) {
                slot = i;
                break;
            }
            if (keys[i] == null) {
                keys[i] = key;
                slot = i;
                break;
            }
        }
        if (slot < 0 || (types[slot] != null && (types[slot] != type || categories[slot] != category))) {
            sm.incrementStatistic(type, category, key, amount);
            return;
        }
        types[slot] = type;
        categories[slot] = category;
        sums[slot] += amount;
        dirty[slot] = true;
        folded.incrementAndGet();
    }

    /** Hands the sums to the game; next game tick, before a save, and when switched off. */
    public static void flush() {
        if (!FrameStamp.onGameThread()) return;
        StatisticsManager sm = null;
        for (int i = 0; i < SLOTS; i++) {
            if (!dirty[i]) continue;
            if (sm == null) sm = StatisticsManager.getInstance();
            float v = sums[i];
            sums[i] = 0.0f;
            dirty[i] = false;
            sm.incrementStatistic((StatisticType) types[i], (StatisticCategory) categories[i], keys[i], v);
        }
    }

    static boolean patchZombie(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "updateMovementStatistics", "()V");
        if (m == null) return false;
        return AsmSupport.redirectCalls(m, "zombie/statistics/StatisticsManager", "incrementStatistic",
            "(Lzombie/statistics/StatisticType;Lzombie/statistics/StatisticCategory;Ljava/lang/String;F)V",
            "com/pzoptimizer/ZombieTravelTally", "add",
            "(Lzombie/statistics/StatisticsManager;Lzombie/statistics/StatisticType;Lzombie/statistics/StatisticCategory;Ljava/lang/String;F)V") == 1;
    }
}
