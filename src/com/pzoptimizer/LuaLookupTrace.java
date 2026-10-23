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
 * Diagnostic for an intermittent 'no such function "Vehicles.Init.Default"' seen on some benchmark routes: when
 * LuaManager.getFunctionObject fails to resolve a Vehicles.* function, the calling thread and its Java stack are
 * logged (at most MAX_REPORTS times a session), so it can be told whether the lookup ran off the game thread.
 */
public final class LuaLookupTrace {

    private static final int MAX_REPORTS = 10;
    private static final AtomicInteger reports = new AtomicInteger();

    private LuaLookupTrace() {}

    // Before every return of getFunctionObject(name, logger): the returned value is passed through
    public static Object result(Object value, String name) {
        if (value != null || name == null || !name.startsWith("Vehicles.")) return value;
        if (reports.incrementAndGet() > MAX_REPORTS) return value;
        Thread t = Thread.currentThread();
        StringBuilder sb = new StringBuilder();
        sb.append("[LuaLookupTrace] getFunctionObject(\"").append(name).append("\") returned null on thread '").append(t.getName())
            .append("'").append("MainThread".equals(t.getName()) ? " (game thread)" : " (NOT the game thread)").append(":");
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            sb.append("\n    at ").append(e);
        }
        PZOLogger.warn(sb.toString());
        return value;
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
