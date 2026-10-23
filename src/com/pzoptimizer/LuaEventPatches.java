package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InvokeDynamicInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
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
 * While a benchmark runs, every callback is also timed and the total is attributed to "event file:function", so the
 * results show which Lua handlers (the game's or a mod's) cost the most, e.g. on LoadGridsquare during chunk loading.
 */
public final class LuaEventPatches {

    public static volatile boolean active = true;
    public static volatile boolean timing = false;

    private static final ConcurrentHashMap<String, String> labels = new ConcurrentHashMap<>();

    // Game thread only (Lua runs there); a handler may fire another event, so the current name is a stack
    private static final String[] eventStack = new String[64];
    private static int depth;
    private static final Map<String, long[]> handlerTimes = new HashMap<>(); // key -> {nanos, calls}

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
        if (depth < eventStack.length) eventStack[depth] = name;
        depth++;
    }

    public static void exit() {
        if (depth > 0) depth--;
    }

    public static void call(se.krka.kahlua.integration.LuaCaller caller, se.krka.kahlua.vm.KahluaThread thread, Object closure,
                            Object[] params) {
        if (!timing) {
            caller.protectedCallVoid(thread, closure, params);
            return;
        }
        String event = depth > 0 && depth <= eventStack.length ? eventStack[depth - 1] : "?";
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
