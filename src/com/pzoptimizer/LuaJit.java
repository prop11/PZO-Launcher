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

    /**
     * What a compiled function implements. direct is true when another compiled function called it straight (it pushed
     * the frame with fromLua false): then RETURN is done here and run returns 1. Otherwise run returns 0 once it hands
     * back to the interpreter.
     */
    public interface Fn {
        int run(KahluaThread thread, LuaCallFrame callFrame, boolean direct);
    }

    // Direct Lua-to-Lua calls nest Java frames; past this the call goes through the interpreter instead
    static final int MAX_DIRECT_DEPTH = 40;

    public static volatile boolean active = false;
    // Instructions a function runs before it's compiled
    static final int HOT_INSTRUCTIONS = 4000;

    public static final LongAdder compiled = new LongAdder();
    public static final LongAdder failed = new LongAdder();
    public static final LongAdder excluded = new LongAdder();
    public static final LongAdder entries = new LongAdder();

    // States in Prototype.pzoJit besides a Fn
    private static final Object PENDING = new Object(), FAILED = new Object(), EXCLUDED = new Object();

    private static volatile boolean bound, broken;
    private static MethodHandles.Lookup hostLookup;

    // static final, so the JIT turns them into plain field accesses; non-final handles cost a slow lookup per instruction
    private static final class Fields {
        static final VarHandle JIT, HITS;

        static {
            try {
                MethodHandles.Lookup p = MethodHandles.privateLookupIn(Prototype.class, MethodHandles.lookup());
                JIT = p.findVarHandle(Prototype.class, "pzoJit", Object.class);
                HITS = p.findVarHandle(Prototype.class, "pzoJitHits", int.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Prototype has no compiler fields", e);
            }
        }
    }
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
                // Loads Fields, which fails if the Prototype patch didn't go in
                Object probe = Fields.JIT;
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
        Object state = Fields.JIT.get(p);
        if (state instanceof Fn fn) {
            fn.run(thread, callFrame, false);
            return;
        }
        if (state != null) return;
        int hits = (int) Fields.HITS.get(p) + 1;
        Fields.HITS.set(p, hits);
        if (hits == HOT_INSTRUCTIONS) request(p);
    }

    private static void request(Prototype p) {
        if (isExcluded(p)) {
            Fields.JIT.setVolatile(p, EXCLUDED);
            tracked.put(p, Boolean.TRUE);
            excluded.increment();
            return;
        }
        Fields.JIT.setVolatile(p, PENDING);
        compiler.execute(() -> {
            try {
                byte[] bytes = LuaJitCompiler.compile(p.code, sourceName(p));
                if (bytes == null) {
                    Fields.JIT.setVolatile(p, FAILED);
                    return;
                }
                MethodHandles.Lookup hidden = hostLookup.defineHiddenClass(bytes, false, MethodHandles.Lookup.ClassOption.NESTMATE);
                Fn fn = (Fn) hidden.findConstructor(hidden.lookupClass(), java.lang.invoke.MethodType.methodType(void.class)).invoke();
                Fields.JIT.setVolatile(p, fn);
                tracked.put(p, Boolean.TRUE);
                compiled.increment();
            } catch (LuaJitCompiler.TooBig tooBig) {
                Fields.JIT.setVolatile(p, FAILED);
                failed.increment();
            } catch (Throwable t) {
                Fields.JIT.setVolatile(p, FAILED);
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
            Object state = Fields.JIT.getVolatile(p);
            if (exclude && state instanceof Fn) {
                Fields.JIT.setVolatile(p, EXCLUDED);
            } else if (!exclude && state == EXCLUDED) {
                Fields.HITS.set(p, 0);
                Fields.JIT.setVolatile(p, null);
                tracked.remove(p);
            }
        }
    }

    public static String stats() {
        return "compiled " + compiled.sum() + ", stayed interpreted " + failed.sum() + ", excluded " + excluded.sum();
    }

    // Error reporting. With direct calls an error can pass more than one luaMainloop catch (or calleeFailed) on its way
    // out; vanilla, with one loop, reports it once, at the innermost frame. So each Throwable is reported the first
    // time only. Also covers vanilla's own nesting (metamethods called through call()).
    private static final class Reported {
        Throwable last;
        boolean skip;
    }

    private static final ThreadLocal<Reported> REPORTED = ThreadLocal.withInitial(Reported::new);

    public static final LongAdder errorsReported = new LongAdder();
    public static final LongAdder errorsSkipped = new LongAdder();

    public static void reportLog(Throwable e) {
        Reported r = REPORTED.get();
        if (active && r.last == e) {
            r.skip = true;
            errorsSkipped.increment();
            return;
        }
        r.last = e;
        r.skip = false;
        errorsReported.increment();
        zombie.core.logger.ExceptionLogger.logException(e);
    }

    public static void reportDebug(KahluaThread thread, Throwable e) {
        if (!REPORTED.get().skip) thread.debugException(e);
    }

    public static void reportStack(KahluaThread thread, LuaCallFrame callFrame) {
        if (!REPORTED.get().skip) thread.doStacktraceProper(callFrame);
    }

    private static final class FrameFields {
        static final VarHandle FROM_LUA;

        static {
            try {
                FROM_LUA = MethodHandles.privateLookupIn(LuaCallFrame.class, MethodHandles.lookup())
                    .findVarHandle(LuaCallFrame.class, "fromLua", boolean.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * A directly called compiled function threw (no luaMainloop was running it to catch the error): what luaMainloop's
     * catch does for that frame. Report once, then pop frames with their stack trace lines down to the first one not
     * entered from Lua (the callee, pushed with fromLua false), close its upvalues, and let the caller rethrow.
     */
    public static Throwable calleeFailed(KahluaThread thread, Throwable e, LuaCallFrame callee) {
        reportLog(e);
        reportDebug(thread, e);
        reportStack(thread, callee);
        se.krka.kahlua.vm.Coroutine co = thread.currentCoroutine;
        LuaCallFrame f;
        do {
            f = co.currentCallFrame();
            if (f == null) break;
            co.addStackTrace(f);
            co.popCallFrame();
        } while ((boolean) FrameFields.FROM_LUA.get(f));
        if (f != null) f.closeUpvalues(0);
        return e;
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
        if (sites != 1) return false;
        boolean hasDepth = false;
        for (FieldNode f : node.fields) hasDepth |= f.name.equals("pzoJitDepth");
        if (!hasDepth) node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "pzoJitDepth", "I", null, null));
        return redirectReporting(loop);
    }

    // The Throwable handler of luaMainloop starts with ExceptionLogger.logException(e), debugException(e) and
    // doStacktraceProper(callFrame); those go through report* so each error is reported once
    private static boolean redirectReporting(MethodNode loop) {
        String thread = "se/krka/kahlua/vm/KahluaThread", self = "com/pzoptimizer/LuaJit";
        for (com.pzoptimizer.shaded.asm.tree.TryCatchBlockNode tc : loop.tryCatchBlocks) {
            if (!"java/lang/Throwable".equals(tc.type)) continue;
            int done = 0;
            AbstractInsnNode insn = tc.handler;
            for (int k = 0; k < 20 && insn != null && done < 3; k++, insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode mi)) continue;
                MethodInsnNode repl = null;
                if (mi.getOpcode() == Opcodes.INVOKESTATIC && "zombie/core/logger/ExceptionLogger".equals(mi.owner)
                        && "logException".equals(mi.name) && "(Ljava/lang/Throwable;)V".equals(mi.desc)) {
                    repl = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "reportLog", "(Ljava/lang/Throwable;)V", false);
                } else if (thread.equals(mi.owner) && "debugException".equals(mi.name)) {
                    repl = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "reportDebug",
                        "(L" + thread + ";Ljava/lang/Throwable;)V", false);
                } else if (thread.equals(mi.owner) && "doStacktraceProper".equals(mi.name)
                        && "(Lse/krka/kahlua/vm/LuaCallFrame;)V".equals(mi.desc)) {
                    repl = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "reportStack",
                        "(L" + thread + ";Lse/krka/kahlua/vm/LuaCallFrame;)V", false);
                }
                if (repl != null) {
                    loop.instructions.set(mi, repl);
                    insn = repl;
                    done++;
                }
            }
            if (done == 3) return true;
        }
        return false;
    }

    private static AbstractInsnNode next(AbstractInsnNode insn) {
        AbstractInsnNode n = insn == null ? null : insn.getNext();
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n;
    }
}
