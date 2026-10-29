package com.pzoptimizer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * Calls into game methods whose signature differs between supported builds. PZO compiles against one build, so a
 * direct call links that build's exact signature and throws NoSuchMethodError on the other. These are looked up by
 * name at first use instead:
 * - IsoGridSquare.getObjects(): PZArrayList (42.21, itself a java.util.List) -> List (42.22)
 * - ZomboidFileSystem.getModIDs(): ArrayList (42.21) -> List (42.22)
 * - IsoPlayer.teleportTo(x, y, z): z is an int (42.21) -> float (42.22)
 */
public final class GameCompat {

    private GameCompat() {}

    // Held lazily: the lookups load game classes, which must not happen while the agent is still defining them
    private static final class H {
        static final MethodHandle GET_OBJECTS = find(zombie.iso.IsoGridSquare.class, "getObjects",
            MethodType.methodType(List.class, zombie.iso.IsoGridSquare.class));
        static final MethodHandle GET_MOD_IDS = find(zombie.ZomboidFileSystem.class, "getModIDs",
            MethodType.methodType(List.class, zombie.ZomboidFileSystem.class));
        static final MethodHandle TELEPORT = teleport();

        private static MethodHandle find(Class<?> owner, String name, MethodType erased) {
            try {
                return MethodHandles.publicLookup().unreflect(owner.getMethod(name)).asType(erased);
            } catch (Throwable t) {
                PZOLogger.warn("[GameCompat] " + owner.getSimpleName() + "." + name + "() not found: " + t);
                return null;
            }
        }

        private static MethodHandle teleport() {
            MethodType erased = MethodType.methodType(void.class, zombie.characters.IsoPlayer.class, float.class, float.class, float.class);
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            try {
                return l.findVirtual(zombie.characters.IsoPlayer.class, "teleportTo",
                    MethodType.methodType(void.class, float.class, float.class, float.class));
            } catch (Throwable ignored) {
            }
            try {
                MethodHandle intZ = l.findVirtual(zombie.characters.IsoPlayer.class, "teleportTo",
                    MethodType.methodType(void.class, float.class, float.class, int.class));
                MethodHandle toInt = MethodHandles.lookup().findStatic(GameCompat.class, "floorZ", MethodType.methodType(int.class, float.class));
                return MethodHandles.filterArguments(intZ, 3, toInt).asType(erased);
            } catch (Throwable t) {
                PZOLogger.warn("[GameCompat] IsoPlayer.teleportTo(x, y, z) not found: " + t);
                return null;
            }
        }
    }

    // The 42.21 overload takes a whole level
    private static int floorZ(float z) {
        return (int) Math.floor(z);
    }

    @SuppressWarnings("unchecked")
    public static List<zombie.iso.IsoObject> objects(zombie.iso.IsoGridSquare square) {
        try {
            return (List<zombie.iso.IsoObject>) (List<?>) H.GET_OBJECTS.invokeExact(square);
        } catch (Throwable t) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> modIds() {
        zombie.ZomboidFileSystem fs = zombie.ZomboidFileSystem.instance;
        if (fs == null || H.GET_MOD_IDS == null) return List.of();
        try {
            return (List<String>) (List<?>) H.GET_MOD_IDS.invokeExact(fs);
        } catch (Throwable t) {
            return List.of();
        }
    }

    public static void teleport(zombie.characters.IsoPlayer player, float x, float y, float z) {
        if (H.TELEPORT == null) return;
        try {
            H.TELEPORT.invokeExact(player, x, y, z);
        } catch (Throwable t) {
            PZOLogger.warn("[GameCompat] teleport failed: " + t);
        }
    }
}
