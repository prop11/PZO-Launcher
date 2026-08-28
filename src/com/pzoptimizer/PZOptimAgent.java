package com.pzoptimizer;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Project Zomboid Build 42 - Runtime JVM Instrumentation Agent.
 */
public class PZOptimAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        PZOLogger.info("[PZO Agent] Build 42 Instrumentation Agent Active");
        inst.addTransformer(new EngineTransformer());
        HighPrecisionTimer.initialize();
        StreamBufferBooster.applyStreamTweaks();
        PZOLogger.success("[PZO Agent] Engine transformer and native timers attached");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    static class EngineTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            return null;
        }
    }
}
