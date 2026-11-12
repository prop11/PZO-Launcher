package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Per-pixel lighting and wind sway on the chunk composite, for shader patches that ask for them (PZO Enhancements'
 * Per-Pixel Lighting and Swaying Plants). Nothing here does anything until the chunk shader declares PZOLit / PZOWind.
 *
 * Lighting. Vanilla bakes light into every chunk texture (each sprite tinted by its square's corner lights) and
 * re-bakes a level whenever any of its lights change: dawn, dusk, flickering lamps, a moving torch. B42 already has
 * the other half of a deferred path behind its NoLighting debug option: the bake draws everything unlit and LightingJNI
 * stops re-baking on light changes. I turn that option on only while a chunk is being baked (main thread: the chunk
 * manager is caching; render thread: between the chunk FBO's begin and end), and keep the lights in a small texture
 * instead: per square its own 4 floor and 4 ceiling corners (so light doesn't leak through walls, same as vanilla),
 * two texels a square so hardware filtering gives the in-square blend, a floor and a ceiling slice per level tiled
 * into one 2D atlas, wrapping with the chunk map. The composite rebuilds each pixel's world position from its depth (the tile depth maps are an
 * orthographic projection at the engine's own depth scale) and multiplies the light in. A light change now costs one
 * 16x16 column upload instead of re-baking chunk levels.
 *
 * Sway. With wind sprites on, vanilla draws every bush and tall grass every frame so it can skew it. With sway on,
 * FoliageLod bakes those too (trees keep vanilla's path) and the composite shifts their pixels with the wind,
 * anchored at the base. The square's "has wind foliage" flag rides in the light texture's alpha.
 */
public final class PixelLight {

    static final int BASE_LEVEL = -8, LEVELS = 24;
    // The light texture is 2D: 2 * LEVELS slices tiled 8 across, 6 down. A sampler3D can't be used: the game runs
    // glValidateProgram right after linking, with every sampler still on unit 0, and a 3D sampler sharing unit 0 with
    // the 2D chunk texture fails it, so the game drops the chunk shader for the rest of the session.
    static final int TILE_COLS = 8, TILE_ROWS = (2 * LEVELS + TILE_COLS - 1) / TILE_COLS;
    static final int UPLOADS_PER_FRAME = 24;
    static final long DIRTY_LIGHTING = 32L, DIRTY_REDRAW = 1024L;

    public static final AtomicLong columnsUploaded = new AtomicLong();
    public static final AtomicLong lightChangesKept = new AtomicLong();

    // Seen by the render thread on the chunk shader
    static volatile boolean lightShaderReady, swayShaderReady, atmosShaderReady, windowsWanted;
    // Decided on the main thread; atmos = ground fog / light glow, which only reads the light map
    static volatile boolean deferred, sway, atmos;
    static volatile float fogIntensity, rainIntensity, dayLight, cloudIntensity, snowCover, cloudOffX, cloudOffY;
    static volatile Thread mainThread;
    // Render thread: inside the FBO of a chunk that's being baked unlit
    static boolean renderBakingUnlit;

    // Main thread writes per frame, render thread reads
    static volatile float windX = 1f, windY = 0f, windStrength, clock;
    private static long lastNanos;
    private static int lastFrame = Integer.MIN_VALUE;

    // FBORenderChunk -> deferred mode when its bake was recorded (main), depth texture -> baked unlit (render)
    private static final ConcurrentHashMap<FBORenderChunk, Boolean> modeAtBake = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, Boolean> unlitByDepth = new ConcurrentHashMap<>();
    // depth texture -> {padded w, padded h, drawn w, top offset, origin x, origin y, min level, ready, ring size, player}
    private static final ConcurrentHashMap<Object, float[]> chunkInfo = new ConcurrentHashMap<>();

    private static final class PlayerState {
        int grid, ring;
        long[] slotKey;
        final Set<IsoChunk> dirty = ConcurrentHashMap.newKeySet();
        int roundRobin;
    }

    private static final PlayerState[] players = new PlayerState[4];

    // Render thread
    private static final int[] texId = new int[4], texRing = new int[4];
    private static int locProgram = -1;
    private static long locCheckedNs;
    private static int locA, locB, locC, locLit, locWind, locTex, locAtmos = -1, locFogWind = -1, locClouds = -1, locSnow = -1, locTrail = -1, locTrailInfo = -1, locWindows = -1;

    private PixelLight() {}

    // Unlit bake

    /** Replaces DebugOptions.instance.fboRenderChunk.nolighting.getValue() in the bake code. */
    public static boolean noLighting(zombie.debug.BooleanDebugOption opt) {
        if (opt.getValue()) return true;
        if (!deferred) return false;
        Thread t = Thread.currentThread();
        if (t == mainThread) return zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.isCaching();
        return renderBakingUnlit;
    }

    /** Replaces FBORenderLevels.invalidateLevel(z, flags) inside LightingJNI's per-square light update. */
    public static void lightInvalidate(FBORenderLevels renderLevels, int z, long flags) {
        if (flags == DIRTY_LIGHTING && deferred) {
            lightChangesKept.incrementAndGet();
            PlayerState ps = players[renderLevels.getPlayerIndex()];
            if (ps != null) ps.dirty.add(renderLevels.getChunk());
            return;
        }
        // Without per-pixel lighting the light map still feeds fog and window glow, so it follows light changes too
        if (flags == DIRTY_LIGHTING && atmos) {
            PlayerState ps = players[renderLevels.getPlayerIndex()];
            if (ps != null) ps.dirty.add(renderLevels.getChunk());
        }
        renderLevels.invalidateLevel(z, flags);
    }

    /**
     * Replaces Core.getOptionDoWindSpriteEffects() in IsoObject.getObjectRenderEffectsToApply(): a plant baked for the
     * composite to sway goes in upright, not frozen at whatever lean the game's wind skew had that frame.
     */
    public static boolean windSkewOption(zombie.core.Core core) {
        if (!core.getOptionDoWindSpriteEffects()) return false;
        return !(sway && Thread.currentThread() == mainThread && zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.isCaching());
    }

    /** FoliageLod asks while classifying an object's render layer: bake wind foliage so the composite can sway it. */
    public static boolean bakeWindFoliage(Object object) {
        return sway && object != null && !(object instanceof zombie.iso.objects.IsoTree);
    }

    // Main thread, once a frame per player (start of FBORenderCell.checkWindEffectsOption)

    public static void frame(int playerIndex) {
        try {
            if (mainThread == null) mainThread = Thread.currentThread();
            int f = zombie.iso.IsoCamera.frameState.frameCount;
            if (f != lastFrame) {
                lastFrame = f;
                globalFrame();
            }
            if (!(deferred || sway || atmos)) return;
            update(playerIndex);
        } catch (Throwable t) {
            PZOLogger.warn("[PixelLight] frame: " + t);
        }
    }

    private static void globalFrame() {
        long now = System.nanoTime();
        status(now);
        double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (zombie.GameTime.isGamePaused()) dt = 0;
        float c = clock + (float) dt;
        clock = c > 4096f ? c - 4096f : c;

        boolean wantDeferred = lightShaderReady && !zombie.debug.DebugOptions.instance.fboRenderChunk.combinedFbo.getValue();
        boolean wantSway = swayShaderReady && zombie.core.Core.getInstance().getOptionDoWindSpriteEffects();
        if (wantDeferred != deferred) {
            deferred = wantDeferred;
            if (zombie.core.Core.debug) PZOLogger.info("[PixelLight] per-pixel lighting " + (wantDeferred ? "on" : "off") + ", re-baking loaded chunks");
            invalidateAllChunks(DIRTY_LIGHTING | DIRTY_REDRAW);
            resetSlots();
        }
        if (wantSway != sway) {
            sway = wantSway;
            if (zombie.core.Core.debug) PZOLogger.info("[PixelLight] composite sway " + (wantSway ? "on" : "off") + ", re-baking loaded chunks");
            FoliageLod.invalidateAllLoaded();
            resetSlots();
        }
        if (BakePass.rebakeWanted) {
            BakePass.rebakeWanted = false;
            invalidateAllChunks(DIRTY_REDRAW);
        }
        if (atmosShaderReady != atmos) {
            atmos = atmosShaderReady;
            if (zombie.core.Core.debug) PZOLogger.info("[PixelLight] ground fog / light glow " + (atmos ? "on" : "off"));
        }
        if (sway || atmos) {
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            fogIntensity = cm.getFogIntensity();
            rainIntensity = cm.getRainIntensity();
            dayLight = cm.getDayLightStrength();
            cloudIntensity = cm.getCloudIntensity();
            snowCover = cm.getSnowFracNow();
            // The cloud field drifts with the wind, faster when it blows harder; kept small so float noise stays smooth
            double ca = cm.getWindAngleRadians(), speed = (0.5 + 3.0 * cm.getWindIntensity()) * 0.03 * dt;
            cloudOffX = (float) ((cloudOffX + Math.cos(ca) * speed) % 512.0);
            cloudOffY = (float) ((cloudOffY + Math.sin(ca) * speed) % 512.0);
            float a = cm.getWindAngleRadians();
            // World wind vector to screen: x - y across, (x + y) / 2 down
            float wx = (float) Math.cos(a), wy = (float) Math.sin(a);
            float sx = wx - wy, sy = (wx + wy) * 0.5f;
            float len = (float) Math.sqrt(sx * sx + sy * sy);
            if (len > 1e-4f) {
                windX = sx / len;
                windY = sy / len;
            }
            windStrength = cm.getWindIntensity();
        }
    }

    private static void resetSlots() {
        for (PlayerState ps : players) {
            if (ps == null) continue;
            if (ps.slotKey != null) java.util.Arrays.fill(ps.slotKey, Long.MIN_VALUE);
            ps.dirty.clear();
        }
    }

    private static void invalidateAllChunks(long flags) {
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return;
        for (int p = 0; p < 4; p++) {
            IsoChunkMap map = cell.getChunkMap(p);
            if (map == null) continue;
            int n = IsoChunkMap.chunkGridWidth;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    IsoChunk c = map.getChunk(x, y);
                    if (c != null) c.invalidateRenderChunkLevels(flags);
                }
            }
        }
    }

    private static long key(int wx, int wy) {
        return ((long) wx << 32) ^ (wy & 0xffffffffL);
    }

    private static void update(int p) {
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return;
        IsoChunkMap map = cell.getChunkMap(p);
        if (map == null) return;
        PlayerState ps = players[p];
        if (ps == null) players[p] = ps = new PlayerState();
        int g = IsoChunkMap.chunkGridWidth;
        if (ps.grid != g) {
            ps.grid = g;
            ps.ring = g * 8;
            ps.slotKey = new long[g * g];
            java.util.Arrays.fill(ps.slotKey, Long.MIN_VALUE);
            ps.dirty.clear();
            SpriteRenderer().drawGeneric(new CreateTexture(p, ps.ring));
        }
        // New or reloaded chunks go up at once (they're about to be drawn); light changes within a budget
        for (int y = 0; y < g; y++) {
            for (int x = 0; x < g; x++) {
                IsoChunk c = map.getChunk(x, y);
                if (c == null || c.lightingNeverDone[p]) continue;
                int slot = Math.floorMod(c.wy, g) * g + Math.floorMod(c.wx, g);
                long k = key(c.wx, c.wy);
                if (ps.slotKey[slot] != k) {
                    upload(p, ps, c);
                    ps.slotKey[slot] = k;
                    ps.dirty.remove(c);
                }
            }
        }
        int budget = UPLOADS_PER_FRAME;
        java.util.Iterator<IsoChunk> it = ps.dirty.iterator();
        while (budget > 0 && it.hasNext()) {
            IsoChunk c = it.next();
            it.remove();
            if (c.lightingNeverDone[p]) continue;
            int slot = Math.floorMod(c.wy, g) * g + Math.floorMod(c.wx, g);
            if (ps.slotKey[slot] != key(c.wx, c.wy)) continue;
            upload(p, ps, c);
            budget--;
        }
        // One column a frame refreshed anyway: catches foliage added or removed and anything a hook missed
        if (budget > 0 && g > 0) {
            int i = ps.roundRobin = (ps.roundRobin + 1) % (g * g);
            IsoChunk c = map.getChunk(i % g, i / g);
            if (c != null && !c.lightingNeverDone[p]) upload(p, ps, c);
        }
    }

    private static zombie.core.SpriteRenderer SpriteRenderer() {
        return zombie.core.SpriteRenderer.instance;
    }

    private static final int COLUMN_INTS = 16 * 16 * LEVELS * 2;
    private static final ConcurrentLinkedQueue<ByteBuffer> buffers = new ConcurrentLinkedQueue<>();
    // Main thread scratch: per level, per square, 8 corner colours (0 = not set)
    private static final int[][] scratch = new int[LEVELS][64 * 8];
    private static final boolean[][] scratchSet = new boolean[LEVELS][64];
    // Per square: FLAG_FOLIAGE (wind-swayed plant, for sway) | FLAG_OUTDOORS (for ground fog), stored in alpha
    static final int FLAG_FOLIAGE = 128, FLAG_OUTDOORS = 64;
    private static final int[][] scratchFlags = new int[LEVELS][64];

    private static void upload(int p, PlayerState ps, IsoChunk c) {
        boolean wantVeg = sway;
        for (int li = 0; li < LEVELS; li++) {
            int level = BASE_LEVEL + li;
            int[] vals = scratch[li];
            boolean[] set = scratchSet[li];
            int[] flags = scratchFlags[li];
            for (int i = 0; i < 64; i++) {
                IsoGridSquare sq = level < c.minLevel || level > c.maxLevel ? null : c.getGridSquare(i & 7, i >> 3, level);
                if (sq != null && sq.lighting[p] != null) {
                    for (int v = 0; v < 8; v++) vals[i * 8 + v] = sq.getVertLight(v, p);
                    set[i] = true;
                    flags[i] = (wantVeg && hasWindFoliage(sq) ? FLAG_FOLIAGE : 0) | (sq.isOutside() ? FLAG_OUTDOORS : 0);
                } else if (li > 0 && scratchSet[li - 1][i]) {
                    // Nothing on this level here (open air, above the roof): the ceiling light of the level below
                    int[] below = scratch[li - 1];
                    for (int v = 0; v < 4; v++) {
                        vals[i * 8 + v] = below[i * 8 + 4 + v];
                        vals[i * 8 + 4 + v] = below[i * 8 + 4 + v];
                    }
                    set[i] = true;
                    flags[i] = FLAG_OUTDOORS;
                } else {
                    set[i] = false;
                    flags[i] = 0;
                }
            }
            if (windowsWanted && level >= c.minLevel && level <= c.maxLevel) windowFlags(c, level, flags);
        }
        // Below the lowest level that has anything: the floor light of the level above
        for (int li = LEVELS - 2; li >= 0; li--) {
            for (int i = 0; i < 64; i++) {
                if (scratchSet[li][i] || !scratchSet[li + 1][i]) continue;
                int[] vals = scratch[li], above = scratch[li + 1];
                for (int v = 0; v < 4; v++) {
                    vals[i * 8 + v] = above[i * 8 + v];
                    vals[i * 8 + 4 + v] = above[i * 8 + v];
                }
                scratchSet[li][i] = true;
            }
        }
        ByteBuffer buf = buffers.poll();
        if (buf == null) buf = ByteBuffer.allocateDirect(COLUMN_INTS * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.clear();
        pack(scratch, scratchSet, scratchFlags, buf.asIntBuffer());
        buf.position(0);
        buf.limit(COLUMN_INTS * 4);
        int x0 = 2 * Math.floorMod(c.wx * 8, ps.ring), y0 = 2 * Math.floorMod(c.wy * 8, ps.ring);
        SpriteRenderer().drawGeneric(new Upload(p, ps.ring, x0, y0, buf));
        columnsUploaded.incrementAndGet();
    }

    /**
     * One chunk column as uploaded: x fastest, then y, then slice; slice 2 * level holds the floor corners, 2 * level + 1
     * the ceiling corners, each square a 2x2 block of its own corners.
     */
    static void pack(int[][] vals, boolean[][] set, int[][] flags, IntBuffer ib) {
        for (int li = 0; li < LEVELS; li++) {
            int[] v = vals[li], g = flags[li];
            boolean[] s = set[li];
            for (int half = 0; half < 2; half++) {
                int o = half * 4;
                for (int ty = 0; ty < 16; ty++) {
                    for (int tx = 0; tx < 16; tx++) {
                        int i = (ty >> 1) * 8 + (tx >> 1);
                        // Light order is (x,y), (x+1,y), (x+1,y+1), (x,y+1)
                        int corner = (ty & 1) == 0 ? ((tx & 1) == 0 ? 0 : 1) : ((tx & 1) == 0 ? 3 : 2);
                        int col = s[i] ? v[i * 8 + o + corner] : 0xFFFFFFFF;
                        // ABGR int, little endian bytes = R G B A; alpha carries the square's flags
                        ib.put((col & 0x00FFFFFF) | ((g[i] & 0xFF) << 24));
                    }
                }
            }
        }
    }

    // Lit windows: a window on the square's north / west edge with a lit room on one side only (the _IN bit when the
    // lit room is this square's side), and NEAR on squares the light pool outside one can reach, so the shader only
    // looks for windows there. Windows up to 2 squares outside the chunk count, a pool reaches 2 squares out.
    static final int FLAG_WIN_N = 32, FLAG_WIN_W = 16, FLAG_WIN_NEAR = 8, FLAG_WIN_N_IN = 4, FLAG_WIN_W_IN = 2;

    private static void windowFlags(IsoChunk c, int z, int[] flags) {
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
        int ox = c.wx * 8, oy = c.wy * 8;
        for (int y = oy - 2; y < oy + 10; y++) {
            for (int x = ox - 2; x < ox + 10; x++) {
                boolean inside = x >= ox && x < ox + 8 && y >= oy && y < oy + 8;
                IsoGridSquare sq = inside ? c.getGridSquare(x - ox, y - oy, z) : cell.getGridSquare(x, y, z);
                if (sq == null || sq.getSpecialObjects().isEmpty()) continue;
                int n = windowSide(cell, sq, true), w = windowSide(cell, sq, false);
                if (inside) {
                    int i = (y - oy) * 8 + (x - ox);
                    if (n != 0) flags[i] |= FLAG_WIN_N | (n < 0 ? FLAG_WIN_N_IN : 0);
                    if (w != 0) flags[i] |= FLAG_WIN_W | (w < 0 ? FLAG_WIN_W_IN : 0);
                }
                // Lit room across the edge: the pool falls into this square and the next two; lit on this side: the
                // two squares on the other side of the edge
                if (n > 0) for (int j = 0; j <= 2; j++) near(flags, x - ox, y + j - oy);
                if (n < 0) for (int j = 1; j <= 2; j++) near(flags, x - ox, y - j - oy);
                if (w > 0) for (int j = 0; j <= 2; j++) near(flags, x + j - ox, y - oy);
                if (w < 0) for (int j = 1; j <= 2; j++) near(flags, x - j - ox, y - oy);
            }
        }
    }

    private static void near(int[] flags, int lx, int ly) {
        if (lx < 0 || lx >= 8 || ly < 0 || ly >= 8) return;
        int i = ly * 8 + lx;
        if ((flags[i] & FLAG_OUTDOORS) != 0) flags[i] |= FLAG_WIN_NEAR;
    }

    // +1 = the lit room is across the edge, -1 = it's this square's side, 0 = no window there or no lit room on one side
    private static int windowSide(zombie.iso.IsoCell cell, IsoGridSquare sq, boolean north) {
        java.util.List<zombie.iso.IsoObject> sp = sq.getSpecialObjects();
        boolean found = false;
        for (int i = 0, n = sp.size(); i < n && !found; i++) {
            if (sp.get(i) instanceof zombie.iso.objects.IsoWindow w && w.isNorth() == north && !w.isBarricaded()) found = true;
        }
        if (!found) return 0;
        IsoGridSquare across = cell.getGridSquare(north ? sq.getX() : sq.getX() - 1, north ? sq.getY() - 1 : sq.getY(), sq.getZ());
        if (across == null) return 0;
        zombie.iso.areas.IsoRoom a = sq.getRoom(), b = across.getRoom();
        if (a == b) return 0;
        boolean litA = roomLit(a, sq), litB = roomLit(b, across);
        if (litA == litB) return 0;
        return litB ? 1 : -1;
    }

    private static boolean roomLit(zombie.iso.areas.IsoRoom r, IsoGridSquare sq) {
        return r != null && r.def != null && r.def.lightsActive && (sq.hasGridPower() || sq.haveElectricity());
    }

    private static boolean hasWindFoliage(IsoGridSquare sq) {
        java.util.List<zombie.iso.IsoObject> objects = sq.getObjects();
        for (int i = 0, n = objects.size(); i < n; i++) {
            zombie.iso.IsoObject o = objects.get(i);
            if (!(o instanceof zombie.iso.objects.IsoTree) && o.getWindRenderEffects() != null) return true;
        }
        return false;
    }

    // Render thread GL work, queued in frame order

    private static final class CreateTexture extends TextureDraw.GenericDrawer {
        final int player, ring;

        CreateTexture(int player, int ring) {
            this.player = player;
            this.ring = ring;
        }

        @Override
        public void render() {
            if (texId[player] != 0) GL11.glDeleteTextures(texId[player]);
            int id = GL11.glGenTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2 * ring * TILE_COLS, 2 * ring * TILE_ROWS, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            texId[player] = id;
            texRing[player] = ring;
        }
    }

    private static final class Upload extends TextureDraw.GenericDrawer {
        final int player, ring, x0, y0;
        final ByteBuffer data;

        Upload(int player, int ring, int x0, int y0, ByteBuffer data) {
            this.player = player;
            this.ring = ring;
            this.x0 = x0;
            this.y0 = y0;
            this.data = data;
        }

        @Override
        public void render() {
            if (texId[player] == 0 || texRing[player] != ring) return;
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId[player]);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            uploadSlices(x0, y0, ring, data);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }

        @Override
        public void postRender() {
            buffers.offer(data);
        }
    }

    /** One packed column (see pack) into its 2 * LEVELS slices of the bound atlas; slice s is tile (s % 8, s / 8). */
    static void uploadSlices(int x0, int y0, int ring, ByteBuffer data) {
        int tile = 2 * ring, sliceBytes = 16 * 16 * 4;
        for (int s = 0; s < 2 * LEVELS; s++) {
            data.limit((s + 1) * sliceBytes);
            data.position(s * sliceBytes);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (s % TILE_COLS) * tile + x0, (s / TILE_COLS) * tile + y0, 16, 16,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        }
        data.limit(data.capacity());
        data.position(0);
    }

    // Chunk FBO hooks

    /** Start of FBORenderChunk.beginMainThread(clear): the mode this bake's draws are recorded in. */
    public static void bakeRecorded(FBORenderChunk chunk, boolean clear) {
        modeAtBake.put(chunk, deferred);
    }

    /** Start of FBORenderChunk.beginRenderThread(clear). */
    public static void bakeBegin(FBORenderChunk chunk, boolean clear) {
        Boolean m = modeAtBake.get(chunk);
        boolean unlit = m != null && m;
        renderBakingUnlit = unlit;
        renderBakeClear = clear;
        // A partial redraw keeps what the texture already holds
        if (clear) unlitByDepth.put(chunk.depth, unlit);
    }

    /** Start of FBORenderChunk.endRenderThread(). */
    public static void bakeEnd(FBORenderChunk chunk) {
        renderBakingUnlit = false;
        // Bake passes (ambient occlusion, weathering) only after a full bake; partial redraws keep what's there
        if (renderBakeClear) {
            renderBakeClear = false;
            try {
                BakePass.afterBake(chunk);
            } catch (Throwable t) {
                if (drawError == null) drawError = "bake pass: " + t;
            }
        }
    }

    // Render thread: the bake in progress began with a clear
    private static boolean renderBakeClear;

    /** End of FBORenderChunk.renderInWorldMainThread(): what the composite needs to place this chunk's pixels. */
    public static void composited(FBORenderChunk rc) {
        if (!(deferred || sway || atmos) || rc.chunk == null || rc.depth == null) return;
        try {
            int p = rc.getRenderLevels().getPlayerIndex();
            PlayerState ps = players[p];
            IsoChunk c = rc.chunk;
            float s = rc.highRes ? 2f : 1f;
            int min = rc.getMinLevel(), top = rc.getTopLevel();
            float topOffset = 192f * (top - min + 1) + FBORenderLevels.extraHeightForJumboTrees(min, top);
            boolean ready = ps != null && ps.slotKey != null
                && ps.slotKey[Math.floorMod(c.wy, ps.grid) * ps.grid + Math.floorMod(c.wx, ps.grid)] == key(c.wx, c.wy);
            float[] info = chunkInfo.get(rc.depth);
            if (info == null) chunkInfo.put(rc.depth, info = new float[10]);
            // The texture's own UV extent, whatever size the GL texture really got (padded or exact)
            float xEnd = rc.tex != null && rc.tex.xEnd > 0f ? rc.tex.xEnd : 1f, yEnd = rc.tex != null && rc.tex.yEnd > 0f ? rc.tex.yEnd : 1f;
            info[0] = rc.w / xEnd / s;
            info[1] = rc.h / yEnd / s;
            info[2] = rc.w / s;
            info[3] = topOffset;
            info[4] = c.wx * 8;
            info[5] = c.wy * 8;
            info[6] = min;
            info[7] = ready ? 1f : 0f;
            info[8] = ps == null ? 0 : ps.ring;
            info[9] = p;
        } catch (Throwable ignored) {
        }
    }

    /** End of ChunkRenderShader.startRenderThread(texd), render thread, program bound. */
    public static void chunkDraw(zombie.core.opengl.Shader shader, TextureDraw texd) {
        try {
            // Without a mod's shader patch (no PZO Enhancements) there's nothing to feed
            if (!ShaderPatchHost.anyLive()) return;
            chunkDraws++;
            int prog = shader.getID();
            long nowNs = System.nanoTime();
            // Re-checked on program change and 4 times a second anyway: a rebuild can hand back the same program id,
            // and a build counter alone missed some of those (lighting and splashes then never saw their uniforms)
            if (prog != locProgram || nowNs - locCheckedNs > 250_000_000L) {
                locProgram = prog;
                locCheckedNs = nowNs;
                locA = GL20.glGetUniformLocation(prog, "PZOChunkA");
                locB = GL20.glGetUniformLocation(prog, "PZOChunkB");
                locC = GL20.glGetUniformLocation(prog, "PZOChunkC");
                locLit = GL20.glGetUniformLocation(prog, "PZOLit");
                locWind = GL20.glGetUniformLocation(prog, "PZOWind");
                locTex = GL20.glGetUniformLocation(prog, "PZOLight");
                locAtmos = GL20.glGetUniformLocation(prog, "PZOAtmos");
                locFogWind = GL20.glGetUniformLocation(prog, "PZOFogWind");
                locClouds = GL20.glGetUniformLocation(prog, "PZOClouds");
                locSnow = GL20.glGetUniformLocation(prog, "PZOSnow");
                locTrail = GL20.glGetUniformLocation(prog, "PZOTrail");
                locTrailInfo = GL20.glGetUniformLocation(prog, "PZOTrailInfo");
                locWindows = GL20.glGetUniformLocation(prog, "PZOWindows");
                TrailMap.chunkWanted = locTrail >= 0;
                windowsWanted = locWindows >= 0;
                lightShaderReady = locLit >= 0;
                swayShaderReady = locWind >= 0;
                // Fog, cloud shadows, snow and trails only read the light map (outdoors flag, light, chunk placement)
                atmosShaderReady = locAtmos >= 0 || locClouds >= 0 || locSnow >= 0 || locTrail >= 0 || locWindows >= 0;
                for (float[] s : sent) java.util.Arrays.fill(s, Float.NaN);
            }
            if (locC < 0) return;
            // Every draw, never cached: the game re-assigns sampler units when it rebuilds a program, which can keep
            // its id, and the light sampler has to stay on unit 7, away from DIFFUSE and DEPTH
            if (locTex >= 0) GL20.glUniform1i(locTex, 7);
            if (locTrail >= 0) {
                int tt = TrailMap.texture(), tr = TrailMap.ringTexels();
                GL13.glActiveTexture(GL13.GL_TEXTURE5);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tt);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL20.glUniform1i(locTrail, 5);
                u4(S_TRAIL, locTrailInfo, Math.max(1, tr), TrailMap.TEXELS_PER_SQUARE, 0f, tt != 0 && tr > 0 ? 1f : 0f);
            }
            float[] info = texd.tex1 == null ? null : chunkInfo.get(texd.tex1);
            int p = info == null ? -1 : (int) info[9];
            boolean valid = info != null && info[7] > 0.5f && p >= 0 && texId[p] != 0 && texRing[p] == (int) info[8];
            if (!valid) {
                u4(S_C, locC, 1f, 0f, 1f, 0f);
                u4(S_LIT, locLit, 0f, 0f, 0f, 0f);
                u4(S_ATMOS, locAtmos, 0f, 0f, 0f, 0f);
                return;
            }
            chunkDrawsValid++;
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId[p]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            u4(S_A, locA, info[0], info[1], info[2], info[3]);
            u4(S_B, locB, info[4], info[5], info[6], 0f);
            u4(S_C, locC, info[8], BASE_LEVEL, LEVELS, 1f);
            u4(S_LIT, locLit, deferred && Boolean.TRUE.equals(unlitByDepth.get(texd.tex1)) ? 1f : 0f, 0f, 0f, 0f);
            u4(S_WIND, locWind, windX, windY, sway ? windStrength : 0f, clock);
            u4(S_ATMOS, locAtmos, atmos ? fogIntensity : 0f, rainIntensity, dayLight, atmos ? 1f : 0f);
            u4(S_FOGWIND, locFogWind, windX, windY, windStrength, clock);
            u4(S_CLOUDS, locClouds, atmos ? cloudIntensity : 0f, dayLight, cloudOffX, cloudOffY);
            u4(S_SNOW, locSnow, atmos ? snowCover : 0f, 0f, 0f, 0f);
            // Windows glow from dusk: full below a quarter daylight, none above 0.6
            u4(S_WINDOWS, locWindows, atmos ? Math.max(0f, Math.min(1f, (0.6f - dayLight) / 0.35f)) : 0f, 0f, 0f, atmos ? 1f : 0f);
        } catch (Throwable t) {
            if (drawError == null) drawError = t.toString();
        }
    }

    // Last values sent per uniform; most stay the same from one chunk draw to the next (about 100 draws a frame)
    private static final int S_A = 0, S_B = 1, S_C = 2, S_LIT = 3, S_WIND = 4, S_ATMOS = 5, S_FOGWIND = 6, S_CLOUDS = 7, S_SNOW = 8,
        S_TRAIL = 9, S_WINDOWS = 10;
    private static final float[][] sent = new float[11][4];

    // PZOLit is a float: only x goes to the GPU for it
    private static void u4(int slot, int loc, float a, float b, float c, float d) {
        if (loc < 0) return;
        float[] s = sent[slot];
        if (s[0] == a && s[1] == b && s[2] == c && s[3] == d) return;
        s[0] = a;
        s[1] = b;
        s[2] = c;
        s[3] = d;
        if (slot == S_LIT) GL20.glUniform1f(loc, a);
        else GL20.glUniform4f(loc, a, b, c, d);
    }

    /** key=value pairs for PZOApi.getChunkEffects(), so a settings card can say what's really running. */
    static String stateText() {
        return "lighting=" + (deferred ? 1 : 0) + ";lightShader=" + (lightShaderReady ? 1 : 0) + ";sway=" + (sway ? 1 : 0)
            + ";swayShader=" + (swayShaderReady ? 1 : 0) + ";fog=" + (atmos ? 1 : 0) + ";columns=" + columnsUploaded.get()
            + ";kept=" + lightChangesKept.get() + ";draws=" + chunkDraws + ";litDraws=" + chunkDrawsValid
            + ";locLit=" + locLit + ";locWind=" + locWind + ";locC=" + locC + ";drawError=" + (drawError != null ? 1 : 0);
    }

    // The hooks the chunk effects can't work without; a stand-down on any of them (another mod patches the same method)
    private static final java.util.regex.Pattern CONFLICT_MOD = java.util.regex.Pattern.compile("([^,()]+?) \\(");
    private static final String[] CHUNK_HOOKS = {"zombie/iso/fboRenderChunk/FBORenderChunk", "zombie/viewCone/ChunkRenderShader",
        "zombie/iso/fboRenderChunk/FBORenderCell", "zombie/iso/LightingJNI$JNILighting"};

    /**
     * Which mods keep the chunk effects (lighting, sway, fog) and the footstep splashes from working, as
     * "chunk=ModA,ModB;splash=ModC", from ZombieBuddyCompat's stand-downs. Empty values when nothing stands down.
     */
    static String conflictText() {
        java.util.Set<String> chunk = new java.util.TreeSet<>(), splash = new java.util.TreeSet<>();
        for (String line : ZombieBuddyCompat.standDownLines()) {
            boolean isChunk = false;
            for (String h : CHUNK_HOOKS) if (line.startsWith("PixelLight " + h + ":")) isChunk = true;
            boolean isSplash = line.startsWith("PuddlesShader splash uniforms:") || line.startsWith("IsoPuddles chunk texture record");
            if (!isChunk && !isSplash) continue;
            // "...: also patched by ModA (Class.method), ModB (Class.method)"
            int at = line.indexOf("also patched by ");
            if (at < 0) continue;
            java.util.regex.Matcher m = CONFLICT_MOD.matcher(line.substring(at + "also patched by ".length()));
            while (m.find()) (isChunk ? chunk : splash).add(m.group(1).trim());
        }
        return "chunk=" + String.join(",", chunk) + ";splash=" + String.join(",", splash);
    }

    static String drawErrorText() {
        return drawError;
    }

    // Diagnostics, read by the main thread's status line
    static volatile long chunkDraws, chunkDrawsValid;
    static volatile String drawError;
    private static long lastStatusNanos;

    private static void status(long now) {
        if (now - lastStatusNanos < 30_000_000_000L) return;
        lastStatusNanos = now;
        // Only with the game's -debug, and only with a PZO Enhancements chunk shader patch live
        if (!zombie.core.Core.debug) return;
        PZOLogger.info("[PixelLight] chunk draws " + chunkDraws + " (" + chunkDrawsValid + " with light data), uniforms lit=" + locLit
            + " wind=" + locWind + " atmos=" + locAtmos + " chunkC=" + locC + ", lighting " + (deferred ? "on" : "off")
            + ", sway " + (sway ? "on" : "off") + ", fog/glow " + (atmos ? "on" : "off")
            + ", columns uploaded " + columnsUploaded.get() + ", light changes kept from re-baking " + lightChangesKept.get()
            + (drawError != null ? ", draw error: " + drawError : ""));
    }

    // Bytecode

    static final Set<String> TARGETS = Set.of(
        "zombie/core/skinnedmodel/shader/Shader", "zombie/tileDepth/TileSeamModifier", "zombie/tileDepth/CutawayAttachedModifier",
        "zombie/core/skinnedmodel/model/WorldItemModelDrawer", "zombie/core/skinnedmodel/model/ModelSlotRenderData",
        "zombie/core/skinnedmodel/model/ItemModelRenderer", "zombie/core/skinnedmodel/model/IsoObjectModelDrawer",
        "zombie/core/skinnedmodel/advancedanimation/AnimatedModel",
        "zombie/core/skinnedmodel/advancedanimation/AnimatedModel$AnimatedModelInstanceRenderData", "zombie/iso/fboRenderChunk/FBORenderCell",
        "zombie/iso/IsoGridSquare", "zombie/iso/IsoObject", "zombie/iso/IsoPuddles$RenderData", "zombie/iso/sprite/shapers/FloorShaper",
        "zombie/iso/sprite/shapers/FloorShaperDeDiamond", "zombie/iso/sprite/shapers/WallShaper", "zombie/iso/objects/IsoDoor",
        "zombie/iso/objects/IsoDeadBody", "zombie/iso/fboRenderChunk/FBORenderChunk", "zombie/viewCone/ChunkRenderShader",
        "zombie/iso/LightingJNI$JNILighting");

    /** Runs after the class's own PZO transform, on its output. Null when nothing changed. */
    static byte[] postTransform(ClassLoader loader, String className, byte[] bytes) {
        if (className == null || bytes == null || !TARGETS.contains(className)) return null;
        byte[] out = AsmSupport.transform(bytes, loader, "PixelLight " + className, PixelLight::patch);
       return out;
    }

    static boolean patch(ClassNode node) {
        String self = "com/pzoptimizer/PixelLight";
        int changes = 0;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.GETFIELD || !"nolighting".equals(f.name)
                        || !"Lzombie/debug/BooleanDebugOption;".equals(f.desc)) continue;
                AbstractInsnNode next = f.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (next instanceof MethodInsnNode mi && "getValue".equals(mi.name) && "()Z".equals(mi.desc)) {
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "noLighting",
                        "(Lzombie/debug/BooleanDebugOption;)Z", false);
                    m.instructions.set(mi, call);
                    changes++;
                }
            }
        }
        switch (node.name) {
            case "zombie/iso/IsoObject" -> {
                MethodNode effects = AsmSupport.findMethod(node, "getObjectRenderEffectsToApply", "()Lzombie/iso/objects/ObjectRenderEffects;");
                if (effects != null && AsmSupport.redirectCalls(effects, "zombie/core/Core", "getOptionDoWindSpriteEffects", "()Z",
                        self, "windSkewOption", "(Lzombie/core/Core;)Z") > 0) {
                    changes++;
                }
            }
            case "zombie/iso/fboRenderChunk/FBORenderCell" -> {
                MethodNode check = AsmSupport.findMethod(node, "checkWindEffectsOption", "(I)V");
                if (check != null) {
                    InsnList pre = new InsnList();
                    pre.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "frame", "(I)V", false));
                    check.instructions.insert(pre);
                    changes++;
                }
            }
            case "zombie/iso/fboRenderChunk/FBORenderChunk" -> {
                String fc = "Lzombie/iso/fboRenderChunk/FBORenderChunk;";
                MethodNode beginMain = AsmSupport.findMethod(node, "beginMainThread", "(Z)V");
                MethodNode beginRender = AsmSupport.findMethod(node, "beginRenderThread", "(Z)V");
                MethodNode endRender = AsmSupport.findMethod(node, "endRenderThread", "()V");
                MethodNode composite = AsmSupport.findMethod(node, "renderInWorldMainThread", "()V");
                if (beginMain == null || beginRender == null || endRender == null || composite == null) return false;
                for (Object[] h : new Object[][] {{beginMain, "bakeRecorded"}, {beginRender, "bakeBegin"}}) {
                    InsnList pre = new InsnList();
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    pre.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, (String) h[1], "(" + fc + "Z)V", false));
                    ((MethodNode) h[0]).instructions.insert(pre);
                }
                AsmSupport.prependCall(endRender, self, "bakeEnd", "(" + fc + ")V");
                int returns = 0;
                for (AbstractInsnNode insn = composite.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() != Opcodes.RETURN) continue;
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "composited", "(" + fc + ")V", false));
                    composite.instructions.insertBefore(insn, call);
                    returns++;
                }
                if (returns == 0) return false;
                changes++;
            }
            case "zombie/viewCone/ChunkRenderShader" -> {
                MethodNode start = AsmSupport.findMethod(node, "startRenderThread", "(Lzombie/core/textures/TextureDraw;)V");
                if (start == null) return false;
                int returns = 0;
                for (AbstractInsnNode insn = start.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() != Opcodes.RETURN) continue;
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "chunkDraw",
                        "(Lzombie/core/opengl/Shader;Lzombie/core/textures/TextureDraw;)V", false));
                    start.instructions.insertBefore(insn, call);
                    returns++;
                }
                if (returns == 0) return false;
                changes++;
            }
            case "zombie/iso/LightingJNI$JNILighting" -> {
                int sites = 0;
                for (MethodNode m : node.methods) {
                    sites += AsmSupport.redirectCalls(m, "zombie/iso/fboRenderChunk/FBORenderLevels", "invalidateLevel", "(IJ)V",
                        self, "lightInvalidate", "(Lzombie/iso/fboRenderChunk/FBORenderLevels;IJ)V");
                }
                if (sites == 0) return false;
                changes++;
            }
            default -> {
            }
        }
        return changes > 0;
    }
}
