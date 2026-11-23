package com.pzoptimizer.pzoe.water;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import java.util.WeakHashMap;
import zombie.GameTime;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.network.GameClient;

public final class WaterZombies {
    private static final String OPTION = "PZOEWater.Zombies";
    private static final long DROWN_DELAY = 8_000_000_000L;
    private static final WeakHashMap<IsoZombie, Exposure> exposure = new WeakHashMap<>();

    private static final class Exposure {
        long last;
        long elapsed;

        Exposure(long last) {
            this.last = last;
        }
    }

    private WaterZombies() {}

    static boolean canCross(IsoZombie zombie, IsoGridSquare from) {
        if (zombie.isDead() || !WaterGameplay.enabled(OPTION)) return false;
        IsoMovingObject target = zombie.getTarget();
        return WaterSwimming.openWater(from) || target != null && WaterSwimming.openWater(target.getCurrentSquare());
    }

    public static float movement(float stock, IsoMovingObject object) {
        if (object instanceof IsoZombie zombie && WaterSwimming.openWater(zombie.getCurrentSquare())
                && WaterGameplay.enabled(OPTION)) return stock * 0.35f;
        return stock;
    }

    public static void update(IsoZombie zombie) {
        if (GameClient.client) return;
        if (zombie.isDead() || !WaterSwimming.openWater(zombie.getCurrentSquare()) || !WaterGameplay.enabled(OPTION)) {
            if (!exposure.isEmpty()) exposure.remove(zombie);
            return;
        }
        long now = System.nanoTime();
        Exposure state = exposure.get(zombie);
        if (state == null) {
            exposure.put(zombie, new Exposure(now));
            return;
        }
        if (!GameTime.isGamePaused()) state.elapsed += Math.min(250_000_000L, now - state.last);
        state.last = now;
        if (state.elapsed < DROWN_DELAY) return;
        exposure.remove(zombie);
        zombie.Kill(null, false);
    }

    public static boolean patch(ClassNode node) {
        var update = AsmSupport.findMethod(node, "update", "()V");
        if (update == null) return false;
        AsmSupport.prependCall(update, "com/pzoptimizer/pzoe/water/WaterZombies", "update", "(Lzombie/characters/IsoZombie;)V");
        return true;
    }
}
