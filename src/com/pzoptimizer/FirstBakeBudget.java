package com.pzoptimizer;

import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Spreads first bakes of distant chunks over a few frames (JVM_FirstBakeBudget, experimental).
 *
 * When a new row of chunks comes into view (driving, a wide zoom, loading in) stock bakes every one of their textures in
 * the same frame, which is a large share of the slow frames on a drive. ChunkBakeBudget can't hold these: there is no
 * old texture to show, and letting the level go through renderOneLevel without baking would queue whatever recycled
 * texture getOrCreateFBOForLevel hands out. So a deferred first bake skips renderOneLevel for that level altogether, and
 * the chunk simply isn't drawn yet, as if it had loaded a moment later.
 *
 * At most 8 first bakes start per frame. Chunks within 24 tiles of the camera are never deferred, nor is anything in a
 * frame where the occlusion counts are rebuilt (skipping then would leave the level a stale count), and no level waits
 * more than 3 frames. Every level of a group shares the group's decision.
 *
 * The patch is in FirstBakeBudgetAsm.
 */
public final class FirstBakeBudget {

    public static volatile boolean active = false;
    public static final AtomicLong deferred = new AtomicLong();

    private static final int BUDGET = 8, MAX_HOLD = 3;
    private static final float NEAR_TILES = 24.0f;

    // Main thread only
    private static final WeakHashMap<Object, int[]> held = new WeakHashMap<>(); // zoom info -> {frames held, last frame}
    private static final IdentityHashMap<Object, Boolean> deferredThisFrame = new IdentityHashMap<>();
    private static int frame = -1, startedThisFrame;

    private FirstBakeBudget() {}

    /** Start of FBORenderCell.renderOneLevel(chunk, level, ..., playerIndex, ...): true skips the level this frame. */
    public static boolean defer(Object chunkObj, int level, int playerIndex) {
        if (!active) return false;
        try {
            IsoChunk chunk = (IsoChunk) chunkObj;
            FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
            if (!renderLevels.isOnScreen(level)) return false;
            float zoom = zombie.core.Core.getInstance().getZoom(playerIndex);
            Object zi = ChunkBakeBudget.zoomInfo(chunk, playerIndex, level, zoom);
            int now = zombie.iso.IsoWorld.instance.getFrameNo();
            if (now != frame) {
                frame = now;
                startedThisFrame = 0;
                deferredThisFrame.clear();
            }
            // Later levels of a group follow the group
            if (level != renderLevels.getMinLevel(level)) return deferredThisFrame.containsKey(zi);
            if (!ChunkBakeBudget.isFirstBakePending(zi)) return false;
            if (RenderCellFields.occlusionChanged(RenderCellFields.perPlayer(playerIndex))) {
                startedThisFrame++;
                return false;
            }
            float cx = chunk.wx * 8 + 4.0f - zombie.iso.IsoCamera.frameState.camCharacterX;
            float cy = chunk.wy * 8 + 4.0f - zombie.iso.IsoCamera.frameState.camCharacterY;
            boolean near = cx * cx + cy * cy <= NEAR_TILES * NEAR_TILES;
            int[] h = held.get(zi);
            if (!near && startedThisFrame >= BUDGET && (h == null || h[0] < MAX_HOLD)) {
                if (h == null) held.put(zi, h = new int[2]);
                if (h[1] != now) {
                    h[0]++;
                    h[1] = now;
                }
                deferredThisFrame.put(zi, Boolean.TRUE);
                deferred.incrementAndGet();
                return true;
            }
            held.remove(zi);
            startedThisFrame++;
            return false;
        } catch (Throwable t) {
            active = false;
            PZOLogger.warn("[FirstBakeBudget] Stood down, first bakes as in the stock game: " + t);
            return false;
        }
    }
}
