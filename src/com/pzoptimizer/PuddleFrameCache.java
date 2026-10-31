package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoPuddlesGeometry;
import zombie.iso.PlayerCamera;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;

/**
 * Medium and high puddle quality: settings screen "Cache puddle squares per chunk" (JVM_PuddleFrameCache).
 *
 * Every frame FBORenderCell.renderPuddles walks the puddle squares of every on-screen chunk level, tests each one
 * (cut-away, on screen, floor, floor layer, puddle shape, twice), reads its four corner lights and works out the depth
 * of its four corners, then packs 32 floats per square for the puddle shader. In rain at a wide zoom that is tens of
 * thousands of squares a frame.
 *
 * This replaces that walk with one that keeps, per chunk level:
 *  - the squares that pass the tests that only change with a bake or a cut-away change (level, cut-away), rebuilt
 *    when the level is baked again, its cut-away flags change or the puddle setting changes;
 *  - the corner lights, re-read when the level is baked again or is waiting for a lighting re-bake (every corner light
 *    change in the FBO renderer marks its level dirty for lighting first);
 *  - the corner depths, recomputed when the player's square or the camera's model jiggle correction changes (in
 *    practice: kept while the camera stands still).
 * Being on screen, the floor and its layer and the puddle shape are still tested every frame, in the stock order, and the
 * shape is read fresh, so the packed data is what the stock walk packs, square for square and float for float. The
 * translucent-floor and chunk-texture puddle passes are left to the game.
 */
public final class PuddleFrameCache {

    public static volatile boolean active = true;
    public static final AtomicLong squaresDrawn = new AtomicLong();
    public static final AtomicLong levelsRebuilt = new AtomicLong();
    public static final AtomicLong lightRefreshes = new AtomicLong();
    public static final AtomicLong depthRefreshes = new AtomicLong();

    private static final long DIRTY_LIGHTING = zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_LIGHTING;
    // Corner order of IsoPuddlesGeometry.updateLighting
    private static final int[] LIGHT_CORNER = {0, 3, 2, 1};

    private static final class Level {
        int bakeStamp = -1, epoch, srcSize, n;
        final byte[] flags = new byte[64];
        IsoGridSquare[] squares = new IsoGridSquare[16];
        float[] light = new float[64];
        float[] depth = new float[64];
        boolean lightValid, depthValid;
        int depthCamX, depthCamY, depthJigX, depthJigY;
    }

    private static final class Levels {
        int bakeStamp;
        final Level[] byZ = new Level[64];
    }

    // Main thread only: bakes and the puddle pass run on it
    private static final WeakHashMap<FBORenderLevels, Levels> states = new WeakHashMap<>();
    private static volatile boolean broken;
    private static VarHandle renderDataField, numSquaresField, capacityField, dataField, perLevelField;
    private static VarHandle geoX, geoY, geoNe, geoNw, geoAll, geoNone;

    private PuddleFrameCache() {}

    /** Start of FBORenderLevels.clearCachedSquares(level): the level group is being baked again. */
    public static void baked(FBORenderLevels renderLevels, int level) {
        Levels s = states.get(renderLevels);
        if (s != null) s.bakeStamp++;
    }

    private static void bind() throws ReflectiveOperationException {
        if (dataField != null) return;
        Class<?> rd = Class.forName("zombie.iso.IsoPuddles$RenderData", false, IsoPuddles.class.getClassLoader());
        MethodHandles.Lookup puddles = MethodHandles.privateLookupIn(IsoPuddles.class, MethodHandles.lookup());
        MethodHandles.Lookup data = MethodHandles.privateLookupIn(rd, MethodHandles.lookup());
        MethodHandles.Lookup geo = MethodHandles.privateLookupIn(IsoPuddlesGeometry.class, MethodHandles.lookup());
        renderDataField = puddles.findVarHandle(IsoPuddles.class, "renderData", rd.arrayType().arrayType());
        numSquaresField = data.findVarHandle(rd, "numSquares", int.class);
        capacityField = data.findVarHandle(rd, "capacity", int.class);
        perLevelField = data.findVarHandle(rd, "squaresPerLevel", int[].class);
        geoX = geo.findVarHandle(IsoPuddlesGeometry.class, "x", float[].class);
        geoY = geo.findVarHandle(IsoPuddlesGeometry.class, "y", float[].class);
        geoNe = geo.findVarHandle(IsoPuddlesGeometry.class, "pdne", float[].class);
        geoNw = geo.findVarHandle(IsoPuddlesGeometry.class, "pdnw", float[].class);
        geoAll = geo.findVarHandle(IsoPuddlesGeometry.class, "pda", float[].class);
        geoNone = geo.findVarHandle(IsoPuddlesGeometry.class, "pnon", float[].class);
        dataField = data.findVarHandle(rd, "data", float[].class);
    }

    private static int epoch() {
        int e = zombie.core.Core.getInstance().getPerfPuddles();
        e = e * 31 + zombie.core.PerformanceSettings.puddlesQuality;
        return e * 31 + (zombie.debug.DebugOptions.instance.fboRenderChunk.nolighting.getValue() ? 1 : 0);
    }

    /** Start of FBORenderCell.renderPuddles(playerIndex); true skips the stock walk. */
    public static boolean render(zombie.iso.fboRenderChunk.FBORenderCell cell, int playerIndex) {
        if (!active || broken) return false;
        // Paths this does not reproduce stay stock: puddles switched off in debug, chunk-texture packing
        if (!zombie.debug.DebugOptions.instance.weather.waterPuddles.getValue()
                || zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.isCaching()) {
            return false;
        }
        IsoPuddles puddles = IsoPuddles.getInstance();
        if (!puddles.shouldRenderPuddles()) return true;
        if (zombie.iso.fboRenderChunk.FBORenderSnow.getInstance().isSnowAnywhere()) return true;
        ArrayList<IsoChunk> onScreen;
        Object threadData;
        int framePlayer = IsoCamera.frameState.playerIndex;
        try {
            bind();
            onScreen = RenderCellFields.onScreenChunks(RenderCellFields.perPlayer(playerIndex));
            threadData = ((Object[][]) renderDataField.get(puddles))[zombie.core.SpriteRenderer.instance.getMainStateIndex()][framePlayer];
        } catch (Throwable t) {
            broken = true;
            PZOLogger.warn("[PuddleFrameCache] Stood down, puddles drawn by the stock pass: " + t);
            return false;
        }
        try {
            int maxZ = zombie.iso.IsoWorld.instance.currentCell.chunkMap[playerIndex].maxHeight;
            if (zombie.core.Core.getInstance().getPerfPuddles() > 0) maxZ = 0;
            int epoch = epoch();
            for (int z = 0; z <= maxZ; z++) {
                int first = (int) numSquaresField.get(threadData);
                for (int i = 0; i < onScreen.size(); i++) {
                    IsoChunk chunk = onScreen.get(i);
                    if (z < chunk.minLevel || z > chunk.maxLevel) continue;
                    FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                    if (!renderLevels.isOnScreen(z)) continue;
                    List<IsoGridSquare> squares = renderLevels.getCachedSquares_Puddles(z);
                    if (squares.isEmpty()) continue;
                    emitLevel(chunk, renderLevels, z, squares, playerIndex, framePlayer, epoch, threadData);
                }
                int added = (int) numSquaresField.get(threadData) - first;
                if (added > 0) zombie.core.SpriteRenderer.instance.drawPuddles(framePlayer, z, first, added);
            }
            return true;
        } catch (Throwable t) {
            // Puddles may already be queued this frame, so the stock pass is skipped once rather than drawing them twice
            broken = true;
            PZOLogger.warn("[PuddleFrameCache] Stood down from the next frame, puddles drawn by the stock pass: " + t);
            return true;
        }
    }

    private static void emitLevel(IsoChunk chunk, FBORenderLevels renderLevels, int z, List<IsoGridSquare> src, int playerIndex,
                                  int framePlayer, int epoch, Object threadData) {
        Levels s = states.get(renderLevels);
        if (s == null) states.put(renderLevels, s = new Levels());
        Level lv = s.byZ[z + 32];
        if (lv == null) s.byZ[z + 32] = lv = new Level();
        zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);
        byte[] flags = levelData.squareFlags[playerIndex];
        if (lv.bakeStamp != s.bakeStamp || lv.epoch != epoch || lv.srcSize != src.size() || !Arrays.equals(lv.flags, flags)) {
            rebuild(lv, s.bakeStamp, epoch, src, flags, levelData, playerIndex, z);
        }
        if (renderLevels.isDirty(z, DIRTY_LIGHTING, zombie.core.Core.getInstance().getZoom(playerIndex))) lv.lightValid = false;
        if (!lv.lightValid) {
            boolean noLighting = zombie.debug.DebugOptions.instance.fboRenderChunk.nolighting.getValue();
            for (int k = 0; k < lv.n; k++) {
                IsoGridSquare sq = lv.squares[k];
                for (int c = 0; c < 4; c++) {
                    lv.light[k * 4 + c] = Float.intBitsToFloat(noLighting ? -1 : sq.getVertLight(LIGHT_CORNER[c], framePlayer));
                }
            }
            lv.lightValid = true;
            lightRefreshes.incrementAndGet();
        }
        PlayerCamera camera = IsoCamera.cameras[framePlayer];
        int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX), camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
        int jigX = Float.floatToRawIntBits(camera.fixJigglyModelsSquareX), jigY = Float.floatToRawIntBits(camera.fixJigglyModelsSquareY);
        if (!lv.depthValid || lv.depthCamX != camX || lv.depthCamY != camY || lv.depthJigX != jigX || lv.depthJigY != jigY) {
            for (int k = 0; k < lv.n; k++) {
                IsoGridSquare sq = lv.squares[k];
                for (int c = 0; c < 4; c++) {
                    float dx = (c == 2 || c == 3) ? 1.0F : 0.0F;
                    float dy = (c == 1 || c == 2) ? 1.0F : 0.0F;
                    dx += camera.fixJigglyModelsSquareX;
                    dy += camera.fixJigglyModelsSquareY;
                    lv.depth[k * 4 + c] = zombie.iso.IsoDepthHelper.getSquareDepthData(camX, camY, sq.x + dx, sq.y + dy, sq.z).depthStart - 1.0E-4F;
                }
            }
            lv.depthValid = true;
            lv.depthCamX = camX;
            lv.depthCamY = camY;
            lv.depthJigX = jigX;
            lv.depthJigY = jigY;
            depthRefreshes.incrementAndGet();
        }

        float jx = camera.fixJigglyModelsX * camera.zoom;
        float jy = camera.fixJigglyModelsY * camera.zoom;
        int num = (int) numSquaresField.get(threadData);
        int cap = (int) capacityField.get(threadData);
        float[] data = (float[]) dataField.get(threadData);
        if (data == null) data = new float[cap * 4 * 8];
        int drawn = 0;
        for (int k = 0; k < lv.n; k++) {
            IsoGridSquare sq = lv.squares[k];
            // Stock order: on screen, floor and its layer, puddle shape
            if (!sq.IsOnScreen()) continue;
            IsoObject floor = sq.getFloor();
            if (floor == null || floor.getRenderInfo(playerIndex).layer == ObjectRenderLayer.TranslucentFloor) continue;
            IsoPuddlesGeometry pg = sq.getPuddles();
            if (pg == null || !pg.shouldRender()) continue;
            if (num + 1 > cap) {
                cap += 128;
                data = Arrays.copyOf(data, cap * 4 * 8);
            }
            float[] x = (float[]) geoX.get(pg), y = (float[]) geoY.get(pg);
            float[] ne = (float[]) geoNe.get(pg), nw = (float[]) geoNw.get(pg), all = (float[]) geoAll.get(pg), none = (float[]) geoNone.get(pg);
            int o = num * 4 * 8;
            for (int c = 0; c < 4; c++) {
                data[o++] = ne[c];
                data[o++] = nw[c];
                data[o++] = all[c];
                data[o++] = none[c];
                data[o++] = x[c] + jx;
                data[o++] = y[c] + jy;
                data[o++] = lv.light[k * 4 + c];
                data[o++] = lv.depth[k * 4 + c];
            }
            num++;
            drawn++;
        }
        if (drawn > 0) {
            ((int[]) perLevelField.get(threadData))[z + 32] += drawn;
            squaresDrawn.addAndGet(drawn);
        }
        dataField.set(threadData, data);
        capacityField.set(threadData, cap);
        numSquaresField.set(threadData, num);
    }

    // The tests the stock walk makes that change only with a bake or a cut-away change
    private static void rebuild(Level lv, int bakeStamp, int epoch, List<IsoGridSquare> src, byte[] flags,
                                zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelData levelData, int playerIndex, int z) {
        int n = 0;
        for (int j = 0; j < src.size(); j++) {
            IsoGridSquare sq = src.get(j);
            if (sq.getZ() != z || !levelData.shouldRenderSquare(playerIndex, sq)) continue;
            if (n == lv.squares.length) lv.squares = Arrays.copyOf(lv.squares, n * 2);
            lv.squares[n++] = sq;
        }
        Arrays.fill(lv.squares, n, lv.squares.length, null);
        if (lv.light.length < n * 4) {
            lv.light = new float[n * 8];
            lv.depth = new float[n * 8];
        }
        lv.n = n;
        lv.bakeStamp = bakeStamp;
        lv.epoch = epoch;
        lv.srcSize = src.size();
        System.arraycopy(flags, 0, lv.flags, 0, 64);
        lv.lightValid = false;
        lv.depthValid = false;
        levelsRebuilt.incrementAndGet();
    }

    static boolean patchRenderCell(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderPuddles", "(I)V");
        if (m == null) return false;
        LabelNode go = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/PuddleFrameCache", "render",
            "(Lzombie/iso/fboRenderChunk/FBORenderCell;I)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, go));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(go);
        m.instructions.insert(pre);
        return true;
    }

    static boolean patchRenderLevels(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "clearCachedSquares", "(I)V");
        if (m == null) return false;
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/PuddleFrameCache", "baked",
            "(Lzombie/iso/fboRenderChunk/FBORenderLevels;I)V", false));
        m.instructions.insert(pre);
        return true;
    }
}
