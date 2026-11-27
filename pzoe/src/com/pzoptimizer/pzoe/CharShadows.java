package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.weather.WeatherLook;
import java.util.ArrayList;
import java.util.Arrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.areas.IsoRoom;
import zombie.iso.fboRenderChunk.FBORenderCutaways;

/** Moving floor shadows from the sun, steady lights, flames and torch cones. */
public final class CharShadows {

    static final int MAX = 24;
    // Vehicles on top of the characters, and floats per caster (a vehicle carries its box in the last four)
    static final int CARS = 8;
    static final int STRIDE = 12;
    private static final int CHUNK = CharacterShadowMask.SQUARES_PER_CHUNK;
    private static final int HALF_CHUNKS = CharacterShadowMask.CHUNKS / 2;
    private static final float RANGE = 25f;
    private static final float RANGE_SQ = RANGE * RANGE;
    // Shoulder height in levels, what the shadow's length is worked out from
    private static final float BODY = 0.6f;

    // Render thread sets it from the chunk program's uniforms
    static volatile boolean wanted;
    // Card strength (modApi "charShadows"), 1 = 100 percent
    static volatile float amount = 1f;
    // Vehicle shadows row on the card
    static volatile boolean vehicles = true;

    private static final Snapshot EMPTY = new Snapshot(0, 0, 0, new float[0]);
    private static final View[] views = {new View(), new View(), new View(), new View()};

    // Shadow position is relative to this viewport's atlas origin. Negative darkness denotes local light. Width < 0
    // marks a vehicle: then forward x, y, half length and half width of its body box follow.
    record Snapshot(int originX, int originY, int count, float[] casters) {}

    private static final class View {
        final IsoGameCharacter[] who = new IsoGameCharacter[MAX];
        final float[] distance = new float[MAX];
        final float[] sun = new float[4];
        final float[] work = new float[(MAX + CARS) * STRIDE];
        final zombie.vehicles.BaseVehicle[] cars = new zombie.vehicles.BaseVehicle[CARS];
        final float[] carDistance = new float[CARS];
        int carCount;
        final IsoGridSquare[] lastSquare = new IsoGridSquare[MAX];
        final int[] lamp = new int[MAX];
        // x, y, level, radius, brightness and fire seed
        float[] lamps = new float[64 * 6];
        IsoRoom[] lampRooms = new IsoRoom[64];
        int lampCount;
        IsoPlayer viewer;
        int count;
        long lastPick;
        volatile Snapshot published = EMPTY;

        void clear() {
            count = 0;
            carCount = 0;
            lastPick = 0;
            viewer = null;
            Arrays.fill(who, null);
            Arrays.fill(cars, null);
            Arrays.fill(lastSquare, null);
            Arrays.fill(lampRooms, null);
            lampCount = 0;
            published = EMPTY;
        }
    }

    private static volatile String lastError;
    private static float lastSun;

    // Render thread
    private static final CharacterShadowMask[] masks = new CharacterShadowMask[views.length];

    private CharShadows() {}

    static boolean active() {
        return wanted && amount > 0f;
    }

    /** Main thread, before the viewport's chunk and character draws. */
    public static void frame(int playerIndex) {
        if (playerIndex < 0 || playerIndex >= views.length) return;
        View view = views[playerIndex];
        IsoPlayer player = IsoPlayer.players[playerIndex];
        if (!active() || player == null) {
            if (view.viewer != null) view.clear();
            return;
        }
        try {
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            if (cell == null) {
                view.clear();
                return;
            }
            long now = System.nanoTime();
            if (view.viewer != player || now - view.lastPick >= 250_000_000L) {
                view.viewer = player;
                view.lastPick = now;
                pick(view, cell, playerIndex);
            }
            if (view.count == 0 && view.carCount == 0) {
                view.published = EMPTY;
                return;
            }
            IsoChunkMap map = cell.getChunkMap(playerIndex);
            int originX = Math.floorDiv((int) Math.floor(player.getX()), CHUNK) - HALF_CHUNKS;
            int originY = Math.floorDiv((int) Math.floor(player.getY()), CHUNK) - HALF_CHUNKS;
            PixelLight.sunNow(view.sun);
            float[] sun = view.sun;
            lastSun = sun[3];
            torches.clear();
            zombie.iso.LightingJNI.getTorches(torches);
            float day = WeatherLook.dayLight();
            double t = (now % 4_000_000_000_000L) / 1e9;
            float[] out = view.work;
            int n = 0;
            for (int i = 0; i < view.count; i++) {
                IsoGameCharacter c = view.who[i];
                float opacity = casterOpacity(c, player, playerIndex);
                if (opacity <= 0f) continue;
                IsoGridSquare square = c.getCurrentSquare();
                float x = c.getX(), y = c.getY();
                float rx = x - player.getX(), ry = y - player.getY();
                if (rx * rx + ry * ry > RANGE_SQ) continue;
                int z = square.getZ();
                boolean animal = c instanceof zombie.characters.animals.IsoAnimal;
                float body = animal ? BODY * 0.55f : c.isOnFloor() ? 0.12f : BODY;
                float dx = 0f, dy = 0f, length = 0f, darkness = 0f;
                if (square.isOutside() && sun[3] > 0.05f) {
                    dx = -sun[0];
                    dy = -sun[1];
                    length = Math.min(4.5f, body / Math.max(0.05f, sun[2]));
                    darkness = 0.42f * sun[3];
                }
                if (square != view.lastSquare[i]) {
                    view.lastSquare[i] = square;
                    view.lamp[i] = strongestLamp(view, x, y, z, square.getRoom());
                }
                int lamp = view.lamp[i];
                if (lamp >= 0) {
                    float[] lights = view.lamps;
                    float lx = x - lights[lamp], ly = y - lights[lamp + 1];
                    float dist = (float) Math.sqrt(lx * lx + ly * ly);
                    float strength = lights[lamp + 4] * (1f - dist / lights[lamp + 3]);
                    if (dist >= 0.35f && strength > 0.08f) {
                        float local = Math.min(0.45f, 0.25f + 0.4f * strength) * (square.isOutside() ? 1f - 0.85f * day : 1f);
                        if (local > darkness) {
                            dx = lx / dist;
                            dy = ly / dist;
                            darkness = -local;
                            length = Math.min(3f, 0.25f + dist * 0.45f) * body / BODY;
                            float seed = lights[lamp + 5];
                            if (seed > 0f) {
                                float flicker = 0.6f * (float) Math.sin(t * 9.1 + seed) + 0.4f * (float) Math.sin(t * 23.3 + seed * 2.7);
                                darkness *= 0.85f + 0.15f * flicker;
                                length *= 1f + 0.08f * (float) Math.sin(t * 6.3 + seed * 1.7);
                            }
                        }
                    }
                }
                if (!torches.isEmpty() && torch(c, day) && torchHit[3] > Math.abs(darkness)) {
                    dx = torchHit[0];
                    dy = torchHit[1];
                    length = torchHit[2] * body / BODY;
                    darkness = -torchHit[3];
                }
                int strength = Math.round(Math.abs(darkness) * opacity * 255f);
                if (strength == 0 || length < 0.1f) continue;
                length = clip(map, square, x, y, dx, dy, length);
                if (length < 0.1f) continue;
                int d = n * STRIDE;
                out[d] = x - originX * CHUNK;
                out[d + 1] = y - originY * CHUNK;
                out[d + 2] = z;
                out[d + 3] = Math.round(length * 128f) / 128f;
                out[d + 4] = Math.round(dx * 512f) / 512f;
                out[d + 5] = Math.round(dy * 512f) / 512f;
                out[d + 6] = Math.copySign(strength / 255f, darkness);
                out[d + 7] = animal ? 0.3f : 0.2f;
                n++;
            }
            for (int i = 0; i < view.carCount; i++) {
                if (car(view, view.cars[i], playerIndex, originX, originY, sun, day, out, n * STRIDE)) n++;
            }
            Snapshot previous = view.published;
            if (n == 0) view.published = EMPTY;
            else if (previous.originX != originX || previous.originY != originY || previous.count != n
                    || !Arrays.equals(previous.casters, 0, n * STRIDE, out, 0, n * STRIDE)) {
                view.published = new Snapshot(originX, originY, n, Arrays.copyOf(out, n * STRIDE));
            }
        } catch (Throwable t) {
            view.clear();
            if (lastError == null) com.pzoptimizer.PZOLogger.warn("[CharShadows] " + t);
            lastError = String.valueOf(t);
        }
    }

    private static float clip(IsoChunkMap map, IsoGridSquare start, float x, float y, float dx, float dy, float length) {
        IsoGridSquare square = start;
        int sx = dx < 0f ? -1 : 1, sy = dy < 0f ? -1 : 1;
        float stepX = dx == 0f ? Float.POSITIVE_INFINITY : 1f / Math.abs(dx);
        float stepY = dy == 0f ? Float.POSITIVE_INFINITY : 1f / Math.abs(dy);
        float tx = dx == 0f ? stepX : Math.max(0f, sx < 0 ? x - square.x : square.x + 1 - x) * stepX;
        float ty = dy == 0f ? stepY : Math.max(0f, sy < 0 ? y - square.y : square.y + 1 - y) * stepY;
        while (Math.min(tx, ty) < length + 0.4f) {
            boolean crossX = tx <= ty;
            float distance = crossX ? tx : ty;
            IsoGridSquare next = map.getGridSquare(square.x + (crossX ? sx : 0), square.y + (crossX ? 0 : sy), start.z);
            if (next == null || square.isBlockedTo(next)) return Math.min(length, distance - 0.4f);
            square = next;
            if (crossX) tx += stepX;
            else ty += stepY;
        }
        return length;
    }

    private static float casterOpacity(IsoGameCharacter caster, IsoPlayer viewer, int playerIndex) {
        if (caster == null || caster.isDead() || caster.getVehicle() != null) return 0f;
        boolean ownPlayer = caster == viewer;
        if (!ownPlayer && caster.isInvisible()) return 0f;
        // The zombie's visibility includes stock head checks and close-range detection.
        if (caster.isZombie() && !viewer.isSeeEveryone() && !caster.isVisibleToPlayer[playerIndex]) return 0f;
        float alpha = caster.getAlpha(playerIndex);
        if (alpha <= 0f) return 0f;
        IsoGridSquare square = caster.getCurrentSquare();
        if (square == null || square.chunk == null || square.lighting[playerIndex] == null || (!ownPlayer && !caster.isOnScreen())
                || square.z < CharacterShadowMask.BASE_LEVEL || square.z >= CharacterShadowMask.BASE_LEVEL + CharacterShadowMask.WORLD_LEVELS
                || !FBORenderCutaways.getInstance().shouldRenderBuildingSquare(playerIndex, square)) return 0f;
        return alpha;
    }

    private static void pick(View view, IsoCell cell, int playerIndex) {
        view.count = 0;
        Arrays.fill(view.who, null);
        Arrays.fill(view.lastSquare, null);
        Arrays.fill(view.lampRooms, 0, view.lampCount, null);
        IsoPlayer p = view.viewer;
        float px = p.getX(), py = p.getY();
        int farthest = 0;
        if (casterOpacity(p, p, playerIndex) > 0f) {
            view.who[view.count] = p;
            view.distance[view.count++] = 0f;
        }
        for (IsoMovingObject o : cell.getObjectList()) {
            if (!(o instanceof IsoGameCharacter c) || c == p) continue;
            float dx = c.getX() - px, dy = c.getY() - py, d = dx * dx + dy * dy;
            if (d > RANGE_SQ || (view.count == MAX && d >= view.distance[farthest])) continue;
            if (casterOpacity(c, p, playerIndex) <= 0f) continue;
            int at = view.count == MAX ? farthest : view.count++;
            view.who[at] = c;
            view.distance[at] = d;
            if (view.count == MAX) {
                farthest = 0;
                for (int i = 1; i < MAX; i++) {
                    if (view.distance[i] > view.distance[farthest]) farthest = i;
                }
            }
        }
        pickCars(view, cell, playerIndex);
        // Lights near the player, once for everyone: lamps and street lights (lamppost list) and the ceiling lights of
        // rooms (IsoRoomLight, a rectangle of the room lit from the middle)
        view.lampCount = 0;
        if (view.count == 0 && view.carCount == 0) return;
        java.util.Stack<IsoLightSource> lights = cell.getLamppostPositions();
        for (int i = 0, n = lights.size(); i < n; i++) {
            IsoLightSource l = lights.get(i);
            if (l == null || !l.isActive() || l.getRadius() < 3 || l.startlife > 0) continue;
            addLamp(view, px, py, l.x + 0.5f, l.y + 0.5f, l.z, l.getRadius(), Math.max(l.r, Math.max(l.g, l.b)), 0f, null);
        }
        java.util.ArrayList<zombie.iso.IsoRoomLight> rooms = cell.roomLights;
        for (int i = 0, n = rooms == null ? 0 : rooms.size(); i < n; i++) {
            zombie.iso.IsoRoomLight l = rooms.get(i);
            if (l == null || !l.active) continue;
            float r = Math.max(4f, Math.max(l.width, l.height) * 0.75f + 2f);
            addLamp(view, px, py, l.x + l.width * 0.5f, l.y + l.height * 0.5f, l.z, r,
                Math.max(0.6f, Math.max(l.r, Math.max(l.g, l.b))), 0f, l.room);
        }
        java.util.ArrayList<?> heat = ScreenPass.heatSources(cell);
        for (int i = 0, n = heat == null ? 0 : heat.size(); i < n; i++) {
            if (!(heat.get(i) instanceof zombie.iso.IsoHeatSource h)) continue;
            // Fires and fireplaces register 35, barbecues 25
            float bright = Math.max(0.3f, Math.min(1f, h.getTemperature() / 35f));
            addLamp(view, px, py, h.getX() + 0.5f, h.getY() + 0.5f, h.getZ(), Math.max(6f, h.getRadius() * 1.2f), bright,
                1f + Math.floorMod(h.getX() * 7 + h.getY() * 13, 97), null);
        }
    }

    // The nearest vehicles on screen, CARS at most
    private static void pickCars(View view, IsoCell cell, int playerIndex) {
        view.carCount = 0;
        Arrays.fill(view.cars, null);
        if (!vehicles) return;
        java.util.Set<zombie.vehicles.BaseVehicle> list = cell.getVehicles();
        if (list == null) return;
        IsoPlayer p = view.viewer;
        int farthest = 0;
        for (zombie.vehicles.BaseVehicle v : list) {
            if (v == null || v.getScript() == null) continue;
            IsoGridSquare sq = v.getCurrentSquare();
            if (sq == null || sq.z < CharacterShadowMask.BASE_LEVEL || sq.z >= CharacterShadowMask.BASE_LEVEL + CharacterShadowMask.WORLD_LEVELS
                    || !FBORenderCutaways.getInstance().shouldRenderBuildingSquare(playerIndex, sq)) continue;
            float dx = v.getX() - p.getX(), dy = v.getY() - p.getY(), d = dx * dx + dy * dy;
            if (d > RANGE_SQ || (view.carCount == CARS && d >= view.carDistance[farthest])) continue;
            int at = view.carCount == CARS ? farthest : view.carCount++;
            view.cars[at] = v;
            view.carDistance[at] = d;
            if (view.carCount == CARS) {
                farthest = 0;
                for (int k = 1; k < CARS; k++) if (view.carDistance[k] > view.carDistance[farthest]) farthest = k;
            }
        }
    }

    private static final org.joml.Vector3f carCentre = new org.joml.Vector3f(), carFront = new org.joml.Vector3f(),
        carSide = new org.joml.Vector3f();

    // A vehicle's shadow: its body box swept away from the sun (or the strongest lamp at night), at about roof height
    private static boolean car(View view, zombie.vehicles.BaseVehicle v, int playerIndex, int originX, int originY,
                               float[] sun, float day, float[] out, int d) {
        if (v == null) return false;
        IsoGridSquare square = v.getCurrentSquare();
        if (square == null || square.chunk == null) return false;
        zombie.scripting.objects.VehicleScript script = v.getScript();
        if (script == null) return false;
        org.joml.Vector3f ext = script.getExtents(), com = script.getCenterOfMassOffset();
        if (ext == null || com == null || ext.z <= 0f || ext.x <= 0f) return false;
        v.getWorldPos(com.x, 0f, com.z, carCentre);
        v.getWorldPos(com.x, 0f, com.z + ext.z * 0.5f, carFront);
        v.getWorldPos(com.x + ext.x * 0.5f, 0f, com.z, carSide);
        float fx = carFront.x - carCentre.x, fy = carFront.y - carCentre.y;
        float halfLength = (float) Math.sqrt(fx * fx + fy * fy);
        float sx = carSide.x - carCentre.x, sy = carSide.y - carCentre.y;
        float halfWidth = (float) Math.sqrt(sx * sx + sy * sy);
        if (halfLength < 0.2f || halfWidth < 0.1f || halfLength > 6f || halfWidth > 6f) return false;
        fx /= halfLength;
        fy /= halfLength;
        // Script units to squares from the box itself; a level is about 2.45 squares of height
        float height = Math.max(0.25f, Math.min(1.2f, ext.y * (halfLength / (ext.z * 0.5f)) / 2.44949f * 0.85f));
        float x = carCentre.x, y = carCentre.y;
        int z = square.getZ();
        float dx = 0f, dy = 0f, length = 0f, darkness = 0f;
        if (square.isOutside() && sun[3] > 0.05f) {
            dx = -sun[0];
            dy = -sun[1];
            length = Math.min(5f, height / Math.max(0.05f, sun[2]));
            darkness = 0.5f * sun[3];
        }
        int lamp = strongestLamp(view, x, y, z, null);
        if (lamp >= 0) {
            float[] lights = view.lamps;
            float lx = x - lights[lamp], ly = y - lights[lamp + 1];
            float dist = (float) Math.sqrt(lx * lx + ly * ly);
            float strength = lights[lamp + 4] * (1f - dist / lights[lamp + 3]);
            if (dist >= 1f && strength > 0.08f) {
                float local = Math.min(0.5f, 0.28f + 0.4f * strength) * (square.isOutside() ? 1f - 0.85f * day : 1f);
                if (local > darkness) {
                    dx = lx / dist;
                    dy = ly / dist;
                    darkness = -local;
                    length = Math.min(3.5f, 0.3f + dist * 0.5f) * height / BODY;
                }
            }
        }
        float alpha = v.getAlpha(playerIndex);
        int strength = Math.round(Math.abs(darkness) * Math.max(0f, Math.min(1f, alpha)) * 255f);
        if (strength == 0 || length < 0.1f) return false;
        out[d] = x - originX * CHUNK;
        out[d + 1] = y - originY * CHUNK;
        out[d + 2] = z;
        out[d + 3] = Math.round(length * 128f) / 128f;
        out[d + 4] = Math.round(dx * 512f) / 512f;
        out[d + 5] = Math.round(dy * 512f) / 512f;
        out[d + 6] = Math.copySign(strength / 255f, darkness);
        out[d + 7] = -1f;
        out[d + 8] = Math.round(fx * 512f) / 512f;
        out[d + 9] = Math.round(fy * 512f) / 512f;
        out[d + 10] = Math.round(halfLength * 64f) / 64f;
        out[d + 11] = Math.round(halfWidth * 64f) / 64f;
        return true;
    }

    private static final ArrayList<IsoGameCharacter.TorchInfo> torches = new ArrayList<>();
    // direction x, y, length, darkness of the strongest torch on the last character asked
    private static final float[] torchHit = new float[4];

    // Torches and headlights (the game's cone lights) shining on c: true with the strongest in torchHit. A light held
    // low throws a long shadow that grows with distance. Faint by day outdoors, where the sun drowns it.
    private static boolean torch(IsoGameCharacter c, float day) {
        IsoGridSquare sq = c.getCurrentSquare();
        float dark = sq != null && sq.isOutside() ? Math.max(0f, Math.min(1f, 1f - day * 1.25f)) : Math.max(0.6f, 1f - day * 0.4f);
        if (dark < 0.05f) return false;
        float best = 0f;
        for (int i = 0, n = torches.size(); i < n; i++) {
            IsoGameCharacter.TorchInfo t = torches.get(i);
            if (t == null || !t.cone || t.strength <= 0.05f || Math.abs(t.z - c.getZ()) > 0.8f) continue;
            float vx = c.getX() - t.x, vy = c.getY() - t.y, d = (float) Math.sqrt(vx * vx + vy * vy);
            // Whoever holds it stands on the light
            if (d < 0.6f) continue;
            float reach = Math.max(4f, Math.min(20f, t.dist > 0f ? t.dist * 1.2f : 12f));
            if (d > reach) continue;
            float al = (float) Math.sqrt(t.angleX * t.angleX + t.angleY * t.angleY);
            if (al < 1e-4f) continue;
            float inCone = (vx * t.angleX + vy * t.angleY) / (d * al);
            float edge = Math.min(0.97f, Math.max(0.5f, t.dot));
            if (inCone < edge) continue;
            float cone = Math.min(1f, (inCone - edge) / Math.max(0.02f, 1f - edge) * 2.5f);
            float k = 0.5f * cone * Math.min(1f, t.strength) * (1f - d / reach) * dark;
            if (k <= best) continue;
            best = k;
            torchHit[0] = vx / d;
            torchHit[1] = vy / d;
            torchHit[2] = Math.min(4f, 0.8f + 0.5f * d);
            torchHit[3] = Math.min(0.5f, k);
        }
        return best > 0.03f;
    }

    private static void addLamp(View view, float px, float py, float x, float y, int z, float radius, float bright, float fire, IsoRoom room) {
        float dx = x - px, dy = y - py;
        if (dx * dx + dy * dy > 35f * 35f || bright <= 0.05f) return;
        if (view.lampCount == view.lampRooms.length) {
            view.lamps = Arrays.copyOf(view.lamps, view.lamps.length * 2);
            view.lampRooms = Arrays.copyOf(view.lampRooms, view.lampRooms.length * 2);
        }
        view.lampRooms[view.lampCount] = room;
        int o = view.lampCount++ * 6;
        float[] lamps = view.lamps;
        lamps[o] = x;
        lamps[o + 1] = y;
        lamps[o + 2] = z;
        lamps[o + 3] = radius;
        lamps[o + 4] = bright;
        lamps[o + 5] = fire;
    }

    private static int strongestLamp(View view, float x, float y, int z, IsoRoom room) {
        float[] lamps = view.lamps;
        int best = -1;
        float bestK = 0.08f;
        for (int i = 0; i < view.lampCount; i++) {
            int o = i * 6;
            if (lamps[o + 2] != z || (view.lampRooms[i] != null && view.lampRooms[i] != room)) continue;
            float dx = x - lamps[o], dy = y - lamps[o + 1], distanceSq = dx * dx + dy * dy;
            float r = lamps[o + 3];
            if (distanceSq < 0.35f * 0.35f || distanceSq >= r * r) continue;
            float k = lamps[o + 4] * (1f - (float) Math.sqrt(distanceSq) / r);
            if (k > bestK) {
                bestK = k;
                best = o;
            }
        }
        return best;
    }

    /** Returns the packed floor slots for this chunk and writes atlas offsets for affected chunks. */
    static int bind(int player, int worldX, int worldY, int minLevel, int[] offset) {
        if (!active()) return 0;
        Snapshot snapshot = views[player].published;
        if (snapshot.count == 0) return 0;
        int x = worldX / CHUNK - snapshot.originX, y = worldY / CHUNK - snapshot.originY;
        if (x < 0 || x >= CharacterShadowMask.CHUNKS || y < 0 || y >= CharacterShadowMask.CHUNKS) return 0;
        try {
            CharacterShadowMask mask = masks[player];
            if (mask == null) masks[player] = mask = new CharacterShadowMask();
            mask.update(snapshot);
            int slots = mask.chunkSlots(x, y, minLevel);
            if (slots == 0) return 0;
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + CharacterShadowMask.textureUnit());
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mask.texture());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            offset[0] = x * CHUNK * CharacterShadowMask.TEXELS_PER_SQUARE;
            offset[1] = y * CHUNK * CharacterShadowMask.TEXELS_PER_SQUARE;
            return slots;
        } catch (RuntimeException error) {
            if (lastError == null) com.pzoptimizer.PZOLogger.warn("[CharShadows] " + error);
            lastError = String.valueOf(error);
            return 0;
        }
    }

    static String stateText() {
        return "charShadows=" + views[0].published.count + ";charShadowCars=" + views[0].carCount + ";charShadowWanted=" + (wanted ? 1 : 0) + ";charShadowLights=" + views[0].lampCount
            + ";charShadowSun=" + lastSun + (lastError != null ? ";charShadowError=" + lastError : "");
    }
}
