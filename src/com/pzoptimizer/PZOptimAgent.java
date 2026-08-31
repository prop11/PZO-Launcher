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
        PZOLogger.info("[PZO Agent] Build 42 JVM Instrumentation Agent Active");
        
        try {
            inst.addTransformer(new EngineTransformer(), true);
        } catch (Throwable ignored) {}

        // Early-boot runtime properties & core memory tuning
        HotSpotJITCompilerTuner.tuneRuntimeProperties();
        PZOEngineBridge.initialize();
        HighPrecisionTimer.initialize();
        StreamBufferBooster.applyStreamTweaks();
        SaveGameStreamBooster.tuneSaveEngine();
        EngineFeaturesTuner.initializeEngineFeatures();
        WorldStreamerBooster.startDaemon();
        PZOFastMath.initialize();
        GenerationalHeapCleaner.startGovernor();
        AsyncEntityDistanceCache.initialize();
        CorpseAudioGovernor.applyCorpseAudioLimits();
        EngineGLStateGovernor.initialize();
        EngineFramePacer.initialize();
        NativeDirectMemoryPool.initialize();
        FastBitwiseChunkIndexer.initialize();
        
        PZOLogger.success("[PZO Agent] Live Bytecode Instrumentation engine attached");

        // Automatically load and hook any ZombieBuddy / Java Workshop mods with full Instrumentation
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
