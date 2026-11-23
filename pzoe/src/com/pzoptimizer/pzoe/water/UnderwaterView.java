package com.pzoptimizer.pzoe.water;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import fmod.javafmod;
import fmod.javafmodJNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

public final class UnderwaterView {
    public static final String SOUND_MANAGER = "zombie/SoundManager";
    public static volatile long shaderAskedNanos;
    private static volatile long audioSampleNanos;
    private static volatile float audioTarget;
    private static long group;
    private static float previousGain, appliedGain;
    private static long retryAt;

    private UnderwaterView() {}

    public static float sample(int index, long now) {
        float amount = now - shaderAskedNanos < 1_000_000_000L ? WaterSwimming.submersion(IsoPlayer.players[index]) : 0f;
        if (index == 0) {
            audioSampleNanos = now;
            audioTarget = IsoPlayer.numPlayers == 1 ? amount : 0f;
        }
        return amount;
    }

    public static void audio() {
        if (GameServer.server || (group == 0L && audioTarget <= 0f)) return;
        long now = System.nanoTime();
        float amount = now - audioSampleNanos < 1_000_000_000L && now - shaderAskedNanos < 1_000_000_000L ? audioTarget : 0f;
        try {
            if (amount <= 0f) {
                if (group != 0L) javafmodJNI.FMOD_ChannelGroup_SetLowPassGain(group, previousGain);
                group = 0L;
                return;
            }
            if (group == 0L) {
                if (now < retryAt) return;
                retryAt = now + 5_000_000_000L;
                long master = javafmod.FMOD_System_GetMasterChannelGroup();
                if (master == 0L) return;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var gain = stack.mallocFloat(1);
                    if (javafmodJNI.FMOD_ChannelGroup_GetLowPassGain(master, MemoryUtil.memAddress(gain)) != 0) return;
                    previousGain = gain.get(0);
                }
                if (!Float.isFinite(previousGain) || previousGain < 0f || previousGain > 1f) return;
                group = master;
                appliedGain = previousGain;
            }
            float gain = previousGain * (1f - 0.78f * amount);
            if (Math.abs(gain - appliedGain) < 0.015f) return;
            if (javafmodJNI.FMOD_ChannelGroup_SetLowPassGain(group, gain) == 0) appliedGain = gain;
            else group = 0L;
        } catch (Throwable ignored) {
            if (group != 0L) {
                try {
                    javafmodJNI.FMOD_ChannelGroup_SetLowPassGain(group, previousGain);
                } catch (Throwable unavailable) {
                }
            }
            group = 0L;
            retryAt = now + 5_000_000_000L;
        }
    }

    public static boolean patchSoundManager(ClassNode node) {
        var update = AsmSupport.findMethod(node, "Update", "()V");
        if (update == null) return false;
        update.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "com/pzoptimizer/pzoe/water/UnderwaterView", "audio", "()V", false));
        return true;
    }
}
