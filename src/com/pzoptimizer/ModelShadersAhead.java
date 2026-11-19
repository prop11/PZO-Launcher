package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model shaders made while the save loads (JVM_ModelShadersAhead).
 *
 * Model.CreateShader runs on whatever thread loads the model and waits for the render thread to make or find the
 * shader, which costs a frame each time (up to ~20 ms) even when the shader already exists. When the world has loaded
 * I make the shader of every model script (plus basicEffect, static and not) on the render thread, a few each frame,
 * and CreateShader takes an already made one straight from here instead of waiting. A shader nobody made ahead still
 * goes the stock way, and what that makes is kept for next time.
 *
 * With the shader cache on, the first load with PZO compiles these and stores them; later loads read them back.
 * ShaderCacheBar shows the progress.
 */
public final class ModelShadersAhead {

    public static volatile boolean active = true;

    private static final long BATCH_NANOS = 8_000_000L;
    private static final java.util.Set<String> REFLECTING = java.util.Set.of("vehicle", "vehicle_multiuv", "vehicle_norandom_multiuv");

    // "name|static" or "name" -> the game's shader
    private static final ConcurrentHashMap<String, zombie.core.skinnedmodel.shader.Shader> made = new ConcurrentHashMap<>();

    private static volatile List<Object[]> wanted = List.of();
    private static volatile int next, total;
    private static volatile boolean batchQueued;
    private static volatile long startNanos;
    private static long hitsAtStart, missesAtStart;
    public static volatile int taken;

    private ModelShadersAhead() {}

    static String key(String name, boolean isStatic) {
        return isStatic ? name + "|static" : name;
    }

    /** Prefix of Model.CreateShader: true when the shader was handed over and stock must return. */
    public static boolean take(Object model, String name) {
        if (!active || name == null || zombie.core.skinnedmodel.ModelManager.noOpenGL) return false;
        zombie.core.skinnedmodel.model.Model m = (zombie.core.skinnedmodel.model.Model) model;
        zombie.core.skinnedmodel.shader.Shader s = made.get(key(name, m.isStatic));
        if (s == null) return false;
        m.effect = s;
        taken++;
        return true;
    }

    /** End of Model.CreateShader (stock path): keep what the render thread made. */
    public static void made(Object model, String name) {
        if (name == null) return;
        zombie.core.skinnedmodel.model.Model m = (zombie.core.skinnedmodel.model.Model) model;
        keep(name, m.isStatic, m.effect);
    }

    // An instanced shader is never found again by getOrCreateShader(.., false), so stock makes a new one each time;
    // handing one out would change that
    private static void keep(String name, boolean isStatic, zombie.core.skinnedmodel.shader.Shader s) {
        if (s != null && name.equals(s.getName()) && !s.isInstanced()) made.putIfAbsent(key(name, isStatic), s);
    }

    static volatile boolean modelHooked;
    private static boolean retransformTried;

    /** GameLoadingState.SendDone (single player, host) or Done (client): the scripts are loaded, list what to make. */
    public static void worldLoaded() {
        if (!active || zombie.core.skinnedmodel.ModelManager.noOpenGL || busy()) return;
        // Model tends to load while another class is being patched, which leaves it stock
        if (!modelHooked && !retransformTried) {
            retransformTried = true;
            PZOptimAgent.retransformLate(zombie.core.skinnedmodel.model.Model.class);
            if (!modelHooked) PZOLogger.warn("[ModelShadersAhead] Model.CreateShader isn't hooked; shaders are still made ahead but models wait as before");
        }
        try {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            List<Object[]> list = new ArrayList<>();
            add(keys, list, "basicEffect", true);
            add(keys, list, "basicEffect", false);
            zombie.core.Core core = zombie.core.Core.getInstance();
            boolean reflect = core.getPerfReflectionsOnLoad() && !core.getUseOpenGL21();
            for (zombie.scripting.objects.ModelScript ms : zombie.scripting.ScriptManager.instance.getAllModelScripts()) {
                String n = ms.getShaderName();
                // ModelManager.modifyShaderName
                if (!reflect && REFLECTING.contains(n)) n = n + "_noreflect";
                add(keys, list, n, ms.isStatic());
            }
            hitsAtStart = ShaderCache.hits.get();
            missesAtStart = ShaderCache.misses.get();
            startNanos = System.nanoTime();
            next = 0;
            total = list.size();
            wanted = list;
            PZOLogger.info("[ModelShadersAhead] " + list.size() + " model shaders to make ahead");
        } catch (Throwable t) {
            PZOLogger.warn("[ModelShadersAhead] couldn't list the model shaders: " + t);
        }
    }

    private static void add(LinkedHashSet<String> keys, List<Object[]> list, String name, boolean isStatic) {
        if (name == null || name.isEmpty()) return;
        String k = key(name, isStatic);
        if (keys.add(k) && !made.containsKey(k)) list.add(new Object[] {name, isStatic});
    }

    public static boolean busy() {
        return next < total;
    }

    public static int done() {
        return Math.min(next, total);
    }

    public static int total() {
        return total;
    }

    /** True while every program this run came from the shader cache. */
    public static boolean allFromCache() {
        return ShaderCache.misses.get() == missesAtStart;
    }

    /** Main thread, once a frame (from the bar): queues one batch for the render thread when none is waiting. */
    public static void tick() {
        if (!busy() || batchQueued) return;
        batchQueued = true;
        try {
            zombie.core.opengl.RenderThread.queueInvokeOnRenderContext(ModelShadersAhead::batch);
        } catch (Throwable t) {
            batchQueued = false;
            total = next;
            PZOLogger.warn("[ModelShadersAhead] couldn't reach the render thread: " + t);
        }
    }

    // Render thread
    private static void batch() {
        try {
            long end = System.nanoTime() + BATCH_NANOS;
            List<Object[]> list = wanted;
            while (next < list.size()) {
                Object[] w = list.get(next);
                String name = (String) w[0];
                boolean isStatic = (Boolean) w[1];
                try {
                    keep(name, isStatic, zombie.core.skinnedmodel.shader.ShaderManager.instance.getOrCreateShader(name, isStatic, false));
                } catch (Throwable t) {
                    PZOLogger.warn("[ModelShadersAhead] " + name + " couldn't be made ahead: " + t);
                }
                next++;
                if (System.nanoTime() >= end) break;
            }
            if (next >= list.size() && total > 0) {
                long ms = (System.nanoTime() - startNanos) / 1_000_000L;
                PZOLogger.info("[ModelShadersAhead] " + total + " model shaders ready in " + ms + " ms ("
                    + (ShaderCache.hits.get() - hitsAtStart) + " from the shader cache, " + (ShaderCache.misses.get() - missesAtStart)
                    + " compiled). " + ShaderCache.summary());
            }
        } finally {
            batchQueued = false;
        }
    }

    // Model.CreateShader(String): "if (ModelShadersAhead.take(this, name)) return;" first, made(this, name) before return
    static boolean patchModel(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "CreateShader", "(Ljava/lang/String;)V");
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "com/pzoptimizer/ModelShadersAhead".equals(mi.owner)) return false;
        }
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList after = new InsnList();
                after.add(new VarInsnNode(Opcodes.ALOAD, 0));
                after.add(new VarInsnNode(Opcodes.ALOAD, 1));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ModelShadersAhead", "made", "(Ljava/lang/Object;Ljava/lang/String;)V", false));
                m.instructions.insertBefore(insn, after);
            }
        }
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ModelShadersAhead", "take", "(Ljava/lang/Object;Ljava/lang/String;)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(stock);
        m.instructions.insert(pre);
        modelHooked = true;
        return true;
    }

    // GameLoadingState.SendDone() and Done(): worldLoaded() first. Single player only ever calls SendDone, a client
    // gets Done from the server later; the second call finds nothing left to make.
    static boolean patchLoadingDone(ClassNode node) {
        int n = 0;
        for (String name : new String[] {"SendDone", "Done"}) {
            MethodNode m = AsmSupport.findMethod(node, name, "()V");
            if (m == null || (m.access & Opcodes.ACC_STATIC) == 0) continue;
            boolean has = false;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode mi && "com/pzoptimizer/ModelShadersAhead".equals(mi.owner)) has = true;
            }
            if (has) continue;
            m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ModelShadersAhead", "worldLoaded", "()V", false));
            n++;
        }
        return n > 0;
    }
}
