package com.pzoptimizer.pzoe;

import java.util.Arrays;
import org.joml.Vector3f;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.weather.ClimateManager;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleLight;
import zombie.vehicles.VehiclePart;

final class VehicleLightTrails {
    static final int MAX = 12;
    static volatile long askedNanos;

    private static final float[] nearest = new float[MAX];
    private static final float[] segments = new float[MAX * 8];
    private static final Vector3f origin = new Vector3f(), forward = new Vector3f();

    private VehicleLightTrails() {}

    static float[] project(int player, float zoom, int width, int height) {
        IsoPlayer viewer = IsoPlayer.players[player];
        PlayerCamera camera = IsoCamera.cameras[player];
        var cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        float darkness = 1f - ClimateManager.getInstance().getDayLightStrength();
        if (viewer == null || camera == null || cell == null || darkness < 0.15f || width <= 0 || height <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(player), bottom = IsoCamera.getScreenTop(player) + IsoCamera.getScreenHeight(player);
        int count = 0;
        for (BaseVehicle vehicle : cell.getVehicles()) {
            float speed = vehicle.getCurrentSpeedKmHour();
            if (Math.abs(speed) < 8f || vehicle.getZ() != viewer.getZ()) continue;
            float dx = vehicle.getX() - viewer.getX(), dy = vehicle.getY() - viewer.getY();
            float dist = dx * dx + dy * dy;
            if (dist > 24f * 24f) continue;
            var square = vehicle.getCurrentSquare();
            if (square == null || !square.isCouldSee(player)) continue;
            var script = vehicle.getScript();
            if (script == null) continue;
            vehicle.getForwardVector(forward);
            float span = Math.copySign(Math.min(2.4f, (Math.abs(speed) - 8f) * 0.034f), speed);
            int lights = vehicle.getLightCount();
            for (int i = 0; i < lights; i++) {
                VehiclePart part = vehicle.getLightByIndex(i);
                VehicleLight light = part == null ? null : part.getLight();
                if (light == null || !light.getActive()) continue;
                boolean tailLight = light.r > light.g * 1.5f || part.getId().contains("Rear");
                vehicle.getWorldPos(light.offset.x * script.getExtents().x * 0.5f, 0f,
                    light.offset.y * script.getExtents().z * 0.5f, origin);
                float x = origin.x, y = origin.y, z = vehicle.getZ() + 0.28f;
                float fromX = left + camera.XToScreenExact(x, y, z, 0) / zoom;
                float fromY = bottom - camera.YToScreenExact(x, y, z, 0) / zoom;
                float toX = left + camera.XToScreenExact(x - span * forward.x, y - span * forward.z, z, 0) / zoom;
                float toY = bottom - camera.YToScreenExact(x - span * forward.x, y - span * forward.z, z, 0) / zoom;
                if (Math.max(fromX, toX) < 0f || Math.min(fromX, toX) > width
                    || Math.max(fromY, toY) < 0f || Math.min(fromY, toY) > height) continue;
                int slot = count;
                if (slot == MAX) {
                    float far = dist;
                    for (int j = 0; j < count; j++) {
                        if (nearest[j] <= far) continue;
                        far = nearest[j];
                        slot = j;
                    }
                    if (slot == MAX) continue;
                } else {
                    count++;
                }
                nearest[slot] = dist;
                int at = slot * 8;
                segments[at] = fromX / width;
                segments[at + 1] = fromY / height;
                segments[at + 2] = toX / width;
                segments[at + 3] = toY / height;
                segments[at + 4] = light.r;
                segments[at + 5] = tailLight ? 0.14f : light.g;
                segments[at + 6] = tailLight ? 0.045f : light.b;
                segments[at + 7] = darkness * Math.min(1f, Math.abs(span)) * (tailLight ? 0.35f : 0.24f);
            }
        }
        return count == 0 ? null : Arrays.copyOf(segments, count * 8);
    }
}
