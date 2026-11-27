package com.pzoptimizer.pzoe.water;

import java.util.LinkedHashMap;
import zombie.GameTime;
import zombie.WorldSoundManager;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.network.GameClient;

public final class WaterIce {
    private static final String OPTION = "PZOEWater.Ice";
    private static final String SAVE = "PZOEWaterIce";
    private static final int MAX_HOLES = 96;
    private static final IsoDirections[] BANKS = {IsoDirections.N, IsoDirections.S, IsoDirections.W, IsoDirections.E};
    private static final LinkedHashMap<Long, Double> holes = new LinkedHashMap<>();
    private static volatile float amount;
    private static volatile boolean remoteSnapshot;
    private static volatile boolean enabledHere;
    private static volatile float[] visibleHoles = new float[0];
    private static double lastHour = Double.NaN;
    private static final long WARNING_NS = 850_000_000L;
    private static final LinkedHashMap<Long, Long> stressed = new LinkedHashMap<>();
    private static final java.util.concurrent.atomic.AtomicReferenceArray<Warning> warnings =
        new java.util.concurrent.atomic.AtomicReferenceArray<>(4);
    private static boolean holesChanged;
    private static long nextObserverSample;
    private static volatile float[] observerLoads = new float[4];

    private record Warning(int x, int y, long until, float risk) {}

    private WaterIce() {}

    static boolean active() {
        return WaterGameplay.enabled(OPTION) && (!GameClient.client || remoteSnapshot);
    }

    public static void load(GameTime time) {
        if (GameClient.client) return;
        lastHour = Double.NaN;
        stressed.clear();
        clearWarnings();
        Object saved = time.getModData().rawget(SAVE);
        apply(saved instanceof String text ? text : "");
    }

    public static void save(GameTime time) {
        if (!GameClient.client && WaterGameplay.enabled(OPTION)) time.getModData().rawset(SAVE, encodeState());
    }

    public static void advance(float temperature, double hours) {
        if (GameClient.client) return;
        enabledHere = WaterGameplay.enabled(OPTION);
        if (!enabledHere) {
            lastHour = Double.NaN;
            stressed.clear();
            clearWarnings();
            return;
        }
        double elapsed = Double.isNaN(lastHour) ? 0 : Math.max(0, Math.min(1, hours - lastHour));
        lastHour = hours;
        if (temperature < -2f) amount = Math.min(1f, amount + (float) elapsed * Math.min(0.06f, (-2f - temperature) * 0.004f));
        if (temperature > 1f) amount = Math.max(0f, amount - (float) elapsed * Math.min(0.09f, (temperature - 1f) * 0.008f));
        if (!holes.isEmpty() && holes.values().removeIf(expiry -> expiry <= hours)) {
            publishHoles();
            holesChanged = true;
        }
    }

    public static float coverage(float localAmount) {
        return (GameClient.client ? remoteSnapshot : enabledHere) ? amount : localAmount;
    }

    static float[] visibleHoles() {
        return (GameClient.client ? remoteSnapshot : enabledHere) ? visibleHoles : null;
    }

    private static long key(IsoGridSquare square) {
        return ((long) square.x << 32) | (square.y & 0xffffffffL);
    }

    static float thickness(IsoGridSquare square) {
        if (amount < 0.2f || !WaterSwimming.openWater(square) || holes.containsKey(key(square))) return 0f;
        boolean shore = false;
        for (IsoDirections direction : BANKS) {
            IsoGridSquare neighbor = square.getAdjacentSquare(direction);
            if (neighbor != null && !WaterSwimming.openWater(neighbor)) {
                shore = true;
                break;
            }
        }
        return Math.max(0f, (amount - (shore ? 0.14f : 0.57f)) / (shore ? 0.86f : 0.43f));
    }

    static boolean supports(IsoGridSquare square) {
        return active() && thickness(square) >= 0.16f;
    }

    private static float load(IsoPlayer player) {
        float carry = Math.min(2f, player.getInventoryWeight() / Math.max(1, player.getMaxWeight()));
        return 0.26f + 0.12f * carry + (player.isSprinting() ? 0.18f : player.isRunning() ? 0.1f : 0f);
    }

    static void step(IsoPlayer player, IsoGridSquare square) {
        if (!active()) return;
        float ice = thickness(square);
        float load = load(player);
        if (ice < 0.16f || ice >= load) return;
        long now = System.nanoTime();
        if (player.isLocalPlayer() && player.isPlayerMoving()) {
            int index = player.getIndex();
            if (index >= 0 && index < warnings.length()) {
                var prior = warnings.get(index);
                if (prior == null || prior.x != square.x || prior.y != square.y || prior.until - now < 450_000_000L) {
                    warnings.set(index, new Warning(square.x, square.y, now + 1_100_000_000L,
                        Math.min(1f, 0.45f + (load - ice) * 1.25f)));
                }
            }
        }
        if (GameClient.client) return;
        long key = key(square);
        Long since = stressed.get(key);
        if (since == null || now - since > 3_000_000_000L) {
            if (!player.isPlayerMoving()) return;
            if (stressed.size() >= MAX_HOLES) stressed.remove(stressed.keySet().iterator().next());
            stressed.put(key, now);
            return;
        }
        if (now - since < WARNING_NS) return;
        stressed.remove(key);
        if (holes.size() >= MAX_HOLES) holes.remove(holes.keySet().iterator().next());
        holes.put(key, GameTime.getInstance().getWorldAgeHours() + 8.0);
        publishHoles();
        holesChanged = true;
        if (WorldSoundManager.instance != null) WorldSoundManager.instance.addSound(player, square.x, square.y, 0, 8, 8);
    }

    static void sampleObservers(long now) {
        if (now < nextObserverSample) return;
        nextObserverSample = now + 150_000_000L;
        float[] loads = new float[4];
        if (active()) {
            for (int i = 0; i < loads.length; i++) {
                IsoPlayer player = IsoPlayer.players[i];
                if (player != null) loads[i] = load(player);
            }
        }
        observerLoads = loads;
    }

    static void bindCondition(int location, int observer) {
        if (location < 0) return;
        float[] loads = observerLoads;
        float limit = observer >= 0 && observer < loads.length ? loads[observer] : 0f;
        var warning = observer >= 0 && observer < warnings.length() ? warnings.get(observer) : null;
        float risk = warning != null && limit > 0f
            ? warning.risk * Math.max(0f, Math.min(1f, (warning.until - System.nanoTime()) / 400_000_000f)) : 0f;
        org.lwjgl.opengl.GL20.glUniform4f(location, warning == null ? 0f : warning.x + 0.5f,
            warning == null ? 0f : warning.y + 0.5f, risk, limit);
    }

    private static void clearWarnings() {
        for (int i = 0; i < warnings.length(); i++) warnings.set(i, null);
        observerLoads = new float[4];
    }

    public static String changedHoles() {
        if (!holesChanged || !WaterGameplay.enabled(OPTION)) return "";
        holesChanged = false;
        return encodeState();
    }

    public static String encodeState() {
        StringBuilder text = new StringBuilder().append(Math.round(amount * 100));
        holes.forEach((key, expiry) -> text.append('/').append(key).append(',').append(Math.round(expiry * 60)));
        return text.toString();
    }

    public static String remoteState() {
        return WaterGameplay.enabled(OPTION) ? encodeState() : "";
    }

    public static void apply(String text) {
        holes.clear();
        publishHoles();
        amount = 0f;
        remoteSnapshot = false;
        if (text == null || text.isEmpty()) {
            clearWarnings();
            return;
        }
        try {
            String[] parts = text.split("/", MAX_HOLES + 2);
            if (parts.length > MAX_HOLES + 1) return;
            amount = Math.max(0f, Math.min(1f, Integer.parseInt(parts[0]) / 100f));
            for (int i = 1; i < parts.length; i++) {
                int sep = parts[i].indexOf(',');
                if (sep <= 0) continue;
                holes.put(Long.parseLong(parts[i].substring(0, sep)), Long.parseLong(parts[i].substring(sep + 1)) / 60.0);
            }
            publishHoles();
            remoteSnapshot = true;
        } catch (NumberFormatException ignored) {
            holes.clear();
            publishHoles();
            amount = 0f;
        }
    }

    private static void publishHoles() {
        float[] points = new float[holes.size() * 2];
        int i = 0;
        for (long key : holes.keySet()) {
            points[i++] = (int) (key >> 32) + 0.5f;
            points[i++] = (int) key + 0.5f;
        }
        visibleHoles = points;
    }
}
