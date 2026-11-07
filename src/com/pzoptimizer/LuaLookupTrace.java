package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic for an occasional 'no such function "Vehicles.Init.Default"' on some benchmark routes. When
 * LuaManager.getFunctionObject can't resolve a Vehicles.* function I log the calling thread and its Java stack (at
 * most MAX_REPORTS a session), to see whether the lookup ran off the game thread.
 */
public final class LuaLookupTrace {

    private static final int MAX_REPORTS = 10;
    private static final AtomicInteger reports = new AtomicInteger();

    private LuaLookupTrace() {}

    // Before every return of getFunctionObject(name, logger), the return value passes through here
    public static Object result(Object value, String name) {
        if (value != null || name == null || !name.startsWith("Vehicles.")) return value;
        if (reports.incrementAndGet() > MAX_REPORTS) return value;
        Thread t = Thread.currentThread();
        StringBuilder sb = new StringBuilder();
        sb.append("[LuaLookupTrace] getFunctionObject(\"").append(name).append("\") returned null on thread '").append(t.getName())
            .append("'").append(isGameThread(t) ? " (game thread)" : " (NOT the game thread)").append(":");
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            sb.append("\n    at ").append(e);
        }
        PZOLogger.warn(sb.toString());
        return value;
    }

    // By identity, not name: PZO renames the game's main thread to PZO-MainSimulationThread
    private static boolean isGameThread(Thread t) {
        try {
            Thread main = zombie.MainThread.mainThread;
            if (main != null) return t == main;
        } catch (Throwable ignored) {}
        return "MainThread".equals(t.getName()) || "PZO-MainSimulationThread".equals(t.getName());
    }

    static boolean patchLuaManager(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "getFunctionObject", "(Ljava/lang/String;Lzombie/debug/DebugType;)Ljava/lang/Object;");
        if (m == null || (m.access & Opcodes.ACC_STATIC) == 0) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.ARETURN) continue;
            InsnList l = new InsnList();
            l.add(new VarInsnNode(Opcodes.ALOAD, 0));
            l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/LuaLookupTrace", "result",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
            m.instructions.insertBefore(insn, l);
            n++;
        }
        return n > 0;
    }
}
