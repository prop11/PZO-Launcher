package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.weather.WeatherLook;
import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.ShaderPatchHost;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoHeatSource;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.vehicles.BaseVehicle;

/**
 * Data for the final screen pass (screen.frag, WeatherShader): heat haze points and the time-of-day/weather grade.
 * Only feeds a patched screen shader that declares the uniforms (PZO Enhancements); with none live nothing is
 * gathered.
 *
 *   uniform vec4 PZOHaze[16];     xy = source base in the world image's UV, z = one square's width in screen pixels
 *                                 times the source's size, w = strength 0..1
 *   uniform float PZOHazeCount;
 *   uniform float PZOHazeTime;    seconds, stops while paused
 *   uniform vec4 PZOGrade;        golden hour, night, overcast, fog (0..1 each)
 *   uniform vec4 PZOGrade2;       rain, snow falling, 0, 1 = values are live
 *   uniform sampler2D PZOBloomTex; the world image again, with mipmaps made this frame (bloom reads blurred levels)
 *   uniform vec4 PZOBloom;        1 = live, night 0..1, 0, 0
 *   uniform vec4 PZOEye;          scene brightness for eye adaptation (EyeAdapt)
 *   uniform vec4 PZOSmoke[16], PZOSmokeInfo;   smoke columns over fires (SmokePlumes)
 *
 * Haze sources: the cell's heat sources (fires, fireplaces, barbecues, campfires: everything lit registers one),
 * burning characters, and vehicles with the engine running (behind the rear axle; vehicle scripts have no exhaust
 * point).
 *
 * Main thread (end of WeatherShader.startMainThread, per player): points are projected with that player's camera
 * and filed under a sequence number written into the TextureDraw's spare var 24. Render thread (end of
 * startRenderThread): the points filed under that number are uploaded, so they match the frame being drawn.
 */
public final class ScreenPass {

    static final int MAX = 16;
    static final float RANGE_SQ = 30f * 30f;
    static final int SEQ_VAR = 24;

    private record Filed(int seq, float zoom, float[] data, int worldTex, int frame, int player, float[] rect, SmokePlumes.Projection smoke, float[] ref, float[] breath, float[] moodle, float[] glass, float[] dust, float[] glare, float[] lightTrails,
        float[] fireflies, float[] flies, float[] eyes, float[] basis, float submerged, boolean singlePlayerView, float[] fireGlow, float[] impacts) {}

    // Main thread
    private static int lastFrame = Integer.MIN_VALUE;
    private static long lastNanos;
    private static double clock;
    private static int seq;
    // Nearest sources this frame: x, y, z, size (tiles), strength, squared distance
    private static int sourceCount;
    private static final float[] best = new float[MAX * 6];
    private static final float[] staticHeat = new float[MAX * 6];
    private static IsoCell heatCell;
    private static long heatSample;
    private static int staticHeatCount;
    private static Field heatField;
    private static boolean heatFieldTried;

    // Shared
    private static final AtomicReferenceArray<Filed> filed = new AtomicReferenceArray<>(8);
    private static volatile long askedNanos, gradeAskedNanos, bloomAskedNanos, eyeAskedNanos;
    private static volatile float bloomNight;
    private static volatile float published;
    private static volatile float[] grade = new float[8];

    // Render thread
    private static final int[] locations = new int[51];
    private static final FloatBuffer spriteBuf = ByteBuffer.allocateDirect(16 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer trailBuf = ByteBuffer.allocateDirect(VehicleLightTrails.MAX * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer impactBuf = ByteBuffer.allocateDirect(BulletImpacts.FX_MAX * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer fireGlowBuf = ByteBuffer.allocateDirect(FireGlow.MAX * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer glareBuf = ByteBuffer.allocateDirect(Glare.MAX * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer dustBuf = ByteBuffer.allocateDirect(DustTrails.MAX * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer breathBuf = ByteBuffer.allocateDirect(Breath.MAX * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    // World textures switched to mipmap filtering for bloom, put back when bloom goes away
    private static final java.util.Set<Integer> mipTextures = new java.util.HashSet<>();
    private static int lastMipFrame = Integer.MIN_VALUE;
    private static int locProgram = -1;
    private static long locCheckedNs;
    private static final FloatBuffer buf = ByteBuffer.allocateDirect(MAX * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer roomSmokeBuf = ByteBuffer.allocateDirect(SmokePlumes.MAX_ROOM_RECTS * 8 * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static volatile int smokeRoomRects;
    private static volatile long draws, drawsWithPoints;
    private static volatile int diffuseUnit = -1;
    private static volatile long diffuseRepairs;

    private ScreenPass() {}

    /** End of WeatherShader.startMainThread(texd, playerIndex), main thread. */
    public static void main(TextureDraw texd, int playerIndex) {
        try {
            if (!ShaderPatchHost.anyLive() || texd.vars == null || texd.vars.length <= SEQ_VAR) return;
            if (playerIndex < 0 || playerIndex >= 4) return;
            long now = System.nanoTime();
            int f = IsoCamera.frameState.frameCount;
            if (f != lastFrame) {
                lastFrame = f;
                double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
                lastNanos = now;
                if (zombie.GameTime.isGamePaused()) dt = 0;
                clock += dt;
                if (clock > 4096.0) clock -= 4096.0;
                published = (float) clock;
                sourceCount = 0;
                if (now - askedNanos < 1_000_000_000L) gather();
                if (now - gradeAskedNanos < 1_000_000_000L) grade();
            }
            int s = ++seq & 0xFFFFF;
            texd.vars[SEQ_VAR] = s;
            float zoom = Core.getInstance().getZoom(playerIndex);
            int worldTex = 0;
            float[] rect = null;
            boolean eye = now - eyeAskedNanos < 1_000_000_000L;
            boolean temporal = (TemporalPass.on || TemporalPass.historyAsked()) && playerIndex == 0;
            if (eye || temporal || now - bloomAskedNanos < 1_000_000_000L) {
                zombie.core.textures.TextureFBO fbo = Core.getInstance().getOffscreenBuffer();
                if (fbo != null && fbo.getTexture() != null) worldTex = fbo.getTexture().getID();
                bloomNight = zombie.iso.weather.ClimateManager.getInstance().getNightStrength();
            }
            if (eye && texd.col2 > 0 && texd.col3 > 0) {
                float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
                float w = IsoCamera.getScreenWidth(playerIndex), h = IsoCamera.getScreenHeight(playerIndex);
                rect = new float[] {left / texd.col2, top / texd.col3, (left + w) / texd.col2, (top + h) / texd.col3};
            }
            float[] ref = temporal ? reference(playerIndex, zoom) : null;
            SmokePlumes.Projection smoke = null;
            if (now - SmokePlumes.askedNanos < 1_000_000_000L) {
                SmokePlumes.frame();
                smoke = SmokePlumes.project(playerIndex, zoom, texd.col2, texd.col3);
            }
            float[] breath = null;
            if (now - Breath.askedNanos < 1_000_000_000L) {
                Breath.frame();
                breath = Breath.project(playerIndex, zoom, texd.col2, texd.col3);
            }
            float[] dust = null;
            if (now - DustTrails.askedNanos < 1_000_000_000L) {
                DustTrails.frame();
                dust = DustTrails.project(playerIndex, zoom, texd.col2, texd.col3);
            }
            if (playerIndex == 0) Lut.frame();
            float[] glare = null;
            if (now - Glare.askedNanos < 1_000_000_000L) {
                if (playerIndex == 0) Glare.frame();
                glare = Glare.project(playerIndex, zoom, texd.col2, texd.col3);
            }
            float[] lightTrails = now - VehicleLightTrails.askedNanos < 1_000_000_000L
                ? VehicleLightTrails.project(playerIndex, zoom, texd.col2, texd.col3) : null;
            float[] moodle = now - MoodleFx.askedNanos < 1_000_000_000L ? MoodleFx.sample(playerIndex) : null;
            float[] fireflies = null, flies = null, eyes = null;
            if (now - Fireflies.askedNanos < 1_000_000_000L) {
                if (playerIndex == 0) Fireflies.frame();
                fireflies = Fireflies.project(playerIndex, zoom, texd.col2, texd.col3);
            }
            // Corpses are gathered in PixelLight.frame (the rot stains share them)
            if (now - CorpseFlies.screenAskedNanos < 1_000_000_000L) flies = CorpseFlies.project(playerIndex, zoom, texd.col2, texd.col3);
            if (now - EyeShine.askedNanos < 1_000_000_000L) {
                if (playerIndex == 0) EyeShine.frame();
                eyes = EyeShine.project(playerIndex, zoom, texd.col2, texd.col3);
            }
            float[] basis = fireflies != null || flies != null ? basis(playerIndex, zoom, texd.col2, texd.col3) : null;
            float[] fireGlow = null;
            if (now - FireGlow.askedNanos < 1_000_000_000L) {
                if (playerIndex == 0) FireGlow.frame();
                fireGlow = FireGlow.project(playerIndex, zoom, texd.col2, texd.col3);
            }
            float[] impacts = now - BulletImpacts.fxAskedNanos < 1_000_000_000L
                ? BulletImpacts.projectFx(playerIndex, zoom, texd.col2, texd.col3) : null;
            filed.set(s & 7, new Filed(s, zoom, sourceCount == 0 ? null : project(playerIndex, zoom, texd.col2, texd.col3),
                worldTex, f, playerIndex, rect, smoke, ref, breath, moodle,
                now - Windscreen.askedNanos < 1_000_000_000L ? Windscreen.sample(playerIndex) : null, dust, glare, lightTrails,
                fireflies, flies, eyes, basis, com.pzoptimizer.pzoe.water.UnderwaterView.sample(playerIndex, now),
                IsoPlayer.numPlayers == 1, fireGlow, impacts));
        } catch (Throwable t) {
            // Worst case no haze this frame
        }
    }

    static float effectClock() {
        return published;
    }

    // Smooth 0..1..0 bump over [a, b] peaking at the middle
    private static float bump(float t, float a, float b) {
        if (t <= a || t >= b) return 0f;
        float x = (t - a) / (b - a);
        return 1f - (2f * x - 1f) * (2f * x - 1f);
    }

    private static void grade() {
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        if (cm == null || zombie.GameTime.getInstance() == null) return;
        float t = zombie.GameTime.getInstance().getTimeOfDay();
        float dawn = 6f, dusk = 20f;
        zombie.erosion.season.ErosionSeason season = cm.getSeason();
        if (season != null) {
            dawn = season.getDawn();
            dusk = season.getDusk();
        }
        float cloud = clamp01(WeatherLook.cloud());
        float rain = cm.getPrecipitationIsSnow() ? 0f : clamp01(WeatherLook.precipitation());
        float snow = cm.getPrecipitationIsSnow() ? clamp01(WeatherLook.precipitation()) : 0f;
        // The low sun from just before dawn to an hour and a half after, and the same around dusk; clouds hide it
        float golden = Math.max(bump(t, dawn - 0.5f, dawn + 1.5f), bump(t, dusk - 1.5f, dusk + 0.5f)) * (1f - 0.8f * cloud);
        float night = clamp01(cm.getNightStrength());
        float overcast = clamp01(cloud * (1f - 0.5f * night) + rain * 0.4f);
        grade = new float[] {golden, night, overcast, clamp01(WeatherLook.fog()), rain, snow,
            clamp01(com.pzoptimizer.pzoe.weather.WeatherEvents.heat()), 1f};
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    private static void gather() {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        IsoPlayer[] players = IsoPlayer.players;
        long now = System.nanoTime();
        if (heatCell != cell || now - heatSample >= 250_000_000L) {
            heatCell = cell;
            heatSample = now;
            gatherStaticHeat(cell, players);
            staticHeatCount = sourceCount;
            System.arraycopy(best, 0, staticHeat, 0, sourceCount * 6);
        } else {
            sourceCount = staticHeatCount;
            System.arraycopy(staticHeat, 0, best, 0, sourceCount * 6);
        }
        for (IsoMovingObject o : cell.getObjectList()) {
            if (o instanceof IsoGameCharacter chr && chr.isOnFire()) {
                float d = nearest(chr.getX(), chr.getY(), players);
                if (d >= 0) offer(chr.getX(), chr.getY(), chr.getZ(), 0.8f, 0.8f, d);
            }
        }
        org.joml.Vector3f p = exhaust;
        for (BaseVehicle v : cell.getVehicles()) {
            if (!v.isEngineRunning()) continue;
            float d = nearest(v.getX(), v.getY(), players);
            if (d < 0) continue;
            zombie.scripting.objects.VehicleScript script = v.getScript();
            if (script == null) continue;
            org.joml.Vector3f ext = script.getExtents(), com = script.getCenterOfMassOffset();
            // Rear wheels sit at negative z in vehicle scripts
            v.getWorldPos(ext.x * 0.25f, 0f, com.z - ext.z * 0.5f - 0.15f, p);
            float kmh = Math.abs(v.getCurrentSpeedKmHour());
            float strength = 0.45f * Math.max(0.3f, 1f - kmh / 40f);
            offer(p.x, p.y, v.getZ(), 0.5f, strength, d);
        }
    }

    private static void gatherStaticHeat(IsoCell cell, IsoPlayer[] players) {
        var fires = zombie.iso.objects.IsoFireManager.FireStack;
        for (int i = 0; i < fires.size(); i++) {
            var fire = fires.get(i);
            var square = fire == null ? null : fire.getSquare();
            if (square == null || fire.smoke || fire.lifeStage >= 5) continue;
            float x = square.getX() + 0.5f, y = square.getY() + 0.5f;
            float distance = nearest(x, y, players);
            if (distance >= 0f) offer(x, y, square.getZ(), 0.85f + 0.35f * RoomSmoke.intensity(fire),
                RoomSmoke.intensity(fire), distance);
        }
        ArrayList<?> heat = heatSources(cell);
        if (heat != null) {
            for (int i = 0, n = heat.size(); i < n; i++) {
                Object o = heat.get(i);
                if (!(o instanceof IsoHeatSource h)) continue;
                float x = h.getX() + 0.5f, y = h.getY() + 0.5f;
                float d = nearest(x, y, players);
                if (d < 0) continue;
                var square = cell.getGridSquare(h.getX(), h.getY(), h.getZ());
                if (square != null && square.getProperties().has(zombie.iso.SpriteDetails.IsoFlagType.burning)) continue;
                // Fire and fireplaces register 35, barbecues 25
                float strength = Math.min(1f, h.getTemperature() / 35f);
                float size = Math.max(0.7f, Math.min(1.3f, 0.6f + h.getRadius() * 0.07f));
                offer(x, y, h.getZ(), size, strength, d);
            }
        }
    }

    private static final org.joml.Vector3f exhaust = new org.joml.Vector3f();

    static ArrayList<?> heatSources(IsoCell cell) {
        if (!heatFieldTried) {
            heatFieldTried = true;
            try {
                heatField = IsoCell.class.getDeclaredField("heatSources");
                heatField.setAccessible(true);
            } catch (Throwable t) {
                heatField = null;
            }
        }
        if (heatField == null) return null;
        try {
            return (ArrayList<?>) heatField.get(cell);
        } catch (Throwable t) {
            return null;
        }
    }

    // Squared distance to the nearest local player, or -1 when out of range
    private static float nearest(float x, float y, IsoPlayer[] players) {
        float bestD = -1f;
        for (IsoPlayer p : players) {
            if (p == null) continue;
            float dx = p.getX() - x, dy = p.getY() - y, d = dx * dx + dy * dy;
            if (d < RANGE_SQ && (bestD < 0 || d < bestD)) bestD = d;
        }
        return bestD;
    }

    // Keeps the MAX nearest; replaces the farthest kept one when full
    private static void offer(float x, float y, float z, float size, float strength, float d) {
        int at;
        if (sourceCount < MAX) {
            at = sourceCount++;
        } else {
            at = -1;
            float far = d;
            for (int i = 0; i < MAX; i++) {
                if (best[i * 6 + 5] > far) {
                    far = best[i * 6 + 5];
                    at = i;
                }
            }
            if (at < 0) return;
        }
        int o = at * 6;
        best[o] = x;
        best[o + 1] = y;
        best[o + 2] = z;
        best[o + 3] = size;
        best[o + 4] = strength;
        best[o + 5] = d;
    }

    // A world point near the camera (snapped to 32 squares, so it stays put while the camera moves) and where it lands
    // in the world texture this frame, pixels with v up: the camera's move for the history buffer
    private static float[] reference(int playerIndex, float zoom) {
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        IsoPlayer pl = IsoPlayer.players[playerIndex];
        if (cam == null || pl == null || zoom <= 0f) return null;
        float wx = (float) Math.floor(pl.getX() / 32f) * 32f, wy = (float) Math.floor(pl.getY() / 32f) * 32f;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        return new float[] {wx, wy, left + cam.XToScreenExact(wx, wy, 0f, 0) / zoom, top + sh - cam.YToScreenExact(wx, wy, 0f, 0) / zoom};
    }

    // The world's axes on screen for things the shader moves about in the world itself (fireflies, flies): one square
    // along x, along y and one level up, in the world image's UV (v up), and one square's width in pixels
    private static float[] basis(int playerIndex, float zoom, int texW, int texH) {
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        IsoPlayer pl = IsoPlayer.players[playerIndex];
        if (cam == null || pl == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float x = (float) Math.floor(pl.getX()), y = (float) Math.floor(pl.getY()), z = (float) Math.floor(pl.getZ());
        float x0 = cam.XToScreenExact(x, y, z, 0), y0 = cam.YToScreenExact(x, y, z, 0);
        float sx = zoom * texW, sy = -zoom * texH;
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        return new float[] {
            (cam.XToScreenExact(x + 1f, y, z, 0) - x0) / sx, (cam.YToScreenExact(x + 1f, y, z, 0) - y0) / sy,
            (cam.XToScreenExact(x, y + 1f, z, 0) - x0) / sx, (cam.YToScreenExact(x, y + 1f, z, 0) - y0) / sy,
            (cam.XToScreenExact(x, y, z + 1f, 0) - x0) / sx, (cam.YToScreenExact(x, y, z + 1f, 0) - y0) / sy, tile, 0f};
    }

    // Per point: u, v in the world image, tile width in screen pixels, strength. The world quad maps texture row
    // (screenTop + screenHeight) to the top of the viewport (Texture.rendershader2), so v runs upward.
    private static float[] project(int playerIndex, float zoom, int texW, int texH) {
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        // Full diamond width of one square, corner (0,1) to corner (1,0)
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        float[] out = new float[sourceCount * 4];
        int k = 0;
        for (int i = 0; i < sourceCount; i++) {
            int o = i * 6;
            float px = cam.XToScreenExact(best[o], best[o + 1], best[o + 2], 0) / zoom;
            float py = cam.YToScreenExact(best[o], best[o + 1], best[o + 2], 0) / zoom;
            out[k++] = (left + px) / texW;
            out[k++] = (top + sh - py) / texH;
            out[k++] = tile * best[o + 3];
            out[k++] = SmokePlumes.seed((int) Math.floor(best[o]), (int) Math.floor(best[o + 1]), (int) best[o + 2])
                + 0.5f * best[o + 4];
        }
        return out;
    }

    /** End of WeatherShader.startRenderThread(texd), render thread, with the screen program bound. */
    public static void render(zombie.core.opengl.Shader shader, TextureDraw texd) {
        try {
            if (!ShaderPatchHost.anyLive()) return;
            draws++;
            GpuTimer.screenPass();
            // Re-checked on program change and 4 times a second: a rebuild can hand back the same program id
            int prog = shader.getID();
            long nowNs = System.nanoTime();
            int[] loc = locations;
            if (prog != locProgram || nowNs - locCheckedNs > 250_000_000L) {
                locProgram = prog;
                locCheckedNs = nowNs;
                // Build 42 numbers samplers in driver order, but the world quad always binds its image to unit zero.
                int worldSampler = GL20.glGetUniformLocation(prog, "DIFFUSE");
                diffuseUnit = worldSampler < 0 ? -1 : GL20.glGetUniformi(prog, worldSampler);
                if (diffuseUnit > 0) {
                    shader.getProgram().setSamplerUnit("DIFFUSE", 0);
                    diffuseUnit = 0;
                    diffuseRepairs++;
                }
                loc[0] = GL20.glGetUniformLocation(prog, "PZOHaze");
                loc[1] = GL20.glGetUniformLocation(prog, "PZOHazeCount");
                loc[2] = GL20.glGetUniformLocation(prog, "PZOHazeTime");
                loc[3] = GL20.glGetUniformLocation(prog, "PZOGrade");
                loc[4] = GL20.glGetUniformLocation(prog, "PZOGrade2");
                loc[5] = GL20.glGetUniformLocation(prog, "PZOBloomTex");
                loc[6] = GL20.glGetUniformLocation(prog, "PZOBloom");
                loc[7] = GL20.glGetUniformLocation(prog, "PZOStorm");
                loc[8] = GL20.glGetUniformLocation(prog, "PZOBolt");
                loc[9] = GL20.glGetUniformLocation(prog, "PZOEye");
                loc[10] = GL20.glGetUniformLocation(prog, "PZOSmoke");
                loc[11] = GL20.glGetUniformLocation(prog, "PZOSmokeInfo");
                loc[12] = GL20.glGetUniformLocation(prog, "PZOLens");
                loc[13] = GL20.glGetUniformLocation(prog, "PZOBreath");
                loc[14] = GL20.glGetUniformLocation(prog, "PZOBreathInfo");
                loc[15] = GL20.glGetUniformLocation(prog, "PZOMoodle");
                loc[16] = GL20.glGetUniformLocation(prog, "PZOMoodle2");
                loc[17] = GL20.glGetUniformLocation(prog, "PZOWindscreen");
                loc[18] = GL20.glGetUniformLocation(prog, "PZOWindscreen2");
                loc[19] = GL20.glGetUniformLocation(prog, "PZODust");
                loc[20] = GL20.glGetUniformLocation(prog, "PZODustInfo");
                loc[21] = GL20.glGetUniformLocation(prog, "PZOGlare");
                loc[22] = GL20.glGetUniformLocation(prog, "PZOGlareInfo");
                loc[23] = GL20.glGetUniformLocation(prog, "PZOLut");
                loc[24] = GL20.glGetUniformLocation(prog, "PZOLutInfo");
                loc[25] = GL20.glGetUniformLocation(prog, "PZOFireflies");
                loc[26] = GL20.glGetUniformLocation(prog, "PZOFireflyInfo");
                loc[27] = GL20.glGetUniformLocation(prog, "PZOFlies");
                loc[28] = GL20.glGetUniformLocation(prog, "PZOFliesInfo");
                loc[29] = GL20.glGetUniformLocation(prog, "PZOEyes");
                loc[30] = GL20.glGetUniformLocation(prog, "PZOEyesInfo");
                loc[31] = GL20.glGetUniformLocation(prog, "PZOWorldBasis");
                loc[32] = GL20.glGetUniformLocation(prog, "PZOEUnderwater");
                loc[33] = GL20.glGetUniformLocation(prog, "PZOLightTrails[0]");
                loc[34] = GL20.glGetUniformLocation(prog, "PZOLightTrailCount");
                loc[35] = GL20.glGetUniformLocation(prog, "PZOSmokeBounds");
                loc[36] = GL20.glGetUniformLocation(prog, "PZOFireAtlas");
                loc[37] = GL20.glGetUniformLocation(prog, "PZOFireAtlasReady");
                loc[38] = GL20.glGetUniformLocation(prog, "PZOSmokeSceneDepth");
                loc[39] = GL20.glGetUniformLocation(prog, "PZOSmokeDepth[0]");
                loc[40] = GL20.glGetUniformLocation(prog, "PZOSmokeDepthBounds");
                loc[41] = GL20.glGetUniformLocation(prog, "PZOSmokeDepthStep");
                loc[42] = GL20.glGetUniformLocation(prog, "PZORoomSmoke[0]");
                loc[43] = GL20.glGetUniformLocation(prog, "PZORoomSmokeInfo");
                loc[44] = GL20.glGetUniformLocation(prog, "PZORoomSmokeBounds");
                loc[45] = GL20.glGetUniformLocation(prog, "PZOFireGlow[0]");
                loc[46] = GL20.glGetUniformLocation(prog, "PZOFireGlowInfo");
                loc[47] = GL20.glGetUniformLocation(prog, "PZOImpactFx[0]");
                loc[48] = GL20.glGetUniformLocation(prog, "PZOImpactFxInfo");
                loc[49] = GL20.glGetUniformLocation(prog, "PZOSceneDepthInfo");
                loc[50] = GL20.glGetUniformLocation(prog, "PZOSceneDepth");
                BulletImpacts.fxWanted = loc[48] >= 0;
                if (loc[36] >= 0) GL20.glUniform1i(loc[36], FireAtlas.UNIT);
                if (loc[38] >= 0) GL20.glUniform1i(loc[38], SceneDepth.UNIT);
                if (loc[50] >= 0) GL20.glUniform1i(loc[50], SceneDepth.UNIT);
            }
            if (loc[49] >= 0) {
                SceneDepth.askedNanos = nowNs;
                float[] v = texd.vars;
                int seq = v != null && v.length > SEQ_VAR ? (int) v[SEQ_VAR] : -1;
                Filed fl = seq >= 0 ? filed.get(seq & 7) : null;
                boolean live = false;
                float scale = com.pzoptimizer.WorldRenderScale.getRenderThreadScale();
                if (fl != null && fl.seq == seq && fl.player == 0 && fl.singlePlayerView && zombie.core.PerformanceSettings.fboRenderChunk) {
                    var scene = Core.getInstance().getOffscreenBuffer(0);
                    if (scene != null && scene.getTexture() != null && scene.getTexture().getWidthHW() == texd.col2
                        && scene.getTexture().getHeightHW() == texd.col3) {
                        live = SceneDepth.capture(scene, texd.col2, texd.col3, scale, fl.frame);
                    }
                }
                GL20.glUniform4f(loc[49], live ? 1f : 0f, scale, 0f, 0f);
            }
            if (loc[32] >= 0) {
                com.pzoptimizer.pzoe.water.UnderwaterView.shaderAskedNanos = nowNs;
                float[] v = texd.vars;
                int seq = v != null && v.length > SEQ_VAR ? (int) v[SEQ_VAR] : -1;
                Filed fl = seq >= 0 ? filed.get(seq & 7) : null;
                boolean valid = fl != null && fl.seq == seq;
                GL20.glUniform2f(loc[32], valid ? fl.submerged : 0f, valid && fl.singlePlayerView ? 1f : 0f);
            }
            if (loc[26] >= 0 || loc[28] >= 0 || loc[30] >= 0) sprites(loc, texd, nowNs);
            if (loc[24] >= 0) Lut.upload(loc[23], loc[24]);
            if (loc[34] >= 0) {
                VehicleLightTrails.askedNanos = nowNs;
                float[] v = texd.vars;
                int seq = v != null && v.length > SEQ_VAR ? (int) v[SEQ_VAR] : -1;
                Filed fl = seq >= 0 ? filed.get(seq & 7) : null;
                float[] data = fl != null && fl.seq == seq && fl.zoom == v[14] ? fl.lightTrails : null;
                if (data != null && loc[33] >= 0) {
                    trailBuf.clear();
                    trailBuf.put(data).flip();
                    GL20.glUniform4fv(loc[33], trailBuf);
                }
                GL20.glUniform1i(loc[34], data == null ? 0 : data.length / 8);
            }
            if (loc[48] >= 0) {
                BulletImpacts.fxAskedNanos = nowNs;
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                float[] g = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14] ? fl.impacts : null;
                int n = g == null ? 0 : Math.min(BulletImpacts.FX_MAX, g.length / 4);
                if (n > 0 && loc[47] >= 0) {
                    impactBuf.clear();
                    impactBuf.put(g, 0, n * 4);
                    impactBuf.flip();
                    GL20.glUniform4fv(loc[47], impactBuf);
                }
                GL20.glUniform4f(loc[48], n, effectClock(), 0f, n > 0 ? 1f : 0f);
            }
            if (loc[46] >= 0) {
                FireGlow.askedNanos = nowNs;
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                float[] g = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14] ? fl.fireGlow : null;
                int n = g == null ? 0 : Math.min(FireGlow.MAX, g.length / 4);
                if (n > 0 && loc[45] >= 0) {
                    fireGlowBuf.clear();
                    fireGlowBuf.put(g, 0, n * 4);
                    fireGlowBuf.flip();
                    GL20.glUniform4fv(loc[45], fireGlowBuf);
                }
                GL20.glUniform4f(loc[46], n, effectClock(), FireGlow.haze, FireGlow.night);
            }
            if (loc[22] >= 0) {
                Glare.askedNanos = nowNs;
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                float[] gl = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14] ? fl.glare : null;
                int n = gl == null ? 0 : Math.min(Glare.MAX, gl.length / 8);
                if (n > 0 && loc[21] >= 0) {
                    glareBuf.clear();
                    glareBuf.put(gl, 0, n * 8);
                    glareBuf.flip();
                    GL20.glUniform4fv(loc[21], glareBuf);
                }
                GL20.glUniform4f(loc[22], n, Glare.rain, Glare.night, n > 0 ? 1f : 0f);
            }
            if (loc[20] >= 0) {
                DustTrails.askedNanos = nowNs;
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                float[] du = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14] ? fl.dust : null;
                int n = du == null ? 0 : Math.min(DustTrails.MAX, du.length / 4);
                if (n > 0 && loc[19] >= 0) {
                    dustBuf.clear();
                    dustBuf.put(du, 0, n * 4);
                    dustBuf.flip();
                    GL20.glUniform4fv(loc[19], dustBuf);
                }
                GL20.glUniform4f(loc[20], n, zombie.iso.weather.ClimateManager.getInstance().getDayLightStrength(), 0f, n > 0 ? 1f : 0f);
            }
            if (loc[18] >= 0) {
                Windscreen.askedNanos = nowNs;
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                float[] g = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14] ? fl.glass : null;
                if (g != null) {
                    if (loc[17] >= 0) GL20.glUniform4f(loc[17], g[0], g[1], g[2], g[3]);
                    GL20.glUniform4f(loc[18], g[4], g[5], g[6], g[7]);
                } else {
                    GL20.glUniform4f(loc[18], 0f, 0f, 0f, 0f);
                }
            }
            if (loc[16] >= 0) {
                MoodleFx.askedNanos = nowNs;
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                float[] m = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14] ? fl.moodle : null;
                if (m != null) {
                    if (loc[15] >= 0) GL20.glUniform4f(loc[15], m[0], m[1], m[2], m[3]);
                    GL20.glUniform4f(loc[16], m[4], m[5], m[6], published);
                } else {
                    GL20.glUniform4f(loc[16], 0f, 0f, 0f, 0f);
                }
            }
            if (loc[14] >= 0) {
                Breath.askedNanos = nowNs;
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                float[] br = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14] ? fl.breath : null;
                int n = br == null ? 0 : Math.min(Breath.MAX, br.length / 8);
                if (n > 0 && loc[13] >= 0) {
                    breathBuf.clear();
                    breathBuf.put(br, 0, n * 8);
                    breathBuf.flip();
                    GL20.glUniform4fv(loc[13], breathBuf);
                }
                GL20.glUniform4f(loc[14], n, published, Breath.windScreen, Breath.cold);
            }
            if (loc[12] >= 0) {
                // PZOE Gore's lens blood: amount, seed, palette, live
                float[] lb = GoreFx.lensUniform();
                GL20.glUniform4f(loc[12], lb[0], lb[1], lb[2], lb[3]);
            }
            if (loc[8] >= 0) {
                float[] b = com.pzoptimizer.pzoe.weather.WeatherStorm.bolt();
                GL20.glUniform4f(loc[8], b[0], b[1], b[2], b[3]);
            }
            // A storm off to one side (PZOE weather): screen direction and strength
            if (loc[7] >= 0) {
                float[] st = com.pzoptimizer.pzoe.weather.WeatherRegional.storm();
                GL20.glUniform4f(loc[7], st[0], st[1], st[2], 0f);
            }
            if (TemporalPass.on || TemporalPass.historyAsked()) {
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                if (fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14] && fl.ref != null) {
                    TemporalPass.render(fl.worldTex, fl.frame, fl.ref, fl.zoom,
                        loc[11] >= 0 && v[5] < 0.5f ? fl.smoke : null);
                }
            }
            bloom(loc, texd);
            if (loc[9] >= 0) {
                eyeAskedNanos = nowNs;
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                boolean ok = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14];
                EyeAdapt.render(loc[9], ok ? fl.worldTex : 0, ok ? fl.frame : 0, ok ? fl.player : 0, ok ? fl.rect : null);
            }
            if (loc[11] >= 0) {
                SmokePlumes.askedNanos = nowNs;
                float[] v = texd.vars;
                Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
                // A thumbnail draw re-runs startMainThread and then changes the zoom; those get no smoke
                SmokePlumes.Projection sm = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14] ? fl.smoke : null;
                int n = sm == null ? 0 : sm.count();
                if (n > 0 && loc[10] >= 0) {
                    buf.clear();
                    buf.put(sm.data(), 0, n * 4).flip();
                    GL20.glUniform4fv(loc[10], buf);
                    if (loc[35] >= 0) {
                        var bounds = SmokePlumes.bounds(sm.data(), n, texd.col2, texd.col3, false);
                        GL20.glUniform4f(loc[35], bounds.minX(), bounds.minY(), bounds.maxX(), bounds.maxY());
                    }
                    if (loc[37] >= 0) GL20.glUniform1i(loc[37], FireAtlas.bind() ? 1 : 0);
                }
                SmokePlumes.RoomProjection rooms = sm == null ? null : sm.rooms();
                smokeRoomRects = rooms == null ? 0 : rooms.count();
                float depthScale = com.pzoptimizer.WorldRenderScale.getRenderThreadScale();
                if (loc[43] >= 0) {
                    GL20.glUniform4f(loc[43], smokeRoomRects, sm == null ? 0f : sm.tileWidth(),
                        zombie.iso.IsoDepthHelper.SQUARE_DEPTH, depthScale);
                    if (rooms != null) {
                        roomSmokeBuf.clear();
                        roomSmokeBuf.put(rooms.data(), 0, smokeRoomRects * 8).flip();
                        GL20.glUniform4fv(loc[42], roomSmokeBuf);
                        var bounds = rooms.bounds();
                        GL20.glUniform4f(loc[44], bounds.minX(), bounds.minY(), bounds.maxX(), bounds.maxY());
                    }
                }
                if (sm != null) uploadSmokeDepth(loc, sm, texd.col2, texd.col3, depthScale, fl.frame);
                else if (loc[41] >= 0) GL20.glUniform1f(loc[41], 0f);
                GL20.glUniform4f(loc[11], n, published, SmokePlumes.windScreen, SmokePlumes.night);
            }
            if (loc[3] >= 0 || loc[4] >= 0) {
                gradeAskedNanos = nowNs;
                float[] g = grade;
                if (loc[3] >= 0) GL20.glUniform4f(loc[3], g[0], g[1], g[2], g[3]);
                if (loc[4] >= 0) GL20.glUniform4f(loc[4], g[4], g[5], g[6], g[7]);
            }
            if (loc[0] < 0 && loc[1] < 0) return;
            askedNanos = nowNs;
            if (loc[2] >= 0) GL20.glUniform1f(loc[2], published);
            int count = 0;
            float[] v = texd.vars;
            if (v != null && v.length > SEQ_VAR) {
                int s = (int) v[SEQ_VAR];
                Filed fl = filed.get(s & 7);
                // A thumbnail draw re-runs startMainThread and then changes the zoom; those get no haze
                if (fl != null && fl.seq == s && fl.data != null && fl.zoom == v[14] && loc[0] >= 0) {
                    buf.clear();
                    count = Math.min(MAX, fl.data.length / 4);
                    buf.put(fl.data, 0, count * 4);
                    buf.flip();
                    GL20.glUniform4fv(loc[0], buf);
                    drawsWithPoints++;
                }
            }
            if (loc[1] >= 0) GL20.glUniform1f(loc[1], count);
        } catch (Throwable t) {
            // No haze this draw
        }
    }

    // Fireflies, flies over corpses and eye shine: lists filed by the main thread for this frame
    private static void sprites(int[] loc, TextureDraw texd, long nowNs) {
        float[] v = texd.vars;
        Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
        boolean ok = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.zoom == v[14];
        if (ok && fl.basis != null && loc[31] >= 0) {
            spriteBuf.clear();
            spriteBuf.put(fl.basis);
            spriteBuf.flip();
            GL20.glUniform4fv(loc[31], spriteBuf);
        }
        if (loc[26] >= 0) {
            Fireflies.askedNanos = nowNs;
            int n = list(loc[25], ok && fl.basis != null ? fl.fireflies : null);
            GL20.glUniform4f(loc[26], n, published, 0f, n > 0 ? 1f : 0f);
        }
        if (loc[28] >= 0) {
            CorpseFlies.screenAskedNanos = nowNs;
            int n = list(loc[27], ok && fl.basis != null ? fl.flies : null);
            GL20.glUniform4f(loc[28], n, published, zombie.iso.weather.ClimateManager.getInstance().getDayLightStrength(), n > 0 ? 1f : 0f);
        }
        if (loc[30] >= 0) {
            EyeShine.askedNanos = nowNs;
            int n = list(loc[29], ok ? fl.eyes : null);
            GL20.glUniform4f(loc[30], n, 0f, 0f, n > 0 ? 1f : 0f);
        }
    }

    // Up to 16 vec4s into an array uniform; how many went
    private static int list(int locArray, float[] d) {
        int n = d == null ? 0 : Math.min(16, d.length / 4);
        if (n > 0 && locArray >= 0) {
            spriteBuf.clear();
            spriteBuf.put(d, 0, n * 4);
            spriteBuf.flip();
            GL20.glUniform4fv(locArray, spriteBuf);
        }
        return n;
    }

    /**
     * Bloom reads blurred copies of the world image: its own mipmaps, made once a frame (one glGenerateMipmap, a
     * fraction of a millisecond) and read on unit 3. The world texture gets mipmap filtering while bloom is on; the
     * screen pass draws it 1:1, so every other read still lands on the full-size level.
     */
    private static void bloom(int[] loc, TextureDraw texd) {
        if (loc[5] < 0) {
            if (!mipTextures.isEmpty()) {
                int bound = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
                for (int id : mipTextures) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
                mipTextures.clear();
            }
            return;
        }
        bloomAskedNanos = System.nanoTime();
        float[] v = texd.vars;
        Filed fl = v != null && v.length > SEQ_VAR ? filed.get(((int) v[SEQ_VAR]) & 7) : null;
        boolean live = fl != null && fl.seq == (int) v[SEQ_VAR] && fl.worldTex != 0;
        if (live) {
            int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fl.worldTex);
            if (mipTextures.add(fl.worldTex)) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            }
            mipmaps(fl.worldTex, fl.frame);
            GL13.glActiveTexture(prevActive);
            GL20.glUniform1i(loc[5], 3);
        }
        if (loc[6] >= 0) GL20.glUniform4f(loc[6], live ? 1f : 0f, bloomNight, 0f, 0f);
    }

    /** Mipmaps of the world texture, once a frame; the texture is bound on the active unit. Render thread. */
    private static void uploadSmokeDepth(int[] locations, SmokePlumes.Projection smoke, int w, int h, float depthScale, int frame) {
        if (locations[41] < 0) return;
        GL20.glUniform1f(locations[41], 0f);
        if (smoke.scene() == null) return;
        var b = smoke.count() == 0 ? smoke.rooms().bounds() : SmokePlumes.bounds(smoke.data(), smoke.count(), w, h, true);
        if (smoke.count() > 0 && smoke.rooms() != null) {
            var r = smoke.rooms().bounds();
            b = new SmokePlumes.Bounds(Math.min(b.minX(), r.minX()), Math.min(b.minY(), r.minY()),
                Math.max(b.maxX(), r.maxX()), Math.max(b.maxY(), r.maxY()));
        }
        int x0 = (int)Math.floor(b.minX() * w), y0 = (int)Math.floor(b.minY() * h);
        int x1 = (int)Math.ceil(b.maxX() * w), y1 = (int)Math.ceil(b.maxY() * h);
        // The whole frame's depth, shared with every other depth-tested effect this frame
        if (!SceneDepth.capture(smoke.scene(), w, h, depthScale, frame)) return;
        if (smoke.depth() != null) {
            buf.clear();
            buf.put(smoke.depth(), 0, smoke.count()).flip();
            GL20.glUniform1fv(locations[39], buf);
        }
        GL20.glUniform4f(locations[40], x0 / (float)w, y0 / (float)h, x1 / (float)w, y1 / (float)h);
        GL20.glUniform1f(locations[41], zombie.iso.IsoDepthHelper.LEVEL_DEPTH / (1.5f * smoke.tileWidth()));
    }

    static void mipmaps(int worldTex, int frame) {
        if (frame == lastMipFrame && worldTex == lastMipTex) return;
        lastMipFrame = frame;
        lastMipTex = worldTex;
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }

    private static int lastMipTex;

    static String worldImageState() {
        return "screenDraws=" + draws + ";diffuseUnit=" + diffuseUnit + ";diffuseRepairs=" + diffuseRepairs
            + ";smokeRoomRects=" + smokeRoomRects
            + ";eyeMeter=" + (System.nanoTime() - eyeAskedNanos < 1_000_000_000L ? 1 : 0)
            + ";" + EyeAdapt.stateText();
    }

    /** key=value pairs for the settings card (PZOApi.getChunkEffects). */
    static String stateText() {
        boolean asked = System.nanoTime() - askedNanos < 1_000_000_000L;
        return "hazeShader=" + (asked ? 1 : 0) + ";hazeSources=" + sourceCount + ";hazeDraws=" + draws
            + ";hazeDrawsWithPoints=" + drawsWithPoints + ";locHaze=" + locations[0] + ";locGrade=" + locations[3]
            + ";locEye=" + locations[9] + ";" + EyeAdapt.stateText() + ";" + SmokePlumes.stateText() + ";" + Breath.stateText() + ";" + TemporalPass.stateText()
            + ";" + Fireflies.stateText() + ";" + CorpseFlies.stateText() + ";" + EyeShine.stateText() + ";" + CharShadows.stateText() + ";" + FireGlow.stateText() + ";" + SceneDepth.stateText();
    }

    static boolean patchShader(ClassNode node) {
        boolean a = appendCall(node, "startMainThread", "(Lzombie/core/textures/TextureDraw;I)V", true);
        boolean b = appendCall(node, "startRenderThread", "(Lzombie/core/textures/TextureDraw;)V", false);
        return a & b & guardNullVars(node);
    }

    // A screen.frag rebuild can land between the two threads: StartShader skips startMainThread while the program
    // id isn't in ShaderMap, the driver hands the new program the same id, and startRenderThread then reads
    // texd.vars[6] with no null check (stock never rebuilds this shader mid-game). Skip that one frame instead of
    // crashing the game.
    private static boolean guardNullVars(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "startRenderThread", "(Lzombie/core/textures/TextureDraw;)V");
        if (m == null) return false;
        LabelNode ok = new LabelNode();
        InsnList g = new InsnList();
        g.add(new VarInsnNode(Opcodes.ALOAD, 1));
        g.add(new FieldInsnNode(Opcodes.GETFIELD, "zombie/core/textures/TextureDraw", "vars", "[F"));
        g.add(new JumpInsnNode(Opcodes.IFNONNULL, ok));
        g.add(new InsnNode(Opcodes.RETURN));
        g.add(ok);
        m.instructions.insert(g);
        return true;
    }

    private static boolean appendCall(ClassNode node, String name, String desc, boolean mainThread) {
        MethodNode m = AsmSupport.findMethod(node, name, desc);
        if (m == null) return false;
        int returns = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList call = new InsnList();
            if (mainThread) {
                call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                call.add(new VarInsnNode(Opcodes.ILOAD, 2));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/ScreenPass", "main",
                    "(Lzombie/core/textures/TextureDraw;I)V", false));
            } else {
                call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/ScreenPass", "render",
                    "(Lzombie/core/opengl/Shader;Lzombie/core/textures/TextureDraw;)V", false));
            }
            m.instructions.insertBefore(insn, call);
            returns++;
        }
        return returns > 0;
    }
}
