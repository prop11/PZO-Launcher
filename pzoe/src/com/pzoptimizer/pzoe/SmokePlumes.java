package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.weather.WeatherLook;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;
import zombie.vehicles.BaseVehicle;

/**
 * Smoke columns over fires, for PZO Enhancements' Smoke Plumes (screen.frag declares PZOSmoke):
 *
 *   uniform vec4 PZOSmoke[16];    xy = plume base in the world image's UV, z = plume scale in screen pixels (one
 *                                 square's width times the plume's size), w = profile, seed and intensity
 *   uniform vec4 PZOSmokeInfo;    count, clock in seconds, sideways wind on screen (-1..1 times strength), night 0..1
 *
 * Sources: the fire manager's fires (a burning house is dozens of them, so fires within 3 squares of each other make
 * one plume that grows with how many there are) and lit heat sources outdoors (campfires, barbecues). 60 squares out,
 * further than the haze, since a column of smoke is how you spot a fire across town. Main thread, from
 * ScreenPass.main: gathered 4 times a second, projected every frame with that frame's camera.
 */
final class SmokePlumes {
    record Projection(float[] data, float[] depth, int count, float tileWidth, zombie.core.textures.TextureFBO scene, RoomProjection rooms) {}
    record RoomProjection(float[] data, int count, Bounds bounds) {}
    record Bounds(float minX, float minY, float maxX, float maxY) {}

    static final int MAX = 16, MAX_ROOM_RECTS = 12;
    private static final float RANGE_SQ = 60f * 60f;

    static volatile long askedNanos;
    static volatile float windScreen, night;
    private static final int CAMPFIRE = 0, STRUCTURE = 1, WRECK = 2, SMOULDER = 3;
    private static final int CEILING = 4, VENT_RIGHT = 5, VENT_LEFT = 6, INDOOR_FLAME = 7;
    // x, y, z, size, strength, distance squared, members
    private static final int INDOOR_FURNITURE = 8, OUTDOOR_FURNITURE = 9;
    private static final java.util.WeakHashMap<IsoFire, Boolean> furnitureFires = new java.util.WeakHashMap<>();
    private static final int STRIDE = 7;
    private static final float[] src = new float[MAX * STRIDE];
    private static final int[] profiles = new int[MAX];
    private static final int[] seeds = new int[MAX];
    private static final IsoRoom[] sourceRooms = new IsoRoom[MAX];
    private static final RoomSmoke roomSmoke = new RoomSmoke();
    private static int count;
    private static long lastGather;
    // Gun smoke indoors (Smoke card option): shots wait here until the next gather, which runs on the main thread
    static volatile boolean gunSmoke = true;
    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> shots = new java.util.concurrent.ConcurrentLinkedQueue<>();

    // Generator fumes (Smoke card option): running generators indoors, from IsoGenerator.update (main thread)
    static volatile boolean fumes = true;
    private static final java.util.WeakHashMap<zombie.iso.objects.IsoGenerator, Boolean> generators = new java.util.WeakHashMap<>();

    static void generator(zombie.iso.objects.IsoGenerator g) {
        if (zombie.network.GameServer.server) return;
        boolean on = fumes && System.nanoTime() - askedNanos < 1_000_000_000L;
        IsoGridSquare square = g.getSquare();
        boolean indoor = on && g.isActivated() && square != null && square.getRoom() != null && !square.isOutside();
        synchronized (generators) {
            if (indoor) {
                if (generators.size() < 32) generators.put(g, Boolean.TRUE);
            } else if (!generators.isEmpty()) {
                generators.remove(g);
            }
        }
    }

    /** Any thread: a shot fired (FlashLights' shot sound hook, local and remote shooters). */
    static void shot(zombie.characters.IsoGameCharacter shooter) {
        if (!gunSmoke || shooter == null || zombie.network.GameServer.server
            || System.nanoTime() - askedNanos > 1_000_000_000L || shots.size() > 64) return;
        IsoGridSquare square = shooter.getCurrentSquare();
        if (square == null || square.getRoom() == null || square.isOutside()) return;
        var weapon = shooter.getAttackingWeapon();
        float k = weapon == null ? 1f : weapon.getProjectileCount() > 1 ? 1.6f : weapon.isTwoHandWeapon() ? 1.3f : 1f;
        shots.add(new Object[] {square, k});
    }

    private SmokePlumes() {}

    private static volatile int shotsTaken;

    static String roomText() {
        StringBuilder b = new StringBuilder("gunShots=").append(shotsTaken).append(";smokeRooms=");
        try {
            var rooms = roomSmoke.rooms();
            for (int i = 0; i < Math.min(4, rooms.size()); i++) {
                var r = rooms.get(i);
                b.append(String.format(java.util.Locale.ROOT, "%s%.3f/%.2f", i == 0 ? "" : ",", r.density, r.gun));
            }
        } catch (RuntimeException ignored) {
        }
        return b.toString();
    }

    /** Main thread, once a frame from ScreenPass.main while the shader asks. */
    static void frame() {
        long now = System.currentTimeMillis();
        if (now - lastGather < 250) return;
        float dt = Math.max(0.25f, Math.min(2f, (now - lastGather) * 0.001f));
        lastGather = now;
        try {
            float a = WeatherLook.windAngleRadians();
            float strength = 0.25f + 0.75f * WeatherLook.wind();
            float wx = (float) Math.cos(a) * strength, wy = (float) Math.sin(a) * strength;
            gather(wx, wy, dt);
            // World x - y runs across the screen
            windScreen = Math.max(-1f, Math.min(1f, (wx - wy) * 0.7071f));
            night = zombie.iso.weather.ClimateManager.getInstance().getNightStrength();
        } catch (Throwable t) {
            count = 0;
        }
    }

    private static void gather(float windX, float windY, float dt) {
        java.util.Arrays.fill(sourceRooms, 0, count, null);
        count = 0;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        roomSmoke.begin(cell, System.nanoTime());
        if (cell == null) {
            shots.clear();
            return;
        }
        for (Object[] s; (s = shots.poll()) != null; ) {
            roomSmoke.addShot((IsoGridSquare) s[0], (Float) s[1]);
            shotsTaken++;
        }
        synchronized (generators) {
            for (var g : generators.keySet()) {
                IsoGridSquare square = g.getSquare();
                if (fumes && square != null && g.isActivated() && square.getRoom() != null) roomSmoke.addFume(square, 0.35f);
            }
        }
        IsoPlayer[] players = IsoPlayer.players;
        for (int i = 0, n = IsoFireManager.FireStack.size(); i < n; i++) {
            IsoFire f = IsoFireManager.FireStack.get(i);
            if (f == null || f.getSquare() == null) continue;
            IsoGridSquare square = f.getSquare();
            float x = square.getX() + 0.5f, y = square.getY() + 0.5f;
            float distance = distanceSq(x, y, players);
            if (distance < 0f) continue;
            boolean smoulder = f.smoke || (!f.isPermanent() && f.lifeStage >= 5);
            float strength = RoomSmoke.intensity(f);
            IsoRoom room = square.getRoom();
            boolean furniture = !smoulder && !f.isPermanent() && furnitureFires.computeIfAbsent(f, key -> hasFurniture(square));
            if (room != null && !square.isOutside()) {
                roomSmoke.addFire(square, strength);
                if (!smoulder) add(x, y, square.getZ(), strength, distance, furniture ? INDOOR_FURNITURE : INDOOR_FLAME, room);
            } else {
                BaseVehicle vehicle = smoulder || f.isPermanent() ? null : square.getVehicleContainer();
                int profile = smoulder ? SMOULDER : vehicle != null ? WRECK : f.isPermanent() ? CAMPFIRE : furniture ? OUTDOOR_FURNITURE : STRUCTURE;
                add(x, y, square.getZ(), strength, distance, profile, null);
            }
        }
        java.util.ArrayList<?> heat = ScreenPass.heatSources(cell);
        if (heat != null) {
            for (int i = 0, n = heat.size(); i < n; i++) {
                if (!(heat.get(i) instanceof zombie.iso.IsoHeatSource h)) continue;
                float x = h.getX() + 0.5f, y = h.getY() + 0.5f;
                float distance = distanceSq(x, y, players);
                if (distance < 0f) continue;
                IsoGridSquare sq = cell.getGridSquare(h.getX(), h.getY(), h.getZ());
                // Indoors it would come out of the ceiling: fireplaces and stoves have no chimney point to use
                if (sq == null || !sq.isOutside()) continue;
                if (sq.getProperties().has(zombie.iso.SpriteDetails.IsoFlagType.burning)) continue;
                add(x, y, h.getZ(), Math.min(1f, h.getTemperature() / 35f) * 0.6f, distance, CAMPFIRE, null);
            }
        }
        engines(cell, players);
        roomSmoke.advance(dt, windX, windY);
        var rooms = roomSmoke.rooms();
        for (int i = 0; i < rooms.size(); i++) {
            RoomSmoke.Room state = rooms.get(i);
            IsoGridSquare anchor = state.anchor;
            float x = anchor.getX() + 0.5f, y = anchor.getY() + 0.5f;
            float distance = distanceSq(x, y, players);
            // Gun smoke and exhaust leave quietly; a sooty plume out of the window is only for fire smoke
            if (distance < 0f || state.density < 0.022f || state.gun > 0.6f) continue;
            var portals = roomSmoke.portals(state);
            for (int j = 0; j < portals.size(); j++) {
                var portal = portals.get(j);
                var destination = portal.receivingRoom();
                float flow = (state.density - roomSmoke.density(destination)) * portal.weight(windX, windY);
                if (flow < 0.07f || (destination == null && !portal.to().isOutside())) continue;
                float ventX = (portal.from().getX() + portal.to().getX() + 1) * 0.5f;
                float ventY = (portal.from().getY() + portal.to().getY() + 1) * 0.5f;
                float ventZ = (portal.from().getZ() == portal.to().getZ()) ? portal.from().getZ() + portal.height()
                    : Math.max(portal.from().getZ(), portal.to().getZ());
                boolean right = portal.to().getX() - portal.from().getX() > portal.to().getY() - portal.from().getY();
                add(ventX, ventY, ventZ, flow, distanceSq(ventX, ventY, players),
                    right ? VENT_RIGHT : VENT_LEFT, destination);
            }
        }
        // Size from how many fires make it up: one campfire 0.6, a house well alight about 2.5
        for (int i = 0; i < count; i++) {
            int o = i * STRIDE;
            float base = switch (profiles[i]) {
                case WRECK -> 1.3f;
                case STRUCTURE -> 0.85f;
                case INDOOR_FURNITURE, OUTDOOR_FURNITURE -> 0.74f;
                case CEILING -> 1.1f;
                case VENT_LEFT, VENT_RIGHT -> 0.65f;
                default -> 0.6f;
            };
            src[o + 3] = Math.min(2.8f, base + 0.35f * (float) Math.sqrt(src[o + 6] - 1f));
        }
    }

    // Failing engines (Smoke card option): a running engine below 40 condition smokes from under the hood, thicker
    // the worse it is; it thins out with speed, where the smoke blows away under the car
    static volatile boolean engineSmoke = true;
    private static final org.joml.Vector3f hood = new org.joml.Vector3f();

    private static void engines(IsoCell cell, IsoPlayer[] players) {
        if (!engineSmoke) return;
        java.util.Set<BaseVehicle> vehicles = cell.getVehicles();
        if (vehicles == null) return;
        for (BaseVehicle v : vehicles) {
            if (v == null || !v.isEngineRunning()) continue;
            zombie.vehicles.VehiclePart engine = v.getPartById("Engine");
            if (engine == null) continue;
            int condition = engine.getCondition();
            if (condition >= 40) continue;
            float kmh = Math.abs(v.getCurrentSpeedKmHour());
            float strength = (40f - condition) / 40f * 0.75f * Math.max(0f, 1f - kmh / 30f);
            if (strength < 0.05f) continue;
            zombie.scripting.objects.VehicleScript script = v.getScript();
            if (script == null) continue;
            org.joml.Vector3f ext = script.getExtents(), com = script.getCenterOfMassOffset();
            v.getWorldPos(com.x, 0f, com.z + ext.z * 0.32f, hood);
            float distance = distanceSq(hood.x, hood.y, players);
            if (distance < 0f) continue;
            IsoGridSquare sq = v.getCurrentSquare();
            if (sq == null || !sq.isOutside()) continue;
            add(hood.x, hood.y, v.getZ() + 0.15f, strength, distance, SMOULDER, null);
        }
    }

    private static boolean hasFurniture(IsoGridSquare square) {
        var objects = com.pzoptimizer.GameCompat.objects(square);
        for (int i = 0; i < objects.size(); i++) {
            var object = objects.get(i);
            String tileset = object == null || object.sprite == null ? null : object.sprite.tilesetName;
            if (tileset != null && (tileset.startsWith("furniture_") || tileset.startsWith("appliances_"))) return true;
        }
        return false;
    }

    private static float distanceSq(float x, float y, IsoPlayer[] players) {
        float d = -1f;
        for (IsoPlayer p : players) {
            if (p == null) continue;
            float dx = p.getX() - x, dy = p.getY() - y, dd = dx * dx + dy * dy;
            if (dd < RANGE_SQ && (d < 0 || dd < d)) d = dd;
        }
        return d;
    }

    private static void add(float x, float y, float z, float strength, float d, int profile, IsoRoom room) {
        if (strength <= 0f || d < 0f) return;
        strength = Math.min(1f, strength);
        for (int i = 0; i < count; i++) {
            if (profiles[i] != profile || sourceRooms[i] != room) continue;
            int o = i * STRIDE;
            float dx = src[o] - x, dy = src[o + 1] - y;
            float radiusSq = profile == STRUCTURE || profile == SMOULDER ? 9f : 0.01f;
            if (dx * dx + dy * dy < radiusSq && src[o + 2] == z) {
                // The plume stands over the middle of its fires
                float m = src[o + 6];
                src[o] = (src[o] * m + x) / (m + 1f);
                src[o + 1] = (src[o + 1] * m + y) / (m + 1f);
                src[o + 4] = Math.max(src[o + 4], strength);
                src[o + 5] = Math.min(src[o + 5], d);
                if (profile != CAMPFIRE) src[o + 6] = m + 1f;
                return;
            }
        }
        int at;
        if (count < MAX) {
            at = count++;
        } else {
            at = -1;
            float far = d;
            for (int i = 0; i < MAX; i++) {
                if (src[i * STRIDE + 5] > far) {
                    far = src[i * STRIDE + 5];
                    at = i;
                }
            }
            if (at < 0) return;
        }
        int o = at * STRIDE;
        src[o] = x;
        src[o + 1] = y;
        src[o + 2] = z;
        src[o + 3] = 0.6f;
        src[o + 4] = strength;
        src[o + 5] = d;
        src[o + 6] = 1f;
        profiles[at] = profile;
        sourceRooms[at] = room;
        seeds[at] = seed((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    static int seed(int x, int y, int z) {
        int hash = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
        return (hash ^ (hash >>> 16)) & 255;
    }

    /** This frame's plumes for one player's camera, as the shader wants them, or null with none. */
    static Projection project(int playerIndex, float zoom, int texW, int texH) {
        if (count == 0 && roomSmoke.rooms().isEmpty()) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        IsoPlayer player = IsoPlayer.players[playerIndex];
        IsoGridSquare playerSquare = player == null ? null : player.getCurrentSquare();
        IsoRoom visibleRoom = playerSquare == null ? null : playerSquare.getRoom();
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sw = IsoCamera.getScreenWidth(playerIndex), sh = IsoCamera.getScreenHeight(playerIndex);
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        float[] out = null, depth = null;
        var scene = player != null && IsoPlayer.numPlayers == 1 && zombie.core.PerformanceSettings.fboRenderChunk
            ? zombie.core.Core.getInstance().getOffscreenBuffer(playerIndex) : null;
        if (scene != null && (scene.getTexture() == null || scene.getTexture().getWidthHW() != texW
            || scene.getTexture().getHeightHW() != texH)) scene = null;
        int visible = 0;
        for (int i = 0; i < count; i++) {
            if (sourceRooms[i] != null && sourceRooms[i] != visibleRoom) continue;
            if (profiles[i] == INDOOR_FLAME || profiles[i] == INDOOR_FURNITURE) {
                IsoGridSquare source = playerSquare.getCell().getGridSquare((int) Math.floor(src[i * STRIDE]),
                    (int) Math.floor(src[i * STRIDE + 1]), (int) src[i * STRIDE + 2]);
                if (source == null || !source.isCouldSee(playerIndex)) continue;
            }
            int o = i * STRIDE;
            float px = cam.XToScreenExact(src[o], src[o + 1], src[o + 2], 0) / zoom;
            float py = cam.YToScreenExact(src[o], src[o + 1], src[o + 2], 0) / zoom;
            float scale = tile * src[o + 3];
            if (px < -10f * scale || px > sw + 10f * scale || py < -0.35f * scale || py > sh + 7f * scale) continue;
            if (out == null) {
                out = new float[count * 4];
                if (scene != null) depth = new float[count];
            }
            if (depth != null) {
                depth[visible] = zombie.iso.IsoDepthHelper.getSquareDepthData((int)Math.floor(player.getX()),
                    (int)Math.floor(player.getY()), src[o], src[o + 1], src[o + 2]).depthStart;
            }
            int at = visible++ * 4;
            out[at] = (left + px) / texW;
            out[at + 1] = (top + sh - py) / texH;
            out[at + 2] = scale;
            // Integer bits store the material and seed. The fractional part stores intensity.
            out[at + 3] = profiles[i] * 256 + seeds[i] + 0.5f * src[o + 4];
        }
        RoomProjection rooms = projectRooms(playerIndex, playerSquare, cam, zoom, texW, texH, left, top + sh, tile, scene != null);
        return out == null && rooms == null ? null : new Projection(out, depth, visible, tile, scene, rooms);
    }

    private static RoomProjection projectRooms(int playerIndex, IsoGridSquare viewer, PlayerCamera cam, float zoom,
                                               int w, int h, float left, float bottom, float tile, boolean hasDepth) {
        if (viewer == null || roomSmoke.rooms().isEmpty()) return null;
        float[] data = null;
        int count = 0;
        float minX = w, minY = h, maxX = 0f, maxY = 0f;
        IsoRoom current = viewer.getRoom();
        int cx = viewer.getX(), cy = viewer.getY();
        // Give the player's room priority when several connected rooms are full.
        for (int pass = 0; pass < 2 && count < MAX_ROOM_RECTS; pass++) {
            for (RoomSmoke.Room state : roomSmoke.rooms()) {
                if ((state.room == current) != (pass == 0) || state.density < 0.016f) continue;
                if (state.room != current && (!hasDepth || !state.anchor.isCanSee(playerIndex)
                    || state.anchor.getZ() != viewer.getZ())) continue;
                var def = state.room.getRoomDef();
                var rects = def == null ? state.room.rects : def.rects;
                for (var rect : rects) {
                    if (count == MAX_ROOM_RECTS) break;
                    if (rect.w <= 0 || rect.h <= 0) continue;
                    int z = state.anchor.getZ();
                    float x = left + cam.XToScreenExact(rect.x, rect.y, z, 0) / zoom;
                    float y = bottom - cam.YToScreenExact(rect.x, rect.y, z, 0) / zoom;
                    float x0 = x - rect.h * tile * 0.5f, x1 = x + rect.w * tile * 0.5f;
                    float y0 = y - (rect.w + rect.h) * tile * 0.25f, y1 = y + tile * 1.5f;
                    if (x1 <= 0f || x0 >= w || y1 <= 0f || y0 >= h) continue;
                    if (data == null) data = new float[MAX_ROOM_RECTS * 8];
                    int o = count++ * 8;
                    data[o] = x;
                    data[o + 1] = y;
                    data[o + 2] = rect.w;
                    data[o + 3] = rect.h;
                    data[o + 4] = state.density;
                    data[o + 5] = zombie.iso.IsoDepthHelper.getSquareDepthData(cx, cy, rect.x, rect.y, z).depthStart;
                    data[o + 6] = seed(rect.x, rect.y, z) * 0.073f;
                    data[o + 7] = state.gun;
                    minX = Math.min(minX, x0);
                    minY = Math.min(minY, y0);
                    maxX = Math.max(maxX, x1);
                    maxY = Math.max(maxY, y1);
                }
            }
        }
        return data == null ? null : new RoomProjection(data, count, new Bounds(Math.max(0f, minX / w),
            Math.max(0f, minY / h), Math.min(1f, maxX / w), Math.min(1f, maxY / h)));
    }

    static Bounds bounds(float[] projected, int n, int w, int h, boolean local) {
        float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
        for (int i = 0; i < n * 4; i += 4) {
            int material = (int) projected[i + 3] / 256;
            float rise = switch (material) {
                case CEILING -> 0.36f;
                case INDOOR_FLAME, INDOOR_FURNITURE -> 2.4f;
                case VENT_LEFT, VENT_RIGHT -> 3f;
                default -> 6f;
            };
            float reach = switch (material) {
                case CEILING -> 1.15f;
                case INDOOR_FLAME, INDOOR_FURNITURE -> 1.4f;
                case VENT_LEFT, VENT_RIGHT -> 2.4f + 1.4f * Math.abs(windScreen);
                default -> 3f + 3.75f * Math.abs(windScreen);
            };
            if (local) {
                reach = Math.min(reach, 1.55f);
                rise = Math.min(rise, 1.8f);
            }
            float x = projected[i], y = projected[i + 1], scale = projected[i + 2];
            minX = Math.min(minX, x - (reach * scale + 1f) / w);
            maxX = Math.max(maxX, x + (reach * scale + 1f) / w);
            minY = Math.min(minY, y - (0.40f * scale + 1f) / h);
            maxY = Math.max(maxY, y + (rise * scale + 1f) / h);
        }
        return new Bounds(Math.max(0f, minX), Math.max(0f, minY), Math.min(1f, maxX), Math.min(1f, maxY));
    }

    static String stateText() {
        return "smokePlumes=" + count;
    }
}
