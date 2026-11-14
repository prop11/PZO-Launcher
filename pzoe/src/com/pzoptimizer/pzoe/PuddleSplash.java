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

    private static boolean groundIsWet(IsoGridSquare sq) {
        if (sq.z > 0 || !sq.isOutside()) return false;
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

    // Lights for puddle reflections, game thread gathers, render thread places them per level:
    // [count, then x, y, level, height (levels), r, g, b, brightness per light]
    static final int MAX_LIGHTS = 16;
    private static volatile float[] lights = {0f};
    private static volatile long lightsAskedNanos;
    private static final float[] lightBest = new float[MAX_LIGHTS * 9];

    // Render thread
    private static final int[] locations = new int[11];

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
            double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
            lastNanos = now;
            if (zombie.GameTime.isGamePaused()) dt = 0;
            clock += dt;
            // Wraps well before float precision gets coarse; a wrap restarts the rain ripple phases, nothing else
            if (clock > 4096.0) clock -= 4096.0;

            boolean gather = now - splashUploadNanos < 1_000_000_000L;
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
            if (!gather) ringUsed = 0;
            if (!gather && !trails) {
                if (!walkers.isEmpty()) walkers.clear();
                if (!wheels.isEmpty()) wheels.clear();
            } else if (dt > 0) {
                gather((float) dt, gather);
            }
            if (trails) {
                zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
                TrailMap.frame(dt, cm.getPrecipitationIsSnow() && cm.getPrecipitationIntensity() > 0.05f);
            }
            publish();
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

    private static void gather(float dt, boolean splashAsked) {
        IsoPlayer[] players = IsoPlayer.players;
        if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return;
        for (IsoMovingObject o : IsoWorld.instance.currentCell.getObjectList()) {
            if (o instanceof IsoGameCharacter chr && near(chr, players)) step(chr, dt, splashAsked);
        }
        walkersSeen = walkers.size();
        if (TrailMap.active() || splashAsked) {
            for (zombie.vehicles.BaseVehicle v : IsoWorld.instance.currentCell.getVehicles()) {
                if (near(v.getX(), v.getY(), players)) wheels(v, splashAsked);
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

    private static boolean snowAt(IsoGridSquare sq) {
        return sq.isOutside() && IsoWorld.instance.currentCell.gridSquareIsSnow(sq.x, sq.y, sq.z);
    }

    // Shoes pick up water from puddles or soaked ground, not from a drizzle-damp road
    private static boolean feetGetWet(IsoGridSquare sq) {
        if (sq.z > 0 || !sq.isOutside()) return false;
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
            if (TrailMap.chunkWanted) {
                if (snowAt(sq)) TrailMap.stamp(cx, cy, sx, sy, halfLen, halfWid, 1f, 0f, 0f);
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

    private static boolean near(IsoGameCharacter chr, IsoPlayer[] players) {
        float x = chr.getX(), y = chr.getY();
        for (IsoPlayer p : players) {
            if (p == null) continue;
            float dx = p.getX() - x, dy = p.getY() - y;
            if (dx * dx + dy * dy < RANGE_SQ) return true;
        }
        return false;
    }

    private static void step(IsoGameCharacter chr, float dt, boolean splashAsked) {
        float x = chr.getX(), y = chr.getY();
        float[] st = walkers.get(chr);
        if (st == null) {
            // lastX, lastY, travelled, foot, wet steps left, last grass press
            walkers.put(chr, new float[] {x, y, 0f, 0f, 0f, -100f});
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
        if (streamsAsked && chr instanceof IsoPlayer && dt > 0f && d / dt > 0.6f) {
            IsoGridSquare wsq = chr.getCurrentSquare();
            if (wsq != null) path(walkerPaths, chr, x, y, chr.getZ(), standingWater(wsq), 0.12f, 0.55f);
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
        if (splashAsked && wet) {
            float strength = Math.max(0.35f, Math.min(1f, 0.25f + speed * 0.17f));
            add(fx, fy, chr.getZ(), strength);
        }
        if (TrailMap.chunkWanted) {
            // A boot-sized oval along the walking line: pressed into snow, or wet on a dry floor for ~10 steps
            if (snowAt(sq)) {
                TrailMap.stamp(fx, fy, mx, my, 0.13f, 0.07f, 0.9f, 0f, 0f);
            } else if (feetGetWet(sq)) {
                st[4] = 10f;
            } else if (st[4] > 0f) {
                TrailMap.stamp(fx, fy, mx, my, 0.12f, 0.06f, 0f, 0.35f * st[4] / 10f, 0f);
                st[4] -= 1f;
            }
        }
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
        return sq.z <= 0 && sq.isOutside() && IsoPuddles.getInstance().getPuddlesSizeFinalValue() > 0.02f;
    }

    static final int MAX_STREAMS = 24, STREAM_POINTS = 10, HEAD = 9, PT = 5;
    static final float STREAM_LIFE = 1.8f, STREAM_STEP = 0.2f;
    private static final WeakHashMap<Object, float[]> vehiclePaths = new WeakHashMap<>(), walkerPaths = new WeakHashMap<>();
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

    private static int segments(WeakHashMap<Object, float[]> paths, float[] out, int count, float now) {
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
            for (int k = 0; k < n && count < MAX_STREAMS; k++) {
                int o = HEAD + Math.floorMod(head - k, STREAM_POINTS) * PT;
                float aAge = now - p[o + 3];
                if (aAge < 0f || aAge > STREAM_LIFE) break;
                float ax = p[o], ay = p[o + 1];
                if (bWet > 0.5f && p[o + 4] > 0.5f && p[o + 2] == bz && (ax != bx || ay != by)) {
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
            if (count >= MAX_STREAMS) break;
        }
        return count;
    }

    private static final float[] streamScratch = new float[1 + MAX_STREAMS * 9];

    private static void add(float x, float y, float z, float strength) {
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

    private static void publish() {
        float now = (float) clock;
        streamsAsked = System.nanoTime() - streamUploadNanos < 1_000_000_000L;
        if (streamsAsked) {
            int c = segments(vehiclePaths, streamScratch, 0, now);
            c = segments(walkerPaths, streamScratch, c, now);
            float[] out = new float[1 + c * 9];
            System.arraycopy(streamScratch, 1, out, 1, c * 9);
            out[0] = c;
            streams = out;
        } else {
            if (streams.length > 1) streams = new float[] {0f};
            vehiclePaths.clear();
            walkerPaths.clear();
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
                TrailMap.puddleWanted = loc[3] >= 0;
            }
            if (loc[3] >= 0) trailUniforms(loc, z);
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
                if ((int) snap[at + 2] != z) continue;
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
