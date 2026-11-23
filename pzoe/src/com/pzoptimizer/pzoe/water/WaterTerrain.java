package com.pzoptimizer.pzoe.water;

import com.pzoptimizer.pzoe.weather.WeatherLook;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;
import zombie.iso.IsoWaterFlow;
import zombie.iso.SpriteDetails.IsoFlagType;

public final class WaterTerrain {
    private static final int MAX_DISTANCE = 24;
    private static final int[] WIND_X = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] WIND_Y = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final PlayerField[] fields = {new PlayerField(), new PlayerField(), new PlayerField(), new PlayerField()};
    private static final int TEXTURE_UNIT = 4;

    private record MapData(int x, int y, int width, byte[] pixels) {}

    private static final class PlayerField {
        volatile long asked;
        volatile MapData current;
        IsoChunkMap map;
        int x, y, octant = -1, grid;
        long checked;
        IsoChunk[] chunks = new IsoChunk[0];
        long[] masks = new long[0], coordinates = new long[0];
        byte[] terrain = new byte[0];
        short[] currents = new short[0];
        int[] distances = new int[0], fetch = new int[0];
        int texture, textureWidth;
        ByteBuffer pixels;
        MapData uploaded;
    }

    private WaterTerrain() {}

    static void update(long now) {
        for (int player = 0; player < fields.length; player++) {
            PlayerField data = fields[player];
            if (data.asked == 0L || now - data.asked >= 1_000_000_000L) {
                data.current = null;
                if (data.map != null) {
                    data.map = null;
                    Arrays.fill(data.chunks, null);
                }
                continue;
            }
            if (now - data.checked < 200_000_000L) continue;
            data.checked = now;
            var world = IsoWorld.instance;
            if (world == null || world.currentCell == null || IsoPlayer.players[player] == null) {
                data.current = null;
                data.map = null;
                Arrays.fill(data.chunks, null);
                continue;
            }
            IsoChunkMap map = world.currentCell.getChunkMap(player);
            if (map == null || map.getChunks() == null) continue;
            publish(data, map);
        }
    }

    private static void publish(PlayerField data, IsoChunkMap map) {
        int grid = IsoChunkMap.chunkGridWidth, width = 8 * grid;
        int originX = map.getWorldXMinTiles(), originY = map.getWorldYMinTiles();
        int octant = Math.floorMod(Math.round(WeatherLook.windAngleRadians() * (4f / (float) Math.PI)), 8);
        boolean reset = data.map != map || data.grid != grid || data.current == null;
        if (data.grid != grid) {
            data.chunks = new IsoChunk[grid * grid];
            data.masks = new long[grid * grid];
            data.coordinates = new long[grid * grid];
            data.currents = new short[grid * grid];
            data.terrain = new byte[width * width];
            data.distances = new int[width * width];
            data.fetch = new int[width * width];
        }
        if (reset) Arrays.fill(data.chunks, null);
        boolean changed = reset || data.octant != octant || data.x != originX || data.y != originY;
        IsoChunk[] chunks = map.getChunks();
        int cx = originX / 8, cy = originY / 8;
        for (int i = 0; i < grid * grid; i++) {
            int wx = cx + i % grid, wy = cy + i / grid;
            IsoChunk chunk = chunks[i];
            if (chunk != null && (!chunk.loaded || chunk.wx != wx || chunk.wy != wy)) chunk = null;
            int slot = slot(wx, wy, grid);
            long key = ((long) wx << 32) | (wy & 0xFFFFFFFFL);
            if (data.chunks[slot] == chunk && data.coordinates[slot] == key && !reset) continue;
            changed = true;
            data.chunks[slot] = chunk;
            data.coordinates[slot] = key;
            data.masks[slot] = chunk == null ? 0L : waterMask(chunk);
            data.currents[slot] = data.masks[slot] == 0L ? (short) 0x8080 : current(8 * wx + 4, 8 * wy + 4);
        }
        if (!changed) return;
        for (int i = 0; i < grid * grid; i++) {
            int x = i % grid, y = i / grid;
            int slot = slot(cx + x, cy + y, grid);
            IsoChunk chunk = data.chunks[slot];
            long water = data.masks[slot];
            for (int tile = 0; tile < 64; tile++) {
                int index = (8 * y + (tile >> 3)) * width + 8 * x + (tile & 7);
                data.terrain[index] = chunk == null ? 2 : (byte) ((water >>> tile) & 1);
            }
        }
        byte[] coast = new byte[width * width * 2];
        measure(data.terrain, data.distances, data.fetch, coast, width, octant);
        byte[] result = new byte[width * width * 4];
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int flow = sampleCurrent(data.currents, grid, cx, cy, x, y);
                result[4 * i] = data.terrain[i] == 0 ? (byte) shoreCaustic(data.terrain, width, x, y) : coast[2 * i];
                result[4 * i + 1] = coast[2 * i + 1];
                result[4 * i + 2] = (byte) flow;
                result[4 * i + 3] = (byte) (flow >>> 8);
            }
        }
        data.map = map;
        data.grid = grid;
        data.x = originX;
        data.y = originY;
        data.octant = octant;
        data.current = new MapData(originX, originY, width, result);
    }

    private static int shoreCaustic(byte[] field, int width, int x, int y) {
        int i = y * width + x;
        if (x > 0 && field[i - 1] == 1 || x + 1 < width && field[i + 1] == 1
            || y > 0 && field[i - width] == 1 || y + 1 < width && field[i + width] == 1) return 12;
        return 0;
    }

    private static int slot(int x, int y, int grid) {
        return Math.floorMod(y, grid) * grid + Math.floorMod(x, grid);
    }

    private static short current(int x, int y) {
        if (IsoWaterFlow.getPoints().isEmpty()) return (short) 0x8080;
        var points = IsoWaterFlow.getNearestMultiple(x, y, 3);
        double flowX = 0, flowY = 0, weights = 0;
        for (var point : points) {
            double dx = point.x - x, dy = point.y - y;
            double weight = 1.0 / (4.0 + dx * dx + dy * dy);
            double strength = Math.max(0, Math.min(1, point.speed));
            flowX += Math.cos(point.radians) * strength * weight;
            flowY += Math.sin(point.radians) * strength * weight;
            weights += weight;
        }
        if (weights == 0) return (short) 0x8080;
        return (short) (Math.round(128 + 127 * flowX / weights) | Math.round(128 + 127 * flowY / weights) << 8);
    }

    private static int sampleCurrent(short[] currents, int grid, int cx, int cy, int x, int y) {
        float u = Math.max(0f, Math.min(grid - 1f, (x - 3.5f) / 8f));
        float v = Math.max(0f, Math.min(grid - 1f, (y - 3.5f) / 8f));
        int u0 = (int) u, v0 = (int) v;
        int u1 = Math.min(u0 + 1, grid - 1), v1 = Math.min(v0 + 1, grid - 1);
        int a = currents[slot(cx + u0, cy + v0, grid)], b = currents[slot(cx + u1, cy + v0, grid)];
        int c = currents[slot(cx + u0, cy + v1, grid)], d = currents[slot(cx + u1, cy + v1, grid)];
        float f = u - u0, g = v - v0;
        int xFlow = mixCurrent(a, b, c, d, f, g);
        int yFlow = mixCurrent(a >>> 8, b >>> 8, c >>> 8, d >>> 8, f, g);
        return xFlow | yFlow << 8;
    }

    private static int mixCurrent(int a, int b, int c, int d, float f, float g) {
        float upper = (a & 255) * (1f - f) + (b & 255) * f;
        float lower = (c & 255) * (1f - f) + (d & 255) * f;
        return Math.round(upper * (1f - g) + lower * g);
    }

    private static long waterMask(IsoChunk chunk) {
        long water = 0;
        for (int i = 0; i < 64; i++) {
            var square = chunk.getGridSquare(i & 7, i >> 3, 0);
            if (square != null && square.has(IsoFlagType.water)) water |= 1L << i;
        }
        return water;
    }

    static void measure(byte[] terrain, int[] distances, int[] fetch, byte[] out, int width, int octant) {
        int max = MAX_DISTANCE * 3;
        for (int i = 0; i < terrain.length; i++) distances[i] = terrain[i] == 0 ? 0 : max;
        // Chamfer distance to known land. Unloaded squares do not count as a shore.
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int best = distances[i];
                if (x > 0) best = Math.min(best, distances[i - 1] + 3);
                if (y > 0) {
                    best = Math.min(best, distances[i - width] + 3);
                    if (x > 0) best = Math.min(best, distances[i - width - 1] + 4);
                    if (x + 1 < width) best = Math.min(best, distances[i - width + 1] + 4);
                }
                distances[i] = best;
            }
        }
        for (int y = width - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                int i = y * width + x;
                int best = distances[i];
                if (x + 1 < width) best = Math.min(best, distances[i + 1] + 3);
                if (y + 1 < width) {
                    best = Math.min(best, distances[i + width] + 3);
                    if (x > 0) best = Math.min(best, distances[i + width - 1] + 4);
                    if (x + 1 < width) best = Math.min(best, distances[i + width + 1] + 4);
                }
                distances[i] = best;
                out[2 * i] = (byte) (terrain[i] == 2 ? 255 : (best * 255 + max / 2) / max);
            }
        }
        int dx = WIND_X[octant], dy = WIND_Y[octant];
        for (int row = 0; row < width; row++) {
            int y = dy < 0 ? width - 1 - row : row;
            for (int col = 0; col < width; col++) {
                int x = dx < 0 ? width - 1 - col : col;
                int i = y * width + x, upstreamX = x - dx, upstreamY = y - dy;
                int previous = upstreamX < 0 || upstreamY < 0 || upstreamX >= width || upstreamY >= width
                    ? MAX_DISTANCE : fetch[upstreamY * width + upstreamX];
                fetch[i] = terrain[i] == 0 ? 0 : terrain[i] == 2 ? MAX_DISTANCE : Math.min(previous + 1, MAX_DISTANCE);
                float distance = dx != 0 && dy != 0 ? fetch[i] * 1.4142136f : fetch[i];
                out[2 * i + 1] = (byte) Math.min(255, Math.round(distance * (255f / MAX_DISTANCE)));
            }
        }
    }

    static void bind(int sampler, int region, int player) {
        bind(sampler, region, player, TEXTURE_UNIT, GL13.GL_TEXTURE2);
    }

    public static void bindShore(int sampler, int region, int player) {
        bind(sampler, region, player, 16, GL13.GL_TEXTURE0);
    }

    private static void bind(int sampler, int region, int player, int unit, int restore) {
        if (player < 0 || player >= fields.length) {
            GL20.glUniform3f(region, 0f, 0f, 0f);
            return;
        }
        PlayerField data = fields[player];
        data.asked = System.nanoTime();
        MapData map = data.current;
        GL20.glUniform1i(sampler, unit);
        GL20.glUniform3f(region, map == null ? 0f : map.x(), map == null ? 0f : map.y(), map == null ? 0f : map.width());
        if (map == null) return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        try {
            if (data.texture == 0) data.texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, data.texture);
            if (data.uploaded == map) return;
            if (data.pixels == null || data.pixels.capacity() < map.pixels().length) {
                data.pixels = BufferUtils.createByteBuffer(map.pixels().length);
            }
            data.pixels.clear().put(map.pixels()).flip();
            int width = map.width();
            if (width != data.textureWidth) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, width, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data.pixels);
                data.textureWidth = width;
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, width, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data.pixels);
            }
            data.uploaded = map;
        } finally {
            GL13.glActiveTexture(restore);
        }
    }
}
