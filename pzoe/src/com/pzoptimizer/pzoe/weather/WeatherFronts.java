package com.pzoptimizer.pzoe.weather;

import com.pzoptimizer.PZOLogger;

/**
 * PZOE weather phase 1: fronts that roll in and weather that changes gradually.
 *
 * I never touch vanilla's weather period (its stages, timing and save stay vanilla). Instead, every frame right after
 * ClimateManager has calculated its final values, post() reshapes them:
 * - Build-up: vanilla starts a front when its air mass noise changes sign on the hour. That noise is a plain function
 *   of world age, so I can see a front coming hours ahead: clouds thicken, the wind picks up and the day darkens
 *   before the rain, instead of rain out of a clear sky. Inside a running period, a heavier stage coming within the
 *   hour builds up the same way.
 * - Rain starts as spits and grows over half an hour, comes in heavier and lighter bursts (same average), and with Sim
 *   on dies away as a short drizzle instead of stopping dead.
 * - Gusts on top of vanilla's wind, stronger ahead of a front and in strong wind; the wind direction wobbles with them.
 * - After rain, still dawns bring a mist that the sun burns off.
 * - Behind a cold front that brought heavy rain, it gets up to 3 C colder for a few hours (Sim on only).
 *
 * With the PZOEWeather.Sim sandbox option on, the reshaped values become the real climate values (gameplay reads
 * them). With it off, gameplay stays vanilla and only WeatherLook gets them, minus what would clash with gameplay (the
 * drizzle tail, the cold).
 *
 * The slow parts (build-up, mist, cold) are worked out on climate ticks where the sim runs and sent to MP clients;
 * the fast parts (gusts, bursts) are noise of world age, so server and clients get the same without sending anything.
 */
public final class WeatherFronts {
    // Slow targets: SP/server set them on climate ticks, an MP client gets them from the server
    static volatile float preTarget, mistTarget, coldTarget;
    // Eased versions used every frame
    private static float pre, mist, cold;
    // Server/SP bookkeeping
    private static float wetness;
    private static double lastTickHours = -1;
    // Per frame
    private static double lastFrameHours = -1;
    private static boolean wasRaining;
    private static double rainStart = -1, rainEnd = -1;
    private static float lastRain, rainAtEnd;
    // Dev menu: build-up forced on until this world hour
    static volatile double forcedPreUntil = -1;
    // Hours to the next front, -1 none seen (for the log)
    static float hoursToFront = -1;

    // Card: look-only extras (when Sim is off) on/off and gust strength
    static volatile boolean lookExtras = true;
    static volatile float gustLook = 1f;

    private static final int PRECIP = 3, WIND = 6, WIND_ANGLE = 7, CLOUD = 8, FOG = 5, TEMP = 4, DAYLIGHT = 11;

    private WeatherFronts() {}

    public static void configure(boolean on, double gustPct) {
        lookExtras = on;
        gustLook = (float) Math.max(0.0, Math.min(2.0, gustPct / 100.0));
    }

    // Slow state, climate ticks (SP/server)

    static void climateTick(zombie.iso.weather.ClimateManager cm, double hours) {
        double dt = lastTickHours < 0 ? 0 : hours - lastTickHours;
        lastTickHours = hours;
        if (dt < 0 || dt > 2) dt = 0;
        float h = (float) dt;

        float p = cm.getPrecipitationIntensity();
        float wind = cm.getWindIntensity();
        boolean snow = cm.getPrecipitationIsSnow();

        // Build-up ahead of the next front
        float preT = 0f;
        hoursToFront = -1;
        float toFront = (float) hoursToNextFront(cm, hours);
        if (toFront >= 0) {
            hoursToFront = toFront;
            preT = 1f - smooth(0.5f, 4f, toFront);
        }
        // And ahead of a heavier stage inside a running period
        zombie.iso.weather.WeatherPeriod wp = cm.getWeatherPeriod();
        if (wp != null && wp.isRunning()) {
            zombie.iso.weather.WeatherPeriod.WeatherStage now = wp.getCurrentStage();
            int nowId = now == null ? -1 : now.getStageID();
            for (double ahead = 0.25; ahead <= 1.0; ahead += 0.25) {
                zombie.iso.weather.WeatherPeriod.WeatherStage s = wp.getStageForWorldAge(hours + ahead);
                if (s == null || s == now) continue;
                if (heavy(s.getStageID()) && !heavy(nowId)) {
                    preT = Math.max(preT, 0.7f * (1f - (float) ((s.getStageStart() - hours) / 1.0)));
                    break;
                }
            }
        }
        // Once it's raining properly the period's own clouds and wind take over
        preT *= 1f - smooth(0.25f, 0.6f, p);
        // Dev menu: a front building up now
        if (hours < forcedPreUntil) preT = 1f;
        preTarget = clamp(preT, 0f, 1f);

        // Ground wetness for the dawn mist: rain wets, it dries over about half a day
        if (p > 0.01f && !snow) wetness = Math.min(1.5f, wetness + h * p);
        wetness *= (float) Math.exp(-h / 10.0);
        float hod = zombie.GameTime.getInstance().getTimeOfDay();
        float dawn = smooth(3.5f, 5.5f, hod) * (1f - smooth(7.5f, 9.5f, hod));
        float still = 1f - smooth(0.15f, 0.35f, wind);
        float m = p > 0.01f ? 0f : Math.min(0.55f, wetness * 0.6f) * dawn * still;
        mistTarget = clamp(m, 0f, 0.55f);

        // Cold behind a cold front that brought heavy rain or snow
        boolean coldFront = wp != null && wp.isRunning() && wp.getFrontType() == -1;
        float c = coldTarget;
        if (coldFront && p > 0.4f) c = Math.min(1f, c + h * 0.5f);
        else c = Math.max(0f, c - h / 8f);
        coldTarget = c;

        WeatherEvents.climateTick(cm, hours, h);
        WeatherRegional.climateTick(cm, h);
    }

    private static boolean heavy(int stage) {
        return stage == 2 || stage == 3 || stage == 7 || stage == 8 || stage == 11;
    }

    /**
     * Vanilla sets the front type on each hour change from the sign of its air mass noise, and starts a weather period
     * when it flips. Same noise here, read ahead hour by hour.
     */
    private static double hoursToNextFront(zombie.iso.weather.ClimateManager cm, double hours) {
        if (cm.getEnabledWeatherGeneration()) return -1; // vanilla's getter returns the disable flag
        double off = cm.getSimplexOffsetA();
        double freq = airMassFrequency(zombie.SandboxOptions.instance.getRainModifier());
        double hourNow = Math.floor(hours);
        int typeNow = zombie.iso.weather.SimplexNoise.noise(off, hourNow / freq) < 0 ? -1 : 1;
        for (int k = 1; k <= 5; k++) {
            double hh = hourNow + k;
            int t = zombie.iso.weather.SimplexNoise.noise(off, hh / freq) < 0 ? -1 : 1;
            if (t != typeNow) return hh - hours;
        }
        return -1;
    }

    // ClimateManager.getAirMassNoiseFrequencyMod (protected)
    private static double airMassFrequency(int sandboxRain) {
        return switch (sandboxRain) {
            case 1 -> 300.0;
            case 2 -> 240.0;
            case 4 -> 145.0;
            case 5 -> 120.0;
            default -> 166.0;
        };
    }

    // Per frame, every role

    /** Injected before updateWindTick in ClimateManager.update, after the climate values are calculated. */
    public static void post(zombie.iso.weather.ClimateManager cm) {
        try {
            frame(cm);
        } catch (Throwable t) {
            WeatherLook.lookValid = false;
            if (!failed) {
                failed = true;
                PZOLogger.warn("[PZOE weather] frame failed, weather left vanilla: " + t);
            }
        }
    }

    private static boolean failed;

    private static void frame(zombie.iso.weather.ClimateManager cm) {
        zombie.GameTime gt = zombie.GameTime.getInstance();
        if (gt == null) return;
        double hours = gt.getWorldAgeHours();
        double dt = lastFrameHours < 0 ? 0 : hours - lastFrameHours;
        lastFrameHours = hours;
        if (dt < 0 || dt > 2) {
            // Another save or a big jump: start from the targets
            dt = 0;
            pre = preTarget;
            mist = mistTarget;
            cold = coldTarget;
            rainStart = rainEnd = -1;
            wasRaining = false;
        }
        float k = (float) (1.0 - Math.exp(-dt / 0.25));
        pre += (preTarget - pre) * k;
        mist += (mistTarget - mist) * k;
        cold += (coldTarget - cold) * k;

        zombie.iso.weather.ClimateManager.ClimateFloat fp = cm.getClimateFloat(PRECIP), fw = cm.getClimateFloat(WIND),
            fa = cm.getClimateFloat(WIND_ANGLE), fc = cm.getClimateFloat(CLOUD), ff = cm.getClimateFloat(FOG),
            ft = cm.getClimateFloat(TEMP), fd = cm.getClimateFloat(DAYLIGHT);
        float p = fp.getFinalValue(), w = fw.getFinalValue(), a = fa.getFinalValue(), c = fc.getFinalValue(),
            f = ff.getFinalValue(), t = ft.getFinalValue(), d = fd.getFinalValue();

        boolean raining = p > 0.01f;
        if (raining && !wasRaining) rainStart = hours;
        if (!raining && wasRaining) {
            rainEnd = hours;
            rainAtEnd = lastRain;
        }
        wasRaining = raining;
        if (raining) lastRain = p;

        // Where no sim tick runs (MP client), the 30 s log line comes from here
        if (zombie.network.GameClient.client) WeatherCore.logMaybe(cm);
        WeatherEffects.localFrame();
        WeatherSnow.frame();
        com.pzoptimizer.pzoe.GroundSnow.weatherFrame();
        boolean sim = WeatherCore.behaviourOn();
        float gustAmt = sim ? 1f : gustLook;
        double minutes = hours * 60.0;
        float gust = (float) (0.65 * zombie.iso.weather.SimplexNoise.noise(minutes / 1.7, 11.3)
            + 0.35 * zombie.iso.weather.SimplexNoise.noise(minutes / 0.6, 37.9));
        float burst = (float) zombie.iso.weather.SimplexNoise.noise(minutes / 22.0, 71.1);

        float c2 = Math.max(c, pre * 0.8f);
        float w2 = clamp(w + 0.2f * pre + gust * gustAmt * (0.05f + 0.2f * w + 0.15f * pre), 0f, 1f);
        // The wind veers up to 30 degrees as a front comes through, and swings a few degrees with the gusts.
        // Done in degrees: vanilla's angle value maps to degrees piecewise (see WeatherLook).
        float a2 = WeatherLook.angleValue(WeatherLook.angleDegrees(a) + 30f * pre + 6f * gust * gustAmt);
        float pLook = p, pSim = p;
        if (raining) {
            float onset = 0.25f + 0.75f * smooth(0f, 0.6f, (float) (hours - rainStart));
            pLook = pSim = clamp(p * onset * (1f + 0.35f * burst), 0f, 1f);
        } else if (rainEnd >= 0 && hours - rainEnd < 0.35) {
            pSim = rainAtEnd * 0.25f * (1f - (float) ((hours - rainEnd) / 0.35));
        }
        float f2 = Math.max(f, mist);
        float d2 = d * (1f - 0.15f * pre);

        boolean vSnow = cm.getPrecipitationIsSnow();
        WeatherEvents.ease((float) dt);
        Mix ms = SIM.set(pSim, w2, a2, f2, c2, d2, t - 3f * cold, vSnow, gust);
        WeatherEvents.apply(ms, hours, true);
        WeatherRegional.apply(ms, sim, p, pre, (float) dt);
        Mix ml = lookExtras ? LOOK.set(pLook, w2, a2, f2, c2, d2, t, vSnow, gust) : LOOK.set(p, w, a, f, c, d, t, vSnow, 0f);
        if (lookExtras) WeatherEvents.apply(ml, hours, false);

        WeatherEvents.freezePuddles(sim ? ms.temp : t, d * (1f - c), (float) dt);

        if (sim) {
            if (!fc.isEnableAdmin()) fc.setFinalValue(ms.cloud);
            if (!fw.isEnableAdmin()) fw.setFinalValue(ms.wind);
            if (!fa.isEnableAdmin()) fa.setFinalValue(ms.angle);
            if (!fp.isEnableAdmin()) fp.setFinalValue(ms.precip);
            if (!ff.isEnableAdmin()) ff.setFinalValue(ms.fog);
            if (!fd.isEnableAdmin()) fd.setFinalValue(ms.daylight);
            if (!ft.isEnableAdmin()) ft.setFinalValue(ms.temp);
            if (ms.snow != vSnow) cm.setPrecipitationIsSnow(ms.snow);
            WeatherLook.snowOverride = -1;
            WeatherLook.setLook(fp.getFinalValue(), fw.getFinalValue(), fa.getFinalValue(), ff.getFinalValue(), fc.getFinalValue(), fd.getFinalValue());
            WeatherEvents.iceShown = ms.ice;
            WeatherEvents.hailShown = ms.hail;
            WeatherEvents.heatShown = ms.heat;
        } else {
            WeatherLook.snowOverride = ml.snow == vSnow ? -1 : ml.snow ? 1 : 0;
            WeatherLook.setLook(ml.precip, ml.wind, ml.angle, ml.fog, ml.cloud, ml.daylight);
            WeatherEvents.iceShown = 0f;
            WeatherEvents.hailShown = ml.hail;
            WeatherEvents.heatShown = ml.heat;
        }
    }

    /** The weather values one frame works on (one for gameplay, one for looks). */
    static final class Mix {
        float precip, wind, angle, fog, cloud, daylight, temp, gust, heat, ice, hail;
        boolean snow;

        Mix set(float p, float w, float a, float f, float c, float d, float t, boolean sn, float g) {
            precip = p;
            wind = w;
            angle = a;
            fog = f;
            cloud = c;
            daylight = d;
            temp = t;
            snow = sn;
            gust = g;
            heat = ice = hail = 0f;
            return this;
        }
    }

    private static final Mix SIM = new Mix(), LOOK = new Mix();

    /**
     * End of IsoPuddles.update (SP/server, Sim on): ground dries by sun and wind too, not only warmth. Vanilla dries
     * by temperature alone, and below 0 C its drying runs backwards and wets the ground.
     */
    public static void puddles(zombie.iso.IsoPuddles ip, zombie.iso.weather.ClimateManager cm) {
        try {
            if (zombie.network.GameClient.client || !WeatherCore.behaviourOn() || ip == null || cm == null) return;
            zombie.iso.IsoPuddles.PuddlesFloat wet = ip.getPuddlesFloat(1);
            if (wet == null || wet.isEnableAdmin()) return;
            float tm = zombie.GameTime.getInstance().getThirtyFPSMultiplier();
            if (cm.getRainIntensity() > 0f) {
                // Freezing rain turns to ice where it lands: take back what vanilla's update just added to the
                // wet ground and puddles (its own sums)
                if (WeatherEvents.freezing > 0.5f || WeatherEvents.forcedFreeze) {
                    float r = cm.getRainIntensity();
                    float add = r * r * 0.05f * tm;
                    wet.addFinalValue(-add);
                    zombie.iso.IsoPuddles.PuddlesFloat muddy = ip.getPuddlesFloat(2), size = ip.getPuddlesFloat(3);
                    if (muddy != null) muddy.addFinalValue(-add * 2f);
                    if (size != null) size.addFinalValue(-add * 0.01f);
                }
                return;
            }
            float t = cm.getTemperature();
            // Undo vanilla's backwards drying below 0
            if (t < 0f) wet.addFinalValue(2.0E-5f * tm * t);
            float sun = cm.getDayLightStrength() * (1f - cm.getCloudIntensity());
            float warm = clamp((t + 5f) / 25f, 0f, 1.5f);
            float dry = 2.0E-5f * tm * (6f * sun + 4f * cm.getWindIntensity()) * warm;
            if (dry > 0f) wet.addFinalValue(-dry);
        } catch (Throwable ignored) {
        }
    }

    // Save and sync

    static String encode() {
        return "pre=" + f3(preTarget) + ";mist=" + f3(mistTarget) + ";cold=" + f3(coldTarget);
    }

    static String saveText() {
        return encode() + ";wet=" + f3(wetness) + ";" + WeatherEvents.saveText() + ";" + WeatherRegional.saveText();
    }

    static void apply(String k, String v) {
        if (WeatherEvents.apply(k, v) || WeatherRegional.apply(k, v)) return;
        float x = parse(v);
        if (Float.isNaN(x)) return;
        switch (k) {
            case "pre" -> preTarget = clamp(x, 0f, 1f);
            case "mist" -> mistTarget = clamp(x, 0f, 0.55f);
            case "cold" -> coldTarget = clamp(x, 0f, 1f);
            case "wet" -> wetness = clamp(x, 0f, 1.5f);
            case "gsd" -> {
                if (zombie.network.GameClient.client) com.pzoptimizer.pzoe.GroundSnow.serverDepth(x);
            }
            default -> WeatherEvents.apply(k, v);
        }
    }

    static void clearRemote() {
        preTarget = mistTarget = coldTarget = 0f;
        WeatherEvents.clearRemote();
    }

    static String stateText() {
        return "weatherPre=" + Math.round(pre * 100) + ";weatherMist=" + Math.round(mist * 100) + ";weatherCold=" + Math.round(cold * 100)
            + ";weatherFront=" + (hoursToFront < 0 ? -1 : Math.round(hoursToFront * 10) / 10f) + ";" + WeatherEvents.stateText() + ";" + WeatherRegional.stateText()
            + ";" + WeatherSnow.stateText();
    }

    private static float parse(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static String f3(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    static float smooth(float a, float b, float x) {
        float t = clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
