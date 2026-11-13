package com.pzoptimizer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.ShaderUnit;

/**
 * Shader source patches sent by other mods (PZO Enhancements) through PZOApi. Each patch names the shader files it
 * edits, a piece of text that has to occur exactly once in that file, and what replaces it. ${NAME} in the
 * replacement is filled from the defines the mod sets, so a slider only changes a define and the shader is rebuilt.
 *
 * Patches run inside ShaderUnit.compile (via ShaderEarlyDepth.load), after the game's own preprocessing. A patched
 * unit is test-compiled first; if the driver rejects it, or the text isn't found because a game update changed the
 * shader, the game's own source is used and the patch reports why.
 *
 * Registrations are staged and only take effect on commit, so a Lua reload (reset, then every mod registers again)
 * doesn't rebuild anything unless the result differs. Programs that loaded a changed file are flagged with the
 * same sourceFilesChanged bit the game's debug file watcher uses, and rebuild the next time they're bound.
 */
public final class ShaderPatchHost {

    public static final String OFF = "off", WAITING = "waiting", APPLIED = "applied", NO_MATCH = "nomatch",
        COMPILE_FAILED = "compilefailed", CONFLICT = "conflict", UNKNOWN = "unknown";

    private static final class Patch {
        final String id;
        final Set<String> files;
        final String find, replace;
        boolean on;

        Patch(String id, Set<String> files, String find, String replace) {
            this.id = id;
            this.files = files;
            this.find = find;
            this.replace = replace;
        }
    }

    private static final class State {
        final Map<String, Patch> patches;
        final Map<String, String> defines;

        State(Map<String, Patch> patches, Map<String, String> defines) {
            this.patches = patches;
            this.defines = defines;
        }
    }

    private static volatile State committed = new State(Collections.emptyMap(), Collections.emptyMap());
    // Render thread only, bumped on every shader unit compile
    static volatile int buildGeneration;
    // Game thread only (Lua)
    private static final Map<String, Patch> staged = new LinkedHashMap<>();
    private static final Map<String, String> stagedDefines = new HashMap<>();

    private static final Map<String, String> status = new ConcurrentHashMap<>();
    // Base file name -> programs that compiled it
    private static final Map<String, Map<ShaderProgram, Boolean>> programsByFile = new HashMap<>();

    private static final class H {
        static final MethodHandle PARENT = parentGetter();
        static final MethodHandle CHANGED = changedSetter();
    }

    private ShaderPatchHost() {}

    private static MethodHandle parentGetter() {
        try {
            return MethodHandles.privateLookupIn(ShaderUnit.class, MethodHandles.lookup()).findGetter(ShaderUnit.class, "parentProgram", ShaderProgram.class);
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderPatch] ShaderUnit.parentProgram not found, live rebuilds off: " + t);
            return null;
        }
    }

    private static MethodHandle changedSetter() {
        try {
            return MethodHandles.privateLookupIn(ShaderProgram.class, MethodHandles.lookup()).findSetter(ShaderProgram.class, "sourceFilesChanged", boolean.class);
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderPatch] ShaderProgram.sourceFilesChanged not found, live rebuilds off: " + t);
            return null;
        }
    }

    static String baseName(String fileName) {
        String n = fileName.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        return (slash >= 0 ? n.substring(slash + 1) : n).toLowerCase(Locale.ROOT);
    }

    // ModApi ops, game thread

    static void reset() {
        staged.clear();
        stagedDefines.clear();
    }

    static boolean add(String id, String files, String find, String replace) {
        if (id == null || id.isEmpty() || files == null || find == null || find.isEmpty() || replace == null) return false;
        Set<String> set = new java.util.HashSet<>();
        for (String f : files.split("[;,]")) {
            String t = f.trim();
            if (!t.isEmpty()) set.add(baseName(t));
        }
        if (set.isEmpty()) return false;
        Patch old = staged.get(id);
        Patch p = new Patch(id, set, normalize(find), normalize(replace));
        p.on = old != null && old.on;
        staged.put(id, p);
        return true;
    }

    static boolean setOn(String id, boolean on) {
        Patch p = staged.get(id);
        if (p == null) return false;
        p.on = on;
        return true;
    }

    static void define(String name, String value) {
        if (name == null || name.isEmpty()) return;
        if (value == null) stagedDefines.remove(name);
        else stagedDefines.put(name, value);
    }

    /**
     * Whether a committed, switched-on patch puts s into a shader (a uniform name, say). For engine code that serves
     * many programs at once and can't ask each one; cheap enough for once a second, not per draw.
     */
    public static boolean liveTextContains(String s) {
        if (!anyLive || s == null) return false;
        for (Patch p : committed.patches.values()) {
            if (p.on && p.replace != null && p.replace.contains(s)) return true;
        }
        return false;
    }

    /** Whether any mod has shader patches live; hooks that only serve patched shaders return straight away without. */
    public static boolean anyLive() {
        return anyLive;
    }

    // Set on each commit, so the per-draw check is one field read
    private static volatile boolean anyLive;

    static String statusOf(String id) {
        if (id == null) return UNKNOWN;
        Patch p = committed.patches.get(id);
        if (p == null) return UNKNOWN;
        if (!p.on) return OFF;
        return status.getOrDefault(id, WAITING);
    }

    /** Makes the staged patches live and flags the programs whose source changes. Returns how many were flagged. */
    public static int commit() {
        Map<String, Patch> copy = new LinkedHashMap<>();
        for (Patch p : staged.values()) {
            Patch c = new Patch(p.id, p.files, p.find, p.replace);
            c.on = p.on;
            copy.put(c.id, c);
        }
        State next = new State(Collections.unmodifiableMap(copy), Collections.unmodifiableMap(new HashMap<>(stagedDefines)));
        State prev = committed;

        Set<String> changedFiles = new java.util.HashSet<>();
        Set<String> files = new java.util.HashSet<>();
        for (Patch p : prev.patches.values()) files.addAll(p.files);
        for (Patch p : next.patches.values()) files.addAll(p.files);
        for (String f : files) {
            if (!signature(prev, f).equals(signature(next, f))) changedFiles.add(f);
        }
        committed = next;
        boolean live = false;
        for (Patch p : next.patches.values()) live |= p.on;
        anyLive = live;
        for (Patch p : next.patches.values()) {
            if (!p.on || !Collections.disjoint(p.files, changedFiles)) status.remove(p.id);
        }
        if (changedFiles.isEmpty()) return 0;
        int n = flagPrograms(changedFiles);
        PZOLogger.info("[ShaderPatch] " + changedFiles + " changed, " + n + " shader program(s) will rebuild");
        return n;
    }

    // What a file's patched source depends on: the live patches touching it with their filled-in text
    private static String signature(State s, String file) {
        StringBuilder sb = new StringBuilder();
        for (Patch p : s.patches.values()) {
            if (p.on && p.files.contains(file)) sb.append(p.id).append('\u0001').append(p.find).append('\u0001').append(fill(p.replace, s.defines)).append('\u0002');
        }
        return sb.toString();
    }

    private static int flagPrograms(Set<String> files) {
        if (H.CHANGED == null) return 0;
        List<ShaderProgram> list = new ArrayList<>();
        synchronized (programsByFile) {
            for (String f : files) {
                Map<ShaderProgram, Boolean> m = programsByFile.get(f);
                if (m != null) list.addAll(m.keySet());
            }
        }
        int n = 0;
        for (ShaderProgram prog : list) {
            if (prog == null) continue;
            try {
                H.CHANGED.invokeExact(prog, true);
                n++;
            } catch (Throwable t) {
                PZOLogger.warn("[ShaderPatch] couldn't flag " + prog.getName() + ": " + t);
            }
        }
        return n;
    }

    // Render thread, from ShaderUnit.compile

    static String apply(ShaderUnit unit, String fileName, String code) {
        // Anything caching uniform locations re-queries after a rebuild; drivers can hand the same program id back
        buildGeneration++;
        if (code == null || fileName == null) return code;
        String base = baseName(fileName);
        remember(unit, base);
        State s = committed;
        if (s.patches.isEmpty()) return code;

        List<Patch> applied = new ArrayList<>(2);
        String out = patch(s, base, code, applied);
        if (applied.isEmpty()) return code;

        String err = testCompile(unit, out);
        if (err != null) {
            for (Patch p : applied) status.put(p.id, COMPILE_FAILED);
            PZOLogger.warn("[ShaderPatch] " + base + " didn't compile with " + ids(applied) + ", using the game's shader: " + err.trim());
            return code;
        }
        for (Patch p : applied) status.put(p.id, APPLIED);
        PZOLogger.info("[ShaderPatch] " + base + ": " + ids(applied));
        return out;
    }

    // The live patches for this file applied to code; the ones that matched go into applied. Every patch is matched
    // against the game's text, not another patch's output. Two that edit overlapping text can't both be right, so
    // the one registered first wins and the other reports CONFLICT.
    private static String patch(State s, String base, String code, List<Patch> applied) {
        String src = null;
        List<int[]> spans = new ArrayList<>(2);
        List<Patch> chosen = new ArrayList<>(2);
        for (Patch p : s.patches.values()) {
            if (!p.on || !p.files.contains(base)) continue;
            if (src == null) src = normalize(code);
            int at = src.indexOf(p.find);
            if (at < 0 || src.indexOf(p.find, at + 1) >= 0) {
                // Missing here can still match another of its files; only report when no file had it
                status.putIfAbsent(p.id, NO_MATCH);
                PZOLogger.warn("[ShaderPatch] " + p.id + ": text not found exactly once in " + base + ", left as the game has it");
                continue;
            }
            int end = at + p.find.length();
            Patch clash = null;
            for (int i = 0; i < spans.size(); i++) {
                if (at < spans.get(i)[1] && spans.get(i)[0] < end) clash = chosen.get(i);
            }
            if (clash != null) {
                status.put(p.id, CONFLICT);
                PZOLogger.warn("[ShaderPatch] " + p.id + " edits the same part of " + base + " as " + clash.id + ", left off");
                continue;
            }
            spans.add(new int[] {at, end});
            chosen.add(p);
        }
        if (chosen.isEmpty()) return code;
        // Last position first, so earlier offsets still hold
        Integer[] order = new Integer[chosen.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(spans.get(b)[0], spans.get(a)[0]));
        StringBuilder out = new StringBuilder(src);
        for (int i : order) out.replace(spans.get(i)[0], spans.get(i)[1], fill(chosen.get(i).replace, s.defines));
        applied.addAll(chosen);
        return out.toString();
    }

    /** Offline checks: the committed patches applied to an already preprocessed file, no GL involved. */
    static String patchForTest(String fileName, String code, List<String> appliedIds) {
        List<Patch> applied = new ArrayList<>();
        String out = patch(committed, baseName(fileName), code, applied);
        for (Patch p : applied) appliedIds.add(p.id);
        return out;
    }

    private static void remember(ShaderUnit unit, String base) {
        if (H.PARENT == null || unit == null) return;
        try {
            ShaderProgram prog = (ShaderProgram) H.PARENT.invokeExact(unit);
            if (prog == null) return;
            synchronized (programsByFile) {
                programsByFile.computeIfAbsent(base, k -> new WeakHashMap<>()).put(prog, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
        }
    }

    // Compiles the patched source on its own and returns the driver's log on failure, null when it compiled. Every
    // unit compiles standalone in this game (ShaderUnit.combineShaderSources is never set).
    private static String testCompile(ShaderUnit unit, String src) {
        try {
            int type = unit.getType() == ShaderUnit.Type.Vert ? 0x8B31 : 0x8B30;
            int s = org.lwjgl.opengl.GL20.glCreateShader(type);
            if (s == 0) return null;
            try {
                org.lwjgl.opengl.GL20.glShaderSource(s, src);
                org.lwjgl.opengl.GL20.glCompileShader(s);
                if (org.lwjgl.opengl.GL20.glGetShaderi(s, 0x8B81) != 0) return null;
                String log = org.lwjgl.opengl.GL20.glGetShaderInfoLog(s, 4096);
                return log == null || log.isEmpty() ? "compile failed" : log;
            } finally {
                org.lwjgl.opengl.GL20.glDeleteShader(s);
            }
        } catch (Throwable t) {
            return "test compile threw " + t;
        }
    }

    /** ${NAME} filled from the committed defines, for bake passes. */
    public static String fillCommitted(String text) {
        return fill(text == null ? "" : text, committed.defines);
    }

    private static String fill(String text, Map<String, String> defines) {
        if (text.indexOf("${") < 0) return text;
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("${", i);
            if (start < 0) break;
            int end = text.indexOf('}', start + 2);
            if (end < 0) break;
            sb.append(text, i, start);
            String v = defines.get(text.substring(start + 2, end));
            sb.append(v != null ? v : "0.0");
            i = end + 1;
        }
        sb.append(text, i, text.length());
        return sb.toString();
    }

    // The game's shader files use CRLF; patches are matched and written with plain \n
    private static String normalize(String s) {
        return s.indexOf('\r') < 0 ? s : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String ids(List<Patch> list) {
        StringBuilder sb = new StringBuilder();
        for (Patch p : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.id);
        }
        return sb.toString();
    }

    public static String summary() {
        State s = committed;
        int on = 0;
        for (Patch p : s.patches.values()) if (p.on) on++;
        StringBuilder sb = new StringBuilder("Shader patches from mods: " + s.patches.size() + " registered, " + on + " on");
        for (Patch p : s.patches.values()) {
            if (p.on) sb.append("; ").append(p.id).append('=').append(statusOf(p.id));
        }
        return sb.toString();
    }
}
