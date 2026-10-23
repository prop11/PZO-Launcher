package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionState;
import zombie.characters.action.ActionTransition;
import zombie.characters.action.IActionCondition;
import zombie.characters.action.conditions.CharacterVariableCondition;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableReference;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableSlotCallback;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlot;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSource;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * Reads the zombies' action-transition variables on worker threads.
 *
 * Each frame every animating zombie's ActionContext.update() evaluates the transition conditions of its current
 * action states; nearly all of the cost is the variable reads (a megamorphic callback into the zombie, a string
 * conversion and a parse per condition). The evaluation itself stays on the game thread; only the reads move:
 *
 * 1. In each scheduler bucket's postupdate loop an eligible zombie runs its postupdate up to the point stock calls
 *    ActionContext.update() (movement, facing, turning flags) and is queued; everything after that point in its
 *    postupdate (the state change, animator, model, hit and attack clears, the zombie's own tail) is deferred.
 * 2. After the loop, the workers read, for every queued zombie, the variables its current and sub-states'
 *    conditions reference, but only plain stored values and callbacks CallbackPurity proved to be pure reads.
 * 3. Still in the bucket, each queued zombie resumes its postupdate exactly where it stopped, in queue order, on the
 *    game thread. Condition reads of a pre-read variable return the worker's value; any other read (an impure
 *    callback, a state entered during this evaluation) runs live as stock. Evaluation works on a scratch copy of
 *    the state container and changes no variable, so the pre-read values hold for the whole evaluation.
 *
 * Within one zombie nothing is reordered. Across zombies, the deferred parts run after every zombie's first part,
 * so a zombie whose postupdate read another zombie's mid-update state could see it one step later; grappling and
 * reanimated-player zombies, whose postupdates read each other, are never deferred. Single player only.
 */
public final class ActionParallel {

    private static volatile boolean active = true;
    private static boolean allPatched;
    private static boolean collecting;

    private static final byte NONE = 0, DEFERRED = 1, RESUMING = 2;
    private static final Object NULL = new Object();
    private static final int MIN_PARALLEL = 24;

    public static final AtomicLong deferred = new AtomicLong();
    public static final AtomicLong preRead = new AtomicLong();
    public static final AtomicLong preReadHits = new AtomicLong();

    private static final List<IsoZombie> queue = new ArrayList<>();
    private static Thread gameThread;

    private static volatile Set<String> pureKeys;
    private static ExecutorService workers;
    private static int workerCount;

    // Set on the game thread while one queued zombie resumes
    private static IsoZombie resumingZombie;
    private static Object[] resumingKeys, resumingVals;
    private static int resumingCount;

    // The handles below load game classes, so they live in a holder: the agent calls this class's patch methods
    // while one of those classes is being defined, and loading it again from there is a duplicate definition
    private static final class H {
        private static final VarHandle PHASE, SNAP_KEYS, SNAP_VALS;
        private static final VarHandle CVC_LHS, CVC_RHS, LOOKUP_REF, REF_HANDLE, REF_SUBSOURCE, AC_CONTAINER, CONT_CURRENT, CONT_CHILDREN,
            TRANSITION_CONDITIONS;
        private static final MethodHandle RESOLVE, PARSE;
        private static final Class<?> LOOKUP_CLASS;
        static {
            VarHandle phase = null, sk = null, sv = null, lhs = null, rhs = null, ref = null, handle = null, sub = null, cont = null,
                cur = null, kids = null, conds = null;
            MethodHandle resolve = null, parse = null;
            Class<?> lookupClass = null;
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                MethodHandles.Lookup gc = MethodHandles.privateLookupIn(IsoGameCharacter.class, l);
                phase = gc.findVarHandle(IsoGameCharacter.class, "pzo$phase", byte.class);
                sk = gc.findVarHandle(IsoGameCharacter.class, "pzo$snapKeys", Object[].class);
                sv = gc.findVarHandle(IsoGameCharacter.class, "pzo$snapVals", Object[].class);
                MethodHandles.Lookup cvc = MethodHandles.privateLookupIn(CharacterVariableCondition.class, l);
                lhs = cvc.findVarHandle(CharacterVariableCondition.class, "lhsValue", Object.class);
                rhs = cvc.findVarHandle(CharacterVariableCondition.class, "rhsValue", Object.class);
                resolve = cvc.findStatic(CharacterVariableCondition.class, "resolveValue",
                    MethodType.methodType(Object.class, Object.class, IAnimationVariableSource.class));
                parse = cvc.findStatic(CharacterVariableCondition.class, "parseValue", MethodType.methodType(Object.class, String.class, boolean.class));
                lookupClass = Class.forName("zombie.characters.action.conditions.CharacterVariableCondition$CharacterVariableLookup");
                ref = MethodHandles.privateLookupIn(lookupClass, l).findVarHandle(lookupClass, "variableReference", AnimationVariableReference.class);
                MethodHandles.Lookup avr = MethodHandles.privateLookupIn(AnimationVariableReference.class, l);
                handle = avr.findVarHandle(AnimationVariableReference.class, "variableHandle",
                    Class.forName("zombie.core.skinnedmodel.advancedanimation.AnimationVariableHandle"));
                sub = avr.findVarHandle(AnimationVariableReference.class, "subVariableSourceName", String.class);
                Class<?> container = Class.forName("zombie.characters.action.ActionStateContainer");
                cont = MethodHandles.privateLookupIn(ActionContext.class, l).findVarHandle(ActionContext.class, "actionStateContainer", container);
                MethodHandles.Lookup c = MethodHandles.privateLookupIn(container, l);
                cur = c.findVarHandle(container, "currentState", ActionState.class);
                kids = c.findVarHandle(container, "childStates", ArrayList.class);
                conds = MethodHandles.privateLookupIn(ActionTransition.class, l).findVarHandle(ActionTransition.class, "conditions", List.class);
            } catch (Throwable t) {
                PZOLogger.warn("[ActionParallel] game structure not as expected, disabled: " + t);
                active = false;
            }
            PHASE = phase;
            SNAP_KEYS = sk;
            SNAP_VALS = sv;
            CVC_LHS = lhs;
            CVC_RHS = rhs;
            LOOKUP_REF = ref;
            REF_HANDLE = handle;
            REF_SUBSOURCE = sub;
            AC_CONTAINER = cont;
            CONT_CURRENT = cur;
            CONT_CHILDREN = kids;
            TRANSITION_CONDITIONS = conds;
            RESOLVE = resolve;
            PARSE = parse;
            LOOKUP_CLASS = lookupClass;
        }
    }

    private ActionParallel() {}

    public static void setActive(boolean value) {
        active = value;
    }

    static final int PATCH_CHARACTER = 1, PATCH_ZOMBIE = 2, PATCH_BUCKET = 4, PATCH_CONDITION = 8;
    private static int patchedBits;

    // The split is only safe when every piece of it is in place
    static synchronized void markPatched(int bit) {
        patchedBits |= bit;
        allPatched = patchedBits == (PATCH_CHARACTER | PATCH_ZOMBIE | PATCH_BUCKET | PATCH_CONDITION);
    }

    // The purity audit reads class files, so it runs once in the background instead of on a game frame
    private static void startAudit() {
        Thread t = new Thread(() -> {
            try {
                CallbackPurity p = new CallbackPurity(ActionParallel.class.getClassLoader());
                p.run();
                pureKeys = p.pureKeys();
                PZOLogger.success("[ActionParallel] Variable callbacks audited: " + pureKeys.size() + " of " + p.keys.size() + " are pure reads");
            } catch (Throwable e) {
                PZOLogger.warn("[ActionParallel] callback audit failed, reads stay on the game thread: " + e);
            }
        }, "PZO-CallbackAudit");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void ensureWorkers() {
        if (workers != null) return;
        workerCount = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "PZO-ActionRead");
            t.setDaemon(true);
            return t;
        });
        startAudit();
    }

    // ---- phase 1: game thread, inside the bucket loop ----

    public static void beginBucket() {
        if (!active || !allPatched || GameClient.client || GameServer.server) return;
        if (gameThread == null) gameThread = Thread.currentThread();
        if (Thread.currentThread() != gameThread) return;
        ensureWorkers();
        collecting = true;
    }

    /** At the point stock calls ActionContext.update(): true means stop here, the rest runs after the loop. */
    public static boolean defer(IsoGameCharacter chr) {
        if (!collecting || !(chr instanceof IsoZombie z) || Thread.currentThread() != gameThread) return false;
        if (z.isBeingGrappled() || z.isGrappling() || z.getReanimatedPlayer() != null || z.isReanimatedForGrappleOnly()) return false;
        H.PHASE.set(chr, DEFERRED);
        queue.add(z);
        return true;
    }

    public static boolean isDeferred(IsoGameCharacter chr) {
        return H.PHASE != null && (byte) H.PHASE.get(chr) == DEFERRED;
    }

    public static boolean isResuming(IsoGameCharacter chr) {
        return H.PHASE != null && (byte) H.PHASE.get(chr) == RESUMING;
    }

    public static void resumed(IsoGameCharacter chr) {
        H.PHASE.set(chr, NONE);
    }

    // ---- phases 2 and 3: after the bucket loop ----

    public static void flushBucket() {
        if (!collecting) return;
        collecting = false;
        int n = queue.size();
        if (n == 0) return;
        deferred.addAndGet(n);
        Set<String> keys = pureKeys;
        boolean read = keys != null && n >= MIN_PARALLEL;
        if (read) {
            readOnWorkers(keys);
        }
        try {
            for (int i = 0; i < n; i++) {
                IsoZombie z = queue.get(i);
                resumingZombie = z;
                resumingKeys = read ? (Object[]) H.SNAP_KEYS.get(z) : null;
                resumingVals = read ? (Object[]) H.SNAP_VALS.get(z) : null;
                resumingCount = resumingKeys == null ? 0 : countOf(resumingKeys);
                H.PHASE.set(z, RESUMING);
                try {
                    z.postupdate();
                } catch (Throwable t) {
                    PZOLogger.warn("[ActionParallel] deferred postupdate of a zombie threw: " + t);
                } finally {
                    H.PHASE.set(z, NONE);
                }
            }
        } finally {
            resumingZombie = null;
            resumingKeys = resumingVals = null;
            queue.clear();
        }
    }

    private static int countOf(Object[] keys) {
        int c = 0;
        while (c < keys.length && keys[c] != null) c++;
        return c;
    }

    private static void readOnWorkers(Set<String> keys) {
        int n = queue.size();
        int chunks = Math.min(workerCount, (n + 15) / 16);
        CountDownLatch done = new CountDownLatch(chunks);
        for (int c = 0; c < chunks; c++) {
            int from = n * c / chunks, to = n * (c + 1) / chunks;
            workers.execute(() -> {
                try {
                    for (int i = from; i < to; i++) {
                        preReadZombie(queue.get(i), keys);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Worker: the game thread is waiting, so the zombie and its action states do not change meanwhile
    private static void preReadZombie(IsoZombie z, Set<String> keys) {
        Object[] k = (Object[]) H.SNAP_KEYS.get(z);
        Object[] v = (Object[]) H.SNAP_VALS.get(z);
        if (k == null) {
            k = new Object[64];
            v = new Object[64];
        }
        int count = 0;
        try {
            ActionContext ctx = z.getActionContext();
            Object container = ctx == null ? null : H.AC_CONTAINER.get(ctx);
            if (container != null) {
                ActionState current = (ActionState) H.CONT_CURRENT.get(container);
                if (current != null) count = preReadState(z, current, keys, k, v, count);
                ArrayList<?> children = (ArrayList<?>) H.CONT_CHILDREN.get(container);
                for (int i = 0; i < children.size(); i++) {
                    count = preReadState(z, (ActionState) children.get(i), keys, k, v, count);
                }
            }
        } catch (Throwable t) {
            count = 0; // anything unexpected: this zombie reads everything live
        }
        k[Math.min(count, k.length - 1)] = null;
        H.SNAP_KEYS.set(z, k);
        H.SNAP_VALS.set(z, v);
        preRead.addAndGet(count);
    }

    // Returns the new count; a full snapshot stops growing and the rest are read live
    private static int preReadState(IsoZombie z, ActionState state, Set<String> keys, Object[] k, Object[] v, int count) throws Throwable {
        for (ActionTransition t : state.transitions) {
            @SuppressWarnings("unchecked")
            List<IActionCondition> conds = (List<IActionCondition>) H.TRANSITION_CONDITIONS.get(t);
            for (IActionCondition cond : conds) {
                if (!(cond instanceof CharacterVariableCondition cvc)) continue;
                for (int side = 0; side < 2; side++) {
                    Object operand = side == 0 ? H.CVC_LHS.get(cvc) : H.CVC_RHS.get(cvc);
                    if (operand == null || operand.getClass() != H.LOOKUP_CLASS || indexOf(k, count, operand) >= 0) continue;
                    if (count >= k.length - 1) return count;
                    Object value = readPure(z, operand, keys);
                    if (value == null) continue;
                    k[count] = operand;
                    v[count] = value;
                    count++;
                }
            }
        }
        return count;
    }

    private static int indexOf(Object[] k, int count, Object key) {
        for (int i = 0; i < count; i++) if (k[i] == key) return i;
        return -1;
    }

    // The resolved value stock would compute, or null when this variable must be read on the game thread
    private static Object readPure(IsoZombie z, Object lookup, Set<String> keys) throws Throwable {
        AnimationVariableReference ref = (AnimationVariableReference) H.LOOKUP_REF.get(lookup);
        // An unresolved handle is allocated lazily from a global pool; a sub-source lookup reaches other objects
        if (ref == null || H.REF_HANDLE.get(ref) == null || H.REF_SUBSOURCE.get(ref) != null) return null;
        IAnimationVariableSlot slot = ref.getVariable(z);
        if (slot == null) return NULL;
        if (slot instanceof AnimationVariableSlotCallback<?> cb) {
            if (!keys.contains(cb.getKey()) || !isHostLambda(cb)) return null;
        } else if (!slot.getClass().getName().startsWith("zombie.core.skinnedmodel.advancedanimation.AnimationVariableSlot")) {
            return null;
        }
        String s = slot.getValueString();
        if (s == null) return NULL;
        Object parsed = (Object) H.PARSE.invokeExact(s, false);
        return parsed == null ? NULL : parsed;
    }

    private static final VarHandle CB_GET = lookupCallbackGet();

    private static VarHandle lookupCallbackGet() {
        try {
            return MethodHandles.privateLookupIn(AnimationVariableSlotCallback.class, MethodHandles.lookup())
                .findVarHandle(AnimationVariableSlotCallback.class, "callbackGet", java.util.function.Supplier.class);
        } catch (Throwable t) {
            return null;
        }
    }

    // The audited getter is a lambda of the game's character classes; a mod that replaced the slot's callback is not
    private static boolean isHostLambda(AnimationVariableSlotCallback<?> cb) {
        if (CB_GET == null) return false;
        Object get = CB_GET.get(cb);
        if (get == null) return false;
        String n = get.getClass().getName();
        return n.startsWith("zombie.characters.IsoZombie$$Lambda") || n.startsWith("zombie.characters.IsoGameCharacter$$Lambda")
            || n.startsWith("zombie.iso.IsoMovingObject$$Lambda");
    }

    // CharacterVariableCondition.passes: replaces both resolveValue calls
    public static Object resolve(Object value, IAnimationVariableSource owner) throws Throwable {
        if (resumingCount > 0 && owner == resumingZombie && value != null && value.getClass() == H.LOOKUP_CLASS) {
            Object[] k = resumingKeys;
            for (int i = 0; i < resumingCount; i++) {
                if (k[i] == value) {
                    preReadHits.incrementAndGet();
                    Object r = resumingVals[i];
                    return r == NULL ? null : r;
                }
            }
        }
        return (Object) H.RESOLVE.invokeExact(value, owner);
    }

    // ---- ASM ----

    private static final String SELF = "com/pzoptimizer/ActionParallel";
    private static final String CHR = "zombie/characters/IsoGameCharacter";

    private static InsnList call(String name, String desc) {
        InsnList l = new InsnList();
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, name, desc, false));
        return l;
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode n) {
        do {
            n = n.getPrevious();
        } while (n != null && n.getOpcode() < 0);
        return n;
    }

    // IsoGameCharacter: the phase fields, the postUpdateAnimating split and the postUpdateInternal skip/resume
    static boolean patchCharacter(ClassNode node) {
        MethodNode anim = AsmSupport.findMethod(node, "postUpdateAnimating", "()V");
        MethodNode internal = AsmSupport.findMethod(node, "postUpdateInternal", "()V");
        if (anim == null || internal == null) return false;

        int animLocal = -1;
        MethodInsnNode getAnimPlayer = null;
        AbstractInsnNode splitAt = null;
        for (AbstractInsnNode insn = anim.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (getAnimPlayer == null && insn instanceof MethodInsnNode mi && "getAnimationPlayer".equals(mi.name)
                    && insn.getNext() instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE) {
                getAnimPlayer = mi;
                animLocal = v.var;
            }
            if (insn instanceof MethodInsnNode mi && "update".equals(mi.name) && "zombie/characters/action/ActionContext".equals(mi.owner)
                    && "()V".equals(mi.desc)) {
                AbstractInsnNode getCtx = prevReal(mi);
                AbstractInsnNode load = getCtx == null ? null : prevReal(getCtx);
                if (getCtx instanceof MethodInsnNode g && "getActionContext".equals(g.name) && load != null && load.getOpcode() == Opcodes.ALOAD) {
                    splitAt = load;
                }
                break;
            }
        }
        if (getAnimPlayer == null || splitAt == null) return false;

        // Split: defer here, and the resume path jumps here
        LabelNode split = new LabelNode();
        InsnList deferHere = call("defer", "(L" + CHR + ";)Z");
        deferHere.add(new JumpInsnNode(Opcodes.IFEQ, split));
        deferHere.add(new InsnNode(Opcodes.RETURN));
        deferHere.add(split);
        anim.instructions.insertBefore(splitAt, deferHere);

        LabelNode normal = new LabelNode();
        InsnList resume = call("isResuming", "(L" + CHR + ";)Z");
        resume.add(new JumpInsnNode(Opcodes.IFEQ, normal));
        resume.add(new VarInsnNode(Opcodes.ALOAD, 0));
        resume.add(new MethodInsnNode(getAnimPlayer.getOpcode(), getAnimPlayer.owner, getAnimPlayer.name, getAnimPlayer.desc, getAnimPlayer.itf));
        resume.add(new VarInsnNode(Opcodes.ASTORE, animLocal));
        resume.add(call("resumed", "(L" + CHR + ";)V"));
        resume.add(new JumpInsnNode(Opcodes.GOTO, split));
        resume.add(normal);
        anim.instructions.insert(resume);

        // postUpdateInternal: [super.postupdate()] [postUpdateAnimating()] [clears...]
        MethodInsnNode animCall = null;
        for (AbstractInsnNode insn = internal.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "postUpdateAnimating".equals(mi.name)) {
                animCall = mi;
                break;
            }
        }
        if (animCall == null || prevReal(animCall) == null || prevReal(animCall).getOpcode() != Opcodes.ALOAD) return false;
        if (!skipAndResume(internal, prevReal(animCall), animCall)) return false;

        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT, "pzo$phase", "B", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT, "pzo$snapKeys", "[Ljava/lang/Object;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT, "pzo$snapVals", "[Ljava/lang/Object;", null, null));
        return true;
    }

    // Resuming: jump straight to `resumeAt`. After `afterCall`: return while the character is deferred.
    private static boolean skipAndResume(MethodNode m, AbstractInsnNode resumeAt, AbstractInsnNode afterCall) {
        LabelNode target = new LabelNode();
        m.instructions.insertBefore(resumeAt, target);
        LabelNode normal = new LabelNode();
        InsnList head = call("isResuming", "(L" + CHR + ";)Z");
        head.add(new JumpInsnNode(Opcodes.IFEQ, normal));
        head.add(new JumpInsnNode(Opcodes.GOTO, target));
        head.add(normal);
        m.instructions.insert(head);

        LabelNode cont = new LabelNode();
        InsnList stop = call("isDeferred", "(L" + CHR + ";)Z");
        stop.add(new JumpInsnNode(Opcodes.IFEQ, cont));
        stop.add(new InsnNode(Opcodes.RETURN));
        stop.add(cont);
        m.instructions.insert(afterCall, stop);
        return true;
    }

    // IsoZombie.postUpdateInternal: [chasing count] [super.postupdate()] [zombie tail]
    static boolean patchZombie(ClassNode node) {
        MethodNode internal = AsmSupport.findMethod(node, "postUpdateInternal", "()V");
        if (internal == null) return false;
        for (AbstractInsnNode insn = internal.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL && CHR.equals(mi.owner)
                    && "postupdate".equals(mi.name) && "()V".equals(mi.desc)) {
                AbstractInsnNode load = prevReal(mi);
                if (load == null || load.getOpcode() != Opcodes.ALOAD) return false;
                return skipAndResume(internal, load, mi);
            }
        }
        return false;
    }

    // MovingObjectUpdateSchedulerUpdateBucket.postupdate(int): begin before the loop, flush before the multiplier reset
    static boolean patchBucket(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "postupdate", "(I)V");
        if (m == null) return false;
        MethodInsnNode lastGameTime = null;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "zombie/GameTime".equals(mi.owner) && "getInstance".equals(mi.name)) lastGameTime = mi;
        }
        if (lastGameTime == null) return false;
        m.instructions.insertBefore(lastGameTime, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "flushBucket", "()V", false));
        m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "beginBucket", "()V", false));
        return true;
    }

    // CharacterVariableCondition.passes: both operand reads go through resolve()
    static boolean patchCondition(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, mn -> "passes".equals(mn.name));
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && "resolveValue".equals(mi.name)
                    && node.name.equals(mi.owner)) {
                MethodInsnNode repl = new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "resolve", mi.desc, false);
                m.instructions.set(mi, repl);
                insn = repl;
                n++;
            }
        }
        return n == 2;
    }
}
