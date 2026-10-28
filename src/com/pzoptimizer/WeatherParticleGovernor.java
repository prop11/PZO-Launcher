package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.lwjgl.opengl.GL11;
import zombie.core.ShaderHelper;

/**
 * Eliminates synchronous GPU pipeline flushes and driver stalls during weather particle rendering.
 * In vanilla, WeatherParticleDrawer queries the active shader program via GL11.glGetInteger(GL_CURRENT_PROGRAM),
 * which forces a CPU-GPU pipeline synchronization stall on every weather drawer every frame.
 */
public final class WeatherParticleGovernor {

    public static volatile boolean active = true;

    private static final VarHandle CURRENTLY_BOUND;

    static {
        VarHandle vh = null;
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            vh = MethodHandles.privateLookupIn(ShaderHelper.class, l).findStaticVarHandle(ShaderHelper.class, "currentlyBound", int.class);
        } catch (Throwable ignored) {
            vh = null;
        }
        CURRENTLY_BOUND = vh;
    }

    private WeatherParticleGovernor() {}

    /**
     * Replaces GL11.glGetInteger(GL_CURRENT_PROGRAM) in WeatherParticleDrawer.render().
     * Reads the tracked Java program ID directly from ShaderHelper.currentlyBound without stalling the GPU pipeline.
     */
    public static int getProgramNoStall(int param) {
        if (active && param == 35725) { // GL_CURRENT_PROGRAM
            if (CURRENTLY_BOUND != null) {
                try {
                    int bound = (int) CURRENTLY_BOUND.get();
                    if (bound >= 0) {
                        return bound;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return GL11.glGetInteger(param);
    }

    /**
     * ASM Transformer for zombie.iso.weather.fx.WeatherParticleDrawer:
     * Redirects GL11.glGetInteger(35725) calls to WeatherParticleGovernor.getProgramNoStall(35725).
     */
    public static boolean patchDrawer(ClassNode node) {
        MethodNode render = AsmSupport.findMethod(node, "render", "()V");
        if (render == null) return false;
        boolean changed = false;
        for (AbstractInsnNode insn = render.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                    && "org/lwjgl/opengl/GL11".equals(mi.owner)
                    && "glGetInteger".equals(mi.name)
                    && "(I)I".equals(mi.desc)) {
                mi.owner = "com/pzoptimizer/WeatherParticleGovernor";
                mi.name = "getProgramNoStall";
                changed = true;
            }
        }
        return changed;
    }
}
