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
 * on-screen texture in the frame it became dirty: a lightning flash marks every chunk lighting-dirty at once (a
 * 50-90 ms frame per flash), and blood and corpse changes do the same on a smaller scale. Here a re-bake that is
 * only cosmetic may wait briefly while the previous texture keeps being drawn:
 *
 *  - lighting (DIRTY_LIGHTING, possibly with other cosmetic bits): only during a lightning flash and for
 *    LIGHTNING_TAIL_MS after it. Then the groups that waited longest bake first, then the ones nearest the camera,
 *    and the budget grows with the backlog so nothing waits more than LIGHTING_MAX_WAIT_MS. Any other time every
 *    lighting re-bake starts at once, as in stock: at dusk, dawn and in fog the light changes a little every frame
 *    on every square, and holding some groups back showed neighbouring chunks at different brightness, flickering
 *    even while standing still.
 *  - blood, corpse changes: start only while fewer than 4 bakes started this frame, none waits more than 3 frames.
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

    private static final int LIGHTING_MIN_PER_FRAME = 8;
    private static final long LIGHTING_MAX_WAIT_MS = 33;
    private static final int COSMETIC_BUDGET = 4, COSMETIC_MAX_HOLD = 3;
    // Only reasons that change pixels and nothing else may wait
    private static final long COSMETIC = FBORenderChunk.DIRTY_BLOOD | FBORenderChunk.DIRTY_CORPSE | FBORenderChunk.DIRTY_LIGHTING;

    // Main thread only
    private static final WeakHashMap<Object, int[]> holds = new WeakHashMap<>(); // zoom info -> {frames held, last frame}
    private static final IdentityHashMap<Object, Boolean> heldThisFrame = new IdentityHashMap<>();
    private static int frame = -1, bakesThisFrame, lightingBakesThisFrame;

    // Lighting groups waiting for a bake, keyed by zoom info. Main thread only
    private static final class Waiting {
        long sinceNanos;
        int lastSeenFrame;
        int distSq;
        boolean planned;
    }
    private static final WeakHashMap<Object, Waiting> waiting = new WeakHashMap<>();
    private static final java.util.ArrayList<Waiting> planScratch = new java.util.ArrayList<>();
    private static long frameStartNanos, lastFrameNanos = 16_000_000L;
    private static int newLightingAllowance;

    private static final long LIGHTNING_TAIL_MS = 300;
    private static boolean lightningWindow;
    private static long lastFlashNanos = Long.MIN_VALUE / 2;

    // ThunderStorm's per-player flash state (private there); loaded on first use, after the class rewrites
    private static final class Storm {
        static final VarHandle INFOS, STATE;
        static {
            VarHandle infos = null, state = null;
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                Class<?> storm = Class.forName("zombie.iso.weather.ThunderStorm");
                Class<?> info = Class.forName("zombie.iso.weather.ThunderStorm$PlayerLightningInfo");
                Class<?> stateType = Class.forName("zombie.iso.weather.ThunderStorm$LightningState");
                infos = MethodHandles.privateLookupIn(storm, l).findVarHandle(storm, "lightningInfos", info.arrayType());
                state = MethodHandles.privateLookupIn(info, l).findVarHandle(info, "lightningState", stateType);
            } catch (Throwable t) {
                PZOLogger.warn("[ChunkBakeBudget] lightning state not readable, lighting re-bakes are never held: " + t);
            }
            INFOS = infos;
            STATE = state;
        }
    }

    // Whether any player's lightning flash is being applied
    private static boolean flashing() {
        try {
            if (Storm.STATE == null) return false;
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            Object storm = cm == null ? null : cm.getThunderStorm();
            if (storm == null) return false;
            Object[] infos = (Object[]) Storm.INFOS.get(storm);
            if (infos == null) return false;
            for (Object info : infos) {
                if (info != null && ((Enum<?>) Storm.STATE.get(info)).ordinal() != 0) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

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
        if (now != frame) startFrame(now);
        // Later levels of a group share its texture and its decision
        if (level != renderLevels.getMinLevel(level)) {
            return heldThisFrame.containsKey(zi) ? beginHidden(mgr, chunk, level, zoom, canRender, canClear, zi)
                : mgr.beginRenderChunkLevel(chunk, level, zoom, canRender, canClear);
        }

        long flags = (long) H.ZI_FLAGS.get(zi);
        boolean hasTexture = H.ZI_CHUNK.get(zi) != null;
        boolean cosmetic = (flags & ~COSMETIC) == 0;
        boolean lighting = cosmetic && (flags & FBORenderChunk.DIRTY_LIGHTING) != 0;
        if (hasTexture && lighting) {
            if (lightningWindow && holdLighting(zi, chunk)) {
                heldThisFrame.put(zi, Boolean.TRUE);
                bakesHeld.incrementAndGet();
                return beginHidden(mgr, chunk, level, zoom, canRender, canClear, zi);
            }
        } else if (hasTexture && cosmetic) {
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
        waiting.remove(zi);
        boolean baking = mgr.beginRenderChunkLevel(chunk, level, zoom, canRender, canClear);
        if (baking) {
            bakesThisFrame++;
            if (lighting) lightingBakesThisFrame++;
        }
        return baking;
    }

    // Plans this frame's lighting bakes from what was still waiting at the end of the previous one
    private static void startFrame(int now) {
        long t = System.nanoTime();
        if (frameStartNanos != 0) lastFrameNanos = Math.max(1_000_000L, t - frameStartNanos);
        frameStartNanos = t;
        int previous = frame;
        frame = now;
        bakesThisFrame = lightingBakesThisFrame = 0;
        heldThisFrame.clear();
        if (flashing()) lastFlashNanos = t;
        lightningWindow = t - lastFlashNanos <= LIGHTNING_TAIL_MS * 1_000_000L;

        planScratch.clear();
        for (java.util.Iterator<Waiting> it = waiting.values().iterator(); it.hasNext(); ) {
            Waiting w = it.next();
            w.planned = false;
            if (w.lastSeenFrame != previous) it.remove(); // off screen or no longer dirty
            else planScratch.add(w);
        }
        long maxWait = LIGHTING_MAX_WAIT_MS * 1_000_000L;
        int framesLeft = (int) Math.max(1L, maxWait / lastFrameNanos);
        int budget = Math.max(LIGHTING_MIN_PER_FRAME, (planScratch.size() + framesLeft - 1) / framesLeft);
        if (planScratch.size() > budget) {
            planScratch.sort((a, b) -> a.sinceNanos != b.sinceNanos ? Long.compare(a.sinceNanos, b.sinceNanos)
                : Integer.compare(a.distSq, b.distSq));
        }
        int planned = Math.min(budget, planScratch.size());
        for (int i = 0; i < planned; i++) planScratch.get(i).planned = true;
        newLightingAllowance = budget - planned;
        planScratch.clear();
    }

    private static boolean holdLighting(Object zi, IsoChunk chunk) {
        Waiting w = waiting.get(zi);
        long t = System.nanoTime();
        if (w == null) {
            if (newLightingAllowance > 0) {
                newLightingAllowance--;
                return false;
            }
            w = new Waiting();
            w.sinceNanos = t;
            waiting.put(zi, w);
        } else if (w.planned || t - w.sinceNanos >= LIGHTING_MAX_WAIT_MS * 1_000_000L) {
            return false;
        }
        w.lastSeenFrame = frame;
        int cx = (int) Math.floor(IsoCamera.frameState.camCharacterX / 8.0f);
        int cy = (int) Math.floor(IsoCamera.frameState.camCharacterY / 8.0f);
        int dx = chunk.wx - cx, dy = chunk.wy - cy;
        w.distSq = dx * dx + dy * dy;
        return true;
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
