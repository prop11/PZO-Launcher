package com.pzoptimizer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Other mods' LoadGridsquare handlers run once per square as the map streams in, all inside the frame that loaded the
 * chunk. With a lot of mods that's most of the "chunk streaming" time while driving (17 of 18 ms per frame in one
 * player's recording, about 30 mods). Here their calls go into a queue and run after the chunk update under a time
 * budget, squares nearest a player first. The game's own handlers and PZO's run straight away as before.
 *
 * A square that unloads before its turn is dropped; the event fires again when it loads next time. While the world is
 * still loading nothing is queued. If the queue gets old the budget grows so nothing waits for long.
 *
 * Exempt: mods that asked through PZOApi.exemptMod, and the ones on the settings screen list (ModApi "mapLoadExempt").
 */
public final class MapLoadScriptPacer {

    public static volatile boolean active = true;

    private static final String EVENT = "LoadGridsquare";
    private static final long BUDGET_NS = 2_000_000L;
    private static final long AGE_1_NS = 1_000_000_000L, AGE_2_NS = 3_000_000_000L;

    private static final class Item {
        final se.krka.kahlua.integration.LuaCaller caller;
        final se.krka.kahlua.vm.KahluaThread thread;
        final Object closure;
        final zombie.iso.IsoGridSquare square;
        final long queuedAt;

        Item(se.krka.kahlua.integration.LuaCaller caller, se.krka.kahlua.vm.KahluaThread thread, Object closure,
             zombie.iso.IsoGridSquare square, long queuedAt) {
            this.caller = caller;
            this.thread = thread;
            this.closure = closure;
            this.square = square;
            this.queuedAt = queuedAt;
        }
    }

    // Game thread only. One bucket per 8x8 squares, in the order they were queued
    private static final Map<Long, ArrayDeque<Item>> buckets = new HashMap<>();
    private static int queued;
    private static se.krka.kahlua.vm.KahluaThread lastThread;
    private static boolean draining;
    private static int budgetFrame = Integer.MIN_VALUE;
    private static long spentNs;

    private static final ConcurrentHashMap<se.krka.kahlua.vm.Prototype, Boolean> deferrable = new ConcurrentHashMap<>();
    private static final Set<String> exemptPaths = ConcurrentHashMap.newKeySet();

    // Since launch, for the profiler report
    public static long deferredCalls, ranLater, dropped;
    public static int peakQueue;

    private MapLoadScriptPacer() {}

    /** From LuaEventPatches.call: true when the call was queued instead of run. */
    static boolean defer(se.krka.kahlua.integration.LuaCaller caller, se.krka.kahlua.vm.KahluaThread thread, Object closure,
                         String event, Object[] params) {
        if (!active || draining || !EVENT.equals(event)) return false;
        if (params == null || params.length < 1 || !(params[0] instanceof zombie.iso.IsoGridSquare sq)) return false;
        if (!inGame() || !isDeferrable(closure)) return false;
        if (thread != lastThread) {
            // A new Lua state (back from the main menu): the old closures are gone
            clear();
            lastThread = thread;
        }
        long key = ((long) (sq.getX() >> 3) << 32) | ((sq.getY() >> 3) & 0xFFFFFFFFL);
        buckets.computeIfAbsent(key, k -> new ArrayDeque<>()).add(new Item(caller, thread, closure, sq, System.nanoTime()));
        queued++;
        deferredCalls++;
        if (queued > peakQueue) peakQueue = queued;
        return true;
    }

    /** After each IsoChunkMap.update (LiveProfiler.chunkUpdate). The budget is per frame, shared by all chunk maps. */
    static void drain() {
        if (queued == 0) return;
        if (!active || !inGame()) {
            // Switched off or left the game: run nothing late, the squares get the event on their next load
            dropped += queued;
            clear();
            return;
        }
        zombie.iso.IsoWorld world = zombie.iso.IsoWorld.instance;
        zombie.iso.IsoCell cell = world != null ? world.currentCell : null;
        if (cell == null) return;
        int frame = world.getFrameNo();
        if (frame != budgetFrame) {
            budgetFrame = frame;
            spentNs = 0;
        }
        long start = System.nanoTime();
        long budget = BUDGET_NS;
        draining = true;
        try {
            while (queued > 0) {
                Map.Entry<Long, ArrayDeque<Item>> next = nearest(start);
                if (next == null) break;
                long oldest = oldestAge;
                long allowed = oldest > AGE_2_NS ? budget * 4 : oldest > AGE_1_NS ? budget * 2 : budget;
                ArrayDeque<Item> q = next.getValue();
                boolean out = false;
                while (!q.isEmpty()) {
                    if (spentNs + (System.nanoTime() - start) >= allowed) {
                        out = true;
                        break;
                    }
                    Item it = q.poll();
                    queued--;
                    if (it.thread != lastThread || !stillLoaded(cell, it.square)) {
                        dropped++;
                        continue;
                    }
                    run(it);
                    ranLater++;
                }
                if (q.isEmpty()) buckets.remove(next.getKey());
                if (out) break;
            }
        } finally {
            draining = false;
            spentNs += System.nanoTime() - start;
        }
    }

    private static long oldestAge;

    // The bucket nearest any local player; also notes how long the oldest call has waited
    private static Map.Entry<Long, ArrayDeque<Item>> nearest(long now) {
        zombie.characters.IsoPlayer[] players = zombie.characters.IsoPlayer.players;
        Map.Entry<Long, ArrayDeque<Item>> best = null;
        float bestD = Float.MAX_VALUE;
        long oldestAt = Long.MAX_VALUE;
        for (Iterator<Map.Entry<Long, ArrayDeque<Item>>> i = buckets.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<Long, ArrayDeque<Item>> e = i.next();
            ArrayDeque<Item> q = e.getValue();
            Item head = q.peek();
            if (head == null) {
                i.remove();
                continue;
            }
            if (head.queuedAt < oldestAt) oldestAt = head.queuedAt;
            long k = e.getKey();
            float cx = (int) (k >> 32) * 8 + 4, cy = (int) k * 8 + 4;
            float d = Float.MAX_VALUE;
            if (players != null) {
                for (zombie.characters.IsoPlayer p : players) {
                    if (p == null) continue;
                    float dx = p.getX() - cx, dy = p.getY() - cy;
                    d = Math.min(d, dx * dx + dy * dy);
                }
            }
            if (best == null || d < bestD) {
                best = e;
                bestD = d;
            }
        }
        oldestAge = oldestAt == Long.MAX_VALUE ? 0 : now - oldestAt;
        return best;
    }

    private static boolean stillLoaded(zombie.iso.IsoCell cell, zombie.iso.IsoGridSquare sq) {
        if (sq.chunk == null) return false;
        return cell.getGridSquare(sq.getX(), sq.getY(), sq.getZ()) == sq;
    }

    private static void run(Item it) {
        Object[] params = {it.square};
        LuaEventPatches.enter(EVENT);
        try {
            LuaEventPatches.call(it.caller, it.thread, it.closure, params);
        } catch (Throwable t) {
            PZOLogger.warn("[MapLoadScriptPacer] queued LoadGridsquare handler failed: " + t);
        } finally {
            LuaEventPatches.exit();
        }
    }

    private static boolean inGame() {
        zombie.gameStates.IngameState in = zombie.gameStates.IngameState.instance;
        return in != null && zombie.GameWindow.states.current == in;
    }

    private static boolean isDeferrable(Object closure) {
        if (!(closure instanceof se.krka.kahlua.vm.LuaClosure lc) || lc.prototype == null) return false;
        Boolean cached = deferrable.get(lc.prototype);
        if (cached != null) return cached;
        String file = lc.prototype.filename != null ? lc.prototype.filename : lc.prototype.file;
        boolean ok = false;
        if (file != null) {
            String f = file.replace('\\', '/').toLowerCase(Locale.ROOT);
            ok = (f.contains("/mods/") || f.contains("workshop")) && !f.contains("mpoptimiser")
                && !ModApi.isExemptPath(f) && !ModApi.isExemptPrototype(lc.prototype) && !onExemptList(f);
        }
        deferrable.put(lc.prototype, ok);
        return ok;
    }

    private static boolean onExemptList(String path) {
        for (String p : exemptPaths) {
            if (path.contains(p)) return true;
        }
        return false;
    }

    /** ModApi "mapLoadExempt": the settings screen's list of mod paths, joined with ';'. */
    static void setExemptPaths(String joined) {
        exemptPaths.clear();
        if (joined != null) {
            for (String p : joined.split(";")) {
                String t = p.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) exemptPaths.add(t);
            }
        }
        deferrable.clear();
    }

    /** ModApi exemptions changed. */
    static void clearCache() {
        deferrable.clear();
    }

    private static void clear() {
        buckets.clear();
        queued = 0;
    }

    public static String summary() {
        return String.format(Locale.ROOT, "Mod map-load scripts spread over frames (%s): %d calls queued, %d run later, %d dropped (square unloaded first), queue peak %d",
            active ? "on" : "off", deferredCalls, ranLater, dropped, peakQueue);
    }
}
