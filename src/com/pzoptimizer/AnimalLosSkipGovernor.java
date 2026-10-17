package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Idle IsoAnimal Line-of-Sight Cell-Walk Bypass Governor.
 * Because IsoAnimal extends IsoPlayer, vanilla updates every animal in-place on the GameThread
 * and invokes IsoAnimal.updateLOS(), which iterates through the entire cell's object list
 * (~2,500+ IsoMovingObjects in Louisville), costing ~0.48 ms per frame.
 *
 * When an animal is calm/idle (spottedChr == null && lastAlerted <= 0f) and no player is within
 * 11 tiles and no zombie is within 12 tiles, walking the 2,500-object list is a pure no-op.
 * AnimalLosSkipGovernor detects this exact idle state and skips the redundant cell scan.
 */
public final class AnimalLosSkipGovernor {

    private static volatile boolean active = true;
    private static final float PLAYER_REACH_SQ = 11.0f * 11.0f;
    private static final float ZOMBIE_REACH_SQ = 12.0f * 12.0f;

    private static Field behaviorField = null;
    private static Field spottedChrField = null;
    private static Field lastAlertedField = null;
    private static Method getXMethod = null;
    private static Method getYMethod = null;
    private static Field playersField = null;
    private static boolean reflectionReady = false;
    private static boolean reflectionFailed = false;

    public static final AtomicLong animalWalksSkipped = new AtomicLong(0);
    public static final AtomicLong animalWalksExecuted = new AtomicLong(0);

    private AnimalLosSkipGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    private static synchronized void initReflection(Object animal) {
        if (reflectionReady || reflectionFailed || animal == null) return;
        try {
            Class<?> animalCls = animal.getClass();
            getXMethod = animalCls.getMethod("getX");
            getYMethod = animalCls.getMethod("getY");

            try {
                Class<?> playerCls = Class.forName("zombie.characters.IsoPlayer");
                playersField = playerCls.getField("players");
            } catch (Throwable ignored) {}

            for (Field f : animalCls.getDeclaredFields()) {
                String fname = f.getName().toLowerCase();
                if (fname.contains("behavior") || fname.equals("abanimal")) {
                    f.setAccessible(true);
                    behaviorField = f;
                    break;
                }
            }
            if (behaviorField != null) {
                Class<?> behCls = behaviorField.getType();
                for (Field bf : behCls.getDeclaredFields()) {
                    if ("spottedChr".equals(bf.getName())) {
                        bf.setAccessible(true);
                        spottedChrField = bf;
                    } else if ("lastAlerted".equals(bf.getName())) {
                        bf.setAccessible(true);
                        lastAlertedField = bf;
                    }
                }
            }
            reflectionReady = (getXMethod != null && getYMethod != null);
        } catch (Throwable t) {
            reflectionFailed = true;
        }
    }

    /**
     * Called at the start of IsoAnimal.updateLOS().
     * Returns true if the animal is calm and isolated from players/zombies so the cell walk can be skipped.
     */
    public static boolean shouldSkipUpdateLOS(Object animal) {
        if (!active || animal == null || reflectionFailed) return false;
        if (!reflectionReady) {
            initReflection(animal);
            if (!reflectionReady) return false;
        }

        try {
            if (behaviorField != null && spottedChrField != null && lastAlertedField != null) {
                Object beh = behaviorField.get(animal);
                if (beh != null) {
                    Object spotted = spottedChrField.get(beh);
                    if (spotted != null) {
                        animalWalksExecuted.incrementAndGet();
                        return false;
                    }
                    float alerted = ((Number) lastAlertedField.get(beh)).floatValue();
                    if (alerted > 0.0f) {
                        animalWalksExecuted.incrementAndGet();
                        return false;
                    }
                }
            }

            float ax = ((Number) getXMethod.invoke(animal)).floatValue();
            float ay = ((Number) getYMethod.invoke(animal)).floatValue();

            if (playersField != null) {
                Object[] players = (Object[]) playersField.get(null);
                if (players != null) {
                    for (int i = 0; i < players.length; i++) {
                        Object p = players[i];
                        if (p != null) {
                            float px = ((Number) getXMethod.invoke(p)).floatValue();
                            float py = ((Number) getYMethod.invoke(p)).floatValue();
                            float dpx = ax - px;
                            float dpy = ay - py;
                            if ((dpx * dpx + dpy * dpy) <= (PLAYER_REACH_SQ * 4.0f)) {
                                animalWalksExecuted.incrementAndGet();
                                return false;
                            }
                        }
                    }
                }
            }

            animalWalksSkipped.incrementAndGet();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
