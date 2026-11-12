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
 * nothing is queued.
 *
 * The budget follows the frame: it grows a step each frame while frames stay at their usual length and shrinks when
 * three in a row run long, so the queue only uses time the frame had spare. If the queue gets old it gets a minimum
 * anyway so nothing waits for long. While driving, chunks around where the car will be in a moment go first.
 *
 * Anything queued within a few tiles of a player on foot runs straight away, whatever the budget says. Mods set up
 * or replace objects in these handlers, and a late run after the player had already used the object lost items.
 *
 * Exempt: mods that asked through PZOApi.exemptMod, and the ones on the settings screen list (ModApi "mapLoadExempt").
 */
public final class MapLoadScriptPacer {

    public static volatile boolean active = true;

    private static final String SQUARE_EVENT = "LoadGridsquare";
    private static final String CHUNK_EVENT = "LoadChunk";
    private static final long BUDGET_NS = 2_000_000L;
    private static final long MAX_BUDGET_NS = 2 * BUDGET_NS;
    // The engine's own chunk work keeps frames long on a drive, and that is not the queue's doing, so the budget
    // never goes under this
    private static final long MIN_BUDGET_NS = BUDGET_NS / 2;
    // Frame length over the target that counts as running long, and what I allow on top of the fastest recent frame
    private static final long OVERSHOOT_NS = 500_000L, FLOOR_SLACK_NS = 500_000L;
    // A gap this long between frames is a pause or a loading screen
    private static final long FRAME_GAP_NS = 1_000_000_000L;
    private static final int FLOOR_FRAMES = 60;
    private static final long AGE_1_NS = 1_000_000_000L, AGE_2_NS = 3_000_000_000L;
    // Squares queued in one frame at or above which that frame drains nothing (about four chunks)
    private static final int ARRIVAL_GATE = 256;
    // Buckets scored per pick; the drain works through these before scoring again
    private static final int PICK_N = 16;
    // Buckets checked for an unloaded chunk per drain call
    private static final int SWEEP_SLICE = 32;
    private static final float EXIT_FLUSH_TILES = 24.0f;
    // On foot nothing queued may sit this close to the player (measured to the chunk's edge)
    private static final float FOOT_RING_TILES = 12.0f;
    // Scoring aims at where the vehicle will be after this long, at most this far ahead
    private static final float LOOKAHEAD_S = 1.5f, LOOKAHEAD_MAX_TILES = 48.0f;

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
    private static final boolean[] hasLastPos = new boolean[4];
    private static final float[] lastPosX = new float[4], lastPosY = new float[4];
    // Packed, one entry per local player this frame
    private static final float[] playerX = new float[4], playerY = new float[4];
    private static final float[] aimX = new float[4], aimY = new float[4];
    private static final boolean[] onFoot = new boolean[4];
    private static int playerCount;

    // Frame pacing
    private static long budgetNs = BUDGET_NS;
    private static long lastFrameAt, lastFrameNs;
    private static long f1, f2, f3;
    private static long floorA = Long.MAX_VALUE, floorB = Long.MAX_VALUE;
    private static int floorCount;
    private static long capNs;

    private static final ConcurrentHashMap<se.krka.kahlua.vm.Prototype, Boolean> deferrable = new ConcurrentHashMap<>();
    private static final Set<String> exemptPaths = ConcurrentHashMap.newKeySet();

    // Since launch, for the profiler report
    public static long deferredCalls, ranLater, dropped, swept, gatedFrames, exitFlushes, ranNearFoot;
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
        zombie.iso.IsoWorld world = zombie.iso.IsoWorld.instance;
        int frame = world != null ? world.getFrameNo() : budgetFrame;
        boolean newFrame = frame != budgetFrame;
        long start = System.nanoTime();
        if (newFrame) {
            budgetFrame = frame;
            observeFrame(start);
            spentNs = 0;
            // Squares are pooled, so the same object can come back as a different square on a later frame
            lastSquare = null;
            countedSquare = null;
        }
        if (queued == 0) {
            // Keep the vehicle state current, or an exit long after the queue emptied would look new later
            readPlayers(newFrame, null);
            return;
        }
        if (!active || !inGame()) {
            // Switched off or left the game: run nothing late, the squares get the event on their next load
            dropped += queued;
            clear();
            return;
        }
        zombie.iso.IsoCell cell = world != null ? world.currentCell : null;
        if (cell == null) return;
        draining = true;
        try {
            readPlayers(newFrame, cell);
            // Before the chunk-burst check: this one is about correctness, not smoothness
            footRing(cell);
            sweepStep(cell);
            if (queued == 0) return;

            long oldest = oldestAge(start);
            // The frame that loaded a burst of chunks already carries the engine's chunk work. Only a queue that has
            // waited a long time still runs on it.
            if (frame == arrivalFrame && arrivals >= ARRIVAL_GATE && oldest <= AGE_2_NS) {
                if (newFrame) gatedFrames++;
                return;
            }
            // What the frame can spare, with a minimum once the queue has waited
            long allowed = budgetNs;
            if (oldest > AGE_2_NS) allowed = Math.max(allowed, BUDGET_NS * 4);
            else if (oldest > AGE_1_NS) allowed = Math.max(allowed, BUDGET_NS);
            if (allowed <= 0) return;
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
            boolean ok = it.thread == lastThread && (tail ? it.arg == live : stillLoaded(cell, (zombie.iso.IsoGridSquare) it.arg, live));
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

    // The PICK_N best buckets into picks, best first: squared distance to the nearest player's aim point
    private static int pick() {
        int count = 0;
        for (int i = 0, size = list.size(); i < size; i++) {
            Bucket b = list.get(i);
            float cx = b.wx * 8 + 4, cy = b.wy * 8 + 4;
            float d = Float.MAX_VALUE;
            for (int p = 0; p < playerCount; p++) {
                float dx = aimX[p] - cx, dy = aimY[p] - cy;
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

    // The frame that just ended. Moves the budget one step: up while frames keep their usual length, down when the
    // shortest of the last three ran long (one long frame is the engine's own chunk burst, three are mine).
    private static void observeFrame(long now) {
        long frameNs = lastFrameAt == 0 ? 0 : now - lastFrameAt;
        lastFrameAt = now;
        if (frameNs <= 0 || frameNs >= FRAME_GAP_NS) {
            lastFrameNs = 0;
            f1 = f2 = f3 = 0;
            return;
        }
        lastFrameNs = frameNs;
        if (floorCount == 0) capNs = readCapNs();
        // The floor is the frame without my own work, or the queue would raise its own target
        long own = frameNs - spentNs;
        if (own < floorA) floorA = own;
        if (++floorCount >= FLOOR_FRAMES) {
            floorB = floorA;
            floorA = Long.MAX_VALUE;
            floorCount = 0;
        }
        f3 = f2;
        f2 = f1;
        f1 = frameNs;
        long typical = f1;
        if (f2 > 0 && f2 < typical) typical = f2;
        if (f3 > 0 && f3 < typical) typical = f3;

        // Under a frame cap or vsync every frame is as long as the cap, so the cap is the target there. Without
        // one it's the fastest frame of the last couple of seconds.
        long target = capNs;
        long floor = Math.min(floorA, floorB);
        if (floor != Long.MAX_VALUE && floor + FLOOR_SLACK_NS > target) target = floor + FLOOR_SLACK_NS;
        if (typical > target + OVERSHOOT_NS) budgetNs = Math.max(MIN_BUDGET_NS, budgetNs - MAX_BUDGET_NS / 4);
        else budgetNs = Math.min(MAX_BUDGET_NS, budgetNs + MAX_BUDGET_NS / 8);
    }

    // Frame length the game holds itself to, 0 if nothing limits it
    private static long readCapNs() {
        long cap = 0;
        try {
            zombie.core.PerformanceSettings ps = zombie.core.PerformanceSettings.instance;
            if (ps != null && !ps.isFramerateUncapped()) {
                int fps = zombie.core.PerformanceSettings.getLockFPS();
                if (fps > 0) cap = 1_000_000_000L / fps;
            }
            zombie.core.Core core = zombie.core.Core.getInstance();
            if (core != null && core.getOptionVSync()) {
                int hz = org.lwjglx.opengl.Display.getDisplayMode().getFrequency();
                if (hz > 0) cap = Math.max(cap, 1_000_000_000L / hz);
            }
        } catch (Throwable ignored) {
        }
        return cap;
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
                hasLastPos[i] = false;
                continue;
            }
            float px = p.getX(), py = p.getY();
            boolean inVehicle = p.getVehicle() != null;
            float ax = px, ay = py;
            if (inVehicle && hasLastPos[i] && lastFrameNs > 0) {
                // Tiles per second from the last frame's movement; chunks behind the car are about to unload anyway
                float k = LOOKAHEAD_S * 1_000_000_000f / lastFrameNs;
                float lx = (px - lastPosX[i]) * k, ly = (py - lastPosY[i]) * k;
                float len2 = lx * lx + ly * ly;
                if (len2 > LOOKAHEAD_MAX_TILES * LOOKAHEAD_MAX_TILES) {
                    float scale = LOOKAHEAD_MAX_TILES / (float) Math.sqrt(len2);
                    lx *= scale;
                    ly *= scale;
                }
                ax += lx;
                ay += ly;
            }
            lastPosX[i] = px;
            lastPosY[i] = py;
            hasLastPos[i] = true;
            playerX[playerCount] = px;
            playerY[playerCount] = py;
            aimX[playerCount] = ax;
            aimY[playerCount] = ay;
            onFoot[playerCount] = !inVehicle;
            playerCount++;
            if (wasInVehicle[i] && !inVehicle && cell != null) {
                flushNear(cell, px, py, EXIT_FLUSH_TILES);
                exitFlushes++;
            }
            wasInVehicle[i] = inVehicle;
        }
    }

    // A late handler near a player on foot can undo what the player just did (items put in a container the handler
    // then replaces). So on foot nothing stays queued within reach. Every call, not once per frame: in split screen
    // the second player's chunk map loads after the first drain.
    private static void footRing(zombie.iso.IsoCell cell) {
        for (int p = 0; p < playerCount && queued > 0; p++) {
            if (onFoot[p]) ranNearFoot += flushNear(cell, playerX[p], playerY[p], FOOT_RING_TILES);
        }
    }

    // Runs every bucket whose chunk comes within the radius, no budget. Returns the calls run.
    private static long flushNear(zombie.iso.IsoCell cell, float px, float py, float radius) {
        float r2 = radius * radius;
        long before = ranLater;
        long t0 = System.nanoTime();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (i >= list.size()) continue;
            Bucket b = list.get(i);
            float dx = Math.max(0f, Math.abs(b.wx * 8 + 4 - px) - 4f), dy = Math.max(0f, Math.abs(b.wy * 8 + 4 - py) - 4f);
            if (dx * dx + dy * dy <= r2) drainBucket(cell, b, t0, Long.MAX_VALUE);
        }
        return ranLater - before;
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

    // The chunk check matters: a pooled square can be loaded again somewhere else while its old entry still waits
    // here, and it would get the event twice
    private static boolean stillLoaded(zombie.iso.IsoCell cell, zombie.iso.IsoGridSquare sq, zombie.iso.IsoChunk live) {
        if (sq.chunk != live) return false;
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
                && !ModApi.isExemptPath(f) && !ModApi.isExemptPrototype(lc.prototype) && !onExemptList(f)
                && !isLetMeDrive(f);
        }
        deferrable.put(lc.prototype, ok);
        return ok;
    }

    // Let Me Drive! queues squares itself behind its own dispatcher; queueing that dispatcher too would do it twice
    private static boolean isLetMeDrive(String path) {
        return path.contains("/letmedrive/") || path.contains("/3805307651/");
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
        return String.format(Locale.ROOT, "Mod map-load scripts spread over frames (%s): %d calls queued, %d run later, %d dropped (square unloaded first, %d of them swept), queue peak %d, %d chunk-load frames skipped, %d vehicle-exit flushes, %d run at once near a player on foot, frame budget now %.2f ms",
            active ? "on" : "off", deferredCalls, ranLater, dropped, swept, peakQueue, gatedFrames, exitFlushes, ranNearFoot, budgetNs / 1e6);
    }
}
