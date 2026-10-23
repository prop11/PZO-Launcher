package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.ClassReader;
import com.pzoptimizer.shaded.asm.Handle;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InvokeDynamicInsnNode;
import com.pzoptimizer.shaded.asm.tree.LdcInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Static audit of the animation-variable callbacks a zombie registers, deciding which ones are pure reads that a
 * worker thread may call. The getter lambda of every setVariable("key", getter, ...) in the registering classes is
 * found in their bytecode and analysed together with everything it calls: no field, static or array store, no
 * monitor, no invokedynamic other than string concatenation, and no call outside the game's own code except an
 * allowlist of side-effect-free JDK methods. Virtual calls are checked against the implementation in every class of
 * the receiver hierarchies a zombie callback can reach. Anything the analysis cannot prove is impure, and an impure
 * key is simply read on the game thread as stock does.
 */
public final class CallbackPurity {

    static final String[] REGISTRARS = {
        "zombie/characters/IsoZombie", "zombie/characters/IsoGameCharacter", "zombie/iso/IsoMovingObject",
    };

    // Subclasses whose overrides a virtual call on one of their supertypes may reach
    private static final String[] KNOWN_SUBTYPES = {
        "zombie/characters/IsoZombie", "zombie/characters/IsoPlayer", "zombie/characters/animals/IsoAnimal",
        "zombie/characters/IsoGameCharacter", "zombie/characters/IsoSurvivor", "zombie/iso/IsoMovingObject",
        "zombie/vehicles/BaseVehicle", "zombie/iso/objects/IsoDeadBody",
    };

    private static final int MAX_DEPTH = 10;

    private final ClassLoader loader;
    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, Boolean> memo = new HashMap<>();
    private final Set<String> inProgress = new HashSet<>();

    // key -> pure, per registering class; a key registered impure anywhere stays impure
    public final Map<String, Boolean> keys = new HashMap<>();
    public String lastReason;

    public CallbackPurity(ClassLoader loader) {
        this.loader = loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    public Set<String> pureKeys() {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Boolean> e : keys.entrySet()) if (e.getValue()) out.add(e.getKey());
        return out;
    }

    public void run() {
        for (String owner : REGISTRARS) {
            ClassNode cn = load(owner);
            if (cn == null) continue;
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("registerVariableCallbacks")) continue;
                scanRegistrations(m);
            }
        }
    }

    private static final Set<String> GETTER_SAMS = Set.of("get", "getAsBoolean", "getAsInt", "getAsFloat", "getAsDouble", "getAsLong");

    // setVariable("key", <getter>, ...): the getter is the invokedynamic right after the key constant (only the
    // captured `this` may come between). A key whose getter is anything else is left out, and so is read on the
    // game thread.
    private void scanRegistrations(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LdcInsnNode ldc && ldc.cst instanceof String key)) continue;
            AbstractInsnNode n = insn.getNext();
            while (n != null && (n.getOpcode() < 0 || n.getOpcode() == Opcodes.ALOAD)) n = n.getNext();
            if (!(n instanceof InvokeDynamicInsnNode indy) || !GETTER_SAMS.contains(indy.name)
                    || !"java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())
                    || indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle h)) {
                continue;
            }
            // Only count it when this getter is passed to setVariable before another key starts
            boolean registered = false;
            for (AbstractInsnNode p = indy.getNext(); p != null; p = p.getNext()) {
                if (p instanceof LdcInsnNode l2 && l2.cst instanceof String) break;
                if (p instanceof MethodInsnNode mi && "setVariable".equals(mi.name)) {
                    registered = true;
                    break;
                }
            }
            if (!registered || key.isEmpty()) continue;
            boolean pure = isPure(h.getOwner(), h.getName(), h.getDesc(), h.getTag() != Opcodes.H_INVOKESTATIC, 0);
            keys.merge(key, pure, (a, b) -> a && b);
        }
    }

    private ClassNode load(String internalName) {
        if (classes.containsKey(internalName)) return classes.get(internalName);
        ClassNode cn = null;
        try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
            if (in != null) {
                cn = new ClassNode();
                new ClassReader(in).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            }
        } catch (Exception e) {
            cn = null;
        }
        classes.put(internalName, cn);
        return cn;
    }

    // The method a call resolves to when dispatched on an instance of exactly `cls`
    private MethodNode resolve(String cls, String name, String desc, String[] ownerOut) {
        for (String c = cls; c != null; ) {
            ClassNode cn = load(c);
            if (cn == null) return null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) {
                    ownerOut[0] = c;
                    return m;
                }
            }
            c = cn.superName;
        }
        return null;
    }

    private boolean isSubtype(String sub, String sup) {
        for (String c = sub; c != null; ) {
            if (c.equals(sup)) return true;
            ClassNode cn = load(c);
            if (cn == null) return false;
            c = cn.superName;
        }
        return false;
    }

    private boolean isPure(String owner, String name, String desc, boolean virtual, int depth) {
        if (isAllowedExternal(owner, name, desc)) return true;
        if (!owner.startsWith("zombie/")) {
            lastReason = "external call " + owner + "." + name + desc;
            return false;
        }
        if (depth > MAX_DEPTH) {
            lastReason = "too deep at " + owner + "." + name;
            return false;
        }
        // Every implementation a virtual call can reach from the known receiver types must be pure
        Set<String> impls = new HashSet<>();
        String[] out = new String[1];
        if (resolve(owner, name, desc, out) != null) impls.add(out[0]);
        if (virtual) {
            for (String sub : KNOWN_SUBTYPES) {
                if (!sub.equals(owner) && isSubtype(sub, owner) && resolve(sub, name, desc, out) != null) impls.add(out[0]);
            }
        }
        if (impls.isEmpty()) {
            lastReason = "unresolved " + owner + "." + name + desc;
            return false;
        }
        for (String implOwner : impls) {
            if (!isBodyPure(implOwner, name, desc, depth)) return false;
        }
        return true;
    }

    private boolean isBodyPure(String owner, String name, String desc, int depth) {
        String id = owner + "." + name + desc;
        Boolean known = memo.get(id);
        if (known != null) return known;
        if (inProgress.contains(id)) return true; // recursion: decided by the outer frame
        ClassNode cn = load(owner);
        MethodNode m = null;
        if (cn != null) for (MethodNode mm : cn.methods) if (mm.name.equals(name) && mm.desc.equals(desc)) m = mm;
        if (m == null || (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0) {
            lastReason = "no pure body for " + id;
            memo.put(id, false);
            return false;
        }
        inProgress.add(id);
        boolean pure = true;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null && pure; insn = insn.getNext()) {
            int op = insn.getOpcode();
            switch (op) {
                case Opcodes.PUTFIELD, Opcodes.PUTSTATIC, Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                     Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE, Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> {
                    lastReason = "store or monitor in " + id;
                    pure = false;
                }
                case Opcodes.INVOKEDYNAMIC -> {
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                    if (!"java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())) {
                        lastReason = "invokedynamic in " + id;
                        pure = false;
                    }
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL -> {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if (op == Opcodes.INVOKESPECIAL && mi.name.equals("<init>")) {
                        // A new object's constructor writes only that object, unless it reaches further
                        pure = isAllowedExternal(mi.owner, mi.name, mi.desc) || isBodyPureConstructor(mi.owner, mi.desc, depth + 1);
                    } else {
                        boolean virtual = op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE;
                        pure = isPure(mi.owner, mi.name, mi.desc, virtual, depth + 1);
                    }
                }
                default -> { }
            }
        }
        inProgress.remove(id);
        memo.put(id, pure);
        return pure;
    }

    // Constructors may store into `this` (the new object) but nothing else
    private boolean isBodyPureConstructor(String owner, String desc, int depth) {
        if (!owner.startsWith("zombie/")) {
            lastReason = "external constructor " + owner;
            return false;
        }
        ClassNode cn = load(owner);
        if (cn == null) return false;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("<init>") || !m.desc.equals(desc)) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                int op = insn.getOpcode();
                if (op == Opcodes.PUTFIELD) {
                    // Only `aload_0; <single push>; putfield` counts as a store into the new object
                    AbstractInsnNode p = insn.getPrevious();
                    AbstractInsnNode pp = p == null ? null : p.getPrevious();
                    if (!(pp instanceof com.pzoptimizer.shaded.asm.tree.VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0)) return false;
                } else if (op == Opcodes.PUTSTATIC || (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) || op == Opcodes.MONITORENTER) {
                    return false;
                } else if (insn instanceof MethodInsnNode mi) {
                    if (mi.name.equals("<init>")) {
                        if (!mi.owner.equals("java/lang/Object") && !isBodyPureConstructor(mi.owner, mi.desc, depth + 1)
                                && !isAllowedExternal(mi.owner, mi.name, mi.desc)) return false;
                    } else if (!isPure(mi.owner, mi.name, mi.desc, mi.getOpcode() != Opcodes.INVOKESTATIC, depth + 1)) {
                        return false;
                    }
                } else if (op == Opcodes.INVOKEDYNAMIC) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static final Set<String> PURE_JDK_CLASSES = Set.of(
        "java/lang/Math", "java/lang/StrictMath", "java/lang/Float", "java/lang/Double", "java/lang/Integer",
        "java/lang/Long", "java/lang/Short", "java/lang/Byte", "java/lang/Boolean", "java/lang/Character",
        "java/lang/String", "java/util/Objects");

    private static final Set<String> READ_ONLY_COLLECTION_METHODS = Set.of(
        "size", "isEmpty", "get", "contains", "containsKey", "containsValue", "indexOf", "getFirst", "getLast", "peek");

    private static boolean isAllowedExternal(String owner, String name, String desc) {
        if (PURE_JDK_CLASSES.contains(owner)) {
            // String.intern and the like write global state; everything else on these classes is a pure function
            return !name.equals("intern");
        }
        if (owner.equals("java/lang/Object") && (name.equals("<init>") || name.equals("getClass"))) return true;
        if (owner.equals("java/lang/Enum") && (name.equals("ordinal") || name.equals("name") || name.equals("toString"))) return true;
        if ((owner.equals("java/util/ArrayList") || owner.equals("java/util/List") || owner.equals("java/util/Collection")
                || owner.equals("java/util/HashMap") || owner.equals("java/util/Map") || owner.equals("java/util/Set")
                || owner.equals("java/util/HashSet") || owner.equals("java/util/Stack") || owner.equals("java/util/Vector"))
                && READ_ONLY_COLLECTION_METHODS.contains(name)) {
            return true;
        }
        return false;
    }
}
