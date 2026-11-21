package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps AnimalManagerMain.fromWorker from throwing when it picks a zero facing for an animal (GitHub issue #80).
 *
 * When a chunk with animals loads, 42.22 sets each one's facing to the herd's direction plus (Rand.Next(360),
 * Rand.Next(360)). Herd directions are often exactly (-1, 0), (0, -1) or (0, 0), so now and then the sum is zero and
 * setForwardDirection throws in the middle of the chunk load. A zero sum gets a random unit direction instead, which is
 * what the random offset was there for anyway.
 */
public final class AnimalSpawnDirGuard {

    public static final AtomicLong caught = new AtomicLong();

    private AnimalSpawnDirGuard() {}

    /** Stands in for isoAnimal.setForwardDirection(x, y) inside fromWorker. */
    public static void setForwardDirection(zombie.characters.animals.IsoAnimal animal, float x, float y) {
        if (x * x + y * y < 1.0E-6F) {
            if (caught.incrementAndGet() == 1) {
                PZOLogger.info("[AnimalSpawnDirGuard] an animal spawned with a zero facing; gave it a random one (the game would throw here)");
            }
            double a = Math.toRadians(zombie.core.random.Rand.Next(360));
            x = (float) Math.cos(a);
            y = (float) Math.sin(a);
        }
        animal.setForwardDirection(x, y);
    }

    static boolean patchManager(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "fromWorker", "(Ljava/util/ArrayList;)V");
        if (m == null) return false;
        return AsmSupport.redirectCalls(m, "zombie/characters/animals/IsoAnimal", "setForwardDirection", "(FF)V",
            "com/pzoptimizer/AnimalSpawnDirGuard", "setForwardDirection", "(Lzombie/characters/animals/IsoAnimal;FF)V") > 0;
    }
}
