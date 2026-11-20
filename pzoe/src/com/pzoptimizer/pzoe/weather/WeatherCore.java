package com.pzoptimizer.pzoe.weather;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.IntInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/**
 * PZOE weather: the sim side. Runs where vanilla runs its climate sim (single player and the dedicated server, never
 * an MP client), right after WeatherPeriod.update on every climate tick (once a game minute). An MP client never
 * simulates; it only gets the server's state (applyRemote) and eases its looks towards it. If the server doesn't run
 * PZOE's engine, no state arrives and the client's behaviour stays vanilla.
 *
 * Gameplay vs looks: the sim may only write vanilla's climate floats (what gameplay reads) while the PZOEWeather.Sim
 * sandbox option is on. Everything it wants to show goes to WeatherLook, which only render code reads. With Sim off
 * the game plays exactly like vanilla and the looks still get their extra detail.
 *
 * No GL in here: this class loads on dedicated servers.
 */
public final class WeatherCore {
    static final int VERSION = 1;
    private static final String SAVE_KEY = "PZOE_Weather";
    private static final String CLIMATE = "zombie/iso/weather/ClimateManager";

    // Sandbox (SP/server read them, an MP client gets the server's values in the state)
    static volatile boolean sim = true, effects = true;
    // MP client: a server running PZOE weather sent its state this session
    static volatile boolean serverEngine;
    static volatile String role = "none";

    private static long ticks;
    private static long lastLogMs;
    private static double savedAt = -1;
    private static boolean loaded;
    private static Object lastCell;
    private static String lastRemote = "";

    private WeatherCore() {}

    /** True where our sim may drive gameplay: SP or server with Sim on, or a client of such a server. */
    public static boolean behaviourOn() {
        if (zombie.network.GameClient.client) return serverEngine && sim;
        return sim;
    }

    /** True where gameplay effects (hearing, sight, footing, grip, body) may apply. */
    public static boolean effectsOn() {
        if (zombie.network.GameClient.client) return serverEngine && effects;
        return effects;
    }

    /** ClimateManager.update, after weatherPeriod.update, on climate ticks where vanilla simulates. */
    public static void simTick(zombie.iso.weather.ClimateManager cm) {
        try {
            tick(cm);
        } catch (Throwable t) {
            // A broken tick must never take the climate sim down with it
            if (ticks++ % 600 == 0) PZOLogger.warn("[PZOE weather] tick failed: " + t);
        }
    }

    private static void tick(zombie.iso.weather.ClimateManager cm) {
        ticks++;
        role = zombie.network.GameServer.server ? "server" : "sp";
        sim = sandboxBool("PZOEWeather.Sim", true);
        effects = sandboxBool("PZOEWeather.Effects", true);

        zombie.GameTime gt = zombie.GameTime.getInstance();
        if (gt == null) return;
        // A new cell means another save: its state comes from its own mod data
        Object cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell != lastCell) {
            lastCell = cell;
            loaded = false;
        }
        double hours = gt.getWorldAgeHours();
        if (!loaded) {
            loaded = true;
            load(gt);
            savedAt = hours;
        }
        WeatherFronts.climateTick(cm, hours);
        WeatherStorm.climateTick(hours);
        if (hours - savedAt > 1.0 / 6.0 || hours < savedAt) {
            savedAt = hours;
            save(gt);
        }
        logMaybe(cm);
    }

    static int sandboxInt(String name, int dflt) {
        try {
            zombie.SandboxOptions.SandboxOption o = zombie.SandboxOptions.instance.getOptionByName(name);
            if (o != null && o.asConfigOption().getValueAsObject() instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {
        }
        return dflt;
    }

    static boolean sandboxBool(String name, boolean dflt) {
        try {
            zombie.SandboxOptions.SandboxOption o = zombie.SandboxOptions.instance.getOptionByName(name);
            if (o != null && o.asConfigOption() instanceof zombie.config.BooleanConfigOption b) return b.getValue();
        } catch (Throwable ignored) {
        }
        return dflt;
    }

    // Save: a short key=value string in GameTime mod data, so a save without PZOE stays a plain vanilla save

    private static void load(zombie.GameTime gt) {
        String s = null;
        try {
            Object v = gt.getModData().rawget(SAVE_KEY);
            if (v instanceof String str) s = str;
        } catch (Throwable ignored) {
        }
        if (s != null) {
            for (String part : s.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) WeatherFronts.apply(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        PZOLogger.info("[PZOE weather] " + role + " state " + (s == null ? "new" : "loaded (" + s + ")"));
    }

    private static void save(zombie.GameTime gt) {
        try {
            gt.getModData().rawset(SAVE_KEY, "v=" + VERSION + ";" + WeatherFronts.saveText());
        } catch (Throwable ignored) {
        }
    }

    // MP sync. The server's Lua sends encode() every 10 game minutes and to each client that says hello.

    /** The state for clients, or null where this isn't a server running the sim. */
    public static String encodeForClients() {
        if (!zombie.network.GameServer.server || ticks == 0) return null;
        return "v=" + VERSION + ";sim=" + (sim ? 1 : 0) + ";fx=" + (effects ? 1 : 0) + ";" + WeatherFronts.encode() + ";" + WeatherEvents.encode() + ";" + WeatherRegional.encode()
            + ";gsd=" + String.format(java.util.Locale.ROOT, "%.2f", com.pzoptimizer.pzoe.GroundSnow.depthNow());
    }

    private static String lastSig = "";

    /**
     * The server's Lua asks every game minute: the state when something clients should see soon has changed
     * (an event list, freezing rain, hail starting or stopping, the build-up moving a step), otherwise null.
     */
    public static String encodeIfChanged() {
        String s = encodeForClients();
        if (s == null) return null;
        String sig = "sim" + sim + effects + WeatherEvents.encode().replaceAll("ice=[0-9.]+", "")
            + Math.round(WeatherEvents.hail * 4) + Math.round(WeatherFronts.preTarget * 5) + Math.round(WeatherFronts.mistTarget * 5) + Math.round(WeatherRegional.angleDeg / 15f)
            + Math.round(com.pzoptimizer.pzoe.GroundSnow.depthNow() * 5);
        sig = sig.replaceAll("hail=[0-9.]+", "");
        if (sig.equals(lastSig)) return null;
        lastSig = sig;
        return s;
    }

    /** MP client: the server's state, or "" at game start to forget the last server's. */
    public static void applyRemote(String s) {
        if (!zombie.network.GameClient.client) return;
        role = "client";
        if (s == null || s.isEmpty()) {
            serverEngine = false;
            lastRemote = "";
            WeatherFronts.clearRemote();
            return;
        }
        int v = 0;
        boolean ns = sim, nf = effects;
        java.util.List<String[]> rest = new java.util.ArrayList<>();
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq), val = part.substring(eq + 1);
            switch (k) {
                case "v" -> v = parseInt(val);
                case "sim" -> ns = "1".equals(val);
                case "fx" -> nf = "1".equals(val);
                default -> rest.add(new String[] {k, val});
            }
        }
        // A newer server format: stay vanilla rather than guess
        if (v != VERSION) {
            if (!s.equals(lastRemote)) PZOLogger.warn("[PZOE weather] server sends weather v" + v + ", this PZOE has v" + VERSION + ": staying vanilla (update PZO Enhancements)");
            serverEngine = false;
            lastRemote = s;
            return;
        }
        sim = ns;
        effects = nf;
        for (String[] kv : rest) {
            // The server's wetness is its own bookkeeping
            if (!kv[0].equals("wet")) WeatherFronts.apply(kv[0], kv[1]);
        }
        if (!serverEngine) PZOLogger.info("[PZOE weather] server state " + s);
        serverEngine = true;
        lastRemote = s;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static void logMaybe(zombie.iso.weather.ClimateManager cm) {
        long now = System.currentTimeMillis();
        if (now - lastLogMs < 30_000) return;
        lastLogMs = now;
        PZOLogger.info("[PZOE weather] " + stateText() + " precip=" + f2(cm.getPrecipitationIntensity()) + " fog=" + f2(cm.getFogIntensity())
            + " wind=" + f2(cm.getWindIntensity()) + " cloud=" + f2(cm.getCloudIntensity()) + " temp=" + f2(cm.getTemperature()));
    }

    private static String f2(float v) {
        return String.format("%.2f", v);
    }

    public static String stateText() {
        return "weatherRole=" + role + ";weatherSim=" + (behaviourOn() ? 1 : 0) + ";weatherFx=" + (effectsOn() ? 1 : 0)
            + ";weatherServer=" + (serverEngine ? 1 : 0) + ";" + WeatherFronts.stateText() + ";" + WeatherLook.stateText();
    }

    // ClimateManager patches

    public static final String CLIMATE_CLASS = CLIMATE;
    public static final String PUDDLES_CLASS = "zombie/iso/IsoPuddles";

    /** IsoPuddles.update(ClimateManager): WeatherFronts.puddles before each return. */
    public static boolean patchPuddlesUpdate(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            if (!m.name.equals("update") || !m.desc.equals("(L" + CLIMATE + ";)V")) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList l = new InsnList();
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                l.add(new VarInsnNode(Opcodes.ALOAD, 1));
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherFronts", "puddles", "(Lzombie/iso/IsoPuddles;L" + CLIMATE + ";)V", false));
                m.instructions.insertBefore(insn, l);
                n++;
            }
        }
        return n > 0;
    }

    /** update(): simTick after weatherPeriod.update, WeatherFronts.post before updateWindTick. updateFx(): every IsoWeatherFX setter goes through WeatherLook. */
    public static boolean patchClimate(ClassNode node) {
        int ticked = 0, posted = 0, fx = 0, body = 0;
        for (MethodNode m : node.methods) {
            if (m.name.equals("update") && m.desc.equals("()V")) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode mi
                        && mi.owner.equals("zombie/iso/weather/WeatherPeriod") && mi.name.equals("update") && mi.desc.equals("(D)V")) {
                        InsnList l = new InsnList();
                        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherCore", "simTick", "(L" + CLIMATE + ";)V", false));
                        AbstractInsnNode last = l.getLast();
                        m.instructions.insert(insn, l);
                        insn = last;
                        ticked++;
                    }
                }
            }
            if (m.name.equals("update") && m.desc.equals("()V")) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode mi && mi.owner.equals(CLIMATE) && mi.name.equals("updateWindTick") && mi.desc.equals("()V")) {
                        InsnList l = new InsnList();
                        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherFronts", "post", "(L" + CLIMATE + ";)V", false));
                        m.instructions.insertBefore(insn, l);
                        posted++;
                    }
                }
            } else if (m.name.equals("getAirTemperatureForCharacter") && m.desc.equals("(Lzombie/characters/IsoGameCharacter;Z)F")) {
                body += WeatherEffects.patchBodyAir(m);
            } else if (m.name.equals("updateFx") && m.desc.equals("()V")) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL || !(insn instanceof MethodInsnNode mi) || !mi.owner.equals("zombie/iso/weather/fx/IsoWeatherFX")) continue;
                    int id = WeatherLook.fxId(mi.name);
                    if (id < 0) continue;
                    InsnList l = new InsnList();
                    if (mi.desc.equals("(F)V")) {
                        l.add(id <= 5 ? new InsnNode(Opcodes.ICONST_0 + id) : new IntInsnNode(Opcodes.BIPUSH, id));
                        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherLook", "fx", "(FI)F", false));
                    } else if (mi.desc.equals("(Z)V")) {
                        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherLook", "fxSnow", "(Z)Z", false));
                    } else {
                        continue;
                    }
                    m.instructions.insertBefore(insn, l);
                    fx++;
                }
            }
        }
        if (ticked != 1 || posted != 1 || fx != WeatherLook.FX_COUNT + 1 || body < 1) {
            PZOLogger.warn("[PZOE weather] ClimateManager didn't match (sim " + ticked + ", post " + posted + ", fx " + fx + ", body " + body + "): left vanilla");
            return false;
        }
        return true;
    }
}
