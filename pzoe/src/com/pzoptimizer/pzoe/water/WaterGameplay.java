package com.pzoptimizer.pzoe.water;

import zombie.SandboxOptions;
import zombie.ZomboidFileSystem;
import zombie.gameStates.ChooseGameInfo;

final class WaterGameplay {
    private static long lastConflictCheck;
    private static boolean realWaterActive;

    private WaterGameplay() {}

    static boolean enabled(String name) {
        var options = SandboxOptions.instance;
        return options != null && options.getOptionByName(name) instanceof SandboxOptions.BooleanSandboxOption option
            && option.getValue() && !overlappingMod();
    }

    private static boolean overlappingMod() {
        long now = System.nanoTime();
        if (lastConflictCheck != 0L && now - lastConflictCheck < 2_000_000_000L) return realWaterActive;
        lastConflictCheck = now;
        realWaterActive = false;
        for (String id : ZomboidFileSystem.instance.getModIDs()) {
            ChooseGameInfo.Mod mod = ChooseGameInfo.getModDetails(id);
            if (mod == null || !"3832474484".equals(mod.getWorkshopID())) continue;
            realWaterActive = true;
            break;
        }
        return realWaterActive;
    }
}
