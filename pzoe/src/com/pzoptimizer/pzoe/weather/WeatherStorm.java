package com.pzoptimizer.pzoe.weather;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/**
 * PZOE weather: thunder and lightning.
 *
 * Look (SP and clients, from ThunderStorm.enqueueThunderEvent): a lightning flash lights the world from where it struck.
 * Vanilla brightens everything evenly; while the flash lasts I point PZOE's sun shadow (Sun and Cloud Shadows card)
 * at the strike, low over the horizon, so walls and trees throw short sharp shadows away from it, flickering like a
 * real strike (two or three pulses).
 *
 * Gameplay (SP and server, from ThunderStorm.triggerThunderEvent): thunder close by draws zombies to where it struck,
 * after the sound has had time to travel, and a strike on dry ground can start a fire. Each has its own sandbox
 * switch under PZOEWeather and needs the Effects switch.
 */
public final class WeatherStorm {
    private static final String THUNDER = "zombie/iso/weather/ThunderStorm";

    // Last flash, main thread
    private static long flashAt;
    private static float flashStrength, flashDirX, flashDirY = 1f;

    // Thunder sounds waiting to reach zombies (world age hours when they land)
    private static final int MAX_PENDING = 16;
    private static final double[] pendingAt = new double[MAX_PENDING];
    private static final int[] pendingX = new int[MAX_PENDING], pendingY = new int[MAX_PENDING], pendingR = new int[MAX_PENDING];
    private static int pendingCount;

    private WeatherStorm() {}

    /** First thing in ThunderStorm.enqueueThunderEvent (SP and clients): the flash. */
    public static void thunderSeen(int x, int y, boolean strike, boolean lightning, boolean rumble) {
        if (!lightning) return;
        try {
            zombie.characters.IsoPlayer pl = zombie.characters.IsoPlayer.getInstance();
            if (pl == null) return;
            float dx = x - pl.getX(), dy = y - pl.getY();
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float s = 1f - Math.min(1f, dist / 2500f);
            if (s <= 0.05f) return;
            flashAt = System.currentTimeMillis();
            flashStrength = s;
            if (dist > 1f) {
                flashDirX = dx / dist;
                flashDirY = dy / dist;
            }
        } catch (Throwable ignored) {
        }
    }

    /** Flash brightness now: three quick pulses over about 0.4 s, the first the brightest. */
    static float flash() {
        long t = System.currentTimeMillis() - flashAt;
        if (flashAt == 0 || t > 450) return 0f;
        float p;
        if (t < 70) p = 1f;
        else if (t < 130) p = 0.15f;
        else if (t < 200) p = 0.7f;
        else if (t < 290) p = 0.1f;
        else p = 0.45f * (1f - (t - 290) / 160f);
        return p * flashStrength;
    }

    /**
     * PixelLight, after it worked out the sun: during a flash the shadow light comes from the strike. Returns
     * {x, y, rise, vis} or null when there's no flash.
     */
    public static float[] flashSun() {
        if (!lightningLook) return null;
        float f = flash();
        if (f <= 0.02f) return null;
        // 15 degrees up, in the depth metric PixelLight uses (a level is 2.45 squares tall)
        return new float[] {flashDirX, flashDirY, (float) (Math.tan(Math.toRadians(15.0)) / 2.45), Math.min(1f, f * 1.4f)};
    }

    static volatile boolean lightningLook = true;
    private static float boltSeed;
    private static long boltFor;

    /** {screen x, screen y (down), flash 0..1, seed} for the bolt the screen pass draws, flash 0 when none. */
    public static float[] bolt() {
        float f = lightningLook ? flash() : 0f;
        if (f <= 0.02f) return new float[] {0f, 0f, 0f, 0f};
        if (boltFor != flashAt) {
            boltFor = flashAt;
            boltSeed = (float) ((flashAt % 9973) / 97.0);
        }
        float sx = flashDirX - flashDirY, sy = (flashDirX + flashDirY) * 0.5f;
        float l = (float) Math.sqrt(sx * sx + sy * sy);
        if (l < 1e-4f) l = 1f;
        return new float[] {sx / l, sy / l, f, boltSeed};
    }

    /** Dev menu (SP/server): a lightning strike 40-150 squares from a player, in a random direction. */
    static String strikeNearPlayer() {
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        zombie.characters.IsoPlayer pl = null;
        if (zombie.network.GameServer.server) {
            java.util.List<zombie.characters.IsoPlayer> list = zombie.network.GameServer.getPlayers();
            if (!list.isEmpty()) pl = list.get(zombie.core.random.Rand.Next(list.size()));
        } else {
            pl = zombie.characters.IsoPlayer.getInstance();
        }
        if (pl == null || cm == null) return "No player to strike near";
        double a = zombie.core.random.Rand.Next(360) * Math.PI / 180.0;
        float d = 40 + zombie.core.random.Rand.Next(110);
        int x = (int) (pl.getX() + Math.cos(a) * d), y = (int) (pl.getY() + Math.sin(a) * d);
        cm.getThunderStorm().triggerThunderEvent(x, y, true, true, true);
        return "Lightning strike " + Math.round(d) + " squares away";
    }

    /** First thing in ThunderStorm.triggerThunderEvent (SP and server): gameplay. */
    public static void thunderHeard(int x, int y, boolean strike, boolean lightning, boolean rumble) {
        try {
            if (zombie.network.GameClient.client || !WeatherCore.effectsOn()) return;
            zombie.GameTime gt = zombie.GameTime.getInstance();
            if (gt == null) return;
            if (strike && WeatherEffects.on("PZOEWeather.ThunderDraws", true)) {
                // Thunder travels about 340 m a second and a square is about a metre. A game day lasts
                // getMinutesPerDay real minutes, so that real delay in game hours is s * 24 / (minutesPerDay * 60).
                zombie.characters.IsoPlayer pl = nearestPlayer(x, y);
                float dist = pl == null ? 0f : (float) Math.hypot(pl.getX() - x, pl.getY() - y);
                if (pl != null && dist < 300f) {
                    double delayH = (dist / 340.0) * 24.0 / (Math.max(1.0, gt.getMinutesPerDay()) * 60.0);
                    queue(gt.getWorldAgeHours() + delayH, x, y, (int) (60 * WeatherEffects.strength()));
                }
            }
            if (strike && WeatherEffects.on("PZOEWeather.LightningFires", false)) WeatherEffects.maybeStrikeFire(x, y);
        } catch (Throwable t) {
            PZOLogger.warn("[PZOE weather] thunder gameplay failed: " + t);
        }
    }

    private static zombie.characters.IsoPlayer nearestPlayer(int x, int y) {
        zombie.characters.IsoPlayer best = null;
        float bd = Float.MAX_VALUE;
        java.util.List<zombie.characters.IsoPlayer> list = zombie.network.GameServer.server ? zombie.network.GameServer.getPlayers()
            : java.util.Arrays.asList(zombie.characters.IsoPlayer.players);
        for (zombie.characters.IsoPlayer p : list) {
            if (p == null) continue;
            float d = (float) Math.hypot(p.getX() - x, p.getY() - y);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static void queue(double at, int x, int y, int radius) {
        if (pendingCount >= MAX_PENDING || radius <= 0) return;
        pendingAt[pendingCount] = at;
        pendingX[pendingCount] = x;
        pendingY[pendingCount] = y;
        pendingR[pendingCount] = radius;
        pendingCount++;
    }

    /** Climate tick (SP/server): thunder that has reached the area draws zombies. */
    static void climateTick(double hours) {
        for (int i = 0; i < pendingCount; ) {
            if (pendingAt[i] <= hours) {
                zombie.WorldSoundManager.instance.addSound(null, pendingX[i], pendingY[i], 0, pendingR[i], pendingR[i]);
                pendingCount--;
                pendingAt[i] = pendingAt[pendingCount];
                pendingX[i] = pendingX[pendingCount];
                pendingY[i] = pendingY[pendingCount];
                pendingR[i] = pendingR[pendingCount];
            } else {
                i++;
            }
        }
    }

    // ThunderStorm patch: both entry points get a call to me first, with the same arguments

    public static final String THUNDER_CLASS = THUNDER;

    public static boolean patchThunder(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            String hook = m.desc.equals("(IIZZZ)V") ? switch (m.name) {
                case "enqueueThunderEvent" -> "thunderSeen";
                case "triggerThunderEvent" -> "thunderHeard";
                default -> null;
            } : null;
            if (hook == null) continue;
            InsnList l = new InsnList();
            for (int i = 1; i <= 2; i++) l.add(new VarInsnNode(Opcodes.ILOAD, i));
            for (int i = 3; i <= 5; i++) l.add(new VarInsnNode(Opcodes.ILOAD, i));
            l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherStorm", hook, "(IIZZZ)V", false));
            m.instructions.insert(l);
            n++;
        }
        return n == 2;
    }
}
