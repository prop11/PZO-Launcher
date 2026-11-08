package com.pzoptimizer;

/** LWJGL and NIO settings for sprite vertex submission. */
public class SpriteBatchOptimizer {
    private static boolean active = false;

    public static void apply() {
        try {
            // LWJGL reads this once when it loads. Viewpoint_ArcPatch probes GL from main() during loadMods, and with no
            // checks a thread without a context aborts the JVM (#62)
            String probeMod = findGlProbeMod();
            if (probeMod == null) {
                System.setProperty("org.lwjgl.util.NoChecks", "true");
            } else {
                PZOLogger.info("SpriteBatchOptimizer: LWJGL checks left on for " + probeMod);
            }
            System.setProperty("org.lwjgl.util.NoArrayChecks", "true");
            System.setProperty("pzo.sprite.batching", "true");

            active = true;
            PZOLogger.success("SpriteBatchOptimizer active (Zero-copy LWJGL vertex array optimizations configured)");
        } catch (Throwable t) {
            PZOLogger.warn("SpriteBatchOptimizer notice: " + t.getMessage());
        }
    }

    private static String findGlProbeMod() {
        try {
            if (!JavaModLoader.isZombieBuddyPresent()) return null;
            java.util.Set<String> enabled = JavaModLoader.getEnabledModIds();
            for (java.io.File jar : JavaModLoader.deduplicateModJars(JavaModLoader.findJavaModJars())) {
                JavaModLoader.ModMetadata meta = JavaModLoader.findModMetadataForJar(jar);
                boolean on = enabled.isEmpty() || meta.modIds.isEmpty();
                boolean named = isArcPatchName(jar.getName());
                for (String id : meta.modIds) {
                    on |= enabled.contains(id) || enabled.contains(id.toLowerCase());
                    named |= isArcPatchName(id);
                }
                if (on && (named || hasArcPatchClass(jar))) return jar.getName();
            }
        } catch (Throwable t) {
            PZOLogger.warn("SpriteBatchOptimizer: GL probe mod check failed: " + t);
        }
        return null;
    }

    private static boolean isArcPatchName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]", "").contains("arcpatch");
    }

    private static boolean hasArcPatchClass(java.io.File jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
            return zip.getEntry("viewpoint_arcpatch/Main.class") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isActive() {
        return active;
    }
}
