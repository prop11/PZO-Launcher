package com.pzoptimizer.pzoe;

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

    private record Filed(int seq, float zoom, float[] data, int worldTex, int frame) {}

    // Main thread
    private static int lastFrame = Integer.MIN_VALUE;
    private static long lastNanos;
    private static double clock;
    private static int seq;
    // Nearest sources this frame: x, y, z, size (tiles), strength, squared distance
    private static int sourceCount;
    private static final float[] best = new float[MAX * 6];
    private static Field heatField;
    private static boolean heatFieldTried;

    // Shared
    private static final AtomicReferenceArray<Filed> filed = new AtomicReferenceArray<>(8);
    private static volatile long askedNanos, gradeAskedNanos, bloomAskedNanos;
    private static volatile float bloomNight;
    private static volatile float published;
    private static volatile float[] grade = new float[8];

    // Render thread
    private static final int[] locations = new int[7];
    // World textures switched to mipmap filtering for bloom, put back when bloom goes away
    private static final java.util.Set<Integer> mipTextures = new java.util.HashSet<>();
    private static int lastMipFrame = Integer.MIN_VALUE;
    private static int locProgram = -1;
    private static long locCheckedNs;
    private static final FloatBuffer buf = ByteBuffer.allocateDirect(MAX * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static volatile long draws, drawsWithPoints;

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
            if (now - bloomAskedNanos < 1_000_000_000L) {
                zombie.core.textures.TextureFBO fbo = Core.getInstance().getOffscreenBuffer();
                if (fbo != null && fbo.getTexture() != null) worldTex = fbo.getTexture().getID();
                bloomNight = zombie.iso.weather.ClimateManager.getInstance().getNightStrength();
            }
            filed.set(s & 7, new Filed(s, zoom, sourceCount == 0 ? null : project(playerIndex, zoom, texd.col2, texd.col3), worldTex, f));
        } catch (Throwable t) {
            // Worst case no haze this frame
        }
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
        float cloud = clamp01(cm.getCloudIntensity());
        float rain = cm.getPrecipitationIsSnow() ? 0f : clamp01(cm.getPrecipitationIntensity());
        float snow = cm.getPrecipitationIsSnow() ? clamp01(cm.getPrecipitationIntensity()) : 0f;
        // The low sun from just before dawn to an hour and a half after, and the same around dusk; clouds hide it
        float golden = Math.max(bump(t, dawn - 0.5f, dawn + 1.5f), bump(t, dusk - 1.5f, dusk + 0.5f)) * (1f - 0.8f * cloud);
        float night = clamp01(cm.getNightStrength());
        float overcast = clamp01(cloud * (1f - 0.5f * night) + rain * 0.4f);
        grade = new float[] {golden, night, overcast, clamp01(cm.getFogIntensity()), rain, snow, 0f, 1f};
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    private static void gather() {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        IsoPlayer[] players = IsoPlayer.players;
        ArrayList<?> heat = heatSources(cell);
        if (heat != null) {
            for (int i = 0, n = heat.size(); i < n; i++) {
                Object o = heat.get(i);
                if (!(o instanceof IsoHeatSource h)) continue;
                float x = h.getX() + 0.5f, y = h.getY() + 0.5f;
                float d = nearest(x, y, players);
                if (d < 0) continue;
                // Fire and fireplaces register 35, barbecues 25
                float strength = Math.min(1f, h.getTemperature() / 35f);
                float size = Math.max(0.7f, Math.min(1.3f, 0.6f + h.getRadius() * 0.07f));
                offer(x, y, h.getZ(), size, strength, d);
            }
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

    private static final org.joml.Vector3f exhaust = new org.joml.Vector3f();

    private static ArrayList<?> heatSources(IsoCell cell) {
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
            out[k++] = best[o + 4];
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
                loc[0] = GL20.glGetUniformLocation(prog, "PZOHaze");
                loc[1] = GL20.glGetUniformLocation(prog, "PZOHazeCount");
                loc[2] = GL20.glGetUniformLocation(prog, "PZOHazeTime");
                loc[3] = GL20.glGetUniformLocation(prog, "PZOGrade");
                loc[4] = GL20.glGetUniformLocation(prog, "PZOGrade2");
                loc[5] = GL20.glGetUniformLocation(prog, "PZOBloomTex");
                loc[6] = GL20.glGetUniformLocation(prog, "PZOBloom");
            }
            bloom(loc, texd);
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

    /**
     * Bloom reads blurred copies of the world image: its own mipmaps, made once a frame (one glGenerateMipmap, a
     * fraction of a millisecond) and read on unit 3. The world texture gets mipmap filtering while bloom is on; the
     * screen pass draws it 1:1, so every other read still lands on the full-size level.
     */
    private static void bloom(int[] loc, TextureDraw texd) {
        if (loc[5] < 0) {
            if (!mipTextures.isEmpty()) {
                int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
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
            int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fl.worldTex);
            if (mipTextures.add(fl.worldTex)) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            }
            if (fl.frame != lastMipFrame) {
                lastMipFrame = fl.frame;
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            }
            GL13.glActiveTexture(prevActive);
            GL20.glUniform1i(loc[5], 3);
        }
        if (loc[6] >= 0) GL20.glUniform4f(loc[6], live ? 1f : 0f, bloomNight, 0f, 0f);
    }

    /** key=value pairs for the settings card (PZOApi.getChunkEffects). */
    static String stateText() {
        boolean asked = System.nanoTime() - askedNanos < 1_000_000_000L;
        return "hazeShader=" + (asked ? 1 : 0) + ";hazeSources=" + sourceCount + ";hazeDraws=" + draws
            + ";hazeDrawsWithPoints=" + drawsWithPoints + ";locHaze=" + locations[0] + ";locGrade=" + locations[3];
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
