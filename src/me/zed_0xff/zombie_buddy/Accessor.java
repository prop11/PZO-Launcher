package me.zed_0xff.zombie_buddy;

import com.pzoptimizer.PZOptimAgent;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * ZombieBuddy API Emulation Bridge - Complete Accessor reflection & transformer helper.
 */
public class Accessor {
    public static Instrumentation getInstrumentation() {
        return PZOptimAgent.getInstrumentation();
    }

    public static void addTransformer(ClassFileTransformer transformer) {
        Instrumentation inst = PZOptimAgent.getInstrumentation();
        if (inst != null && transformer != null) {
            inst.addTransformer(transformer, true);
        }
    }

    public static void addTransformer(ClassFileTransformer transformer, boolean canRetransform) {
        Instrumentation inst = PZOptimAgent.getInstrumentation();
        if (inst != null && transformer != null) {
            inst.addTransformer(transformer, canRetransform);
        }
    }

    public static Class<?> findClass(String... classNames) {
        if (classNames == null) return null;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        for (String name : classNames) {
            if (name == null) continue;
            try {
                return Class.forName(name.replace('/', '.'), true, cl);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static Field findField(Class<?> clazz, String... fieldNames) {
        if (clazz == null || fieldNames == null) return null;
        for (String name : fieldNames) {
            if (name == null) continue;
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Field f = current.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {}
                current = current.getSuperclass();
            }
        }
        return null;
    }

    public static Method findNoArgMethod(Class<?> clazz, String... methodNames) {
        return findMethod(clazz, methodNames, new Class<?>[0]);
    }

    public static Method findMethod(Class<?> clazz, String[] methodNames, Class<?>... paramTypes) {
        if (clazz == null || methodNames == null) return null;
        for (String name : methodNames) {
            if (name == null) continue;
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Method m = (paramTypes == null || paramTypes.length == 0)
                        ? current.getDeclaredMethod(name)
                        : current.getDeclaredMethod(name, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {}
                current = current.getSuperclass();
            }
        }
        return null;
    }

    public static Method findMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        return findMethod(clazz, new String[]{methodName}, paramTypes);
    }

    public static Object getFieldValue(Object target, String... fieldNames) {
        if (target == null) return null;
        Field f = findField(target.getClass(), fieldNames);
        if (f != null) {
            try {
                return f.get(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void setFieldValue(Object target, Object value, String... fieldNames) {
        if (target == null) return;
        Field f = findField(target.getClass(), fieldNames);
        if (f != null) {
            try {
                f.set(target, value);
            } catch (Throwable ignored) {}
        }
    }

    public static Object getStaticFieldValue(Class<?> clazz, String... fieldNames) {
        Field f = findField(clazz, fieldNames);
        if (f != null) {
            try {
                return f.get(null);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void setStaticFieldValue(Class<?> clazz, Object value, String... fieldNames) {
        Field f = findField(clazz, fieldNames);
        if (f != null) {
            try {
                f.set(null, value);
            } catch (Throwable ignored) {}
        }
    }
}
