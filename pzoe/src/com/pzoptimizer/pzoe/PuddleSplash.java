package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.ShaderPatchHost;
import com.pzoptimizer.PZOLogger;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;

/**
 * Footstep splashes in puddles, plus a smooth clock for the puddle shader. Only feeds shader patches that ask for it
 * (PZO Enhancements' Better Puddles); with no such patch live nothing is gathered.
 *
 * A patched puddle shader declares any of these, and gets them every draw:
 *   uniform float PZOTime;          seconds, stops while paused (the game's WTime only moves once per game minute)
 *   uniform vec4 PZOSplash[32];     xy = splash point in the same space as calculateFragCoord() * WOffset.zw,
 *                                   z = age in seconds, w = strength 0..1
 *   uniform float PZOSplashCount;
 *   uniform vec4 PZOLights[16];     xy = the light's ground point in the PZOSplash space, z = its height in screen
 *                                   pixels, w = brightness
 *   uniform vec4 PZOLightCol[16];   rgb = colour
 *   uniform float PZOLightCount;    lights on the level being drawn
 *   uniform vec4 PZOStream[48];     wakes, 2 per path segment: (end a xy, end b xy) in the PZOSplash space, then
 *                                   (age at a, age at b, half width in squares, strength)
 *   uniform float PZOStreamCount;   segments on the level being drawn
 *
 * Game thread (start of IsoPuddles.clearThreadData, once a frame): every character within 30 tiles of a local player
 * that has walked a stride since its last step, on an outdoor ground-level square while the ground is wet or
 * puddled at all, leaves a splash point at its feet, left and right foot in turn.
 * The newest 32 still alive are published. Render thread (end of PuddlesShader.updatePuddlesParams): the points on
 * the level being drawn are turned into screen space with the render camera, so they stay put on the ground.
 *
 * Wakes (the stream idea is from fmac's Atmospherics): vehicles and players moving through standing water leave a path
 * of points, one every STREAM_STEP seconds, plus a live end where they are now. Each piece of path spreads ripples out
 * to both sides from where it was when it was laid, so behind a car the rings open into a V. Only gathered while the
 * puddle shader declares PZOStream. Vehicles go first, then players, until the 24 segments are used.
 *
 * Grass underfoot: every character and moving vehicle near player 0 presses the grass it stands in (TrailMap's A
 * channel) while the chunk shader asks for it.
 */
public final class PuddleSplash {

    static final int MAX = 32;
    static final float LIFE = 1.6f;
    static final float RANGE_SQ = 30f * 30f;
    // The game's CPU puddle estimate (getPuddlesInGround) follows a different path from the shader's water test, and
    // with PZO's ground-only puddle mode the two disagree, so steps through visible water got dropped. Any step on a
    // wet outdoor square counts instead; the shader shows the rings at full strength only where it draws water.
    static final float WET = 0.05f;

    // Frozen puddles or freezing rain's glaze (PZOE weather): ice doesn't splash, wake or wet a shoe
    private static boolean frozen() {
        return com.pzoptimizer.pzoe.weather.WeatherEvents.puddleFreeze() > 0.6f || com.pzoptimizer.pzoe.weather.WeatherEvents.ice() > 0.4f;
    }

    static boolean groundIsWet(IsoGridSquare sq) {
        if (sq.z > 0 || !sq.isOutside() || frozen()) return false;
        IsoPuddles p = IsoPuddles.getInstance();
        return p.getPuddlesSizeFinalValue() > 0.01f || p.getWetGroundFinalValue() > WET;
    }

    public static final AtomicLong splashes = new AtomicLong();

    // Game thread
    private static final float[] ring = new float[MAX * 5]; // x, y, z, birth, strength
    private static int ringNext, ringUsed;
    private static final WeakHashMap<IsoGameCharacter, float[]> walkers = new WeakHashMap<>(); // lastX, lastY, travelled, foot, wet steps, grass time
    private static int lastFrame = Integer.MIN_VALUE;
    private static long lastNanos;
    private static double clock;
    private static long lastReportNanos, reportedSplashes, reportedUploads;
    // Characters tracked at the last gather, for the settings card
    private static volatile int walkersSeen;

    // Published for the render thread: [clock, count, then x, y, z, age, strength per point]
    private static volatile float[] published = {0f, 0f};
    private static volatile long splashUploadNanos;
    private static volatile long waterRipplesAskedNanos;
    private static volatile float[] waterStreams = {0f};
    private record WaterImpact(float x, float y, float z, float strength) {}
    private static final java.util.concurrent.ArrayBlockingQueue<WaterImpact> waterImpacts =
        new java.util.concurrent.ArrayBlockingQueue<>(32);

    public static float[] waterRippleSnapshot() {
        waterRipplesAskedNanos = System.nanoTime();
        return published;
    }

    public static float[] waterWakeSnapshot() {
        return waterStreams;
    }

    public static void waterImpact(IsoGridSquare square, float x, float y, float strength) {
        if (System.nanoTime() - waterRipplesAskedNanos < 1_000_000_000L && wetBody(square, x, y)) {
            waterImpacts.offer(new WaterImpact(x, y, square.z, strength));
        }
    }

    public static void waterSplash(IsoGridSquare square, boolean big, float dx, float dy) {
        waterImpact(square, square.x + 0.5f + dx, square.y + 0.5f + dy, big ? 0.86f : 0.46f);
    }

    // Lights for puddle reflections, game thread gathers, render thread places them per level:
    // [count, then x, y, level, height (levels), r, g, b, brightness per light]
    static final int MAX_LIGHTS = 16;
    private static volatile float[] lights = {0f};
    private static volatile long lightsAskedNanos;
    private static final float[] lightBest = new float[MAX_LIGHTS * 9];

    public static float[] outdoorLightSnapshot() {
        lightsAskedNanos = System.nanoTime();
        return lights;
    }

    // Render thread
    private static final int[] locations = new int[23];
    // Scene reflection strength (modApi puddleScene), and whether a puddle shader asks for it (render thread)
    static volatile float sceneStrength;
    static volatile boolean sceneWanted;
    private static volatile long waterSceneAskedNanos;

    public static boolean waterSceneAsked() {
        return System.nanoTime() - waterSceneAskedNanos < 1_000_000_000L;
    }

    // The trail map on unit 5 (the game uses 0-3 here), its size, and the world square at the puddle shader's screen
    // origin for this level: screen x = (x - y) * 64, y = (x + y - 6z) * 32 for the ground of level z
    private static void trailUniforms(int[] loc, int z) {
        int tex = TrailMap.texture(), ringTexels = TrailMap.ringTexels();
        int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE5);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL13.glActiveTexture(prevActive);
        GL20.glUniform1i(loc[3], 5);
        if (loc[4] >= 0) GL20.glUniform4f(loc[4], Math.max(1, ringTexels), TrailMap.TEXELS_PER_SQUARE, 0f, tex != 0 && ringTexels > 0 ? 1f : 0f);
        if (loc[5] >= 0) {
            org.joml.Vector4f off = IsoPuddles.getInstance().getShaderOffset();
            double ox = off.x / 128.0 + off.y / 64.0 + 3.0 * z, oy = off.y / 64.0 - off.x / 128.0 + 3.0 * z;
            GL20.glUniform2f(loc[5], (float) ox, (float) oy);
        }
    }
    // PZOE Gore's blood map on unit 15 (blood in a puddle tints the water), with this level's screen origin in squares
    private static void goreUniforms(int[] loc, int z) {
        int tex = GoreMap.texture(), rt = GoreMap.ringTexels();
        int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE15);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL13.glActiveTexture(prevActive);
        GL20.glUniform1i(loc[12], 15);
        boolean ok = tex != 0 && rt > 0 && com.pzoptimizer.pzoe.gore.GoreCore.mapDraws && z >= 0 && z < GoreMap.LEVELS;
        if (loc[13] >= 0) GL20.glUniform4f(loc[13], Math.max(1, rt), Math.max(1, GoreMap.texelsPerSquare()), GoreMap.BANDS, ok ? 1f : 0f);
        if (loc[14] >= 0) {
            org.joml.Vector4f off = IsoPuddles.getInstance().getShaderOffset();
            double ox = off.x / 128.0 + off.y / 64.0 + 3.0 * z, oy = off.y / 64.0 - off.x / 128.0 + 3.0 * z;
            GL20.glUniform4f(loc[14], (float) ox, (float) oy, z, 0f);
        }
    }

    // Scene reflection: the height map on unit 6, last frame's image on unit 9 (both rebound per chunk draw by their
    // owners), this level's origin in squares as for gore, and the history's camera
    private static void sceneUniforms(int[] loc, int z, int player) {
        TemporalPass.historyAskedNs = System.nanoTime();
        sceneUniforms(loc, z, player, IsoPuddles.getInstance().getShaderOffset(), sceneStrength);
    }

    public static void waterSceneUniforms(int[] loc, int player) {
        waterSceneAskedNanos = System.nanoTime();
        if (player == 0) TemporalPass.waterHistoryAskedNs = waterSceneAskedNanos;
        sceneUniforms(loc, 0, player, zombie.iso.IsoWater.getInstance().getShaderOffset(), 0.58f);
    }

    private static void sceneUniforms(int[] loc, int z, int player, org.joml.Vector4f off, float strength) {
        float[] pr = player == 0 ? TemporalPass.projection() : null;
        int hist = pr != null ? TemporalPass.history() : 0;
        int ht = PixelLight.heightTexture(player), ring = PixelLight.lightRing(player);
        boolean ok = hist != 0 && ht != 0 && ring > 0 && strength > 0f;
        int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, ok ? ht : 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE9);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, ok ? hist : 0);
        SceneReflectionActors.bind(loc[21], loc[22], ok);
        GL13.glActiveTexture(prevActive);
        if (loc[15] >= 0) GL20.glUniform1i(loc[15], 6);
        if (loc[16] >= 0) GL20.glUniform1i(loc[16], 9);
        double ox = off.x / 128.0 + off.y / 64.0 + 3.0 * z, oy = off.y / 64.0 - off.x / 128.0 + 3.0 * z;
        GL20.glUniform4f(loc[17], (float) ox, (float) oy, z, ok ? 1f : 0f);
        if (!ok) return;
        if (loc[18] >= 0) GL20.glUniform4f(loc[18], pr[0], pr[1], pr[2], pr[3]);
        if (loc[19] >= 0) GL20.glUniform4f(loc[19], pr[4], pr[5], pr[6], ring);
        if (loc[20] >= 0) GL20.glUniform4f(loc[20], pr[7], pr[8], pr[9], strength);
    }

    private static int locProgram = -1;
    private static long locCheckedNs;
    private static final AtomicLong uploadsWithPoints = new AtomicLong();
    private static final FloatBuffer buf = ByteBuffer.allocateDirect(MAX * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private PuddleSplash() {}

    /** Start of IsoPuddles.clearThreadData(), main thread, once per player per frame. */
    public static void frame(IsoPuddles puddles) {
        try {
            int f = zombie.iso.IsoCamera.frameState.frameCount;
            if (f == lastFrame) return;
            lastFrame = f;
            long now = System.nanoTime();
            if (com.pzoptimizer.pzoe.water.WaterWaves.needsSun(now)) {
                com.pzoptimizer.pzoe.water.WaterWaves.setSun(PixelLight.sunNow());
            }
            com.pzoptimizer.pzoe.water.WaterWaves.updateEnvironment(now, GroundSnow.active() ? GroundSnow.depth : 0f);
            double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
            lastNanos = now;
            if (zombie.GameTime.isGamePaused()) dt = 0;
            clock += dt;
            // Wraps well before float precision gets coarse; a wrap restarts the rain ripple phases, nothing else
            if (clock > 4096.0) clock -= 4096.0;

            SceneReflectionActors.frame(now - TemporalPass.historyAskedNs < 1_000_000_000L
                || now - waterSceneAskedNanos < 1_000_000_000L);
            boolean gather = now - splashUploadNanos < 1_000_000_000L;
            boolean gatherWater = now - waterRipplesAskedNanos < 1_000_000_000L;
            if (gatherWater && dt > 0 && !waterImpacts.isEmpty()) {
                for (int i = 0; i < 32; i++) {
                    WaterImpact impact = waterImpacts.poll();
                    if (impact == null) break;
                    add(impact.x(), impact.y(), impact.z(), -impact.strength());
                }
            } else if (!gatherWater && !waterImpacts.isEmpty()) {
                waterImpacts.clear();
            }
            if (now - lastReportNanos > 15_000_000_000L) {
                lastReportNanos = now;
                long s = splashes.get(), u = uploadsWithPoints.get();
                if (zombie.core.Core.debug) {
                    PZOLogger.info("[PuddleSplash] last 15 s: " + (s - reportedSplashes) + " splashes, " + (u - reportedUploads)
                        + " draws with splash points, " + walkers.size() + " walkers tracked, shader asks for splashes: " + gather
                        + ", puddle draws " + uploadCalls + ", uniforms time=" + locations[0] + " splash=" + locations[1]);
                }
                reportedSplashes = s;
                reportedUploads = u;
            }
            if (now - lightsAskedNanos < 1_000_000_000L) gatherLights();
            else if (lights.length > 1) lights = new float[] {0f};
            boolean trails = TrailMap.active();
            if (!gather && !gatherWater) ringUsed = 0;
            if (!gather && !gatherWater && !trails) {
                if (!walkers.isEmpty()) walkers.clear();
                if (!wheels.isEmpty()) wheels.clear();
            } else if (dt > 0) {
                gather((float) dt, gather, gatherWater);
            }
            if (trails) {
                zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
                TrailMap.frame(dt, cm.getPrecipitationIsSnow() && cm.getPrecipitationIntensity() > 0.05f);
            }
            com.pzoptimizer.pzoe.water.WaterRippleField.update((float) dt, now);
            publish(gatherWater);
        } catch (Throwable t) {
            // Never let this break the frame; worst case no splashes
        }
    }

    /**
     * Outdoor lights near the players for puddle reflections: the cell's lamppost list (street and porch lamps, fires,
     * fireplaces, burning people, lightbars) plus vehicle headlights. Lights inside buildings never reach a puddle.
     * Street lamps hang about a level up, fires and headlights sit low.
     */
    private static void gatherLights() {
        zombie.iso.IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        IsoPlayer[] players = IsoPlayer.players;
        int n = 0;
        java.util.Stack<zombie.iso.IsoLightSource> list = cell.getLamppostPositions();
        for (int i = 0, size = list.size(); i < size; i++) {
            zombie.iso.IsoLightSource l;
            try {
                l = list.get(i);
            } catch (ArrayIndexOutOfBoundsException e) {
                break;
            }
            if (l == null || !l.active || l.localToBuilding != null || l.radius <= 0) continue;
            float x = l.x + 0.5f, y = l.y + 0.5f;
            float d = distSq(x, y, players);
            if (d < 0) continue;
            IsoGridSquare sq = cell.getGridSquare(l.x, l.y, l.z);
            if (l.hydroPowered && (sq == null || !(sq.hasGridPower() || sq.haveElectricity()))) continue;
            float height = sq != null && (sq.getFire() != null || sq.hasFireObject()) ? 0.25f : 0.9f;
            n = offerLight(n, x, y, l.z, height, l.r, l.g, l.b, Math.min(1f, 0.3f + l.radius / 12f), d);
        }
        org.joml.Vector3f p = wheelPos;
        for (zombie.vehicles.BaseVehicle v : cell.getVehicles()) {
            if (!v.hasHeadlights() || !v.getHeadlightsOn()) continue;
            float d = distSq(v.getX(), v.getY(), players);
            if (d < 0) continue;
            zombie.scripting.objects.VehicleScript script = v.getScript();
            if (script == null) continue;
            org.joml.Vector3f ext = script.getExtents(), com = script.getCenterOfMassOffset();
            for (int side = -1; side <= 1; side += 2) {
                v.getWorldPos(side * ext.x * 0.35f, 0f, com.z + ext.z * 0.5f, p);
                n = offerLight(n, p.x, p.y, v.getZ(), 0.15f, 1f, 0.95f, 0.8f, 0.8f, d);
            }
        }
        float[] out = new float[1 + n * 8];
        out[0] = n;
        for (int i = 0; i < n; i++) System.arraycopy(lightBest, i * 9, out, 1 + i * 8, 8);
        lights = out;
    }

    private static float distSq(float x, float y, IsoPlayer[] players) {
        float best = -1f;
        for (IsoPlayer pl : players) {
            if (pl == null) continue;
            float dx = pl.getX() - x, dy = pl.getY() - y, d = dx * dx + dy * dy;
            if (d < RANGE_SQ && (best < 0 || d < best)) best = d;
        }
        return best;
    }

    // Keeps the nearest MAX_LIGHTS; slot layout x, y, level, height, r, g, b, brightness, distance
    private static int offerLight(int n, float x, float y, float z, float h, float r, float g, float b, float bright, float d) {
        int at;
        if (n < MAX_LIGHTS) {
            at = n++;
        } else {
            at = -1;
            float far = d;
            for (int i = 0; i < MAX_LIGHTS; i++) {
                if (lightBest[i * 9 + 8] > far) {
                    far = lightBest[i * 9 + 8];
                    at = i;
                }
            }
            if (at < 0) return n;
        }
        int o = at * 9;
        lightBest[o] = x;
        lightBest[o + 1] = y;
        lightBest[o + 2] = (float) Math.floor(z);
        lightBest[o + 3] = h;
        lightBest[o + 4] = r;
        lightBest[o + 5] = g;
        lightBest[o + 6] = b;
        lightBest[o + 7] = bright;
        lightBest[o + 8] = d;
        return n;
    }

    private static final FloatBuffer lightBuf = ByteBuffer.allocateDirect(MAX_LIGHTS * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer lightColBuf = ByteBuffer.allocateDirect(MAX_LIGHTS * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    // Render thread, puddle program bound: the lights on level z, placed like the splash points
    private static void uploadLights(int[] loc, int z) {
        lightsAskedNanos = System.nanoTime();
        float[] snap = lights;
        org.joml.Vector4f off = IsoPuddles.getInstance().getShaderOffset();
        float levelPx = IsoUtils.YToScreen(0f, 0f, 0f, 0) - IsoUtils.YToScreen(0f, 0f, 1f, 0);
        lightBuf.clear();
        lightColBuf.clear();
        int count = 0, n = (int) snap[0];
        for (int i = 0; i < n && count < MAX_LIGHTS; i++) {
            int at = 1 + i * 8;
            if ((int) snap[at + 2] != z) continue;
            float wx = snap[at] - 3f * z, wy = snap[at + 1] - 3f * z;
            lightBuf.put(IsoUtils.XToScreen(wx, wy, 0f, 0) - off.x);
            lightBuf.put(IsoUtils.YToScreen(wx, wy, 0f, 0) - off.y);
            lightBuf.put(snap[at + 3] * levelPx);
            lightBuf.put(snap[at + 7]);
            lightColBuf.put(snap[at + 4]).put(snap[at + 5]).put(snap[at + 6]).put(1f);
            count++;
        }
        if (count > 0) {
            lightBuf.flip();
            lightColBuf.flip();
            GL20.glUniform4fv(loc[6], lightBuf);
            if (loc[7] >= 0) GL20.glUniform4fv(loc[7], lightColBuf);
        }
        if (loc[8] >= 0) GL20.glUniform1f(loc[8], count);
    }

    private static final FloatBuffer streamBuf = ByteBuffer.allocateDirect(PuddleSplash.MAX_STREAMS * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    // Render thread, puddle program bound: the wake segments on level z, placed like the splash points
    private static void uploadStreams(int[] loc, int z) {
        streamUploadNanos = System.nanoTime();
        float[] snap = streams;
        int n = (int) snap[0], count = 0;
        if (n > 0) {
            org.joml.Vector4f off = IsoPuddles.getInstance().getShaderOffset();
            streamBuf.clear();
            for (int i = 0; i < n; i++) {
                int at = 1 + i * 9;
                if ((int) snap[at + 4] != z) continue;
                float ax = snap[at] - 3f * z, ay = snap[at + 1] - 3f * z, bx = snap[at + 2] - 3f * z, by = snap[at + 3] - 3f * z;
                streamBuf.put(IsoUtils.XToScreen(ax, ay, 0f, 0) - off.x).put(IsoUtils.YToScreen(ax, ay, 0f, 0) - off.y);
                streamBuf.put(IsoUtils.XToScreen(bx, by, 0f, 0) - off.x).put(IsoUtils.YToScreen(bx, by, 0f, 0) - off.y);
                streamBuf.put(snap[at + 5]).put(snap[at + 6]).put(snap[at + 7]).put(snap[at + 8]);
                count++;
            }
            if (count > 0) {
                streamBuf.flip();
                GL20.glUniform4fv(loc[9], streamBuf);
            }
        }
        if (loc[10] >= 0) GL20.glUniform1f(loc[10], count);
    }

    // This gather's time step, for the vehicle ploughs
    private static float frameDt;

    private static void gather(float dt, boolean splashAsked, boolean waterAsked) {
        frameDt = dt;
        IsoPlayer[] players = IsoPlayer.players;
        if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return;
        for (IsoMovingObject o : IsoWorld.instance.currentCell.getObjectList()) {
            if (o instanceof IsoGameCharacter chr && near(chr, players)) step(chr, dt, splashAsked, waterAsked);
        }
        walkersSeen = walkers.size();
        boolean wheelEffects = TrailMap.active() || splashAsked;
        if (wheelEffects || waterAsked) {
            for (zombie.vehicles.BaseVehicle v : IsoWorld.instance.currentCell.getVehicles()) {
                float x = v.getX(), y = v.getY(), z = v.getZ();
                if (!near(x, y, players)) continue;
                if (wheelEffects) wheels(v, splashAsked);
                if (waterAsked && Math.abs(v.getCurrentSpeedKmHour()) > 2f && z >= 0f && z < 1f) {
                    IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare((int) Math.floor(x), (int) Math.floor(y), 0);
                    boolean wet = wetBody(square, x, y);
                    if (wet || waterPaths.containsKey(v)) path(waterPaths, v, x, y, z, wet, 0.75f, 1f);
                }
            }
        }
    }

    private static boolean near(float x, float y, IsoPlayer[] players) {
        for (IsoPlayer p : players) {
            if (p == null) continue;
            float dx = p.getX() - x, dy = p.getY() - y;
            if (dx * dx + dy * dy < RANGE_SQ) return true;
        }
        return false;
    }

    static final String FOOTSTEP2 = "zombie/audio/parameters/ParameterFootstepMaterial2";

    static boolean patchWaterSplash(ClassNode node) {
        MethodNode method = AsmSupport.findMethod(node, "startWaterSplash", "(ZFF)V");
        if (method == null) return false;
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof com.pzoptimizer.shaded.asm.tree.FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETSTATIC || !field.owner.endsWith("/FishSplashSoundManager")
                || !field.name.equals("instance")) continue;
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new VarInsnNode(Opcodes.ILOAD, 1));
            call.add(new VarInsnNode(Opcodes.FLOAD, 2));
            call.add(new VarInsnNode(Opcodes.FLOAD, 3));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/PuddleSplash", "waterSplash",
                "(Lzombie/iso/IsoGridSquare;ZFF)V", false));
            method.instructions.insertBefore(field, call);
            return true;
        }
        return false;
    }

    /**
     * The FootstepMaterial2 FMOD value (0 none, 2 shallow puddle, 3 deep puddle; glass, garbage and leaves left alone).
     * Vanilla only splashes above getPuddlesInGround 0.1, which is the full reflective water level, so stepping through
     * the darker water drawn below it was silent. Any drawn water splashes here (above 0 the shader draws water), deep
     * from 0.25. Ice and PZOE snow lying on it don't splash.
     */
    public static float footMaterial2(float v, IsoGameCharacter c) {
        if (v != 0f && v != 2f && v != 3f) return v;
        try {
            IsoGridSquare sq = c == null ? null : c.getCurrentSquare();
            if (sq == null || sq.z > 0) return v;
            if (frozen() || (GroundSnow.active() && com.pzoptimizer.pzoe.weather.WeatherSnow.coverAt(c.getX(), c.getY()) >= 0.5f)) return 0f;
            float p = sq.getPuddlesInGround();
            return p > 0.25f ? 3f : p > 0.01f ? 2f : 0f;
        } catch (Throwable t) {
            return v;
        }
    }

    /** ParameterFootstepMaterial2.calculateCurrentValue: its result goes through footMaterial2. */
    static boolean patchFootstep2(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "calculateCurrentValue", "()F");
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.FRETURN) continue;
            InsnList in = new InsnList();
            in.add(new VarInsnNode(Opcodes.ALOAD, 0));
            in.add(new com.pzoptimizer.shaded.asm.tree.FieldInsnNode(Opcodes.GETFIELD, FOOTSTEP2, "character", "Lzombie/characters/IsoGameCharacter;"));
            in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/PuddleSplash", "footMaterial2",
                "(FLzombie/characters/IsoGameCharacter;)F", false));
            m.instructions.insertBefore(insn, in);
            n++;
        }
        return n > 0;
    }

    private static boolean naturalFloor(IsoGridSquare sq) {
        zombie.iso.IsoObject floor = sq.getFloor();
        return floor != null && floor.sprite != null && NaturalGround.isNaturalName(floor.sprite.getName());
    }

    static boolean snowAt(IsoGridSquare sq) {
        return sq.isOutside() && IsoWorld.instance.currentCell.gridSquareIsSnow(sq.x, sq.y, sq.z);
    }

    // Per foot, not per square: in thin snow a print only goes where a patch is drawn
    private static boolean snowUnderFoot(IsoGridSquare sq, float fx, float fy) {
        if (GroundSnow.active()) return sq.z == 0 && com.pzoptimizer.pzoe.weather.WeatherSnow.coverAt(fx, fy) >= 0.35f;
        return snowAt(sq);
    }

    // Shoes pick up water from puddles or soaked ground, not from a drizzle-damp road
    private static boolean feetGetWet(IsoGridSquare sq) {
        if (sq.z > 0 || !sq.isOutside() || frozen()) return false;
        IsoPuddles p = IsoPuddles.getInstance();
        return p.getPuddlesSizeFinalValue() > 0.05f || p.getWetGroundFinalValue() > 0.3f;
    }

    // Per vehicle, per wheel: last x, y, last stamp x, y, spray distance
    private static final WeakHashMap<zombie.vehicles.BaseVehicle, float[]> wheels = new WeakHashMap<>();
    private static final org.joml.Vector3f wheelPos = new org.joml.Vector3f();

    /**
     * Each wheel of a moving vehicle draws its track as capsules from the last stamp to where it is now, so tracks stay
     * unbroken at any speed: a rut in snow, a darker line on wet ground, a wake in standing water; rear wheels throw
     * spray (splash points) at speed on wet ground.
     */
    private static void wheels(zombie.vehicles.BaseVehicle v, boolean splashAsked) {
        zombie.scripting.objects.VehicleScript script = v.getScript();
        if (script == null) return;
        int n = script.getWheelCount();
        if (n <= 0) return;
        float[] st = wheels.get(v);
        boolean first = st == null || st.length != n * 5;
        if (first) wheels.put(v, st = new float[n * 5]);
        float kmh = Math.abs(v.getCurrentSpeedKmHour());
        zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
        if (kmh >= 1f && (TrailMap.grassWanted || streamsAsked)) {
            org.joml.Vector3f ext = script.getExtents();
            if (TrailMap.grassWanted && v.getZ() < 1f && TrailMap.inGrassRange(v.getX(), v.getY())) {
                org.joml.Vector3f fwd = v.getForwardVector(wheelPos);
                TrailMap.stampGrass(v.getX(), v.getY(), fwd.x, fwd.z, ext.z * 0.5f + 0.35f, ext.x * 0.5f + 0.35f);
            }
            if (streamsAsked) {
                IsoGridSquare vsq = cell.getGridSquare((int) Math.floor(v.getX()), (int) Math.floor(v.getY()), (int) Math.floor(v.getZ()));
                if (vsq != null) {
                    path(vehiclePaths, v, v.getX(), v.getY(), v.getZ(), standingWater(vsq), ext.x * 0.5f,
                        Math.min(1f, 0.35f + kmh / 50f));
                }
            }
        }
        if (TrailMap.snowWanted || TrailMap.chunkWanted) plough(v, script, kmh);
        for (int i = 0; i < n; i++) {
            zombie.scripting.objects.VehicleScript.Wheel wh = script.getWheel(i);
            if (wh == null) continue;
            org.joml.Vector3f w = v.getWorldPos(wh.offset.x, 0f, wh.offset.z, wheelPos);
            int o = i * 5;
            if (first || kmh < 2f) {
                st[o] = st[o + 2] = w.x;
                st[o + 1] = st[o + 3] = w.y;
                continue;
            }
            float mx = w.x - st[o], my = w.y - st[o + 1];
            st[o] = w.x;
            st[o + 1] = w.y;
            float sx = w.x - st[o + 2], sy = w.y - st[o + 3];
            float len = (float) Math.sqrt(sx * sx + sy * sy);
            if (len > 3f) {
                // Teleport or sync snap
                st[o + 2] = w.x;
                st[o + 3] = w.y;
                continue;
            }
            if (len < 0.3f) continue;
            IsoGridSquare sq = cell.getGridSquare((int) Math.floor(w.x), (int) Math.floor(w.y), (int) Math.floor(w.z));
            float cx = (w.x + st[o + 2]) * 0.5f, cy = (w.y + st[o + 3]) * 0.5f;
            st[o + 2] = w.x;
            st[o + 3] = w.y;
            if (sq == null) continue;
            float halfLen = len * 0.5f + 0.04f, halfWid = Math.max(0.08f, wh.width * 0.35f);
            boolean wet = groundIsWet(sq);
            if (TrailMap.chunkWanted || TrailMap.snowWanted) {
                // PZOE's deep snow counts as snow too, so ruts print with only the ground snow card on
                if (snowAt(sq) || com.pzoptimizer.pzoe.weather.WeatherSnow.depthAt(w.x, w.y, w.z) > 0.01f) {
                    TrailMap.stamp(cx, cy, sx, sy, halfLen, halfWid, 1f, 0f, 0f);
                }
                else if (wet) TrailMap.stamp(cx, cy, sx, sy, halfLen, halfWid, 0f, 0.45f, 0f);
            }
            if (TrailMap.puddleWanted && wet && IsoPuddles.getInstance().getPuddlesSizeFinalValue() > 0.01f) {
                TrailMap.stamp(cx, cy, sx, sy, halfLen, halfWid * 1.8f, 0f, 0f, 1f);
            }
            if (splashAsked && TrailMap.puddleWanted && !wh.front && wet && kmh > 15f) {
                st[o + 4] += len;
                if (st[o + 4] >= 0.6f) {
                    st[o + 4] = 0f;
                    add(w.x, w.y, w.z, Math.min(1f, kmh / 60f));
                }
            }
        }
    }

    // Per vehicle: where its body trench was last stamped
    private static final WeakHashMap<zombie.vehicles.BaseVehicle, float[]> ploughs = new WeakHashMap<>();

    /**
     * Snow deeper than the car sits off the ground: the body pushes through it and leaves a trench its own width, the
     * snow shoved aside into berms (the ground snow shader finds those from the trench), and packs it down for
     * whoever wades or drives along it next. Shallower snow only gets the wheel ruts.
     */
    private static void plough(zombie.vehicles.BaseVehicle v, zombie.scripting.objects.VehicleScript script, float kmh) {
        float x = v.getX(), y = v.getY();
        // last stamp x, y, snow load on the bumper 0..1, seconds stood still, last direction x, y
        float[] st = ploughs.get(v);
        if (st == null) {
            ploughs.put(v, new float[] {x, y, 0f, 0f, 1f, 0f});
            return;
        }
        float halfWid = Math.max(0.5f, script.getExtents().x * 0.5f);
        org.joml.Vector3f fw = v.getForwardVector(wheelPos);
        float fl = (float) Math.sqrt(fw.x * fw.x + fw.z * fw.z);
        float fx = fl > 1e-4f ? fw.x / fl : st[4], fy = fl > 1e-4f ? fw.z / fl : st[5];
        float front = script.getExtents().z * 0.5f + 0.3f;
        boolean reversing = v.getCurrentSpeedKmHour() < -0.5f;
        // Stopped for most of a second or backing off: the pile stays where it is
        if (kmh < 1f) st[3] += (float) frameDt;
        else st[3] = 0f;
        if (st[2] > 0.05f && (st[3] > 0.7f || reversing)) {
            com.pzoptimizer.pzoe.weather.WeatherSnow.dropPile(v, x + st[4] * front, y + st[5] * front, st[4], st[5], halfWid * 1.1f, pileHeight(st[2]));
            st[2] = 0f;
        }
        float dx = x - st[0], dy = y - st[1];
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist > 3f || kmh < 1f || v.getZ() >= 0.5f) {
            st[0] = x;
            st[1] = y;
            return;
        }
        if (dist < 0.3f) return;
        float mx = (x + st[0]) * 0.5f, my = (y + st[1]) * 0.5f;
        st[0] = x;
        st[1] = y;
        st[4] = fx;
        st[5] = fy;
        float h = com.pzoptimizer.pzoe.weather.WeatherSnow.depthAt(mx, my, v.getZ());
        if (h >= 0.05f) {
            float r = Math.min(1f, 0.45f + (h - 0.05f) / 0.15f * 0.55f);
            TrailMap.stamp(mx, my, dx, dy, dist * 0.5f + 0.12f, halfWid, r, 0f, 0f);
            com.pzoptimizer.pzoe.weather.WeatherSnow.plow(mx, my, dx, dy, dist * 0.5f, halfWid);
        }
        // Going forward through deep snow the bumper gathers a load; some always spills to the sides, much more at
        // speed, where the snow is thrown clear instead (the wheels' spray)
        if (!reversing) {
            float gain = h >= 0.05f ? dist * Math.min(1f, (h - 0.05f) / 0.15f + 0.3f) * 0.3f : 0f;
            float spill = (float) Math.exp(-dist / (kmh > 30f ? 2f : 7f));
            st[2] = Math.min(1f, st[2] * spill + gain);
        }
        com.pzoptimizer.pzoe.weather.WeatherSnow.livePile(v, x + fx * front, y + fy * front, fx, fy, halfWid * 1.1f,
            st[2] > 0.05f ? pileHeight(st[2]) : 0f);
    }

    private static float pileHeight(float load) {
        return 0.03f + 0.09f * load;
    }

    // Footprints' Tracked-in mud (modApi trailsMud)
    static volatile boolean trackedMud;

    private static boolean near(IsoGameCharacter chr, IsoPlayer[] players) {
        float x = chr.getX(), y = chr.getY();
        for (IsoPlayer p : players) {
            if (p == null) continue;
            float dx = p.getX() - x, dy = p.getY() - y;
            if (dx * dx + dy * dy < RANGE_SQ) return true;
        }
        return false;
    }

    private static void step(IsoGameCharacter chr, float dt, boolean splashAsked, boolean waterAsked) {
        float x = chr.getX(), y = chr.getY();
        float[] st = walkers.get(chr);
        if (st == null) {
            // lastX, lastY, travelled, foot, wet steps left, last grass press
            walkers.put(chr, new float[] {x, y, 0f, 0f, 0f, -100f, Float.NaN, 0f, 0f});
            return;
        }
        float mx = x - st[0], my = y - st[1];
        st[0] = x;
        st[1] = y;
        float d = (float) Math.sqrt(mx * mx + my * my);
        if (d > 2f) {
            // Teleport, vehicle exit, sync snap
            st[2] = 0f;
            return;
        }
        if (TrailMap.grassWanted) grass(chr, x, y, mx, my, d, st);
        if (TrailMap.snowWanted || TrailMap.chunkWanted) trench(chr, x, y, mx, my, st);
        if (streamsAsked && chr instanceof IsoPlayer && dt > 0f && d / dt > 0.6f) {
            IsoGridSquare wsq = chr.getCurrentSquare();
            if (wsq != null) path(walkerPaths, chr, x, y, chr.getZ(), standingWater(wsq), 0.12f, 0.55f);
        }
        if (waterAsked && d > dt * 1.4f && !chr.isDead() && chr.getVehicle() == null) {
            boolean wet = wetBody(chr.getCurrentSquare(), x, y);
            if (wet || waterPaths.containsKey(chr)) {
                path(waterPaths, chr, x, y, chr.getZ(), wet, 0.18f, Math.min(0.8f, d / dt * 0.15f));
            }
        }
        if (d < 0.0005f) {
            // Standing still: the first step after it splashes a little sooner
            st[2] = Math.max(st[2], 0.3f);
            return;
        }
        float speed = d / dt;
        float stride = speed > 4.5f ? 0.95f : speed > 2.4f ? 0.75f : 0.55f;
        st[2] += d;
        if (st[2] < stride) return;
        st[2] = Math.min(st[2] - stride, stride);
        IsoGridSquare sq = chr.getCurrentSquare();
        if (sq == null) return;
        // Left and right foot, a little to each side of the walking line
        st[3] = st[3] > 0.5f ? 0f : 1f;
        float side = (st[3] > 0.5f ? 0.09f : -0.09f) / d;
        float fx = x - my * side, fy = y + mx * side;
        boolean wet = groundIsWet(sq);
        boolean inWater = waterAsked && wetBody(sq, fx, fy);
        if (inWater || (splashAsked && wet)) {
            float strength = Math.max(0.35f, Math.min(1f, 0.25f + speed * 0.17f));
            // The shared ring uses signed strength to route water steps to the body-of-water shader.
            add(fx, fy, chr.getZ(), inWater ? -strength : strength);
        }
        // A boot-sized oval along the walking line: pressed into snow (drawn by Better Ground Snow or Footprints), or
        // wet on a dry floor for ~10 steps (Footprints)
        if ((TrailMap.chunkWanted || TrailMap.snowWanted) && snowUnderFoot(sq, fx, fy)) {
            TrailMap.stamp(fx, fy, mx, my, 0.13f, 0.07f, 0.9f, 0f, 0f);
        } else if (TrailMap.chunkWanted) {
            if (trackedMud && st.length > 8 && sq.isOutside() && groundIsWet(sq) && naturalFloor(sq)) {
                // Wet dirt: the shoes come away muddy. Wet grass and plants less so (they used not to count at all, and
                // most ways into a house cross a lawn)
                float load = DustTrails.natural(sq) ? 18f : 10f;
                st[4] = st[8] > 0.5f ? Math.max(st[4], load) : load;
                st[8] = 1f;
            } else if (feetGetWet(sq)) {
                if (st.length > 8 && st[8] > 0.5f) {
                    // Wet paving wears the mud off over about 20 steps, real puddles faster. Washing it off on the first
                    // wet step meant nobody ever got mud indoors: after rain every road counts as wet.
                    st[4] -= IsoPuddles.getInstance().getPuddlesSizeFinalValue() > 0.25f ? 1.5f : 0.4f;
                    if (st[4] <= 10f) {
                        st[8] = 0f;
                        st[4] = 14f;
                    }
                } else {
                    st[4] = Math.max(st[4], trackedMud ? 14f : 10f);
                }
            } else if (trackedMud && sq.isOutside() && !frozen() && com.pzoptimizer.pzoe.weather.WeatherLook.precipitation() > 0.25f) {
                // Out in the rain the shoes get wet anyway, without leaving a mark on the wet ground
                st[4] = Math.max(st[4], 8f);
            } else if (st[4] > 0f) {
                // Muddy prints are stamped above 0.36 in G, which the shader tints brown; wet ones below
                boolean mud = st.length > 8 && st[8] > 0.5f;
                float k = st[4] / (mud ? 18f : trackedMud ? 14f : 10f);
                TrailMap.stamp(fx, fy, mx, my, 0.16f, 0.08f, 0f, mud ? 0.6f + 0.4f * Math.min(1f, k) : 0.35f * Math.min(1f, k), 0f);
                st[4] -= 1f;
                if (st[4] <= 0f && st.length > 8) st[8] = 0f;
            }
        }
    }

    /**
     * Deep snow (PZOE weather): a body-wide trench pushed through it as a character wades, a capsule from the last
     * stamp to here every 0.15 squares, and a hole where it stands still. Deeper snow, deeper trench (R).
     */
    private static void trench(IsoGameCharacter chr, float x, float y, float mx, float my, float[] st) {
        if (chr.getZ() >= 0.5f || chr.getVehicle() != null || chr.isDead()) return;
        float h = com.pzoptimizer.pzoe.weather.WeatherSnow.trenchDepth(chr);
        if (h < 0.04f) return;
        float r = Math.min(1f, 0.45f + h / 0.3f * 0.55f);
        if (Float.isNaN(st[6])) {
            st[6] = x;
            st[7] = y;
            TrailMap.stamp(x, y, 1f, 0f, 0.24f, 0.24f, r, 0f, 0f);
            return;
        }
        float dx = x - st[6], dy = y - st[7];
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.15f) return;
        if (dist < 1.5f) TrailMap.stamp((x + st[6]) * 0.5f, (y + st[7]) * 0.5f, dx, dy, dist * 0.5f + 0.18f, 0.26f, r, 0f, 0f);
        st[6] = x;
        st[7] = y;
    }

    /**
     * Grass pressed where a character stands: every frame while it moves, every 0.15 s while it stands, so the grass
     * stays down under it and springs back once it walks off. Oval along the way it walks, a bit wider than the body.
     */
    private static void grass(IsoGameCharacter chr, float x, float y, float mx, float my, float d, float[] st) {
        if (chr.getZ() >= 1f || chr.getVehicle() != null || chr.isDead() || !TrailMap.inGrassRange(x, y)) return;
        double since = clock - st[5];
        if (d < 0.02f && since >= 0 && since < 0.15) return;
        st[5] = (float) clock;
        TrailMap.stampGrass(x, y, mx, my, 0.8f, 0.65f);
    }

    // Standing water to make a wake in: rain-soaked ground alone has none
    private static boolean standingWater(IsoGridSquare sq) {
        return sq.z <= 0 && sq.isOutside() && !frozen() && IsoPuddles.getInstance().getPuddlesSizeFinalValue() > 0.02f;
    }

    private static boolean wetBody(IsoGridSquare sq, float x, float y) {
        return com.pzoptimizer.pzoe.water.WaterSurface.depthAt(sq, x, y) >= 0.45f;
    }

    static final int MAX_STREAMS = 24, STREAM_POINTS = 10, HEAD = 9, PT = 5;
    static final float STREAM_LIFE = 1.8f, STREAM_STEP = 0.2f;
    private static final WeakHashMap<Object, float[]> vehiclePaths = new WeakHashMap<>(), walkerPaths = new WeakHashMap<>();
    private static final WeakHashMap<Object, float[]> waterPaths = new WeakHashMap<>();
    private static volatile long streamUploadNanos;
    private static boolean streamsAsked;
    // Published: [count, then ax, ay, bx, by, level, age a, age b, half width, strength per segment]
    private static volatile float[] streams = {0f};

    /**
     * Where a mover is now, and a point on its path every STREAM_STEP seconds. Path layout: count, newest index,
     * current x, y, level, time, half width, strength, wet; then x, y, level, time, wet per point.
     */
    private static void path(WeakHashMap<Object, float[]> paths, Object mover, float x, float y, float z, boolean wet, float halfW, float strength) {
        float[] p = paths.get(mover);
        if (p == null) paths.put(mover, p = new float[HEAD + STREAM_POINTS * PT]);
        float now = (float) clock;
        p[2] = x;
        p[3] = y;
        p[4] = (float) Math.floor(z);
        p[5] = now;
        p[6] = halfW;
        p[7] = strength;
        p[8] = wet ? 1f : 0f;
        int n = (int) p[0], head = (int) p[1];
        float since = n == 0 ? 1e9f : now - p[HEAD + head * PT + 3];
        if (n > 0 && since >= 0f && since < STREAM_STEP) return;
        head = n == 0 ? 0 : (head + 1) % STREAM_POINTS;
        int o = HEAD + head * PT;
        p[o] = x;
        p[o + 1] = y;
        p[o + 2] = p[4];
        p[o + 3] = now;
        p[o + 4] = p[8];
        p[1] = head;
        if (n < STREAM_POINTS) p[0] = n + 1;
    }

    private static int segments(WeakHashMap<Object, float[]> paths, float[] out, int count, int limit, float now) {
        boolean water = paths == waterPaths;
        java.util.Iterator<float[]> it = paths.values().iterator();
        while (it.hasNext()) {
            float[] p = it.next();
            float seen = now - p[5];
            // Gone quiet (parked, out of range, unloaded) or across the clock wrap: nothing left to show
            if (seen < 0f || seen > STREAM_LIFE) {
                it.remove();
                continue;
            }
            int n = (int) p[0], head = (int) p[1];
            // Newest first: the live end, then back along the path while it's young enough
            float bx = p[2], by = p[3], bz = p[4], bAge = seen, bWet = p[8];
            int perMover = water ? Math.min(limit, count + 2) : limit;
            for (int k = 0; k < n && count < perMover; k++) {
                int o = HEAD + Math.floorMod(head - k, STREAM_POINTS) * PT;
                float aAge = now - p[o + 3];
                if (aAge < 0f || aAge > STREAM_LIFE) break;
                float ax = p[o], ay = p[o + 1];
                float dx = ax - bx, dy = ay - by;
                if (bWet > 0.5f && p[o + 4] > 0.5f && p[o + 2] == bz && (ax != bx || ay != by)
                    && (!water || dx * dx + dy * dy < 9f)) {
                    int at = 1 + count * 9;
                    out[at] = ax;
                    out[at + 1] = ay;
                    out[at + 2] = bx;
                    out[at + 3] = by;
                    out[at + 4] = bz;
                    out[at + 5] = aAge;
                    out[at + 6] = bAge;
                    out[at + 7] = p[6];
                    out[at + 8] = p[7];
                    count++;
                }
                bx = ax;
                by = ay;
                bz = p[o + 2];
                bAge = aAge;
                bWet = p[o + 4];
            }
            if (count >= limit && !water) break;
        }
        return count;
    }

    private static final float[] streamScratch = new float[1 + MAX_STREAMS * 9];

    private static void add(float x, float y, float z, float strength) {
        if (strength < 0f && z >= 0f && z < 1f) {
            com.pzoptimizer.pzoe.water.WaterRippleField.impulse(x, y, -strength);
        }
        int at = ringNext * 5;
        ring[at] = x;
        ring[at + 1] = y;
        ring[at + 2] = (float) Math.floor(z);
        ring[at + 3] = (float) clock;
        ring[at + 4] = strength;
        ringNext = (ringNext + 1) % MAX;
        if (ringUsed < MAX) ringUsed++;
        splashes.incrementAndGet();
    }

    private static void publish(boolean waterAsked) {
        float now = (float) clock;
        streamsAsked = System.nanoTime() - streamUploadNanos < 1_000_000_000L;
        if (streamsAsked) {
            int c = segments(vehiclePaths, streamScratch, 0, MAX_STREAMS, now);
            c = segments(walkerPaths, streamScratch, c, MAX_STREAMS, now);
            float[] out = new float[1 + c * 9];
            System.arraycopy(streamScratch, 1, out, 1, c * 9);
            out[0] = c;
            streams = out;
        } else {
            if (streams.length > 1) streams = new float[] {0f};
            vehiclePaths.clear();
            walkerPaths.clear();
        }
        if (waterAsked) {
            int count = segments(waterPaths, streamScratch, 0, com.pzoptimizer.pzoe.water.WaterWaves.MAX_WAKES, now);
            if (count > 0 || waterStreams[0] != 0f) {
                float[] snapshot = java.util.Arrays.copyOf(streamScratch, 1 + count * 9);
                snapshot[0] = count;
                waterStreams = snapshot;
            }
        } else if (!waterPaths.isEmpty() || waterStreams[0] != 0f) {
            waterPaths.clear();
            waterStreams = new float[] {0f};
        }
        int alive = 0;
        for (int i = 0; i < ringUsed; i++) {
            float age = now - ring[i * 5 + 3];
            if (age >= 0f && age < LIFE) alive++;
        }
        float[] out = new float[2 + alive * 5];
        out[0] = now;
        out[1] = alive;
        int k = 2;
        for (int i = 0; i < ringUsed; i++) {
            int at = i * 5;
            float age = now - ring[at + 3];
            if (age < 0f || age >= LIFE) continue;
            out[k++] = ring[at];
            out[k++] = ring[at + 1];
            out[k++] = ring[at + 2];
            out[k++] = age;
            out[k++] = ring[at + 4];
        }
        published = out;
    }

    /** key=value pairs for the Footstep Splashes card, via PZOApi.getChunkEffects(). */
    static String stateText() {
        boolean asked = System.nanoTime() - splashUploadNanos < 1_000_000_000L;
        return "splashShader=" + (asked ? 1 : 0) + ";splashes=" + splashes.get() + ";splashDraws=" + uploadsWithPoints.get()
            + ";walkers=" + walkersSeen + ";puddleDraws=" + uploadCalls + ";locTime=" + locations[0] + ";locSplash=" + locations[1]
            + ";streams=" + (int) streams[0] + ";locStream=" + locations[9];
    }

    private static volatile long uploadCalls;

    // Once per puddle program: whether the driver linked it, and how much of it is live (0 = the game destroyed it)
    private static void logProgram(zombie.core.opengl.Shader shader, int prog) {
        try {
            String what = prog == 0 ? "id 0 (compile, link or validate failed, the game destroyed it)"
                : "id " + prog + " linked=" + GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) + " validated=" + GL20.glGetProgrami(prog, GL20.GL_VALIDATE_STATUS)
                + " activeUniforms=" + GL20.glGetProgrami(prog, GL20.GL_ACTIVE_UNIFORMS) + " PZOFreeze=" + GL20.glGetUniformLocation(prog, "PZOFreeze")
                + " PuddlesParams=" + GL20.glGetUniformLocation(prog, "PuddlesParams");
            PZOLogger.info("[PuddleSplash] puddle program " + shader.getName() + ": " + what);
        } catch (Throwable t) {
            PZOLogger.info("[PuddleSplash] puddle program " + prog + ": " + t);
        }
    }

    /** End of PuddlesShader.updatePuddlesParams(userId, z), render thread, with the puddle program bound. */
    public static void upload(zombie.core.opengl.Shader shader, int userId, int z) {
        try {
            // Without a mod's shader patch (no PZO Enhancements) there's nothing to feed
            if (!ShaderPatchHost.anyLive()) return;
            uploadCalls++;
            // Re-checked on program change and 4 times a second anyway: a settings change rebuilds the shader and
            // drivers often hand back the same id, so a lookup cached by id alone sticks (the 2026-11-12 no-splash bug)
            int prog = shader.getID();
            long nowNs = System.nanoTime();
            int[] loc = locations;
            if (prog != locProgram || nowNs - locCheckedNs > 250_000_000L) {
                if (prog != locProgram) logProgram(shader, prog);
                locProgram = prog;
                locCheckedNs = nowNs;
                loc[0] = GL20.glGetUniformLocation(prog, "PZOTime");
                loc[1] = GL20.glGetUniformLocation(prog, "PZOSplash");
                loc[2] = GL20.glGetUniformLocation(prog, "PZOSplashCount");
                loc[3] = GL20.glGetUniformLocation(prog, "PZOTrail");
                loc[4] = GL20.glGetUniformLocation(prog, "PZOTrailInfo");
                loc[5] = GL20.glGetUniformLocation(prog, "PZOTrailOrigin");
                loc[6] = GL20.glGetUniformLocation(prog, "PZOLights");
                loc[7] = GL20.glGetUniformLocation(prog, "PZOLightCol");
                loc[8] = GL20.glGetUniformLocation(prog, "PZOLightCount");
                loc[9] = GL20.glGetUniformLocation(prog, "PZOStream");
                loc[10] = GL20.glGetUniformLocation(prog, "PZOStreamCount");
                loc[11] = GL20.glGetUniformLocation(prog, "PZOFreeze");
                loc[12] = GL20.glGetUniformLocation(prog, "PZOGore");
                loc[13] = GL20.glGetUniformLocation(prog, "PZOGoreInfo");
                loc[14] = GL20.glGetUniformLocation(prog, "PZOGoreOrigin");
                loc[15] = GL20.glGetUniformLocation(prog, "PZOHeight");
                loc[16] = GL20.glGetUniformLocation(prog, "PZOHist");
                loc[17] = GL20.glGetUniformLocation(prog, "PZOReflOrigin");
                loc[18] = GL20.glGetUniformLocation(prog, "PZOReflRef");
                loc[19] = GL20.glGetUniformLocation(prog, "PZOReflU");
                loc[20] = GL20.glGetUniformLocation(prog, "PZOReflV");
                loc[21] = GL20.glGetUniformLocation(prog, "PZOReflActors");
                loc[22] = GL20.glGetUniformLocation(prog, "PZOReflActorRegion");
                sceneWanted = loc[17] >= 0;
                TrailMap.puddleWanted = loc[3] >= 0;
            }
            if (loc[11] >= 0) GL20.glUniform1f(loc[11], com.pzoptimizer.pzoe.weather.WeatherEvents.puddleFreeze());
            if (loc[3] >= 0) trailUniforms(loc, z);
            if (loc[12] >= 0) goreUniforms(loc, z);
            if (loc[17] >= 0) sceneUniforms(loc, z, userId);
            if (loc[6] >= 0) uploadLights(loc, z);
            if (loc[9] >= 0) uploadStreams(loc, z);
            if (loc[0] < 0 && loc[1] < 0) return;
            float[] snap = published;
            if (loc[0] >= 0) GL20.glUniform1f(loc[0], snap[0]);
            if (loc[1] < 0) return;
            splashUploadNanos = System.nanoTime();
            org.joml.Vector4f off = IsoPuddles.getInstance().getShaderOffset();
            buf.clear();
            int count = 0, n = (int) snap[1];
            for (int i = 0; i < n && count < MAX; i++) {
                int at = 2 + i * 5;
                if ((int) snap[at + 2] != z || snap[at + 4] < 0f) continue;
                // Same placement as IsoPuddlesGeometry: a level's ground is drawn at x - 3z, y - 3z on screen level 0
                float wx = snap[at] - 3f * z, wy = snap[at + 1] - 3f * z;
                buf.put(IsoUtils.XToScreen(wx, wy, 0f, 0) - off.x);
                buf.put(IsoUtils.YToScreen(wx, wy, 0f, 0) - off.y);
                buf.put(snap[at + 3]);
                buf.put(snap[at + 4]);
                count++;
            }
            if (count > 0) {
                buf.flip();
                GL20.glUniform4fv(loc[1], buf);
                uploadsWithPoints.incrementAndGet();
            }
            if (loc[2] >= 0) GL20.glUniform1f(loc[2], count);
        } catch (Throwable t) {
            // A missing uniform or driver hiccup just means no splashes this draw
        }
    }

    static boolean patchShader(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "updatePuddlesParams", "(II)V");
        if (m == null) return false;
        int returns = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new VarInsnNode(Opcodes.ILOAD, 1));
            call.add(new VarInsnNode(Opcodes.ILOAD, 2));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/PuddleSplash", "upload",
                "(Lzombie/core/opengl/Shader;II)V", false));
            m.instructions.insertBefore(insn, call);
            returns++;
        }
        return returns > 0;
    }

    static boolean patchPuddles(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "clearThreadData", "()V");
        if (m == null) return false;
        AsmSupport.prependCall(m, "com/pzoptimizer/pzoe/PuddleSplash", "frame", "(Lzombie/iso/IsoPuddles;)V");
        return true;
    }
}
