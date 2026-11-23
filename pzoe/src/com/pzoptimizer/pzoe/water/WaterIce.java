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

    private WaterIce() {}

    static boolean active() {
        return WaterGameplay.enabled(OPTION) && (!GameClient.client || remoteSnapshot);
    }

    public static void load(GameTime time) {
        if (GameClient.client) return;
        lastHour = Double.NaN;
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
            return;
        }
        double elapsed = Double.isNaN(lastHour) ? 0 : Math.max(0, Math.min(1, hours - lastHour));
        lastHour = hours;
        if (temperature < -2f) amount = Math.min(1f, amount + (float) elapsed * Math.min(0.06f, (-2f - temperature) * 0.004f));
        if (temperature > 1f) amount = Math.max(0f, amount - (float) elapsed * Math.min(0.09f, (temperature - 1f) * 0.008f));
        if (!holes.isEmpty() && holes.values().removeIf(expiry -> expiry <= hours)) publishHoles();
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

    static void step(IsoPlayer player, IsoGridSquare square) {
        if (GameClient.client || !active() || !player.isPlayerMoving()) return;
        float ice = thickness(square);
        if (ice < 0.16f) return;
        float carry = Math.min(2f, player.getInventoryWeight() / Math.max(1, player.getMaxWeight()));
        float load = 0.26f + 0.12f * carry + (player.isSprinting() ? 0.18f : player.isRunning() ? 0.1f : 0f);
        if (ice >= load) return;

        if (holes.size() >= MAX_HOLES) holes.remove(holes.keySet().iterator().next());
        holes.put(key(square), GameTime.getInstance().getWorldAgeHours() + 8.0);
        publishHoles();
        if (WorldSoundManager.instance != null) WorldSoundManager.instance.addSound(player, square.x, square.y, 0, 8, 8);
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
        if (text == null || text.isEmpty()) return;
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
