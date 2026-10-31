package com.pzoptimizer;

/**
 * Mod classes seen by the agent's transformer as they load, from any class loader (ZombieBuddy and other Java mod
 * loaders included). Kept free of game references so the transformer can touch it without loading anything else.
 */
public final class ClassSightings {

    public static volatile boolean viewpointLoaded;

    private ClassSightings() {}

    static void note(String internalName) {
        if (internalName.startsWith("viewpoint/") || internalName.startsWith("com/viewpoint/")
                || internalName.startsWith("com/trueweathers/")) {
            viewpointLoaded = true;
        }
    }
}
