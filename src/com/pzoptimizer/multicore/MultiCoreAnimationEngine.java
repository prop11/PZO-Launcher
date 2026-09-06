package com.pzoptimizer.multicore;

import com.pzoptimizer.ModelSkinningGovernor;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.PZONative;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Quaternion;
import org.lwjgl.util.vector.Vector3f;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PZO Multi-Core Skeletal Animation Engine (Pillar 3).
 * 
 * Solves Build 42 skeletal skinning CPU bottlenecks without static scratch race conditions:
 * 
 * 1. Off-screen bone matrix multiplication elimination:
 *    Hooks MultiCoreHordeGovernor AABB culling mask to skip 100% of CPU skeletal matrix computations
 *    for non-visible entities (saving 64 bone transforms per entity per frame).
 * 2. Distant entity (Tier 2, 32-50 tiles away) LOD downsampling:
 *    Reduces animation evaluation from 60 FPS to 15 FPS (4x computation reduction) with cached matrices.
 * 3. Multi-threaded ModelInstance.UpdateDir() pre-staging across CPU cores, protected by ModelInstance.lock.
 * 4. Thread-local transform scratchpads for thread safety without static variable collision.
 */
public final class MultiCoreAnimationEngine {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // Telemetry metrics
    public static final AtomicLong totalParallelAnimationUpdates = new AtomicLong(0);
    public static final AtomicLong totalBoneTransformsBypassed = new AtomicLong(0);

    // Thread-local math scratchpads ensuring zero collision with AnimationPlayer$L_updateBoneAnimationTransform
    public static final ThreadLocal<Matrix4f> TL_MATRIX = ThreadLocal.withInitial(Matrix4f::new);
    public static final ThreadLocal<Quaternion> TL_QUAT = ThreadLocal.withInitial(Quaternion::new);
    public static final ThreadLocal<Vector3f> TL_VEC3 = ThreadLocal.withInitial(Vector3f::new);

    public static synchronized void initialize() {
        if (initialized.get()) return;

        initialized.set(true);
        PZOLogger.success("[MultiCoreAnimationEngine] Armed: Thread-Safe Multi-Core Skeletal Animation Pipeline");
    }

    /**
     * Determines whether full skeletal skinning matrix transformation should occur for a given entity.
     */
    public static boolean shouldSkinModel(float screenX, float screenY, int entityIndex) {
        // 1. Check if HordeGovernor marked this entity as outside camera frustum
        if (entityIndex >= 0 && MultiCoreHordeGovernor.isEntityCulled(entityIndex)) {
            totalBoneTransformsBypassed.addAndGet(64);
            return false;
        }

        // 2. Fall back to screen viewport bounds check
        if (!ModelSkinningGovernor.shouldSkinModel(screenX, screenY)) {
            totalBoneTransformsBypassed.addAndGet(64);
            return false;
        }

        return true;
    }

    /**
     * Determines whether bone transforms should be evaluated this frame for distant entities (LOD downsampling).
     */
    public static boolean shouldUpdateLOD(int entityIndex, long frameCounter) {
        if (entityIndex < 0) return true;

        byte tier = MultiCoreHordeGovernor.getEntityTier(entityIndex);
        if (tier >= 2) {
            // Tier 2 (32-50 tiles): Evaluate only every 4th frame (15 FPS), reusing bone matrices
            return (frameCounter & 3) == 0;
        }

        return true;
    }

    /**
     * Parallelizes ModelSlot direction and track pre-staging across worker pool.
     */
    public static void stageParallelModelSlots(List<ModelManager.ModelSlot> slots, float delta) {
        if (slots == null || slots.isEmpty() || PZOMultiCoreEngine.getExecutor() == null) return;

        int size = slots.size();
        if (size <= 16) {
            // Small count: process sequentially
            for (int i = 0; i < size; i++) {
                updateSlotDirect(slots.get(i), delta);
            }
            return;
        }

        int workers = PZOMultiCoreEngine.getWorkerCount();
        int batchSize = (size + workers - 1) / workers;
        List<CompletableFuture<Void>> futures = new ArrayList<>(workers);

        for (int w = 0; w < workers; w++) {
            final int start = w * batchSize;
            final int end = Math.min(size, start + batchSize);
            if (start >= end) break;

            futures.add(CompletableFuture.runAsync(() -> {
                PZONative.bindCallingThreadToPCores();
                for (int i = start; i < end; i++) {
                    updateSlotDirect(slots.get(i), delta);
                }
            }, PZOMultiCoreEngine.getExecutor()));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        totalParallelAnimationUpdates.addAndGet(size);
    }

    private static void updateSlotDirect(ModelManager.ModelSlot slot, float delta) {
        if (slot == null || slot.model == null || slot.character == null || slot.remove) return;

        ModelInstance model = slot.model;
        synchronized (model.lock) {
            try {
                model.UpdateDir();
                model.Update(delta);
            } catch (Throwable ignored) {}
        }
    }

    public static long getBoneTransformsBypassed() {
        return totalBoneTransformsBypassed.get();
    }

    public static long getParallelAnimationUpdates() {
        return totalParallelAnimationUpdates.get();
    }
}
