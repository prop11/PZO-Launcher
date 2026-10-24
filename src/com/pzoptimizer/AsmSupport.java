package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.ClassReader;
import com.pzoptimizer.shaded.asm.ClassWriter;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Method-level class rewriting for the agent, using the bundled (relocated) ASM. A transform edits a ClassNode in
 * place and returns true if it changed anything; the class is then rewritten with recomputed stack map frames.
 * Any exception leaves the class as it was loaded, so a game update that moves code degrades to vanilla.
 */
public final class AsmSupport {

    public interface NodeTransform {
        boolean apply(ClassNode node) throws Exception;
    }

    // The class being retransformed, set by the agent around the call; null for a first load
    static final ThreadLocal<Class<?>> REDEFINING = new ThreadLocal<>();

    private AsmSupport() {}

    public static byte[] transform(byte[] classfile, ClassLoader loader, String what, NodeTransform t) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(classfile).accept(node, ClassReader.EXPAND_FRAMES);
            int fields = node.fields.size(), methods = node.methods.size(), interfaces = node.interfaces.size();
            java.util.Set<String> shapeBefore = keepClassShapes() ? shapeOf(node) : null;
            if (!t.apply(node)) {
                PZOLogger.warn("[AsmSupport] " + what + ": target code not found, class left unchanged");
                return null;
            }
            if (hasDuplicateMembers(node)) {
                // Another agent (ViewpointFPS+ via ZombieBuddy) redefined the class with bytes this agent had already
                // patched, so the patch is in the input; adding its members again is a ClassFormatError
                PZOLogger.warn("[AsmSupport] " + what + ": class already carries this patch, left as is");
                return null;
            }
            if (shapeBefore != null && !shapeBefore.equals(shapeOf(node))) {
                PZOLogger.info("[AsmSupport] " + what + ": stands down while a ZombieBuddy Java mod is active (adds or renames class members)");
                return null;
            }
            Class<?> live = REDEFINING.get();
            if (live != null && !sameShape(live, node, fields, methods, interfaces)) {
                PZOLogger.warn("[AsmSupport] " + what + ": adds members, which a retransform cannot; class left unchanged");
                return null;
            }
            ClassWriter cw = new LoaderAwareClassWriter(loader);
            node.accept(cw);
            return cw.toByteArray();
        } catch (Throwable e) {
            PZOLogger.warn("[AsmSupport] " + what + " failed, class left unchanged: " + e);
            return null;
        }
    }

    /**
     * A retransform may not add or remove members. Another agent (ZombieBuddy, for one) retransforming a class this
     * agent already patched hands over the original bytes, so the patch has to add its members again: that is fine
     * exactly when the live class already has every one of them, i.e. the result has the shape it has now. A class
     * that was loaded before this agent attached has none of them and keeps refusing.
     */
    private static boolean sameShape(Class<?> live, ClassNode node, int fields, int methods, int interfaces) {
        java.util.Set<String> liveFields = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : live.getDeclaredFields()) liveFields.add(f.getName());
        for (int i = fields; i < node.fields.size(); i++) {
            if (!liveFields.contains(node.fields.get(i).name)) return false;
        }
        java.util.Set<String> liveMethods = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : live.getDeclaredMethods()) {
            liveMethods.add(m.getName() + com.pzoptimizer.shaded.asm.Type.getMethodDescriptor(m));
        }
        for (int i = methods; i < node.methods.size(); i++) {
            MethodNode m = node.methods.get(i);
            if (!liveMethods.contains(m.name + m.desc)) return false;
        }
        java.util.Set<String> liveInterfaces = new java.util.HashSet<>();
        for (Class<?> c : live.getInterfaces()) liveInterfaces.add(c.getName().replace('.', '/'));
        for (int i = interfaces; i < node.interfaces.size(); i++) {
            if (!liveInterfaces.contains(node.interfaces.get(i))) return false;
        }
        return true;
    }

    private static volatile Boolean keepShapes;

    /**
     * ZombieBuddy re-patches classes for its mods in batches. When one of them is a class this agent reshaped (added,
     * removed or renamed members or interfaces), the JVM rejects ZombieBuddy's version of it and drops the rest of
     * that batch, so the mod's hooks are silently missing: Viewpoint got no clicks, Knox Event Expanded reported a
     * missing Java class, Knox Inventory's menu and ShadowZ / Peek a View did nothing. With any ZombieBuddy Java mod
     * in the startup mod list, reshaping patches stand down; body-only patches stay. ZombieBuddy installed without
     * such a mod changes nothing.
     */
    static boolean keepClassShapes() {
        Boolean k = keepShapes;
        if (k == null) {
            String mod = JavaModLoader.isZombieBuddyPresent() ? JavaModLoader.findZombieBuddyJavaStartupMod() : null;
            k = mod != null;
            keepShapes = k;
            if (k) PZOLogger.info("[AsmSupport] ZombieBuddy Java mod at startup (" + mod + "): patches that add or rename class members stand down");
        }
        return k;
    }

    private static java.util.Set<String> shapeOf(ClassNode node) {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (FieldNode f : node.fields) s.add("F " + f.name + " " + f.desc);
        for (MethodNode m : node.methods) s.add("M " + m.name + m.desc);
        for (String i : node.interfaces) s.add("I " + i);
        return s;
    }

    private static boolean hasDuplicateMembers(ClassNode node) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (FieldNode f : node.fields) {
            if (!seen.add("F " + f.name + " " + f.desc)) return true;
        }
        for (MethodNode m : node.methods) {
            if (!seen.add("M " + m.name + m.desc)) return true;
        }
        return false;
    }

    public static MethodNode findMethod(ClassNode node, String name, String desc) {
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    public static MethodNode findMethod(ClassNode node, Predicate<MethodNode> p) {
        for (MethodNode m : node.methods) {
            if (p.test(m)) {
                return m;
            }
        }
        return null;
    }

    /**
     * COMPUTE_FRAMES needs the common superclass of two types. The default implementation loads classes, which
     * inside a ClassFileTransformer can recurse into the transformer or initialise game classes too early, so the
     * hierarchy is read from class file bytes instead.
     */
    static final class LoaderAwareClassWriter extends ClassWriter {
        private final ClassLoader loader;
        private final Map<String, String[]> cache = new HashMap<>();

        LoaderAwareClassWriter(ClassLoader loader) {
            super(ClassWriter.COMPUTE_FRAMES);
            this.loader = loader != null ? loader : ClassLoader.getSystemClassLoader();
        }

        // [superName, isInterface ? "I" : "C"]
        private String[] info(String type) {
            String[] r = cache.get(type);
            if (r != null) {
                return r;
            }
            try (InputStream in = open(type)) {
                if (in != null) {
                    ClassReader cr = new ClassReader(in);
                    r = new String[] {cr.getSuperName(), (cr.getAccess() & 0x0200) != 0 ? "I" : "C"};
                }
            } catch (Exception e) {
                r = null;
            }
            if (r == null) {
                // JDK classes are not always readable as resources, and on a newer JDK their class files can be
                // newer than ASM parses. The platform loader only holds JDK modules, so this lookup can never load
                // or initialise game code.
                try {
                    Class<?> k = Class.forName(type.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                    Class<?> sup = k.getSuperclass();
                    r = new String[] {sup == null ? null : sup.getName().replace('.', '/'), k.isInterface() ? "I" : "C"};
                } catch (ClassNotFoundException e) {
                    throw new TypeNotPresentException(type, e);
                }
            }
            cache.put(type, r);
            return r;
        }

        private InputStream open(String type) {
            String res = type + ".class";
            InputStream in = loader.getResourceAsStream(res);
            return in != null ? in : ClassLoader.getSystemResourceAsStream(res);
        }

        private boolean isAssignable(String to, String from) {
            for (String t = from; t != null; t = info(t)[0]) {
                if (t.equals(to)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected String getCommonSuperClass(String a, String b) {
            if (a.equals(b)) {
                return a;
            }
            if (isAssignable(a, b)) {
                return a;
            }
            if (isAssignable(b, a)) {
                return b;
            }
            if ("I".equals(info(a)[1]) || "I".equals(info(b)[1])) {
                return "java/lang/Object";
            }
            String t = a;
            do {
                t = info(t)[0];
                if (t == null) {
                    return "java/lang/Object";
                }
            } while (!isAssignable(t, b));
            return t;
        }

        @Override
        protected ClassLoader getClassLoader() {
            return loader;
        }
    }
}
