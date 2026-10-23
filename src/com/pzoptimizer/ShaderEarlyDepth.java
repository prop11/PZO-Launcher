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
 * Puddle shaders with the early depth test left on.
 *
 * The HQ / MQ / LQ puddle fragment shaders end with gl_FragDepth = vDepth, where vDepth is the per-vertex depth
 * attribute passed through unchanged. Writing gl_FragDepth turns the GPU's early depth test off, so every wet
 * pixel hidden behind a wall, roof or object still runs the full water shader (the HQ one is a ray-marched
 * reflection) before being rejected. The puddle quads are drawn with an orthographic projection (w = 1), so the
 * same depth reaches the depth buffer when the vertex shader writes it into gl_Position.z: with the (0, 1) depth
 * range the game uses, window depth = z_ndc * 0.5 + 0.5, hence z_ndc = 2 * depth - 1. The fragment shader then
 * no longer touches depth and hidden pixels are rejected before shading. Same colours, same depth test result.
 *
 * Applied to the shader text at load, and only to the game's own puddles_hq / _mq / _lq units containing the
 * exact stock lines; any other shader, or a modded puddle shader without those lines, is left as it is.
 */
public final class ShaderEarlyDepth {

    private static volatile boolean active = true;
    public static final AtomicInteger unitsRewritten = new AtomicInteger();

    private static final String FRAG_DEPTH = "gl_FragDepth = vDepth;";
    private static final String VERT_DEPTH = "vDepth = aFragDepth;";
    private static final String VERT_EARLY = "vDepth = aFragDepth;\n\tgl_Position.z = (2.0 * clamp(aFragDepth, 0.0, 1.0) - 1.0) * gl_Position.w;";

    // Held lazily: patchShaderUnit runs while ShaderUnit is being defined, and the lookup would load it a second time
    private static final class H {
        static final MethodHandle LOAD = lookupLoad();
    }
    // Quality levels whose vertex unit now writes the depth; only their fragment units may stop writing it
    private static final java.util.Set<String> vertexDone = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        String quality = base.substring(0, "puddles_xq".length());
        String out = code;
        if (out.contains(VERT_DEPTH) && out.contains("gl_Position") && !out.contains("gl_Position.z")) {
            out = out.replace(VERT_DEPTH, VERT_EARLY);
            vertexDone.add(quality);
        } else if (out.contains(FRAG_DEPTH) && vertexDone.contains(quality)) {
            out = out.replace(FRAG_DEPTH, "/* depth from the vertex stage (PZO early depth) */");
        }
        if (out != code) unitsRewritten.incrementAndGet();
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
