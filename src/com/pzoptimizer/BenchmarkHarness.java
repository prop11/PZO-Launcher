package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.LuaClosure;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.opengl.RenderThread;
import zombie.iso.IsoCamera;
import zombie.ui.UIManager;

/**
 * Automated A/B benchmark driven by bench/fsr_compare.py.
 *
 * Configuration comes from ~/Zomboid/Lua/pzo-bench.txt (key=value lines) written by the script before launch,
 * so nothing depends on the launcher forwarding arguments. The script also copies a disposable bench save from
 * a template and points latestSave.ini at it, so the run may quit normally; every file it touches is restored.
 *
 * Flow (all on the main thread, ticked from EngineThreadGovernor's MainThread loop hook):
 *   MENU     queue a Lua OnFETick handler that waits for MainScreen's start-up delay, then continues the latest save
 *   LOADING  press "Click to Start" (GameLoadingState.forceDone) once the load is finished
 *   WARMUP   god mode + invisible, remember the start tile, uncap the framerate
 *   per zoom: SETTLE at the start tile -> ROUTE (square loop, fixed tiles/second, frame times sampled)
 *             -> SHOTS (paused at the start tile: screenshot without UI, then with UI)
 *   DONE     results.json, then Core.quitToDesktop() with a halt fallback
 */
public final class BenchmarkHarness {

    private enum Phase { OFF, MENU, LOADING, WARMUP, SETTLE, ROUTE, SHOT_NOUI, SHOT_UI, DONE }

    private static final String CONFIG_FILE = "pzo-bench.txt";
    private static final long WARMUP_NS = 15_000_000_000L;
    private static final long SETTLE_NS = 4_000_000_000L;
    private static final long SHOT_DELAY_NS = 1_500_000_000L;
    private static final long MENU_REQUEUE_NS = 20_000_000_000L;
    private static final long TIMEOUT_NS = 600_000_000_000L;

    private static volatile Phase phase = Phase.OFF;
    private static File outDir;
    private static String label = "run";
    private static float[] zooms = {1.0f, 2.0f};
    private static int routeTiles = 40;
    private static float routeSpeed = 8.0f;

    private static long phaseStart;
    private static long benchStart;
    private static long lastQueueAttempt;
    private static int zoomIndex;
    private static float startX, startY;
    private static int startZ;
    private static long lastFrameNs;
    private static int lastFrameCount = -1;
    private static final List<Long> frameTimes = new ArrayList<>();
    private static final StringBuilder zoomJson = new StringBuilder();
    private static final List<String> notes = new ArrayList<>();

    private BenchmarkHarness() {}

    /** Called from PZOEntrypoint.main. Activates when the bench config file exists (or -pzo_bench=<dir> is passed). */
    public static void checkLaunchParams(String[] args) {
        Map<String, String> cfg = readConfig();
        if (args != null) {
            for (String a : args) {
                if (a != null && a.startsWith("-pzo_bench=")) {
                    cfg.put("out", a.substring("-pzo_bench=".length()).replace("\"", ""));
                }
            }
        }
        String out = cfg.get("out");
        if (out == null || out.isEmpty()) return;
        outDir = new File(out);
        outDir.mkdirs();
        label = cfg.getOrDefault("label", outDir.getName());
        try {
            if (cfg.containsKey("zooms")) {
                String[] parts = cfg.get("zooms").split(",");
                zooms = new float[parts.length];
                for (int i = 0; i < parts.length; i++) zooms[i] = Float.parseFloat(parts[i].trim());
            }
            if (cfg.containsKey("route_tiles")) routeTiles = Integer.parseInt(cfg.get("route_tiles").trim());
            if (cfg.containsKey("route_speed")) routeSpeed = Float.parseFloat(cfg.get("route_speed").trim());
        } catch (NumberFormatException e) {
            notes.add("bad config value: " + e.getMessage());
        }
        phase = Phase.MENU;
        benchStart = System.nanoTime();
        phaseStart = benchStart;
        lastTickNs = benchStart;
        startWatchdog();
        PZOLogger.success("[BenchmarkHarness] Benchmark mode '" + label + "': output -> " + outDir.getAbsolutePath()
            + " zooms=" + Arrays.toString(zooms) + " route=" + routeTiles + " tiles @ " + routeSpeed + " tiles/s");
    }

    private static Map<String, String> readConfig() {
        Map<String, String> cfg = new HashMap<>();
        File f = new File(System.getProperty("user.home"), "Zomboid" + File.separator + "Lua" + File.separator + CONFIG_FILE);
        if (!f.isFile()) return cfg;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) cfg.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Throwable t) {
            PZOLogger.warn("[BenchmarkHarness] Could not read " + f + ": " + t);
        }
        return cfg;
    }

    public static boolean isActive() {
        return phase != Phase.OFF;
    }

    private static volatile long lastTickNs;
    private static final long MAIN_STALL_NS = 45_000_000_000L;
    private static final long PHASE_STALL_NS = 240_000_000_000L;

    // A hung main thread never ticks again, so stalls are detected and documented from a separate thread
    private static void startWatchdog() {
        Thread t = new Thread(() -> {
            while (phase != Phase.DONE) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.nanoTime();
                String why = null;
                if (now - lastTickNs > MAIN_STALL_NS) {
                    why = "main thread stopped ticking for " + (now - lastTickNs) / 1_000_000_000L + "s in phase " + phase;
                } else if (now - phaseStart > PHASE_STALL_NS) {
                    why = "phase " + phase + " stalled for " + (now - phaseStart) / 1_000_000_000L + "s (state " + stateName() + ")";
                }
                if (why != null) {
                    PZOLogger.warn("[BenchmarkHarness] " + why + " - writing thread dump");
                    dumpThreads(why);
                    notes.add(why + " (see threads.txt)");
                    finish(false);
                    return;
                }
            }
        }, "PZO-BenchWatchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void dumpThreads(String why) {
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(outDir, "threads.txt")))) {
            w.println(why);
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread th = e.getKey();
                w.println();
                w.println("\"" + th.getName() + "\" state=" + th.getState() + " daemon=" + th.isDaemon());
                for (StackTraceElement el : e.getValue()) w.println("    at " + el);
            }
        } catch (Throwable ignored) {}
    }

    private static void enter(Phase p) {
        phase = p;
        phaseStart = System.nanoTime();
        PZOLogger.info("[BenchmarkHarness] -> " + p + (p == Phase.SETTLE ? " (zoom " + zooms[zoomIndex] + ")" : ""));
    }

    private static String stateName() {
        Object s = GameWindow.states != null ? GameWindow.states.current : null;
        return s == null ? "" : s.getClass().getSimpleName();
    }

    private static Object[] runLua(String code) throws Exception {
        LuaClosure closure = LuaCompiler.loadstring(code, "PZOBench", LuaManager.env);
        LuaReturn ret = LuaManager.caller.protectedCall(LuaManager.thread, closure);
        if (!ret.isSuccess()) {
            throw new IllegalStateException("Lua error: " + ret.getErrorString());
        }
        Object[] out = new Object[ret.size()];
        for (int i = 0; i < out.length; i++) out[i] = ret.get(i);
        return out;
    }

    private static java.lang.reflect.Field forceDoneField;

    // "Click to Start": GameLoadingState only continues once forceDone is set by a click; it still checks
    // done / streamer / animation loading itself before acting on it, so this cannot cut the load short
    private static void pressClickToStart() throws Exception {
        Object state = GameWindow.states.current;
        if (forceDoneField == null) {
            forceDoneField = state.getClass().getDeclaredField("forceDone");
            forceDoneField.setAccessible(true);
        }
        forceDoneField.setBoolean(state, true);
    }

    private static void applyZoom() {
        Core.getInstance().offscreenBuffer.autoZoom[0] = false;
        Core.getInstance().offscreenBuffer.setZoomAndTargetZoom(0, zooms[zoomIndex]);
    }

    // Same light and weather in every run: noon, no rain or fog, no wounds or infection progressing
    private static void normalizeScene() throws Exception {
        runLua(
            "pcall(function() getGameTime():setTimeOfDay(12.0) end)\n"
            + "pcall(function() getClimateManager():stopWeatherAndThunder() end)\n"
            + "local p = getPlayer()\n"
            + "if p then pcall(function() p:getBodyDamage():RestoreToFullHealth() end) end\n"
            + "return true\n");
    }

    private static void setPaused(boolean paused) throws Exception {
        runLua(paused ? "setGameSpeed(0)\nsetShowPausedMessage(false)\nreturn true\n" : "setGameSpeed(1)\nreturn true\n");
    }

    private static void sampleFrame(long now) {
        int fc = IsoCamera.frameState.frameCount;
        if (fc == lastFrameCount) return;
        if (lastFrameCount != -1) frameTimes.add(now - lastFrameNs);
        lastFrameCount = fc;
        lastFrameNs = now;
    }

    // Square loop E, S, W, N back to the start tile
    private static boolean moveAlongRoute(long elapsedNs) {
        IsoPlayer p = IsoPlayer.players[0];
        if (p == null) return true;
        float d = routeSpeed * (elapsedNs / 1e9f);
        float total = routeTiles * 4.0f;
        if (d >= total) {
            p.teleportTo(startX, startY, startZ);
            return true;
        }
        int leg = (int) (d / routeTiles);
        float o = d - leg * routeTiles;
        float x = startX, y = startY;
        switch (leg) {
            case 0: x += o; break;
            case 1: x += routeTiles; y += o; break;
            case 2: x += routeTiles - o; y += routeTiles; break;
            default: y += routeTiles - o; break;
        }
        p.teleportTo(x, y, startZ);
        return false;
    }

    private static String shotName(boolean ui) {
        return "pzo_bench_" + label + "_z" + Math.round(zooms[zoomIndex] * 100) + (ui ? "_ui" : "_noui") + ".png";
    }

    public static void tick() {
        if (phase == Phase.OFF || phase == Phase.DONE) return;
        long now = System.nanoTime();
        lastTickNs = now;
        try {
            if (now - benchStart > TIMEOUT_NS) {
                notes.add("timeout in phase " + phase + " (state " + stateName() + ")");
                finish(false);
                return;
            }
            String state = stateName();
            // Re-asserted every frame while measuring: a mouse-wheel scroll would otherwise change the scene
            if (phase == Phase.SETTLE || phase == Phase.ROUTE || phase == Phase.SHOT_NOUI || phase == Phase.SHOT_UI) {
                applyZoom();
            }
            if (phase.ordinal() >= Phase.WARMUP.ordinal()) {
                IsoPlayer p = IsoPlayer.players[0];
                // A dead player swaps the scene for the death screen (no vision cone, no lighting falloff), which
                // makes the run incomparable; god mode does not stop deaths already in progress from the save
                if (p == null || p.isDead()) {
                    notes.add("player died in phase " + phase + " - the bench save's character is not viable");
                    finish(false);
                    return;
                }
            }
            switch (phase) {
                case MENU:
                    if (!"MainScreenState".equals(state)) {
                        if (state.contains("Loading")) enter(Phase.LOADING);
                        break;
                    }
                    // Lua resets (for the save's mod set) drop the handler, so it is re-queued while still on the menu
                    if (now - lastQueueAttempt > MENU_REQUEUE_NS || lastQueueAttempt == 0) {
                        lastQueueAttempt = now;
                        runLua(
                            "if PZOBenchHandler then Events.OnFETick.Remove(PZOBenchHandler) end\n"
                            + "PZOBenchHandler = function()\n"
                            + "  local ms = MainScreen and MainScreen.instance\n"
                            // MainScreen ignores menu actions while its start-up delay runs
                            + "  if not ms or (ms.delay and ms.delay > 0) or not MainScreen.latestSaveWorld then return end\n"
                            + "  Events.OnFETick.Remove(PZOBenchHandler)\n"
                            + "  PZOBenchHandler = nil\n"
                            + "  print('[PZOBench] continuing ' .. tostring(MainScreen.latestSaveWorld) .. ' (' .. tostring(MainScreen.latestSaveGameMode) .. ')')\n"
                            + "  MainScreen.continueLatestSave(MainScreen.latestSaveGameMode, MainScreen.latestSaveWorld)\n"
                            + "end\n"
                            + "Events.OnFETick.Add(PZOBenchHandler)\n"
                            + "return true\n");
                    }
                    break;
                case LOADING:
                    if ("GameLoadingState".equals(state)) {
                        pressClickToStart();
                    }
                    if ("IngameState".equals(state) && IsoPlayer.players[0] != null) {
                        IsoPlayer p = IsoPlayer.players[0];
                        p.setGodMod(true);
                        p.setInvisible(true);
                        // The start tile comes straight from the save on the first in-game frame and the player is
                        // pinned there from now on, so stray input or a moving vehicle cannot shift the scene
                        p.ensureNotInVehicle();
                        startX = (int) p.getX() + 0.5f;
                        startY = (int) p.getY() + 0.5f;
                        startZ = (int) p.getZ();
                        notes.add(String.format(Locale.ROOT, "start tile %.0f,%.0f,%d", startX, startY, startZ));
                        normalizeScene();
                        enter(Phase.WARMUP);
                    }
                    break;
                case WARMUP:
                    IsoPlayer.players[0].teleportTo(startX, startY, startZ);
                    if (now - phaseStart > WARMUP_NS) {
                        PerformanceSettings.instance.setFramerateUncapped(true);
                        RenderThread.invokeOnRenderContext(() -> org.lwjglx.opengl.Display.setVSyncEnabled(false));
                        zoomIndex = 0;
                        applyZoom();
                        enter(Phase.SETTLE);
                    }
                    break;
                case SETTLE:
                    IsoPlayer.players[0].teleportTo(startX, startY, startZ);
                    if (now - phaseStart < 100_000_000L) {
                        normalizeScene();
                    }
                    if (now - phaseStart > SETTLE_NS) {
                        frameTimes.clear();
                        lastFrameCount = -1;
                        enter(Phase.ROUTE);
                    }
                    break;
                case ROUTE:
                    sampleFrame(now);
                    if (moveAlongRoute(now - phaseStart)) {
                        recordZoom();
                        setPaused(true);
                        UIManager.setVisibleAllUI(false);
                        enter(Phase.SHOT_NOUI);
                    }
                    break;
                case SHOT_NOUI:
                    IsoPlayer.players[0].teleportTo(startX, startY, startZ);
                    if (now - phaseStart > SHOT_DELAY_NS) {
                        notes.add(String.format(Locale.ROOT, "zoom %.2f: camera zoom at screenshot %.3f, player %.1f,%.1f",
                            zooms[zoomIndex], Core.getInstance().getZoom(0), IsoPlayer.players[0].getX(), IsoPlayer.players[0].getY()));
                        Core.getInstance().TakeFullScreenshot(shotName(false));
                        UIManager.setVisibleAllUI(true);
                        enter(Phase.SHOT_UI);
                    }
                    break;
                case SHOT_UI:
                    if (now - phaseStart > SHOT_DELAY_NS) {
                        Core.getInstance().TakeFullScreenshot(shotName(true));
                        setPaused(false);
                        zoomIndex++;
                        if (zoomIndex < zooms.length) {
                            applyZoom();
                            enter(Phase.SETTLE);
                        } else {
                            finish(true);
                        }
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            notes.add("error in phase " + phase + ": " + t);
            PZOLogger.warn("[BenchmarkHarness] " + t);
            if (phase != Phase.MENU) {
                finish(false);
            }
        }
    }

    private static void recordZoom() {
        long[] ft = new long[frameTimes.size()];
        for (int i = 0; i < ft.length; i++) ft[i] = frameTimes.get(i);
        Arrays.sort(ft);
        double avgMs = 0;
        for (long v : ft) avgMs += v / 1e6;
        avgMs = ft.length > 0 ? avgMs / ft.length : 0;
        double p99 = ft.length > 0 ? ft[Math.min(ft.length - 1, (int) (ft.length * 0.99))] / 1e6 : 0;
        // 1% low = average of the slowest 1% of frames
        int n1 = Math.max(1, ft.length / 100);
        double low = 0;
        for (int i = Math.max(0, ft.length - n1); i < ft.length; i++) low += ft[i] / 1e6;
        low = ft.length > 0 ? low / n1 : 0;
        if (zoomJson.length() > 0) zoomJson.append(",\n");
        zoomJson.append(String.format(Locale.ROOT,
            "    {\"zoom\": %.2f, \"frames\": %d, \"avg_ms\": %.3f, \"avg_fps\": %.1f, \"p99_ms\": %.3f, \"low1_fps\": %.1f, "
                + "\"shot_noui\": \"%s\", \"shot_ui\": \"%s\"}",
            zooms[zoomIndex], ft.length, avgMs, avgMs > 0 ? 1000.0 / avgMs : 0, p99, low > 0 ? 1000.0 / low : 0,
            shotName(false), shotName(true)));
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static synchronized void finish(boolean ok) {
        if (phase == Phase.DONE) return;
        phase = Phase.DONE;
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(outDir, "results.json")))) {
            StringBuilder ns = new StringBuilder();
            for (String n : notes) {
                if (ns.length() > 0) ns.append(", ");
                ns.append('"').append(esc(n)).append('"');
            }
            w.println("{");
            w.println("  \"label\": \"" + esc(label) + "\",");
            w.println("  \"ok\": " + ok + ",");
            w.println("  \"screen\": [" + Core.getInstance().getScreenWidth() + ", " + Core.getInstance().getScreenHeight() + "],");
            w.println("  \"route\": {\"tiles\": " + routeTiles + ", \"tiles_per_s\": " + routeSpeed + "},");
            w.println("  \"fsr_requested\": " + WorldUpscalerGovernor.isRequestedAtLaunch() + ",");
            w.println("  \"fsr_active\": " + WorldUpscalerGovernor.isActive() + ",");
            w.println("  \"fsr_hooks_installed\": " + WorldRenderScale.isInstalled() + ",");
            w.println("  \"fsr_render_scale\": " + WorldRenderScale.getRenderThreadScale() + ",");
            w.println("  \"fsr_frames_upscaled\": " + WorldUpscalerGovernor.framesUpscaled.get() + ",");
            w.println("  \"fsr_shaders_rewritten\": " + WorldRenderScale.shadersRewritten + ",");
            w.println("  \"screenshot_dir\": \"" + esc(ZomboidFileSystem.instance.getScreenshotDir()) + "\",");
            w.println("  \"notes\": [" + ns + "],");
            w.println("  \"zooms\": [");
            w.println(zoomJson);
            w.println("  ]");
            w.println("}");
        } catch (Throwable t) {
            PZOLogger.warn("[BenchmarkHarness] Could not write results: " + t);
        }
        PZOLogger.success("[BenchmarkHarness] Benchmark finished (ok=" + ok + "), quitting to desktop");
        // The save is a disposable bench copy, so a normal quit is fine; escalate if it does not happen
        try {
            Core.getInstance().quitToDesktop();
        } catch (Throwable ignored) {}
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(30_000L);
                PZOLogger.warn("[BenchmarkHarness] Still running 30s after quit request; halting");
                Runtime.getRuntime().halt(0);
            } catch (InterruptedException ignored) {}
        }, "PZO-BenchQuit");
        t.setDaemon(true);
        t.start();
    }
}
