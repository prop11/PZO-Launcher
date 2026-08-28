package me.zed_0xff.zombie_buddy;

import com.pzoptimizer.PZOptimAgent;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

/**
 * ZombieBuddy API Emulation Bridge - Accessor.
 * Provides live JVM Instrumentation access to ZombieBuddy mods.
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
}
