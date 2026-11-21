package com.pzoptimizer.pzoe.weather;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.Type;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/**
 * PZOE weather has the last word on the climate, but when another mod sets a climate value, that value is a forced
 * event PZOE follows instead of fighting it.
 *
 * Mods set the weather through three layers of each ClimateFloat/ClimateBool: modded (Extreme Weather), override
 * (Unseasonal Weather) and admin (Unseasonal Weather's fallback, the admin climate panel). Vanilla's own weather
 * period and sandbox weather also use the override layer, so the setters are hooked to see who wrote last: writes made
 * inside vanilla's updateSandboxOverrides / WeatherPeriod.update count as vanilla, anything else is a mod.
 *
 * While a value is driven, WeatherFronts keeps the mod's value for it: no rain bursts or onset, no regional thinning,
 * no own heat, cold, freezing rain or hail on it, no snow flip. Gusts, the looks and everything reading the result
 * stay on. When the mod lets go, PZOE eases back to its own weather.
 *
 * MP: Unseasonal Weather only runs on the server and the override layer reaches clients through vanilla's climate
 * packets, where the writer can't be told. So the server sends its mask with the weather state and a client ORs it
 * with what its own Lua drives (Extreme Weather sets the modded layer on clients too).
 */
public final class WeatherAuthority {
    static final int PRECIP = 3, TEMP = 4, FOG = 5, WIND = 6, ANGLE = 7, CLOUD = 8, DAYLIGHT = 11;
    static final int SNOW_BIT = 1 << 16;
    private static final int[] TRACKED = {PRECIP, TEMP, FOG, WIND, ANGLE, CLOUD, DAYLIGHT};
    private static final int N = 16;

    private static final boolean[] modOn = new boolean[N], ovMod = new boolean[N];
    private static final float[] modInterp = new float[N];
    private static boolean snowModOn, snowOvMod;
    private static boolean inVanilla;

    // Bits: 1 << float id, SNOW_BIT for the snow flag
    static volatile int mask, remote;
    private static final float[] hold = new float[N];
    private static float snowHold;
    private static int loggedMask;

    private WeatherAuthority() {}

    // Setter hooks (method entry of the vanilla setters)

    public static void fModded(zombie.iso.weather.ClimateManager.ClimateFloat f, boolean b) {
        int id = idOf(f);
        if (id >= 0) modOn[id] = b;
    }

    public static void fModInterp(zombie.iso.weather.ClimateManager.ClimateFloat f, float v) {
        int id = idOf(f);
        if (id >= 0) modInterp[id] = v;
    }

    public static void fOverride(zombie.iso.weather.ClimateManager.ClimateFloat f) {
        int id = idOf(f);
        if (id >= 0) ovMod[id] = !inVanilla;
    }

    public static void fEnableOverride(zombie.iso.weather.ClimateManager.ClimateFloat f, boolean b) {
        int id = idOf(f);
        if (id >= 0) ovMod[id] = b && !inVanilla;
    }

    public static void bModded(zombie.iso.weather.ClimateManager.ClimateBool c, boolean b) {
        if (c != null && c.getID() == 0) snowModOn = b;
    }

    public static void bOverride(zombie.iso.weather.ClimateManager.ClimateBool c) {
        if (c != null && c.getID() == 0) snowOvMod = !inVanilla;
    }

    public static void bEnableOverride(zombie.iso.weather.ClimateManager.ClimateBool c, boolean b) {
        if (c != null && c.getID() == 0) snowOvMod = b && !inVanilla;
    }

    public static void vanillaBegin() {
        inVanilla = true;
    }

    public static void vanillaEnd() {
        inVanilla = false;
    }

    private static int idOf(zombie.iso.weather.ClimateManager.ClimateFloat f) {
        if (f == null) return -1;
        int id = f.getID();
        return id >= 0 && id < N ? id : -1;
    }

    // Per frame (WeatherFronts.frame)

    static void frame(zombie.iso.weather.ClimateManager cm, double dtHours) {
        // An exception inside a vanilla section would leave the flag up; clear it once a frame
        inVanilla = false;
        boolean client = zombie.network.GameClient.client;
        int m = 0;
        for (int id : TRACKED) {
            zombie.iso.weather.ClimateManager.ClimateFloat f = cm.getClimateFloat(id);
            if (f == null) continue;
            boolean admin = !client && f.isEnableAdmin();
            boolean modded = modOn[id] && modInterp[id] > 0f;
            boolean over = !client && ovMod[id] && f.isEnableOverride() && f.getOverrideInterpolate() > 0f;
            if (admin || modded || over) m |= 1 << id;
        }
        zombie.iso.weather.ClimateManager.ClimateBool sb = cm.getClimateBool(0);
        if (sb != null && ((!client && sb.isEnableAdmin()) || snowModOn || (!client && snowOvMod && sb.isEnableOverride()))) m |= SNOW_BIT;
        if (client) m |= remote;
        mask = m;
        if (m != loggedMask) {
            loggedMask = m;
            PZOLogger.info("[PZOE weather] " + (m == 0 ? "no other mod drives the climate now" : "another mod drives " + names(m) + ": following it"));
        }

        float k = dtHours > 0 ? (float) (1.0 - Math.exp(-dtHours / 0.08)) : 0f;
        for (int id : TRACKED) {
            float t = (m & (1 << id)) != 0 ? 1f : 0f;
            hold[id] = dtHours < 0 || dtHours > 2 ? t : hold[id] + (t - hold[id]) * k;
        }
        float ts = (m & SNOW_BIT) != 0 ? 1f : 0f;
        snowHold = dtHours < 0 || dtHours > 2 ? ts : snowHold + (ts - snowHold) * k;
    }

    static boolean drives(int id) {
        return (mask & (1 << id)) != 0;
    }

    static boolean drivesSnow() {
        return (mask & SNOW_BIT) != 0;
    }

    /**
     * Puts the mod's values back into a mix where a mod drives them (eased by hold). p..t are the climate values as
     * calculated (they already hold the mod's value), windGust is that wind plus PZOE's gusts.
     */
    static void arbitrate(WeatherFronts.Mix m, float p, float windGust, float a, float f, float c, float d, float t, boolean vSnow) {
        float hp = hold[PRECIP], ht = hold[TEMP];
        if (hp > 0f) {
            m.precip = lerp(hp, m.precip, p);
            m.hail *= 1f - hp;
        }
        if (hold[WIND] > 0f) m.wind = lerp(hold[WIND], m.wind, windGust);
        // Angle values map to degrees piecewise, so no blending: whoever holds it more
        if (hold[ANGLE] > 0.5f) m.angle = a;
        if (hold[FOG] > 0f) m.fog = lerp(hold[FOG], m.fog, f);
        if (hold[CLOUD] > 0f) m.cloud = lerp(hold[CLOUD], m.cloud, c);
        if (hold[DAYLIGHT] > 0f) m.daylight = lerp(hold[DAYLIGHT], m.daylight, d);
        if (ht > 0f) {
            m.temp = lerp(ht, m.temp, t);
            // The heat look follows the mod's heat instead of PZOE's heat wave
            m.heat = lerp(ht, m.heat, WeatherFronts.smooth(27f, 35f, t));
        }
        if (snowHold > 0.5f || hp > 0.5f) m.snow = vSnow;
    }

    private static float lerp(float k, float a, float b) {
        return a + (b - a) * k;
    }

    // Sync

    static String encode() {
        return "mod=" + mask;
    }

    static boolean apply(String k, String v) {
        if (!k.equals("mod")) return false;
        if (zombie.network.GameClient.client) {
            try {
                remote = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return true;
    }

    static void clearRemote() {
        remote = 0;
    }

    static String stateText() {
        return "weatherModDriven=" + (mask == 0 ? "none" : names(mask));
    }

    private static String names(int m) {
        StringBuilder sb = new StringBuilder();
        String[] n = {"desat", "light", "nightStrength", "precip", "temp", "fog", "wind", "windAngle", "cloud", "ambient", "viewDist", "daylight", "humidity"};
        for (int id = 0; id < n.length; id++) if ((m & (1 << id)) != 0) sb.append(sb.length() == 0 ? "" : ",").append(n[id]);
        if ((m & SNOW_BIT) != 0) sb.append(sb.length() == 0 ? "" : ",").append("snow");
        return sb.toString();
    }

    // Patches

    public static final String FLOAT_CLASS = "zombie/iso/weather/ClimateManager$ClimateFloat";
    public static final String BOOL_CLASS = "zombie/iso/weather/ClimateManager$ClimateBool";
    private static final String SELF = "com/pzoptimizer/pzoe/weather/WeatherAuthority";

    /** ClimateFloat: setEnableModded, setModdedInterpolate, setOverride, setEnableOverride, setOverrideValue report in. */
    public static boolean patchFloat(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            String hook = switch (m.name + m.desc) {
                case "setEnableModded(Z)V" -> "fModded";
                case "setModdedInterpolate(F)V" -> "fModInterp";
                case "setOverride(FF)V" -> "fOverride";
                case "setEnableOverride(Z)V", "setOverrideValue(Z)V" -> "fEnableOverride";
                default -> null;
            };
            if (hook == null) continue;
            entry(m, hook, "L" + FLOAT_CLASS + ";", !hook.equals("fOverride"));
            n++;
        }
        if (n != 5) {
            PZOLogger.warn("[PZOE weather] ClimateFloat didn't match (" + n + " of 5 setters): other mods' climate not followed");
            return false;
        }
        return true;
    }

    /** ClimateBool: setEnableModded, setOverride, setEnableOverride report in. */
    public static boolean patchBool(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            String hook = switch (m.name + m.desc) {
                case "setEnableModded(Z)V" -> "bModded";
                case "setOverride(Z)V" -> "bOverride";
                case "setEnableOverride(Z)V" -> "bEnableOverride";
                default -> null;
            };
            if (hook == null) continue;
            entry(m, hook, "L" + BOOL_CLASS + ";", !hook.equals("bOverride"));
            n++;
        }
        if (n != 3) {
            PZOLogger.warn("[PZOE weather] ClimateBool didn't match (" + n + " of 3 setters): other mods' snow not followed");
            return false;
        }
        return true;
    }

    // At method entry: hook(this) or hook(this, first argument)
    private static void entry(MethodNode m, String hook, String selfDesc, boolean withArg) {
        InsnList l = new InsnList();
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        String desc;
        if (withArg) {
            Type arg = Type.getArgumentTypes(m.desc)[0];
            l.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), 1));
            desc = "(" + selfDesc + arg.getDescriptor() + ")V";
        } else {
            desc = "(" + selfDesc + ")V";
        }
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, hook, desc, false));
        m.instructions.insert(l);
    }

    /** ClimateManager.update: vanillaBegin/End around updateSandboxOverrides() and weatherPeriod.update(D). Returns how many. */
    static int patchVanillaSections(MethodNode m) {
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            boolean sandbox = mi.owner.equals(WeatherCore.CLIMATE_CLASS) && mi.name.equals("updateSandboxOverrides") && mi.desc.equals("()V");
            boolean period = mi.owner.equals("zombie/iso/weather/WeatherPeriod") && mi.name.equals("update") && mi.desc.equals("(D)V");
            if (!sandbox && !period) continue;
            m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "vanillaBegin", "()V", false));
            MethodInsnNode end = new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "vanillaEnd", "()V", false);
            m.instructions.insert(insn, end);
            insn = end;
            n++;
        }
        return n;
    }
}
