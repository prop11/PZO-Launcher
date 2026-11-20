package com.pzoptimizer.pzoe.weather;

/**
 * PZOE weather phase 4: weather that differs across the map.
 *
 * A coverage map over the world (two layers of noise, cells a few hundred squares across) drifts with the wind. While
 * vanilla says it's raining, rain only falls where the map is covered: light rain comes as scattered showers you can
 * see passing over, heavy rain covers nearly everything. The drift is integrated from the wind where the sim runs and
 * sent to clients, which carry it on between updates, so a change of wind never makes the map jump.
 *
 * With Sim and the PZOEWeather.Regional sandbox option on, the player's local rain and clouds become their gameplay
 * values (SP: the whole loaded area follows the player; MP: each client for its own player). The server keeps
 * vanilla's values for everything it runs itself (rain barrels, crops, puddles far from players), so a client under a
 * dry gap may still see barrels fill: a known limit, not worth patching every reader of the rain.
 *
 * Looks, every mode: a storm off to one side (a covered patch with rain nearby, or a front on its way) darkens and
 * cools that side of the screen. With Sim off the player's own square always matches vanilla; only the distance shows
 * the regions.
 */
public final class WeatherRegional {
    // Where the sim runs: seed and drift; clients get them from the server and carry the drift on
    static volatile double seedX, seedY;
    static volatile double driftX, driftY;
    static volatile float angleDeg = 90f;
    private static final float SPEED = 260f; // squares a game hour at full wind
    private static float windNow;

    // Per frame
    private static float local = 1f;
    private static float stormX, stormY, stormS;
    static volatile float[] storm = {0f, 0f, 0f};
    private static int frame;

    private WeatherRegional() {}

    static boolean on() {
        return WeatherCore.sandboxBool("PZOEWeather.Regional", true);
    }

    /** Climate tick (SP/server): seed, wind direction and the drift so far. */
    static void climateTick(zombie.iso.weather.ClimateManager cm, float h) {
        double off = cm.getSimplexOffsetA();
        seedX = (off * 7.31) % 1000.0;
        seedY = (off * 3.17) % 1000.0;
        float deg = WeatherLook.angleDegrees(cm.getWindAngleIntensity());
        angleDeg = deg;
        windNow = cm.getWindIntensity();
        carry(h);
    }

    private static void carry(float h) {
        double a = Math.toRadians(angleDeg);
        double sp = SPEED * (0.3 + 0.7 * windNow);
        driftX += Math.cos(a) * sp * h;
        driftY += Math.sin(a) * sp * h;
        // Keep the numbers small; the noise doesn't care where it is
        driftX %= 100000.0;
        driftY %= 100000.0;
    }

    /** 0..1 coverage at a square. */
    static float coverage(double x, double y) {
        double px = x - driftX, py = y - driftY;
        double n = 0.65 * zombie.iso.weather.SimplexNoise.noise(px / 700.0 + seedX, py / 700.0 + seedY)
            + 0.35 * zombie.iso.weather.SimplexNoise.noise(px / 260.0 + seedY, py / 260.0 - seedX);
        return (float) (0.5 + 0.5 * n);
    }

    /** How much of vanilla's rain falls at a point of this coverage: heavier rain covers more of the map. */
    static float factor(float cov, float p) {
        float c = 0.35f + 0.65f * WeatherFronts.smooth(0f, 0.6f, p);
        return WeatherFronts.smooth(1f - c - 0.12f, 1f - c + 0.12f, cov);
    }

    /** Per frame from WeatherFronts: local rain into the gameplay mix, the storm direction for the looks. */
    static void apply(WeatherFronts.Mix ms, boolean sim, float vanillaP, float pre, float dtHours) {
        if (zombie.network.GameServer.server) return;
        boolean reg = on();
        // MP clients carry the drift on between the server's updates
        if (zombie.network.GameClient.client && dtHours > 0f) {
            windNow = zombie.iso.weather.ClimateManager.getInstance().getWindIntensity();
            carry(dtHours);
        }
        zombie.characters.IsoPlayer pl = zombie.characters.IsoPlayer.getInstance();
        if (pl == null) return;
        double x = pl.getX(), y = pl.getY();
        float k = (float) (1.0 - Math.exp(-dtHours / 0.05));
        if (reg && sim && vanillaP > 0.01f) {
            float cov = coverage(x, y);
            float f = factor(cov, vanillaP);
            local += (f - local) * k;
            ms.precip = WeatherFronts.clamp(ms.precip * local * (0.85f + 0.3f * cov), 0f, 1f);
            ms.hail *= local;
            ms.cloud = Math.max(ms.cloud * (0.55f + 0.45f * local), ms.cloud * 0.5f);
        } else {
            local += (1f - local) * k;
        }

        // Storm direction: every 8th frame is plenty
        if ((frame++ & 7) != 0) return;
        float sx = 0f, sy = 0f;
        if (reg && vanillaP > 0.05f) {
            float here = factor(coverage(x, y), vanillaP);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                float s = 0f;
                for (int r = 1; r <= 2; r++) s += factor(coverage(x + dx * 450.0 * r, y + dy * 450.0 * r), vanillaP) / r;
                float w = Math.max(0f, s / 1.5f - here) * vanillaP;
                sx += dx * w;
                sy += dy * w;
            }
        }
        // A front on its way shows upwind before it arrives (clouds drift along the wind angle)
        if (pre > 0.05f) {
            double a = Math.toRadians(angleDeg);
            sx -= (float) Math.cos(a) * pre * 0.8f;
            sy -= (float) Math.sin(a) * pre * 0.8f;
        }
        float len = (float) Math.sqrt(sx * sx + sy * sy);
        float tx = 0f, ty = 0f, ts = 0f;
        if (len > 1e-3f) {
            // World direction to screen: x - y across, (x + y) / 2 down
            float scx = sx - sy, scy = (sx + sy) * 0.5f;
            float sl = (float) Math.sqrt(scx * scx + scy * scy);
            tx = scx / sl;
            ty = scy / sl;
            ts = Math.min(1f, len);
        }
        float kk = 0.15f;
        stormX += (tx - stormX) * kk;
        stormY += (ty - stormY) * kk;
        stormS += (ts - stormS) * kk;
        storm = new float[] {stormX, stormY, stormS};
    }

    /** {screen x, screen y (down), strength} of the nearest storm, for the screen pass. */
    public static float[] storm() {
        return storm;
    }

    static String encode() {
        return "rsx=" + f(seedX) + ";rsy=" + f(seedY) + ";rdx=" + f(driftX) + ";rdy=" + f(driftY) + ";ra=" + Math.round(angleDeg);
    }

    static String saveText() {
        return "rdx=" + f(driftX) + ";rdy=" + f(driftY);
    }

    static boolean apply(String k, String v) {
        double d;
        try {
            d = Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        switch (k) {
            case "rsx" -> seedX = d;
            case "rsy" -> seedY = d;
            case "rdx" -> driftX = d;
            case "rdy" -> driftY = d;
            case "ra" -> angleDeg = (float) d;
            default -> {
                return false;
            }
        }
        return true;
    }

    static String stateText() {
        return "weatherLocal=" + Math.round(local * 100) + ";weatherStorm=" + Math.round(stormS * 100);
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
