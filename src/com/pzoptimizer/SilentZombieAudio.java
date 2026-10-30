package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Settings screen "Silent zombies skip sound upkeep" (JVM_SilentZombieAudio).
 *
 * IsoGameCharacter.updateEmitter recomputes every FMOD parameter of the character each frame (footstep material,
 * inside, player distance, zombie state...) and, every 30 ms, places and ticks its three emitters; for a prone zombie
 * that includes a head bone lookup. For a zombie whose emitters hold no sound at all (nothing queued, playing or
 * fading) all of that feeds nothing: the parameters only reach FMOD event instances, and sounds are only started
 * inside that same emitter tick. The first updateEmitter after a sound is queued finds the emitter not clear and runs
 * in full, so the sound starts with this frame's parameter values and position, exactly as before.
 */
public final class SilentZombieAudio {

    public static volatile boolean active = true;
    public static final AtomicLong skipped = new AtomicLong();

    private SilentZombieAudio() {}

    /** Prefix of IsoGameCharacter.updateEmitter: true skips the method. */
    public static boolean skip(zombie.characters.IsoGameCharacter chr) {
        if (!active || !(chr instanceof zombie.characters.IsoZombie)) return false;
        zombie.characters.BaseCharacterSoundEmitter e = chr.getEmitter();
        if (e == null || !e.isClear()) return false;
        skipped.incrementAndGet();
        return true;
    }

    static boolean patchCharacter(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "updateEmitter", "()V");
        if (m == null) return false;
        AsmSupport.prependSkip(m, "com/pzoptimizer/SilentZombieAudio", "skip", "(Lzombie/characters/IsoGameCharacter;)Z");
        return true;
    }
}
