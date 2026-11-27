package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoRoomLight;
import zombie.iso.IsoWorld;

/**
 * Light Colours: street lamps and room lights get the colour of the kind of light they are, for the chunk shader patch
 * that declares PZOLightTint. Only the picture changes; the game's own light colour, which gameplay reads, stays.
 *
 *   uniform vec4 PZOLightTint[32];   per light: (x0, y0, x1, y1) room rectangle or (x, y, reach, 0) lamp,
 *                                    (r, g, b, level + 0.5 for a street lamp, + 0.75 for a TV)
 *   uniform vec4 PZOLightTintInfo;   count, darkness 0..1, strength, 1 = live
 *
 * Lamps: powered lamppost lights outside rooms, sodium orange. Room lights by the room's name: homes warm bulbs,
 * shops, offices, schools, hospitals, police, warehouses and garages cool fluorescent. Only near-white lights are
 * tinted; a light the game already colours (TVs, coloured lamps, fires) is left alone.
 *
 * Main thread, 4 times a second while the shader asks: the 16 nearest to player 0 within 30 squares.
 */
final class LightColours {

    static final int MAX = 16;
    private static final float RANGE_SQ = 30f * 30f;
    // Luma 1 each, so a tint shifts the hue but not the brightness
    private static final float[] SODIUM = norm(1.0f, 0.74f, 0.42f);
    private static final float[] WARM = norm(1.0f, 0.86f, 0.68f);
    private static final float[] FLUORESCENT = norm(0.9f, 1.0f, 1.02f);
    private static final float[] SCREEN = norm(0.55f, 0.72f, 1.0f);

    static volatile boolean wanted;
    static volatile float amount = 1f;
    private static volatile float[] published = new float[0];
    private static volatile float darkness;
    private static long lastGather;
    private static final float[] cand = new float[MAX * 9];
    private static int count;

    private static final FloatBuffer buf = ByteBuffer.allocateDirect(MAX * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static float[] uploaded;

    private LightColours() {}

    private static float[] norm(float r, float g, float b) {
        float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        return new float[] {r / l, g / l, b / l};
    }

    /** Main thread, once a frame (PixelLight.frame). */
    static void frame(float dayLight) {
        if (!wanted) {
            if (published.length > 0) published = new float[0];
            return;
        }
        darkness = Math.max(0f, Math.min(1f, 1f - dayLight * 1.4f));
        long now = System.currentTimeMillis();
        if (now - lastGather < 250) return;
        lastGather = now;
        try {
            gather();
        } catch (Throwable t) {
            count = 0;
        }
        float[] out = new float[count * 8];
        for (int i = 0; i < count; i++) System.arraycopy(cand, i * 9, out, i * 8, 8);
        published = out;
    }

    private static boolean white(float r, float g, float b) {
        float hi = Math.max(r, Math.max(g, b)), lo = Math.min(r, Math.min(g, b));
        return hi > 0.05f && (hi - lo) / hi < 0.12f;
    }

    private static void gather() {
        count = 0;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        IsoPlayer p = IsoPlayer.players[0];
        if (cell == null || p == null) return;
        float px = p.getX(), py = p.getY();
        java.util.Stack<IsoLightSource> lamps = cell.getLamppostPositions();
        for (int i = 0, n = lamps.size(); i < n; i++) {
            IsoLightSource l = lamps.get(i);
            if (l == null || !l.isActive()) continue;
            if (!l.isHydroPowered() || l.getRadius() < 3 || !white(l.r, l.g, l.b)) {
                IsoGridSquare tv = cell.getGridSquare(l.getX(), l.getY(), l.getZ());
                if (tv == null || tv.getRoom() == null || !television(tv)) continue;
            }
            IsoGridSquare sq = cell.getGridSquare(l.getX(), l.getY(), l.getZ());
            if (sq == null) continue;
            if (sq.getRoom() != null) {
                // A TV's light is blue only the moment it's made; its flicker then sets a grey white, warm or cool
                // depending on the left over timer. Here it's always the screen's blue.
                if (l.isActive() && television(sq)) {
                    offer(px, py, l.getX() + 0.5f, l.getY() + 0.5f, l.getX() + 0.5f, l.getY() + 0.5f,
                        Math.max(2f, l.getRadius()), l.getZ(), TV, SCREEN);
                }
                continue;
            }
            float cx = l.getX() + 0.5f, cy = l.getY() + 0.5f, r = l.getRadius();
            offer(px, py, cx, cy, cx, cy, r, l.getZ(), LAMP, SODIUM);
        }
        java.util.ArrayList<IsoRoomLight> rooms = cell.roomLights;
        for (int i = 0, n = rooms == null ? 0 : rooms.size(); i < n; i++) {
            IsoRoomLight l = rooms.get(i);
            if (l == null || !l.active || !white(l.r, l.g, l.b)) continue;
            float[] tint = fluorescent(l.room) ? FLUORESCENT : WARM;
            offer(px, py, l.x, l.y, l.x + l.width, l.y + l.height, 0f, l.z, ROOM, tint);
        }
    }

    private static boolean television(IsoGridSquare sq) {
        var objects = sq.getObjects();
        for (int i = 0, n = objects.size(); i < n; i++) {
            if (objects.get(i) instanceof zombie.iso.objects.IsoTelevision tv && tv.getDeviceData() != null
                && tv.getDeviceData().getIsTurnedOn()) return true;
        }
        return false;
    }

    // Commercial and public rooms run on tubes; homes on bulbs
    private static boolean fluorescent(zombie.iso.areas.IsoRoom room) {
        String name = room == null ? null : room.getName();
        if (name == null) return false;
        name = name.toLowerCase(java.util.Locale.ROOT);
        String[] tubes = {"office", "store", "shop", "grocer", "market", "mall", "school", "class", "hospital", "medical",
            "clinic", "police", "warehouse", "factory", "storage", "garage", "mechanic", "gas", "pharmac", "bank",
            "restaurant", "cafeteria", "gym", "lab", "library", "prison", "cell", "lobby", "security",
            "motel", "hotel", "spiffo", "pizza", "burger", "fastfood", "laundr", "salon", "toolstore", "liquor"};
        for (String t : tubes) if (name.contains(t)) return true;
        return false;
    }

    // Kinds go in the level slot's fraction: a room rectangle, a street lamp, a TV
    private static final float ROOM = 0f, LAMP = 0.5f, TV = 0.75f;

    private static void offer(float px, float py, float x0, float y0, float x1, float y1, float reach, int z, float kind, float[] tint) {
        boolean round = kind > 0f;
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
        float dx = cx - px, dy = cy - py, d = dx * dx + dy * dy;
        if (d > RANGE_SQ) return;
        int at;
        if (count < MAX) {
            at = count++;
        } else {
            at = -1;
            float far = d;
            for (int i = 0; i < MAX; i++) {
                if (cand[i * 9 + 8] > far) {
                    far = cand[i * 9 + 8];
                    at = i;
                }
            }
            if (at < 0) return;
        }
        int o = at * 9;
        if (round) {
            cand[o] = cx;
            cand[o + 1] = cy;
            cand[o + 2] = reach;
            cand[o + 3] = 0f;
        } else {
            cand[o] = x0;
            cand[o + 1] = y0;
            cand[o + 2] = x1;
            cand[o + 3] = y1;
        }
        cand[o + 4] = tint[0];
        cand[o + 5] = tint[1];
        cand[o + 6] = tint[2];
        cand[o + 7] = z + kind;
        cand[o + 8] = d;
    }

    /** Render thread, per chunk draw. */
    static void upload(int locArray, int locInfo) {
        if (locInfo < 0) return;
        float[] d = published;
        int n = d.length / 8;
        if (n > 0 && locArray >= 0 && d != uploaded) {
            buf.clear();
            buf.put(d);
            buf.flip();
            GL20.glUniform4fv(locArray, buf);
        }
        uploaded = d;
        GL20.glUniform4f(locInfo, n, darkness, amount, n > 0 ? 1f : 0f);
    }

    static void invalidate() {
        uploaded = null;
    }

    static String stateText() {
        return "lightColoursWanted=" + (wanted ? 1 : 0) + ";lightColours=" + (published.length / 8);
    }
}
