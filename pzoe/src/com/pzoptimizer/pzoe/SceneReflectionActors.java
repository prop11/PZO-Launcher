package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

final class SceneReflectionActors {
    // 6 texels a square: at 2 a character reflected as a square block that jumped as they walked
    private static final int PER = 6, RADIUS = 16, SIZE = 2 * RADIUS * PER;
    private static final int LEVELS = 4;
    private static final int UNIT = 12;
    private static final byte[] scratch = new byte[SIZE * SIZE * LEVELS];
    private static final Vector3f forward = new Vector3f();
    private record Field(int x, int y, int z, byte[] pixels) {}
    private static volatile Field active;
    private static Field uploaded;
    private static ByteBuffer pixels;
    private static int texture;

    private SceneReflectionActors() {}

    static void frame(boolean wanted) {
        if (!wanted) {
            active = null;
            return;
        }
        var world = IsoWorld.instance;
        var cell = world == null ? null : world.currentCell;
        IsoPlayer viewer = IsoPlayer.players[0];
        if (cell == null || viewer == null) {
            active = null;
            return;
        }
        int x = (int) Math.floor(viewer.getX()) - RADIUS;
        int y = (int) Math.floor(viewer.getY()) - RADIUS;
        int z = Math.max(-32, Math.min(28, Math.min(0, (int) viewer.getZ() - 1)));
        Arrays.fill(scratch, (byte) 0);
        boolean stamped = false;
        for (var object : cell.getObjectList()) {
            if (!(object instanceof IsoGameCharacter character) || character.isDead()) continue;
            float h = character.isOnFloor() ? 0.13f : 0.57f;
            if (Math.abs(character.getX() - viewer.getX()) > RADIUS + 1 || Math.abs(character.getY() - viewer.getY()) > RADIUS + 1) continue;
            stamped |= stamp(x, y, z, character.getX(), character.getY(), character.getZ(), 0f, 0f, 0.24f, 0.24f, h);
        }
        for (BaseVehicle vehicle : cell.getVehicles()) {
            var script = vehicle.getScript();
            if (script == null || Math.abs(vehicle.getX() - viewer.getX()) > RADIUS + 5
                || Math.abs(vehicle.getY() - viewer.getY()) > RADIUS + 5) continue;
            vehicle.getForwardVector(forward);
            var extents = script.getExtents();
            stamped |= stamp(x, y, z, vehicle.getX(), vehicle.getY(), vehicle.getZ(), forward.x, forward.z,
                extents.x * 0.5f, extents.z * 0.5f, Math.min(1f, extents.y / 2.45f));
        }
        Field previous = active;
        if (!stamped) active = null;
        else if (previous == null || previous.x != x || previous.y != y || previous.z != z
            || !Arrays.equals(scratch, previous.pixels)) active = new Field(x, y, z, scratch.clone());
    }

    private static boolean stamp(int x, int y, int z, float wx, float wy, float wz,
        float dx, float dy, float halfWidth, float halfLength, float height) {
        int level = (int) Math.floor(wz) - z;
        if (level < 0 || level >= LEVELS) return false;
        float ox = (wx - x) * PER, oy = (wy - y) * PER;
        float radius = PER * (Math.max(halfWidth, halfLength) + 0.5f);
        // No direction means round (a character)
        boolean round = dx == 0f && dy == 0f;
        int x0 = Math.max(0, (int) (ox - radius)), x1 = Math.min(SIZE - 1, (int) (ox + radius));
        int y0 = Math.max(0, (int) (oy - radius)), y1 = Math.min(SIZE - 1, (int) (oy + radius));
        int top = Math.min(255, Math.round((wz - (float) Math.floor(wz) + height) * 255f));
        boolean hit = false;
        for (int v = y0; v <= y1; v++) {
            for (int u = x0; u <= x1; u++) {
                float px = (u + 0.5f - ox) / PER, py = (v + 0.5f - oy) / PER;
                if (round ? px * px + py * py > halfWidth * halfWidth
                    : Math.abs(px * dy - py * dx) > halfWidth || Math.abs(px * dx + py * dy) > halfLength) continue;
                int i = (level * SIZE + v) * SIZE + u;
                scratch[i] = (byte) Math.max(scratch[i] & 255, top);
                hit = true;
            }
        }
        return hit;
    }

    static void bind(int sampler, int region, boolean enabled) {
        if (sampler < 0 || region < 0) return;
        Field field = enabled ? active : null;
        GL20.glUniform1i(sampler, UNIT);
        GL20.glUniform4f(region, field == null ? 0 : field.x, field == null ? 0 : field.y,
            field == null ? 0 : field.z, field == null ? 0 : (float) SIZE / PER);
        if (field == null) return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        try {
            if (texture == 0) {
                texture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, SIZE, SIZE * LEVELS, 0, GL11.GL_RED,
                    GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                pixels = BufferUtils.createByteBuffer(scratch.length);
            } else {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            }
            if (uploaded != field) {
                pixels.clear().put(field.pixels).flip();
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE * LEVELS, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixels);
                uploaded = field;
            }
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }
}
