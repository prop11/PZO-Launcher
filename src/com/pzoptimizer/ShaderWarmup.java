package com.pzoptimizer;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every shader program the game will need, ready before play starts (JVM_ShaderWarmup).
 *
 * Each program the game compiles (its own, other mods' shader files, and the ones PZO Enhancements patches) is noted
 * by name and variant (static, instanced) in Zomboid/PZO/shader_programs.txt. When a save has loaded, every listed
 * program the session hasn't made yet is built through the game's own code with the shader cache in probe mode: that
 * gives the exact text the driver would get, with the current settings and PZOE patches, and its cache key, without
 * compiling anything. Every one of them is then made ahead: compiled where the cache doesn't have it (and stored),
 * from its stored binary where it does, together on the driver's own threads (KHR/ARB_parallel_shader_compile; one at
 * a time where the driver has neither). The finished programs wait in ShaderCache's pool, and when the game compiles
 * that program later (first time you aim, open the map, meet an effect) it takes the finished one instead of building
 * its own. That matters on drivers like AMD's, where taking in a stored binary costs nearly as much as compiling.
 * Model shaders are made after that and come straight from the pool.
 *
 * The loading screen waits while this runs (the click to start does nothing yet, the bar bottom right says so), at
 * most MAX_HOLD from the end of loading. The probe throws its programs away and unhooks their file watchers.
 */
public final class ShaderWarmup {

    public static volatile boolean active = true;

    private static final int MAX_LISTED = 3000;
    private static final long MAX_HOLD_NANOS = 120_000_000_000L;
    private static final long SLICE_HOLDING_NANOS = 40_000_000L, SLICE_NANOS = 6_000_000L;
    private static final long RESTART_GAP_NANOS = 20_000_000_000L;

    private static final int IDLE = 0, PROBE = 1, COMPILE = 2, DONE = 3;
    private static volatile int phase = IDLE;

    // "name|static|instanced" made this session (by the game) and listed in the file
    private static final Set<String> seen = ConcurrentHashMap.newKeySet();
    private static final Set<String> listed = ConcurrentHashMap.newKeySet();
    private static volatile boolean listRead;

    // Render thread
    private static boolean probing;
    private static List<String> todo = List.of();
    private static int next;
    private static final List<ShaderCache.Capture> captured = new ArrayList<>();
    private static final Set<String> capturedKeys = new java.util.HashSet<>();
    private static ShaderCache.Batch batch;
    private static volatile boolean queued;

    private static volatile long startedAt, probeNanos, compileStart;
    private static volatile int probed, total;

    // Looked up on first use, never while ShaderProgram is still loading (see ShaderCache.Ids)
    private static final class Fields {
        static final VarHandle IS_STATIC = field("isStatic", boolean.class);
        static final VarHandle IS_INSTANCED = field("isInstanced", boolean.class);
        static final VarHandle WATCHERS = field("fileWatchers", Map.class);
    }

    private ShaderWarmup() {}

    private static VarHandle field(String name, Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(zombie.core.opengl.ShaderProgram.class, MethodHandles.lookup())
                .findVarHandle(zombie.core.opengl.ShaderProgram.class, name, type);
        } catch (Throwable t) {
            return null;
        }
    }

    /** ShaderProgram.compile entry: note the program's name and variant. */
    public static void noteProgram(Object program) {
        if (probing || Fields.IS_STATIC == null || Fields.IS_INSTANCED == null) return;
        try {
            zombie.core.opengl.ShaderProgram p = (zombie.core.opengl.ShaderProgram) program;
            String name = p.getName();
            if (name == null || name.isEmpty() || name.indexOf('|') >= 0) return;
            seen.add(name + "|" + ((boolean) Fields.IS_STATIC.get(p) ? 1 : 0) + "|" + ((boolean) Fields.IS_INSTANCED.get(p) ? 1 : 0));
        } catch (Throwable ignored) {
        }
    }

    /** GameLoadingState.SendDone / Done, through ModelShadersAhead.worldLoaded. */
    static void worldLoaded() {
        if (!active || zombie.core.skinnedmodel.ModelManager.noOpenGL || !ShaderCache.active) return;
        long now = System.nanoTime();
        int ph = phase;
        if ((ph == PROBE || ph == COMPILE) || (startedAt != 0 && now - startedAt < RESTART_GAP_NANOS)) return;
        readList();
        List<String> list = new ArrayList<>();
        for (String s : new TreeSet<>(listed)) if (!seen.contains(s)) list.add(s);
        startedAt = now;
        if (list.isEmpty()) {
            phase = DONE;
            // Nothing to check yet (first run): keep what loading made so far, so a crash doesn't lose it
            writeList();
            return;
        }
        todo = list;
        next = 0;
        probed = 0;
        total = list.size();
        probeNanos = 0;
        phase = PROBE;
        PZOLogger.info("[ShaderWarmup] " + list.size() + " listed shader programs to check against the cache");
    }

    public static boolean busy() {
        int ph = phase;
        return ph == PROBE || ph == COMPILE;
    }

    /** Whether the loading screen waits for shaders (warm-up or model shaders still going, within the time cap). */
    public static boolean holding() {
        if (!active || startedAt == 0 || System.nanoTime() - startedAt > MAX_HOLD_NANOS) return false;
        return busy() || ModelShadersAhead.busy();
    }

    /** GameLoadingState.update, just before it reads the click to start: true keeps the loading screen up. */
    public static boolean holdStart() {
        return holding();
    }

    // Progress for the bar: programs checked, then programs compiled
    public static int total() {
        ShaderCache.Batch b = batch;
        return phase == IDLE ? 0 : total + (b == null ? 0 : b.total());
    }

    public static int done() {
        ShaderCache.Batch b = batch;
        return Math.min(probed, total) + (b == null ? 0 : b.finished());
    }

    /** Main thread, once a frame (from the bar): queues one slice for the render thread. */
    public static void tick() {
        if (!busy() || queued) return;
        queued = true;
        try {
            zombie.core.opengl.RenderThread.queueInvokeOnRenderContext(ShaderWarmup::slice);
        } catch (Throwable t) {
            queued = false;
            phase = DONE;
            PZOLogger.warn("[ShaderWarmup] couldn't reach the render thread: " + t);
        }
    }

    // Render thread
    private static void slice() {
        try {
            long budget = holding() ? SLICE_HOLDING_NANOS : SLICE_NANOS;
            if (phase == PROBE) probe(budget);
            else if (phase == COMPILE) compile(budget);
        } catch (Throwable t) {
            phase = DONE;
            PZOLogger.warn("[ShaderWarmup] stopped: " + t);
        } finally {
            queued = false;
        }
    }

    private static void probe(long budget) {
        if (!ShaderCache.usableOnRenderThread()) {
            phase = DONE;
            return;
        }
        long t0 = System.nanoTime(), end = t0 + budget;
        probing = true;
        ShaderCache.probe = c -> {
            if (!ShaderCache.pooled(c.key) && capturedKeys.add(c.key)) captured.add(c);
        };
        try {
            while (next < todo.size() && System.nanoTime() < end) {
                String[] parts = todo.get(next++).split("\\|");
                probed++;
                if (parts.length != 3) continue;
                try {
                    zombie.core.opengl.ShaderProgram p = zombie.core.opengl.ShaderProgram.createShaderProgram(parts[0], "1".equals(parts[1]), "1".equals(parts[2]), true);
                    unwatch(p);
                    p.destroy();
                } catch (Throwable t) {
                    // A shader file that's gone (mod removed): nothing to make
                }
            }
        } finally {
            ShaderCache.probe = null;
            probing = false;
            probeNanos += System.nanoTime() - t0;
            while (org.lwjgl.opengl.GL11C.glGetError() != org.lwjgl.opengl.GL11C.GL_NO_ERROR) {}
        }
        if (next >= todo.size()) {
            int missing = 0;
            for (ShaderCache.Capture c : captured) if (!c.fromBinary()) missing++;
            PZOLogger.info(String.format("[ShaderWarmup] %d programs checked in %.0f ms: %d to make ahead, %d of them not in the cache",
                probed, probeNanos / 1e6, captured.size(), missing));
            if (captured.isEmpty()) {
                finishAll();
                return;
            }
            // Missing ones first: they take longest and the driver can work on them while the binaries go in
            List<ShaderCache.Capture> order = new ArrayList<>(captured);
            order.sort((a, b) -> Boolean.compare(a.fromBinary(), b.fromBinary()));
            batch = new ShaderCache.Batch(order, true);
            compileStart = System.nanoTime();
            phase = COMPILE;
        }
    }

    private static void compile(long budget) {
        ShaderCache.Batch b = batch;
        b.tick(budget);
        if (b.done()) {
            PZOLogger.info(String.format("[ShaderWarmup] %d programs made ahead in %.0f ms (%s), %d failed, %d waiting for the game",
                b.made, (System.nanoTime() - compileStart) / 1e6, b.parallel() ? "on the driver's threads" : "one at a time",
                b.failed, ShaderCache.poolSize()));
            finishAll();
        }
    }

    private static void finishAll() {
        captured.clear();
        capturedKeys.clear();
        phase = DONE;
        writeList();
    }

    private static void unwatch(zombie.core.opengl.ShaderProgram p) {
        if (Fields.WATCHERS == null) return;
        try {
            Map<?, ?> w = (Map<?, ?>) Fields.WATCHERS.get(p);
            for (Object o : w.values()) zombie.DebugFileWatcher.instance.remove((zombie.PredicatedFileWatcher) o);
            w.clear();
        } catch (Throwable ignored) {
        }
    }

    private static File file() {
        File dir = ZomboidDir.sub("PZO");
        dir.mkdirs();
        return new File(dir, "shader_programs.txt");
    }

    private static synchronized void readList() {
        if (listRead) return;
        listRead = true;
        try {
            File f = file();
            if (f.isFile()) {
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (!s.isEmpty() && !s.startsWith("#") && listed.size() < MAX_LISTED) listed.add(s);
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderWarmup] couldn't read the program list: " + t);
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(ShaderWarmup::writeList, "PZO-ShaderListSave"));
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void writeList() {
        try {
            Set<String> all = new TreeSet<>(listed);
            for (String s : seen) {
                if (all.size() >= MAX_LISTED) break;
                all.add(s);
            }
            listed.addAll(all);
            List<String> lines = new ArrayList<>(all.size() + 1);
            lines.add("# shader programs the game has made: name|static|instanced");
            lines.addAll(new LinkedHashSet<>(all));
            File f = file();
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderWarmup] couldn't write the program list: " + t);
        }
    }

    public static String summary() {
        return "phase=" + phase + " listed=" + listed.size() + " seen=" + seen.size() + " probed=" + probed + "/" + total
            + (batch == null ? "" : " compiled=" + batch.made + "/" + batch.total() + (batch.parallel() ? " parallel" : ""));
    }
}
