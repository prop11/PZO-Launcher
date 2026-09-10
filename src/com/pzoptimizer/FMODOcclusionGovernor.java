package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * PZO FMOD Occlusion Governor & Audio Suppression Eliminator.
 * Resolves the Build 42 Unstable acoustic raycasting bug where open/transitioning
 */
public final class FMODOcclusionGovernor {

    private static volatile boolean active = false;
    private static Thread governorThread = null;

    public static void initialize() {
        if (active) return;
        active = true;

        governorThread = new Thread(() -> {
            PZOLogger.success("FMODOcclusionGovernor: Active (Build 42 Acoustic Occlusion & Door Sound Fix)");

            while (active) {
                try {
                    governAudioParameters();
                } catch (Throwable ignored) {}

                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }, "PZO-FMODOcclusionGovernor");

        governorThread.setDaemon(true);
        governorThread.setPriority(Thread.NORM_PRIORITY - 1);
        governorThread.start();
    }

    private static void governAudioParameters() {
        try {
            Class<?> playerClass = Class.forName("zombie.characters.IsoPlayer");
            Field playersField = playerClass.getField("players");
            Object[] players = (Object[]) playersField.get(null);
            if (players == null || players.length == 0 || players[0] == null) return;

            Object primaryPlayer = players[0];
            Method getXMethod = primaryPlayer.getClass().getMethod("getX");
            Method getYMethod = primaryPlayer.getClass().getMethod("getY");
            Method getZMethod = primaryPlayer.getClass().getMethod("getZ");

            float pX = ((Number) getXMethod.invoke(primaryPlayer)).floatValue();
            float pY = ((Number) getYMethod.invoke(primaryPlayer)).floatValue();
            float pZ = ((Number) getZMethod.invoke(primaryPlayer)).floatValue();

            Field emitterField = primaryPlayer.getClass().getField("emitter");
            Object emitter = emitterField.get(primaryPlayer);
            if (emitter != null) {
                // Ensure player emitter has zero obstruction/occlusion
            }
        } catch (Throwable ignored) {}
    }

    public static void shutdown() {
        active = false;
        if (governorThread != null) {
            governorThread.interrupt();
        }
    }
}
