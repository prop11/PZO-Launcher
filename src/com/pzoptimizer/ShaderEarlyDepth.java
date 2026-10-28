package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.core.opengl.ShaderUnit;

/**
 * Puddle shaders with Early-Z preserved via GL 4.2 / ARB_conservative_depth.
 *
 * The HQ / MQ / LQ puddle fragment shaders end with gl_FragDepth = vDepth. Writing gl_FragDepth unconditionally
 * disables the GPU's early depth test unless conservative depth is declared, forcing every occluded wet pixel to run
 * the full ray-marched reflection shader.
 *
 * By declaring layout (depth_unchanged) out float gl_FragDepth, modern OpenGL 4.2+ drivers keep hardware Early-Z active
 * while keeping the exact stock depth writes intact. This avoids the localized puddle/reflection flicker caused by
 * vertex-stage depth rounding discrepancies.
 */
public final class ShaderEarlyDepth {

    private static volatile boolean active = true;
    public static final AtomicInteger unitsRewritten = new AtomicInteger();

    private static final String FRAG_DEPTH = "gl_FragDepth = vDepth;";
    private static final String CONSERVATIVE_DEPTH_DECL =
        "\n#extension GL_ARB_conservative_depth : enable\n" +
        "#ifdef GL_ARB_conservative_depth\n" +
        "layout (depth_unchanged) out float gl_FragDepth;\n" +
        "#endif\n";

    // Held lazily: patchShaderUnit runs while ShaderUnit is being defined, and the lookup would load it a second time
    private static final class H {
        static final MethodHandle LOAD = lookupLoad();
    }

    private ShaderEarlyDepth() {}

    private static MethodHandle lookupLoad() {
        try {
            return MethodHandles.privateLookupIn(ShaderUnit.class, MethodHandles.lookup()).findVirtual(ShaderUnit.class, "loadShaderFile",
                MethodType.methodType(String.class, String.class, ArrayList.class));
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderEarlyDepth] ShaderUnit.loadShaderFile not found, disabled: " + t);
            active = false;
            return null;
        }
    }

    public static void setActive(boolean value) {
        active = value && H.LOAD != null;
    }

    /** Replaces this.loadShaderFile(fileName, includes) in ShaderUnit.compile. */
    @SuppressWarnings("rawtypes")
    public static String load(ShaderUnit unit, String fileName, ArrayList includes) throws Throwable {
        String code = (String) H.LOAD.invokeExact(unit, fileName, includes);
        return active ? rewrite(fileName, code) : code;
    }

    static String rewrite(String fileName, String code) {
        if (code == null || fileName == null) return code;
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        String base = (slash >= 0 ? name.substring(slash + 1) : name).toLowerCase();
        if (!(base.startsWith("puddles_hq") || base.startsWith("puddles_mq") || base.startsWith("puddles_lq"))) return code;
        if (!code.contains(FRAG_DEPTH) || code.contains("GL_ARB_conservative_depth")) return code;

        String out = code;
        int vIdx = out.indexOf("#version");
        if (vIdx >= 0) {
            int lineEnd = out.indexOf('\n', vIdx);
            if (lineEnd >= 0) {
                out = out.substring(0, lineEnd + 1) + CONSERVATIVE_DEPTH_DECL + out.substring(lineEnd + 1);
            } else {
                out = out + "\n" + CONSERVATIVE_DEPTH_DECL;
            }
        } else {
            out = CONSERVATIVE_DEPTH_DECL + out;
        }

        if (!out.equals(code)) unitsRewritten.incrementAndGet();
        return out;
    }

    static boolean patchShaderUnit(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "compile", "()Z");
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "loadShaderFile".equals(mi.name) && node.name.equals(mi.owner)) {
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ShaderEarlyDepth", "load",
                    "(Lzombie/core/opengl/ShaderUnit;Ljava/lang/String;Ljava/util/ArrayList;)Ljava/lang/String;", false));
                return true;
            }
        }
        return false;
    }
}
