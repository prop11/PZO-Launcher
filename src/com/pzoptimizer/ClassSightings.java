package com.pzoptimizer;

/**
 * Mod classes the transformer has seen load, from any class loader (ZombieBuddy and other Java mod loaders too). No
 * game references in here, so the transformer can use it without loading anything else.
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
