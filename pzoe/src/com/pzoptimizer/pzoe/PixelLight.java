package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.ShaderPatchHost;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.FoliageLod;
import com.pzoptimizer.ZombieBuddyCompat;

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
    static final int UPLOADS_PER_FRAME = 24, SNOW_ONLY_UPLOADS_PER_FRAME = 6, STALE_UPLOADS_PER_FRAME = 12;
    static final long DIRTY_LIGHTING = 32L, DIRTY_REDRAW = 1024L;

    public static final AtomicLong columnsUploaded = new AtomicLong();
    public static final AtomicLong lightChangesKept = new AtomicLong();

    // Seen by the render thread on the chunk shader
    static volatile boolean lightShaderReady, swayShaderReady, atmosShaderReady, snowShaderReady, windowsWanted, sunWanted;
    // Snow on Surfaces only needs the light map while there is snow; without it the card would keep the whole
    // light map pipeline running all year
    private static boolean snowing, groundSnowWas;
    // Toward the sun in world squares, levels the ray climbs per square, visibility (0 = no sun)
    static volatile float sunX, sunY = 1f, sunRise = 1f, sunVis;
    // Decided on the main thread; atmos = ground fog / light glow, which only reads the light map
    static volatile boolean deferred, sway, atmos;
    // The Per-Pixel Lighting card (Lua sends it). The lighting code stays in the chunk shader either way, behind
    // PZOLit, so flipping the card never recompiles anything.
    static volatile boolean lightWanted;
    // Card strengths for PZOTune0..2 (Lua sends them on change); uniforms so a slider never recompiles the shader
    static final float[] tune = new float[12];
    // Lighting just went off: new bakes are vanilla, but chunks still holding an unlit bake keep their light from
    // here until the re-bake queue reaches them, so nothing shows fullbright while the world converts
    static volatile boolean draining;
    private static long drainEndNs;
    // Next update uploads every stale column at once (lighting just went on with an out-of-date light map)
    private static boolean catchUpAll;
    static volatile float fogIntensity, rainIntensity, dayLight, cloudIntensity, snowCover, cloudOffX, cloudOffY, wetness;
    static volatile Thread mainThread;
    // Render thread: inside the FBO of a chunk that's being baked unlit
    static boolean renderBakingUnlit;

    // Main thread writes per frame, render thread reads
    // How much leaf the trees carry this month, 0..1: their sun shadows thin out to bare branches in winter
    static volatile float leafDensity = 1f;
    private static final float[] MONTH_LEAF = {0.2f, 0.2f, 0.25f, 0.55f, 0.9f, 1f, 1f, 1f, 0.95f, 0.75f, 0.4f, 0.2f};
    static volatile float windX = 1f, windY = 0f, windStrength, clock, worldWindX = 1f, worldWindY;
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
        // Slot holds the right chunk but its column is out of date (heights or plant flags newly wanted, or the light
        // map wasn't followed while nothing read it). Caught up nearest first within a budget.
        boolean[] stale;
        final Set<IsoChunk> dirty = ConcurrentHashMap.newKeySet();
        int roundRobin;
    }

    private static final PlayerState[] players = new PlayerState[4];

    // Render thread
    private static final int[] texId = new int[4], texRing = new int[4], heightTex = new int[4];
    private static int locProgram = -1;
    private static long locCheckedNs;
    private static int locA, locB, locC, locLit, locWind, locTex, locAtmos = -1, locFogWind = -1, locClouds = -1, locSnow = -1, locTrail = -1, locTrailInfo = -1, locWindows = -1,
        locHeight = -1, locSun = -1, locWet = -1, locBend = -1, locTrailMarks = -1, locBeams = -1, locBeamInfo = -1,
        locFogField = -1, locFogInfo = -1, locGSnow = -1, locGSnow2 = -1, locSky = -1, locSunCache = -1, locSunCacheInfo = -1,
        locCloudCache = -1, locCloudInfo = -1, locFogCache = -1, locFogCacheInfo = -1, locSnowCache = -1, locSnowCacheInfo = -1, locTune0 = -1, locTune1 = -1, locTune2 = -1;

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
        // A chunk still waiting in the re-bake queue may hold a lit bake: let the game re-bake that level now, which
        // also converts it
        if (flags == DIRTY_LIGHTING && deferred && !RebakeQueue.contains(renderLevels.getChunk())) {
            lightChangesKept.incrementAndGet();
            PlayerState ps = players[renderLevels.getPlayerIndex()];
            if (ps != null) ps.dirty.add(renderLevels.getChunk());
            return;
        }
        // Without per-pixel lighting the light map still feeds fog and window glow, so it follows light changes too
        if (flags == DIRTY_LIGHTING && (atmos || deferred || draining)) {
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
            if (!(deferred || sway || atmos || draining)) return;
            update(playerIndex);
        } catch (Throwable t) {
            PZOLogger.warn("[PixelLight] frame: " + t);
        }
    }

    private static void globalFrame() {
        long now = System.nanoTime();
        flashing = Storm.flashing();
        status(now);
        ModelSnow.frame(now);
        double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (zombie.GameTime.isGamePaused()) dt = 0;
        float c = clock + (float) dt;
        clock = c > 4096f ? c - 4096f : c;

        boolean wantDeferred = lightShaderReady && lightWanted && !zombie.debug.DebugOptions.instance.fboRenderChunk.combinedFbo.getValue();
        boolean wantSway = swayShaderReady && zombie.core.Core.getInstance().getOptionDoWindSpriteEffects();
        if (wantDeferred != deferred) {
            // The light map stopped following changes while nothing used it: every column goes up again this frame,
            // before the chunks re-bake unlit
            if (wantDeferred && !(sway || atmos || draining)) {
                markStale();
                catchUpAll = true;
            }
            deferred = wantDeferred;
            draining = !wantDeferred;
            drainEndNs = 0L;
            if (zombie.core.Core.debug) PZOLogger.info("[PixelLight] per-pixel lighting " + (wantDeferred ? "on" : "off") + ", re-baking loaded chunks");
            RebakeQueue.now(DIRTY_LIGHTING | DIRTY_REDRAW);
        }
        if (wantSway != sway) {
            sway = wantSway;
            if (zombie.core.Core.debug) PZOLogger.info("[PixelLight] composite sway " + (wantSway ? "on" : "off") + ", re-baking loaded chunks");
            RebakeQueue.now(zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_TREES);
            // Plant flags ride in the light map
            markStale();
        }
        if (BakePass.rebakeWanted) {
            BakePass.rebakeWanted = false;
            RebakeQueue.now(DIRTY_REDRAW);
        }
        if (BakePass.rebakeSoon) {
            BakePass.rebakeSoon = false;
            RebakeQueue.addAll(DIRTY_REDRAW);
        }
        if (sunWanted != sunHeightsOn) {
            sunHeightsOn = sunWanted;
            // Every column goes up again with its heights
            if (sunHeightsOn) markStale();
        }
        GroundSnow.frame();
        NaturalGround.frame();
        leafDensity = MONTH_LEAF[Math.floorMod(zombie.GameTime.getInstance().getMonth(), 12)];
        if (GroundSnow.active() != groundSnowWas) {
            groundSnowWas = GroundSnow.active();
            // Vanilla's snow tiles are baked into the chunks: re-bake so they go (or come back)
            RebakeQueue.now(DIRTY_REDRAW);
        }
        RebakeQueue.drain();
        if (draining && RebakeQueue.isEmpty()) {
            // A couple of seconds more for chunks invalidated off screen that bake as they come into view
            if (drainEndNs == 0L) drainEndNs = now + 2_000_000_000L;
            else if (now > drainEndNs) draining = false;
        }
        if (snowShaderReady || GroundSnow.active()) {
            zombie.iso.weather.ClimateManager scm = zombie.iso.weather.ClimateManager.getInstance();
            // Ground snow starts before vanilla's hourly snow shows any, so it counts the moment snow falls
            float sn = Math.max(scm.getSnowFracNow(), GroundSnow.active() ? GroundSnow.depth : 0f);
            if (GroundSnow.active() && scm.getPrecipitationIsSnow() && scm.getPrecipitationIntensity() > 0.02f) sn = Math.max(sn, 0.02f);
            // Gap between on and off so a thin, melting cover doesn't flip the light map every frame
            if (sn > 0.01f) snowing = true;
            else if (sn < 0.002f) snowing = false;
        } else {
            snowing = false;
        }
        boolean wantAtmos = atmosShaderReady || ((snowShaderReady || GroundSnow.active()) && snowing);
        if (wantAtmos != atmos) {
            // The light map stopped following changes while nothing used it, so it goes up again from scratch
            if (wantAtmos && !(deferred || sway || draining)) markStale();
            atmos = wantAtmos;
            if (zombie.core.Core.debug) PZOLogger.info("[PixelLight] light map effects (fog, glow, clouds, snow...) " + (atmos ? "on" : "off"));
        }
        if (sway || atmos) {
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            fogIntensity = cm.getFogIntensity();
            rainIntensity = cm.getRainIntensity();
            dayLight = cm.getDayLightStrength();
            cloudIntensity = cm.getCloudIntensity();
            // Snow on Surfaces follows the ground snow's smooth depth when that card is on
            snowCover = GroundSnow.active() ? Math.min(1f, GroundSnow.depth) : cm.getSnowFracNow();
            wetness = zombie.iso.IsoPuddles.getInstance().getWetGroundFinalValue();
            // The cloud field drifts with the wind, faster when it blows harder; kept small so float noise stays smooth
            double ca = cm.getWindAngleRadians(), speed = (0.5 + 3.0 * cm.getWindIntensity()) * 0.03 * dt;
            cloudOffX = (float) ((cloudOffX + Math.cos(ca) * speed) % 512.0);
            cloudOffY = (float) ((cloudOffY + Math.sin(ca) * speed) % 512.0);
            float a = cm.getWindAngleRadians();
            // World wind vector to screen: x - y across, (x + y) / 2 down
            float wx = (float) Math.cos(a), wy = (float) Math.sin(a);
            worldWindX = wx;
            worldWindY = wy;
            float sx = wx - wy, sy = (wx + wy) * 0.5f;
            float len = (float) Math.sqrt(sx * sx + sy * sy);
            if (len > 1e-4f) {
                windX = sx / len;
                windY = sy / len;
            }
            windStrength = cm.getWindIntensity();
            if (sunHeightsOn) sun(cm);
            else sunVis = 0f;
            // Fog drift as the chunk shader had it: screen wind back to the ground, carried with the clock
            float fwx = windY + 0.5f * windX, fwy = windY - 0.5f * windX, fk = clock * (0.15f + 0.6f * windStrength);
            float weather = Math.max(fogIntensity, rainIntensity * 0.5f) + 0.12f * (1f - dayLight);
            SkyCache.frame(sunHeightsOn && sunVis > 0f, sunX, sunY, sunRise, leafDensity, atmos ? cloudIntensity : 0f, cloudOffX, cloudOffY, clock,
                atmos && weather > 0.001f, fwx * fk, fwy * fk, atmos && GroundSnow.active() && GroundSnow.depth > 0.001f);
            LightBeams.frame();
            FogField.frame();
            // Morning mist (High fog) is real fog for everything that reads the fog amount
            fogIntensity = Math.max(fogIntensity, FogField.mist);
        }
    }

    private static boolean sunHeightsOn;

    // East (+x) at dawn, south (+y) at midday, west at dusk. Longer days put the midday sun higher. Shadows fade in as
    // the sun clears the horizon and under cloud.
    private static void sun(zombie.iso.weather.ClimateManager cm) {
        float t = zombie.GameTime.getInstance().getTimeOfDay();
        float dawn = 6f, dusk = 20f;
        zombie.erosion.season.ErosionSeason season = cm.getSeason();
        if (season != null) {
            dawn = season.getDawn();
            dusk = season.getDusk();
        }
        float day = Math.max(1f, dusk - dawn);
        float f = (t - dawn) / day;
        if (f <= 0f || f >= 1f) {
            sunVis = 0f;
            return;
        }
        double peak = Math.toRadians(Math.max(25.0, Math.min(65.0, 25.0 + (day - 8.0) * 5.0)));
        double elev = peak * Math.sin(Math.PI * f);
        sunX = (float) Math.cos(Math.PI * f);
        sunY = (float) Math.sin(Math.PI * f);
        // A level is 2.45 squares tall in the depth metric
        sunRise = (float) (Math.tan(Math.max(elev, Math.toRadians(4.0))) / 2.45);
        double deg = Math.toDegrees(elev);
        float rise = (float) Math.max(0.0, Math.min(1.0, (deg - 2.0) / 8.0));
        // Overcast or rain/snow means no direct sun at all. A leftover 15% here showed as faint square patches in snow.
        float clear = 1f - smooth(0.3f, 0.7f, cloudIntensity);
        float dry = 1f - smooth(0.02f, 0.15f, cm.getPrecipitationIntensity());
        sunVis = rise * rise * (3f - 2f * rise) * clear * dry;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    // Render thread: the light map of a player, for passes outside the chunk shader (falling rain and snow)
    static int lightTexture(int p) {
        return p >= 0 && p < 4 ? texId[p] : 0;
    }

    static int lightRing(int p) {
        return p >= 0 && p < 4 ? texRing[p] : 0;
    }

    // Main thread reads it to tell SkyCache a player's height map exists; GL ids are just ints
    static int heightTexture(int p) {
        return p >= 0 && p < 4 ? heightTex[p] : 0;
    }

    // Every column goes up again, nearest first within a budget. Slots keep their chunk, so the composite carries on
    // with the old column meanwhile instead of the whole screen dropping its effects for a frame.
    private static void markStale() {
        for (PlayerState ps : players) {
            if (ps == null || ps.stale == null) continue;
            java.util.Arrays.fill(ps.stale, true);
        }
    }

    /** Main thread: this chunk's column is in the light map and up to date for player p (safe to bake it unlit). */
    static boolean columnCurrent(int p, IsoChunk c) {
        PlayerState ps = p >= 0 && p < 4 ? players[p] : null;
        if (ps == null || ps.slotKey == null || ps.grid <= 0) return true;
        int slot = Math.floorMod(c.wy, ps.grid) * ps.grid + Math.floorMod(c.wx, ps.grid);
        return ps.slotKey[slot] == key(c.wx, c.wy) && !ps.stale[slot];
    }

    // Grid positions of a chunk map ordered by distance from its middle (the player), cached per grid size
    private static int[] spiral = new int[0];
    private static int spiralGrid = -1;

    static int[] spiralOrder(int g) {
        if (g == spiralGrid) return spiral;
        Integer[] idx = new Integer[g * g];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        float mid = (g - 1) * 0.5f;
        java.util.Arrays.sort(idx, (a, b) -> {
            float ax = a % g - mid, ay = a / g - mid, bx = b % g - mid, by = b / g - mid;
            return Float.compare(ax * ax + ay * ay, bx * bx + by * by);
        });
        int[] out = new int[idx.length];
        for (int i = 0; i < out.length; i++) out[i] = idx[i];
        spiral = out;
        spiralGrid = g;
        return out;
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
            ps.stale = new boolean[g * g];
            java.util.Arrays.fill(ps.slotKey, Long.MIN_VALUE);
            ps.dirty.clear();
            SpriteRenderer().drawGeneric(new CreateTexture(p, ps.ring));
        }
        // New or reloaded chunks go up at once (they're about to be drawn); stale columns and light changes within a
        // budget, nearest first
        int[] order = spiralOrder(g);
        int catchUp = catchUpAll ? Integer.MAX_VALUE : STALE_UPLOADS_PER_FRAME;
        for (int idx : order) {
            IsoChunk c = map.getChunk(idx % g, idx / g);
            if (c == null || c.lightingNeverDone[p]) continue;
            int slot = Math.floorMod(c.wy, g) * g + Math.floorMod(c.wx, g);
            long k = key(c.wx, c.wy);
            if (ps.slotKey[slot] != k) {
                upload(p, ps, c, true);
                ps.slotKey[slot] = k;
                ps.stale[slot] = false;
                ps.dirty.remove(c);
            } else if (ps.stale[slot] && catchUp > 0) {
                upload(p, ps, c, true);
                ps.stale[slot] = false;
                ps.dirty.remove(c);
                catchUp--;
            }
        }
        if (p == lastPlayerIndex()) catchUpAll = false;
        // Snow alone only tints what it covers by the light, so it can follow light changes more slowly
        int budget = !deferred && !draining && !sway && !atmosShaderReady ? SNOW_ONLY_UPLOADS_PER_FRAME : UPLOADS_PER_FRAME;
        // A lightning flash lights every outdoor square for a moment. The light map can't take all of that in one
        // frame, so a few columns at a time caught the flash and stayed bright after it: squares lighting up like day
        // and dropping back. The game's weather shader already flashes the whole screen, so light changes wait in the
        // dirty set until the flash is over (PZO's bake budget holds lighting re-bakes the same way).
        if (flashing) budget = 0;
        java.util.Iterator<IsoChunk> it = ps.dirty.iterator();
        while (budget > 0 && it.hasNext()) {
            IsoChunk c = it.next();
            it.remove();
            if (c.lightingNeverDone[p]) continue;
            int slot = Math.floorMod(c.wy, g) * g + Math.floorMod(c.wx, g);
            if (ps.slotKey[slot] != key(c.wx, c.wy)) continue;
            upload(p, ps, c, false);
            budget--;
        }
        // One column a frame refreshed anyway: catches foliage added or removed and anything a hook missed
        if (budget > 0 && g > 0) {
            int i = ps.roundRobin = (ps.roundRobin + 1) % (g * g);
            IsoChunk c = map.getChunk(i % g, i / g);
            if (c != null && !c.lightingNeverDone[p]) upload(p, ps, c, false);
        }
    }

    // Main thread, once a frame: a lightning flash is on for some player
    private static boolean flashing;

    // ThunderStorm's per-player flash state (private in the game), read the way PZO's ChunkBakeBudget does
    private static final class Storm {
        private static final java.lang.invoke.VarHandle INFOS, STATE;
        static {
            java.lang.invoke.VarHandle infos = null, state = null;
            try {
                java.lang.invoke.MethodHandles.Lookup l = java.lang.invoke.MethodHandles.lookup();
                Class<?> storm = Class.forName("zombie.iso.weather.ThunderStorm");
                Class<?> info = Class.forName("zombie.iso.weather.ThunderStorm$PlayerLightningInfo");
                Class<?> stateType = Class.forName("zombie.iso.weather.ThunderStorm$LightningState");
                infos = java.lang.invoke.MethodHandles.privateLookupIn(storm, l).findVarHandle(storm, "lightningInfos", info.arrayType());
                state = java.lang.invoke.MethodHandles.privateLookupIn(info, l).findVarHandle(info, "lightningState", stateType);
            } catch (Throwable t) {
                PZOLogger.warn("[PixelLight] lightning state not readable, flashes may light squares: " + t);
            }
            INFOS = infos;
            STATE = state;
        }

        static boolean flashing() {
            try {
                if (STATE == null) return false;
                zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
                Object storm = cm == null ? null : cm.getThunderStorm();
                if (storm == null) return false;
                Object[] infos = (Object[]) INFOS.get(storm);
                if (infos == null) return false;
                for (Object info : infos) {
                    if (info != null && ((Enum<?>) STATE.get(info)).ordinal() != 0) return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    // Highest player index with a chunk map, so a catch-up for everyone ends after the last of them
    private static int lastPlayerIndex() {
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return 0;
        for (int p = 3; p > 0; p--) if (cell.getChunkMap(p) != null && zombie.characters.IsoPlayer.players[p] != null) return p;
        return 0;
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

    // full = every slice (the slot held another chunk). Otherwise only the slices from one level below the chunk to
    // three above its top: the composite reads no further (tree tops reach about two levels up), and most chunks have
    // 1-3 of the 24 levels, so a light change costs a handful of uploads instead of 48.
    private static void upload(int p, PlayerState ps, IsoChunk c, boolean full) {
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
            if (level >= c.minLevel && level <= c.maxLevel) weld(c, level, p, vals);
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
        ByteBuffer hb = sunHeightsOn ? heights(c) : null;
        ByteBuffer buf = buffers.poll();
        if (buf == null) buf = ByteBuffer.allocateDirect(COLUMN_INTS * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.clear();
        pack(scratch, scratchSet, scratchFlags, buf.asIntBuffer());
        buf.position(0);
        buf.limit(COLUMN_INTS * 4);
        int x0 = 2 * Math.floorMod(c.wx * 8, ps.ring), y0 = 2 * Math.floorMod(c.wy * 8, ps.ring);
        int sFrom = 0, sTo = 2 * LEVELS - 1;
        if (!full) {
            sFrom = 2 * Math.max(0, c.minLevel - 1 - BASE_LEVEL);
            sTo = Math.min(2 * LEVELS - 1, 2 * (c.maxLevel + 3 - BASE_LEVEL) + 1);
            if (sTo < sFrom) {
                sFrom = 0;
                sTo = 2 * LEVELS - 1;
            }
        }
        SpriteRenderer().drawGeneric(new Upload(p, ps.ring, x0, y0, buf, hb, sFrom, sTo));
        columnsUploaded.incrementAndGet();
    }

    // Main thread scratch for weld: the level's squares with a 1-square border (10x10), and the unwelded corners
    private static final IsoGridSquare[] weldSq = new IsoGridSquare[100];
    private static final int[] weldOrig = new int[64 * 8];

    /**
     * The game gives every square its own 4 corner lights, and neighbours often disagree on a corner they share
     * (under a roof edge, next to a lamp's falloff), so each square's light stops at its edge: blocky steps. I average
     * each shared corner over the squares around it that no wall, window or door frame separates, so light runs
     * smoothly across open floor and still stops at walls. Squares outside the chunk are read too; both chunks
     * work out the same average, so chunk borders don't seam.
     */
    private static void weld(IsoChunk c, int z, int p, int[] vals) {
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return;
        int ox = c.wx * 8, oy = c.wy * 8;
        for (int yy = 0; yy < 10; yy++) {
            for (int xx = 0; xx < 10; xx++) {
                int lx = xx - 1, ly = yy - 1;
                IsoGridSquare sq = lx >= 0 && lx < 8 && ly >= 0 && ly < 8 ? c.getGridSquare(lx, ly, z) : cell.getGridSquare(ox + lx, oy + ly, z);
                weldSq[yy * 10 + xx] = sq != null && sq.lighting[p] != null ? sq : null;
            }
        }
        System.arraycopy(vals, 0, weldOrig, 0, 64 * 8);
        for (int i = 0; i < 64; i++) {
            int sx = i & 7, sy = i >> 3;
            IsoGridSquare self = weldSq[(sy + 1) * 10 + sx + 1];
            if (self == null) continue;
            for (int k = 0; k < 4; k++) {
                // Corner order (x,y), (x+1,y), (x+1,y+1), (x,y+1)
                int dx = k == 1 || k == 2 ? 1 : 0, dy = k >= 2 ? 1 : 0;
                for (int o = 0; o < 8; o += 4) {
                    int col = weldOrig[i * 8 + o + k];
                    int r = col & 0xFF, g = (col >> 8) & 0xFF, b = (col >> 16) & 0xFF, n = 1;
                    for (int qy = dy - 1; qy <= dy; qy++) {
                        for (int qx = dx - 1; qx <= dx; qx++) {
                            if (qx == 0 && qy == 0) continue;
                            IsoGridSquare nb = weldSq[(sy + 1 + qy) * 10 + sx + 1 + qx];
                            if (nb == null || !connected(self, sx, sy, qx, qy)) continue;
                            int vx = dx - qx, vy = dy - qy;
                            int kc = vy == 0 ? vx : 3 - vx;
                            int nx = sx + qx, ny = sy + qy;
                            int v = nx >= 0 && nx < 8 && ny >= 0 && ny < 8 ? weldOrig[(ny * 8 + nx) * 8 + o + kc] : nb.getVertLight(o + kc, p);
                            r += v & 0xFF;
                            g += (v >> 8) & 0xFF;
                            b += (v >> 16) & 0xFF;
                            n++;
                        }
                    }
                    if (n > 1) vals[i * 8 + o + k] = (col & 0xFF000000) | ((b / n) << 16) | ((g / n) << 8) | (r / n);
                }
            }
        }
    }

    // Square at offset (qx, qy) from (sx, sy), both in weldSq, reachable without crossing a wall edge
    private static boolean connected(IsoGridSquare self, int sx, int sy, int qx, int qy) {
        IsoGridSquare nb = weldSq[(sy + 1 + qy) * 10 + sx + 1 + qx];
        if (qx == 0 || qy == 0) return !edgeBlocked(self, nb, qx, qy);
        IsoGridSquare h = weldSq[(sy + 1) * 10 + sx + 1 + qx], v = weldSq[(sy + 1 + qy) * 10 + sx + 1];
        return (h != null && !edgeBlocked(self, h, qx, 0) && !edgeBlocked(h, nb, 0, qy))
            || (v != null && !edgeBlocked(self, v, 0, qy) && !edgeBlocked(v, nb, qx, 0));
    }

    // The square with the larger x owns the west edge between them, larger y the north edge
    private static boolean edgeBlocked(IsoGridSquare a, IsoGridSquare b, int dx, int dy) {
        if (dx != 0) return wallEdge(dx > 0 ? b : a, false);
        return wallEdge(dy > 0 ? b : a, true);
    }

    private static boolean wallEdge(IsoGridSquare sq, boolean north) {
        zombie.core.properties.PropertyContainer pr = sq.getProperties();
        if (pr == null) return false;
        if (north) return pr.has(zombie.iso.SpriteDetails.IsoFlagType.collideN) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.WindowN)
            || pr.has(zombie.iso.SpriteDetails.IsoFlagType.DoorWallN) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.doorN);
        return pr.has(zombie.iso.SpriteDetails.IsoFlagType.collideW) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.WindowW)
            || pr.has(zombie.iso.SpriteDetails.IsoFlagType.DoorWallW) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.doorW);
    }

    private static final ConcurrentLinkedQueue<ByteBuffer> heightBuffers = new ConcurrentLinkedQueue<>();
    private static final float[] hContent = new float[64], hOpacity = new float[64], hWallN = new float[64], hWallW = new float[64],
        hSeeN = new float[64], hSeeW = new float[64];
    private static final boolean[] hSnowGround = new boolean[64], hRoad = new boolean[64];

    /**
     * Sun shadow heights for a chunk column, one RGBA texel per square, in 1/32 levels above level 0: R = top of what
     * stands on the square (solid things, trees, the floor of upper levels), G = top of a wall on its north edge,
     * B = on its west edge, A = how much light the content stops (trees let some through; floors are 1, props just
     * under 1 so the shader rounds their footprint). Walls are edges, not squares, so a shadow starts at the wall line.
     * G and B keep heights in even steps; an odd value means the edge is see-through (chain-link, railings, hoppable
     * fences). Below ground nothing casts.
     */
    private static ByteBuffer heights(IsoChunk c) {
        java.util.Arrays.fill(hContent, 0f);
        java.util.Arrays.fill(hOpacity, 1f);
        java.util.Arrays.fill(hWallN, 0f);
        java.util.Arrays.fill(hWallW, 0f);
        java.util.Arrays.fill(hSeeN, 0f);
        java.util.Arrays.fill(hSeeW, 0f);
        java.util.Arrays.fill(hSnowGround, false);
        java.util.Arrays.fill(hRoad, false);
        int top = Math.min(c.maxLevel, BASE_LEVEL + LEVELS - 1);
        for (int z = Math.max(0, c.minLevel); z <= top; z++) {
            for (int i = 0; i < 64; i++) {
                IsoGridSquare sq = c.getGridSquare(i & 7, i >> 3, z);
                if (sq == null) continue;
                if (z == 0) snowFlags(sq, i);
                java.util.List<zombie.iso.IsoObject> objects = sq.getObjects();
                for (int k = 0, n = objects.size(); k < n; k++) {
                    zombie.iso.IsoObject o = objects.get(k);
                    if (o == null || o.sprite == null) continue;
                    zombie.core.properties.PropertyContainer pr = o.getProperties();
                    if (pr == null) continue;
                    if (pr.has(zombie.iso.SpriteDetails.IsoFlagType.solidfloor)) {
                        if (z > hContent[i]) {
                            hContent[i] = z;
                            hOpacity[i] = 1f;
                        }
                        continue;
                    }
                    boolean wallSE = o.isWallSE();
                    boolean nw = pr.has(zombie.iso.SpriteDetails.IsoFlagType.WallNW);
                    // Windows are transparentN/W too, but the wall around them still blocks most of the sun
                    boolean window = pr.has(zombie.iso.SpriteDetails.IsoFlagType.WindowN) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.WindowW);
                    boolean seeN = !window && pr.has(zombie.iso.SpriteDetails.IsoFlagType.transparentN);
                    boolean seeW = !window && pr.has(zombie.iso.SpriteDetails.IsoFlagType.transparentW);
                    float[] wn = seeN ? hSeeN : hWallN, ww = seeW ? hSeeW : hWallW;
                    if (!wallSE && (o.sprite.cutN || nw)) wn[i] = Math.max(wn[i], z + 1f);
                    if (!wallSE && (o.sprite.cutW || nw)) ww[i] = Math.max(ww[i], z + 1f);
                    if (pr.has(zombie.iso.SpriteDetails.IsoFlagType.TallHoppableN)) hSeeN[i] = Math.max(hSeeN[i], z + 0.9f);
                    if (pr.has(zombie.iso.SpriteDetails.IsoFlagType.TallHoppableW)) hSeeW[i] = Math.max(hSeeW[i], z + 0.9f);
                    if (pr.has(zombie.iso.SpriteDetails.IsoFlagType.HoppableN)) hSeeN[i] = Math.max(hSeeN[i], z + 0.45f);
                    if (pr.has(zombie.iso.SpriteDetails.IsoFlagType.HoppableW)) hSeeW[i] = Math.max(hSeeW[i], z + 0.45f);
                    if (o.sprite.cutN || o.sprite.cutW || nw) continue;
                    float h;
                    float op = 1f;
                    if (o instanceof zombie.iso.objects.IsoTree) {
                        h = z + 2.2f;
                        op = 0.55f;
                    } else if (pr.has(zombie.iso.SpriteDetails.IsoFlagType.vegitation) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.FloorOverlay)
                        || pr.has(zombie.iso.SpriteDetails.IsoFlagType.attachedFloor)) {
                        // Grass, plants and ground clutter: counted as blocks they covered open ground in square shadows
                        continue;
                    } else if (pr.has(zombie.iso.SpriteDetails.IsoFlagType.solid) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.solidtrans)) {
                        h = z + 0.7f;
                        op = 0.97f;
                    } else if (z == 0 && (pr.has(zombie.iso.SpriteDetails.IsoFlagType.container) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.bed)
                        || pr.has(zombie.iso.SpriteDetails.IsoFlagType.tableN) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.tableW)
                        || pr.has(zombie.iso.SpriteDetails.IsoFlagType.pushable) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.blocksight))) {
                        // Low furniture and props; on upper levels these are mostly roof pieces, which sit flat
                        h = 0.4f;
                        op = 0.8f;
                    } else {
                        continue;
                    }
                    if (h > hContent[i]) {
                        hContent[i] = h;
                        hOpacity[i] = op;
                    }
                }
            }
        }
        ByteBuffer b = heightBuffers.poll();
        if (b == null) b = ByteBuffer.allocateDirect(64 * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.clear();
        for (int i = 0; i < 64; i++) {
            b.put((byte) ((Math.round(Math.max(0f, Math.min(7.9f, hContent[i])) * 16f) * 2) | (hSnowGround[i] ? 1 : 0)));
            b.put(wall(hWallN[i], hSeeN[i]));
            b.put(wall(hWallW[i], hSeeW[i]));
            b.put((byte) ((Math.round(hOpacity[i] * 127f) * 2) | (hRoad[i] ? 1 : 0)));
        }
        b.flip();
        return b;
    }

    // Ground level squares that hold snow by vanilla's own rule (outdoors, solid floor, not in a room, not water),
    // and which of them are road
    private static void snowFlags(IsoGridSquare sq, int i) {
        zombie.core.properties.PropertyContainer pr = sq.getProperties();
        if (pr == null || sq.room != null || !pr.has(zombie.iso.SpriteDetails.IsoFlagType.exterior)
            || !pr.has(zombie.iso.SpriteDetails.IsoFlagType.solidfloor) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.water)) return;
        if (sq.getWater() != null && sq.getWater().isValid()) return;
        hSnowGround[i] = true;
        zombie.iso.IsoObject floor = sq.getFloor();
        String name = floor == null || floor.sprite == null ? null : floor.sprite.getName();
        hRoad[i] = name != null && (name.contains("blends_street") || name.contains("floors_exterior_street"));
    }

    // Solid wins unless the see-through part stands taller
    private static byte wall(float solid, float see) {
        boolean seeThrough = see > solid;
        int even = Math.max(0, Math.min(127, Math.round(Math.max(solid, see) * 16f))) * 2;
        return (byte) (even | (seeThrough ? 1 : 0));
    }

    private static byte h32(float levels) {
        return (byte) Math.max(0, Math.min(255, Math.round(levels * 32f)));
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
            if (heightTex[player] != 0) GL11.glDeleteTextures(heightTex[player]);
            int h = GL11.glGenTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, h);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, ring, ring, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            heightTex[player] = h;
        }
    }

    private static final class Upload extends TextureDraw.GenericDrawer {
        final int player, ring, x0, y0, sFrom, sTo;
        final ByteBuffer data, heights;

        Upload(int player, int ring, int x0, int y0, ByteBuffer data, ByteBuffer heights, int sFrom, int sTo) {
            this.sFrom = sFrom;
            this.sTo = sTo;
            this.player = player;
            this.ring = ring;
            this.x0 = x0;
            this.y0 = y0;
            this.data = data;
            this.heights = heights;
        }

        @Override
        public void render() {
            if (texId[player] == 0 || texRing[player] != ring) return;
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId[player]);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            uploadSlices(x0, y0, ring, data, sFrom, sTo);
            if (heights != null && heightTex[player] != 0) {
                GL13.glActiveTexture(GL13.GL_TEXTURE4);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, heightTex[player]);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x0 / 2, y0 / 2, 8, 8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, heights);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }

        @Override
        public void postRender() {
            buffers.offer(data);
            if (heights != null) heightBuffers.offer(heights);
        }
    }

    /** One packed column (see pack) into its 2 * LEVELS slices of the bound atlas; slice s is tile (s % 8, s / 8). */
    static void uploadSlices(int x0, int y0, int ring, ByteBuffer data) {
        uploadSlices(x0, y0, ring, data, 0, 2 * LEVELS - 1);
    }

    static void uploadSlices(int x0, int y0, int ring, ByteBuffer data, int sFrom, int sTo) {
        int tile = 2 * ring, sliceBytes = 16 * 16 * 4;
        for (int s = sFrom; s <= sTo; s++) {
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
        // Natural Ground reads a few texture pages back before the floors are drawn
        if (clear) {
            try {
                NaturalGround.renderThread();
            } catch (Throwable t) {
                if (drawError == null) drawError = "natural ground: " + t;
            }
        }
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
        if (!(deferred || sway || atmos || draining) || rc.chunk == null || rc.depth == null) return;
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
            GpuTimer.chunkDraw();
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
                locHeight = GL20.glGetUniformLocation(prog, "PZOHeight");
                locSun = GL20.glGetUniformLocation(prog, "PZOSun");
                locWet = GL20.glGetUniformLocation(prog, "PZOWet");
                locBend = GL20.glGetUniformLocation(prog, "PZOBend");
                locTrailMarks = GL20.glGetUniformLocation(prog, "PZOTrailMarks");
                locBeams = GL20.glGetUniformLocation(prog, "PZOBeams");
                locBeamInfo = GL20.glGetUniformLocation(prog, "PZOBeamInfo");
                locFogField = GL20.glGetUniformLocation(prog, "PZOFogField");
                locFogInfo = GL20.glGetUniformLocation(prog, "PZOFogInfo");
                locGSnow = GL20.glGetUniformLocation(prog, "PZOGroundSnow");
                locGSnow2 = GL20.glGetUniformLocation(prog, "PZOGroundSnow2");
                locSky = GL20.glGetUniformLocation(prog, "PZOSky");
                locSunCache = GL20.glGetUniformLocation(prog, "PZOSunCache");
                locSunCacheInfo = GL20.glGetUniformLocation(prog, "PZOSunCacheInfo");
                locCloudCache = GL20.glGetUniformLocation(prog, "PZOCloudCache");
                locCloudInfo = GL20.glGetUniformLocation(prog, "PZOCloudInfo");
                SkyCache.sunWanted = locSunCache >= 0 && locSunCacheInfo >= 0;
                SkyCache.cloudWanted = locCloudCache >= 0 && locCloudInfo >= 0;
                locFogCache = GL20.glGetUniformLocation(prog, "PZOFogCache");
                locFogCacheInfo = GL20.glGetUniformLocation(prog, "PZOFogCacheInfo");
                SkyCache.fogWanted = locFogCache >= 0 && locFogCacheInfo >= 0;
                locTune0 = GL20.glGetUniformLocation(prog, "PZOTune0");
                locTune1 = GL20.glGetUniformLocation(prog, "PZOTune1");
                locTune2 = GL20.glGetUniformLocation(prog, "PZOTune2");
                locSnowCache = GL20.glGetUniformLocation(prog, "PZOSnowCache");
                locSnowCacheInfo = GL20.glGetUniformLocation(prog, "PZOSnowCacheInfo");
                SkyCache.snowWanted = locSnowCache >= 0 && locSnowCacheInfo >= 0;
                GroundSnow.shaderReady = locGSnow >= 0;
                FogField.wanted = locFogField >= 0 && locFogInfo >= 0;
                // The height map feeds sun shadows and ground snow (its flags and walls for drifts)
                sunWanted = (locHeight >= 0 && (locSun >= 0 || locGSnow >= 0)) || SkyCache.sunWanted;
                // Footprints and grass share the trail texture but are asked for apart, so neither card gathers for the other
                TrailMap.chunkWanted = locTrail >= 0 && locTrailMarks >= 0;
                TrailMap.grassWanted = locTrail >= 0 && locBend >= 0;
                LightBeams.wanted = locBeams >= 0 && locBeamInfo >= 0;
                LightBeams.invalidate();
                windowsWanted = locWindows >= 0;
                lightShaderReady = locLit >= 0;
                swayShaderReady = locWind >= 0;
                // Fog, cloud shadows, snow and trails only read the light map (outdoors flag, light, chunk placement)
                atmosShaderReady = locAtmos >= 0 || locClouds >= 0 || locTrail >= 0 || locWindows >= 0 || locSun >= 0 || locWet >= 0
                    || LightBeams.wanted;
                snowShaderReady = locSnow >= 0;
                for (float[] s : sent) java.util.Arrays.fill(s, Float.NaN);
            }
            if (locC < 0) return;
            float[] k = tune;
            u4(S_TUNE0, locTune0, k[0], k[1], k[2], k[3]);
            u4(S_TUNE1, locTune1, k[4], k[5], k[6], k[7]);
            u4(S_TUNE2, locTune2, k[8], k[9], k[10], k[11]);
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
                u4(S_SNOW, locSnow, 0f, 0f, 0f, 0f);
                u4(S_GSNOW, locGSnow, 0f, 0f, 0f, 0f);
                u4(S_SUN, locSun, 0f, 0f, 0f, 0f);
                u4(S_SUNCACHE, locSunCacheInfo, 1f, 1f, 0f, SkyCache.LEVELS);
                u4(S_CLOUDCACHE, locCloudInfo, 1f, 1f, 0f, 0f);
                u4(S_FOGCACHE, locFogCacheInfo, 1f, 1f, 0f, 0f);
                u4(S_SNOWCACHE, locSnowCacheInfo, 1f, 0f, 0f, 0f);
                u4(S_BEND, locBend, 0f, 0f, 0f, 0f);
                LightBeams.upload(locBeams, locBeamInfo, false, 0f, 0f, 0f, 0f, 0f);
                return;
            }
            chunkDrawsValid++;
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId[p]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            u4(S_A, locA, info[0], info[1], info[2], info[3]);
            u4(S_B, locB, info[4], info[5], info[6], 0f);
            u4(S_C, locC, info[8], BASE_LEVEL, LEVELS, 1f);
            u4(S_LIT, locLit, (deferred || draining) && Boolean.TRUE.equals(unlitByDepth.get(texd.tex1)) ? 1f : 0f, 0f, 0f, 0f);
            u4(S_WIND, locWind, windX, windY, sway ? windStrength : 0f, clock);
            u4(S_ATMOS, locAtmos, atmos ? fogIntensity : 0f, rainIntensity, dayLight, atmos ? 1f : 0f);
            u4(S_FOGWIND, locFogWind, windX, windY, windStrength, clock);
            u4(S_CLOUDS, locClouds, atmos ? cloudIntensity : 0f, dayLight, cloudOffX, cloudOffY);
            u4(S_SNOW, locSnow, atmos ? snowCover : 0f, 0f, 0f, 0f);
            boolean hOk = (locHeight >= 0 || SkyCache.sunWanted) && heightTex[p] != 0 && sunHeightsOn;
            if (hOk && locHeight >= 0) {
                GL13.glActiveTexture(GL13.GL_TEXTURE4);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, heightTex[p]);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL20.glUniform1i(locHeight, 4);
            }
            if (locSun >= 0) u4(S_SUN, locSun, sunX, sunY, sunRise, hOk && atmos ? sunVis : 0f);
            if (locSunCacheInfo >= 0) {
                int st = SkyCache.sunTexture(p);
                GL13.glActiveTexture(GL13.GL_TEXTURE10);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, st);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                if (locSunCache >= 0) GL20.glUniform1i(locSunCache, 10);
                boolean sOk = st != 0 && SkyCache.sunRing(p) == (int) info[8];
                u4(S_SUNCACHE, locSunCacheInfo, info[8], SkyCache.sunDetail(p), sOk ? 1f : 0f, SkyCache.LEVELS);
            }
            if (locCloudInfo >= 0) {
                int ct = SkyCache.cloudTexture(p);
                GL13.glActiveTexture(GL13.GL_TEXTURE11);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, ct);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                if (locCloudCache >= 0) GL20.glUniform1i(locCloudCache, 11);
                u4(S_CLOUDCACHE, locCloudInfo, info[8], 1f, ct != 0 ? 1f : 0f, 0f);
            }
            if (locFogCacheInfo >= 0) {
                int ft = SkyCache.fogTexture(p);
                GL13.glActiveTexture(GL13.GL_TEXTURE12);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, ft);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                if (locFogCache >= 0) GL20.glUniform1i(locFogCache, 12);
                boolean fOk = ft != 0 && SkyCache.fogRing(p) == (int) info[8];
                u4(S_FOGCACHE, locFogCacheInfo, info[8], SkyCache.FOG_LAYER_TOP, fOk ? 1f : 0f, 0f);
            }
            if (locSnowCacheInfo >= 0) {
                int nt = SkyCache.snowTexture(p);
                GL13.glActiveTexture(GL13.GL_TEXTURE13);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, nt);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                if (locSnowCache >= 0) GL20.glUniform1i(locSnowCache, 13);
                boolean nOk = nt != 0 && SkyCache.snowRing(p) == (int) info[8];
                u4(S_SNOWCACHE, locSnowCacheInfo, info[8], 0f, nOk ? 1f : 0f, 0f);
            }
            if (locGSnow >= 0) {
                // No height map yet means no flags to say where snow lies: draw none rather than snow everywhere
                u4(S_GSNOW, locGSnow, hOk && atmos ? GroundSnow.depth : 0f, GroundSnow.fresh, worldWindX * windStrength, worldWindY * windStrength);
                // zw: toward the sun on the ground, scaled by how much direct sun there is (0 overcast or at night)
                float vis = sunHeightsOn ? sunVis : 0f;
                u4(S_GSNOW2, locGSnow2, clock, dayLight, sunX * vis, sunY * vis);
            }
            u4(S_SKY, locSky, clock, leafDensity, GroundSnow.melt, dayLight);
            u4(S_WET, locWet, atmos ? wetness : 0f, dayLight, rainIntensity, atmos ? 1f : 0f);
            u4(S_MARKS, locTrailMarks, 1f, 0f, 0f, 0f);
            u4(S_BEND, locBend, sway ? 1f : 0f, 0f, 0f, 0f);
            LightBeams.upload(locBeams, locBeamInfo, atmos, info[4], info[5], fogIntensity, rainIntensity, 1f - 0.85f * dayLight);
            if (locFogField >= 0) {
                int ft = FogField.texture();
                GL13.glActiveTexture(GL13.GL_TEXTURE6);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, ft);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL20.glUniform1i(locFogField, 6);
                u4(S_FOG, locFogInfo, FogField.fieldOx, FogField.fieldOy, ft != 0 ? 1f : 0f, FogField.HEIGHT);
            }
            // Windows glow from dusk: full below a quarter daylight, none above 0.6
            u4(S_WINDOWS, locWindows, atmos ? Math.max(0f, Math.min(1f, (0.6f - dayLight) / 0.35f)) : 0f, 0f, 0f, atmos ? 1f : 0f);
        } catch (Throwable t) {
            if (drawError == null) drawError = t.toString();
        }
    }

    // Last values sent per uniform; most stay the same from one chunk draw to the next (about 100 draws a frame)
    private static final int S_A = 0, S_B = 1, S_C = 2, S_LIT = 3, S_WIND = 4, S_ATMOS = 5, S_FOGWIND = 6, S_CLOUDS = 7, S_SNOW = 8,
        S_TRAIL = 9, S_WINDOWS = 10, S_SUN = 11, S_WET = 12, S_BEND = 13, S_MARKS = 14, S_FOG = 15, S_GSNOW = 16, S_GSNOW2 = 17,
        S_SKY = 18, S_SUNCACHE = 19, S_CLOUDCACHE = 20, S_FOGCACHE = 21, S_SNOWCACHE = 22, S_TUNE0 = 23, S_TUNE1 = 24, S_TUNE2 = 25;
    private static final float[][] sent = new float[26][4];

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
        return RebakeQueue.stateText() + ";lighting=" + (deferred ? 1 : 0) + ";draining=" + (draining ? 1 : 0) + ";lightShader=" + (lightShaderReady ? 1 : 0) + ";sway=" + (sway ? 1 : 0)
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
     * "chunk=ModA,ModB;splash=ModC;haze=", from ZombieBuddyCompat's stand-downs. Empty values when nothing stands down.
     */
    static String conflictText() {
        java.util.Set<String> chunk = new java.util.TreeSet<>(), splash = new java.util.TreeSet<>(), haze = new java.util.TreeSet<>();
        for (String line : ZombieBuddyCompat.standDownLines()) {
            boolean isChunk = false;
            for (String h : CHUNK_HOOKS) if (line.startsWith("PixelLight " + h + ":")) isChunk = true;
            boolean isSplash = line.startsWith("PuddlesShader splash uniforms:") || line.startsWith("IsoPuddles chunk texture record");
            boolean isHaze = line.startsWith("WeatherShader screen pass uniforms:");
            if (!isChunk && !isSplash && !isHaze) continue;
            // "...: also patched by ModA (Class.method), ModB (Class.method)"
            int at = line.indexOf("also patched by ");
            if (at < 0) continue;
            java.util.regex.Matcher m = CONFLICT_MOD.matcher(line.substring(at + "also patched by ".length()));
            while (m.find()) (isChunk ? chunk : isSplash ? splash : haze).add(m.group(1).trim());
        }
        return "chunk=" + String.join(",", chunk) + ";splash=" + String.join(",", splash) + ";haze=" + String.join(",", haze);
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
        // What the weather effects are fed, so "fog / beams / runoff do nothing" can be told apart from a broken pass
        float weather = Math.max(fogIntensity, rainIntensity * 0.5f) + 0.12f * (1f - dayLight);
        PZOLogger.info(String.format(java.util.Locale.ROOT,
            "[PZOE fx] fog %.2f rain %.2f daylight %.2f clouds %.2f sun %.2f -> fog weather %.2f; tune fog density %.2f height %.2f glow %.2f beams %.2f haze %.2f; %s; %s; %s; %s; rebake %s",
            fogIntensity, rainIntensity, dayLight, cloudIntensity, sunVis, weather, tune[4], tune[5], tune[6], tune[9], tune[10], FogField.stateText(),
            LightBeams.stateText(), RainRunoff.stateText(), SkyCache.stateText(), RebakeQueue.stateText()));
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
        String self = "com/pzoptimizer/pzoe/PixelLight";
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
