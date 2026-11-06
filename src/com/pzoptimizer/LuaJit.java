package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.FieldNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/**
 * Compiles hot Lua functions to JVM code (JVM_LuaJit). See LuaJitCompiler for what a compiled function does and why it
 * stays exact.
 *
 * luaMainloop calls enter() right before it fetches each instruction. For a function that isn't compiled that only
 * counts; once a function has run HOT_INSTRUCTIONS instructions it's compiled on a background thread, and from then on
 * enter() runs the compiled code from the frame's pc until the next instruction the interpreter has to do itself.
 * Nothing runs compiled in debug mode (the debugger steps the interpreter), and mods on the exclusion list (settings
 * screen) are never compiled.
 */
public final class LuaJit {

    /** What a compiled function implements. */
    public interface Fn {
        void run(KahluaThread thread, LuaCallFrame callFrame);
    }

    public static volatile boolean active = false;
    // Instructions a function runs before it's compiled
    static final int HOT_INSTRUCTIONS = 4000;

    public static final LongAdder compiled = new LongAdder();
    public static final LongAdder failed = new LongAdder();
    public static final LongAdder excluded = new LongAdder();
    public static final LongAdder entries = new LongAdder();

    // States in Prototype.pzoJit besides a Fn
    private static final Object PENDING = new Object(), FAILED = new Object(), EXCLUDED = new Object();

    private static volatile VarHandle jitField, hitsField;
    private static volatile boolean bound, broken;
    private static MethodHandles.Lookup hostLookup;
    private static final ExecutorService compiler = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PZO-LuaJit");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    // Lower-case path tokens ("/mods/<folder>/") of mods the player excluded
    private static final Set<String> excludedPaths = ConcurrentHashMap.newKeySet();
    // Functions that are compiled or excluded, so a change to the list can take them back or let them compile
    private static final java.util.Map<Prototype, Boolean> tracked = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private LuaJit() {}

    private static boolean bind() {
        if (bound) return !broken;
        synchronized (LuaJit.class) {
            if (bound) return !broken;
            try {
                MethodHandles.Lookup p = MethodHandles.privateLookupIn(Prototype.class, MethodHandles.lookup());
                jitField = p.findVarHandle(Prototype.class, "pzoJit", Object.class);
                hitsField = p.findVarHandle(Prototype.class, "pzoJitHits", int.class);
                hostLookup = MethodHandles.privateLookupIn(KahluaThread.class, MethodHandles.lookup());
            } catch (Throwable t) {
                broken = true;
                PZOLogger.warn("[LuaJit] Stood down, Lua stays interpreted: " + t);
            }
            bound = true;
            return !broken;
        }
    }

    /** From luaMainloop, before it fetches callFrame's next instruction. Game thread (or whichever thread runs Lua). */
    public static void enter(KahluaThread thread, LuaCallFrame callFrame) {
        if (!active || zombie.core.Core.debug) return;
        if (!bound && !bind()) return;
        if (broken) return;
        LuaClosure closure = callFrame.closure;
        if (closure == null) return;
        Prototype p = closure.prototype;
        Object state = jitField.get(p);
        if (state instanceof Fn fn) {
            fn.run(thread, callFrame);
            return;
        }
        if (state != null) return;
        int hits = (int) hitsField.get(p) + 1;
        hitsField.set(p, hits);
        if (hits == HOT_INSTRUCTIONS) request(p);
    }

    private static void request(Prototype p) {
        if (isExcluded(p)) {
            jitField.setVolatile(p, EXCLUDED);
            tracked.put(p, Boolean.TRUE);
            excluded.increment();
            return;
        }
        jitField.setVolatile(p, PENDING);
        compiler.execute(() -> {
            try {
                byte[] bytes = LuaJitCompiler.compile(p.code, sourceName(p));
                if (bytes == null) {
                    jitField.setVolatile(p, FAILED);
                    return;
                }
                MethodHandles.Lookup hidden = hostLookup.defineHiddenClass(bytes, false, MethodHandles.Lookup.ClassOption.NESTMATE);
                Fn fn = (Fn) hidden.findConstructor(hidden.lookupClass(), java.lang.invoke.MethodType.methodType(void.class)).invoke();
                jitField.setVolatile(p, fn);
                tracked.put(p, Boolean.TRUE);
                compiled.increment();
            } catch (LuaJitCompiler.TooBig tooBig) {
                jitField.setVolatile(p, FAILED);
                failed.increment();
            } catch (Throwable t) {
                jitField.setVolatile(p, FAILED);
                failed.increment();
                if (failed.sum() <= 5) PZOLogger.warn("[LuaJit] " + sourceName(p) + " stays interpreted: " + t);
            }
        });
    }

    private static String sourceName(Prototype p) {
        String f = p.filename != null ? p.filename : p.file;
        int line = p.lines != null && p.lines.length > 0 ? p.lines[0] : 0;
        return (f != null ? f : "?") + ":" + line;
    }

    private static boolean isExcluded(Prototype p) {
        if (excludedPaths.isEmpty()) return false;
        String f = p.filename != null ? p.filename : p.file;
        if (f == null) return false;
        String lower = f.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String token : excludedPaths) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    /**
     * Settings screen: replaces the excluded mods. Compiled functions of a newly excluded mod go back to the
     * interpreter at once, and functions of a mod taken off the list start counting again.
     */
    public static synchronized void setExcludedPaths(java.util.Collection<String> tokens) {
        excludedPaths.clear();
        for (String t : tokens) {
            if (t != null && !t.isBlank()) excludedPaths.add(t.trim().toLowerCase(Locale.ROOT));
        }
        if (!bound || broken) return;
        Prototype[] list;
        synchronized (tracked) {
            list = tracked.keySet().toArray(new Prototype[0]);
        }
        for (Prototype p : list) {
            boolean exclude = isExcluded(p);
            Object state = jitField.getVolatile(p);
            if (exclude && state instanceof Fn) {
                jitField.setVolatile(p, EXCLUDED);
            } else if (!exclude && state == EXCLUDED) {
                hitsField.set(p, 0);
                jitField.setVolatile(p, null);
                tracked.remove(p);
            }
        }
    }

    public static String stats() {
        return "compiled " + compiled.sum() + ", stayed interpreted " + failed.sum() + ", excluded " + excluded.sum();
    }

    // Patches

    /** Prototype: the compiled function (or its state) and the instruction count, as plain fields. */
    static boolean patchPrototype(ClassNode node) {
        for (FieldNode f : node.fields) {
            if (f.name.equals("pzoJit")) return false;
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE, "pzoJit", "Ljava/lang/Object;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "pzoJitHits", "I", null, null));
        return true;
    }

    /**
     * luaMainloop: LuaJit.enter(this, callFrame) right before "int op = opcodes[callFrame.pc++]", which is inside the
     * method's try, so anything a compiled function throws is handled like an error in that instruction.
     */
    static boolean patchThread(ClassNode node) {
        MethodNode loop = AsmSupport.findMethod(node, "luaMainloop", "()V");
        if (loop == null) return false;
        int sites = 0;
        for (AbstractInsnNode insn = loop.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            // ALOAD opcodes, ALOAD callFrame, DUP, GETFIELD LuaCallFrame.pc, DUP_X1, ICONST_1, IADD, PUTFIELD pc, IALOAD
            if (!(insn instanceof VarInsnNode codes) || codes.getOpcode() != Opcodes.ALOAD) continue;
            AbstractInsnNode n1 = next(codes), n2 = next(n1), n3 = next(n2);
            if (!(n1 instanceof VarInsnNode frame) || frame.getOpcode() != Opcodes.ALOAD) continue;
            if (n2 == null || n2.getOpcode() != Opcodes.DUP) continue;
            if (!(n3 instanceof FieldInsnNode pc) || pc.getOpcode() != Opcodes.GETFIELD
                    || !"se/krka/kahlua/vm/LuaCallFrame".equals(pc.owner) || !"pc".equals(pc.name)) continue;
            AbstractInsnNode n = n3;
            for (int k = 0; k < 5 && n != null; k++) n = next(n);
            if (n == null || n.getOpcode() != Opcodes.IALOAD) continue;
            // SETLIST and CLOSURE fetch extra words the same way; the main fetch is the one that feeds the 38-way switch
            AbstractInsnNode sw = n;
            for (int k = 0; k < 8 && sw != null && sw.getOpcode() != Opcodes.TABLESWITCH; k++) sw = next(sw);
            if (!(sw instanceof com.pzoptimizer.shaded.asm.tree.TableSwitchInsnNode ts) || ts.max - ts.min < 30) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new VarInsnNode(Opcodes.ALOAD, frame.var));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/LuaJit", "enter",
                "(Lse/krka/kahlua/vm/KahluaThread;Lse/krka/kahlua/vm/LuaCallFrame;)V", false));
            loop.instructions.insertBefore(codes, hook);
            sites++;
            insn = n;
        }
        return sites == 1;
    }

    private static AbstractInsnNode next(AbstractInsnNode insn) {
        AbstractInsnNode n = insn == null ? null : insn.getNext();
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n;
    }
}
