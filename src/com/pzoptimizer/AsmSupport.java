package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.ClassReader;
import com.pzoptimizer.shaded.asm.ClassWriter;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
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

    private AsmSupport() {}

    public static byte[] transform(byte[] classfile, ClassLoader loader, String what, NodeTransform t) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(classfile).accept(node, ClassReader.EXPAND_FRAMES);
            if (!t.apply(node)) {
                PZOLogger.warn("[AsmSupport] " + what + ": target code not found, class left unchanged");
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
