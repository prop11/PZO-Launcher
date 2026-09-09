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

public final class MultiCoreAnimationEngine {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    public static final AtomicLong totalParallelAnimationUpdates = new AtomicLong(0);
    public static final AtomicLong totalBoneTransformsBypassed = new AtomicLong(0);

    // Per-thread scratch storage avoids sharing transform temporaries.
    public static final ThreadLocal<Matrix4f> TL_MATRIX = ThreadLocal.withInitial(Matrix4f::new);
    public static final ThreadLocal<Quaternion> TL_QUAT = ThreadLocal.withInitial(Quaternion::new);
    public static final ThreadLocal<Vector3f> TL_VEC3 = ThreadLocal.withInitial(Vector3f::new);

    public static synchronized void initialize() {
        if (initialized.get()) return;

        initialized.set(true);
        PZOLogger.success("[MultiCoreAnimationEngine] Armed: Thread-Safe Multi-Core Skeletal Animation Pipeline");
    }

    public static boolean shouldSkinModel(float screenX, float screenY, int entityIndex) {
        if (entityIndex >= 0 && MultiCoreHordeGovernor.isEntityCulled(entityIndex)) {
            totalBoneTransformsBypassed.addAndGet(64);
            return false;
        }

        if (!ModelSkinningGovernor.shouldSkinModel(screenX, screenY)) {
            totalBoneTransformsBypassed.addAndGet(64);
            return false;
        }

        return true;
    }

    public static boolean shouldUpdateLOD(int entityIndex, long frameCounter) {
        if (entityIndex < 0) return true;

        byte tier = MultiCoreHordeGovernor.getEntityTier(entityIndex);
        if (tier >= 2) {
            // Evaluate tier 2 every fourth frame, reusing bone matrices in between.
            return (frameCounter & 3) == 0;
        }

        return true;
    }

    public static void stageParallelModelSlots(List<ModelManager.ModelSlot> slots, float delta) {
        if (slots == null || slots.isEmpty() || PZOMultiCoreEngine.getExecutor() == null) return;

        int size = slots.size();
        if (size <= 16) {
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

    private static final AtomicLong frameCounter = new AtomicLong(0);

    public static void applyHordeAnimationGovernor(List<?> zombies, int count, byte[] cullMask, byte[] tiers) {
        if (zombies == null || count <= 0 || cullMask == null) return;

        long frame = frameCounter.incrementAndGet();
        int workers = PZOMultiCoreEngine.getWorkerCount();
        int batchSize = (count + workers - 1) / workers;
        int numBatches = (count + batchSize - 1) / batchSize;

        if (numBatches <= 1 || PZOMultiCoreEngine.getExecutor() == null) {
            processHordeBatch(zombies, 0, count, cullMask, tiers, frame);
        } else {
            List<CompletableFuture<Void>> futures = new ArrayList<>(numBatches);
            for (int b = 0; b < numBatches; b++) {
                final int start = b * batchSize;
                final int end = Math.min(count, start + batchSize);
                if (start >= end) break;
                futures.add(CompletableFuture.runAsync(() -> {
                    processHordeBatch(zombies, start, end, cullMask, tiers, frame);
                }, PZOMultiCoreEngine.getExecutor()));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    private static void processHordeBatch(List<?> zombies, int start, int end, byte[] cullMask, byte[] tiers, long frame) {
        long bypassed = 0;
        for (int i = start; i < end; i++) {
            if (i >= zombies.size()) break;
            Object obj = zombies.get(i);
            if (!(obj instanceof zombie.characters.IsoGameCharacter)) continue;
            zombie.characters.IsoGameCharacter character = (zombie.characters.IsoGameCharacter) obj;

            zombie.core.skinnedmodel.animation.AnimationPlayer animPlayer = character.getAnimationPlayer();
            if (animPlayer == null) continue;

            boolean culled = (i < cullMask.length && cullMask[i] == 0);
            if (culled) {
                animPlayer.updateBones = false;
                bypassed += 64;
            } else {
                byte tier = (tiers != null && i < tiers.length) ? tiers[i] : 0;
                if (tier >= 3) {
                    boolean updateThisFrame = ((frame + i) & 3) == 0;
                    animPlayer.updateBones = updateThisFrame;
                    if (!updateThisFrame) bypassed += 64;
                } else if (tier == 2) {
                    boolean updateThisFrame = ((frame + i) % 3) == 0;
                    animPlayer.updateBones = updateThisFrame;
                    if (!updateThisFrame) bypassed += 64;
                } else if (tier == 1) {
                    boolean updateThisFrame = ((frame + i) & 1) == 0;
                    animPlayer.updateBones = updateThisFrame;
                    if (!updateThisFrame) bypassed += 64;
                } else {
                    animPlayer.updateBones = true;
                }
            }
        }
        if (bypassed > 0) {
            totalBoneTransformsBypassed.addAndGet(bypassed);
        }
    }

    public static long getBoneTransformsBypassed() {
        return totalBoneTransformsBypassed.get();
    }

    public static long getParallelAnimationUpdates() {
        return totalParallelAnimationUpdates.get();
    }
}

