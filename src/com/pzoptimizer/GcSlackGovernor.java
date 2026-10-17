package com.pzoptimizer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frame-Slack Young Generation GC Pacer Governor.
 * With G1GC, a young collection pause (3-8 ms) normally triggers wherever the GameThread happens
 * to be when Eden fills up -- often mid-step during horde combat or high-speed driving, causing a
 * dropped frame.
 *
 * GcSlackGovernor monitors the "G1 Eden Space" pool via JMX. When Eden usage exceeds 90% of its
 * learned collection threshold and the frame limiter has spare slack time at the end of a step,
 * it nudges the young collection inside the idle slack window so the next active game tick runs
 * with a clean Eden pool and zero mid-step GC pause.
 */
public final class GcSlackGovernor {

    private static volatile boolean active = true;
    private static MemoryPoolMXBean edenPool = null;
    private static GarbageCollectorMXBean youngCollector = null;
    private static boolean initialized = false;
    private static boolean usable = false;

    private static long lastYoungCount = -1L;
    private static long lastEdenUsed = 0L;
    private static long learnedEdenCapBytes = 256L * 1024L * 1024L;
    private static long lastSlackCollectNanos = 0L;

    public static final AtomicLong slackCollectionsScheduled = new AtomicLong(0);
    public static final AtomicLong naturalCollectionsObserved = new AtomicLong(0);
    public static volatile Object fillerSink = null;

    private GcSlackGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if ("G1 Eden Space".equals(pool.getName())) {
                    edenPool = pool;
                    break;
                }
            }
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if ("G1 Young Generation".equals(gc.getName())) {
                    youngCollector = gc;
                    break;
                }
            }
            usable = (edenPool != null && youngCollector != null);
            if (usable) {
                lastYoungCount = youngCollector.getCollectionCount();
                PZOLogger.success("[PZO GcSlackGovernor] G1 Eden Space frame-slack pacer armed");
            }
        } catch (Throwable ignored) {
            usable = false;
        }
    }

    /**
     * Called at the end of a frame step when spare frame-cap slack time (in nanoseconds) is available.
     */
    public static void onFrameSlack(long spareSlackNanos) {
        if (!active) return;
        if (!initialized) initialize();
        if (!usable || spareSlackNanos < 3_500_000L) {
            return; // Require at least 3.5 ms of spare frame slack
        }

        try {
            long gcCount = youngCollector.getCollectionCount();
            MemoryUsage usage = edenPool.getUsage();
            if (usage == null) return;

            long used = usage.getUsed();
            long committed = usage.getCommitted();
            if (committed > 0) {
                learnedEdenCapBytes = committed;
            }

            if (gcCount != lastYoungCount) {
                if (lastYoungCount >= 0 && lastEdenUsed > (64L << 20)) {
                    // Blend learned Eden capacity before collection
                    learnedEdenCapBytes = (learnedEdenCapBytes * 3 + lastEdenUsed) / 4;
                }
                lastYoungCount = gcCount;
                naturalCollectionsObserved.incrementAndGet();
            }
            lastEdenUsed = used;

            long remaining = learnedEdenCapBytes - used;
            long threshold = (long) (learnedEdenCapBytes * 0.10); // Less than 10% Eden remaining
            long now = System.nanoTime();

            if (remaining > 0 && remaining <= threshold && remaining <= (24L << 20) && (now - lastSlackCollectNanos) > 500_000_000L) {
                // Allocate short-lived small Eden filler chunks to tip the imminent Young GC inside this idle slack window
                long deadline = now + Math.min(spareSlackNanos - 1_500_000L, 2_500_000L);
                int chunkBytes = 128 * 1024; // 128 KB (never humongous)
                long beforeCount = youngCollector.getCollectionCount();
                long allocated = 0L;
                while (System.nanoTime() < deadline && allocated < remaining + (4L << 20)) {
                    fillerSink = new byte[chunkBytes];
                    allocated += chunkBytes;
                    if (youngCollector.getCollectionCount() > beforeCount) {
                        slackCollectionsScheduled.incrementAndGet();
                        lastYoungCount = youngCollector.getCollectionCount();
                        lastSlackCollectNanos = System.nanoTime();
                        break;
                    }
                }
                fillerSink = null;
            }
        } catch (Throwable ignored) {}
    }
}
