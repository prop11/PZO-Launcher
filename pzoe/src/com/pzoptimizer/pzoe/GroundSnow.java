package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * Ground snow for PZO Enhancements' Better Ground Snow card: how deep the snow looks, and vanilla's snow tiles off
 * while the card's shader draws the snow per pixel instead.
 *
 * Depth. Vanilla adds one hour's worth of snow at a time (ClimateManager.updateSnow, on the hour) and needs about 8
 * hours of heavy snow for a full cover, so the ground jumps once an hour and takes most of a day. I keep my own depth
 * that moves every frame with game time: steady heavy snow gives a dusting in about half an hour, patches by an hour
 * or so and a full blanket in about 3 hours; light snow takes about 6. Past 1 the snow keeps piling into drifts, up to
 * DEPTH_MAX. It melts with vanilla's own melt (warmth, more by day, and rain), just smoothly instead of hourly. The
 * depth is kept in the save's GameTime mod data; without one it starts from vanilla's snow. Vanilla's own snow grid
 * keeps running for gameplay (gridSquareIsSnow), only its tiles aren't drawn.
 *
 * Main thread: frame() from PixelLight.globalFrame. Render thread reads the volatile values.
 */
public final class GroundSnow {
    // 1 is a full blanket; past it the snow keeps building toward deep winter snow (PZOE weather's deep snow: about
    // knee deep in the open at 3, waist deep in drifts and hollows)
    static final float DEPTH_MAX = 3.0f;
    private static final String SAVE_KEY = "PZOE_GroundSnow";

    // Main thread writes, render thread reads
    static volatile float depth, fresh;
    // 0..1, how fast it's melting now: the ground round the patches shows wet
    static volatile float melt;
    // Set by the render thread when the chunk shader asks for ground snow
    static volatile boolean shaderReady;
    // From the card (percent of the normal build-up speed)
    private static volatile float speed = 1f;

    private static double lastHours = -1;
    private static double savedAt = -1;
    private static boolean loaded;
    private static Object lastCell;

    private GroundSnow() {}

    /** True while the card's shader is live: vanilla's snow tiles stay undrawn and the depth runs. */
    static boolean active() {
        return shaderReady;
    }

    /** How much snow lies, for PZOE weather's deep snow (also runs without the card, and on servers, for wading). */
    public static float depthNow() {
        return depth;
    }

    // All the snow that has fallen so far (blanket depth added by snowing): trodden paths and shovelled squares fill in
    // again as it grows (WeatherSnow)
    public static volatile double snowfall;
    // Dev menu: heavy snow piling up fast until this world hour
    private static volatile double devFastUntil = -1;

    /** Dev menu (SP/server): snow piles up about 40 times as fast for this many game hours. */
    public static void devFast(double hoursFor) {
        zombie.GameTime gt = zombie.GameTime.getInstance();
        devFastUntil = gt == null ? -1 : gt.getWorldAgeHours() + hoursFor;
    }

    /** Dev menu (SP/server): set how much snow lies; clients get it with the next weather state. */
    public static void forceDepth(float d) {
        depth = clamp(d, 0f, DEPTH_MAX);
        fresh = 1f;
    }

    /** MP client: the server's depth, when ours has wandered off from it. */
    public static void serverDepth(float d) {
        if (Math.abs(d - depth) > 0.15f) depth = clamp(d, 0f, DEPTH_MAX);
    }

    /** Every frame from PZOE weather, every role: keeps the depth running for deep snow without the card. */
    public static void weatherFrame() {
        if (!active() && com.pzoptimizer.pzoe.weather.WeatherSnow.on()) frame(true);
    }

    static void configure(double pct) {
        speed = (float) Math.max(0.25, Math.min(4.0, pct / 100.0));
    }

    static void frame() {
        frame(false);
    }

    private static void frame(boolean forGameplay) {
        zombie.GameTime gt = zombie.GameTime.getInstance();
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        if (gt == null || cm == null || (!active() && !forGameplay)) {
            lastHours = -1;
            melt = 0f;
            return;
        }
        double hours = gt.getWorldAgeHours();
        float vanilla = cm.getSnowFracNow();
        // A new cell means another save was loaded: its own depth comes from its mod data
        Object cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell != lastCell) {
            lastCell = cell;
            loaded = false;
        }
        if (!loaded) {
            loaded = true;
            depth = clamp(load(gt, vanilla), 0f, DEPTH_MAX);
            fresh = cm.getPrecipitationIsSnow() && cm.getPrecipitationIntensity() > 0.05f ? 0.6f : 0f;
            lastHours = hours;
            savedAt = hours;
            PZOLogger.info("[GroundSnow] depth " + String.format("%.2f", depth) + " (vanilla " + String.format("%.2f", vanilla) + ")");
        }
        if (!zombie.SandboxOptions.instance.enableSnowOnGround.getValue()) {
            depth = 0f;
            fresh = 0f;
            lastHours = hours;
            return;
        }
        if (lastHours < 0) lastHours = hours;
        // Sleeping or a long skip still integrates; a save from another world doesn't (world age went back)
        double dt = hours - lastHours;
        lastHours = hours;
        if (dt <= 0) return;
        float h = (float) Math.min(dt, 48.0);

        float d = depth, f = fresh;
        float intensity = cm.getPrecipitationIntensity();
        boolean snow = cm.getPrecipitationIsSnow() && intensity > 0.02f;
        boolean dev = hours < devFastUntil;
        if (dev) {
            snow = true;
            intensity = 1f;
        }
        if (snow) {
            // A third of the blanket an hour at full intensity, slower for light snow (light snow, about 6 hours).
            // Past the blanket it builds more slowly: deep snow takes a day or two of snowing.
            float rate = h * speed * (float) Math.pow(intensity, 0.58) / 3f * (dev ? 40f : 1f);
            float add = d < 1.6f ? rate : rate * 0.35f;
            d += add;
            snowfall += add;
            f = Math.min(1f, f + h * intensity * 2f);
        } else {
            // Fresh snow loses its sparkle over half a day
            f = Math.max(0f, f - h * 0.15f);
        }
        // Vanilla's melt, in the same units (it fills at 7.5)
        float melt = 0f;
        float temp = cm.getTemperature();
        if (temp > 0f) {
            float m = temp / 10f;
            melt += m * 0.2f + m * 0.8f * cm.getDayLightStrength();
        }
        if (!cm.getPrecipitationIsSnow() && intensity > 0f) melt += intensity;
        GroundSnow.melt = clamp(melt * 0.8f, 0f, 1f);
        if (melt > 0f) {
            d -= h * melt / 7.5f;
            f = Math.max(0f, f - h * melt);
        }
        // Far apart from vanilla (snow set by an admin or a mod, weather changed by the server): drift back to it.
        // Vanilla's own snow stops at a full cover (1), so deep snow above that only follows it when vanilla's is gone.
        float gap = vanilla - d;
        if (gap > 0.5f || (gap < -0.5f && (d <= 1.6f || vanilla < 0.1f))) d += Math.signum(gap) * Math.min(Math.abs(gap) - 0.5f, h * 0.25f);
        depth = clamp(d, 0f, DEPTH_MAX);
        fresh = clamp(f, 0f, 1f);

        if (hours - savedAt > 1.0 / 6.0) {
            savedAt = hours;
            save(gt);
        }
    }

    private static float load(zombie.GameTime gt, float vanilla) {
        try {
            Object v = gt.getModData().rawget(SAVE_KEY);
            if (v instanceof Double dv) return dv.floatValue();
        } catch (Throwable ignored) {
        }
        return vanilla;
    }

    private static void save(zombie.GameTime gt) {
        try {
            gt.getModData().rawset(SAVE_KEY, Double.valueOf(depth));
        } catch (Throwable ignored) {
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // Vanilla's snow tiles

    static final String SNOW_RENDER = "zombie/iso/fboRenderChunk/FBORenderSnow";

    /** Replaces SnowGrid.frac where RenderSnow decides whether to draw tiles: 0 while the shader draws the snow. */
    public static int tileFrac(int frac) {
        return active() ? 0 : frac;
    }

    /**
     * FBORenderSnow.RenderSnow: the grid update stays (gameplay asks it whether a square has snow), the draw is
     * skipped by reading the grid's frac through tileFrac.
     */
    static boolean patchRenderSnow(ClassNode node) {
        MethodNode m = null;
        for (MethodNode mn : node.methods) {
            if (mn.name.equals("RenderSnow") && mn.desc.equals("(Lzombie/iso/IsoChunk;I)V")) m = mn;
        }
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GETFIELD && insn instanceof FieldInsnNode fi && fi.name.equals("frac") && fi.desc.equals("I")) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/GroundSnow", "tileFrac", "(I)I", false);
                m.instructions.insert(insn, call);
                insn = call;
                n++;
            }
        }
        return n > 0;
    }

    static String stateText() {
        return "groundSnow=" + (active() ? 1 : 0) + ";snowDepth=" + Math.round(depth * 100) + ";snowFresh=" + Math.round(fresh * 100);
    }
}
