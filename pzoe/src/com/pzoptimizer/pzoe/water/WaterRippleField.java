package com.pzoptimizer.pzoe.water;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.iso.weather.ClimateManager;

public final class WaterRippleField {
    static final int SIZE = 64;
    private static final int CELLS_PER_TILE = 2, SHIFT = 8, COUNT = SIZE * SIZE;
    private static final int UNIT = 7;
    private static final float DT = 1f / 30f, LIFE = 3.8f;
    private static final State[] states = {new State(), new State(), new State(), new State()};
    private static volatile long lastRequest;
    private static boolean running;

    private static final class State {
        volatile long asked;
        IsoCell cell;
        int x, y;
        int random = 135525629;
        long maskAt;
        float time, active;
        byte[] wet, display, nextDisplay;
        float[] current, previous, scratch;
        int serial, visibleX, visibleY;
        boolean ready;

        int texture, uploaded = -1;
        ByteBuffer pixels;
    }

    private WaterRippleField() {}

    public static void impulse(float x, float y, float power) {
        float wx = x * CELLS_PER_TILE, wy = y * CELLS_PER_TILE;
        for (State state : states) inject(state, wx, wy, 0.6f * power);
    }

    private static boolean inject(State state, float wx, float wy, float power) {
        int x = (int) Math.floor(wx) - state.x, y = (int) Math.floor(wy) - state.y;
        if (state.wet == null || state.cell == null || x < 1 || y < 1 || x >= SIZE - 1 || y >= SIZE - 1) return false;
        boolean touched = false;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int i = (y + dy) * SIZE + x + dx;
                if (state.wet[i] == 0) continue;
                float weight = dx == 0 && dy == 0 ? 1f : dx == 0 || dy == 0 ? 0.34f : 0.14f;
                state.current[i] = Math.max(-1f, Math.min(1f, state.current[i] + power * weight));
                state.previous[i] = Math.max(-1f, Math.min(1f, state.previous[i] + power * weight * 0.75f));
                touched = true;
            }
        }
        if (touched) state.active = LIFE;
        return touched;
    }

    public static void update(float dt, long now) {
        boolean requested = lastRequest != 0L && now - lastRequest <= 1_000_000_000L;
        if (!running && !requested) return;
        running = requested;
        var world = IsoWorld.instance;
        IsoCell cell = world == null ? null : world.currentCell;
        for (int p = 0; p < states.length; p++) {
            State state = states[p];
            IsoPlayer player = IsoPlayer.players[p];
            if (cell == null || player == null || state.asked == 0L || now - state.asked > 1_000_000_000L) {
                if (state.cell != null) reset(state);
                continue;
            }
            int x = Math.floorDiv((int) Math.floor(player.getX() * CELLS_PER_TILE), SHIFT) * SHIFT - SIZE / 2;
            int y = Math.floorDiv((int) Math.floor(player.getY() * CELLS_PER_TILE), SHIFT) * SHIFT - SIZE / 2;
            if (state.cell != cell || state.x != x || state.y != y || now - state.maskAt >= 2_000_000_000L) {
                move(state, cell, x, y);
                state.maskAt = now;
            }
            state.time = Math.min(state.time + dt, DT * 3f);
            if (state.time < DT) continue;
            ClimateManager climate = ClimateManager.getInstance();
            float rain = climate == null || climate.getPrecipitationIsSnow() ? 0f : climate.getRainIntensity();
            if (rain > 0.07f) {
                for (int i = 0, count = 1 + (int) (rain * 4f); i < count; i++) {
                    int n = next(state);
                    float wx = state.x + (n & (SIZE - 1)) + 0.5f, wy = state.y + ((n >>> 16) & (SIZE - 1)) + 0.5f;
                    inject(state, wx, wy, 0.07f + 0.06f * rain);
                }
            }
            if (state.active <= 0f) {
                state.time = 0f;
                if (state.ready) {
                    Arrays.fill(state.current, 0f);
                    Arrays.fill(state.previous, 0f);
                    synchronized (state) {
                        state.ready = false;
                    }
                }
                continue;
            }
            while (state.time >= DT) {
                advance(state.current, state.previous, state.wet);
                float[] next = state.previous;
                state.previous = state.current;
                state.current = next;
                state.active -= DT;
                state.time -= DT;
            }
            gradients(state.current, state.wet, state.nextDisplay);
            synchronized (state) {
                byte[] previousDisplay = state.display;
                state.display = state.nextDisplay;
                state.nextDisplay = previousDisplay;
                state.visibleX = state.x;
                state.visibleY = state.y;
                state.serial++;
                state.ready = true;
            }
        }
    }

    private static int next(State state) {
        int x = state.random;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        return state.random = x;
    }

    private static void reset(State state) {
        state.cell = null;
        state.active = state.time = 0f;
        Arrays.fill(state.current, 0f);
        Arrays.fill(state.previous, 0f);
        synchronized (state) {
            state.ready = false;
        }
    }

    private static void move(State state, IsoCell cell, int x, int y) {
        if (state.wet == null) {
            state.wet = new byte[COUNT];
            state.display = new byte[COUNT * 2];
            state.nextDisplay = new byte[COUNT * 2];
            state.current = new float[COUNT];
            state.previous = new float[COUNT];
            state.scratch = new float[COUNT];
        }
        int dx = x - state.x, dy = y - state.y;
        if (state.cell != cell || Math.abs(dx) >= SIZE || Math.abs(dy) >= SIZE) reset(state);
        else if (state.active > 0f && (dx != 0 || dy != 0)) {
            shift(state.current, state.scratch, dx, dy);
            shift(state.previous, state.scratch, dx, dy);
        }
        state.x = x;
        state.y = y;
        state.cell = cell;
        for (int py = 0; py < SIZE; py += 2) {
            for (int px = 0; px < SIZE; px += 2) {
                int wx = (x + px) / 2, wy = (y + py) / 2;
                var square = cell.getGridSquare(wx, wy, 0);
                int i = py * SIZE + px;
                byte wet = (byte) (WaterSurface.liquidDepthAt(square, wx + 0.5f, wy + 0.5f) >= 0.45f ? 1 : 0);
                state.wet[i] = state.wet[i + 1] = state.wet[i + SIZE] = state.wet[i + SIZE + 1] = wet;
            }
        }
        for (int i = 0; i < COUNT; i++) {
            if (state.wet[i] == 0) state.previous[i] = state.current[i] = 0f;
        }
    }

    private static void shift(float[] field, float[] scratch, int dx, int dy) {
        Arrays.fill(scratch, 0f);
        int l = Math.max(0, -dx), r = Math.min(SIZE, SIZE - dx);
        for (int y = Math.max(0, -dy), end = Math.min(SIZE, SIZE - dy); y < end; y++) {
            System.arraycopy(field, (y + dy) * SIZE + l + dx, scratch, y * SIZE + l, r - l);
        }
        System.arraycopy(scratch, 0, field, 0, COUNT);
    }

    static void advance(float[] current, float[] previous, byte[] wet) {
        Arrays.fill(previous, 0, SIZE, 0f);
        Arrays.fill(previous, COUNT - SIZE, COUNT, 0f);
        for (int y = 1; y < SIZE - 1; y++) {
            previous[y * SIZE] = previous[(y + 1) * SIZE - 1] = 0f;
            for (int x = 1; x < SIZE - 1; x++) {
                int i = y * SIZE + x;
                if (wet[i] == 0) {
                    previous[i] = 0f;
                    continue;
                }
                float center = current[i];
                float laplace = at(current, wet, i - 1, center) + at(current, wet, i + 1, center)
                    + at(current, wet, i - SIZE, center) + at(current, wet, i + SIZE, center) - center * 4f;
                int border = Math.min(Math.min(x, SIZE - 1 - x), Math.min(y, SIZE - 1 - y));
                float damping = border < 5 ? 0.64f + border * 0.064f : 0.974f;
                previous[i] = (2f * center - previous[i] + 0.19f * laplace) * damping;
            }
        }
    }

    private static float at(float[] height, byte[] wet, int i, float center) {
        return wet[i] != 0 ? height[i] : center;
    }

    private static void gradients(float[] height, byte[] wet, byte[] out) {
        Arrays.fill(out, (byte) 128);
        for (int y = 1; y < SIZE - 1; y++) {
            for (int x = 1; x < SIZE - 1; x++) {
                int i = y * SIZE + x;
                if (wet[i] == 0) continue;
                float center = height[i];
                out[i * 2] = encode(at(height, wet, i + 1, center) - at(height, wet, i - 1, center));
                out[i * 2 + 1] = encode(at(height, wet, i + SIZE, center) - at(height, wet, i - SIZE, center));
            }
        }
    }

    private static byte encode(float value) {
        return (byte) Math.round(128f + Math.max(-127f, Math.min(127f, value * 160f)));
    }

    static void bind(int sampler, int region, int player) {
        GL20.glUniform1i(sampler, UNIT);
        if (player < 0 || player >= states.length) {
            GL20.glUniform3f(region, 0f, 0f, 0f);
            return;
        }
        State state = states[player];
        state.asked = System.nanoTime();
        lastRequest = state.asked;
        int serial, x, y;
        synchronized (state) {
            if (!state.ready) {
                GL20.glUniform3f(region, 0f, 0f, 0f);
                return;
            }
            serial = state.serial;
            x = state.visibleX;
            y = state.visibleY;
            if (state.uploaded != serial) {
                if (state.pixels == null) state.pixels = BufferUtils.createByteBuffer(COUNT * 2);
                state.pixels.clear().put(state.display).flip();
            }
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        try {
            if (state.texture == 0) {
                state.texture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, state.texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, SIZE, SIZE, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, state.pixels);
            } else {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, state.texture);
                if (state.uploaded != serial) {
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, state.pixels);
                }
            }
            state.uploaded = serial;
            GL20.glUniform3f(region, (float) x / CELLS_PER_TILE, (float) y / CELLS_PER_TILE, (float) SIZE / CELLS_PER_TILE);
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
        }
    }
}
