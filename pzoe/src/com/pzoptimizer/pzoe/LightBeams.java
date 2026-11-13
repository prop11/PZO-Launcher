package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

/**
 * Torch and headlight beams in the air, for the chunk shader patch that asks for them (PZO Enhancements' Light Beams).
 * The idea of beams that follow the game's own torch list is from fmac's Atmospherics; this is a much smaller take on
 * it that works inside the chunk composite instead of a voxel volume.
 *
 * Game thread, once a frame: the game's own cone lights (LightingJNI.getTorches: handheld torches and each vehicle
 * light, with the position, aim, reach, strength, cone and colour the lighting engine uses), the 8 nearest to a local
 * player kept. Lanterns and other round lights have no beam. Render thread, per chunk draw: only the cones whose reach
 * can touch that chunk's pixels or the air in front of them go up, and only when that set changed since the last draw
 * (most neighbouring chunks share it), so the shader loops over 0-2 cones on most of the screen.
 *
 * The shader steps along each pixel's view ray only where it passes within a cone's reach, so pixels far from every
 * light do a few multiplies and stop. Walls stop a beam through the light map: the game's own lighting already lights
 * only the squares a torch reaches, so the air in a dark square takes no light.
 *
 * PZOBeams, 3 vec4 a cone: (x, y, level, reach in squares), (aim x, y, z in the composite's metric where a level is
 * 2.45 squares tall, cos of the cone's half angle), (colour times strength, 0). PZOBeamInfo = (count, fog, rain,
 * darkness).
 */
final class LightBeams {

    static final int MAX = 8, FLOATS = 12;
    // The shader follows each view ray up about 2.45 levels: 6 squares toward the camera in x and in y
    private static final float RAY_REACH = 6.5f;
    private static final float RANGE_SQ = 40f * 40f;
    // Handheld torches all get this colour from TorchInfo.set(IsoPlayer, item); vehicle lights take their own
    private static final float TORCH_G = 0.8235294f, TORCH_B = 0.7058824f;

    // Set from the chunk shader's uniform lookups
    static volatile boolean wanted;

    // Game thread
    private static final ArrayList<IsoGameCharacter.TorchInfo> torches = new ArrayList<>();
    private static final float[] best = new float[MAX * (FLOATS + 1)];
    private static float[] last = {0f};
    static volatile float[] published = {0f};
    static volatile int version;

    // Render thread
    private static int sentMask = -1, sentVersion = -1;
    private static final float[] sentInfo = new float[4];
    private static final FloatBuffer buf = ByteBuffer.allocateDirect(MAX * FLOATS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private LightBeams() {}

    /** Main thread, once a frame (PixelLight's frame). */
    static void frame() {
        if (!wanted) {
            if (last.length > 1) publish(new float[] {0f});
            return;
        }
        torches.clear();
        zombie.iso.LightingJNI.getTorches(torches);
        IsoPlayer[] players = IsoPlayer.players;
        int n = 0;
        for (int i = 0, size = torches.size(); i < size; i++) {
            IsoGameCharacter.TorchInfo t = torches.get(i);
            if (t == null || !t.cone || t.strength <= 0.01f || t.dist <= 0.5f) continue;
            float d = distSq(t.x, t.y, players);
            if (d < 0f) continue;
            float ax = t.angleX, ay = t.angleY, al = (float) Math.sqrt(ax * ax + ay * ay);
            if (al < 1e-4f) continue;
            boolean handheld = t.r == 1f && t.g == TORCH_G && t.b == TORCH_B;
            // Torches are held at chest height and aimed a little down; headlights sit low and nearly level
            float height = handheld ? 0.45f : 0.22f, tilt = handheld ? -0.22f : -0.08f;
            float dx = ax / al, dy = ay / al, dl = (float) Math.sqrt(1f + tilt * tilt);
            float k = Math.min(1.5f, t.strength);
            // Tail and brake lights are red and dim next to headlights
            if (!handheld && t.r > t.g * 1.6f) k *= 0.35f;
            int at = slot(n, d);
            if (at < 0) continue;
            if (at == n) n++;
            int o = at * (FLOATS + 1);
            best[o] = t.x;
            best[o + 1] = t.y;
            best[o + 2] = t.z + height;
            best[o + 3] = Math.min(t.dist, 24f);
            best[o + 4] = dx / dl;
            best[o + 5] = dy / dl;
            best[o + 6] = tilt / dl;
            best[o + 7] = Math.max(0.7f, Math.min(0.985f, t.dot));
            best[o + 8] = t.r * k;
            best[o + 9] = t.g * k;
            best[o + 10] = t.b * k;
            best[o + 11] = 0f;
            best[o + FLOATS] = d;
        }
        // Torches move every frame while carried, but a parked car's lights don't: only a change goes out
        boolean same = last.length == 1 + n * FLOATS;
        for (int i = 0; same && i < n; i++) {
            for (int j = 0; j < FLOATS; j++) {
                if (best[i * (FLOATS + 1) + j] != last[1 + i * FLOATS + j]) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;
        float[] out = new float[1 + n * FLOATS];
        out[0] = n;
        for (int i = 0; i < n; i++) System.arraycopy(best, i * (FLOATS + 1), out, 1 + i * FLOATS, FLOATS);
        publish(out);
    }

    private static void publish(float[] out) {
        last = out;
        published = out;
        version++;
    }

    private static float distSq(float x, float y, IsoPlayer[] players) {
        float bestD = -1f;
        for (IsoPlayer p : players) {
            if (p == null) continue;
            float dx = p.getX() - x, dy = p.getY() - y, d = dx * dx + dy * dy;
            if (d < RANGE_SQ && (bestD < 0f || d < bestD)) bestD = d;
        }
        return bestD;
    }

    // Where a cone at distance d goes to keep the nearest MAX: n for a free slot, the farthest one's slot, or -1 when
    // every kept one is nearer. The distance rides after each cone's FLOATS values.
    private static int slot(int n, float d) {
        if (n < MAX) return n;
        int at = -1;
        float far = d;
        for (int i = 0; i < MAX; i++) {
            float di = best[i * (FLOATS + 1) + FLOATS];
            if (di > far) {
                far = di;
                at = i;
            }
        }
        return at;
    }

    /** The chunk program was looked up again (it may be a new one): send everything again. Render thread. */
    static void invalidate() {
        sentMask = -1;
        sentVersion = -1;
        java.util.Arrays.fill(sentInfo, Float.NaN);
    }

    /**
     * Chunk draw, program bound. ox, oy = the chunk's first square; a pixel's view ray only goes toward +x +y, so a cone
     * can only reach this chunk from that side or from within its reach of it. Render thread.
     */
    static void upload(int locBeams, int locInfo, boolean valid, float ox, float oy, float fog, float rain, float dark) {
        if (locBeams < 0 || locInfo < 0) return;
        float[] snap = published;
        int n = valid ? (int) snap[0] : 0, mask = 0, count = 0;
        for (int i = 0; i < n; i++) {
            int o = 1 + i * FLOATS;
            float x = snap[o], y = snap[o + 1], r = snap[o + 3];
            if (x + r < ox || y + r < oy || x - r > ox + 8f + RAY_REACH || y - r > oy + 8f + RAY_REACH) continue;
            mask |= 1 << i;
            count++;
        }
        if (mask != sentMask || version != sentVersion) {
            sentMask = mask;
            sentVersion = version;
            if (count > 0) {
                buf.clear();
                for (int i = 0; i < n; i++) {
                    if ((mask & (1 << i)) != 0) buf.put(snap, 1 + i * FLOATS, FLOATS);
                }
                buf.flip();
                GL20.glUniform4fv(locBeams, buf);
            }
        }
        float c = count;
        if (sentInfo[0] != c || sentInfo[1] != fog || sentInfo[2] != rain || sentInfo[3] != dark) {
            sentInfo[0] = c;
            sentInfo[1] = fog;
            sentInfo[2] = rain;
            sentInfo[3] = dark;
            GL20.glUniform4f(locInfo, c, fog, rain, dark);
        }
    }

    /** key=value pairs for the Light Beams card, via PZOApi.getChunkEffects(). */
    static String stateText() {
        return "beamShader=" + (wanted ? 1 : 0) + ";beams=" + (int) published[0];
    }
}
