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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event.trigger (every Lua event the game fires) does two needless things per callback: it builds the profiler label
 * "Lua - " + name as a new string even with the profiler off, and after each callback it asks callbacks.contains
 * (a linear scan) whether the callback is still registered. The label is now built once per event name, and the
 * scan is skipped when the callback is still at the index it was called from (the scan's answer is then true); any
 * other case runs the scan as stock.
 *
 * In addition, LuaEventPatches provides bytecode-level rate pacing and LOD governors:
 * 1. Mod OnTick / OnPlayerUpdate 60Hz Rate Clamp: 3rd-party mod per-frame handlers are paced to 60Hz and staggered across
 *    frames, eliminating frametime spikes on 120Hz/144Hz/240Hz monitors while keeping UI and controls instant.
 * 2. Mod Zombie Script LOD: 3rd-party zombie mod scripts run every frame near the player, and are smoothly staggered
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

    // 60Hz Mod Event Pacing (Main thread only)
    private static final Map<Object, Long> lastModRunTimes = new HashMap<>();
    private static final ConcurrentHashMap<se.krka.kahlua.vm.Prototype, Boolean> modPrototypeCache = new ConcurrentHashMap<>();

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
        modPrototypeCache.put(lc.prototype, Boolean.valueOf(isMod));
        return isMod;
    }

    public static void call(se.krka.kahlua.integration.LuaCaller caller, se.krka.kahlua.vm.KahluaThread thread, Object closure,
                            Object[] params) {
        String event = depth > 0 && depth <= eventStack.length ? eventStack[depth - 1] : "?";

        // 1. Mod OnTick / OnPlayerUpdate 60Hz Rate Clamp & Frame Spreading
        if (modPaced && ("OnTick".equals(event) || "OnPlayerUpdate".equals(event))) {
            if (isModClosure(closure)) {
                long now = System.nanoTime();
                Long last = lastModRunTimes.get(closure);
                if (last == null) {
                    int phaseOffset = (Math.abs(closure.hashCode()) % 4) * 4_000_000;
                    lastModRunTimes.put(closure, now - phaseOffset);
                } else {
                    long elapsed = now - last;
                    if (elapsed < 16_000_000L) {
                        return; // 60Hz rate clamp: skip execution on this high-FPS frame
                    }
                    lastModRunTimes.put(closure, now);
                }
            }
        }

        // 2. Mod scripts on zombies LOD: every frame near player, staggered when far
        if (zombieLod && "OnZombieUpdate".equals(event)) {
            if (isModClosure(closure) && params != null && params.length > 0 && params[0] instanceof zombie.characters.IsoZombie z) {
                zombie.characters.IsoPlayer player = zombie.characters.IsoPlayer.getInstance();
                if (player == null && zombie.characters.IsoPlayer.players != null && zombie.characters.IsoPlayer.players.length > 0) {
                    player = zombie.characters.IsoPlayer.players[0];
                }
                if (player != null && player.getCell() == z.getCell()) {
                    float dx = z.getX() - player.getX();
                    float dy = z.getY() - player.getY();
                    float distSq = dx * dx + dy * dy;

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
