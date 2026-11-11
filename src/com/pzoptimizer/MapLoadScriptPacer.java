package com.pzoptimizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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
 * A frame that just loaded a burst of chunks runs nothing from the queue, since the engine's own chunk work is already
 * on it. A mod's LoadChunk handlers wait behind that chunk's queued squares, so they still see squares first like in
 * vanilla. Squares with a generator are never queued (Generator Hose credits the offline hours in its handler, and the
 * generator's first update would beat it). Getting out of a vehicle runs everything queued near that player at once.
 *
 * A square that unloads before its turn is dropped; the event fires again when it loads next time. Buckets whose chunk
 * has unloaded are swept a slice per frame so they don't hold squares in memory. While the world is still loading
 * nothing is queued. If the queue gets old the budget grows so nothing waits for long.
 *
 * Exempt: mods that asked through PZOApi.exemptMod, and the ones on the settings screen list (ModApi "mapLoadExempt").
 */
public final class MapLoadScriptPacer {

    public static volatile boolean active = true;

    private static final String SQUARE_EVENT = "LoadGridsquare";
    private static final String CHUNK_EVENT = "LoadChunk";
    private static final long BUDGET_NS = 2_000_000L;
    private static final long AGE_1_NS = 1_000_000_000L, AGE_2_NS = 3_000_000_000L;
    // Squares queued in one frame at or above which that frame drains nothing (about four chunks)
    private static final int ARRIVAL_GATE = 256;
    // Buckets scored per pick; the drain works through these before scoring again
    private static final int PICK_N = 16;
    // Buckets checked for an unloaded chunk per drain call
    private static final int SWEEP_SLICE = 32;
    private static final float EXIT_FLUSH_TILES = 24.0f;

    private static final class Item {
        final se.krka.kahlua.integration.LuaCaller caller;
        final se.krka.kahlua.vm.KahluaThread thread;
        final Object closure;
        final Object arg;
        final long queuedAt;

        Item(se.krka.kahlua.integration.LuaCaller caller, se.krka.kahlua.vm.KahluaThread thread, Object closure,
             Object arg, long queuedAt) {
            this.caller = caller;
            this.thread = thread;
            this.closure = closure;
            this.arg = arg;
            this.queuedAt = queuedAt;
        }
    }

    // One per chunk (8x8 squares). LoadChunk calls go in tails and run only once the squares are done.
    private static final class Bucket {
        final long key;
        final int wx, wy;
        final ArrayDeque<Item> squares = new ArrayDeque<>();
        final ArrayDeque<Item> tails = new ArrayDeque<>();
        int idx;
        float score;

        Bucket(long key, int wx, int wy) {
            this.key = key;
            this.wx = wx;
            this.wy = wy;
        }

        int size() {
            return squares.size() + tails.size();
        }

        long headQueuedAt() {
            Item s = squares.peek();
            if (s != null) return s.queuedAt;
            Item t = tails.peek();
            return t != null ? t.queuedAt : Long.MAX_VALUE;
        }
    }

    // Game thread only. The list is unordered (swap-remove), each bucket knows its slot.
    private static final Map<Long, Bucket> byKey = new HashMap<>();
    private static final ArrayList<Bucket> list = new ArrayList<>();
    private static final Bucket[] picks = new Bucket[PICK_N];
    private static int queued;
    private static se.krka.kahlua.vm.KahluaThread lastThread;
    private static boolean draining;
    private static int budgetFrame = Integer.MIN_VALUE;
    private static long spentNs;
    private static int arrivalFrame = Integer.MIN_VALUE;
    private static int arrivals;
    private static Object lastSquare, countedSquare;
    private static boolean lastSquareHasGenerator;
    private static int sweepAt;
    private static final boolean[] wasInVehicle = new boolean[4];
    private static final float[] playerX = new float[4], playerY = new float[4];
    private static int playerCount;

    private static final ConcurrentHashMap<se.krka.kahlua.vm.Prototype, Boolean> deferrable = new ConcurrentHashMap<>();
    private static final Set<String> exemptPaths = ConcurrentHashMap.newKeySet();

    // Since launch, for the profiler report
    public static long deferredCalls, ranLater, dropped, swept, gatedFrames, exitFlushes;
    public static int peakQueue;

    private MapLoadScriptPacer() {}

    private static long keyOf(int wx, int wy) {
        return ((long) wx << 32) | (wy & 0xFFFFFFFFL);
    }

    /** From LuaEventPatches.call: true when the call was queued instead of run. */
    static boolean defer(se.krka.kahlua.integration.LuaCaller caller, se.krka.kahlua.vm.KahluaThread thread, Object closure,
                         String event, Object[] params) {
        if (!active || draining || params == null || params.length < 1) return false;
        if (SQUARE_EVENT.equals(event)) {
            if (!(params[0] instanceof zombie.iso.IsoGridSquare sq)) return false;
            if (!inGame() || !isDeferrable(closure) || hasGenerator(sq)) return false;
            checkThread(thread);
            int wx = sq.getX() >> 3, wy = sq.getY() >> 3;
            long key = keyOf(wx, wy);
            Bucket b = byKey.get(key);
            if (b == null) b = addBucket(key, wx, wy);
            b.squares.add(new Item(caller, thread, closure, sq, System.nanoTime()));
            noteArrival(sq);
        } else if (CHUNK_EVENT.equals(event)) {
            if (!(params[0] instanceof zombie.iso.IsoChunk ch)) return false;
            if (!inGame() || !isDeferrable(closure)) return false;
            // Only behind squares still waiting; with none queued it runs now, same order as vanilla
            Bucket b = byKey.get(keyOf(ch.wx, ch.wy));
            if (b == null || b.squares.isEmpty() || thread != lastThread) return false;
            b.tails.add(new Item(caller, thread, closure, ch, System.nanoTime()));
        } else {
            return false;
        }
        queued++;
        deferredCalls++;
        if (queued > peakQueue) peakQueue = queued;
        return true;
    }

    // defer runs once per handler per square, so the answer is kept for the square in hand
    private static boolean hasGenerator(zombie.iso.IsoGridSquare sq) {
        if (sq != lastSquare) {
            lastSquare = sq;
            lastSquareHasGenerator = sq.getGenerator() != null;
        }
        return lastSquareHasGenerator;
    }

    private static void noteArrival(zombie.iso.IsoGridSquare sq) {
        zombie.iso.IsoWorld world = zombie.iso.IsoWorld.instance;
        int frame = world != null ? world.getFrameNo() : 0;
        if (frame != arrivalFrame) {
            arrivalFrame = frame;
            arrivals = 0;
        }
        // Counted by square, not by handler
        if (sq != countedSquare) {
            countedSquare = sq;
            arrivals++;
        }
    }

    private static void checkThread(se.krka.kahlua.vm.KahluaThread thread) {
        if (thread != lastThread) {
            // A new Lua state (back from the main menu): the old closures are gone
            clear();
            lastThread = thread;
        }
    }

    private static Bucket addBucket(long key, int wx, int wy) {
        Bucket b = new Bucket(key, wx, wy);
        b.idx = list.size();
        list.add(b);
        byKey.put(key, b);
        return b;
    }

    private static void removeBucket(Bucket b) {
        if (b.idx < 0) return;
        int lastIdx = list.size() - 1;
        Bucket last = list.get(lastIdx);
        list.set(b.idx, last);
        last.idx = b.idx;
        list.remove(lastIdx);
        byKey.remove(b.key);
        b.idx = -1;
    }

    private static void dropBucket(Bucket b) {
        int n = b.size();
        queued -= n;
        dropped += n;
        b.squares.clear();
        b.tails.clear();
        removeBucket(b);
    }

    private static zombie.iso.IsoChunk loadedChunk(zombie.iso.IsoCell cell, Bucket b) {
        return cell.getChunkForGridSquare(b.wx * 8, b.wy * 8, 0);
    }

    /** After each IsoChunkMap.update (LiveProfiler.chunkUpdate). The budget is per frame, shared by all chunk maps. */
    static void drain() {
        if (queued == 0) {
            // Keep the vehicle state current, or an exit long after the queue emptied would look new later
            readPlayers(true, null);
            return;
        }
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
        boolean newFrame = frame != budgetFrame;
        if (newFrame) {
            budgetFrame = frame;
            spentNs = 0;
        }
        long start = System.nanoTime();
        draining = true;
        try {
            readPlayers(newFrame, cell);
            sweepStep(cell);
            if (queued == 0) return;

            long oldest = oldestAge(start);
            // The frame that loaded a burst of chunks already carries the engine's chunk work. Only a queue that has
            // waited a long time still runs on it.
            if (frame == arrivalFrame && arrivals >= ARRIVAL_GATE && oldest <= AGE_2_NS) {
                if (newFrame) gatedFrames++;
                return;
            }
            long allowed = oldest > AGE_2_NS ? BUDGET_NS * 4 : oldest > AGE_1_NS ? BUDGET_NS * 2 : BUDGET_NS;
            boolean out = false;
            while (queued > 0 && !out) {
                int n = pick();
                if (n == 0) break;
                for (int i = 0; i < n && !out; i++) {
                    // A handler of an earlier pick can't queue (draining), but a bucket may already be gone
                    if (picks[i].idx >= 0) out = drainBucket(cell, picks[i], start, allowed);
                }
                for (int i = 0; i < n; i++) picks[i] = null;
            }
        } finally {
            draining = false;
            spentNs += System.nanoTime() - start;
        }
    }

    // Returns true when the budget ran out
    private static boolean drainBucket(zombie.iso.IsoCell cell, Bucket b, long start, long allowed) {
        zombie.iso.IsoChunk live = loadedChunk(cell, b);
        if (live == null) {
            dropBucket(b);
            return false;
        }
        while (true) {
            if (allowed != Long.MAX_VALUE && spentNs + (System.nanoTime() - start) >= allowed) return true;
            boolean tail = b.squares.isEmpty();
            Item it = tail ? b.tails.poll() : b.squares.poll();
            if (it == null) break;
            queued--;
            boolean ok = it.thread == lastThread && (tail ? it.arg == live : stillLoaded(cell, (zombie.iso.IsoGridSquare) it.arg));
            if (!ok) {
                dropped++;
                continue;
            }
            run(it, tail ? CHUNK_EVENT : SQUARE_EVENT);
            ranLater++;
        }
        removeBucket(b);
        return false;
    }

    // The PICK_N best buckets into picks, best first: squared distance to the nearest local player
    private static int pick() {
        int count = 0;
        for (int i = 0, size = list.size(); i < size; i++) {
            Bucket b = list.get(i);
            float cx = b.wx * 8 + 4, cy = b.wy * 8 + 4;
            float d = Float.MAX_VALUE;
            for (int p = 0; p < playerCount; p++) {
                float dx = playerX[p] - cx, dy = playerY[p] - cy;
                d = Math.min(d, dx * dx + dy * dy);
            }
            b.score = d;
            int k;
            if (count < PICK_N) {
                k = count++;
            } else if (d < picks[count - 1].score) {
                k = count - 1;
            } else {
                continue;
            }
            while (k > 0 && picks[k - 1].score > d) {
                picks[k] = picks[k - 1];
                k--;
            }
            picks[k] = b;
        }
        return count;
    }

    private static long oldestAge(long now) {
        long oldestAt = Long.MAX_VALUE;
        for (int i = 0, size = list.size(); i < size; i++) {
            long t = list.get(i).headQueuedAt();
            if (t < oldestAt) oldestAt = t;
        }
        return oldestAt == Long.MAX_VALUE ? 0 : now - oldestAt;
    }

    // Local player positions for scoring, and the step out of a vehicle. Once per frame.
    private static void readPlayers(boolean newFrame, zombie.iso.IsoCell cell) {
        if (!newFrame) return;
        zombie.characters.IsoPlayer[] players = zombie.characters.IsoPlayer.players;
        playerCount = 0;
        if (players == null) return;
        for (int i = 0; i < players.length && i < 4; i++) {
            zombie.characters.IsoPlayer p = players[i];
            if (p == null) {
                wasInVehicle[i] = false;
                continue;
            }
            float px = p.getX(), py = p.getY();
            playerX[playerCount] = px;
            playerY[playerCount] = py;
            playerCount++;
            boolean inVehicle = p.getVehicle() != null;
            if (wasInVehicle[i] && !inVehicle && cell != null) flushNear(cell, px, py);
            wasInVehicle[i] = inVehicle;
        }
    }

    // Mods set up objects in LoadGridsquare assuming nobody touched them yet, so what the player can walk to runs now.
    // One hitch when getting out, the rest stays on the budget.
    private static void flushNear(zombie.iso.IsoCell cell, float px, float py) {
        float r2 = EXIT_FLUSH_TILES * EXIT_FLUSH_TILES;
        long t0 = System.nanoTime();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (i >= list.size()) continue;
            Bucket b = list.get(i);
            float dx = b.wx * 8 + 4 - px, dy = b.wy * 8 + 4 - py;
            if (dx * dx + dy * dy <= r2) drainBucket(cell, b, t0, Long.MAX_VALUE);
        }
        exitFlushes++;
    }

    // Buckets left behind by a fast drive sit at the far end of a nearest-first queue, holding squares the engine
    // has already unloaded. A slice per call, walking down the list (removal swaps from the end).
    private static void sweepStep(zombie.iso.IsoCell cell) {
        if (list.isEmpty()) return;
        if (sweepAt <= 0 || sweepAt > list.size()) sweepAt = list.size();
        int stop = Math.max(0, sweepAt - SWEEP_SLICE);
        for (int i = sweepAt - 1; i >= stop; i--) {
            if (i >= list.size()) continue;
            Bucket b = list.get(i);
            if (loadedChunk(cell, b) == null) {
                swept += b.size();
                dropBucket(b);
            }
        }
        sweepAt = stop;
    }

    private static boolean stillLoaded(zombie.iso.IsoCell cell, zombie.iso.IsoGridSquare sq) {
        if (sq.chunk == null) return false;
        return cell.getGridSquare(sq.getX(), sq.getY(), sq.getZ()) == sq;
    }

    private static void run(Item it, String event) {
        Object[] params = {it.arg};
        LuaEventPatches.enter(event);
        try {
            LuaEventPatches.call(it.caller, it.thread, it.closure, params);
        } catch (Throwable t) {
            PZOLogger.warn("[MapLoadScriptPacer] queued " + event + " handler failed: " + t);
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
        byKey.clear();
        list.clear();
        queued = 0;
        sweepAt = 0;
        lastSquare = null;
        countedSquare = null;
        for (int i = 0; i < PICK_N; i++) picks[i] = null;
    }

    public static String summary() {
        return String.format(Locale.ROOT, "Mod map-load scripts spread over frames (%s): %d calls queued, %d run later, %d dropped (square unloaded first, %d of them swept), queue peak %d, %d chunk-load frames skipped, %d vehicle-exit flushes",
            active ? "on" : "off", deferredCalls, ranLater, dropped, swept, peakQueue, gatedFrames, exitFlushes);
    }
}
