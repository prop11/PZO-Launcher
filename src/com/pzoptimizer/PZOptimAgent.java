package com.pzoptimizer;

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/** JVM agent entry point: registers the bytecode transformer that applies the PZO engine patches. */
public class PZOptimAgent {
    private static volatile Instrumentation instrumentationInstance = null;

    public static void premain(String agentArgs, Instrumentation inst) {
        if (instrumentationInstance != null) return;
        instrumentationInstance = inst;
        if (IncompatibleModGuard.isPzOptimizationInstalled()) {
            IncompatibleModGuard.reportAndWarn();
            PZOLogger.warn("[PZO Agent] Incompatible mod installed: no transformer registered, agent idle.");
            return;
        }
        if (BenchmarkHarness.isStockBaselineRequested()) {
            // Only the benchmark's render thread timer is installed, so stock and PZO runs are measured alike
            inst.addTransformer(new java.lang.instrument.ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                                        ProtectionDomain pd, byte[] classfileBuffer) {
                    return "zombie/core/opengl/RenderThread".equals(className)
                        ? AsmSupport.transform(classfileBuffer, loader, "RenderThread timing", GpuFrameTimer::patch) : null;
                }
            });
            PZOLogger.info("[PZO Agent] Stock baseline benchmark run: only the benchmark frame timer is registered.");
            return;
        }
        PZOLogger.info("[PZO Agent] Build 42 JVM Instrumentation Agent Active");

        try {
            inst.addTransformer(new EngineTransformer(), true);
            PZOLogger.success("[PZO Agent] Bytecode Transformer registered successfully");

            if (inst.isRetransformClassesSupported()) {
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    String name = c.getName();
                    if ("se.krka.kahlua.j2se.KahluaTableImpl".equals(name) ||
                        "zombie.core.properties.PropertyContainer".equals(name) ||
                        "zombie.fileSystem.FileSystemImpl".equals(name) ||
                        "zombie.characters.IsoPlayer".equals(name) ||
                        "zombie.core.skinnedmodel.advancedanimation.AnimationVariableHandlePool".equals(name) ||
                        "zombie.WorldSoundManager".equals(name) ||
                        "zombie.iso.WorldStreamer".equals(name) ||
                        "zombie.pathfind.nativeCode.PathfindNative".equals(name) ||
                        "zombie.iso.ChunkSaveWorker".equals(name) ||
                        "zombie.characters.IsoGameCharacter".equals(name) ||
                        "zombie.iso.sprite.IsoSprite".equals(name) ||
                        "zombie.entity.components.spriteconfig.SpriteConfig".equals(name) ||
                        "zombie.iso.IsoGridSquare".equals(name) ||
                        "zombie.iso.IsoChunkMap".equals(name) ||
                        "zombie.core.skinnedmodel.animation.AnimationPlayer".equals(name) ||
                        "zombie.pathfind.nativeCode.PathfindNativeThread".equals(name) ||
                        "zombie.iso.IsoWorld".equals(name) ||
                        "zombie.MovingObjectUpdateScheduler".equals(name) ||
                        "zombie.characters.IsoZombie".equals(name) ||
                        "zombie.FileGuidTable".equals(name) ||
                        "zombie.characters.animals.IsoAnimal".equals(name) ||
                        "zombie.iso.fboRenderChunk.FBORenderCell".equals(name) ||
                        "zombie.fileSystem.TexturePackDevice".equals(name) ||
                        "zombie.core.physics.WorldSimulation".equals(name) ||
                        "zombie.GameWindow".equals(name) ||
                        "zombie.iso.PlayerCamera".equals(name) ||
                        "zombie.vehicles.BaseVehicle".equals(name) ||
                        "zombie.core.textures.TextureIDAssetManager".equals(name)) {
                        try {
                            inst.retransformClasses(c);
                            PZOLogger.success("[PZO Agent] Retransformed early-loaded class: " + name);
                        } catch (Throwable t) {
                            PZOLogger.warn("[PZO Agent] Notice during retransformation of " + name + ": " + t.getMessage());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("[PZO Agent] Notice on transformer registration: " + t.getMessage());
        }

        try {
            HotSpotJITCompilerTuner.tuneRuntimeProperties();
            WorldUpscalerGovernor.checkLaunchParams(null);
            PZOEngineBridge.initialize();
            HighPrecisionTimer.initialize();
        } catch (Throwable ignored) {}

        PZOLogger.success("[PZO Agent] Live Bytecode Instrumentation engine attached");

        try {
            JavaModLoader.loadMods(inst);
        } catch (Throwable t) {
            PZOLogger.warn("[PZO Agent] Notice during Java mod discovery: " + t.getMessage());
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    public static Instrumentation getInstrumentation() {
        return instrumentationInstance;
    }

    private static volatile boolean fsrRenderScaleHooks = false;

    /**
     * Installs the FSR render-scale hooks. ProjectZomboid64.exe hands Steam launch options only to main(),
     * not to the JVM, so -pzo_fsr is usually first seen in PZOEntrypoint.main after the agent started;
     * zombie.* classes already loaded by then are retransformed so they get hooked too.
     */
    public static synchronized void enableRenderScaleHooks() {
        if (fsrRenderScaleHooks) return;
        Instrumentation inst = instrumentationInstance;
        if (inst == null) {
            PZOLogger.warn("[PZO Agent] -pzo_fsr requested but the instrumentation agent is not attached - FSR unavailable");
            return;
        }
        fsrRenderScaleHooks = true;
        WorldRenderScale.markInstalled();
        int retransformed = 0;
        if (inst.isRetransformClassesSupported()) {
            List<Class<?>> loaded = new ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getName().startsWith("zombie.") && inst.isModifiableClass(c)) {
                    loaded.add(c);
                }
            }
            for (Class<?> c : loaded) {
                try {
                    inst.retransformClasses(c);
                    retransformed++;
                } catch (Throwable t) {
                    PZOLogger.warn("[PZO Agent] FSR retransform notice for " + c.getName() + ": " + t.getMessage());
                }
            }
        }
        PZOLogger.success("[PZO Agent] -pzo_fsr: world render-scale hooks installed (" + retransformed + " already-loaded game classes rechecked)");
    }

    private static volatile boolean bytecodeBloodCap = true;

    public static boolean isBytecodeBloodCap() {
        return bytecodeBloodCap;
    }

    public static void setBytecodeBloodCap(boolean enabled) {
        bytecodeBloodCap = enabled;
        BloodCapGovernor.setMasterEnabled(enabled);
    }

    static class EngineTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                 ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            AsmSupport.REDEFINING.set(classBeingRedefined);
            byte[] specific;
            try {
                specific = transformSpecific(loader, className, classfileBuffer);
            } finally {
                AsmSupport.REDEFINING.remove();
            }
            if (!fsrRenderScaleHooks || className == null || classfileBuffer == null || !className.startsWith("zombie/")) {
                return specific;
            }
            byte[] base = (specific != null) ? specific : classfileBuffer;
            byte[] scaled = applyRenderScaleRedirects(className, base);
            return (scaled != null) ? scaled : specific;
        }

        private static final String RS = "com/pzoptimizer/WorldRenderScale";

        // {owner, name, desc, wrapper}: every LWJGL entry point the game uses for pixel-space state, from a
        // scan of projectzomboid.jar. Wrappers are static with the identical descriptor.
        private static final String[][] RENDER_SCALE_REDIRECTS = {
            {"org/lwjgl/opengl/GL11", "glViewport", "(IIII)V", "glViewport"},
            {"org/lwjgl/opengl/GL30", "glViewport", "(IIII)V", "glViewport"},
            {"org/lwjgl/opengl/GL43", "glViewport", "(IIII)V", "glViewport"},
            {"org/lwjgl/opengl/GL11", "glScissor", "(IIII)V", "glScissor"},
            {"org/lwjgl/opengl/GL11", "glPushAttrib", "(I)V", "glPushAttrib"},
            {"org/lwjgl/opengl/GL43", "glPushAttrib", "(I)V", "glPushAttrib"},
            {"org/lwjgl/opengl/GL11", "glPopAttrib", "()V", "glPopAttrib"},
            {"org/lwjgl/opengl/GL43", "glPopAttrib", "()V", "glPopAttrib"},
            {"org/lwjgl/opengl/GL11", "glGetIntegerv", "(I[I)V", "glGetIntegerv"},
            {"org/lwjgl/opengl/GL11", "glGetIntegerv", "(ILjava/nio/IntBuffer;)V", "glGetIntegerv"},
            {"org/lwjgl/opengl/GL30", "glBindFramebuffer", "(II)V", "glBindFramebuffer"},
            {"org/lwjgl/opengl/GL30C", "glBindFramebuffer", "(II)V", "glBindFramebuffer"},
            {"org/lwjgl/opengl/GL43", "glBindFramebuffer", "(II)V", "glBindFramebuffer"},
            {"org/lwjgl/opengl/GL44", "glBindFramebuffer", "(II)V", "glBindFramebuffer"},
            {"org/lwjgl/opengl/ARBFramebufferObject", "glBindFramebuffer", "(II)V", "glBindFramebuffer"},
            {"org/lwjgl/opengl/EXTFramebufferObject", "glBindFramebufferEXT", "(II)V", "glBindFramebufferEXT"},
            {"org/lwjgl/opengl/GL30", "glBlitFramebuffer", "(IIIIIIIIII)V", "glBlitFramebuffer"},
            {"org/lwjgl/opengl/GL30C", "glBlitFramebuffer", "(IIIIIIIIII)V", "glBlitFramebuffer"},
            {"org/lwjgl/opengl/GL43", "glBlitFramebuffer", "(IIIIIIIIII)V", "glBlitFramebuffer"},
            {"org/lwjgl/opengl/GL44", "glBlitFramebuffer", "(IIIIIIIIII)V", "glBlitFramebuffer"},
            {"org/lwjgl/opengl/GL20", "glShaderSource", "(ILjava/lang/CharSequence;)V", "glShaderSource"},
            {"org/lwjgl/opengl/GL20", "glUseProgram", "(I)V", "glUseProgram"},
            {"org/lwjgl/opengl/GL43", "glUseProgram", "(I)V", "glUseProgram"},
            {"org/lwjgl/opengl/GL20", "glLinkProgram", "(I)V", "glLinkProgram"},
        };

        private static boolean containsAscii(byte[] haystack, String needle) {
            byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            outer:
            for (int i = 0, max = haystack.length - n.length; i <= max; i++) {
                for (int j = 0; j < n.length; j++) {
                    if (haystack[i + j] != n[j]) continue outer;
                }
                return true;
            }
            return false;
        }

        private byte[] applyRenderScaleRedirects(String className, byte[] b) {
            byte[] current = b;
            boolean changed = false;
            int sites = 0;
            for (String[] r : RENDER_SCALE_REDIRECTS) {
                if (!containsAscii(current, r[1]) || !containsAscii(current, r[0])) continue;
                byte[] next = redirectCallSites(current, 0xB8, r[0], r[1], r[2], RS, r[3], r[2], null);
                if (next != null) {
                    current = next;
                    changed = true;
                    sites++;
                }
            }
            // The composite hook queues the FSR pass ahead of every world -> screen blit
            if (containsAscii(current, "RenderOffScreenBuffer")) {
                byte[] next = redirectCallSites(current, 0xB6, "zombie/core/Core", "RenderOffScreenBuffer", "()V",
                    "com/pzoptimizer/WorldUpscalerGovernor", "renderOffScreenBuffer", "(Ljava/lang/Object;)V", null);
                if (next != null) {
                    current = next;
                    changed = true;
                    sites++;
                }
            }
            if (changed) {
                PZOLogger.info("[PZO Agent] FSR render-scale hooks: " + className + " (" + sites + " call types)");
            }
            return changed ? current : null;
        }

        private byte[] transformSpecific(ClassLoader loader, String className, byte[] classfileBuffer) {
            if ("zombie/iso/IsoChunkMap".equals(className) && classfileBuffer != null) {
                byte[] base = patchIsoChunkMap(classfileBuffer);
                byte[] paced = AsmSupport.transform(base != null ? base : classfileBuffer, loader,
                    "IsoChunkMap hand-off pacer", GameThreadPatches::patchChunkMap);
                if (paced != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched IsoChunkMap.updateInternal: chunk hand-offs paced by a per-frame time budget");
                    return paced;
                }
                return base;
            }
            if ("zombie/core/opengl/ShaderUnit".equals(className) && classfileBuffer != null) {
                byte[] su = AsmSupport.transform(classfileBuffer, loader, "ShaderUnit early depth", ShaderEarlyDepth::patchShaderUnit);
                if (su != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched ShaderUnit: puddle early depth hook installed (GL 4.2 / ARB_conservative_depth)");
                }
                return su;
            }
            if ("zombie/iso/sprite/IsoSprite".equals(className) && classfileBuffer != null) {
                byte[] sp = AsmSupport.transform(classfileBuffer, loader, "IsoSprite translucent bake", TranslucentBake::patchSprite);
                if (sp != null) TranslucentBake.spriteHooked = true;
                if (sp != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched IsoSprite depth shader setup: baked translucent sprites keep depth on solid pixels only");
                }
                return sp;
            }
            if ("zombie/iso/fboRenderChunk/FBORenderCell".equals(className) && classfileBuffer != null) {
                byte[] fc = AsmSupport.transform(classfileBuffer, loader, "FBORenderCell bake budget", ChunkBakeBudget::patchRenderCell);
                if (fc != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched FBORenderCell.renderOneLevel: lighting and cosmetic re-bakes spread over frames");
                }
                byte[] fl = AsmSupport.transform(fc != null ? fc : classfileBuffer, loader, "FBORenderCell foliage LOD", FoliageLod::patchRenderCell);
                if (fl != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched FBORenderCell layer tests: distant wind-swayed foliage baked into the chunk texture");
                    fc = fl;
                }
                byte[] tb = AsmSupport.transform(fc != null ? fc : classfileBuffer, loader, "FBORenderCell translucent bake", TranslucentBake::patchRenderCell);
                if (tb != null) TranslucentBake.cellHooked = true;
                if (tb != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched FBORenderCell.renderMinusFloor: baked translucent sprites drawn in colour and depth passes");
                    fc = tb;
                }
                if (BenchmarkHarness.isConfigured()) {
                    byte[] ts = AsmSupport.transform(fc != null ? fc : classfileBuffer, loader, "FBORenderCell translucent stats", TranslucentStats::patchRenderCell);
                    if (ts != null) {
                        TranslucentStats.active = true;
                        fc = ts;
                    }
                }
                return fc;
            }
            if ("zombie/core/opengl/VBORenderer".equals(className) && classfileBuffer != null) {
                byte[] vb = AsmSupport.transform(classfileBuffer, loader, "VBORenderer batch size", RenderBatchPatches::patchVboRenderer);
                if (vb != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched VBORenderer: 1 MB batches instead of 4 KB (about 30x fewer flushes)");
                }
                return vb;
            }
            if ("zombie/iso/weather/fx/ParticleRectangle".equals(className) && classfileBuffer != null) {
                byte[] pr = AsmSupport.transform(classfileBuffer, loader, "ParticleRectangle tiles", WeatherTileBatcher::patchParticleRectangle);
                if (pr != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched ParticleRectangle.render: weather particles added once per frame as a tile");
                }
                return pr;
            }
            if ("zombie/iso/weather/fx/WeatherParticleDrawer".equals(className) && classfileBuffer != null) {
                byte[] wd = AsmSupport.transform(classfileBuffer, loader, "WeatherParticleDrawer tiles", WeatherTileBatcher::patchDrawer);
                if (wd != null) {
                    WeatherTileBatcher.drawerPatched = true;
                    PZOLogger.success("[PZO Agent] ASM-patched WeatherParticleDrawer: weather tiles drawn once per screen cell on the GPU");
                }
                return wd;
            }
            if ("se/krka/kahlua/j2se/KahluaTableImpl".equals(className) && classfileBuffer != null) {
                byte[] lua = AsmSupport.transform(classfileBuffer, loader, "KahluaTableImpl.rawget", GameThreadPatches::patchKahluaTable);
                if (lua != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched KahluaTableImpl.rawget: one hash lookup per Lua table read");
                }
                return lua;
            }
            if ("zombie/WorldSoundManager".equals(className) && classfileBuffer != null) {
                byte[] snd = AsmSupport.transform(classfileBuffer, loader, "WorldSoundManager.addSound", GameThreadPatches::patchWorldSound);
                if (snd != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched WorldSoundManager.addSound: chunk walk clamped to the loaded grid");
                }
                return snd;
            }
            if ("zombie/characters/ecs/ECSComponent".equals(className) && classfileBuffer != null) {
                byte[] ecs = AsmSupport.transform(classfileBuffer, loader, "ECSComponent.getECSClass cache", GameThreadPatches::patchEcsComponent);
                if (ecs != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched ECSComponent.getECSClass: component class walk cached per type");
                }
                return ecs;
            }
            if ("zombie/GameProfiler".equals(className) && classfileBuffer != null) {
                byte[] gp = AsmSupport.transform(classfileBuffer, loader, "GameProfiler.isValidThread", GameThreadPatches::patchGameProfiler);
                if (gp != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched GameProfiler.isValidThread: game threads recognised without a name scan");
                }
                return gp;
            }
            if ("zombie/iso/objects/IsoLightSwitch".equals(className) && classfileBuffer != null) {
                byte[] ls = AsmSupport.transform(classfileBuffer, loader, "IsoLightSwitch power cache", GameThreadPatches::patchLightSwitch);
                if (ls != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched IsoLightSwitch.hasElectricityAround: per-switch power answer cached for a few frames");
                }
                return ls;
            }
            if ("zombie/entity/components/spriteconfig/SpriteConfig".equals(className) && classfileBuffer != null) {
                return patchSpriteConfig(classfileBuffer);
            }
            if ("zombie/iso/IsoGridSquare".equals(className) && classfileBuffer != null) {
                byte[] wall = patchIsoGridSquare(classfileBuffer);
                byte[] base = (wall != null) ? wall : classfileBuffer;
                byte[] blood = prefixSkipIfTrue(base, "splatBlood", "(IF)V", "com/pzoptimizer/BloodCapGovernor", "shouldSkipSplat");
                if (blood != null) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched IsoGridSquare.splatBlood: BloodCapGovernor per-tile blood cap armed");
                    base = blood;
                }
                byte[] recalc = redirectRecalcPropertiesIfNeeded(base);
                if (recalc != null) base = recalc;
                byte[] burn = AsmSupport.transform(base, loader, "IsoGridSquare.BurnWalls", BurnLoopGuard::patch);
                if (burn != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched IsoGridSquare.BurnWalls: burnt-building removal loop can no longer spin forever");
                    base = burn;
                }
                return (base != classfileBuffer) ? base : null;
            }
            if ("zombie/vehicles/BaseVehicle".equals(className) && classfileBuffer != null) {
                byte[] current = classfileBuffer;
                byte[] glove = prefixSkipIfTrue(current, "addKeyToGloveBox", "()V", "com/pzoptimizer/VehicleKeyGuard", "shouldSkipGloveBoxKey");
                if (glove != null) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched BaseVehicle.addKeyToGloveBox: skipped only when the glovebox has no container");
                    current = glove;
                }
                byte[] anim = BytecodeRedirect.virtualToStatic(current,
                    "zombie/core/skinnedmodel/animation/AnimationPlayer", "Update", "(F)V",
                    "com/pzoptimizer/VehicleAnimSkipGovernor", "update", "(Lzombie/core/skinnedmodel/animation/AnimationPlayer;F)V");
                if (anim != null) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched BaseVehicle: AnimationPlayer.Update routed through VehicleAnimSkipGovernor");
                    current = anim;
                }
                return current != classfileBuffer ? current : null;
            }
            if ("zombie/characters/IsoZombie".equals(className) && classfileBuffer != null) {
                byte[] los = BytecodeRedirect.virtualToStatic(classfileBuffer,
                    "zombie/vehicles/BaseVehicle", "getIntersectPoint", "(Lorg/joml/Vector3f;Lorg/joml/Vector3f;Lorg/joml/Vector3f;)Lorg/joml/Vector3f;",
                    "com/pzoptimizer/VehicleLosGovernor", "getIntersectPoint",
                    "(Lzombie/vehicles/BaseVehicle;Lorg/joml/Vector3f;Lorg/joml/Vector3f;Lorg/joml/Vector3f;)Lorg/joml/Vector3f;");
                if (los != null) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched IsoZombie: vehicle sight-line test routed through VehicleLosGovernor");
                }
                byte[] base = los != null ? los : classfileBuffer;
                byte[] snap = AsmSupport.transform(base, loader, "IsoZombie.isVehicleBetween snapshot", VehicleLosGovernor::patchZombie);
                if (snap != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched IsoZombie.isVehicleBetween: vehicle spheres snapshotted once per frame");
                    base = snap;
                }
                byte[] split = AsmSupport.transform(base, loader, "IsoZombie postupdate split", ActionParallel::patchZombie);
                if (split != null) {
                    ActionParallel.markPatched(ActionParallel.PATCH_ZOMBIE);
                    return split;
                }
                return snap != null ? snap : los;
            }
            if ("zombie/core/opengl/RenderThread".equals(className) && classfileBuffer != null && BenchmarkHarness.isConfigured()) {
                byte[] rt = AsmSupport.transform(classfileBuffer, loader, "RenderThread timing", GpuFrameTimer::patch);
                if (rt != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched RenderThread: benchmark render frame timing armed");
                }
                return rt;
            }
            if ("zombie/core/textures/TextureIDAssetManager".equals(className) && classfileBuffer != null) {
                byte[] tex = BytecodeRedirect.virtualToStatic(classfileBuffer,
                    "zombie/core/textures/TextureIDAssetManager", "setTask", "(Lzombie/asset/Asset;Lzombie/asset/AssetTask;)V",
                    "com/pzoptimizer/TextureDownscaleGovernor", "setTask",
                    "(Lzombie/core/textures/TextureIDAssetManager;Lzombie/asset/Asset;Lzombie/asset/AssetTask;)V");
                if (tex != null) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched TextureIDAssetManager: oversized texture loads tagged for worker downscale");
                }
                return tex;
            }
            if ("zombie/fileSystem/FileSystemImpl".equals(className) && classfileBuffer != null) {
                byte[] fsb = BytecodeRedirect.virtualToStatic(classfileBuffer,
                    "zombie/fileSystem/FileSystemImpl", "runAsync", "(Lzombie/fileSystem/FileSystemImpl$AsyncItem;)I",
                    "com/pzoptimizer/TextureDownscaleGovernor", "runAsync", "(Lzombie/fileSystem/FileSystemImpl;Ljava/lang/Object;)I");
                if (fsb != null) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched FileSystemImpl: tagged texture loads downscaled on the file worker");
                }
                return fsb;
            }
            if ("zombie/MovingObjectUpdateScheduler".equals(className) && classfileBuffer != null) {
                byte[] sim = BytecodeRedirect.virtualToStatic(classfileBuffer,
                    "zombie/MovingObjectUpdateScheduler", "getUpdateSchedulerSimulationLevelForObject",
                    "(Lzombie/iso/IsoMovingObject;F)Lzombie/UpdateSchedulerSimulationLevel;",
                    "com/pzoptimizer/UnseenZombieSimGovernor", "level",
                    "(Lzombie/MovingObjectUpdateScheduler;Lzombie/iso/IsoMovingObject;F)Lzombie/UpdateSchedulerSimulationLevel;");
                if (sim != null) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched MovingObjectUpdateScheduler: unseen zombie simulation level routed through UnseenZombieSimGovernor");
                }
                byte[] batch = AsmSupport.transform(sim != null ? sim : classfileBuffer, loader, "MovingObjectUpdateScheduler bone batch",
                    AnimationThreadingPatches::patchScheduler);
                if (batch != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched MovingObjectUpdateScheduler.postupdate: bone batch runs when the loop ends");
                    return batch;
                }
                return sim;
            }
            if ("zombie/characters/IsoPlayer".equals(className) && classfileBuffer != null) {
                byte[] cur = BytecodeRedirect.virtualToStatic(classfileBuffer,
                    "java/util/Stack", "contains", "(Ljava/lang/Object;)Z",
                    "com/pzoptimizer/SpottedListGovernor", "contains", "(Ljava/util/Stack;Ljava/lang/Object;)Z");
                if (cur != null) {
                    byte[] added = BytecodeRedirect.virtualToStatic(cur,
                        "java/util/Stack", "add", "(Ljava/lang/Object;)Z",
                        "com/pzoptimizer/SpottedListGovernor", "add", "(Ljava/util/Stack;Ljava/lang/Object;)Z");
                    if (added != null) {
                        PZOLogger.success("[PZO Agent] Bytecode-patched IsoPlayer: spotted-list lookups routed through SpottedListGovernor");
                        return added;
                    }
                }
                return null;
            }
            if ("zombie/iso/fboRenderChunk/FBORenderLevels".equals(className) && classfileBuffer != null) {
                return patchFBORenderLevels(classfileBuffer);
            }
            if ("zombie/fileSystem/TexturePackDevice".equals(className) && classfileBuffer != null) {
                return patchTexturePackDevice(classfileBuffer);
            }
            if ("zombie/iso/ChunkSaveWorker".equals(className) && classfileBuffer != null) {
                return patchChunkSaveWorker(classfileBuffer);
            }
            if ("zombie/iso/WorldStreamer".equals(className) && classfileBuffer != null) {
                return patchWorldStreamer(classfileBuffer);
            }
            if ("zombie/core/physics/WorldSimulation".equals(className) && classfileBuffer != null) {
                byte[] ws = AsmSupport.transform(classfileBuffer, loader, "WorldSimulation vehicle physics timing",
                    VehicleMotionSmoother::patchWorldSimulation);
                if (ws != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched WorldSimulation: VehicleMotionSmoother physics timing hooks armed");
                    return ws;
                }
                return classfileBuffer;
            }
            if ("zombie/GameWindow".equals(className) && classfileBuffer != null) {
                byte[] gw = AsmSupport.transform(classfileBuffer, loader, "GameWindow vehicle motion smoothing",
                    VehicleMotionSmoother::patchGameWindow);
                if (gw != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched GameWindow: VehicleMotionSmoother transient render smoothing armed");
                    return gw;
                }
                return classfileBuffer;
            }
            if ("zombie/iso/PlayerCamera".equals(className) && classfileBuffer != null) {
                byte[] pc = AsmSupport.transform(classfileBuffer, loader, "PlayerCamera subpixel precision",
                    DriveCameraGovernor::patchPlayerCamera);
                if (pc != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched PlayerCamera: DriveCameraGovernor subpixel float precision armed");
                    return pc;
                }
                return classfileBuffer;
            }
            if ("zombie/iso/IsoWorld".equals(className) && classfileBuffer != null) {
                return patchIsoWorld(classfileBuffer);
            }
            if ("zombie/core/skinnedmodel/animation/AnimationPlayer".equals(className) && classfileBuffer != null) {
                byte[] ap = patchAnimationPlayer(classfileBuffer);
                byte[] bb = AsmSupport.transform(ap != null ? ap : classfileBuffer, loader, "AnimationPlayer bone batch",
                    AnimationThreadingPatches::patchAnimationPlayer);
                if (bb != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched AnimationPlayer: zombie bone math queued for the frame workers");
                    return bb;
                }
                return ap;
            }
            if ("zombie/core/skinnedmodel/advancedanimation/AnimatedModel$AnimatedModelInstanceRenderData".equals(className) && classfileBuffer != null) {
                byte[] st = BytecodeRedirect.virtualToStatic(classfileBuffer,
                    "org/lwjgl/util/vector/Matrix4f", "store", "(Ljava/nio/FloatBuffer;)Lorg/lwjgl/util/vector/Matrix;",
                    "com/pzoptimizer/PaletteBuffers", "store", "(Lorg/lwjgl/util/vector/Matrix4f;Ljava/nio/FloatBuffer;)Lorg/lwjgl/util/vector/Matrix;");
                byte[] base = st != null ? st : classfileBuffer;
                byte[] ld = BytecodeRedirect.virtualToStatic(base,
                    "org/lwjgl/util/vector/Matrix4f", "load", "(Ljava/nio/FloatBuffer;)Lorg/lwjgl/util/vector/Matrix;",
                    "com/pzoptimizer/PaletteBuffers", "load", "(Lorg/lwjgl/util/vector/Matrix4f;Ljava/nio/FloatBuffer;)Lorg/lwjgl/util/vector/Matrix;");
                byte[] out = ld != null ? ld : st;
                if (out != null) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched AnimatedModel render data: matrix palette copied in bulk ("
                        + (st != null ? "store" : "") + (st != null && ld != null ? ", " : "") + (ld != null ? "load" : "") + ")");
                }
                return out;
            }
            if ("zombie/Lua/LuaManager".equals(className) && classfileBuffer != null) {
                byte[] lm = AsmSupport.transform(classfileBuffer, loader, "LuaManager lookup trace", LuaLookupTrace::patchLuaManager);
                if (lm != null) {
                    PZOLogger.info("[PZO Agent] LuaManager.getFunctionObject: failed Vehicles.* lookups are traced with their thread and stack");
                }
                return lm;
            }
            if ("zombie/FliesSound$ChunkLevelData".equals(className) && classfileBuffer != null) {
                byte[] fs = AsmSupport.transform(classfileBuffer, loader, "FliesSound hasFlies toggle", FliesSoundFix::patchChunkLevelData);
                if (fs != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched FliesSound: no hasFlies clear+set (and chunk re-bake) on an unchanged square");
                }
                return fs;
            }
            if ("zombie/iso/fboRenderChunk/FBORenderChunk".equals(className) && classfileBuffer != null) {
                byte[] mip = AsmSupport.transform(classfileBuffer, loader, "FBORenderChunk mip levels", ChunkMipLimit::patchRenderChunk);
                if (mip != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched FBORenderChunk.endRenderThread: chunk mip chain capped at " + ChunkMipLimit.maxLevel + " levels");
                }
                return mip;
            }
            if ("zombie/Lua/Event".equals(className) && classfileBuffer != null) {
                byte[] ev = AsmSupport.transform(classfileBuffer, loader, "Lua Event.trigger", LuaEventPatches::patchEvent);
                if (ev != null) {
                    LuaEventPatches.timing = BenchmarkHarness.isConfigured();
                    PZOLogger.success("[PZO Agent] ASM-patched Lua Event.trigger: cached profiler labels, no rescan after each callback"
                        + (LuaEventPatches.timing ? ", handler timing on for this benchmark" : ""));
                }
                return ev;
            }
            if ("zombie/scripting/ScriptManager".equals(className) && classfileBuffer != null) {
                byte[] sm = AsmSupport.transform(classfileBuffer, loader, "ScriptManager.FindItem memo", ScriptLookupCache::patchScriptManager);
                if (sm != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched ScriptManager.FindItem: item lookups remembered until scripts reload");
                }
                return sm;
            }
            if ("zombie/iso/IsoDirections".equals(className) && classfileBuffer != null) {
                byte[] dv = AsmSupport.transform(classfileBuffer, loader, "IsoDirections.ToVector", DirectionVectorFix::patchIsoDirections);
                if (dv != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched IsoDirections.ToVector: shared vector kept for the game thread only");
                }
                return dv;
            }
            if ("zombie/core/skinnedmodel/HelperFunctions".equals(className) && classfileBuffer != null) {
                byte[] hf = AsmSupport.transform(classfileBuffer, loader, "HelperFunctions worker matrices", AnimationThreadingPatches::patchHelperFunctions);
                if (hf != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched HelperFunctions: frame workers use their own matrix stacks");
                }
                return hf;
            }
            if ("zombie/FileGuidTable".equals(className) && classfileBuffer != null) {
                return patchFileGuidTable(classfileBuffer);
            }
            if ("zombie/core/skinnedmodel/advancedanimation/AnimationVariableHandlePool".equals(className) && classfileBuffer != null) {
                return patchAnimationVariableHandlePool(classfileBuffer);
            }
            if ("zombie/core/properties/PropertyContainer".equals(className) && classfileBuffer != null) {
                return patchPropertyContainer(classfileBuffer);
            }
            if ("zombie/characters/IsoGameCharacter".equals(className) && classfileBuffer != null) {
                byte[] gc = patchIsoGameCharacter(classfileBuffer);
                byte[] smc = AsmSupport.transform(gc != null ? gc : classfileBuffer, loader, "IsoGameCharacter state machine cache",
                    GameThreadPatches::patchStateMachineComponent);
                if (smc != null) {
                    PZOLogger.success("[PZO Agent] ASM-patched IsoGameCharacter.getStateMachineComponent: " + (GameThreadPatches.stateMachineInPlace
                        ? "component read straight from the component map (class keeps its stock shape)" : "component kept per character"));
                    gc = smc;
                }
                byte[] split = AsmSupport.transform(gc != null ? gc : classfileBuffer, loader, "IsoGameCharacter postupdate split",
                    ActionParallel::patchCharacter);
                if (split != null) {
                    ActionParallel.markPatched(ActionParallel.PATCH_CHARACTER);
                    return split;
                }
                return gc;
            }
            if ("zombie/MovingObjectUpdateSchedulerUpdateBucket".equals(className) && classfileBuffer != null) {
                byte[] b = AsmSupport.transform(classfileBuffer, loader, "UpdateBucket postupdate flush", ActionParallel::patchBucket);
                if (b != null) ActionParallel.markPatched(ActionParallel.PATCH_BUCKET);
                return b;
            }
            if ("zombie/characters/action/conditions/CharacterVariableCondition".equals(className) && classfileBuffer != null) {
                byte[] c = AsmSupport.transform(classfileBuffer, loader, "CharacterVariableCondition reads", ActionParallel::patchCondition);
                if (c != null) {
                    ActionParallel.markPatched(ActionParallel.PATCH_CONDITION);
                    PZOLogger.success("[PZO Agent] ASM-patched zombie postupdate: action-transition variables read on worker threads");
                }
                return c;
            }
            if ("zombie/characters/animals/IsoAnimal".equals(className) && classfileBuffer != null) {
                return patchIsoAnimal(classfileBuffer);
            }
            if ("zombie/pathfind/nativeCode/PathfindNativeThread".equals(className) && classfileBuffer != null) {
                return patchPathfindNativeThread(classfileBuffer);
            }
            if ("zombie/pathfind/nativeCode/PathfindNative".equals(className) && classfileBuffer != null) {
                return patchPathfindNative(classfileBuffer);
            }
            if ("zombie/GameProfiler".equals(className) && classfileBuffer != null) {
                return patchGameProfiler(classfileBuffer);
            }
            return null;
        }

        private byte[] patchIsoChunkMap(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;

                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    tags[i] = b[pos] & 0xFF;
                    tagOffsets[i] = pos;
                    pos++;
                    int tag = tags[i];
                    if (tag == 1) { // Utf8
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                int chunksSwapARef = -1;
                int chunkGridWidthRef = -1;

                for (int k = 1; k < cpCount; k++) {
                    if (tags[k] == 9) { // Fieldref
                        int p = tagOffsets[k] + 1;
                        int ntIdx = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (ntIdx > 0 && ntIdx < cpCount && tags[ntIdx] == 12) { // NameAndType
                            int ntp = tagOffsets[ntIdx] + 1;
                            int nameIdx = ((b[ntp] & 0xFF) << 8) | (b[ntp + 1] & 0xFF);
                            int descIdx = ((b[ntp + 2] & 0xFF) << 8) | (b[ntp + 3] & 0xFF);
                            String name = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : null;
                            String desc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : null;
                            if ("chunksSwapA".equals(name) && desc != null && desc.contains("[Lzombie/iso/IsoChunk;")) {
                                chunksSwapARef = k;
                            } else if ("chunkGridWidth".equals(name) && "I".equals(desc)) {
                                chunkGridWidthRef = k;
                            }
                        }
                    }
                }

                if (chunksSwapARef == -1 || chunkGridWidthRef == -1) {
                    return null;
                }

                byte oldRefHi = (byte) ((chunksSwapARef >> 8) & 0xFF);
                byte oldRefLo = (byte) (chunksSwapARef & 0xFF);

                byte newRefHi = (byte) ((chunkGridWidthRef >> 8) & 0xFF);
                byte newRefLo = (byte) (chunkGridWidthRef & 0xFF);

                byte[] copy = b.clone();
                int patchedSites = 0;

                // Loop reduction: calculateZExtentsForChunkMap (28,561 loop down to 169)
                for (int k = pos; k < copy.length - 4; k++) {
                    if (copy[k] == 0x2A && copy[k + 1] == (byte) 0xB4 &&
                        copy[k + 2] == oldRefHi && copy[k + 3] == oldRefLo &&
                        copy[k + 4] == (byte) 0xBE) {
                        copy[k] = (byte) 0xB2;
                        copy[k + 1] = newRefHi;
                        copy[k + 2] = newRefLo;
                        copy[k + 3] = 0x00;
                        copy[k + 4] = 0x00;
                        patchedSites++;
                    }
                }

                if (patchedSites > 0) {
                    PZOLogger.success(String.format("[PZO Agent] Bytecode-patched IsoChunkMap.calculateZExtentsForChunkMap: Reduced 28,561 loop iterations down to 169 (%d sites patched - 99.4%% loop overhead eliminated)", patchedSites));
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during IsoChunkMap bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchSpriteConfig(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;
                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    tags[i] = b[pos] & 0xFF;
                    tagOffsets[i] = pos;
                    pos++;
                    int tag = tags[i];
                    if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                int warnRef = -1;
                int resetRef = -1;
                for (int k = 1; k < cpCount; k++) {
                    if (tags[k] == 10) { // Methodref
                        int p = tagOffsets[k] + 1;
                        int ntIdx = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (ntIdx > 0 && ntIdx < cpCount && tags[ntIdx] == 12) {
                            int ntp = tagOffsets[ntIdx] + 1;
                            int nIdx = ((b[ntp] & 0xFF) << 8) | (b[ntp + 1] & 0xFF);
                            int dIdx = ((b[ntp + 2] & 0xFF) << 8) | (b[ntp + 3] & 0xFF);
                            String name = (nIdx > 0 && nIdx < cpCount) ? utf8Strings[nIdx] : null;
                            String desc = (dIdx > 0 && dIdx < cpCount) ? utf8Strings[dIdx] : null;
                            if ("warn".equals(name) && "(Ljava/lang/Object;)V".equals(desc)) {
                                warnRef = k;
                            } else if ("resetObjectInfo".equals(name) && "()V".equals(desc)) {
                                resetRef = k;
                            }
                        }
                    }
                }

                if (warnRef == -1 || resetRef == -1) return null;

                byte warnHi = (byte) ((warnRef >> 8) & 0xFF);
                byte warnLo = (byte) (warnRef & 0xFF);
                byte resetHi = (byte) ((resetRef >> 8) & 0xFF);
                byte resetLo = (byte) (resetRef & 0xFF);

                byte[] copy = b.clone();
                int patched = 0;
                for (int k = pos; k < copy.length - 7; k++) {
                    if (copy[k] == (byte) 0xb6 && copy[k + 1] == warnHi && copy[k + 2] == warnLo &&
                        copy[k + 3] == 0x2a && copy[k + 4] == (byte) 0xb6 && copy[k + 5] == resetHi && copy[k + 6] == resetLo) {
                        copy[k] = 0x58; // pop2
                        copy[k + 1] = 0x00; // nop
                        copy[k + 2] = 0x00; // nop
                        patched++;
                    }
                }

                if (patched > 0) {
                    PZOLogger.success(String.format("[PZO Agent] Bytecode-patched SpriteConfig.initObjectInfo: neutralized %d warn disk log calls", patched));
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during SpriteConfig bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchIsoGridSquare(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;
                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    tags[i] = b[pos] & 0xFF;
                    tagOffsets[i] = pos;
                    pos++;
                    int tag = tags[i];
                    if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                byte[] copy = b.clone();
                int mpos = pos + 6; // access_flags (2), this_class (2), super_class (2)
                int ifaces = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2 + ifaces * 2;

                int fields = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                for (int f = 0; f < fields; f++) {
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6 + alen;
                    }
                }

                int methods = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                int patched = 0;

                for (int m = 0; m < methods; m++) {
                    int nameIdx = ((b[mpos + 2] & 0xFF) << 8) | (b[mpos + 3] & 0xFF);
                    int descIdx = ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                    String mname = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                    String mdesc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : "";
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int anameIdx = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                        String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6;
                        if ("Code".equals(aname) && "isWallTo".equals(mname) && "(Lzombie/iso/IsoGridSquare;I)Z".equals(mdesc)) {
                            int codeLen = ((b[mpos + 4] & 0xFF) << 24) | ((b[mpos + 5] & 0xFF) << 16) | ((b[mpos + 6] & 0xFF) << 8) | (b[mpos + 7] & 0xFF);
                            int cstart = mpos + 8;
                            if (codeLen >= 8 && copy[cstart] == 28 && copy[cstart + 1] == 16 && copy[cstart + 6] == 3 && copy[cstart + 7] == 62) {
                                // Replace istore_3 (0x3E) with ireturn (0xAC) to correctly return false upon reaching depth recursion limit
                                copy[cstart + 7] = (byte) 0xAC;
                                patched++;
                            }
                        }
                        mpos += alen;
                    }
                }

                if (patched > 0) {
                    PZOLogger.success(String.format("[PZO Agent] Bytecode-patched IsoGridSquare: %d sites armed", patched));
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during IsoGridSquare bytecode transform: " + t.getMessage());
            }
            return null;
        }
        private byte[] patchFBORenderLevels(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;
                String[] utf8Strings = new String[cpCount];
                int stackmapUtf8Idx = -1;
                int i = 1;
                while (i < cpCount) {
                    int tag = b[pos] & 0xFF;
                    pos++;
                    if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        if ("StackMapTable".equals(utf8Strings[i])) {
                            stackmapUtf8Idx = i;
                        }
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18 || tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                if (stackmapUtf8Idx == -1) return null;

                pos += 6; // access, this, super
                int ifaces = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                pos += 2 + ifaces * 2;

                int fields = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                pos += 2;
                for (int f = 0; f < fields; f++) {
                    pos += 6;
                    int attrs = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                    pos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int alen = ((b[pos + 2] & 0xFF) << 24) | ((b[pos + 3] & 0xFF) << 16) | ((b[pos + 4] & 0xFF) << 8) | (b[pos + 5] & 0xFF);
                        pos += 6 + alen;
                    }
                }

                int methods = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                pos += 2;

                byte[] result = new byte[b.length + 256];
                int rpos = 0;
                System.arraycopy(b, 0, result, 0, pos);
                rpos = pos;

                int patched = 0;

                for (int m = 0; m < methods; m++) {
                    int nameIdx = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
                    int descIdx = ((b[pos + 4] & 0xFF) << 8) | (b[pos + 5] & 0xFF);
                    String mname = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                    String mdesc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : "";

                    System.arraycopy(b, pos, result, rpos, 6);
                    rpos += 6;
                    pos += 6;
                    int attrs = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                    result[rpos] = b[pos];
                    result[rpos + 1] = b[pos + 1];
                    rpos += 2;
                    pos += 2;

                    for (int a = 0; a < attrs; a++) {
                        int anameIdx = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";
                        int alen = ((b[pos + 2] & 0xFF) << 24) | ((b[pos + 3] & 0xFF) << 16) | ((b[pos + 4] & 0xFF) << 8) | (b[pos + 5] & 0xFF);

                        if ("Code".equals(aname) && "isOnScreen".equals(mname) && "(I)Z".equals(mdesc)) {
                            byte origGetNLevelsHi = b[pos + 17];
                            byte origGetNLevelsLo = b[pos + 18];
                            byte origOnScreenHi = b[pos + 20];
                            byte origOnScreenLo = b[pos + 21];

                            byte[] newCode = new byte[] {
                                0x1b,                      // 0: iload_1
                                0x10, (byte) 0xe0,         // 1: bipush -32
                                (byte) 0xa1, 0x00, 0x09,  // 3: if_icmplt +9 -> 12
                                0x1b,                      // 6: iload_1
                                0x10, 0x1f,                // 7: bipush 31
                                (byte) 0xa4, 0x00, 0x05,  // 9: if_icmple +5 -> 14
                                0x03,                      // 12: iconst_0
                                (byte) 0xac,               // 13: ireturn
                                0x2a,                      // 14: aload_0
                                0x1b,                      // 15: iload_1
                                (byte) 0xb6, origGetNLevelsHi, origGetNLevelsLo, // 16: invokevirtual getNLevels
                                (byte) 0xb4, origOnScreenHi, origOnScreenLo,     // 19: getfield onScreen
                                (byte) 0xac                // 22: ireturn
                            };

                            byte[] stackmapAttr = new byte[] {
                                (byte) ((stackmapUtf8Idx >> 8) & 0xFF), (byte) (stackmapUtf8Idx & 0xFF),
                                0x00, 0x00, 0x00, 0x04,
                                0x00, 0x02,
                                0x0c,                      // same_frame offset 12
                                0x01                       // same_frame offset 14 (14 - 12 - 1 = 1)
                            };

                            int codeAttrBodyLen = 8 + newCode.length + 2 + 2 + stackmapAttr.length;
                            result[rpos] = b[pos];
                            result[rpos + 1] = b[pos + 1];
                            result[rpos + 2] = (byte) ((codeAttrBodyLen >> 24) & 0xFF);
                            result[rpos + 3] = (byte) ((codeAttrBodyLen >> 16) & 0xFF);
                            result[rpos + 4] = (byte) ((codeAttrBodyLen >> 8) & 0xFF);
                            result[rpos + 5] = (byte) (codeAttrBodyLen & 0xFF);
                            rpos += 6;

                            result[rpos++] = 0x00; result[rpos++] = 0x02; // max_stack
                            result[rpos++] = 0x00; result[rpos++] = 0x02; // max_locals
                            result[rpos++] = 0x00; result[rpos++] = 0x00;
                            result[rpos++] = 0x00; result[rpos++] = (byte) newCode.length;
                            System.arraycopy(newCode, 0, result, rpos, newCode.length);
                            rpos += newCode.length;
                            result[rpos++] = 0x00; result[rpos++] = 0x00; // exception_table_length
                            result[rpos++] = 0x00; result[rpos++] = 0x01; // attributes_count (1: StackMapTable)
                            System.arraycopy(stackmapAttr, 0, result, rpos, stackmapAttr.length);
                            rpos += stackmapAttr.length;
                            patched++;
                        } else if ("Code".equals(aname) && "indexForLevel".equals(mname) && "(I)I".equals(mdesc)) {
                            byte calcMinHi = b[pos + 17];
                            byte calcMinLo = b[pos + 18];

                            byte[] newCode = new byte[] {
                                0x1b,                      // 0: iload_1
                                0x10, (byte) 0xe0,         // 1: bipush -32
                                (byte) 0xa2, 0x00, 0x05,  // 3: if_icmpge +5 -> 8
                                0x03,                      // 6: iconst_0
                                (byte) 0xac,               // 7: ireturn
                                0x1b,                      // 8: iload_1
                                0x10, 0x1f,                // 9: bipush 31
                                (byte) 0xa4, 0x00, 0x06,  // 11: if_icmple +6 -> 17
                                0x10, 0x1f,                // 14: bipush 31
                                (byte) 0xac,               // 16: ireturn
                                0x1b,                      // 17: iload_1
                                (byte) 0xb8, calcMinHi, calcMinLo, // 18: invokestatic calculateMinLevel
                                0x10, 0x20,                // 21: bipush 32
                                0x60,                      // 23: iadd
                                0x05,                      // 24: iconst_2
                                0x6c,                      // 25: idiv
                                (byte) 0xac                // 26: ireturn
                            };

                            byte[] stackmapAttr = new byte[] {
                                (byte) ((stackmapUtf8Idx >> 8) & 0xFF), (byte) (stackmapUtf8Idx & 0xFF),
                                0x00, 0x00, 0x00, 0x04,
                                0x00, 0x02,
                                0x08,                      // same_frame offset 8
                                0x08                       // same_frame offset 17 (17 - 8 - 1 = 8)
                            };

                            int codeAttrBodyLen = 8 + newCode.length + 2 + 2 + stackmapAttr.length;
                            result[rpos] = b[pos];
                            result[rpos + 1] = b[pos + 1];
                            result[rpos + 2] = (byte) ((codeAttrBodyLen >> 24) & 0xFF);
                            result[rpos + 3] = (byte) ((codeAttrBodyLen >> 16) & 0xFF);
                            result[rpos + 4] = (byte) ((codeAttrBodyLen >> 8) & 0xFF);
                            result[rpos + 5] = (byte) (codeAttrBodyLen & 0xFF);
                            rpos += 6;

                            result[rpos++] = 0x00; result[rpos++] = 0x02; // max_stack
                            result[rpos++] = 0x00; result[rpos++] = 0x02; // max_locals
                            result[rpos++] = 0x00; result[rpos++] = 0x00;
                            result[rpos++] = 0x00; result[rpos++] = (byte) newCode.length;
                            System.arraycopy(newCode, 0, result, rpos, newCode.length);
                            rpos += newCode.length;
                            result[rpos++] = 0x00; result[rpos++] = 0x00; // exception_table_length
                            result[rpos++] = 0x00; result[rpos++] = 0x01; // attributes_count (1: StackMapTable)
                            System.arraycopy(stackmapAttr, 0, result, rpos, stackmapAttr.length);
                            rpos += stackmapAttr.length;
                            patched++;
                        } else {
                            System.arraycopy(b, pos, result, rpos, 6 + alen);
                            rpos += 6 + alen;
                        }
                        pos += 6 + alen;
                    }
                }

                int remaining = b.length - pos;
                if (remaining > 0) {
                    System.arraycopy(b, pos, result, rpos, remaining);
                    rpos += remaining;
                }

                if (patched > 0) {
                    byte[] trimmed = new byte[rpos];
                    System.arraycopy(result, 0, trimmed, 0, rpos);
                    PZOLogger.success("[PZO Agent] Bytecode-patched FBORenderLevels: Boundary guards installed on isOnScreen & indexForLevel (ArrayIndexOutOfBoundsException & Black Void eliminated)");
                    return trimmed;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during FBORenderLevels bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchTexturePackDevice(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;

                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    int tag = b[pos] & 0xFF;
                    tags[i] = tag;
                    tagOffsets[i] = pos;
                    pos++;
                    if (tag == 1) { // Utf8
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18 || tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                int targetMethodRef = -1;
                int targetNat = -1;

                for (int k = 1; k < cpCount; k++) {
                    if (tags[k] == 10) { // Methodref
                        int p = tagOffsets[k] + 1;
                        int ntIdx = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (ntIdx > 0 && ntIdx < cpCount && tags[ntIdx] == 12) { // NameAndType
                            int ntp = tagOffsets[ntIdx] + 1;
                            int nIdx = ((b[ntp] & 0xFF) << 8) | (b[ntp + 1] & 0xFF);
                            int dIdx = ((b[ntp + 2] & 0xFF) << 8) | (b[ntp + 3] & 0xFF);
                            String name = (nIdx > 0 && nIdx < cpCount) ? utf8Strings[nIdx] : null;
                            String desc = (dIdx > 0 && dIdx < cpCount) ? utf8Strings[dIdx] : null;
                            if ("readIntByte".equals(name) && "(Ljava/io/InputStream;)I".equals(desc)) {
                                targetMethodRef = k;
                                targetNat = ntIdx;
                                break;
                            }
                        }
                    }
                }

                if (targetMethodRef == -1 || targetNat == -1) return null;

                byte oldHi = (byte) ((targetMethodRef >> 8) & 0xFF);
                byte oldLo = (byte) (targetMethodRef & 0xFF);

                byte[] utf8Bytes = "com/pzoptimizer/PackPageScanner".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(b.length + 64);
                DataOutputStream dos = new DataOutputStream(baos);

                dos.write(b, 0, 8); // Magic, minor, major
                dos.writeShort(cpCount + 3); // New cpCount
                dos.write(b, 10, pos - 10); // Original constant pool

                // Entry 1: Utf8
                dos.writeByte(1);
                dos.writeShort(utf8Bytes.length);
                dos.write(utf8Bytes);

                // Entry 2: Class
                dos.writeByte(7);
                dos.writeShort(cpCount);

                // Entry 3: Methodref
                dos.writeByte(10);
                dos.writeShort(cpCount + 1);
                dos.writeShort(targetNat);

                int newMethodRef = cpCount + 2;
                byte newHi = (byte) ((newMethodRef >> 8) & 0xFF);
                byte newLo = (byte) (newMethodRef & 0xFF);

                byte[] rest = new byte[b.length - pos];
                System.arraycopy(b, pos, rest, 0, rest.length);

                int patched = 0;
                for (int k = 0; k < rest.length - 2; k++) {
                    if (rest[k] == (byte) 0xB8 && rest[k + 1] == oldHi && rest[k + 2] == oldLo) {
                        rest[k + 1] = newHi;
                        rest[k + 2] = newLo;
                        patched++;
                    }
                }

                if (patched == 0) return null;

                dos.write(rest);
                dos.flush();
                PZOLogger.success(String.format("[PZO Agent] Bytecode-patched TexturePackDevice.readPage: PackPageScanner block terminator search (%d call sites)", patched));
                return baos.toByteArray();
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during TexturePackDevice bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private static void copyVerificationTypeInfo(DataInputStream dis, DataOutputStream dos) throws IOException {
            int tag = dis.readUnsignedByte();
            dos.writeByte(tag);
            if (tag == 7 || tag == 8) {
                dos.writeShort(dis.readUnsignedShort());
            }
        }

        private static int getInstructionLength(byte[] arr, int cstart, int pc) {
            int op = arr[cstart + pc] & 0xFF;
            switch (op) {
                case 0x10: case 0x12: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
                case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: case 0xA9: case 0xBC:
                    return 2;
                case 0x11: case 0x13: case 0x14: case 0x84:
                case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E:
                case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4:
                case 0xA5: case 0xA6: case 0xA7: case 0xA8:
                case 0xB2: case 0xB3: case 0xB4: case 0xB5: case 0xB6: case 0xB7: case 0xB8:
                case 0xBB: case 0xBD: case 0xC0: case 0xC1: case 0xC6: case 0xC7:
                    return 3;
                case 0xC5:
                    return 4;
                case 0xB9: case 0xBA: case 0xC8: case 0xC9:
                    return 5;
                case 0xC4: { // wide
                    int subOp = arr[cstart + pc + 1] & 0xFF;
                    return (subOp == 0x84) ? 6 : 4;
                }
                case 0xAA: { // tableswitch
                    int aligned = (pc + 4) & ~3;
                    int low = ((arr[cstart + aligned + 4] & 0xFF) << 24) | ((arr[cstart + aligned + 5] & 0xFF) << 16)
                            | ((arr[cstart + aligned + 6] & 0xFF) << 8) | (arr[cstart + aligned + 7] & 0xFF);
                    int high = ((arr[cstart + aligned + 8] & 0xFF) << 24) | ((arr[cstart + aligned + 9] & 0xFF) << 16)
                            | ((arr[cstart + aligned + 10] & 0xFF) << 8) | (arr[cstart + aligned + 11] & 0xFF);
                    return (aligned - pc) + 12 + (high - low + 1) * 4;
                }
                case 0xAB: { // lookupswitch
                    int aligned = (pc + 4) & ~3;
                    int npairs = ((arr[cstart + aligned + 4] & 0xFF) << 24) | ((arr[cstart + aligned + 5] & 0xFF) << 16)
                            | ((arr[cstart + aligned + 6] & 0xFF) << 8) | (arr[cstart + aligned + 7] & 0xFF);
                    return (aligned - pc) + 8 + npairs * 8;
                }
                default:
                    return 1;
            }
        }

        /**
         * Generic instruction-accurate call-site redirector.
         * Redirects targetOpcode (0xB6 invokevirtual, 0xB8 invokestatic or 0xB9 invokeinterface) calling
         * targetOwner.targetMethodName(targetMethodDesc) to invokestatic on (govClassName, govMethodName, govMethodDesc).
         * The governor must take the receiver (if any) as its first parameter. invokeinterface is 5 bytes, so it
         * becomes invokestatic + 2 nops; instruction offsets, exception tables and StackMapTable frames are preserved.
         */
        private byte[] redirectCallSites(
                byte[] b,
                int targetOpcode,
                String targetOwner,
                String targetMethodName,
                String targetMethodDesc,
                String govClassName,
                String govMethodName,
                String govMethodDesc,
                String logTag) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;

                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    int tag = b[pos] & 0xFF;
                    tags[i] = tag;
                    tagOffsets[i] = pos;
                    pos++;
                    if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18 || tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                int targetMethodRef = -1;
                for (int k = 1; k < cpCount; k++) {
                    if (tags[k] == 10 || tags[k] == 11) {
                        int p = tagOffsets[k] + 1;
                        int ownerIdx = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                        int ntIdx = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (!targetOwner.equals(classNameAt(b, tags, tagOffsets, utf8Strings, cpCount, ownerIdx))) {
                            continue;
                        }
                        if (ntIdx > 0 && ntIdx < cpCount && tags[ntIdx] == 12) {
                            int ntp = tagOffsets[ntIdx] + 1;
                            int nIdx = ((b[ntp] & 0xFF) << 8) | (b[ntp + 1] & 0xFF);
                            int dIdx = ((b[ntp + 2] & 0xFF) << 8) | (b[ntp + 3] & 0xFF);
                            String name = (nIdx > 0 && nIdx < cpCount) ? utf8Strings[nIdx] : null;
                            String desc = (dIdx > 0 && dIdx < cpCount) ? utf8Strings[dIdx] : null;
                            if (targetMethodName.equals(name) && targetMethodDesc.equals(desc)) {
                                targetMethodRef = k;
                                break;
                            }
                        }
                    }
                }

                if (targetMethodRef == -1) {
                    if (logTag != null) {
                        PZOLogger.warn(String.format("[PZO Agent] Redirect target not found: %s.%s%s (game update?) - %s inactive",
                            targetOwner, targetMethodName, targetMethodDesc, govClassName));
                    }
                    return null;
                }

                byte oldHi = (byte) ((targetMethodRef >> 8) & 0xFF);
                byte oldLo = (byte) (targetMethodRef & 0xFF);

                ByteArrayOutputStream cpExtBaos = new ByteArrayOutputStream(128);
                DataOutputStream cpExtDos = new DataOutputStream(cpExtBaos);
                // cpCount: Utf8 govClassName
                cpExtDos.writeByte(1);
                cpExtDos.writeUTF(govClassName);
                // cpCount + 1: Class
                cpExtDos.writeByte(7);
                cpExtDos.writeShort(cpCount);
                // cpCount + 2: Utf8 govMethodName
                cpExtDos.writeByte(1);
                cpExtDos.writeUTF(govMethodName);
                // cpCount + 3: Utf8 govMethodDesc
                cpExtDos.writeByte(1);
                cpExtDos.writeUTF(govMethodDesc);
                // cpCount + 4: NameAndType
                cpExtDos.writeByte(12);
                cpExtDos.writeShort(cpCount + 2);
                cpExtDos.writeShort(cpCount + 3);
                // cpCount + 5: Methodref
                cpExtDos.writeByte(10);
                cpExtDos.writeShort(cpCount + 1);
                cpExtDos.writeShort(cpCount + 4);
                cpExtDos.flush();

                int newMethodRef = cpCount + 5;
                byte newHi = (byte) ((newMethodRef >> 8) & 0xFF);
                byte newLo = (byte) (newMethodRef & 0xFF);

                byte[] rest = new byte[b.length - pos];
                System.arraycopy(b, pos, rest, 0, rest.length);

                int thisClassIdx = ((rest[2] & 0xFF) << 8) | (rest[3] & 0xFF);
                // Only the target method's own body is skipped (recursion guard), and only when it lives in this class
                boolean ownerIsThisClass = targetOwner.equals(classNameAt(b, tags, tagOffsets, utf8Strings, cpCount, thisClassIdx));

                int mpos = 6; // skip access_flags, this_class, super_class
                int ifaces = ((rest[mpos] & 0xFF) << 8) | (rest[mpos + 1] & 0xFF);
                mpos += 2 + ifaces * 2;

                int fields = ((rest[mpos] & 0xFF) << 8) | (rest[mpos + 1] & 0xFF);
                mpos += 2;
                for (int f = 0; f < fields; f++) {
                    mpos += 6;
                    int attrs = ((rest[mpos] & 0xFF) << 8) | (rest[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int alen = ((rest[mpos + 2] & 0xFF) << 24) | ((rest[mpos + 3] & 0xFF) << 16)
                                | ((rest[mpos + 4] & 0xFF) << 8) | (rest[mpos + 5] & 0xFF);
                        mpos += 6 + alen;
                    }
                }

                int methods = ((rest[mpos] & 0xFF) << 8) | (rest[mpos + 1] & 0xFF);
                mpos += 2;
                int patched = 0;

                for (int m = 0; m < methods; m++) {
                    int nameIdx = ((rest[mpos + 2] & 0xFF) << 8) | (rest[mpos + 3] & 0xFF);
                    int descIdx = ((rest[mpos + 4] & 0xFF) << 8) | (rest[mpos + 5] & 0xFF);
                    String mName = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                    String mDesc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : "";
                    mpos += 6;
                    int attrs = ((rest[mpos] & 0xFF) << 8) | (rest[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int anameIdx = ((rest[mpos] & 0xFF) << 8) | (rest[mpos + 1] & 0xFF);
                        String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";
                        int alen = ((rest[mpos + 2] & 0xFF) << 24) | ((rest[mpos + 3] & 0xFF) << 16)
                                | ((rest[mpos + 4] & 0xFF) << 8) | (rest[mpos + 5] & 0xFF);
                        mpos += 6;
                        boolean isTargetBody = ownerIsThisClass && targetMethodName.equals(mName) && targetMethodDesc.equals(mDesc);
                        if ("Code".equals(aname) && !isTargetBody) {
                            int codeLen = ((rest[mpos + 4] & 0xFF) << 24) | ((rest[mpos + 5] & 0xFF) << 16)
                                    | ((rest[mpos + 6] & 0xFF) << 8) | (rest[mpos + 7] & 0xFF);
                            int cstart = mpos + 8;
                            int pc = 0;
                            while (pc < codeLen) {
                                int op = rest[cstart + pc] & 0xFF;
                                int step = getInstructionLength(rest, cstart, pc);
                                if (step <= 0) break;
                                if (op == (targetOpcode & 0xFF)
                                        && (step == 3 || (op == 0xB9 && step == 5))
                                        && rest[cstart + pc + 1] == oldHi
                                        && rest[cstart + pc + 2] == oldLo) {
                                    rest[cstart + pc] = (byte) 0xB8; // invokestatic
                                    rest[cstart + pc + 1] = newHi;
                                    rest[cstart + pc + 2] = newLo;
                                    if (step == 5) {
                                        rest[cstart + pc + 3] = 0x00; // nop
                                        rest[cstart + pc + 4] = 0x00; // nop
                                    }
                                    patched++;
                                }
                                pc += step;
                            }
                        }
                        mpos += alen;
                    }
                }

                if (patched == 0) {
                    if (logTag != null) {
                        PZOLogger.warn(String.format("[PZO Agent] No %s call sites matched (opcode 0x%02X) - %s inactive",
                            targetMethodName, targetOpcode, govClassName));
                    }
                    return null;
                }

                ByteArrayOutputStream outBaos = new ByteArrayOutputStream(b.length + 128);
                DataOutputStream outDos = new DataOutputStream(outBaos);
                outDos.write(b, 0, 8);
                outDos.writeShort(cpCount + 6);
                outDos.write(b, 10, pos - 10);
                outDos.write(cpExtBaos.toByteArray());
                outDos.write(rest);
                outDos.flush();

                if (logTag != null) {
                    PZOLogger.success(String.format("[PZO Agent] %s (%d call sites)", logTag, patched));
                }
                return outBaos.toByteArray();
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during call-site bytecode redirect (" + targetMethodName + "): " + t.getMessage());
            }
            return null;
        }

        private static String classNameAt(byte[] b, int[] tags, int[] tagOffsets, String[] utf8Strings, int cpCount, int classIdx) {
            if (classIdx <= 0 || classIdx >= cpCount || tags[classIdx] != 7) return null;
            int p = tagOffsets[classIdx] + 1;
            int nameIdx = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            return (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : null;
        }

        /** Applies redirects in order; each one builds on the previous result. Returns null if none applied. */
        private byte[] chain(byte[] original, java.util.function.UnaryOperator<byte[]>... steps) {
            byte[] current = original;
            boolean changed = false;
            for (java.util.function.UnaryOperator<byte[]> step : steps) {
                byte[] next = step.apply(current);
                if (next != null) {
                    current = next;
                    changed = true;
                }
            }
            return changed ? current : null;
        }

        private byte[] patchChunkSaveWorker(byte[] b) {
            // HotsaveAncilliarySystems is a private instance method (invokevirtual on Java 11+ nestmates)
            return redirectCallSites(
                b,
                0xB6,
                "zombie/iso/ChunkSaveWorker",
                "HotsaveAncilliarySystems",
                "()V",
                "com/pzoptimizer/HotSaveGovernor",
                "hotsaveAncilliarySystems",
                "(Ljava/lang/Object;)V",
                "Bytecode-patched ChunkSaveWorker: HotSaveGovernor mid-travel hot-save throttle armed"
            );
        }

        private byte[] patchWorldStreamer(byte[] b) {
            // Deferred hot-saves are flushed on the WorldStreamer thread, the same thread vanilla saves on
            byte[] b1 = redirectCallSites(
                b,
                0xB6,
                "zombie/iso/ChunkSaveWorker",
                "Update",
                "(Lzombie/iso/IsoChunk;)V",
                "com/pzoptimizer/HotSaveGovernor",
                "update",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                "Bytecode-patched WorldStreamer: HotSaveGovernor deferred hot-save flush armed"
            );
            byte[] base = b1 != null ? b1 : b;

            // StreamerWake: replace fixed Thread.sleep with LockSupport interruptible idle park
            byte[] b2 = redirectCallSites(
                base,
                0xB8,
                "java/lang/Thread",
                "sleep",
                "(J)V",
                "com/pzoptimizer/StreamerWake",
                "idle",
                "(J)V",
                "Bytecode-patched WorldStreamer: StreamerWake zero-latency park armed"
            );
            base = b2 != null ? b2 : base;

            // StreamerWake: hook jobQueue.add to immediately signal the streamer thread
            byte[] b3 = redirectCallSites(
                base,
                0xB6,
                "java/util/concurrent/ConcurrentLinkedQueue",
                "add",
                "(Ljava/lang/Object;)Z",
                "com/pzoptimizer/StreamerWake",
                "enqueueChunk",
                "(Ljava/util/concurrent/ConcurrentLinkedQueue;Ljava/lang/Object;)Z",
                "Bytecode-patched WorldStreamer: StreamerWake instantaneous enqueue signal armed"
            );
            return b3 != null ? b3 : base;
        }

        private byte[] redirectRecalcPropertiesIfNeeded(byte[] b) {
            return redirectCallSites(
                b,
                0xB6,
                "zombie/iso/IsoGridSquare",
                "RecalcPropertiesIfNeeded",
                "()V",
                "com/pzoptimizer/ChunkHandoffRecalcGovernor",
                "recalcPropertiesIfNeededFast",
                "(Ljava/lang/Object;)V",
                "Bytecode-patched IsoGridSquare: ChunkHandoffRecalcGovernor clean-square recalc gate armed"
            );
        }

        @SuppressWarnings("unchecked")
        private byte[] patchFileGuidTable(byte[] b) {
            return chain(b,
                cur -> redirectCallSites(cur, 0xB9, "java/util/Map", "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    "com/pzoptimizer/LookupMemoGovernor", "mapGetFast",
                    "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;",
                    "Bytecode-patched FileGuidTable: LookupMemoGovernor O(1) GUID hash memo armed"),
                cur -> redirectCallSites(cur, 0xB9, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    "com/pzoptimizer/LookupMemoGovernor", "mapPutFast",
                    "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    "Bytecode-patched FileGuidTable: LookupMemoGovernor put invalidation armed"),
                cur -> redirectCallSites(cur, 0xB9, "java/util/Map", "clear", "()V",
                    "com/pzoptimizer/LookupMemoGovernor", "mapClearFast", "(Ljava/util/Map;)V",
                    "Bytecode-patched FileGuidTable: LookupMemoGovernor clear invalidation armed"));
        }

        @SuppressWarnings("unchecked")
        private byte[] patchAnimationVariableHandlePool(byte[] b) {
            return chain(b,
                cur -> redirectCallSites(cur, 0xB9, "java/util/Map", "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    "com/pzoptimizer/LookupMemoGovernor", "mapGetFast",
                    "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;",
                    "Bytecode-patched AnimationVariableHandlePool: LookupMemoGovernor O(1) handle memo armed"),
                cur -> redirectCallSites(cur, 0xB9, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    "com/pzoptimizer/LookupMemoGovernor", "mapPutFast",
                    "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    "Bytecode-patched AnimationVariableHandlePool: LookupMemoGovernor put invalidation armed"));
        }

        private byte[] patchPropertyContainer(byte[] b) {
            return redirectCallSites(
                b,
                0xB6,
                "zombie/core/TilePropertyAliasMap",
                "getIDFromPropertyName",
                "(Ljava/lang/String;)I",
                "com/pzoptimizer/PropIdCacheGovernor",
                "getIDFromPropertyNameFast",
                "(Ljava/lang/Object;Ljava/lang/String;)I",
                "Bytecode-patched PropertyContainer: PropIdCacheGovernor property-ID fast cache armed"
            );
        }

        private byte[] patchIsoWorld(byte[] b) {
            // The alias map is regenerated in place on every world load; the cache must be rebuilt after it
            return redirectCallSites(
                b,
                0xB6,
                "zombie/core/TilePropertyAliasMap",
                "Generate",
                "(Ljava/util/HashMap;)V",
                "com/pzoptimizer/PropIdCacheGovernor",
                "generate",
                "(Ljava/lang/Object;Ljava/util/HashMap;)V",
                "Bytecode-patched IsoWorld: PropIdCacheGovernor alias-map regeneration hook armed"
            );
        }

        private byte[] patchAnimationPlayer(byte[] b) {
            byte[] withAlloc = redirectCallSites(b, 0xB8, "zombie/core/physics/RagdollController", "alloc",
                "()Lzombie/core/physics/RagdollController;",
                "com/pzoptimizer/RagdollGateGovernor", "allocController",
                "()Lzombie/core/physics/RagdollController;",
                "Bytecode-patched AnimationPlayer: RagdollGateGovernor controller alloc tracking armed");
            if (withAlloc == null) return null;
            byte[] withRelease = redirectCallSites(withAlloc, 0xB8, "zombie/util/Pool", "tryRelease",
                "(Lzombie/util/IPooledObject;)Lzombie/util/IPooledObject;",
                "com/pzoptimizer/RagdollGateGovernor", "releaseController",
                "(Lzombie/util/IPooledObject;)Lzombie/util/IPooledObject;",
                "Bytecode-patched AnimationPlayer: RagdollGateGovernor controller release tracking armed");
            // Half-installed tracking would undercount live ragdolls, so ship neither unless both apply
            if (withRelease == null) return null;
            RagdollGateGovernor.markTrackingInstalled();
            return withRelease;
        }

        private byte[] patchIsoGameCharacter(byte[] b) {
            try {
                DataInputStream dis = new DataInputStream(new ByteArrayInputStream(b));
                int magic = dis.readInt();
                int minor = dis.readUnsignedShort();
                int major = dis.readUnsignedShort();
                int cpCount = dis.readUnsignedShort();

                ByteArrayOutputStream cpBaos = new ByteArrayOutputStream();
                DataOutputStream cpDos = new DataOutputStream(cpBaos);

                String[] utf8Strings = new String[cpCount + 8];
                int i = 1;
                while (i < cpCount) {
                    int tag = dis.readUnsignedByte();
                    cpDos.writeByte(tag);
                    if (tag == 1) {
                        String s = dis.readUTF();
                        cpDos.writeUTF(s);
                        utf8Strings[i] = s;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        cpDos.writeShort(dis.readUnsignedShort());
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18 || tag == 3 || tag == 4) {
                        cpDos.writeInt(dis.readInt());
                    } else if (tag == 5 || tag == 6) {
                        cpDos.writeLong(dis.readLong());
                        i++;
                    } else if (tag == 15) {
                        cpDos.writeByte(dis.readUnsignedByte());
                        cpDos.writeShort(dis.readUnsignedShort());
                    } else {
                        return null;
                    }
                    i++;
                }

                // Append RagdollGateGovernor.isRagdollFast(Object)Z
                int govClassUtf8 = cpCount;
                cpDos.writeByte(1); cpDos.writeUTF("com/pzoptimizer/RagdollGateGovernor");
                int govClass = cpCount + 1;
                cpDos.writeByte(7); cpDos.writeShort(govClassUtf8);
                int govMethodNameUtf8 = cpCount + 2;
                cpDos.writeByte(1); cpDos.writeUTF("isRagdollFast");
                int govMethodDescUtf8 = cpCount + 3;
                cpDos.writeByte(1); cpDos.writeUTF("(Ljava/lang/Object;)Z");
                int govNat = cpCount + 4;
                cpDos.writeByte(12); cpDos.writeShort(govMethodNameUtf8); cpDos.writeShort(govMethodDescUtf8);
                int govMethodRef = cpCount + 5;
                cpDos.writeByte(10); cpDos.writeShort(govClass); cpDos.writeShort(govNat);

                int newCpCount = cpCount + 6;

                ByteArrayOutputStream outBaos = new ByteArrayOutputStream(b.length + 64);
                DataOutputStream outDos = new DataOutputStream(outBaos);
                outDos.writeInt(magic);
                outDos.writeShort(minor);
                outDos.writeShort(major);
                outDos.writeShort(newCpCount);
                outDos.write(cpBaos.toByteArray());

                outDos.writeShort(dis.readUnsignedShort());
                outDos.writeShort(dis.readUnsignedShort());
                outDos.writeShort(dis.readUnsignedShort());

                int ifaces = dis.readUnsignedShort();
                outDos.writeShort(ifaces);
                for (int k = 0; k < ifaces; k++) outDos.writeShort(dis.readUnsignedShort());

                int fields = dis.readUnsignedShort();
                outDos.writeShort(fields);
                for (int f = 0; f < fields; f++) {
                    outDos.writeShort(dis.readUnsignedShort());
                    outDos.writeShort(dis.readUnsignedShort());
                    outDos.writeShort(dis.readUnsignedShort());
                    int attrs = dis.readUnsignedShort();
                    outDos.writeShort(attrs);
                    for (int a = 0; a < attrs; a++) {
                        outDos.writeShort(dis.readUnsignedShort());
                        int len = dis.readInt();
                        outDos.writeInt(len);
                        outDos.write(dis.readNBytes(len));
                    }
                }

                int methods = dis.readUnsignedShort();
                outDos.writeShort(methods);
                boolean patched = false;

                for (int m = 0; m < methods; m++) {
                    int access = dis.readUnsignedShort();
                    int nameIdx = dis.readUnsignedShort();
                    int descIdx = dis.readUnsignedShort();
                    outDos.writeShort(access);
                    outDos.writeShort(nameIdx);
                    outDos.writeShort(descIdx);

                    String mName = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                    String mDesc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : "";

                    int attrs = dis.readUnsignedShort();
                    outDos.writeShort(attrs);

                    for (int a = 0; a < attrs; a++) {
                        int anameIdx = dis.readUnsignedShort();
                        int alen = dis.readInt();
                        String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";

                        if ("Code".equals(aname) && "isRagdoll".equals(mName) && "()Z".equals(mDesc)) {
                            int maxStack = dis.readUnsignedShort();
                            int maxLocals = dis.readUnsignedShort();
                            int codeLen = dis.readInt();
                            dis.readNBytes(codeLen);
                            int exTableLen = dis.readUnsignedShort();
                            dis.readNBytes(exTableLen * 8);
                            int codeAttrs = dis.readUnsignedShort();
                            for (int ca = 0; ca < codeAttrs; ca++) {
                                dis.readUnsignedShort();
                                int calen = dis.readInt();
                                dis.readNBytes(calen);
                            }

                            byte refHi = (byte) ((govMethodRef >> 8) & 0xFF);
                            byte refLo = (byte) (govMethodRef & 0xFF);
                            byte[] fastCode = new byte[] {
                                0x2A,                       // aload_0
                                (byte) 0xB8, refHi, refLo,  // invokestatic RagdollGateGovernor.isRagdollFast(Object)Z
                                (byte) 0xAC                 // ireturn
                            };

                            ByteArrayOutputStream caBaos = new ByteArrayOutputStream(32);
                            DataOutputStream caDos = new DataOutputStream(caBaos);
                            caDos.writeShort(Math.max(maxStack, 1));
                            caDos.writeShort(Math.max(maxLocals, 1));
                            caDos.writeInt(fastCode.length);
                            caDos.write(fastCode);
                            caDos.writeShort(0); // exTableLen = 0
                            caDos.writeShort(0); // codeAttrs = 0 (branchless body needs no StackMapTable)
                            caDos.flush();

                            byte[] caBytes = caBaos.toByteArray();
                            outDos.writeShort(anameIdx);
                            outDos.writeInt(caBytes.length);
                            outDos.write(caBytes);
                            patched = true;
                        } else {
                            outDos.writeShort(anameIdx);
                            outDos.writeInt(alen);
                            outDos.write(dis.readNBytes(alen));
                        }
                    }
                }

                int cattrs = dis.readUnsignedShort();
                outDos.writeShort(cattrs);
                for (int ca = 0; ca < cattrs; ca++) {
                    outDos.writeShort(dis.readUnsignedShort());
                    int len = dis.readInt();
                    outDos.writeInt(len);
                    outDos.write(dis.readNBytes(len));
                }

                if (patched) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched IsoGameCharacter.isRagdoll: RagdollGateGovernor direct field gate armed (ModelManager lookup bypassed)");
                    return outBaos.toByteArray();
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during IsoGameCharacter bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchIsoAnimal(byte[] b) {
            byte[] out = prefixSkipIfTrue(b, "updateLOS", "()V", "com/pzoptimizer/AnimalLosSkipGovernor", "shouldSkipUpdateLOS");
            if (out != null) {
                PZOLogger.success("[PZO Agent] Bytecode-patched IsoAnimal.updateLOS: AnimalLosSkipGovernor distant calm-animal LOS skip armed");
            }
            return out;
        }

        private byte[] patchPathfindNativeThread(byte[] b) {
            byte[] out = prefixSkipIfTrue(b, "updateThread", "()V", "com/pzoptimizer/PathfindGovernor", "updateThread");
            if (out != null) {
                PZOLogger.success("[PZO Agent] Bytecode-patched PathfindNativeThread.updateThread: PathfindGovernor request budget and square merge armed");
            }
            return out;
        }

        private byte[] patchPathfindNative(byte[] b) {
            return redirectCallSites(b, 0xB6, "zombie/pathfind/nativeCode/VehicleState", "check", "()Z",
                "com/pzoptimizer/PathfindGovernor", "vehicleCheck", "(Ljava/lang/Object;)Z",
                "Bytecode-patched PathfindNative.updateMain: PathfindGovernor vehicle navmesh throttle armed");
        }

        // Both redirects pass straight through to the recorder unless a debug launch has switched PhaseProfiler on
        private byte[] patchGameProfiler(byte[] b) {
            byte[] spans = redirectCallSites(b, 0xB6, "zombie/GameProfileRecording", "logTimeSpan", "(Lzombie/GameProfiler$ProfileArea;)V",
                "com/pzoptimizer/PhaseProfiler", "logTimeSpan", "(Ljava/lang/Object;Ljava/lang/Object;)V",
                "Bytecode-patched GameProfiler.end: PhaseProfiler debug timing hook armed");
            if (spans == null) return null;
            byte[] lines = redirectCallSites(spans, 0xB6, "zombie/GameProfileRecording", "writeLine", "()V",
                "com/pzoptimizer/PhaseProfiler", "writeLine", "(Ljava/lang/Object;)V",
                "Bytecode-patched GameProfiler.startFrame: vanilla profiler CSV writes routed through PhaseProfiler");
            return (lines != null) ? lines : spans;
        }

        /**
         * Prepends "if (gov(this)) return;" to an instance method with no arguments and a void return.
         * The governor is a static (Object)Z method.
         */
        private byte[] prefixSkipIfTrue(byte[] b, String targetName, String targetDesc, String govClassName, String govMethodName) {
            try {
                DataInputStream dis = new DataInputStream(new ByteArrayInputStream(b));
                int magic = dis.readInt();
                int minor = dis.readUnsignedShort();
                int major = dis.readUnsignedShort();
                int cpCount = dis.readUnsignedShort();

                ByteArrayOutputStream cpBaos = new ByteArrayOutputStream();
                DataOutputStream cpDos = new DataOutputStream(cpBaos);

                String[] utf8Strings = new String[cpCount + 16];
                int i = 1;
                while (i < cpCount) {
                    int tag = dis.readUnsignedByte();
                    cpDos.writeByte(tag);
                    if (tag == 1) {
                        String s = dis.readUTF();
                        cpDos.writeUTF(s);
                        utf8Strings[i] = s;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        cpDos.writeShort(dis.readUnsignedShort());
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18 || tag == 3 || tag == 4) {
                        cpDos.writeInt(dis.readInt());
                    } else if (tag == 5 || tag == 6) {
                        cpDos.writeLong(dis.readLong());
                        i++;
                    } else if (tag == 15) {
                        cpDos.writeByte(dis.readUnsignedByte());
                        cpDos.writeShort(dis.readUnsignedShort());
                    } else {
                        return null;
                    }
                    i++;
                }

                int govClassUtf8 = cpCount;
                cpDos.writeByte(1); cpDos.writeUTF(govClassName);
                int govClass = cpCount + 1;
                cpDos.writeByte(7); cpDos.writeShort(govClassUtf8);
                int govMethodNameUtf8 = cpCount + 2;
                cpDos.writeByte(1); cpDos.writeUTF(govMethodName);
                int govMethodDescUtf8 = cpCount + 3;
                cpDos.writeByte(1); cpDos.writeUTF("(Ljava/lang/Object;)Z");
                int govNat = cpCount + 4;
                cpDos.writeByte(12); cpDos.writeShort(govMethodNameUtf8); cpDos.writeShort(govMethodDescUtf8);
                int govMethodRef = cpCount + 5;
                cpDos.writeByte(10); cpDos.writeShort(govClass); cpDos.writeShort(govNat);

                int newCpCount = cpCount + 6;

                ByteArrayOutputStream outBaos = new ByteArrayOutputStream(b.length + 128);
                DataOutputStream outDos = new DataOutputStream(outBaos);
                outDos.writeInt(magic);
                outDos.writeShort(minor);
                outDos.writeShort(major);
                outDos.writeShort(newCpCount);
                outDos.write(cpBaos.toByteArray());

                outDos.writeShort(dis.readUnsignedShort());
                outDos.writeShort(dis.readUnsignedShort());
                outDos.writeShort(dis.readUnsignedShort());

                int ifaces = dis.readUnsignedShort();
                outDos.writeShort(ifaces);
                for (int k = 0; k < ifaces; k++) outDos.writeShort(dis.readUnsignedShort());

                int fields = dis.readUnsignedShort();
                outDos.writeShort(fields);
                for (int f = 0; f < fields; f++) {
                    outDos.writeShort(dis.readUnsignedShort());
                    outDos.writeShort(dis.readUnsignedShort());
                    outDos.writeShort(dis.readUnsignedShort());
                    int attrs = dis.readUnsignedShort();
                    outDos.writeShort(attrs);
                    for (int a = 0; a < attrs; a++) {
                        outDos.writeShort(dis.readUnsignedShort());
                        int len = dis.readInt();
                        outDos.writeInt(len);
                        outDos.write(dis.readNBytes(len));
                    }
                }

                int methods = dis.readUnsignedShort();
                outDos.writeShort(methods);
                boolean patched = false;

                for (int m = 0; m < methods; m++) {
                    int access = dis.readUnsignedShort();
                    int nameIdx = dis.readUnsignedShort();
                    int descIdx = dis.readUnsignedShort();
                    outDos.writeShort(access);
                    outDos.writeShort(nameIdx);
                    outDos.writeShort(descIdx);

                    String mName = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                    String mDesc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : "";

                    int attrs = dis.readUnsignedShort();
                    outDos.writeShort(attrs);

                    for (int a = 0; a < attrs; a++) {
                        int anameIdx = dis.readUnsignedShort();
                        int alen = dis.readInt();
                        String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";

                        if ("Code".equals(aname) && targetName.equals(mName) && targetDesc.equals(mDesc) && (access & 0x0008) == 0) {
                            int maxStack = dis.readUnsignedShort();
                            int maxLocals = dis.readUnsignedShort();
                            int codeLen = dis.readInt();
                            byte[] origCode = dis.readNBytes(codeLen);
                            int exTableLen = dis.readUnsignedShort();
                            byte[] exTable = dis.readNBytes(exTableLen * 8);
                            int codeAttrs = dis.readUnsignedShort();

                            byte refHi = (byte) ((govMethodRef >> 8) & 0xFF);
                            byte refLo = (byte) (govMethodRef & 0xFF);
                            byte[] prefix = new byte[] {
                                0x2A,                       // 0: aload_0
                                (byte) 0xB8, refHi, refLo,  // 1: invokestatic gov(Object)Z
                                (byte) 0x99, 0x00, 0x04,    // 4: ifeq +4 -> offset 8
                                (byte) 0xB1                 // 7: return
                            };

                            // Shift exception table PCs by +8 if any exist
                            for (int ex = 0; ex < exTableLen; ex++) {
                                int base = ex * 8;
                                int startPc = (((exTable[base] & 0xFF) << 8) | (exTable[base + 1] & 0xFF)) + prefix.length;
                                int endPc = (((exTable[base + 2] & 0xFF) << 8) | (exTable[base + 3] & 0xFF)) + prefix.length;
                                int handlerPc = (((exTable[base + 4] & 0xFF) << 8) | (exTable[base + 5] & 0xFF)) + prefix.length;
                                exTable[base] = (byte) ((startPc >> 8) & 0xFF);
                                exTable[base + 1] = (byte) (startPc & 0xFF);
                                exTable[base + 2] = (byte) ((endPc >> 8) & 0xFF);
                                exTable[base + 3] = (byte) (endPc & 0xFF);
                                exTable[base + 4] = (byte) ((handlerPc >> 8) & 0xFF);
                                exTable[base + 5] = (byte) (handlerPc & 0xFF);
                            }

                            byte[] newCode = new byte[prefix.length + origCode.length];
                            System.arraycopy(prefix, 0, newCode, 0, prefix.length);
                            System.arraycopy(origCode, 0, newCode, prefix.length, origCode.length);

                            ByteArrayOutputStream caBaos = new ByteArrayOutputStream();
                            DataOutputStream caDos = new DataOutputStream(caBaos);
                            caDos.writeShort(Math.max(maxStack, 1));
                            caDos.writeShort(maxLocals);
                            caDos.writeInt(newCode.length);
                            caDos.write(newCode);
                            caDos.writeShort(exTableLen);
                            caDos.write(exTable);

                            List<Integer> retainedAttrNames = new ArrayList<>();
                            List<byte[]> retainedAttrPayloads = new ArrayList<>();

                            for (int ca = 0; ca < codeAttrs; ca++) {
                                int canameIdx = dis.readUnsignedShort();
                                int calen = dis.readInt();
                                byte[] cadata = dis.readNBytes(calen);
                                String caname = (canameIdx > 0 && canameIdx < cpCount) ? utf8Strings[canameIdx] : "";

                                if ("LocalVariableTable".equals(caname) || "LocalVariableTypeTable".equals(caname) || "LineNumberTable".equals(caname)) {
                                    continue;
                                }

                                if ("StackMapTable".equals(caname)) {
                                    DataInputStream smDis = new DataInputStream(new ByteArrayInputStream(cadata));
                                    int numEntries = smDis.readUnsignedShort();
                                    ByteArrayOutputStream smBaos = new ByteArrayOutputStream();
                                    DataOutputStream smDos = new DataOutputStream(smBaos);
                                    smDos.writeShort(numEntries + 1);
                                    smDos.writeByte(0x08); // same_frame at offset 8

                                    if (numEntries > 0) {
                                        int firstTag = smDis.readUnsignedByte();
                                        // A first frame at offset 0 cannot be shifted behind the inserted one
                                        if (firstTag == 0 || firstTag == 64) return null;
                                        if (firstTag >= 0 && firstTag <= 63) {
                                            smDos.writeByte(firstTag - 1);
                                        } else if (firstTag >= 64 && firstTag <= 127) {
                                            smDos.writeByte(firstTag - 1);
                                            copyVerificationTypeInfo(smDis, smDos);
                                        } else if (firstTag == 247) {
                                            smDos.writeByte(247);
                                            int delta = smDis.readUnsignedShort();
                                            smDos.writeShort(delta - 1);
                                            copyVerificationTypeInfo(smDis, smDos);
                                        } else if (firstTag >= 248 && firstTag <= 251) {
                                            smDos.writeByte(firstTag);
                                            int delta = smDis.readUnsignedShort();
                                            smDos.writeShort(delta - 1);
                                        } else if (firstTag == 252 || firstTag == 253 || firstTag == 254) {
                                            smDos.writeByte(firstTag);
                                            int delta = smDis.readUnsignedShort();
                                            smDos.writeShort(delta - 1);
                                            int numLocals = firstTag - 251;
                                            for (int l = 0; l < numLocals; l++) copyVerificationTypeInfo(smDis, smDos);
                                        } else if (firstTag == 255) {
                                            smDos.writeByte(255);
                                            int delta = smDis.readUnsignedShort();
                                            smDos.writeShort(delta - 1);
                                            int numL = smDis.readUnsignedShort();
                                            smDos.writeShort(numL);
                                            for (int l = 0; l < numL; l++) copyVerificationTypeInfo(smDis, smDos);
                                            int numS = smDis.readUnsignedShort();
                                            smDos.writeShort(numS);
                                            for (int s = 0; s < numS; s++) copyVerificationTypeInfo(smDis, smDos);
                                        } else {
                                            smDos.writeByte(firstTag);
                                            int delta = smDis.readUnsignedShort();
                                            smDos.writeShort(delta - 1);
                                        }
                                        byte[] remainingSm = smDis.readAllBytes();
                                        smDos.write(remainingSm);
                                    }
                                    retainedAttrNames.add(canameIdx);
                                    retainedAttrPayloads.add(smBaos.toByteArray());
                                } else {
                                    retainedAttrNames.add(canameIdx);
                                    retainedAttrPayloads.add(cadata);
                                }
                            }

                            caDos.writeShort(retainedAttrNames.size());
                            for (int k = 0; k < retainedAttrNames.size(); k++) {
                                caDos.writeShort(retainedAttrNames.get(k));
                                caDos.writeInt(retainedAttrPayloads.get(k).length);
                                caDos.write(retainedAttrPayloads.get(k));
                            }

                            byte[] caBytes = caBaos.toByteArray();
                            outDos.writeShort(anameIdx);
                            outDos.writeInt(caBytes.length);
                            outDos.write(caBytes);
                            patched = true;
                        } else {
                            outDos.writeShort(anameIdx);
                            outDos.writeInt(alen);
                            outDos.write(dis.readNBytes(alen));
                        }
                    }
                }

                int cattrs = dis.readUnsignedShort();
                outDos.writeShort(cattrs);
                for (int ca = 0; ca < cattrs; ca++) {
                    outDos.writeShort(dis.readUnsignedShort());
                    int len = dis.readInt();
                    outDos.writeInt(len);
                    outDos.write(dis.readNBytes(len));
                }

                if (patched) {
                    return outBaos.toByteArray();
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during " + targetName + " prefix transform: " + t.getMessage());
            }
            return null;
        }
    }
}
