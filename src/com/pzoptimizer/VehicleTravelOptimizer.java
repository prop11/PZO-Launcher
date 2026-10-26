package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.locks.ReentrantLock;
import sun.misc.Unsafe;

/** Vehicle travel stutter fixes: a non-fair IsoChunkMap.bSettingChunk lock and distance-based simulation throttling for town zombies. */
public final class VehicleTravelOptimizer {

    private static volatile boolean initialized = false;
    private static volatile boolean unfairLockInstalled = false;
    private static volatile boolean simulationGovernorInstalled = false;
    // Set when this game build lacks the fields we hook; checkAndMaintain runs every second otherwise.
    private static volatile boolean unfairLockUnavailable = false;
    private static volatile boolean simulationGovernorUnavailable = false;
    private static volatile boolean unfairLockWarned = false;
    private static volatile boolean simulationGovernorWarned = false;

    private static volatile Unsafe unsafeInstance = null;
    private static volatile long lastDrivingCheckTime = 0;
    private static volatile boolean playerIsDriving = false;
    private static volatile float playerX = 0.0f;
    private static volatile float playerY = 0.0f;

    public static final java.util.concurrent.atomic.AtomicLong throttledTownZombies = new java.util.concurrent.atomic.AtomicLong(0);

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        obtainUnsafe();
        installUnfairChunkLock();
        installSimulationGovernor();
    }

    public static void checkAndMaintain() {
        if (!unfairLockInstalled && !unfairLockUnavailable) {
            installUnfairChunkLock();
        }
        if (!simulationGovernorInstalled && !simulationGovernorUnavailable) {
            installSimulationGovernor();
        }
    }

    private static void obtainUnsafe() {
        if (unsafeInstance != null) return;
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            unsafeInstance = (Unsafe) theUnsafeField.get(null);
        } catch (Throwable t) {
            PZOLogger.warn("[VehicleTravelOptimizer] Unsafe unavailable: " + t.getMessage());
        }
    }

    /** Replaces the IsoChunkMap.bSettingChunk fair lock with a non-fair one. */
    public static synchronized boolean installUnfairChunkLock() {
        if (unfairLockInstalled) return true;
        if (unfairLockUnavailable) return false;
        obtainUnsafe();
        if (unsafeInstance == null) return false;

        try {
            Class<?> chunkMapClass = Class.forName("zombie.iso.IsoChunkMap");
            Field lockField = chunkMapClass.getField("bSettingChunk");
            ReentrantLock currentLock = (ReentrantLock) lockField.get(null);

            if (currentLock != null && !currentLock.isFair()) {
                unfairLockInstalled = true;
                return true;
            }

            Object base = unsafeInstance.staticFieldBase(lockField);
            long offset = unsafeInstance.staticFieldOffset(lockField);
            ReentrantLock nonFairLock = new ReentrantLock(false);
            unsafeInstance.putObject(base, offset, nonFairLock);

            ReentrantLock updatedLock = (ReentrantLock) lockField.get(null);
            if (updatedLock != null && !updatedLock.isFair()) {
                unfairLockInstalled = true;
                PZOLogger.success("[VehicleTravelOptimizer] IsoChunkMap Fair-Lock replaced with atomic Non-Fair Lock (Contention hitch eliminated)");
                return true;
            }
        } catch (NoSuchFieldException | ClassCastException e) {
            unfairLockUnavailable = true;
            PZOLogger.warn("[VehicleTravelOptimizer] Unfair lock disabled: incompatible game build (" + e + ")");
        } catch (Throwable t) {
            if (!unfairLockWarned) {
                unfairLockWarned = true;
                PZOLogger.warn("[VehicleTravelOptimizer] Unfair lock replacement notice (retrying quietly): " + t);
            }
        }
        return false;
    }


    private static volatile Class<?> cachedPlayerClass = null;
    private static volatile Method cachedGetInstMethod = null;
    private static volatile Method cachedGetVehicleMethod = null;
    private static volatile boolean playerReflectionResolved = false;

    public static boolean isPlayerDriving() {
        long now = System.currentTimeMillis();
        if (now - lastDrivingCheckTime < 200L) {
            return playerIsDriving;
        }
        lastDrivingCheckTime = now;
        if (!playerReflectionResolved) {
            try {
                cachedPlayerClass = Class.forName("zombie.characters.IsoPlayer");
                cachedGetInstMethod = cachedPlayerClass.getMethod("getInstance");
                cachedGetVehicleMethod = cachedPlayerClass.getMethod("getVehicle");
                playerReflectionResolved = true;
            } catch (Throwable ignored) {
                playerReflectionResolved = true;
            }
        }
        if (cachedGetInstMethod != null && cachedGetVehicleMethod != null) {
            try {
                Object player = cachedGetInstMethod.invoke(null);
                if (player != null) {
                    playerIsDriving = (cachedGetVehicleMethod.invoke(player) != null);
                    if (playerIsDriving) {
                        playerX = getObjectX(player);
                        playerY = getObjectY(player);
                    }
                    return playerIsDriving;
                }
            } catch (Throwable ignored) {}
        }
        playerIsDriving = false;
        return false;
    }

    public static float getPlayerX() { return playerX; }
    public static float getPlayerY() { return playerY; }

    private static volatile Field movingObjFieldX = null;
    private static volatile Field movingObjFieldY = null;
    private static volatile Method movingObjMethodGetX = null;
    private static volatile Method movingObjMethodGetY = null;
    private static volatile boolean coordsResolved = false;

    public static float getObjectX(Object obj) {
        if (obj == null) return 0.0f;
        if (!coordsResolved) {
            resolveCoords();
        }
        try {
            if (movingObjFieldX != null) return movingObjFieldX.getFloat(obj);
            if (movingObjMethodGetX != null) return ((Number) movingObjMethodGetX.invoke(obj)).floatValue();
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    public static float getObjectY(Object obj) {
        if (obj == null) return 0.0f;
        if (!coordsResolved) {
            resolveCoords();
        }
        try {
            if (movingObjFieldY != null) return movingObjFieldY.getFloat(obj);
            if (movingObjMethodGetY != null) return ((Number) movingObjMethodGetY.invoke(obj)).floatValue();
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private static synchronized void resolveCoords() {
        if (coordsResolved) return;
        try {
            Class<?> movingObjClass = Class.forName("zombie.iso.IsoMovingObject");
            try {
                movingObjFieldX = movingObjClass.getDeclaredField("x");
                movingObjFieldX.setAccessible(true);
                movingObjFieldY = movingObjClass.getDeclaredField("y");
                movingObjFieldY.setAccessible(true);
            } catch (Throwable t) {
                movingObjMethodGetX = movingObjClass.getMethod("getX");
                movingObjMethodGetY = movingObjClass.getMethod("getY");
            }
        } catch (Throwable ignored) {}
        coordsResolved = true;
    }

    private static volatile Class<?> cachedZombieClass = null;
    private static volatile boolean zombieClassResolved = false;

    public static boolean isZombie(Object obj) {
        if (!zombieClassResolved) {
            try {
                cachedZombieClass = Class.forName("zombie.characters.IsoZombie");
            } catch (Throwable ignored) {}
            zombieClassResolved = true;
        }
        return cachedZombieClass != null && cachedZombieClass.isInstance(obj);
    }

    private static volatile Field fieldId = null;
    private static volatile Method methodGetID = null;
    private static volatile boolean idResolved = false;

    public static int getObjectId(Object obj) {
        if (!idResolved) {
            try {
                Class<?> movingObjClass = Class.forName("zombie.iso.IsoMovingObject");
                try {
                    fieldId = movingObjClass.getDeclaredField("id");
                    fieldId.setAccessible(true);
                } catch (Throwable t1) {
                    try {
                        fieldId = movingObjClass.getDeclaredField("ID");
                        fieldId.setAccessible(true);
                    } catch (Throwable t2) {
                        methodGetID = movingObjClass.getMethod("getID");
                    }
                }
            } catch (Throwable ignored) {}
            idResolved = true;
        }
        try {
            if (fieldId != null) return fieldId.getInt(obj);
            if (methodGetID != null) return ((Number) methodGetID.invoke(obj)).intValue();
        } catch (Throwable ignored) {}
        return System.identityHashCode(obj);
    }

    /**
     * Hooks MovingObjectUpdateScheduler's FULL bucket, where engine noise dumps 500+ alerted town zombies while
     * driving, and moves zombies more than 20 tiles away to the QUARTER (15 FPS) and EIGHTH (7.5 FPS) buckets.
     */
    public static synchronized boolean installSimulationGovernor() {
        if (simulationGovernorInstalled) return true;
        if (simulationGovernorUnavailable) return false;

        try {
            Class<?> schedulerClass = Class.forName("zombie.MovingObjectUpdateScheduler");
            Field instField = schedulerClass.getField("instance");
            Object scheduler = instField.get(null);
            if (scheduler == null) return false;

            Field simLevelsField = schedulerClass.getDeclaredField("simulationLevels");
            simLevelsField.setAccessible(true);
            Object[] simLevels = (Object[]) simLevelsField.get(scheduler);
            if (simLevels == null || simLevels.length < 5) return false;

            Field bucketsField = simLevels[0].getClass().getDeclaredField("buckets");
            bucketsField.setAccessible(true);

            @SuppressWarnings("unchecked")
            java.util.List<Object>[] fullBuckets = (java.util.List<Object>[]) bucketsField.get(simLevels[0]);
            if (fullBuckets == null || fullBuckets.length == 0) return false;

            if (fullBuckets[0] instanceof GovernedSimulationList) {
                simulationGovernorInstalled = true;
                return true;
            }

            @SuppressWarnings("unchecked")
            java.util.List<Object>[] quarterBuckets = (java.util.List<Object>[]) bucketsField.get(simLevels[2]);
            @SuppressWarnings("unchecked")
            java.util.List<Object>[] eighthBuckets = (java.util.List<Object>[]) bucketsField.get(simLevels[3]);

            GovernedSimulationList governed = new GovernedSimulationList(fullBuckets[0], quarterBuckets, eighthBuckets);
            fullBuckets[0] = governed;

            @SuppressWarnings("unchecked")
            java.util.List<Object>[] halfBuckets = (java.util.List<Object>[]) bucketsField.get(simLevels[1]);
            if (halfBuckets != null) {
                for (int i = 0; i < halfBuckets.length; i++) {
                    if (!(halfBuckets[i] instanceof GovernedSimulationList)) {
                        halfBuckets[i] = new GovernedSimulationList(halfBuckets[i], quarterBuckets, eighthBuckets);
                    }
                }
            }

            simulationGovernorInstalled = true;
            PZOLogger.success("[VehicleTravelOptimizer] Town Zombie Simulation Governor armed (75%+ AI simulation load drop while driving)");
            return true;
        } catch (NoSuchFieldException | ClassCastException e) {
            simulationGovernorUnavailable = true;
            PZOLogger.warn("[VehicleTravelOptimizer] Simulation governor disabled: incompatible game build (" + e + ")");
        } catch (Throwable t) {
            if (!simulationGovernorWarned) {
                simulationGovernorWarned = true;
                PZOLogger.warn("[VehicleTravelOptimizer] Simulation governor install notice (retrying quietly): " + t);
            }
        }
        return false;
    }

    /**
     * Specialized ArrayList that intercepts MovingObjectUpdateScheduler FULL bucket distribution.
     */
    public static final class GovernedSimulationList extends java.util.ArrayList<Object> {
        private static final long serialVersionUID = 4243L;

        private final java.util.List<Object>[] quarterBuckets;
        private final java.util.List<Object>[] eighthBuckets;

        public GovernedSimulationList(java.util.List<Object> existing, java.util.List<Object>[] quarterBuckets, java.util.List<Object>[] eighthBuckets) {
            super();
            if (existing != null && !existing.isEmpty()) {
                this.addAll(existing);
            }
            this.quarterBuckets = quarterBuckets;
            this.eighthBuckets = eighthBuckets;
        }

        @Override
        public boolean add(Object obj) {
            if (obj == null) return false;

            if (playerIsDriving && isZombie(obj)) {
                float px = playerX;
                float py = playerY;
                float zx = getObjectX(obj);
                float zy = getObjectY(obj);
                float dx = zx - px;
                float dy = zy - py;
                float distSq = dx * dx + dy * dy;

                if (distSq <= 400.0f) {
                    return super.add(obj);
                }

                int id = getObjectId(obj);

                if (distSq <= 2500.0f && quarterBuckets != null && quarterBuckets.length > 0) {
                    int slot = (id & 0x7FFFFFFF) % quarterBuckets.length;
                    quarterBuckets[slot].add(obj);
                    throttledTownZombies.incrementAndGet();
                    return true;
                }

                if (eighthBuckets != null && eighthBuckets.length > 0) {
                    int slot = (id & 0x7FFFFFFF) % eighthBuckets.length;
                    eighthBuckets[slot].add(obj);
                    throttledTownZombies.incrementAndGet();
                    return true;
                }
            }

            return super.add(obj);
        }
    }
}
