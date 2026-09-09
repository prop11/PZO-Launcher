package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class HordeAnimationLODGovernor {

    private static volatile boolean active = false;
    private static Thread lodThread = null;

    public static final AtomicLong boneTransformsSaved = new AtomicLong(0);
    public static final AtomicLong activeModelsTracked = new AtomicLong(0);

    private static Field modelSlotsField = null;
    private static Field chrField = null;
    private static Field modelField = null;
    private static Field animPlayerField = null;
    private static Field updateBonesField = null;
    private static Field doBlendingField = null;
    private static Class<?> zombieClass = null;
    private static Class<?> playerClass = null;
    private static Object modelManagerInst = null;
    private static boolean reflectionResolved = false;
    private static boolean reflectionNoticeLogged = false;

    public static void initialize() {
        if (active) return;
        resolveReflection();

        active = true;
        lodThread = new Thread(HordeAnimationLODGovernor::governorLoop, "PZO-HordeAnimationLODGovernor");
        lodThread.setDaemon(true);
        lodThread.setPriority(Thread.MIN_PRIORITY);
        lodThread.start();

        PZOLogger.success("HordeAnimationLODGovernor: Active (Dynamic Multi-Tier Zombie Skeletal LOD Governor)");
    }

    private static void resolveReflection() {
        if (reflectionResolved) return;
        try {
            Class<?> mmClass = Class.forName("zombie.core.skinnedmodel.ModelManager");
            Field instField = mmClass.getField("instance");
            modelManagerInst = instField.get(null);

            modelSlotsField = mmClass.getDeclaredField("modelSlots");
            modelSlotsField.setAccessible(true);

            Class<?> slotClass = Class.forName("zombie.core.skinnedmodel.ModelManager$ModelSlot");
            chrField = slotClass.getField("character");
            modelField = slotClass.getField("model");

            Class<?> modelInstClass = Class.forName("zombie.core.skinnedmodel.model.ModelInstance");
            animPlayerField = modelInstClass.getField("animPlayer");

            Class<?> animPlayerClass = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer");
            updateBonesField = animPlayerClass.getField("updateBones");
            try {
                doBlendingField = animPlayerClass.getField("doBlending");
            } catch (Throwable ignored) {}

            zombieClass = Class.forName("zombie.characters.IsoZombie");
            playerClass = Class.forName("zombie.characters.IsoPlayer");

            reflectionResolved = true;
        } catch (Throwable t) {
            if (!reflectionNoticeLogged) {
                PZOLogger.warn("HordeAnimationLODGovernor reflection notice: " + t.getMessage());
                reflectionNoticeLogged = true;
            }
        }
    }

    private static void governorLoop() {
        while (active) {
            try {
                // Skip the skeletal LOD sweep while driving.
                if (VehicleTravelOptimizer.isPlayerDriving()) {
                    Thread.sleep(500);
                    continue;
                }
                processZombieLOD();
            } catch (Throwable ignored) {}

            try {
                Thread.sleep(60); // Paced ~16 Hz check
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private static void processZombieLOD() {
        if (!reflectionResolved || modelManagerInst == null) {
            resolveReflection();
            if (!reflectionResolved || modelManagerInst == null) return;
        }

        try {
            @SuppressWarnings("unchecked")
            ArrayList<Object> slots = (ArrayList<Object>) modelSlotsField.get(modelManagerInst);
            if (slots == null || slots.isEmpty()) {
                activeModelsTracked.set(0);
                return;
            }

            int count = slots.size();
            activeModelsTracked.set(count);

            for (int i = 0; i < count; i++) {
                if (i >= slots.size()) break;
                Object slot = slots.get(i);
                if (slot == null) continue;

                Object chr = chrField.get(slot);
                if (chr == null) continue;

                Object model = modelField.get(slot);
                if (model == null) continue;

                Object animPlayer = animPlayerField.get(model);
                if (animPlayer == null) continue;

                // Keep bone updates enabled: disabling them caused bind poses and incorrect ground placement.
                updateBonesField.setBoolean(animPlayer, true);

                // Leave doBlending to the main thread. Forcing it off can synchronously bake uncached clips
                // through ModelTransformSampler in determineCurrentSharedSkeleTrack().
            }
        } catch (Throwable ignored) {}
    }

    public static long getBoneTransformsSaved() {
        return boneTransformsSaved.get();
    }

    public static long getActiveModelsTracked() {
        return activeModelsTracked.get();
    }

    public static void shutdown() {
        active = false;
        if (lodThread != null) {
            lodThread.interrupt();
        }
    }
}
