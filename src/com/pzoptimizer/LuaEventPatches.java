package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InvokeDynamicInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event.trigger (every Lua event the game fires) does two needless things per callback: it builds the profiler label
 * "Lua - " + name as a new string even with the profiler off, and after each callback it asks callbacks.contains
 * (a linear scan) whether the callback is still registered. The label is now built once per event name, and the
 * scan is skipped when the callback is still at the index it was called from (the scan's answer is then true); any
 * other case runs the scan as stock.
 *
 * In addition, LuaEventPatches provides bytecode-level rate pacing and LOD governors:
 * 1. Mod OnTick / OnPlayerUpdate 60Hz Rate Clamp: 3rd-party mod per-frame handlers are paced to 60Hz on average and
 *    staggered across frames, eliminating frametime spikes on 120Hz/144Hz/240Hz monitors. At 60 FPS or below nothing is
 *    skipped, and every handler runs on a frame with a key or mouse button press or release.
 * 2. Mod Zombie Script LOD: 3rd-party zombie mod scripts run every frame near a local player, and are smoothly staggered
 *    across frames when far away (cutting 70-80% mod overhead in large hordes).
 *
 * While a benchmark runs, every callback is also timed and the total is attributed to "event file:function", so the
 * results show which Lua handlers (the game's or a mod's) cost the most, e.g. on LoadGridsquare during chunk loading.
 */
public final class LuaEventPatches {

    public static volatile boolean active = true;
    public static volatile boolean timing = false;
    public static volatile boolean modPaced = true;
    public static volatile boolean zombieLod = true;

    private static final ConcurrentHashMap<String, String> labels = new ConcurrentHashMap<>();

    // Game thread only (Lua runs there); a handler may fire another event, so the current name is a stack
    private static final String[] eventStack = new String[64];
    private static int depth;
    private static final Map<String, long[]> handlerTimes = new HashMap<>(); // key -> {nanos, calls}

    // 60Hz Mod Event Pacing (Main thread only). Each handler keeps a schedule advancing by one period per run, so the
    // average rate is 60Hz at any frame rate; the slack keeps a locked 60 FPS with frame jitter from skipping at all.
    private static final long PACE_PERIOD_NS = 16_666_667L, PACE_SLACK_NS = 2_000_000L;
    // Next due time per handler: OnTick uses slot 0, OnPlayerUpdate one slot per local player (split-screen)
    private static final Map<Object, long[]> tickDue = new WeakHashMap<>(), playerDue = new WeakHashMap<>();
    private static final ConcurrentHashMap<se.krka.kahlua.vm.Prototype, Boolean> modPrototypeCache = new ConcurrentHashMap<>();
    private static int edgeFrame = Integer.MIN_VALUE;
    private static boolean edgeThisFrame;

    // GameKeyboard keeps its key states private; held lazily so this class never loads game classes early
    private static final class Keys {
        static final VarHandle DOWN, LAST_DOWN;
        static {
            VarHandle d = null, l = null;
            try {
                MethodHandles.Lookup k = MethodHandles.privateLookupIn(zombie.input.GameKeyboard.class, MethodHandles.lookup());
                d = k.findStaticVarHandle(zombie.input.GameKeyboard.class, "down", boolean[].class);
                l = k.findStaticVarHandle(zombie.input.GameKeyboard.class, "lastDown", boolean[].class);
            } catch (Throwable t) {
                PZOLogger.warn("[LuaEventPatches] keyboard state not readable, mod handlers will not be paced: " + t);
            }
            DOWN = d;
            LAST_DOWN = l;
        }
    }

    // Zombie Script LOD frame ticker
    private static long zombieLODTick = 0;

    static {
        loadStartupOverrides();
    }

    private static void loadStartupOverrides() {
        try {
            String sysPaced = System.getProperty("pzo.mod_paced");
            if (sysPaced != null) {
                modPaced = !"0".equals(sysPaced) && !"false".equalsIgnoreCase(sysPaced);
            }
            String sysLod = System.getProperty("pzo.zombie_lod");
            if (sysLod != null) {
                zombieLod = !"0".equals(sysLod) && !"false".equalsIgnoreCase(sysLod);
            }

            File[] candidateFiles = new File[] {
                new File(System.getProperty("user.home"), "Zomboid" + File.separator + "Lua" + File.separator + "pzo-bench.txt"),
                new File(System.getProperty("user.home"), "Zomboid" + File.separator + "pzo-bench.txt"),
                new File("pzo-bench.txt"),
                new File("pzo-args.txt")
            };
            for (File f : candidateFiles) {
                if (f.isFile()) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            int eq = line.indexOf('=');
                            if (eq > 0) {
                                String key = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
                                String val = line.substring(eq + 1).trim();
                                if ("mod_paced".equals(key)) {
                                    modPaced = !"0".equals(val) && !"false".equalsIgnoreCase(val);
                                    PZOLogger.info("[LuaEventPatches] Startup override: mod_paced = " + modPaced);
                                } else if ("zombie_lod".equals(key)) {
                                    zombieLod = !"0".equals(val) && !"false".equalsIgnoreCase(val);
                                    PZOLogger.info("[LuaEventPatches] Startup override: zombie_lod = " + zombieLod);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Non-critical startup read
        }
    }

    private LuaEventPatches() {}

    public static String label(String name) {
        if (!active) return "Lua - " + name;
        String l = labels.get(name);
        if (l == null) {
            l = "Lua - " + name;
            labels.put(name, l);
        }
        return l;
    }

    public static boolean stillRegistered(ArrayList<?> callbacks, Object closure, int index) {
        if (active && index >= 0 && index < callbacks.size() && callbacks.get(index) == closure) return true;
        return callbacks.contains(closure);
    }

    public static void enter(String name) {
        if ("OnTick".equals(name)) {
            zombieLODTick++;
        }
        if (depth < eventStack.length) eventStack[depth] = name;
        depth++;
    }

    public static void exit() {
        if (depth > 0) depth--;
    }

    public static boolean isModClosure(Object closure) {
        if (!(closure instanceof se.krka.kahlua.vm.LuaClosure lc)) return false;
        if (lc.prototype == null) return false;
        Boolean cached = modPrototypeCache.get(lc.prototype);
        if (cached != null) return cached.booleanValue();

        String file = lc.prototype.filename != null ? lc.prototype.filename : lc.prototype.file;
        if (file == null) {
            modPrototypeCache.put(lc.prototype, Boolean.FALSE);
            return false;
        }
        String f = file.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        boolean isMod = (f.contains("/mods/") || f.contains("workshop")) && !f.contains("mpoptimiser");
        // A 3D camera mod may move the camera from these events; pacing it would step the view above 60 FPS
        if (isMod && ViewpointStanddownGovernor.getMode() != ViewpointStanddownGovernor.Mode.DISABLED
                && ViewpointStanddownGovernor.isViewpointModId(f)) {
            isMod = false;
        }
        if (isMod && (isExemptMod(f) || ModApi.isExemptPath(f) || ModApi.isExemptPrototype(lc.prototype))) {
            isMod = false;
        }
        modPrototypeCache.put(lc.prototype, Boolean.valueOf(isMod));
        return isMod;
    }

    /** Forgets which handlers are mods' ones; ModApi calls it when a mod's exemption changes. */
    static void clearModCache() {
        modPrototypeCache.clear();
    }

    // Mods whose handlers must run on every event, matched on the lower-cased script path (other mods ask through
    // PZOApi.exemptMod instead):
    // - Real Limbs times its kick hits in OnTick and runs its Java heartbeat from OnPlayerUpdate.
    // - Project A-Life's NPCs are zombie shells: its OnZombieUpdate fence re-applies NPC behaviour on every update (a
    //   staggered NPC runs plain zombie AI in between), and its OnTick animation client applies a fixed number of
    //   queued NPC animations per tick before they expire.
    private static final String[] EXEMPT_MOD_PATHS = {"/reallimbs/", "/projectalife"};

    private static boolean isExemptMod(String path) {
        for (String p : EXEMPT_MOD_PATHS) {
            if (path.contains(p)) return true;
        }
        return false;
    }

    /** True when this handler call should be skipped to hold it at 60Hz on average. */
    private static boolean paceSkip(Map<Object, long[]> dueMap, Object closure, int slot) {
        long now = System.nanoTime();
        long[] due = dueMap.get(closure);
        if (due == null) dueMap.put(closure, due = new long[4]);
        long next = due[slot];
        if (next == 0) {
            // Staggered first slot so handlers registered together do not all land on the same frame
            due[slot] = now + PACE_PERIOD_NS - (System.identityHashCode(closure) & 3) * 4_000_000L;
            return false;
        }
        if (now + PACE_SLACK_NS < next) {
            // isKeyPressed / isButtonPressed only hold for the frame of the edge, so a skipped handler would miss it;
            // run early without moving the schedule
            return !inputEdgeThisFrame();
        }
        next += PACE_PERIOD_NS;
        due[slot] = next < now ? now + PACE_PERIOD_NS : next;
        return false;
    }

    private static boolean inputEdgeThisFrame() {
        zombie.iso.IsoWorld world = zombie.iso.IsoWorld.instance;
        int frame = world != null ? world.getFrameNo() : Integer.MIN_VALUE + 1;
        if (frame != edgeFrame) {
            edgeFrame = frame;
            edgeThisFrame = scanInputEdges();
        }
        return edgeThisFrame;
    }

    private static boolean scanInputEdges() {
        try {
            if (Keys.DOWN == null) return true;
            boolean[] down = (boolean[]) Keys.DOWN.get(), last = (boolean[]) Keys.LAST_DOWN.get();
            if (down != null && last != null && !Arrays.equals(down, last)) return true;
            boolean[] b = zombie.input.Mouse.buttonDownStates, p = zombie.input.Mouse.buttonPrevStates;
            return b != null && p != null && !Arrays.equals(b, p);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Squared distance from the zombie to the nearest local player in its cell, or -1 when there is none. */
    private static float nearestLocalPlayerDistSq(zombie.characters.IsoZombie z) {
        zombie.characters.IsoPlayer[] players = zombie.characters.IsoPlayer.players;
        if (players == null) return -1.0f;
        float best = -1.0f;
        for (zombie.characters.IsoPlayer player : players) {
            if (player == null || player.getCell() != z.getCell()) continue;
            float dx = z.getX() - player.getX();
            float dy = z.getY() - player.getY();
            float d = dx * dx + dy * dy;
            if (best < 0.0f || d < best) best = d;
        }
        return best;
    }

    public static void call(se.krka.kahlua.integration.LuaCaller caller, se.krka.kahlua.vm.KahluaThread thread, Object closure,
                            Object[] params) {
        String event = depth > 0 && depth <= eventStack.length ? eventStack[depth - 1] : "?";

        // 1. Mod OnTick / OnPlayerUpdate 60Hz Rate Clamp & Frame Spreading
        if (modPaced && isModClosure(closure)) {
            if ("OnTick".equals(event)) {
                if (paceSkip(tickDue, closure, 0)) return;
            } else if ("OnPlayerUpdate".equals(event)) {
                int slot = params != null && params.length > 0 && params[0] instanceof zombie.characters.IsoPlayer p
                    ? p.getPlayerNum() & 3 : 0;
                if (paceSkip(playerDue, closure, slot)) return;
            }
        }

        // 2. Mod scripts on zombies LOD: every frame near a local player, staggered when far
        if (zombieLod && "OnZombieUpdate".equals(event)) {
            if (isModClosure(closure) && params != null && params.length > 0 && params[0] instanceof zombie.characters.IsoZombie z
                    && !ModApi.isFullRate(z)) {
                float distSq = nearestLocalPlayerDistSq(z);
                if (distSq >= 0.0f) {
                    // Proximity < 15 tiles: run every frame!
                    if (distSq > 225.0f) {
                        long tick = zombieLODTick;
                        int zid = z.zombieId != 0 ? z.zombieId : System.identityHashCode(z);
                        if (distSq <= 900.0f) {
                            // Mid-range (15 to 30 tiles): stagger every 3 frames
                            if (((zid + tick) % 3) != 0) {
                                return;
                            }
                        } else {
                            // Far-range (> 30 tiles): stagger every 6 frames
                            if (((zid + tick) % 6) != 0) {
                                return;
                            }
                        }
                    }
                }
            }
        }

        if (LiveProfiler.on) {
            long t0 = LiveProfiler.luaBegin();
            try {
                caller.protectedCallVoid(thread, closure, params);
            } finally {
                LiveProfiler.luaEnd(closure, event, t0);
            }
            return;
        }
        if (!timing) {
            caller.protectedCallVoid(thread, closure, params);
            return;
        }

        long start = System.nanoTime();
        try {
            caller.protectedCallVoid(thread, closure, params);
        } finally {
            long dt = System.nanoTime() - start;
            String where = "?";
            if (closure instanceof se.krka.kahlua.vm.LuaClosure lc && lc.prototype != null) {
                String file = lc.prototype.filename != null ? lc.prototype.filename : lc.prototype.file;
                where = (file == null ? "?" : file.replace('\\', '/')) + ":" + lc.prototype.name;
            }
            long[] t = handlerTimes.computeIfAbsent(event + " " + where, k -> new long[2]);
            t[0] += dt;
            t[1]++;
        }
    }

    /** The costliest handlers as a JSON object of "event file:function": [ms, calls]. */
    public static String report(int top) {
        StringBuilder sb = new StringBuilder("{");
        handlerTimes.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(top)
            .forEach(e -> sb.append(sb.length() > 1 ? ",\n    " : "\n    ").append('"')
                .append(e.getKey().replace("\\", "/").replace("\"", "'")).append("\": [")
                .append(String.format(java.util.Locale.ROOT, "%.1f", e.getValue()[0] / 1e6)).append(", ").append(e.getValue()[1]).append(']'));
        return sb.append(sb.length() > 1 ? "\n  }" : "}").toString();
    }

    static boolean patchEvent(ClassNode node) {
        MethodNode trigger = null;
        for (MethodNode m : node.methods) {
            if (m.name.equals("trigger") && m.desc.endsWith("[Ljava/lang/Object;)Z")) trigger = m;
        }
        if (trigger == null) return false;
        String self = "com/pzoptimizer/LuaEventPatches";
        int calls = 0, scans = 0, labelsFound = 0;
        for (AbstractInsnNode insn = trigger.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode indy && "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
                    && "(Ljava/lang/String;)Ljava/lang/String;".equals(indy.desc) && indy.bsmArgs.length > 0
                    && String.valueOf(indy.bsmArgs[0]).startsWith("Lua - ")) {
                MethodInsnNode l = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "label", "(Ljava/lang/String;)Ljava/lang/String;", false);
                trigger.instructions.set(indy, l);
                insn = l;
                labelsFound++;
            } else if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "se/krka/kahlua/integration/LuaCaller".equals(mi.owner) && "protectedCallVoid".equals(mi.name)
                    && "(Lse/krka/kahlua/vm/KahluaThread;Ljava/lang/Object;[Ljava/lang/Object;)V".equals(mi.desc)) {
                MethodInsnNode c = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "call",
                    "(Lse/krka/kahlua/integration/LuaCaller;Lse/krka/kahlua/vm/KahluaThread;Ljava/lang/Object;[Ljava/lang/Object;)V", false);
                trigger.instructions.set(mi, c);
                insn = c;
                calls++;
            } else if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL && "java/util/ArrayList".equals(mi.owner)
                    && "contains".equals(mi.name)) {
                int index = loopIndexBefore(mi);
                if (index < 0) continue;
                trigger.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ILOAD, index));
                MethodInsnNode s = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "stillRegistered",
                    "(Ljava/util/ArrayList;Ljava/lang/Object;I)Z", false);
                trigger.instructions.set(mi, s);
                insn = s;
                scans++;
            }
        }
        if (calls == 0) return false;
        // Event name on entry, popped before every return; the callbacks run inside, so they see this event
        com.pzoptimizer.shaded.asm.tree.InsnList pre = new com.pzoptimizer.shaded.asm.tree.InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "name", "Ljava/lang/String;"));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "enter", "(Ljava/lang/String;)V", false));
        trigger.instructions.insert(pre);
        for (AbstractInsnNode insn = trigger.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.IRETURN) {
                trigger.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, self, "exit", "()V", false));
            }
        }
        PZOLogger.info("[LuaEventPatches] Event.trigger: " + calls + " callback calls, " + scans + " registration scans, " + labelsFound + " profiler labels");
        return true;
    }

    // The loop index n of "callbacks.get(n)" earlier in the same loop body
    private static int loopIndexBefore(AbstractInsnNode at) {
        for (AbstractInsnNode p = at.getPrevious(); p != null; p = p.getPrevious()) {
            if (p instanceof MethodInsnNode g && "java/util/ArrayList".equals(g.owner) && "get".equals(g.name)) {
                AbstractInsnNode q = g.getPrevious();
                while (q != null && q.getOpcode() < 0) q = q.getPrevious();
                return q instanceof VarInsnNode v && v.getOpcode() == Opcodes.ILOAD ? v.var : -1;
            }
        }
        return -1;
    }
}
