package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.io.File;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Lua mod's performance profiler: live overlay and timed recordings.
 *
 * Nothing is measured unless the overlay is open or a recording is running. Then each main thread frame is split into
 * update (GameWindow.logic), chunk streaming (IsoChunkMap.update and ProcessChunkPos, inside update), Lua events (every
 * Event.trigger callback, via LuaEventPatches), render prep (GameWindow.renderInternal) and the rest (frame cap,
 * waits). GC pauses come from the collector's pause counters and present time from GpuFrameTimer. Lua time goes to the
 * file of each callback, which tells me the mod.
 *
 * Game thread only, apart from the render thread's present counters. Lua talks to it through PZOEngine.profiler(op,
 * arg), see dispatch.
 */
public final class LiveProfiler {

    // Checked by the hooks on every call; true while the overlay is open or a recording runs
    public static volatile boolean on;
    private static boolean live, recording;
    private static long recordStartNs, recordDurationNs;
    private static boolean reportReady;
    private static String lastExport;

    // Current frame
    private static long logicStart, renderStart, chunkStart;
    private static int chunkDepth, luaDepth;
    private static long logicNs, renderNs, chunkNs, luaLogicNs, luaRenderNs, luaChunkNs;
    private static boolean inLogic, inRender, savedThisFrame;
    private static Handler frameTop;
    private static long frameTopNs;

    // Every Lua callback seen, by its compiled function
    private static final class Handler {
        final Object prototype;
        final String event, where;
        String mod;
        long frameNs, windowNs, recordNs, slowNs, calls;
        boolean touched;
        // PZO's own Lua (profiler screen and overlay included) is left out of the mod lists, recording keeps it busy
        boolean self;

        Handler(Object prototype, String event, String where) {
            this.prototype = prototype;
            this.event = event;
            this.where = where;
        }
    }

    private static final IdentityHashMap<Object, Handler> handlers = new IdentityHashMap<>();
    private static final ArrayList<Handler> touched = new ArrayList<>();
    private static final Handler[] recent = new Handler[16];

    // Ring of recent frames for the overlay (10 s at up to ~800 FPS)
    private static final int RING = 8192;
    private static final long[] ringEnd = new long[RING];
    private static final float[] ringMs = new float[RING];
    private static int ringHead, ringCount;

    // Rolling 2 s window for the overlay's breakdown and top mods
    private static long windowStart;
    private static int windowFrames;
    private static final double[] windowParts = new double[8];
    private static String windowSnapshot = "";

    // Recording
    private static final int MAX_FRAMES = 1 << 20;
    private static float[] recMs = new float[4096];
    private static int recFrames;
    private static final double[] recParts = new double[8];
    private static double recGcMax;
    // The game's own frame limit when the recording started, for the smart FPS cap analysis
    private static boolean recUncapped;
    private static int recLockFps, recRefreshHz;
    // Running estimate of the 99th percentile frame time, from a histogram of 0.1 ms bins up to 250 ms
    private static final int[] hist = new int[2501];
    private static int histCount;
    private static final int SLOW_KEEP = 15;
    private static final List<double[]> slowest = new ArrayList<>();
    private static final List<String> slowestTop = new ArrayList<>();

    // Scene sampled every 30 recorded frames so the report can say what was around when it got slow: zombies, animals,
    // driving, rain, heap use
    private static int ctxSamples, ctxDriving, ctxRaining;
    private static long ctxZombies, ctxAnimals;
    private static int ctxZombiesMax, ctxAnimalsMax;
    private static double ctxHeapPeak;

    // Collector pause counters (concurrent collectors' cycle counters are left out)
    private static GarbageCollectorMXBean[] pauseBeans;
    private static long gcLastMs = -1;

    private LiveProfiler() {}


    /** Start of GameWindow.logic(). */
    public static void logicBegin() {
        if (!on || inLogic) return;
        inLogic = true;
        logicStart = System.nanoTime();
    }

    /** Every return of GameWindow.logic(). */
    public static void logicEnd() {
        if (!on || !inLogic) return;
        inLogic = false;
        logicNs += System.nanoTime() - logicStart;
    }

    /** Start of GameWindow.renderInternal(). A render from inside the update stays counted as update. */
    public static void renderBegin() {
        if (!on || inLogic || inRender) return;
        inRender = true;
        renderStart = System.nanoTime();
    }

    /** Every return of GameWindow.renderInternal(). */
    public static void renderEnd() {
        if (!on || !inRender) return;
        inRender = false;
        renderNs += System.nanoTime() - renderStart;
    }

    /** Replaces IsoChunkMap.update() in IsoCell.updateInternal and IngameState. */
    public static void chunkUpdate(zombie.iso.IsoChunkMap map) {
        if (!on) {
            map.update();
            MapLoadScriptPacer.drain();
            return;
        }
        chunkBegin();
        try {
            map.update();
            MapLoadScriptPacer.drain();
        } finally {
            chunkEnd();
        }
    }

    /**
     * Replaces IsoChunkMap.ProcessChunkPos(character) in IngameState.updateInternal (chunk grid shifts and unloads).
     */
    public static void processChunkPos(zombie.iso.IsoChunkMap map, zombie.characters.IsoGameCharacter chr) {
        if (!on) {
            map.ProcessChunkPos(chr);
            return;
        }
        chunkBegin();
        try {
            map.ProcessChunkPos(chr);
        } finally {
            chunkEnd();
        }
    }

    private static void chunkBegin() {
        if (chunkDepth++ == 0) chunkStart = System.nanoTime();
    }

    private static void chunkEnd() {
        if (--chunkDepth == 0) chunkNs += System.nanoTime() - chunkStart;
    }

    /** Start of GameWindow.save. */
    public static void saveBegin() {
        if (on) savedThisFrame = true;
    }

    /** Before a Lua event callback; returns the start time. */
    public static long luaBegin() {
        luaDepth++;
        return System.nanoTime();
    }

    /** After a Lua event callback started with luaBegin. */
    public static void luaEnd(Object closure, String event, long start) {
        long dt = System.nanoTime() - start;
        if (--luaDepth > 0) {
            // A callback that fired another event: that time is already in the outer callback's total
            attribute(closure, event, dt);
            return;
        }
        if (chunkDepth > 0) luaChunkNs += dt;
        else if (inRender) luaRenderNs += dt;
        else luaLogicNs += dt;
        Handler h = attribute(closure, event, dt);
        if (h != null && !h.self && h.frameNs > frameTopNs) {
            frameTop = h;
            frameTopNs = h.frameNs;
        }
    }

    private static Handler attribute(Object closure, String event, long dt) {
        Object proto = closure;
        String where = "?";
        if (closure instanceof se.krka.kahlua.vm.LuaClosure lc && lc.prototype != null) {
            proto = lc.prototype;
        }
        int slot = System.identityHashCode(proto) & 15;
        Handler h = recent[slot];
        if (h == null || h.prototype != proto) {
            h = handlers.get(proto);
            if (h != null) recent[slot] = h;
        }
        if (h == null) {
            if (proto instanceof se.krka.kahlua.vm.Prototype p) {
                String file = p.filename != null ? p.filename : p.file;
                where = (file == null ? "?" : file.replace('\\', '/')) + ":" + p.name;
            }
            h = new Handler(proto, event == null ? "?" : event, where);
            h.mod = modOf(where);
            h.self = SELF_MOD.equalsIgnoreCase(h.mod);
            handlers.put(proto, h);
            recent[slot] = h;
        }
        if (!h.touched) {
            h.touched = true;
            touched.add(h);
        }
        h.frameNs += dt;
        h.calls++;
        return h;
    }

    /** End of every main loop iteration (FrameDropDiagnosticEngine.onFrameTick), with the iteration's length. */
    public static void onFrame(long frameNanos) {
        if (!on) return;
        long now = System.nanoTime();
        if (inLogic) logicEnd();
        if (inRender) renderEnd();
        double ms = frameNanos / 1e6;
        double gc = gcPauseMsSinceLast();
        double luaMs = (luaLogicNs + luaRenderNs) / 1e6;
        double chunks = chunkNs / 1e6;
        double update = Math.max(0, (logicNs - chunkNs - luaLogicNs) / 1e6);
        double render = Math.max(0, (renderNs - luaRenderNs) / 1e6);
        double other = Math.max(0, ms - logicNs / 1e6 - renderNs / 1e6);
        // Last on purpose: readers that expect seven parts ignore it. Mod Lua run inside the chunk update (LoadGridsquare)
        // is part of "chunks"; this says how much of it
        double[] parts = {update, chunks, luaMs, render, other, gc, presentMsSinceLast(), luaChunkNs / 1e6};

        ringEnd[ringHead] = now;
        ringMs[ringHead] = (float) ms;
        ringHead = (ringHead + 1) % RING;
        if (ringCount < RING) ringCount++;

        windowFrames++;
        for (int i = 0; i < parts.length; i++) windowParts[i] += parts[i];

        boolean slow = false;
        if (recording) {
            if (recFrames == recMs.length && recFrames < MAX_FRAMES) recMs = Arrays.copyOf(recMs, recFrames * 2);
            if (recFrames < recMs.length) recMs[recFrames++] = (float) ms;
            for (int i = 0; i < parts.length; i++) recParts[i] += parts[i];
            recGcMax = Math.max(recGcMax, gc);
            int bin = Math.min(hist.length - 1, (int) (ms * 10));
            hist[bin]++;
            histCount++;
            // After a short warm-up, a frame at or over the running p99 counts as one of the 1% lows
            if ((histCount & 63) == 0) p99Cached = p99Estimate();
            slow = histCount >= 200 && ms >= p99Cached;
            keepIfSlowest(now, ms, parts);
            if (recFrames % 30 == 1) sampleContext();
        }

        for (int i = 0, n = touched.size(); i < n; i++) {
            Handler h = touched.get(i);
            h.windowNs += h.frameNs;
            if (recording) {
                h.recordNs += h.frameNs;
                if (slow) h.slowNs += h.frameNs;
            }
            h.frameNs = 0;
            h.touched = false;
        }
        touched.clear();

        logicNs = renderNs = chunkNs = luaLogicNs = luaRenderNs = luaChunkNs = 0;
        frameTop = null;
        frameTopNs = 0;
        savedThisFrame = false;

        if (now - windowStart >= 2_000_000_000L) closeWindow(now);
        if (recording && now - recordStartNs >= recordDurationNs) stopRecording();
    }

    private static void sampleContext() {
        try {
            ctxSamples++;
            zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
            if (cell != null) {
                int z = cell.getZombieList().size();
                // IsoCell.getAnimals() builds a new list every call, so count in place
                int a = 0;
                for (zombie.iso.IsoMovingObject o : cell.getObjectList()) {
                    if (o instanceof zombie.characters.animals.IsoAnimal) a++;
                }
                ctxZombies += z;
                ctxAnimals += a;
                ctxZombiesMax = Math.max(ctxZombiesMax, z);
                ctxAnimalsMax = Math.max(ctxAnimalsMax, a);
            }
            zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.players[0];
            if (p != null && p.getVehicle() != null) ctxDriving++;
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            if (cm != null && cm.getRainIntensity() > 0.1f) ctxRaining++;
            Runtime rt = Runtime.getRuntime();
            ctxHeapPeak = Math.max(ctxHeapPeak, (rt.totalMemory() - rt.freeMemory()) / (double) rt.maxMemory());
        } catch (Throwable ignored) {
        }
    }

    private static double p99Cached = Double.MAX_VALUE;

    private static double p99Estimate() {
        int target = (int) Math.ceil(histCount * 0.01);
        int seen = 0;
        for (int b = hist.length - 1; b >= 0; b--) {
            seen += hist[b];
            if (seen >= target) return b / 10.0;
        }
        return 0;
    }

    private static void keepIfSlowest(long now, double ms, double[] parts) {
        if (slowest.size() == SLOW_KEEP && ms <= slowest.get(SLOW_KEEP - 1)[1]) return;
        double[] row = new double[11];
        row[0] = (now - recordStartNs) / 1e9;
        row[1] = ms;
        System.arraycopy(parts, 0, row, 2, 6);
        row[8] = savedThisFrame ? 1 : 0;
        row[9] = frameTopNs / 1e6;
        row[10] = parts[7];
        String top = frameTop == null ? "" : frameTop.mod + "  " + frameTop.event + " " + shortWhere(frameTop.where);
        int at = 0;
        while (at < slowest.size() && slowest.get(at)[1] >= ms) at++;
        slowest.add(at, row);
        slowestTop.add(at, top);
        if (slowest.size() > SLOW_KEEP) {
            slowest.remove(SLOW_KEEP);
            slowestTop.remove(SLOW_KEEP);
        }
    }

    // Collectors and render thread

    private static double gcPauseMsSinceLast() {
        try {
            if (pauseBeans == null) {
                List<GarbageCollectorMXBean> keep = new ArrayList<>();
                for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
                    String n = b.getName();
                    boolean concurrent = n.contains("Concurrent") || n.contains("Cycles");
                    if (!concurrent && (n.contains("Pause") || n.contains("Young") || n.contains("Old") || n.contains("Mixed")
                            || n.contains("Copy") || n.contains("MarkSweep") || n.contains("Scavenge"))) {
                        keep.add(b);
                    }
                }
                pauseBeans = keep.toArray(new GarbageCollectorMXBean[0]);
            }
            long total = 0;
            for (GarbageCollectorMXBean b : pauseBeans) total += Math.max(0, b.getCollectionTime());
            long prev = gcLastMs;
            gcLastMs = total;
            return prev < 0 ? 0 : total - prev;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long presentSeenNs, presentSeenCount;

    private static double presentMsSinceLast() {
        long sum = GpuFrameTimer.presentTotalNs, count = GpuFrameTimer.presentCount;
        long dn = sum - presentSeenNs, dc = count - presentSeenCount;
        presentSeenNs = sum;
        presentSeenCount = count;
        return dc > 0 ? dn / 1e6 / dc : 0;
    }

    // Windows, recording, reports

    private static void closeWindow(long now) {
        StringBuilder sb = new StringBuilder();
        int f = Math.max(1, windowFrames);
        sb.append('B');
        for (double p : windowParts) sb.append('\t').append(fmt(p / f));
        sb.append('\n');
        for (Map.Entry<String, Double> e : modTotals(true, f, 5)) {
            sb.append("M\t").append(clean(e.getKey())).append('\t').append(fmt(e.getValue())).append('\n');
        }
        windowSnapshot = sb.toString();
        windowStart = now;
        windowFrames = 0;
        Arrays.fill(windowParts, 0);
        for (Handler h : handlers.values()) h.windowNs = 0;
    }

    // Lua milliseconds per frame by mod, costliest first; window or recording totals
    private static List<Map.Entry<String, Double>> modTotals(boolean window, int frames, int limit) {
        HashMap<String, Double> byMod = new HashMap<>();
        for (Handler h : handlers.values()) {
            long ns = window ? h.windowNs : h.recordNs;
            if (ns > 0 && !h.self) byMod.merge(h.mod, ns / 1e6 / frames, Double::sum);
        }
        List<Map.Entry<String, Double>> list = new ArrayList<>(byMod.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private static void startRecording(int seconds) {
        recordDurationNs = Math.max(5, Math.min(1800, seconds)) * 1_000_000_000L;
        recordStartNs = System.nanoTime();
        recFrames = 0;
        Arrays.fill(recParts, 0);
        recGcMax = 0;
        try {
            recUncapped = zombie.core.PerformanceSettings.instance.isFramerateUncapped();
            recLockFps = zombie.core.PerformanceSettings.getLockFPS();
        } catch (Throwable t) {
            recUncapped = true;
            recLockFps = 0;
        }
        recRefreshHz = SmartFpsCap.refreshHz();
        Arrays.fill(hist, 0);
        histCount = 0;
        p99Cached = Double.MAX_VALUE;
        slowest.clear();
        slowestTop.clear();
        ctxSamples = ctxDriving = ctxRaining = ctxZombiesMax = ctxAnimalsMax = 0;
        ctxZombies = ctxAnimals = 0;
        ctxHeapPeak = 0;
        for (Handler h : handlers.values()) {
            h.recordNs = 0;
            h.slowNs = 0;
        }
        reportReady = false;
        lastExport = null;
        recording = true;
        refreshOn();
        PZOLogger.info("[LiveProfiler] Recording for " + seconds + " s");
    }

    private static void stopRecording() {
        if (!recording) return;
        recording = false;
        reportReady = recFrames > 0;
        refreshOn();
        PZOLogger.info("[LiveProfiler] Recording finished: " + recFrames + " frames");
    }

    private static void refreshOn() {
        boolean was = on;
        on = live || recording;
        if (on && !was) {
            // Start clean: no half-measured frame, fresh counters
            logicNs = renderNs = chunkNs = luaLogicNs = luaRenderNs = luaChunkNs = 0;
            inLogic = inRender = false;
            gcLastMs = -1;
            gcPauseMsSinceLast();
            presentMsSinceLast();
            windowStart = System.nanoTime();
            windowFrames = 0;
            Arrays.fill(windowParts, 0);
            windowSnapshot = "";
        }
    }

    // Reused by liveText, which the overlay calls a few times a second
    private static final int[] liveHist = new int[2501];
    private static final float[] liveBuckets = new float[100];

    /** Stats line, breakdown, top mods and a graph of the last 10 s (max frame time per 100 ms). */
    private static String liveText() {
        long now = System.nanoTime();
        long from = now - 10_000_000_000L;
        Arrays.fill(liveHist, 0);
        Arrays.fill(liveBuckets, 0f);
        int n = 0, spikes = 0;
        double total = 0, worst = 0;
        for (int i = 0; i < ringCount; i++) {
            int idx = (ringHead - 1 - i + RING) % RING;
            if (ringEnd[idx] < from) break;
            float v = ringMs[idx];
            n++;
            total += v;
            worst = Math.max(worst, v);
            if (v > 33.4f) spikes++;
            liveHist[Math.min(liveHist.length - 1, (int) (v * 10))]++;
            int b = 99 - (int) ((now - ringEnd[idx]) / 100_000_000L);
            if (b >= 0 && b < 100) liveBuckets[b] = Math.max(liveBuckets[b], v);
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append(histStatsLine(liveHist, n, total, worst, spikes)).append('\n');
        sb.append(windowSnapshot);
        sb.append('G');
        for (float b : liveBuckets) sb.append('\t').append(fmt(b));
        sb.append('\n');
        if (recording) {
            sb.append("R\t").append(fmt((now - recordStartNs) / 1e9)).append('\t').append(fmt(recordDurationNs / 1e9)).append('\n');
        }
        return sb.toString();
    }

    // Overlay stats line from a 0.1 ms histogram: same fields as statsLine, percentiles to the nearest 0.1 ms
    private static String histStatsLine(int[] h, int n, double total, double worst, int spikes) {
        if (n == 0) return "S\t0\t0\t0\t0\t0\t0";
        int n1 = Math.max(1, n / 100);
        int take = n1, p99Rank = n - Math.min(n - 1, (int) (n * 0.99));
        double low = 0, p99 = 0;
        int seen = 0;
        for (int b = h.length - 1; b >= 0 && (take > 0 || seen < p99Rank); b--) {
            int c = h[b];
            if (c == 0) continue;
            double v = (b + 0.5) / 10.0;
            int use = Math.min(c, take);
            low += use * v;
            take -= use;
            if (seen < p99Rank && seen + c >= p99Rank) p99 = v;
            seen += c;
        }
        low /= n1;
        return "S\t" + fmt(1000.0 * n / total) + '\t' + fmt(low > 0 ? 1000.0 / low : 0) + '\t' + fmt(p99) + '\t'
            + fmt(worst) + '\t' + spikes + '\t' + n;
    }

    // S fps, 1% low fps, p99 ms, worst ms, frames over 33 ms, frames
    private static String statsLine(float[] ms, int n) {
        if (n == 0) return "S\t0\t0\t0\t0\t0\t0";
        float[] s = Arrays.copyOf(ms, n);
        Arrays.sort(s);
        double total = 0;
        int spikes = 0;
        for (int i = 0; i < n; i++) {
            total += s[i];
            if (s[i] > 33.4f) spikes++;
        }
        int n1 = Math.max(1, n / 100);
        double low = 0;
        for (int i = n - n1; i < n; i++) low += s[i];
        low /= n1;
        double p99 = s[Math.min(n - 1, (int) (n * 0.99))];
        return "S\t" + fmt(1000.0 * n / total) + '\t' + fmt(low > 0 ? 1000.0 / low : 0) + '\t' + fmt(p99) + '\t'
            + fmt(s[n - 1]) + '\t' + spikes + '\t' + n;
    }

    /** The finished recording: stats, breakdown, slowest frames and every mod's Lua cost. */
    private static String reportText() {
        if (!reportReady) return "";
        StringBuilder sb = new StringBuilder();
        double secs = recordDurationNs / 1e9;
        sb.append(statsLine(recMs, recFrames)).append('\t').append(fmt(secs)).append('\t').append(fmt(recParts[5]))
            .append('\t').append(fmt(recGcMax)).append('\n');
        int f = Math.max(1, recFrames);
        sb.append('B');
        for (double p : recParts) sb.append('\t').append(fmt(p / f));
        sb.append('\n');
        // C: zombies avg and max, animals avg and max, % of time driving, % raining, peak heap use %, heap max MB
        int cs = Math.max(1, ctxSamples);
        sb.append("C\t").append(fmt((double) ctxZombies / cs)).append('\t').append(ctxZombiesMax).append('\t')
            .append(fmt((double) ctxAnimals / cs)).append('\t').append(ctxAnimalsMax).append('\t')
            .append(fmt(100.0 * ctxDriving / cs)).append('\t').append(fmt(100.0 * ctxRaining / cs)).append('\t')
            .append(fmt(100.0 * ctxHeapPeak)).append('\t').append(Runtime.getRuntime().maxMemory() >> 20).append('\n');
        sb.append(SmartFpsCap.line(recMs, recFrames, recUncapped, recLockFps, recRefreshHz)).append('\n');
        // Timeline: the longest frame in each of 120 equal slices of the recorded time
        double total = 0;
        for (int i = 0; i < recFrames; i++) total += recMs[i];
        float[] slices = new float[120];
        double at = 0;
        for (int i = 0; i < recFrames; i++) {
            int s = total > 0 ? Math.min(119, (int) (at / total * 120)) : 0;
            slices[s] = Math.max(slices[s], recMs[i]);
            at += recMs[i];
        }
        sb.append('T').append('\t').append(fmt(total / 1000.0));
        for (float s : slices) sb.append('\t').append(fmt(s));
        sb.append('\n');
        for (int i = 0; i < slowest.size(); i++) {
            double[] r = slowest.get(i);
            sb.append('F');
            for (int j = 0; j < 10; j++) sb.append('\t').append(fmt(r[j]));
            // Mod map-load Lua goes after the top handler so older readers keep their field positions
            sb.append('\t').append(clean(slowestTop.get(i))).append('\t').append(fmt(r[10])).append('\n');
        }
        // Each mod: ms per frame, share of the time spent in the slowest 1% of frames, calls, its costliest handler
        HashMap<String, double[]> byMod = new HashMap<>();
        HashMap<String, Handler> topOf = new HashMap<>();
        double slowTotal = 0;
        for (Handler h : handlers.values()) {
            if (h.recordNs == 0 || h.self) continue;
            double[] a = byMod.computeIfAbsent(h.mod, k -> new double[3]);
            a[0] += h.recordNs;
            a[1] += h.slowNs;
            a[2] += h.calls;
            Handler t = topOf.get(h.mod);
            if (t == null || h.recordNs > t.recordNs) topOf.put(h.mod, h);
        }
        double slowFrameMs = slowFrameTimeMs();
        List<Map.Entry<String, double[]>> mods = new ArrayList<>(byMod.entrySet());
        mods.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        for (Map.Entry<String, double[]> e : mods) {
            double[] a = e.getValue();
            Handler t = topOf.get(e.getKey());
            sb.append("M\t").append(clean(e.getKey())).append('\t').append(fmt(a[0] / 1e6 / f)).append('\t')
                .append(fmt(slowFrameMs > 0 ? 100.0 * a[1] / 1e6 / slowFrameMs : 0)).append('\t').append((long) a[2]).append('\t')
                .append(clean(t == null ? "" : t.event + " " + shortWhere(t.where))).append('\n');
        }
        return sb.toString();
    }

    // Total length of the frames counted as the slowest 1%
    private static double slowFrameTimeMs() {
        if (recFrames == 0) return 0;
        float[] s = Arrays.copyOf(recMs, recFrames);
        Arrays.sort(s);
        int n1 = Math.max(1, recFrames / 100);
        double t = 0;
        for (int i = recFrames - n1; i < recFrames; i++) t += s[i];
        return t;
    }

    /** Writes the report as plain text under Zomboid/PZO; returns the path or "". */
    private static String export() {
        String report = reportText();
        if (report.isEmpty()) return "";
        try {
            File dir = new File(PZOEngineBridge.getZomboidDir(), "PZO");
            if (!dir.isDirectory() && !dir.mkdirs()) return "";
            File out = new File(dir, "profile_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".txt");
            try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8)) {
                w.println("PZO performance recording, PZO " + UpdateChecker.CURRENT_VERSION);
                w.println("Hardware: " + HardwareProbe.getSummary());
                w.println();
                boolean modsHeading = false;
                for (String line : report.split("\n")) {
                    String[] c = line.split("\t", -1);
                    if ("M".equals(c[0]) && !modsHeading) {
                        modsHeading = true;
                        w.println();
                        w.println("Lua event cost by mod (@game = the game's own scripts):");
                    }
                    switch (c[0]) {
                        case "S" -> w.println(String.format(Locale.ROOT,
                            "%s s, %s frames: %s FPS average, 1%% low %s FPS, 99th percentile %s ms, worst %s ms, %s frames over 33 ms, GC pauses %s ms in total (longest %s ms)",
                            c[7], c[6], c[1], c[2], c[3], c[4], c[5], c[8], c[9]));
                        case "B" -> {
                            w.println(String.format(Locale.ROOT,
                                "Per frame: update %s ms, chunk streaming %s ms (of which other mods' map-load scripts %s ms), Lua events %s ms, render %s ms, other/waiting %s ms, GC %s ms; render thread present %s ms",
                                c[1], c[2], c.length > 8 ? c[8] : "?", c[3], c[4], c[5], c[6], c[7]));
                            w.println(MapLoadScriptPacer.summary());
                            w.println(ShaderPatchHost.summary());
                        }
                        case "C" -> w.println(String.format(Locale.ROOT,
                            "Scene: zombies %s average (%s at most), animals %s average (%s at most), driving %s%% of the time, raining %s%%, Java heap peak %s%% of %s MB",
                            c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]));
                        case "K" -> w.println(String.format(Locale.ROOT,
                            "Smart FPS cap: %s (steady %s FPS, median %s FPS, %s hitch frames for %s s, refresh %s Hz, game limit during recording %s, %s)",
                            "0".equals(c[1]) ? "none" : c[1] + " FPS", c[2], c[3], c[4], c[5], c[6], "0".equals(c[7]) ? "off" : c[7], c[8]));
                        case "F" -> w.println(String.format(Locale.ROOT,
                            "  slow frame at %s s: %s ms (update %s, chunks %s%s, Lua %s, render %s, other %s, GC %s%s)%s",
                            c[1], c[2], c[3], c[4], c.length > 12 ? " incl. mod map-load scripts " + c[12] : "", c[5], c[6], c[7], c[8], "1".equals(c[9]) ? ", saving" : "",
                            c.length > 11 && !c[11].isEmpty() ? "  top Lua: " + c[11] + " " + c[10] + " ms" : ""));
                        case "M" -> w.println(String.format(Locale.ROOT, "  %-40s %8s ms/frame  %6s%% of slowest 1%%  %10s calls  %s",
                            c[1], c[2], c[3], c[4], c.length > 5 ? c[5] : ""));
                        default -> { }
                    }
                    if ("B".equals(c[0])) {
                        w.println();
                        w.println("Slowest frames:");
                    }
                }
            }
            lastExport = out.getAbsolutePath();
            return lastExport;
        } catch (Throwable t) {
            PZOLogger.warn("[LiveProfiler] Export failed: " + t);
            return "";
        }
    }

    // Lua entry point

    /** PZOEngine.profiler(op, arg): "live" on/off, "poll", "start" seconds, "stop", "state", "report", "export". */
    public static Object dispatch(String op, Object arg) {
        try {
            switch (op) {
                case "live" -> {
                    live = Boolean.TRUE.equals(arg);
                    refreshOn();
                    return Boolean.TRUE;
                }
                case "poll" -> {
                    return on ? liveText() : "";
                }
                case "start" -> {
                    startRecording(arg instanceof Number num ? num.intValue() : 60);
                    return Boolean.TRUE;
                }
                case "stop" -> {
                    stopRecording();
                    return Boolean.TRUE;
                }
                case "state" -> {
                    if (recording) return "recording";
                    return reportReady ? "done" : "idle";
                }
                case "report" -> {
                    return reportText();
                }
                case "export" -> {
                    return export();
                }
                default -> {
                    return Boolean.FALSE;
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("[LiveProfiler] " + op + " failed: " + t);
            return Boolean.FALSE;
        }
    }

    // Helpers

    // My Lua mod's folder, as modOf returns it for both the Workshop staging copy and the subscribed one
    private static final String SELF_MOD = "MPOptimiser";

    // Mod folder from a Lua file path; "@game" for the game's own scripts
    static String modOf(String where) {
        String p = where.replace('\\', '/');
        int m = p.lastIndexOf("/mods/");
        if (m >= 0) {
            int end = p.indexOf('/', m + 6);
            return end > m + 6 ? p.substring(m + 6, end) : p.substring(m + 6);
        }
        if (p.contains("media/lua/")) return "@game";
        return "@other";
    }

    private static String shortWhere(String where) {
        int slash = where.lastIndexOf('/');
        return slash >= 0 ? where.substring(slash + 1) : where;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    // Two decimals without String.format, which is slow enough to show up in the overlay
    private static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0.00";
        long c = Math.round(Math.abs(v) * 100.0);
        long frac = c % 100;
        return (v < 0 && c != 0 ? "-" : "") + (c / 100) + (frac < 10 ? ".0" : ".") + frac;
    }

    // Patches

    // The timing sits inside logic() and renderInternal(), not round the calls in frameStep: a mod that drives the frame
    // itself (Viewpoint's fixed tick calls them from its own loop) left update and render at 0.00 in the report.
    private static boolean wrapMethod(ClassNode node, String name, String kind) {
        MethodNode m = AsmSupport.findMethod(node, name, "()V");
        if (m == null || (m.access & Opcodes.ACC_STATIC) == 0) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/LiveProfiler", kind + "End", "()V", false));
            }
        }
        m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/LiveProfiler", kind + "Begin", "()V", false));
        return true;
    }

    /** GameWindow.logic(): logicBegin / logicEnd. An exception out of it is closed by onFrame. */
    static boolean patchLogic(ClassNode node) {
        return wrapMethod(node, "logic", "logic");
    }

    /** GameWindow.renderInternal(): renderBegin / renderEnd. */
    static boolean patchRender(ClassNode node) {
        return wrapMethod(node, "renderInternal", "render");
    }

    /** GameWindow.save: a marker, so a frame that saved is reported as one. */
    static boolean patchSave(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            if ("save".equals(m.name) && (m.access & Opcodes.ACC_STATIC) != 0) {
                InsnList pre = new InsnList();
                pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/LiveProfiler", "saveBegin", "()V", false));
                m.instructions.insert(pre);
                n++;
            }
        }
        return n > 0;
    }

    /** IsoCell.updateInternal: IsoChunkMap.update() timed as chunk streaming. */
    static boolean patchCell(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            n += AsmSupport.redirectCalls(m, "zombie/iso/IsoChunkMap", "update", "()V",
                "com/pzoptimizer/LiveProfiler", "chunkUpdate", "(Lzombie/iso/IsoChunkMap;)V");
        }
        return n > 0;
    }

    /** IngameState: IsoChunkMap.update() and ProcessChunkPos(character) timed as chunk streaming. */
    static boolean patchIngameState(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            n += AsmSupport.redirectCalls(m, "zombie/iso/IsoChunkMap", "update", "()V",
                "com/pzoptimizer/LiveProfiler", "chunkUpdate", "(Lzombie/iso/IsoChunkMap;)V");
            n += AsmSupport.redirectCalls(m, "zombie/iso/IsoChunkMap", "ProcessChunkPos", "(Lzombie/characters/IsoGameCharacter;)V",
                "com/pzoptimizer/LiveProfiler", "processChunkPos", "(Lzombie/iso/IsoChunkMap;Lzombie/characters/IsoGameCharacter;)V");
        }
        return n > 0;
    }
}
