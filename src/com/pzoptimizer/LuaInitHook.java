package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * LuaManager.init builds a fresh Lua state (at boot, after a mod change, when a game loads). A call before each of its
 * returns binds the PZO globals into that state before any mod's Lua runs, so the Lua mod always finds the engine.
 */
final class LuaInitHook {

    private LuaInitHook() {}

    static boolean patchLuaManager(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "init", "()V");
        if (m == null || (m.access & Opcodes.ACC_STATIC) == 0) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/PZOEngineBridge",
                "onLuaEnvCreated", "()V", false));
            n++;
        }
        return n > 0;
    }
}
