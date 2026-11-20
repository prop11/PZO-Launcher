package com.pzoptimizer.pzoe.weather;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * PZOE weather phase 2: kinds of weather vanilla doesn't have.
 *
 * Scheduled per day (deterministic from the world's climate seed and the day, so a reload gives the same days):
 * - Fog bank: a thick fog at dawn on still, dry mornings, more in spring and autumn, burnt off by mid morning.
 * - Overcast spell: a day or two of grey sky without rain.
 * - Flurries: a few hours of light snow on cold winter days that vanilla leaves dry.
 * - Gale: half a day of strong gusty wind without rain, more in March, April and November.
 * - Heat wave: two to four summer days of extra heat (Sim on) and a hazy, bleached look.
 * - Afternoon thunderstorm: on a hot summer afternoon with no other rain, a short heavy storm builds, with thunder
 *   and lightning (vanilla's own thunder cloud) and sometimes hail at its start (Sim on: it's real rain).
 * Decided as it happens (seeded by the vanilla period or stage, so a reload decides the same):
 * - Freezing rain: snow at -3..0.5 C on a warm front falls as rain instead and glazes the ground with ice (Sim on).
 * - Hail: the first minutes of a warm-season storm come down as hail.
 *
 * Each event only shows if the weather allows it at the time (a fog bank needs still dry air, flurries need cold and
 * no other precipitation). Nothing goes into vanilla's weather period, so saves stay vanilla.
 */
public final class WeatherEvents {
    static final int FOGBANK = 0, OVERCAST = 1, FLURRIES = 2, GALE = 3, HEATWAVE = 4, STORM = 5, COUNT = 6;
    static final String[] NAMES = {"FogBank", "Overcast", "Flurries", "Gale", "HeatWave", "Storm"};

    static final class Event {
        final int type;
        final double start, dur;
        final float strength;
        // Forced from the dev menu: shows whatever the weather is doing
        final boolean forced;

        Event(int type, double start, double dur, float strength) {
            this(type, start, dur, strength, false);
        }

        Event(int type, double start, double dur, float strength, boolean forced) {
            this.type = type;
            this.start = start;
            this.dur = dur;
            this.strength = strength;
            this.forced = forced;
        }
    }

    // Dev menu: forced events and forced runtime weather (world hours they run until). Never saved.
    static volatile List<Event> forcedEvents = List.of();
    static volatile double forcedHailUntil = -1, forcedFreezeUntil = -1;

    private static Iterable<Event> all() {
        List<Event> f = forcedEvents;
        if (f.isEmpty()) return events;
        List<Event> l = new ArrayList<>(events);
        l.addAll(f);
        return l;
    }

    static boolean forcedOn(int type, double hours) {
        for (Event e : forcedEvents) if (e.type == type && hours >= e.start && hours <= e.start + e.dur) return true;
        return false;
    }

    /**
     * Dev menu (SP/server): force "FogBank", "Overcast", "Flurries", "Gale", "HeatWave", "Storm", "Hail", "FreezingRain",
     * "Front", "Lightning" for some game hours, or "Clear". Returns what happened, for the menu to show.
     */
    public static String force(String name, double hoursFor) {
        if (zombie.network.GameClient.client) return "Only the server can force weather";
        zombie.GameTime gt = zombie.GameTime.getInstance();
        if (gt == null || zombie.iso.weather.ClimateManager.getInstance() == null) return "No world loaded";
        double now = gt.getWorldAgeHours();
        double dur = Math.max(0.1, Math.min(96.0, hoursFor));
        switch (name) {
            case "Clear" -> {
                forcedEvents = List.of();
                forcedHailUntil = forcedFreezeUntil = -1;
                WeatherFronts.forcedPreUntil = -1;
                return "Forced weather cleared";
            }
            case "Hail" -> {
                forcedHailUntil = now + dur;
                return "Hail for " + fmtH(dur);
            }
            case "FreezingRain" -> {
                forcedFreezeUntil = now + dur;
                return "Freezing rain for " + fmtH(dur);
            }
            case "Front" -> {
                WeatherFronts.forcedPreUntil = now + dur;
                return "A front building up for " + fmtH(dur);
            }
            case "Lightning" -> {
                return WeatherStorm.strikeNearPlayer();
            }
            case "DeepSnow" -> {
                com.pzoptimizer.pzoe.GroundSnow.forceDepth(3f);
                return "Deep snow lying (it melts as usual when it's warm)";
            }
            case "FastSnow" -> {
                com.pzoptimizer.pzoe.GroundSnow.devFast(dur);
                // Something falling to go with it
                List<Event> l = new ArrayList<>(forcedEvents);
                l.removeIf(e -> e.type == FLURRIES);
                l.add(new Event(FLURRIES, now, dur, 1f, true));
                forcedEvents = List.copyOf(l);
                return "Snow piling up about 40 times as fast for " + fmtH(dur);
            }
            case "NoSnow" -> {
                com.pzoptimizer.pzoe.GroundSnow.forceDepth(0f);
                return "Ground snow cleared";
            }
            default -> {
                int t = indexOf(name);
                if (t < 0) return "Unknown weather: " + name;
                List<Event> l = new ArrayList<>(forcedEvents);
                l.removeIf(e -> e.type == t);
                l.add(new Event(t, now, dur, 1f, true));
                forcedEvents = List.copyOf(l);
                return name + " for " + fmtH(dur);
            }
        }
    }

    private static int indexOf(String name) {
        for (int t = 0; t < COUNT; t++) if (NAMES[t].equals(name)) return t;
        return -1;
    }

    private static String fmtH(double h) {
        return h < 1.0 ? Math.round(h * 60) + " game minutes" : (Math.round(h * 10) / 10.0) + " game hours";
    }

    // SP/server: worked out; MP client: from the server. Replaced as a whole, never edited.
    static volatile List<Event> events = List.of();
    private static long scheduledDay = Long.MIN_VALUE;
    private static double seedBase;
    // World age counts from the start time, not midnight: world hours at midnight of day 0
    private static double align;

    // Runtime events, SP/server decide, clients get them
    static volatile float freezing, ice, hail;
    // Eased, per frame
    private static float iceNow, hailNow;
    private static double lastHailStage = -1;
    private static boolean hailThisStage;
    private static double lastFreezePeriod = -1;
    private static boolean freezeThisPeriod;

    private WeatherEvents() {}

    // Scheduling (SP/server, climate ticks)

    static void climateTick(zombie.iso.weather.ClimateManager cm, double hours, float h) {
        seedBase = cm.getSimplexOffsetA();
        long day = dayIndex(hours);
        if (day != scheduledDay) {
            scheduledDay = day;
            List<Event> list = new ArrayList<>();
            // Yesterday too: a heat wave or overcast spell that started then can still be running
            for (long d = day - 3; d <= day + 2; d++) roll(d, list);
            events = List.copyOf(list);
        }
        runtime(cm, hours, h);
    }

    private static void roll(long day, List<Event> out) {
        Random r = new Random(Double.doubleToLongBits(seedBase) ^ (day * 0x9E3779B97F4A7C15L) ^ 0x5DEECE66DL);
        int month = monthOf(day);
        boolean springAutumn = month == 2 || month == 3 || month == 4 || month == 8 || month == 9 || month == 10;
        boolean winter = month == 11 || month == 0 || month == 1;
        boolean summer = month == 5 || month == 6 || month == 7;
        double base = day * 24.0 + align;
        if (r.nextFloat() < (springAutumn ? 0.14f : 0.05f)) {
            out.add(new Event(FOGBANK, base + 3.0 + r.nextFloat() * 1.5, 6.0 + r.nextFloat() * 2.0, 0.6f + 0.4f * r.nextFloat()));
        }
        if (r.nextFloat() < 0.06f) {
            out.add(new Event(OVERCAST, base + r.nextFloat() * 24.0, 18.0 + r.nextFloat() * 42.0, 0.6f + 0.4f * r.nextFloat()));
        }
        if (winter && r.nextFloat() < 0.15f) {
            out.add(new Event(FLURRIES, base + r.nextFloat() * 24.0, 1.0 + r.nextFloat() * 2.0, 0.5f + 0.5f * r.nextFloat()));
        }
        boolean windy = month == 2 || month == 3 || month == 10;
        if (r.nextFloat() < (windy ? 0.07f : 0.03f)) {
            out.add(new Event(GALE, base + 6.0 + r.nextFloat() * 12.0, 6.0 + r.nextFloat() * 8.0, 0.6f + 0.4f * r.nextFloat()));
        }
        if (summer && r.nextFloat() < 0.04f) {
            out.add(new Event(HEATWAVE, base + 8.0, 48.0 + r.nextFloat() * 48.0, 0.6f + 0.4f * r.nextFloat()));
        }
        boolean warm = month == 4 || month == 8;
        if (r.nextFloat() < (summer ? 0.18f : warm ? 0.08f : 0f)) {
            out.add(new Event(STORM, base + 13.0 + r.nextFloat() * 4.0, 1.0 + r.nextFloat(), 0.6f + 0.4f * r.nextFloat()));
        }
    }

    /** Calendar day number for a world age (midnight to midnight), and sets align. */
    static long dayIndex(double hours) {
        double midnight = hours - zombie.GameTime.getInstance().getTimeOfDay();
        long day = (long) Math.floor(midnight / 24.0 + 1e-4);
        align = midnight - day * 24.0;
        return day;
    }

    // Month of a world day (0 = January), from today's date and how many days away it is
    private static int monthOf(long day) {
        zombie.GameTime gt = zombie.GameTime.getInstance();
        long today = dayIndex(gt.getWorldAgeHours());
        java.time.LocalDate d = java.time.LocalDate.of(gt.getYear(), gt.getMonth() + 1, Math.max(1, gt.getDayPlusOne()));
        return d.plusDays(day - today).getMonthValue() - 1;
    }

    private static void runtime(zombie.iso.weather.ClimateManager cm, double hours, float h) {
        zombie.iso.weather.WeatherPeriod wp = cm.getWeatherPeriod();
        boolean running = wp != null && wp.isRunning();
        float t = cm.getTemperature();
        float p = cm.getPrecipitationIntensity();

        // Freezing rain: decided once per period
        if (running) {
            double ps = wp.getWeatherStages().isEmpty() ? 0 : wp.getWeatherStages().get(0).getStageStart();
            if (ps != lastFreezePeriod) {
                lastFreezePeriod = ps;
                freezeThisPeriod = wp.getFrontType() == 1 && chance(ps, 11) < 0.45f;
            }
        }
        boolean forcedFrz = hours < forcedFreezeUntil;
        boolean frz = forcedFrz || (running && freezeThisPeriod && p > 0.02f && t > -3f && t < 0.5f);
        freezing = frz ? 1f : 0f;
        float i = ice;
        if (frz) i += h * Math.max(p, forcedFrz ? 0.5f : 0f) * 1.2f;
        // Melts in warmth and sun, slowly sublimates otherwise (not while it's being forced)
        float sun = cm.getDayLightStrength() * (1f - cm.getCloudIntensity());
        if (!forcedFrz) i -= h * (Math.max(0f, t) * 0.12f + sun * 0.08f + 0.01f);
        ice = WeatherFronts.clamp(i, 0f, 1f);

        // Hail: decided once per heavy stage, the first 12 minutes of it
        float hl = 0f;
        if (running) {
            zombie.iso.weather.WeatherPeriod.WeatherStage s = wp.getCurrentStage();
            int id = s == null ? -1 : s.getStageID();
            if (s != null && (id == 3 || id == 8 || id == 11)) {
                if (s.getStageStart() != lastHailStage) {
                    lastHailStage = s.getStageStart();
                    hailThisStage = chance(s.getStageStart(), 23) < 0.35f;
                }
                double into = hours - s.getStageStart();
                if (hailThisStage && t > 12f && !cm.getPrecipitationIsSnow() && into >= 0 && into < 0.2) {
                    hl = (float) Math.min(1.0, Math.min(into / 0.03, (0.2 - into) / 0.05));
                }
            }
        }
        // Afternoon storm: vanilla's thunder cloud once it gets going (Sim on, hot, no other rain)
        stormNow = false;
        for (Event e : all()) {
            if (e.type != STORM || hours < e.start || hours > e.start + e.dur) continue;
            if (!e.forced && (!WeatherCore.behaviourOn() || t < 22f || (running && p > 0.05f && e.start != stormThunderFor))) continue;
            stormNow = true;
            if (e.start != stormThunderFor && hours > e.start + e.dur * 0.15) {
                stormThunderFor = e.start;
                try {
                    float deg = WeatherLook.angleDegrees(cm.getWindAngleIntensity());
                    cm.getThunderStorm().startThunderCloud(e.strength, deg, 6000f * e.strength, e.strength, 0.6f * e.strength,
                        e.dur * 0.7, e.strength > 0.7f, 0f);
                } catch (Throwable ignored) {
                }
            }
            if (e.strength > 0.8f && hours - e.start > e.dur * 0.15 && hours - e.start < e.dur * 0.3) hl = Math.max(hl, 0.7f);
        }
        if (hours < forcedHailUntil) hl = 1f;
        hail = hl;
    }

    private static double stormThunderFor = -1;
    static volatile boolean stormNow;

    private static float chance(double key, int salt) {
        return new Random(Double.doubleToLongBits(seedBase) ^ Double.doubleToLongBits(key) * 31 + salt).nextFloat();
    }

    // Per frame (every role): event overlays on the reshaped values

    /** 0..1: how strongly an event of this type runs now, with soft edges. */
    static float level(int type, double hours) {
        float best = 0f;
        for (Event e : all()) {
            if (e.type != type || hours < e.start || hours > e.start + e.dur) continue;
            double edge = Math.min(Math.min(hours - e.start, e.start + e.dur - hours), Math.max(0.5, e.dur * 0.25));
            float env = (float) Math.min(1.0, edge / Math.max(0.5, Math.min(3.0, e.dur * 0.25)));
            best = Math.max(best, env * e.strength);
        }
        return best;
    }

    /** Mixes the events into m. sim: these become gameplay values; otherwise only what's safe for looks. */
    /** Once a frame: ice and hail ease toward the sim's values (dt in game hours). */
    static void ease(float dt) {
        iceNow += (ice - iceNow) * (float) (1.0 - Math.exp(-dt / 0.1));
        hailNow += (hail - hailNow) * (float) (1.0 - Math.exp(-dt / 0.02));
    }

    // Forced freezing rain, as the client knows it too
    static volatile boolean forcedFreeze;

    static void apply(WeatherFronts.Mix m, double hours, boolean sim) {
        float hod = zombie.GameTime.getInstance().getTimeOfDay();
        if (!zombie.network.GameClient.client) forcedFreeze = hours < forcedFreezeUntil;

        float fogb = level(FOGBANK, hours);
        boolean fogF = forcedOn(FOGBANK, hours);
        if (fogb > 0f && (fogF || (m.precip < 0.02f && m.wind < 0.4f))) {
            // Thickest around sunrise, burnt off by mid morning (forced: any time, any wind)
            float burn = fogF ? 1f : 1f - WeatherFronts.smooth(7.5f, 10.5f, hod);
            float calm = fogF ? 1f : 1f - WeatherFronts.smooth(0.25f, 0.4f, m.wind);
            m.fog = Math.max(m.fog, fogb * 0.85f * burn * calm);
        }
        float over = level(OVERCAST, hours);
        if (over > 0f) {
            m.cloud = Math.max(m.cloud, 0.65f + 0.25f * over);
            m.daylight *= 1f - 0.08f * over;
        }
        float flur = level(FLURRIES, hours);
        if (flur > 0f && (forcedOn(FLURRIES, hours) || (m.precip < 0.02f && m.temp < 1f))) {
            float n = (float) (0.6 + 0.4 * zombie.iso.weather.SimplexNoise.noise(hours * 60.0 / 15.0, 91.7));
            m.precip = Math.max(m.precip, (0.06f + 0.1f * flur) * n);
            m.snow = true;
        }
        float gale = level(GALE, hours);
        if (gale > 0f) m.wind = Math.max(m.wind, 0.62f + 0.3f * gale + 0.08f * m.gust);
        m.heat = level(HEATWAVE, hours);
        if (sim && m.heat > 0f) {
            // Most at mid afternoon, a little at night
            float day = 0.4f + 0.6f * WeatherFronts.smooth(0f, 1f, (float) Math.sin(Math.PI * (hod - 9.0) / 12.0));
            m.temp += m.heat * (4f + 3f * day);
        }
        // Afternoon storm: a bell of heavy rain, gusts and dark cloud (gameplay rain, so Sim only)
        boolean stormF = forcedOn(STORM, hours);
        float storm = sim && (stormNow || stormF) ? level(STORM, hours) : 0f;
        if (storm > 0f && (stormF || m.temp > 22f)) {
            m.precip = Math.max(m.precip, storm * 0.85f);
            m.cloud = Math.max(m.cloud, 0.6f + 0.35f * storm);
            m.wind = Math.max(m.wind, 0.35f + 0.4f * storm + 0.15f * m.gust);
            m.daylight *= 1f - 0.2f * storm;
        }
        if (sim && forcedFreeze) {
            m.precip = Math.max(m.precip, 0.5f);
            m.snow = false;
        }
        // Freezing rain only falls below freezing: forced on a warm day, the air goes cold too
        if (sim && (forcedFreeze || freezing > 0.5f)) m.temp = Math.min(m.temp, -1.5f);
        if (sim && freezing > 0.5f && m.snow) m.snow = false;
        m.ice = sim ? iceNow : 0f;
        m.hail = hailNow;
        if (m.hail > 0.01f) m.precip = Math.max(m.precip, 0.55f + 0.3f * m.hail);
    }

    // Exports for looks and gameplay effects
    static volatile float iceShown, hailShown, heatShown;
    // 0 water .. 1 frozen through
    static volatile float puddleFreeze;

    public static float puddleFreeze() {
        return puddleFreeze;
    }

    /**
     * Once a frame: puddles freeze over below -1 C (an hour or two, faster the colder) and thaw above 1 C (faster in
     * warmth and sun). In between they stay as they are.
     */
    static void freezePuddles(float temp, float sun, float dtHours) {
        float f = puddleFreeze;
        if (temp < -1f) f += dtHours * (0.5f + 0.15f * Math.min(10f, -1f - temp));
        else if (temp > 1f) f -= dtHours * (0.6f + 0.2f * Math.min(10f, temp - 1f) + 0.5f * sun);
        puddleFreeze = WeatherFronts.clamp(f, 0f, 1f);
    }

    /** Ice underfoot for gameplay: freezing rain's glaze, or frozen puddles where the ground is wet. */
    static float groundIce() {
        float wet = zombie.iso.IsoPuddles.getInstance() == null ? 0f : zombie.iso.IsoPuddles.getInstance().getPuddlesSizeFinalValue();
        return Math.max(ice, puddleFreeze * Math.min(1f, wet * 1.5f));
    }

    public static float ice() {
        return iceShown;
    }

    public static float hail() {
        return hailShown;
    }

    public static float heat() {
        return heatShown;
    }

    /** Types running on a world day (0 today, 1 tomorrow), for the radio forecast: "FogBank,Gale". */
    public static String forecast(int dayOffset) {
        zombie.GameTime gt = zombie.GameTime.getInstance();
        if (gt == null) return "";
        double d0 = (dayIndex(gt.getWorldAgeHours()) + dayOffset) * 24.0 + align;
        StringBuilder sb = new StringBuilder();
        boolean[] seen = new boolean[COUNT];
        for (Event e : all()) {
            if (seen[e.type] || e.start > d0 + 24.0 || e.start + e.dur < d0) continue;
            seen[e.type] = true;
            if (sb.length() > 0) sb.append(',');
            sb.append(NAMES[e.type]);
        }
        return sb.toString();
    }

    // Sync: "ev=T:start:dur:str|...;frz=1;ice=0.3;hail=0"

    static String encode() {
        StringBuilder sb = new StringBuilder("ev=");
        boolean first = true;
        for (Event e : all()) {
            if (!first) sb.append('|');
            first = false;
            sb.append(e.type).append(':').append(f2(e.start)).append(':').append(f2(e.dur)).append(':').append(f2(e.strength))
                .append(':').append(e.forced ? 1 : 0);
        }
        sb.append(";ffz=").append(forcedFreeze ? 1 : 0);
        sb.append(";stm=").append(stormNow ? 1 : 0);
        sb.append(";frz=").append(freezing > 0.5f ? 1 : 0).append(";ice=").append(f2(ice)).append(";hail=").append(f2(hail));
        return sb.toString();
    }

    static String saveText() {
        return "ice=" + f2(ice);
    }

    static boolean apply(String k, String v) {
        switch (k) {
            case "ev" -> {
                List<Event> list = new ArrayList<>(), forced = new ArrayList<>();
                for (String part : v.split("\\|")) {
                    String[] f = part.split(":");
                    if (f.length != 4 && f.length != 5) continue;
                    try {
                        int type = Integer.parseInt(f[0]);
                        if (type < 0 || type >= COUNT) continue;
                        boolean fo = f.length == 5 && "1".equals(f[4]);
                        (fo ? forced : list).add(new Event(type, Double.parseDouble(f[1]), Double.parseDouble(f[2]), Float.parseFloat(f[3]), fo));
                    } catch (NumberFormatException ignored) {
                    }
                }
                events = List.copyOf(list);
                forcedEvents = List.copyOf(forced);
            }
            case "frz" -> freezing = "1".equals(v) ? 1f : 0f;
            case "stm" -> stormNow = "1".equals(v);
            case "ffz" -> forcedFreeze = "1".equals(v);
            case "ice" -> ice = parse(v, ice);
            case "hail" -> hail = parse(v, hail);
            default -> {
                return false;
            }
        }
        return true;
    }

    static void clearRemote() {
        events = List.of();
        forcedEvents = List.of();
        forcedFreeze = false;
        freezing = ice = hail = 0f;
    }

    static String stateText() {
        StringBuilder sb = new StringBuilder("weatherEvents=");
        double h = zombie.GameTime.getInstance() == null ? 0 : zombie.GameTime.getInstance().getWorldAgeHours();
        for (int t = 0; t < COUNT; t++) {
            float l = level(t, h);
            if (l > 0f) sb.append(NAMES[t]).append(Math.round(l * 100)).append(' ');
        }
        return sb.toString().trim() + ";weatherIce=" + Math.round(iceShown * 100) + ";weatherHail=" + Math.round(hailShown * 100)
            + ";weatherHeat=" + Math.round(heatShown * 100);
    }

    private static float parse(String s, float dflt) {
        try {
            return Math.max(0f, Math.min(1f, Float.parseFloat(s.trim())));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String f2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
