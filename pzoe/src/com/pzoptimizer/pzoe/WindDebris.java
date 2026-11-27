package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.pzoe.weather.WeatherLook;
import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.SplittableRandom;
import zombie.GameTime;
import zombie.IndieGL;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.weather.ClimateManager;

final class WindDebris {
    private static final int LEAF = 0, NARROW_LEAF = 1, NEEDLES = 2, FLUFF = 3, GRASS = 4, TWIG = 5, PAPER = 6, BARK = 7;
    private static final int RECEIPT = 8, NEWSPAPER = 9, WRAPPER = 10, CARDBOARD = 11;
    private static final int ATLAS_COLUMNS = 16, ROAD = 1, WALKWAY = 2, HARDSTANDING = 3;
    private static final IsoDirections[] CARDINAL = {IsoDirections.N, IsoDirections.S, IsoDirections.W, IsoDirections.E};
    private static final int MAX_PARTICLES = 128, SCAN_RADIUS = 13, SCAN_SIDE = SCAN_RADIUS * 2 + 1;
    private static final int MAX_SOURCES = 160;
    private static final int[] SCAN_ORDER = scanOrder();
    private static final long SCAN_INTERVAL = 125_000_000L;
    private static final SplittableRandom random = new SplittableRandom();
    private static final Observer[] observers = {new Observer(), new Observer(), new Observer(), new Observer()};
    private static final ArrayList<Particle> active = new ArrayList<>(MAX_PARTICLES);
    private static final ArrayList<Particle> pool = new ArrayList<>(MAX_PARTICLES);
    private static final float[] SIZE = {7.2f, 6.2f, 5.4f, 5.2f, 6.8f, 8.5f, 8.8f, 6.7f, 6.4f, 13.0f, 7.4f, 11.1f};
    private static final int[] COLOR = {0xaf773c, 0xb48636, 0x827346, 0xe0d9bd, 0xa08c4e, 0x82674a,
        0xc8c0a8, 0x927759, 0xe6dfc8, 0xc9c6b9, 0xa9a3a5, 0xb28d62};
    private static java.lang.ref.WeakReference<IsoCell> world = new java.lang.ref.WeakReference<>(null);
    private static Texture atlas;
    private static volatile boolean failed;
    static volatile boolean enabled, ownsLeaves;
    private static long lastTick, nextScan;
    private static int lastFrame = Integer.MIN_VALUE;
    private static float clock, wind, wx, wy, gust;
    private static boolean storm, autumn;

    private record Source(int x, int y, int z, boolean tree, boolean evergreen, boolean paving, boolean frontage) {}

    private static final class Observer {
        int x, y, z, cursor, candidates;
        float credit;
        boolean placed;
        ArrayList<Source> sources = new ArrayList<>(MAX_SOURCES);
        ArrayList<Source> scan = new ArrayList<>(MAX_SOURCES);

        void clear() {
            placed = false;
            cursor = candidates = 0;
            credit = 0f;
            sources.clear();
            scan.clear();
        }
    }

    private static final class Particle {
        float x, y, height, age, life, phase, spin, scale, drift, shelter;
        int z, kind, tint;
    }

    private WindDebris() {}

    static boolean active() {
        return enabled && !failed;
    }

    static boolean replacesLegacyLeaves() {
        return ownsLeaves && !failed && zombie.core.PerformanceSettings.fboRenderChunk;
    }

    static void render(int player, int z) {
        if (!active()) {
            if (world.get() != null || !active.isEmpty()) reset(null);
            return;
        }
        if (!zombie.core.PerformanceSettings.fboRenderChunk || player != IsoCamera.frameState.playerIndex) return;
        try {
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            if (cell == null) return;
            if (cell != world.get()) reset(cell);
            var frame = IsoCamera.frameState;
            if (frame.frameCount != lastFrame) {
                lastFrame = frame.frameCount;
                update(cell, System.nanoTime());
            }
            if (!active.isEmpty()) draw(cell, player, z, frame);
        } catch (Exception | LinkageError error) {
            reset(null);
            failed = true;
            PZOLogger.warn("[PZOE] Windblown debris unavailable: " + error);
        }
    }

    private static void reset(IsoCell cell) {
        world = new java.lang.ref.WeakReference<>(cell);
        pool.addAll(active);
        active.clear();
        for (var observer : observers) observer.clear();
        lastTick = nextScan = 0;
        lastFrame = Integer.MIN_VALUE;
        clock = 0f;
    }

    private static void update(IsoCell cell, long now) {
        float dt = lastTick == 0 ? 0f : Math.min(0.060f, Math.max(0f, (now - lastTick) * 1e-9f));
        lastTick = now;
        if (GameTime.isGamePaused()) return;
        clock += dt;
        wind = WeatherLook.wind();
        double angle = WeatherLook.windAngleRadians();
        wx = (float) Math.cos(angle);
        wy = (float) Math.sin(angle);
        var period = ClimateManager.getInstance().getWeatherPeriod();
        storm = period != null && (period.isThunderStorm() || period.isTropicalStorm() || period.isBlizzard());
        int month = Math.floorMod(GameTime.getInstance().getMonth(), 12);
        autumn = month >= 8 && month <= 10;
        float pulse = (float) (0.5 * Math.sin(clock * 1.28) + 0.5 * Math.sin(clock * 2.4 + 0.7));
        gust = Math.max(0f, pulse - 0.20f) * (storm ? 1f : wind * wind);
        advance(cell, dt);
        if (wind <= 0.07f && !storm) return;

        boolean scan = now >= nextScan;
        if (scan) nextScan = now + SCAN_INTERVAL;
        for (int i = 0; i < observers.length; i++) {
            var player = IsoPlayer.players[i];
            var square = player == null ? null : player.getCurrentSquare();
            if (square == null) {
                if (observers[i].placed) observers[i].clear();
                continue;
            }
            var observer = observers[i];
            if (!observer.placed || square.z != observer.z || Math.abs(square.x - observer.x) > 4
                || Math.abs(square.y - observer.y) > 4) {
                observer.clear();
                observer.placed = true;
                observer.x = square.x;
                observer.y = square.y;
                observer.z = square.z;
            }
            if (scan) scan(cell, observer);
            float rate = 0.75f + wind * (autumn ? 8f : 5f) + gust * (storm ? 17f : 6f);
            observer.credit = Math.min(3f, observer.credit + dt * rate);
            while (observer.credit >= 1f && active.size() < MAX_PARTICLES) {
                observer.credit -= 1f;
                spawn(cell, player, observer);
            }
        }
    }

    private static int[] scanOrder() {
        int[] order = new int[SCAN_SIDE * SCAN_SIDE];
        int index = 0;
        for (int ring = 0; ring <= SCAN_RADIUS; ring++) {
            for (int y = -ring; y <= ring; y++) {
                for (int x = -ring; x <= ring; x++) {
                    if (Math.max(Math.abs(x), Math.abs(y)) == ring) {
                        order[index++] = (y + SCAN_RADIUS) * SCAN_SIDE + x + SCAN_RADIUS;
                    }
                }
            }
        }
        return order;
    }

    private static void scan(IsoCell cell, Observer observer) {
        for (int i = 0; i < 108; i++) {
            int index = SCAN_ORDER[observer.cursor++];
            int x = observer.x + index % SCAN_SIDE - SCAN_RADIUS, y = observer.y + index / SCAN_SIDE - SCAN_RADIUS;
            var square = cell.getGridSquare(x, y, observer.z);
            if (square != null && square.isOutside() && !square.haveRoof && !square.has(zombie.iso.SpriteDetails.IsoFlagType.water)) {
                var tree = square.getTree();
                var floor = square.getFloor();
                int surface = pavement(square);
                boolean frontage = floor != null && (hasBin(square) || surface != 0 && isStorefront(square));
                boolean ground = (hash(x, y) & 7) == 0 && floor != null;
                if (tree != null || ground || frontage) {
                    int choice = observer.candidates++;
                    if (choice >= MAX_SOURCES) choice = random.nextInt(observer.candidates);
                    if (choice < MAX_SOURCES) {
                        var source = new Source(x, y, square.z, tree != null,
                            tree != null && AutumnTrees.isEvergreen(tree.getSprite()), surface != 0, frontage);
                        if (observer.scan.size() < MAX_SOURCES) observer.scan.add(source);
                        else observer.scan.set(choice, source);
                    }
                }
            }
            if (observer.cursor == SCAN_SIDE * SCAN_SIDE) {
                var previous = observer.sources;
                observer.sources = observer.scan;
                observer.scan = previous;
                observer.scan.clear();
                observer.cursor = observer.candidates = 0;
                break;
            }
        }
    }

    private static void spawn(IsoCell cell, IsoPlayer player, Observer observer) {
        var sources = observer.sources.isEmpty() ? observer.scan : observer.sources;
        if (sources.isEmpty()) return;
        Source best = null;
        float bestScore = 0f;
        boolean strong = wind >= 0.43f || storm;
        int start = random.nextInt(sources.size());
        int samples = Math.min(sources.size(), 32);
        for (int i = 0; i < samples; i++) {
            Source source = sources.get((start + i) % sources.size());
            if (!source.tree && !source.frontage && !strong) continue;
            float dx = source.x + 0.5f - player.getX(), dy = source.y + 0.5f - player.getY();
            float along = dx * wx + dy * wy;
            float cross = Math.abs(dx * wy - dy * wx);
            if (along < -5f - wind * 8f || along > 3.5f || cross > 6.8f) continue;
            float score = (1f - cross / 8f) * (1f - Math.abs(along + 2f) / 18f);
            if (source.tree) score *= strong ? 1.15f : 1.3f;
            if (source.frontage) score *= strong ? 1.35f : 0.90f;
            score *= 0.45f + 0.55f * random.nextFloat();
            if (score > bestScore) {
                best = source;
                bestScore = score;
            }
        }
        if (best == null || !strong && !best.tree && random.nextFloat() > wind * 0.85f) return;
        var square = cell.getGridSquare(best.x, best.y, best.z);
        if (square == null || !square.isOutside() || square.haveRoof || (best.tree && square.getTree() == null)) return;
        if (PixelLight.windShelter && sheltered(cell, square) && random.nextFloat() < 0.60f) return;
        var p = pool.isEmpty() ? new Particle() : pool.remove(pool.size() - 1);
        p.kind = chooseKind(best, strong);
        p.tint = p.kind <= NARROW_LEAF && !autumn ? (random.nextBoolean() ? 0x89934b : 0xa49752) : COLOR[p.kind];
        p.x = best.x + 0.22f + random.nextFloat() * 0.56f;
        p.y = best.y + 0.22f + random.nextFloat() * 0.56f;
        p.z = best.z;
        p.height = best.tree ? 0.36f + random.nextFloat() * 0.56f : 0.08f + random.nextFloat() * 0.55f;
        if (p.kind == FLUFF) p.height = Math.max(p.height, 0.40f);
        p.age = 0f;
        p.life = 3.0f + random.nextFloat() * 3.4f;
        p.phase = random.nextFloat() * 6.283f;
        p.spin = (p.kind == WRAPPER ? 6.2f : p.kind == CARDBOARD ? 1.7f : 2.8f) + random.nextFloat() * 6f;
        if (p.kind == WRAPPER) p.tint = random.nextBoolean() ? 0xa06a59 : 0xa1a8ad;
        p.scale = 0.76f + random.nextFloat() * 0.50f;
        p.drift = 0.78f + random.nextFloat() * 0.46f;
        p.shelter = 1f;
        active.add(p);
    }

    private static int chooseKind(Source source, boolean strong) {
        float roll = random.nextFloat();
        if (source.frontage && (!source.tree || roll < 0.48f)) {
            if (!strong) return random.nextFloat() < 0.72f ? RECEIPT : WRAPPER;
            return urbanKind(random.nextFloat());
        }
        if (source.tree) {
            if (strong && roll < 0.14f + gust * 0.12f) return random.nextBoolean() ? TWIG : BARK;
            if (source.evergreen) return NEEDLES;
            if (!autumn && roll > 0.84f) return FLUFF;
            return random.nextBoolean() ? LEAF : NARROW_LEAF;
        }
        if (source.paving && strong) {
            if (roll < 0.6f) return storm || wind > 0.66f ? urbanKind(roll / 0.6f) : PAPER;
            roll = (roll - 0.6f) / 0.4f;
        }
        if (roll < 0.34f) return LEAF;
        return roll < 0.78f ? GRASS : TWIG;
    }

    private static int urbanKind(float roll) {
        if (roll < 0.28f) return RECEIPT;
        if (roll < 0.56f) return NEWSPAPER;
        return roll < 0.84f ? WRAPPER : CARDBOARD;
    }

    private static boolean hasBin(IsoGridSquare square) {
        var objects = com.pzoptimizer.GameCompat.objects(square);
        for (int i = 0; i < objects.size(); i++) {
            var object = objects.get(i);
            if (object == null || object.getSprite() == null) continue;
            String tileset = object.getSprite().tilesetName;
            if (tileset != null && tileset.startsWith("trashcontainers_")) return true;
            var container = object.getContainer();
            if (container != null && binType(container.getType())) return true;
        }
        return false;
    }

    private static boolean binType(String type) {
        return "bin".equals(type) || "dumpster".equals(type) || "garbage".equals(type) || "trashcan".equals(type);
    }

    private static boolean isStorefront(IsoGridSquare square) {
        for (var edge : CARDINAL) {
            var next = square.getAdjacentSquare(edge);
            var room = next == null ? null : next.getRoom();
            if (room != null && commercialRoom(room.getName()) && (square.isDoorTo(next) || square.isWindowTo(next))) return true;
        }
        return false;
    }

    private static boolean commercialRoom(String room) {
        if (room == null) return false;
        String name = room.toLowerCase(java.util.Locale.ROOT);
        return name.contains("shop") || name.contains("store") || name.contains("grocery") || name.contains("market")
            || name.contains("restaurant") || name.contains("pharmacy") || name.contains("bakery") || name.contains("cafe")
            || name.contains("pizz") || name.contains("diner") || name.contains("retail") || name.equals("bar");
    }

    static int pavement(IsoGridSquare square) {
        var floor = square.getFloor();
        var sprite = floor == null ? null : floor.getSprite();
        return pavement(sprite == null ? null : sprite.getName());
    }

    private static int pavement(String name) {
        if (name == null) return 0;
        if (name.contains("sidewalk") || name.contains("curb") || name.contains("pavement")
            || name.startsWith("floors_exterior_street")) return WALKWAY;
        if (name.contains("street") || name.contains("asphalt")) return ROAD;
        if (name.contains("concrete") || name.contains("parking") || name.contains("paving")) return HARDSTANDING;
        return 0;
    }

    static boolean curb(int from, int to) {
        return from != to && (from == WALKWAY || to == WALKWAY || from == HARDSTANDING && to == ROAD
            || from == ROAD && to == HARDSTANDING);
    }

    private static float fallRate(int kind) {
        return switch (kind) {
            case TWIG, BARK, CARDBOARD -> 0.48f;
            case FLUFF, PAPER, WRAPPER -> 0.09f;
            case RECEIPT, NEWSPAPER -> 0.13f;
            default -> 0.23f;
        };
    }

    private static boolean urban(int kind) {
        return kind == PAPER || kind >= RECEIPT;
    }

    private static void advance(IsoCell cell, float dt) {
        for (int i = active.size() - 1; i >= 0; i--) {
            var p = active.get(i);
            p.age += dt;
            if (p.age >= p.life) {
                if (urban(p.kind) && p.height < 0.18f) {
                    int x = floor(p.x), y = floor(p.y);
                    drop(cell.getGridSquare(x, y, p.z), p.x - x, p.y - y, p.kind);
                }
                recycle(i);
                continue;
            }
            float wobble = (float) Math.sin(p.age * 4.1f + p.phase);
            float speed = (0.43f + wind * 5.8f + gust * 3.8f) * p.drift * p.shelter;
            float x = p.x + (wx * speed - wy * wobble * 0.55f) * dt;
            float y = p.y + (wy * speed + wx * wobble * 0.55f) * dt;
            float fall = fallRate(p.kind);
            p.height += (-fall + (wind * 0.45f + gust * 0.32f) * p.shelter
                + wobble * (0.13f + wind * 0.12f)) * dt;
            int oldX = floor(p.x), oldY = floor(p.y), newX = floor(x), newY = floor(y);
            if (oldX != newX || oldY != newY) {
                var before = cell.getGridSquare(oldX, oldY, p.z);
                var after = cell.getGridSquare(newX, newY, p.z);
                if (before == null || after == null) {
                    recycle(i);
                    continue;
                }
                boolean fence = p.height < 0.63f && before.isHoppableTo(after);
                if (p.height < 0.24f && urban(p.kind) && curb(pavement(before), pavement(after))) {
                    drop(before, p.x - oldX, p.y - oldY, p.kind);
                    recycle(i);
                    continue;
                }
                if (blocks(before, after) || fence || !after.isOutside() || after.haveRoof) {
                    if (urban(p.kind) || !after.isOutside() || fence || before.isWallTo(after)) {
                        drop(before, p.x - oldX, p.y - oldY, p.kind);
                    }
                    recycle(i);
                    continue;
                }
                p.shelter = PixelLight.windShelter && sheltered(cell, after) ? 0.42f : 1f;
            }
            p.x = x;
            p.y = y;
            if (p.height <= 0.035f) {
                var square = cell.getGridSquare(newX, newY, p.z);
                if (p.age > 1.0f && (speed < 1.35f || p.kind == TWIG || p.kind == BARK || p.kind == CARDBOARD)) {
                    drop(square, x - newX, y - newY, p.kind);
                    recycle(i);
                    continue;
                }
                p.height = 0.036f + Math.max(0f, wobble) * 0.05f * wind;
            } else {
                p.height = Math.min(p.height, 0.96f);
            }
        }
    }

    private static boolean sheltered(IsoCell cell, IsoGridSquare start) {
        var previous = start;
        int x = start.x, y = start.y;
        for (int i = 1; i <= 3; i++) {
            int nextX = x - Math.round(wx * i), nextY = y - Math.round(wy * i);
            var next = cell.getGridSquare(nextX, nextY, start.z);
            if (next == null) break;
            if (next == previous) continue;
            if (blocks(previous, next) || !next.isOutside()) return true;
            previous = next;
        }
        return false;
    }

    private static boolean blocks(IsoGridSquare a, IsoGridSquare b) {
        return a.isWallTo(b) || a.isDoorBlockedTo(b) || a.isWindowBlockedTo(b);
    }

    private static void recycle(int index) {
        int last = active.size() - 1;
        Particle particle = active.get(index);
        if (index != last) active.set(index, active.get(last));
        active.remove(last);
        pool.add(particle);
    }

    private static void drop(IsoGridSquare square, float x, float y, int kind) {
        if (kind == FLUFF) return;
        float amount = kind == TWIG || kind == BARK || kind == CARDBOARD ? 0.28f : 0.16f;
        WeatherGround.landDebris(square, x, y, amount, urban(kind));
    }

    private static int floor(float value) {
        return (int) Math.floor(value);
    }

    private static int hash(int x, int y) {
        return (x * 73856093) ^ (y * 19349663);
    }

    private static void draw(IsoCell cell, int player, int level, IsoCamera.FrameState frame) throws Exception {
        if (atlas == null) {
            try (var source = WindDebris.class.getResourceAsStream("wind-debris.png.b64")) {
                if (source == null) throw new IllegalStateException("wind-debris atlas missing");
                try (var image = new BufferedInputStream(Base64.getMimeDecoder().wrap(source))) {
                    atlas = new Texture("PZOE wind debris", image, false);
                    atlas.setUseAlphaChannel(true);
                }
            }
        }
        if (!atlas.isReady()) return;
        boolean drawing = false;
        int cx = floor(frame.camCharacterX), cy = floor(frame.camCharacterY);
        for (var p : active) {
            if (p.z != level || Math.abs(p.x - frame.camCharacterX) > 24 || Math.abs(p.y - frame.camCharacterY) > 24) continue;
            var square = cell.getGridSquare(floor(p.x), floor(p.y), p.z);
            if (square == null || !square.isOutside() || !square.isCouldSee(player)) continue;
            float z = p.z + p.height;
            float x = IsoUtils.XToScreen(p.x, p.y, z, 0) - frame.offX;
            float y = IsoUtils.YToScreen(p.x, p.y, z, 0) - frame.offY;
            if (x < -20f || y < -20f || x > frame.offscreenWidth + 20f || y > frame.offscreenHeight + 20f) continue;
            if (!drawing) {
                SpriteRenderer.instance.StartShader(0, player);
                IndieGL.enableDepthTest();
                IndieGL.glDepthFunc(515);
                IndieGL.glDepthMask(false);
                IndieGL.glBlendFunc(770, 771);
                drawing = true;
            }
            float turn = p.phase + p.age * p.spin;
            float hw = SIZE[p.kind] * Core.tileScale * p.scale * 0.48f;
            float hh = hw * (0.21f + 0.60f * Math.abs((float) Math.cos(turn * 1.78f)));
            float c = (float) Math.cos(turn), s = (float) Math.sin(turn);
            float ux = c * hw, uy = s * hw, vx = -s * hh, vy = c * hh;
            float stride = (atlas.getXEnd() - atlas.getXStart()) / ATLAS_COLUMNS;
            float u1 = atlas.getXStart() + stride * (p.kind + 0.01f), u2 = atlas.getXStart() + stride * (p.kind + 0.99f);
            float v1 = atlas.getYStart(), v2 = atlas.getYEnd();
            var light = square.getLightInfo(player);
            float alpha = Math.min(1f, Math.min(p.age / 0.21f, (p.life - p.age) / 0.48f)) * 0.86f;
            float r = (p.tint >> 16 & 255) / 255f * light.r, g = (p.tint >> 8 & 255) / 255f * light.g;
            float b = (p.tint & 255) / 255f * light.b;
            TextureDraw.nextZ = IsoDepthHelper.getSquareDepthData(cx, cy, p.x + 0.07f, p.y + 0.07f, z).depthStart * 2f - 1f;
            TextureDraw.nextChunkDepth = 0f;
            SpriteRenderer.instance.renderPoly(atlas, x - ux - vx, y - uy - vy, x + ux - vx, y + uy - vy,
                x + ux + vx, y + uy + vy, x - ux + vx, y - uy + vy, r, g, b, alpha,
                u1, v1, u2, v1, u2, v2, u1, v2);
        }
    }
}
