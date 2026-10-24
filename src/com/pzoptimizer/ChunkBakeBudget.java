package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Spreads chunk-texture re-bakes over frames.
 *
 * Each chunk level group is cached in a texture and re-baked when it is marked dirty. Stock re-bakes every dirty
 * on-screen texture in the frame it became dirty, and blood and corpse changes can mark many groups dirty at once.
 * A re-bake for blood or corpse changes only starts while fewer than 4 bakes started this frame; none waits more
 * than 3 frames, and the previous texture keeps being drawn meanwhile.
 *
 * Lighting re-bakes are never held. A lightning flash, dusk, dawn and fog change the light a little every frame on
 * every square, and holding some groups back showed neighbouring chunks at different brightness (the screen
 * shimmered for the length of each flash).
 *
 * A first bake (no texture yet) and any re-bake for an object, item, cutaway, obscuring, creation, redraw or tree
 * change is never held. Besides the texture, a bake rebuilds the chunk level's lists of squares drawn every frame
 * (trees, translucent objects), so holding a redraw (chunk or neighbour finished loading) or tree change (stencil
 * area, tree fade) left trees missing or stale for the length of the hold. A held group stays dirty; for the call that would start its
 * bake, its dirty bit is hidden so the manager draws the existing texture instead of re-baking.
 */
public final class ChunkBakeBudget {

    private static volatile boolean active = true;
    public static final AtomicLong bakesHeld = new AtomicLong();

    private static final int COSMETIC_BUDGET = 4, COSMETIC_MAX_HOLD = 3;
    // Only reasons that change pixels and nothing else may wait
    private static final long COSMETIC = FBORenderChunk.DIRTY_BLOOD | FBORenderChunk.DIRTY_CORPSE;

    // Main thread only
    private static final WeakHashMap<Object, int[]> holds = new WeakHashMap<>(); // zoom info -> {frames held, last frame}
    private static final IdentityHashMap<Object, Boolean> heldThisFrame = new IdentityHashMap<>();
    private static int frame = -1, bakesThisFrame;

    // The handles below load game classes, so they live in a holder: the agent calls this class's patch methods
    // while one of those classes is being defined, and loading it again from there is a duplicate definition
    private static final class H {
        private static final MethodHandle GET_NLEVELS, GET_ZOOM_INFO;
        private static final VarHandle ZI_DIRTY, ZI_FLAGS, ZI_CHUNK;
        static {
            MethodHandle nl = null, zi = null;
            VarHandle d = null, f = null, c = null;
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                Class<?> nLevels = Class.forName("zombie.iso.fboRenderChunk.FBORenderLevels$NLevels");
                Class<?> zoomInfo = Class.forName("zombie.iso.fboRenderChunk.FBORenderLevels$ZoomInfo");
                nl = MethodHandles.privateLookupIn(FBORenderLevels.class, l).findVirtual(FBORenderLevels.class, "getNLevels",
                    MethodType.methodType(nLevels, int.class)).asType(MethodType.methodType(Object.class, FBORenderLevels.class, int.class));
                zi = MethodHandles.privateLookupIn(nLevels, l).findVirtual(nLevels, "getZoomInfo",
                    MethodType.methodType(zoomInfo, float.class)).asType(MethodType.methodType(Object.class, Object.class, float.class));
                MethodHandles.Lookup z = MethodHandles.privateLookupIn(zoomInfo, l);
                d = z.findVarHandle(zoomInfo, "dirty", boolean.class);
                f = z.findVarHandle(zoomInfo, "dirtyFlags", long.class);
                c = z.findVarHandle(zoomInfo, "renderChunk", FBORenderChunk.class);
            } catch (Throwable t) {
                PZOLogger.warn("[ChunkBakeBudget] render levels not as expected, disabled: " + t);
                active = false;
            }
            GET_NLEVELS = nl;
            GET_ZOOM_INFO = zi;
            ZI_DIRTY = d;
            ZI_FLAGS = f;
            ZI_CHUNK = c;
        }
    }

    private ChunkBakeBudget() {}

    public static void setActive(boolean value) {
        active = value && H.ZI_DIRTY != null;
    }

    /** Replaces FBORenderChunkManager.beginRenderChunkLevel in FBORenderCell.renderOneLevel. */
    public static boolean begin(FBORenderChunkManager mgr, IsoChunk chunk, int level, float zoom, boolean canRender, boolean canClear)
            throws Throwable {
        if (!active) return mgr.beginRenderChunkLevel(chunk, level, zoom, canRender, canClear);
        FBORenderLevels renderLevels = chunk.getRenderLevels(IsoCamera.frameState.playerIndex);
        Object zi = (Object) H.GET_ZOOM_INFO.invokeExact((Object) H.GET_NLEVELS.invokeExact(renderLevels, level), zoom);
        if (!(boolean) H.ZI_DIRTY.get(zi)) return mgr.beginRenderChunkLevel(chunk, level, zoom, canRender, canClear);

        int now = IsoWorld.instance.getFrameNo();
        if (now != frame) {
            frame = now;
            bakesThisFrame = 0;
            heldThisFrame.clear();
        }
        // Later levels of a group share its texture and its decision
        if (level != renderLevels.getMinLevel(level)) {
            return heldThisFrame.containsKey(zi) ? beginHidden(mgr, chunk, level, zoom, canRender, canClear, zi)
                : mgr.beginRenderChunkLevel(chunk, level, zoom, canRender, canClear);
        }

        long flags = (long) H.ZI_FLAGS.get(zi);
        boolean hasTexture = H.ZI_CHUNK.get(zi) != null;
        boolean cosmetic = (flags & ~COSMETIC) == 0;
        if (hasTexture && cosmetic) {
            int[] h = holds.get(zi);
            int held = h == null ? 0 : h[0];
            if (bakesThisFrame >= COSMETIC_BUDGET && held < COSMETIC_MAX_HOLD) {
                if (h == null) holds.put(zi, h = new int[2]);
                if (h[1] != now) {
                    h[0]++;
                    h[1] = now;
                }
                heldThisFrame.put(zi, Boolean.TRUE);
                bakesHeld.incrementAndGet();
                return beginHidden(mgr, chunk, level, zoom, canRender, canClear, zi);
            }
        }
        holds.remove(zi);
        boolean baking = mgr.beginRenderChunkLevel(chunk, level, zoom, canRender, canClear);
        if (baking) bakesThisFrame++;
        return baking;
    }

    // The group stays dirty; only this call sees it clean, so the existing texture is drawn
    private static boolean beginHidden(FBORenderChunkManager mgr, IsoChunk chunk, int level, float zoom, boolean canRender,
                                       boolean canClear, Object zi) {
        H.ZI_DIRTY.set(zi, false);
        try {
            return mgr.beginRenderChunkLevel(chunk, level, zoom, canRender, canClear);
        } finally {
            H.ZI_DIRTY.set(zi, true);
        }
    }

    static boolean patchRenderCell(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, mn -> "renderOneLevel".equals(mn.name));
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL && "beginRenderChunkLevel".equals(mi.name)
                    && "zombie/iso/fboRenderChunk/FBORenderChunkManager".equals(mi.owner)
                    && "(Lzombie/iso/IsoChunk;IFZZ)Z".equals(mi.desc)) {
                MethodInsnNode repl = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ChunkBakeBudget", "begin",
                    "(Lzombie/iso/fboRenderChunk/FBORenderChunkManager;Lzombie/iso/IsoChunk;IFZZ)Z", false);
                m.instructions.set(mi, repl);
                insn = repl;
                n++;
            }
        }
        return n == 1;
    }
}
