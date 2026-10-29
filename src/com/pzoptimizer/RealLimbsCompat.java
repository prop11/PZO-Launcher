package com.pzoptimizer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Project Real Limbs (ZombieBuddy mod, package reallimbs) gives hit zombies a physics body and drives their pose,
 * states and update from the game thread; any of its hooks reached on another thread is skipped and left to the game.
 * PZO moves zombie bone math (BoneBatch) and action-graph evaluation (ActionParallel) to frame workers and caps the
 * update rate of unseen zombies, so a zombie with a body lost its physics pose. Those zombies, and only those, are kept
 * on the stock path, asked through Real Limbs' own Hooks.ours(IsoZombie), the check it exposes to thread optimisers.
 * Asking also tells Real Limbs that an optimiser threads zombies, which it uses to keep the rest of its hooks safe.
 */
public final class RealLimbsCompat {

    // ZombieBuddy loads mods through its own class loader; the transformer records it when Real Limbs' classes load
    private static volatile ClassLoader loader;
    private static volatile MethodHandle ours, hasBody;
    private static volatile boolean resolveFailed;

    private RealLimbsCompat() {}

    /** Called by the agent's transformer for every class in the reallimbs package. */
    static void noteLoader(ClassLoader l) {
        if (loader == null && l != null) {
            loader = l;
            PZOLogger.info("[RealLimbsCompat] Project Real Limbs detected: zombies with a physics body stay on the game thread");
        }
    }

    public static boolean isPresent() {
        return loader != null;
    }

    /**
     * True while Real Limbs owns this zombie's body (physics, arms, pending NPC or multiplayer ghost). Game thread only.
     * ours() reads a set Real Limbs rebuilds at the end of each frame, so a body taken this frame (the killing blow:
     * the corpse pose comes from it) is only in it from the next frame; hasBody() reads its live body map.
     */
    public static boolean isBody(Object zombie) {
        MethodHandle h = ours;
        if (h == null) {
            if (loader == null || resolveFailed) return false;
            h = resolve();
            if (h == null) return false;
        }
        try {
            return (boolean) h.invokeExact(zombie) || (boolean) hasBody.invokeExact(zombie);
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized MethodHandle resolve() {
        if (ours != null || resolveFailed) return ours;
        try {
            Class<?> hooks = Class.forName("reallimbs.Hooks", true, loader);
            MethodType type = MethodType.methodType(boolean.class, zombie.characters.IsoZombie.class);
            MethodType erased = MethodType.methodType(boolean.class, Object.class);
            hasBody = MethodHandles.publicLookup().findStatic(hooks, "hasBody", type).asType(erased);
            ours = MethodHandles.publicLookup().findStatic(hooks, "ours", type).asType(erased);
        } catch (Throwable t) {
            resolveFailed = true;
            PZOLogger.warn("[RealLimbsCompat] Real Limbs found but Hooks.ours is not callable, bodies are not protected: " + t);
        }
        return ours;
    }
}
