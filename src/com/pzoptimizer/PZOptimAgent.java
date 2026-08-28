package com.pzoptimizer;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Project Zomboid Build 42 - Runtime JVM Instrumentation Agent & Java Mod Loader.
 */
public class PZOptimAgent {
    private static volatile Instrumentation instrumentationInstance = null;

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentationInstance = inst;
        PZOLogger.info("[PZO Agent] Build 42 Instrumentation Agent Active");
        inst.addTransformer(new EngineTransformer());
        HighPrecisionTimer.initialize();
        StreamBufferBooster.applyStreamTweaks();
        SaveGameStreamBooster.tuneSaveEngine();
        PZOLogger.success("[PZO Agent] Engine transformer and native timers attached");

        // Automatically load and hook any ZombieBuddy / Java Workshop mods
        try {
            JavaModLoader.loadMods(inst);
        } catch (Throwable t) {
            PZOLogger.error("[PZO Agent] Error during Java mod discovery", t);
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    public static Instrumentation getInstrumentation() {
        return instrumentationInstance;
    }

    static class EngineTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            return null;
        }
    }
}
