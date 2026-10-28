package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.Arrays;
import zombie.core.PerformanceSettings;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunkLevel;
import zombie.iso.IsoWorld;
import zombie.iso.objects.RainManager;

/**
 * Eliminates heavy rain micro-stutter and frame-time spikes:
 * 1. Replaces vanilla's synchronized 64-RNG-checks per chunk level with an O(1) non-blocking
 *    geometric inverse transform skip, reducing RNG calls by over 99%.
 * 2. Caches the 4 splash frame textures to eliminate per-splash HashMap queries and string concats.
 */
public final class RainSplashGovernor {

    public static volatile boolean active = true;

    private static final Texture[] SPLASH_TEXTURES = new Texture[4];
    private static volatile boolean texturesLoaded = false;

    // Fast 64-bit linear congruential PRNG for rain splash geometric distribution
    private static long rngSeed = 0x5DEECE66DL;
    private static int skipCount = 0;

    private RainSplashGovernor() {}

    private static double nextUniform() {
        rngSeed = (rngSeed * 0x5DEECE66DL + 0xBL) & 0xFFFFFFFFFFFFL;
        return (double) rngSeed / (double) 0x1000000000000L;
    }

    private static int calculateSkip(int divisor) {
        if (divisor <= 1) return 0;
        double u = nextUniform();
        if (u <= 0.0) u = 1e-7;
        double p = 1.0 / divisor;
        int s = (int) (Math.log(u) / Math.log(1.0 - p));
        return Math.max(0, s);
    }

    public static void updateRainSplashes(IsoChunkLevel level) {
        if (!active || level == null) {
            if (level != null) level.updateRainSplashes();
            return;
        }

        if (level.rainSplashFrameNum == IsoCamera.frameState.frameCount) {
            return;
        }
        level.rainSplashFrameNum = IsoCamera.frameState.frameCount;

        IsoCell cell = (IsoWorld.instance != null) ? IsoWorld.instance.currentCell : null;
        int cellIntensity = (cell != null) ? cell.getRainIntensity() : 0;
        boolean isRaining = cellIntensity > 0 || (RainManager.isRaining() && RainManager.rainIntensity > 0.0F);

        if (!isRaining) {
            if (level.raining) {
                level.raining = false;
                Arrays.fill(level.rainSplashFrame, -1.0F);
            }
            return;
        }

        level.raining = true;
        if (IsoCamera.frameState.paused) {
            return;
        }

        int intensity = cellIntensity;
        if (intensity <= 0) {
            intensity = Math.min((int) Math.floor(RainManager.rainIntensity / 0.2F) + 1, 5);
        }

        int baseChance = (int) (5.0F / intensity) * 100;
        int lockFPS = PerformanceSettings.getLockFPS();
        int divisor = (int) (baseChance * (lockFPS / 30.0F));
        if (divisor < 1) divisor = 1;

        float step = 0.08F * (30.0F / lockFPS);
        float[] frames = level.rainSplashFrame;
        byte[] flags = level.rainFlags;
        int len = frames.length;

        for (int i = 0; i < len; i++) {
            float f = frames[i];
            if (f < 0.0F) {
                if (skipCount == 0) {
                    frames[i] = 0.0F;
                    flags[i] = (byte) (flags[i] | 2);
                    skipCount = calculateSkip(divisor);
                } else {
                    skipCount--;
                }
            } else {
                f += step;
                if (f >= 1.0F) {
                    frames[i] = -1.0F;
                } else {
                    frames[i] = f;
                }
            }
        }
    }

    public static Texture getSplashTexture(String name) {
        if (!active) {
            return Texture.getSharedTexture(name);
        }
        if (!texturesLoaded) {
            for (int i = 0; i < 4; i++) {
                SPLASH_TEXTURES[i] = Texture.getSharedTexture("RainSplash_00_" + i);
            }
            texturesLoaded = true;
        }
        if (name != null && name.startsWith("RainSplash_00_") && name.length() == 15) {
            int frame = name.charAt(14) - '0';
            if (frame >= 0 && frame < 4) {
                Texture t = SPLASH_TEXTURES[frame];
                if (t != null) return t;
            }
        }
        return Texture.getSharedTexture(name);
    }

    /**
     * ASM transformer for zombie.iso.IsoChunkLevel:
     * Replaces updateRainSplashes() with RainSplashGovernor.updateRainSplashes(this).
     */
    public static boolean patchChunkLevel(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "updateRainSplashes", "()V");
        if (m == null) return false;
        m.instructions.clear();
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "com/pzoptimizer/RainSplashGovernor",
            "updateRainSplashes",
            "(Lzombie/iso/IsoChunkLevel;)V",
            false
        ));
        list.add(new InsnNode(Opcodes.RETURN));
        m.instructions.add(list);
        return true;
    }

    /**
     * ASM transformer for zombie.iso.IsoGridSquare:
     * Redirects Texture.getSharedTexture in renderRainSplash to RainSplashGovernor.getSplashTexture.
     */
    public static boolean patchGridSquare(ClassNode node) {
        boolean changed = false;
        for (MethodNode m : node.methods) {
            if ("renderRainSplash".equals(m.name)) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                            && "zombie/core/textures/Texture".equals(mi.owner)
                            && "getSharedTexture".equals(mi.name)
                            && "(Ljava/lang/String;)Lzombie/core/textures/Texture;".equals(mi.desc)) {
                        mi.owner = "com/pzoptimizer/RainSplashGovernor";
                        mi.name = "getSplashTexture";
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }
}
