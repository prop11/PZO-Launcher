package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import zombie.IndieGL;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.core.properties.PropertyContainer;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.sprite.IsoSprite;

/**
 * Water running off roofs, awnings, eaves and balconies in the rain (PZO Enhancements' Roof Runoff), a port of fmac's
 * Atmospherics roof runoff, with his permission.
 *
 * Discovery: a few squares around each local player are looked at every 50 ms, nearest first, walking outward. A roof,
 * canopy, eave or balcony floor open to the sky drips from each edge that has nothing as high next to it (a roof that
 * runs on into another roof, or over an eave, hands its water on instead). Only the bottom row of a sloped roof drips.
 * Up to MAX_EDGES edges are kept, forgotten 15 s after they go out of range.
 *
 * Water: each edge fills while it rains and drains after it stops, about 30 s of dripping after the last drop. Most
 * edges drip now and then; some carry a thin steady stream once they're full. Drops fall under gravity from the edge
 * to whatever surface is below, splash for a quarter second and go back to a pool (no garbage while it rains).
 *
 * Drawing: at the end of the game's rain splash pass for each level, with the game's own rain textures, depth and
 * blending, so walls in front hide them exactly like vanilla splashes. A roof between the drop and the camera that the
 * game draws (not cut away over the player) hides it too. It leaves the depth and blend state the way vanilla's splash
 * pass does whenever it rains, so nothing drawn after it sees anything new.
 *
 * Main thread only. Switched on and off from the card through PZOApi (modApi "runoff").
 */
final class RainRunoff {

    static volatile boolean on;
    static volatile float amount = 1f;

    static final int MAX_PARTICLES = 384, MAX_EDGES = 320, SCAN_BUDGET = 64, EMIT_BUDGET = 24, RADIUS = 20, MAX_OCC = 4;
    static final int KIND_FLOOR = 0, KIND_ROOF = 1, KIND_CANOPY = 2, KIND_EAVE = 3;
    static final double EAVE_LIP = 0.89, EAVE_REACH = 0.375, OCCLUSION_REACH = 12;
    // His tuned defaults: drop and stream looks, how fast an edge fills (s), how long it drips on after the rain (s)
    static final double STREAM_CHANCE = 0.18, DROP_OPACITY = 0.55, DROP_SIZE = 0.22, SIZE_VAR = 0.3, OPACITY_VAR = 0.25,
        SPLASH_OPACITY = 0.5, SPLASH_SIZE = 0.65, SPLASH_SIZE_VAR = 0.4, SPLASH_OPACITY_VAR = 0.3, STREAM_OPACITY = 0.42,
        STREAM_SIZE = 0.28, FILL_SECONDS = 2, TAIL_SECONDS = 30;
    private static final int[] DX = {0, 1, 0, -1}, DY = {-1, 0, 1, 0};
    private static final int[][] OFFSETS = offsets();

    private static final class Surface {
        final double height, lo, hi;
        final int edges, kind;
        final boolean emitter;

        Surface(double height, int edges, boolean emitter, int kind, double lo, double hi) {
            this.height = height;
            this.edges = edges;
            this.emitter = emitter;
            this.kind = kind;
            this.lo = lo;
            this.hi = hi;
        }
    }

    private record Art(boolean roof, boolean canopy, int edges, boolean eave) {}

    private static final Art NO_ART = new Art(false, false, 0, false);
    private static final Surface NONE = new Surface(Double.NaN, 0, false, -1, 0, 0);

    private static final class Edge {
        final int x, y, z, side;
        final long seed;
        final double along;
        double seen, water, nextStream;

        Edge(int x, int y, int z, int side) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
            seed = hash(((long) x << 32) ^ (y & 0xffffffffL) ^ ((long) z << 16) ^ side * 0x9e3779b9L);
            along = 0.15 + 0.7 * unit(seed);
            seen = seconds;
        }
    }

    private static final class Particle {
        double x, y, height, target, velocity, born, size, opacity, splashSize, splashOpacity;
        int lightZ, occluders;
        boolean splash, stream;
        final int[] occluder = new int[MAX_OCC * 3];
        final double[] hidden = new double[MAX_OCC * 2];
    }

    private static final HashMap<Long, Edge> edges = new HashMap<>();
    private static final ArrayList<Edge> order = new ArrayList<>();
    private static boolean orderDirty;
    private static final ArrayList<Particle> active = new ArrayList<>(MAX_PARTICLES);
    private static final ArrayList<Particle> pool = new ArrayList<>(MAX_PARTICLES);
    private static final HashMap<Long, Surface> scanned = new HashMap<>();
    private static final IdentityHashMap<IsoSprite, Art> arts = new IdentityHashMap<>();
    private static final HashMap<Long, Boolean> roofDrawn = new HashMap<>();
    private static final double[] observers = new double[8];
    private static int observerCount;
    private static IsoCell lastCell;
    private static long lastTick, lastScan, sequence, emitted;
    private static double seconds, rainAtScan, runoff, tailLevel, tailStarted = Double.NaN;
    private static int columnCursor, levelCursor, observerCursor, edgeCursor, spawnCount;
    private static int columnX, columnY, columnMax;
    private static boolean scanningColumn;
    private static int lastFrame = Integer.MIN_VALUE;
    private static final Texture[] textures = new Texture[5];
    private static String problem;

    private RainRunoff() {}

    private static long hash(long h) {
        h ^= h >>> 30;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27;
        h *= 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }

    private static double unit(long h) {
        return (hash(h) >>> 11) * 0x1.0p-53;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double varied(double base, double variation, long seed) {
        return base * (1 + variation * (unit(seed) * 2 - 1) * 0.8);
    }

    private static int[][] offsets() {
        int side = RADIUS * 2 + 1, at = 0;
        int[][] out = new int[side * side][];
        for (int y = -RADIUS; y <= RADIUS; y++) for (int x = -RADIUS; x <= RADIUS; x++) out[at++] = new int[] {x, y};
        java.util.Arrays.sort(out, java.util.Comparator.comparingInt(p -> p[0] * p[0] + p[1] * p[1]));
        return out;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 16) | ((z & 0xFF) << 2);
    }

    /** modApi "runoff": card switch and amount (percent). */
    static void configure(boolean enabled, double percent) {
        amount = (float) clamp(percent / 100.0, 0, 3);
        on = enabled;
    }

    private static void clear() {
        pool.addAll(active);
        active.clear();
        edges.clear();
        order.clear();
        scanned.clear();
        arts.clear();
        scanningColumn = false;
        columnCursor = levelCursor = edgeCursor = 0;
        rainAtScan = runoff = tailLevel = 0;
        tailStarted = Double.NaN;
    }

    /** End of FBORenderCell.renderRainSplashes(player, z): main thread, once per level per player each frame. */
    public static void render(int player, int z) {
        if (!on) {
            if (!active.isEmpty() || !edges.isEmpty()) clear();
            return;
        }
        try {
            int f = IsoCamera.frameState.frameCount;
            if (f != lastFrame) {
                lastFrame = f;
                update();
                roofDrawn.clear();
            }
            if (!active.isEmpty()) draw(player, z);
        } catch (Throwable t) {
            if (problem == null) {
                problem = t.toString();
                PZOLogger.warn("[RainRunoff] " + t);
            }
        }
    }

    private static void update() {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        if (cell != lastCell) {
            clear();
            lastCell = cell;
        }
        long now = System.nanoTime();
        if (zombie.GameTime.isGamePaused()) {
            lastTick = now;
            return;
        }
        double dt = lastTick == 0 ? 0 : clamp((now - lastTick) * 1e-9, 0, 0.1);
        lastTick = now;
        advance(dt);
        if (lastScan != 0 && now - lastScan < 50_000_000L) return;
        double scanDt = lastScan == 0 ? 0.05 : clamp((now - lastScan) * 1e-9, 0, 0.15);
        lastScan = now;
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        boolean snow = cm.getPrecipitationIsSnow() || cm.isSnowing();
        // Snowfall ends roof runoff at once: no after-rain drain while it snows
        if (snow) {
            rainAtScan = runoff = tailLevel = 0;
            tailStarted = Double.NaN;
        }
        observerCount = 0;
        for (int i = 0; i < 4; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if (p == null) continue;
            observers[observerCount * 2] = p.getX();
            observers[observerCount * 2 + 1] = p.getY();
            observerCount++;
        }
        discoverAndEmit(cell, scanDt, snow ? 0 : clamp(cm.getRainIntensity(), 0, 1));
    }

    // Roof water follows a tapering shower: rising rain counts at once, falling rain drains over a few seconds, and
    // after the last drop the edges keep dripping, less and less, for TAIL_SECONDS
    private static void rainResponse(double dt) {
        if (rainAtScan > 0) {
            double retention = Math.exp(-Math.max(0, dt) / (TAIL_SECONDS * 0.25));
            runoff = rainAtScan >= runoff ? rainAtScan : rainAtScan + (runoff - rainAtScan) * retention;
            tailLevel = runoff;
            tailStarted = Double.NaN;
        } else {
            if (!Double.isFinite(tailStarted)) tailStarted = seconds;
            double progress = clamp((seconds - tailStarted) / TAIL_SECONDS, 0, 1);
            runoff = tailLevel * (1 - progress);
        }
    }

    private static boolean near(Edge e) {
        for (int i = 0; i < observerCount; i++) {
            if (Math.abs(e.x - observers[i * 2]) <= RADIUS + 1 && Math.abs(e.y - observers[i * 2 + 1]) <= RADIUS + 1) return true;
        }
        return false;
    }

    private static void discoverAndEmit(IsoCell cell, double dt, double rain) {
        spawnCount = 0;
        rainAtScan = rain;
        scanned.clear();
        if (observerCount == 0) {
            clear();
            return;
        }
        double previous = runoff;
        rainResponse(dt);
        double drain = previous > 0 ? clamp(runoff / previous, 0, 1) : 0;
        for (Iterator<Edge> it = edges.values().iterator(); it.hasNext(); ) {
            Edge e = it.next();
            if (seconds - e.seen > 15 || !near(e)) {
                it.remove();
                orderDirty = true;
            }
        }
        double wet = runoff * amount;
        if (wet > 0.001) {
            for (int i = 0; i < SCAN_BUDGET; i++) {
                if (!scanningColumn) {
                    int o = observerCursor % observerCount;
                    int[] off = OFFSETS[columnCursor];
                    columnX = (int) Math.floor(observers[o * 2]) + off[0];
                    columnY = (int) Math.floor(observers[o * 2 + 1]) + off[1];
                    columnMax = (int) clamp(maxZ(cell, columnX, columnY), 0, 31);
                    levelCursor = 0;
                    scanningColumn = true;
                }
                discover(cell, columnX, columnY, levelCursor++);
                if (levelCursor > columnMax) {
                    scanningColumn = false;
                    if (++observerCursor % observerCount == 0) columnCursor = (columnCursor + 1) % OFFSETS.length;
                }
            }
        }
        if (orderDirty) {
            order.clear();
            order.addAll(edges.values());
            orderDirty = false;
        }
        // Round robin so the first edges found can't take the whole budget
        int count = order.size();
        for (int i = 0; i < count; i++) {
            Edge e = order.get((edgeCursor + i) % count);
            if (rainAtScan > 0) e.water = clamp(e.water + dt * rainAtScan * amount / FILL_SECONDS, 0, 1);
            else e.water *= drain;
            double dripRate = 4 * wet;
            boolean stream = unit(e.seed ^ 0x7345L) < STREAM_CHANCE;
            if (stream && e.water > 0.25 && wet > 0.001) {
                double interval = 1 / Math.max(0.01, 24 * wet * e.water);
                e.nextStream = Math.max(e.nextStream, seconds - dt);
                while (seconds >= e.nextStream && spawnCount < EMIT_BUDGET) {
                    // Births staggered along the fall, so a stream stays a line at any scan rate
                    spawn(cell, e, e.along, true, seconds - e.nextStream);
                    e.nextStream += interval;
                }
            }
            if (dripRate > 0 && unit(++sequence ^ e.seed) < 1 - Math.exp(-dripRate * dt)) {
                spawn(cell, e, 0.1 + 0.8 * unit(sequence ^ 0x19afL), false, 0);
            }
            if (spawnCount >= EMIT_BUDGET) {
                edgeCursor = (edgeCursor + i + 1) % count;
                break;
            }
        }
        if (runoff == 0 && !edges.isEmpty()) {
            if (edges.values().removeIf(e -> e.water <= 0)) orderDirty = true;
        }
    }

    private static void discover(IsoCell cell, int x, int y, int z) {
        Surface s = surface(cell, x, y, z);
        if (s == null || !s.emitter || s.edges == 0 || !exposed(cell, x, y, z)) {
            for (int side = 0; side < 4; side++) {
                if (edges.remove(key(x, y, z) | side) != null) orderDirty = true;
            }
            return;
        }
        for (int side = 0; side < 4; side++) {
            long k = key(x, y, z) | side;
            Surface n = surface(cell, x + DX[side], y + DY[side], z);
            // A continuous roof or balcony has no seam to drip from
            if ((s.edges & (1 << side)) == 0 || n != null && (n.kind == s.kind || n.height >= s.height - 0.08)) {
                if (edges.remove(k) != null) orderDirty = true;
                continue;
            }
            // A roof edge over an eave hands its water to the eave's lip; one running on into the roof below drips nowhere
            Surface under = s.kind == KIND_ROOF ? surface(cell, x + DX[side], y + DY[side], z - 1) : null;
            if (under != null && (under.kind == KIND_EAVE || under.kind == KIND_ROOF && under.hi >= s.height - 0.1)) {
                if (edges.remove(k) != null) orderDirty = true;
                continue;
            }
            Edge e = edges.get(k);
            if (e != null) {
                e.seen = seconds;
            } else if (edges.size() < MAX_EDGES) {
                edges.put(k, new Edge(x, y, z, side));
                orderDirty = true;
            }
        }
    }

    private static void spawn(IsoCell cell, Edge e, double along, boolean stream, double age) {
        if (spawnCount >= EMIT_BUDGET || active.size() >= MAX_PARTICLES) return;
        // The roof may have gone since it was found
        Surface src = surface(cell, e.x, e.y, e.z);
        if (src == null || !src.emitter || !exposed(cell, e.x, e.y, e.z)) {
            e.seen = -100;
            return;
        }
        // Just outside the surface: an eave's lip, a short way out from its wall
        double out = src.kind == KIND_EAVE ? EAVE_REACH + 0.025 : 1.025;
        double x = e.x + (e.side == 1 ? out : e.side == 3 ? -0.025 : along);
        double y = e.y + (e.side == 2 ? out : e.side == 0 ? -0.025 : along);
        double u = e.side == 1 ? Math.min(1, out) : e.side == 3 ? 0 : along, v = e.side == 2 ? Math.min(1, out) : e.side == 0 ? 0 : along;
        double height = height(cell, e.x, e.y, e.z, u, v), target = landing(cell, x, y, height - 0.02);
        if (!Double.isFinite(height) || !Double.isFinite(target) || height - target < 0.12) return;
        Particle p = pool.isEmpty() ? new Particle() : pool.remove(pool.size() - 1);
        long seed = ++sequence;
        p.x = x;
        p.y = y;
        p.height = height;
        p.target = target;
        p.lightZ = (int) Math.floor(target + 0.01);
        occluders(cell, p, x, y, height, target);
        p.velocity = stream ? 0.8 : 0.2;
        p.born = seconds - age;
        p.stream = stream;
        p.splash = false;
        p.height -= p.velocity * age + 2 * age * age;
        p.velocity += 4 * age;
        p.size = varied(stream ? STREAM_SIZE : DROP_SIZE, SIZE_VAR, seed);
        p.opacity = clamp(varied(stream ? STREAM_OPACITY : DROP_OPACITY, OPACITY_VAR, seed ^ 0x763L), 0, 1);
        p.splashSize = varied(SPLASH_SIZE, SPLASH_SIZE_VAR, seed ^ 0x831L);
        p.splashOpacity = clamp(varied(SPLASH_OPACITY, SPLASH_OPACITY_VAR, seed ^ 0xa34L), 0, 1);
        active.add(p);
        spawnCount++;
        emitted++;
    }

    // The view ray from a drop rises a third of a level for every square it moves toward the camera (+x, +y). A
    // surface whose height band that ray passes through hides the drop while it falls through those heights.
    private static void occluders(IsoCell cell, Particle p, double x, double y, double top, double bottom) {
        p.occluders = 0;
        if (!(top > bottom)) return;
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y);
        double fx = x - sx, fy = y - sy, t = 0;
        double reach = Math.min(OCCLUSION_REACH, 3 * (maxZ(cell, sx, sy) + 1.5 - bottom));
        while (t < reach && p.occluders < MAX_OCC) {
            double step = Math.min(1 - fx, 1 - fy);
            if (!(step > 0)) step = 1e-6;
            double tIn = t, tOut = t + step;
            int lowest = (int) Math.floor(bottom + tIn / 3) - 1, highest = (int) Math.floor(top + tOut / 3) + 1;
            for (int z = Math.max(lowest, -32); z <= Math.min(highest, 31) && p.occluders < MAX_OCC; z++) {
                Surface s = surface(cell, sx, sy, z);
                if (s == null) continue;
                double lo = Math.max(bottom, s.lo - tOut / 3), hi = Math.min(top, s.hi - tIn / 3);
                if (hi - lo < 1e-4 || tOut < 0.02) continue;
                int at = p.occluders * 3;
                p.occluder[at] = sx;
                p.occluder[at + 1] = sy;
                p.occluder[at + 2] = z;
                p.hidden[p.occluders * 2] = lo;
                p.hidden[p.occluders * 2 + 1] = hi;
                p.occluders++;
            }
            t = tOut;
            fx += step;
            fy += step;
            if (fx >= 1 - 1e-9) {
                sx++;
                fx = 0;
            }
            if (fy >= 1 - 1e-9) {
                sy++;
                fy = 0;
            }
        }
    }

    private static void advance(double dt) {
        if (!(dt > 0)) return;
        seconds += dt;
        for (int i = active.size() - 1; i >= 0; i--) {
            Particle p = active.get(i);
            if (p.splash) {
                if (seconds - p.born >= 0.28) recycle(i);
                continue;
            }
            p.height -= p.velocity * dt + 2 * dt * dt;
            p.velocity += 4 * dt;
            if (p.height <= p.target) {
                p.height = p.target + 0.012;
                p.splash = true;
                p.born = seconds;
                p.size = p.splashSize;
                p.opacity = p.splashOpacity;
            } else if (seconds - p.born > 8) {
                recycle(i);
            }
        }
    }

    private static void recycle(int i) {
        int last = active.size() - 1;
        Particle p = active.get(i);
        active.set(i, active.get(last));
        active.remove(last);
        pool.add(p);
    }

    // World

    private static Surface surface(IsoCell cell, int x, int y, int z) {
        long k = key(x, y, z);
        Surface s = scanned.get(k);
        if (s == null) {
            s = read(cell, x, y, z);
            if (scanned.size() < 65536) scanned.put(k, s == null ? NONE : s);
            return s;
        }
        return s == NONE ? null : s;
    }

    private static Surface read(IsoCell cell, int x, int y, int z) {
        IsoGridSquare sq = cell.getGridSquare(x, y, z);
        if (sq == null) return null;
        Art roof = roof(sq);
        if (roof != null) {
            if (roof.canopy) return new Surface(z + 0.75, roof.edges, true, KIND_CANOPY, z + 0.7, z + 0.8);
            if (roof.eave) return new Surface(z + EAVE_LIP, roof.edges, true, KIND_EAVE, z + EAVE_LIP, z + 1);
            double h = sq.getApparentZ(0.5f, 0.5f);
            // Sloped roof art rises most of a level over its square: that's the band that hides drops behind it
            return new Surface(h, roof.edges, true, KIND_ROOF, h, h + 0.75);
        }
        if (!sq.TreatAsSolidFloor() && sq.getFloor() == null) return null;
        double h = sq.getApparentZ(0.5f, 0.5f);
        // An outdoor upper floor (balcony, flat roof) collects rain; a floor only hides what's below it
        return new Surface(h, 15, z > 0 && sq.getRoom() == null, KIND_FLOOR, h, h);
    }

    private static Art roof(IsoGridSquare sq) {
        List<IsoObject> objects = sq.getObjects();
        for (int i = 0, n = Math.min(32, objects.size()); i < n; i++) {
            Art a = art(objects.get(i));
            if (a.roof) return a;
        }
        return null;
    }

    private static Art art(IsoObject o) {
        IsoSprite sprite = o == null ? null : o.getSprite();
        if (sprite == null) return NO_ART;
        Art cached = arts.get(sprite);
        if (cached != null) return cached;
        String name = sprite.getName() == null ? "" : sprite.getName().toLowerCase(java.util.Locale.ROOT);
        PropertyContainer props = sprite.getProperties();
        String custom = props == null || props.get("CustomName") == null ? "" : props.get("CustomName").toLowerCase(java.util.Locale.ROOT);
        IsoObjectType type = sprite.getType();
        boolean canopy = custom.contains("canopy") || custom.contains("awning") || name.contains("canopy") || name.contains("awning");
        boolean slopeUpper = type == IsoObjectType.WestRoofM || type == IsoObjectType.WestRoofT;
        boolean roof = canopy || name.startsWith("roofs_") && !name.startsWith("roofs_accents_") || type == IsoObjectType.WestRoofB || slopeUpper;
        int edgeMask = 15;
        boolean eave = false;
        if (canopy) {
            String facing = props == null || props.get("Facing") == null ? "" : props.get("Facing");
            edgeMask = switch (facing) {
                case "N" -> 1;
                case "E" -> 2;
                case "S" -> 4;
                case "W" -> 8;
                default -> 15;
            };
        } else if (props != null && props.has(IsoFlagType.isEave)) {
            // Eave art hangs from the top of its square's north or west wall and drips toward the open south or east;
            // corner caps have no lip
            eave = true;
            if (props.has(IsoFlagType.attachedN)) edgeMask = 4;
            else if (props.has(IsoFlagType.attachedW)) edgeMask = 2;
            else if (props.has(IsoFlagType.attachedE) || props.has(IsoFlagType.attachedS)) edgeMask = 0;
            else if (name.startsWith("roofs_")) edgeMask = 6;
            else eave = false;
            roof = roof || eave;
        } else if (slopeUpper) {
            // Higher rows of a slope pass their water down to the bottom row
            edgeMask = 0;
        }
        Art a = new Art(roof, canopy, edgeMask, eave);
        if (arts.size() >= 4096) arts.clear();
        arts.put(sprite, a);
        return a;
    }

    private static int maxZ(IsoCell cell, int x, int y) {
        IsoChunk c = cell.getChunkForGridSquare(x, y, 0);
        return c == null ? 0 : Math.max(0, Math.min(31, c.maxLevel));
    }

    private static boolean exposed(IsoCell cell, int x, int y, int z) {
        for (int above = z + 1, max = maxZ(cell, x, y); above <= max; above++) {
            if (surface(cell, x, y, above) != null) return false;
        }
        return true;
    }

    private static double height(IsoCell cell, int x, int y, int z, double u, double v) {
        IsoGridSquare sq = cell.getGridSquare(x, y, z);
        if (sq == null) return Double.NaN;
        Art roof = roof(sq);
        if (roof != null && roof.canopy) return z + 0.75;
        if (roof != null && roof.eave) return z + EAVE_LIP;
        return sq.getApparentZ((float) u, (float) v);
    }

    private static double landing(IsoCell cell, double x, double y, double below) {
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y);
        IsoChunk c = cell.getChunkForGridSquare(sx, sy, 0);
        int minimum = c == null ? -4 : Math.max(-32, c.minLevel);
        for (int z = Math.min(31, (int) Math.floor(below)); z >= minimum; z--) {
            IsoGridSquare sq = cell.getGridSquare(sx, sy, z);
            if (sq == null) continue;
            Art roof = roof(sq);
            if (roof != null && roof.eave) {
                // An eave hangs above its own square's floor, which still takes the splash
                if (!sq.TreatAsSolidFloor() && sq.getFloor() == null) continue;
                double floor = sq.getApparentZ((float) (x - sx), (float) (y - sy));
                if (floor <= below) return floor;
                continue;
            }
            if (surface(cell, sx, sy, z) == null) continue;
            double h = height(cell, sx, sy, z, x - sx, y - sy);
            if (h <= below) return h;
        }
        // Unloaded space never gets a made-up ground or splash
        return Double.NaN;
    }

    // Drawing

    private static void draw(int player, int z) {
        if (!zombie.core.PerformanceSettings.fboRenderChunk) return;
        IsoCamera.FrameState fs = IsoCamera.frameState;
        if (fs.playerIndex != player) return;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        int cx = (int) Math.floor(fs.camCharacterX), cy = (int) Math.floor(fs.camCharacterY);
        float scale = zombie.core.Core.tileScale;
        boolean started = false;
        for (int i = 0, n = active.size(); i < n; i++) {
            Particle p = active.get(i);
            if ((int) Math.floor(p.height) != z || p.opacity <= 0) continue;
            if (hiddenByRoof(cell, player, p)) continue;
            IsoGridSquare sq = cell.getGridSquare((int) Math.floor(p.x), (int) Math.floor(p.y), p.lightZ);
            if (sq == null || !sq.isCouldSee(player)) continue;
            double age = seconds - p.born;
            int index = p.splash ? 1 + Math.min(3, (int) (age / 0.07)) : 0;
            Texture tex = textures[index];
            if (tex == null) textures[index] = tex = Texture.getSharedTexture(index == 0 ? "Rain_00_0" : "RainSplash_00_" + (index - 1));
            if (tex == null || !tex.isReady()) continue;
            float halfW = (float) (tex.getWidth() * scale * p.size * 0.5), halfH = (float) (tex.getHeight() * scale * p.size * 0.5);
            // Vanilla rain is a texel wide: thinner than a pixel and a small drop vanishes
            if (!p.splash) halfW = Math.max(0.75f, halfW);
            float px = (float) p.x, py = (float) p.y, ph = (float) p.height;
            float x = IsoUtils.XToScreen(px, py, ph, 0) - fs.offX, y = IsoUtils.YToScreen(px, py, ph, 0) - fs.offY;
            // A falling streak hangs from its lower tip; a splash is centred on the impact
            if (!p.splash) y -= halfH;
            double alpha = p.opacity * (p.splash ? clamp(1 - age / 0.28, 0, 1) : clamp(age / 0.03, 0, 1));
            ColorInfo light = sq.getLightInfo(player);
            if (!started) {
                SpriteRenderer.instance.StartShader(0, player);
                IndieGL.enableDepthTest();
                IndieGL.glDepthFunc(515);
                IndieGL.glDepthMask(false);
                IndieGL.glBlendFunc(770, 771);
                started = true;
            }
            TextureDraw.nextZ = IsoDepthHelper.getSquareDepthData(cx, cy, px + 0.1f, py + 0.1f, ph).depthStart * 2f - 1f;
            TextureDraw.nextChunkDepth = 0f;
            SpriteRenderer.instance.render(tex, x - halfW, y - halfH, halfW * 2f, halfH * 2f, 0.8f * light.r, 0.9f * light.g, light.b,
                (float) Math.min(1, alpha), null);
        }
    }

    // Behind a roof the game draws at the heights this drop is falling through; a roof cut away over the player hides nothing
    private static boolean hiddenByRoof(IsoCell cell, int player, Particle p) {
        for (int i = 0; i < p.occluders; i++) {
            if (p.height < p.hidden[i * 2] - 1e-6 || p.height > p.hidden[i * 2 + 1] + 1e-6) continue;
            int x = p.occluder[i * 3], y = p.occluder[i * 3 + 1], z = p.occluder[i * 3 + 2];
            long k = key(x, y, z) | player;
            Boolean drawn = roofDrawn.get(k);
            if (drawn == null) {
                IsoGridSquare sq = cell.getGridSquare(x, y, z);
                drawn = sq != null && zombie.iso.fboRenderChunk.FBORenderCutaways.getInstance().shouldRenderBuildingSquare(player, sq);
                roofDrawn.put(k, drawn);
            }
            if (drawn) return true;
        }
        return false;
    }

    static String stateText() {
        return "runoff=" + (on ? 1 : 0) + ";runoffEdges=" + edges.size() + ";runoffDrops=" + active.size() + ";runoffWater="
            + Math.round(runoff * 100);
    }

    // Bytecode: a call at every return of FBORenderCell.renderRainSplashes(int player, int z)
    static boolean patchCell(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderRainSplashes", "(II)V");
        if (m == null) return false;
        int returns = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ILOAD, 1));
            call.add(new VarInsnNode(Opcodes.ILOAD, 2));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/RainRunoff", "render", "(II)V", false));
            m.instructions.insertBefore(insn, call);
            returns++;
        }
        return returns > 0;
    }
}
