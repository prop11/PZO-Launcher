
package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import se.krka.kahlua.vm.KahluaTable;
import zombie.iso.IsoCell;
import zombie.network.GameClient;
import zombie.world.moddata.GlobalModData;

final class GoreSurfaces {
    static final int FLOOR = 0, NORTH = 1, WEST = 2;
    static final int MAX_MARKS = 128;
    static final int QUANTA = 32;
    private static final int SESSION_PAGES = 256;
    private static final String SAVE_KEY = "PZOE_SurfaceBlood";
    private static final String RAIN_KEY = "rain";
    private static final Map<Long, Page> loaded = new HashMap<>();
    private static final LinkedHashMap<Long, String> session = new LinkedHashMap<>(16, 0.75f, true);
    private static IsoCell world;
    private static KahluaTable saved;
    private static boolean client;
    private static Rain rain = new Rain();

    private GoreSurfaces() {}

    static void world(IsoCell cell) {
        if (world == cell) return;
        world = cell;
        loaded.clear();
        session.clear();
        client = GameClient.client;
        saved = world == null || client ? null : GlobalModData.instance.get(SAVE_KEY);
        rain = Rain.decode(saved == null ? null : saved.rawget(RAIN_KEY));
    }

    static Rain rain() {
        return rain;
    }

    static Page page(int wx, int wy, boolean create) {
        if (world == null) return null;
        long key = key(wx, wy);
        Page p = loaded.get(key);
        if (p != null) return p;
        Object text = client ? session.get(key) : saved == null ? null : saved.rawget(Long.toString(key));
        p = Page.decode(text);
        if (p == null && create) p = new Page();
        if (p != null) loaded.put(key, p);
        return p;
    }

    static void release(int wx, int wy) {
        long key = key(wx, wy);
        Page p = loaded.remove(key);
        if (p != null && p.dirty) write(key, p);
    }

    static void releaseAll() {
        for (var e : loaded.entrySet()) {
            if (e.getValue().dirty) write(e.getKey(), e.getValue());
        }
        loaded.clear();
    }

    static void save() {
        if (world == null || client || GameClient.client) return;
        for (var e : loaded.entrySet()) {
            if (e.getValue().dirty) write(e.getKey(), e.getValue());
        }
        if (rain.total > 0.0) table().rawset(RAIN_KEY, rain.encode());
    }

    private static void write(long key, Page p) {
        String text = p.count == 0 ? null : p.encode();
        if (client) {
            if (text == null) session.remove(key);
            else {
                session.put(key, text);
                if (session.size() > SESSION_PAGES) {
                    var oldest = session.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
            }
        } else if (text != null || saved != null) {
            table().rawset(Long.toString(key), text);
        }
        p.dirty = false;
    }

    private static KahluaTable table() {
        if (saved == null) saved = GlobalModData.instance.getOrCreate(SAVE_KEY);
        return saved;
    }

    private static long key(int wx, int wy) {
        return ((long) wx << 32) ^ (wy & 0xffffffffL);
    }

    static int loc(float x, float y, int level, int type) {
        int xq = Math.max(0, Math.min(255, (int) (x * QUANTA)));
        int yq = Math.max(0, Math.min(255, (int) (y * QUANTA)));
        return xq | (yq << 8) | (level << 16) | (type << 18);
    }

    static float x(int loc) { return ((loc & 255) + 0.5f) / QUANTA; }
    static float y(int loc) { return (((loc >>> 8) & 255) + 0.5f) / QUANTA; }
    static int level(int loc) { return (loc >>> 16) & 3; }
    static int type(int loc) { return loc >>> 18; }

    private static int bin(int loc) {
        return (loc & 0xF0F0) | (loc & 0xF0000);
    }

    static long shape(int angle, float len, float width, float amount, float thickness, boolean porous) {
        return (angle & 255L) | (quantize(len) << 8) | (quantize(width) << 16) | (quantize(amount) << 24)
            | (quantize(thickness) << 32) | (porous ? (1L << 40) : 0L);
    }

    private static long quantize(float n) {
        return Math.max(0, Math.min(255, Math.round(n * 255f)));
    }

    static int angle(long shape) { return (int) shape & 255; }
    static float length(long shape) { return value(shape, 8); }
    static float width(long shape) { return value(shape, 16); }
    static float amount(long shape) { return value(shape, 24); }
    static float thickness(long shape) { return value(shape, 32); }
    static boolean porous(long shape) { return (shape & (1L << 40)) != 0; }
    private static float value(long shape, int offset) { return ((shape >>> offset) & 255L) / 255f; }

    static final class Page {
        static final int FULL = -2;
        private static final int INDEX_SIZE = 256, STRIDE = 4 + 8 + 4;
        final int[] loc = new int[MAX_MARKS];
        final long[] shape = new long[MAX_MARKS];
        final float[] born = new float[MAX_MARKS];
        private final short[] bins = new short[INDEX_SIZE];
        int count;
        boolean dirty;
        int lastWashPrune;

        int add(int at, long form, float time) {
            int slot = find(at);
            int i = bins[slot] - 1;
            if (i < 0) {
                if (count >= MAX_MARKS) return FULL;
                i = count++;
                bins[slot] = (short) (i + 1);
            } else {
                if (time - born[i] < 0.04f && length(form) <= length(shape[i]) && thickness(form) <= thickness(shape[i])) return -1;
                at = loc[i];
                long previous = shape[i];
                form = shape(angle(previous), Math.max(length(previous), length(form)), Math.max(width(previous), width(form)),
                    Math.max(amount(previous), amount(form)), Math.max(thickness(previous), thickness(form)), porous(previous));
            }
            loc[i] = at;
            shape[i] = form;
            born[i] = time;
            dirty = true;
            return i;
        }

        boolean clean(int sx, int sy, int z, boolean onlyWall) {
            int next = 0;
            for (int i = 0; i < count; i++) {
                int l = loc[i];
                if (level(l) == z && ((l & 255) >>> 5) == sx && (((l >>> 8) & 255) >>> 5) == sy
                    && (!onlyWall || type(l) != FLOOR)) continue;
                loc[next] = l;
                shape[next] = shape[i];
                born[next++] = born[i];
            }
            if (next == count) return false;
            count = next;
            dirty = true;
            reindex();
            return true;
        }

        boolean reclaimWashed(zombie.iso.IsoChunk ch) {
            int interval = rain.washInterval();
            if (interval == lastWashPrune) return false;
            lastWashPrune = interval;
            int next = 0;
            for (int i = 0; i < count; i++) {
                int l = loc[i];
                if (rain.remaining(born[i]) <= 0f) {
                    var square = ch.getGridSquare((int) x(l), (int) y(l), level(l));
                    if (square == null || square.isOutside()) continue;
                }
                loc[next] = l;
                shape[next] = shape[i];
                born[next++] = born[i];
            }
            if (next == count) return false;
            count = next;
            dirty = true;
            reindex();
            return true;
        }

        boolean visible(int sx, int sy, int z, boolean wall, boolean exposed) {
            for (int i = 0; i < count; i++) {
                int l = loc[i];
                if (level(l) == z && ((l & 255) >>> 5) == sx && (((l >>> 8) & 255) >>> 5) == sy
                    && wall == (type(l) != FLOOR) && (!exposed || rain.remaining(born[i]) > 0.18f)) return true;
            }
            return false;
        }

        private int find(int at) {
            int key = bin(at);
            int slot = ((key ^ (key >>> 9)) * 0x9E3779B9) >>> 24;
            while (bins[slot] != 0 && bin(loc[bins[slot] - 1]) != key) slot = (slot + 1) & (INDEX_SIZE - 1);
            return slot;
        }

        private void reindex() {
            Arrays.fill(bins, (short) 0);
            for (int i = 0; i < count; i++) bins[find(loc[i])] = (short) (i + 1);
        }

        String encode() {
            ByteBuffer b = ByteBuffer.allocate(2 + count * STRIDE);
            b.put((byte) 1).put((byte) count);
            for (int i = 0; i < count; i++) b.putInt(loc[i]).putLong(shape[i]).putFloat(born[i]);
            return Base64.getEncoder().encodeToString(b.array());
        }

        static Page decode(Object text) {
            if (!(text instanceof String s) || s.length() > ((2 + MAX_MARKS * STRIDE + 2) / 3) * 4) return null;
            try {
                ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(s));
                if (b.remaining() < 2 || b.get() != 1) return null;
                int n = b.get() & 255;
                if (n == 0 || n > MAX_MARKS || b.remaining() != n * STRIDE) return null;
                Page p = new Page();
                for (int i = 0; i < n; i++) {
                    int l = b.getInt();
                    long form = b.getLong();
                    float birth = b.getFloat();
                    if (type(l) < FLOOR || type(l) > WEST || (form >>> 41) != 0 || !Float.isFinite(birth) || birth < 0f) return null;
                    int slot = p.find(l);
                    if (p.bins[slot] != 0) return null;
                    p.bins[slot] = (short) (i + 1);
                    p.loc[i] = l;
                    p.shape[i] = form;
                    p.born[i] = birth;
                }
                p.count = n;
                return p;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    static final class Rain {
        // Sample cumulative rain by dose rather than clock time. Dry spells take no space.
        private static final int STEPS = 64, PER_WASH = 32;
        private final float[] crossing = new float[STEPS];
        private double total;
        private double hours = -1.0;

        void resume(double now) {
            hours = now;
        }

        float advance(double now, float intensity) {
            double before = hours;
            hours = now;
            if (before < 0.0 || now <= before || intensity <= 0.05f) return 0f;
            double added = Math.min(1.0, now - before) * intensity / 1.5;
            int first = (int) (total * PER_WASH) + 1, last = (int) ((total + added) * PER_WASH);
            for (int step = first; step <= last; step++) {
                double fraction = (step / (double) PER_WASH - total) / added;
                crossing[step & (STEPS - 1)] = (float) (now - Math.min(1.0, now - before) * (1.0 - fraction));
            }
            total += added;
            return (float) added;
        }

        int washInterval() {
            return (int) (total * 8.0);
        }

        float remaining(float birth) {
            if (total <= 0.0 || birth >= hours) return 1f;
            int current = (int) (total * PER_WASH);
            int lower = Math.max(1, current - STEPS + 1);
            if (lower > 1 && birth < crossing[lower & (STEPS - 1)]) return 0f;
            int lo = lower, hi = current + 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (crossing[mid & (STEPS - 1)] <= birth) lo = mid + 1;
                else hi = mid;
            }
            double atBirth = (lo - 1) / (double) PER_WASH;
            return (float) Math.max(0.0, 1.0 - total + atBirth);
        }

        String encode() {
            ByteBuffer b = ByteBuffer.allocate(1 + 16 + 4 * STEPS).put((byte) 1).putDouble(total).putDouble(hours);
            for (float value : crossing) b.putFloat(value);
            return Base64.getEncoder().encodeToString(b.array());
        }

        static Rain decode(Object text) {
            Rain result = new Rain();
            if (!(text instanceof String s) || s.length() != 364) return result;
            try {
                ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(s));
                if (b.remaining() != 1 + 16 + 4 * STEPS || b.get() != 1) return result;
                double total = b.getDouble(), hours = b.getDouble();
                if (!Double.isFinite(total) || total < 0.0 || total > 10_000_000.0 || !Double.isFinite(hours)) return result;
                for (int i = 0; i < STEPS; i++) {
                    float at = b.getFloat();
                    if (!Float.isFinite(at)) return new Rain();
                    result.crossing[i] = at;
                }
                result.total = total;
                result.hours = hours;
            } catch (IllegalArgumentException ignored) {
                return result;
            }
            return result;
        }
    }
}
