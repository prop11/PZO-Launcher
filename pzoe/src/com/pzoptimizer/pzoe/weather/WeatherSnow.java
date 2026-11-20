package com.pzoptimizer.pzoe.weather;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PZOE weather: deep snow you wade through. Idea and tuning from fmac's Atmospherics (EngineSnowMovement,
 * ClearingActions), rewritten as PZOE's own ASM on PZOE's ground snow.
 *
 * How deep the snow lies at a point, in levels (a level is about 3 m): the same sum the Better Ground Snow shader draws
 * (GroundSnow.depth for how much has fallen, deeper in the low swells of the snow field and banked up against walls
 * and fences on their downwind side, a little less on roads), up to 0.36 of a level, about waist height. The shader
 * writes that height into the depth the characters are drawn against, so people, zombies, wheels and bodies sink in.
 * Here it makes them move like it:
 * - walking speed drops with depth, down to about half (IsoGameCharacter.getDeferredMovement: players, zombies, animals);
 * - from shin deep, characters wade: the game's high-stepping walk through bushes (isInTreesNoBush answers yes),
 *   without the scratches bushes give (damageWhileInTrees skipped unless really in trees);
 * - vehicles lose engine force and top speed and drag (CarController.checkTire, BaseVehicle.getMaxSpeed);
 * - snow that's walked on packs down: a path that's been used a few times is a fraction as deep (the trail shader
 *   shows the same footprints sinking people less); new snow fills it in again, and it settles over a day or two;
 * - a shovel clears a square down to the ground (PZOE_SnowShovel.lua) until fresh snow covers it again.
 *
 * Speed: the movement hook runs every time any character's movement is read, often several times a frame and maybe
 * from PZO's worker threads. Everything per character sits in a fixed array of slots keyed by identity (no locks, no
 * map lookups, weak references), depth is only worked out again after real movement or 150 ms, and the whole thing
 * stops at its first line while no snow lies. Trodden snow is a sparse per-chunk store that empties itself lazily on read.
 */
public final class WeatherSnow {
    static final float MAX_H = 0.36f;
    private static final float WADE_IN = 0.07f, WADE_OUT = 0.05f;
    // Fresh snow (GroundSnow.snowfall units, blanket depth added by snowing) that fills a cleared square back in
    private static final double REFILL = 0.5;

    // Updated once a frame: the sandbox switches, so the hot hooks read one boolean
    static volatile boolean onNow;

    private WeatherSnow() {}

    /** Deep snow gameplay is on here (cached once a frame). */
    public static boolean on() {
        return onNow;
    }

    /** Once a frame from WeatherFronts, every role. */
    static void frame() {
        onNow = WeatherEffects.on("PZOEWeather.DeepSnow", true);
        refillCheck();
    }

    // Per character (and vehicle) cache: slots by identity hash, last writer wins, a collision only costs a recompute

    private static final class Slot {
        WeakReference<Object> who = new WeakReference<>(null);
        float lx = Float.NaN, ly;
        float hx = Float.NaN, hy, h;
        long t;
        boolean wade;
    }

    private static final int SLOTS = 4096;
    private static final Slot[] slots = new Slot[SLOTS];
    static {
        for (int i = 0; i < SLOTS; i++) slots[i] = new Slot();
    }

    private static Slot slot(Object o) {
        Slot s = slots[System.identityHashCode(o) & (SLOTS - 1)];
        if (s.who.get() != o) {
            s.who = new WeakReference<>(o);
            s.lx = Float.NaN;
            s.hx = Float.NaN;
            s.wade = false;
        }
        return s;
    }

    /** Snow depth for this mover, worked out again only after it moved a tenth of a square or 150 ms passed. */
    private static float cachedDepth(Slot s, float x, float y, float z, float ax, float ay) {
        long now = System.nanoTime();
        if (Math.abs(x - s.hx) > 0.1f || Math.abs(y - s.hy) > 0.1f || now - s.t > 150_000_000L) {
            s.h = depthAt(x + ax, y + ay, z);
            s.hx = x;
            s.hy = y;
            s.t = now;
        }
        return s.h;
    }

    // The snow field, the same noise as pzoGsHash / pzoGsNoise / pzoGsField (quality 2) in PZOE_Shaders.lua

    private static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    private static float hash(float px, float py) {
        px = px - 4096f * (float) Math.floor(px / 4096f);
        py = py - 4096f * (float) Math.floor(py / 4096f);
        float qx = fract(px * 0.1031f), qy = fract(py * 0.1031f), qz = fract(px * 0.1031f);
        float dot = qx * (qy + 33.33f) + qy * (qz + 33.33f) + qz * (qx + 33.33f);
        qx += dot;
        qy += dot;
        qz += dot;
        return fract((qx + qy) * qz);
    }

    private static float noise(float x, float y) {
        float ix = (float) Math.floor(x), iy = (float) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float a = hash(ix, iy), b = hash(ix + 1f, iy), c = hash(ix, iy + 1f), d = hash(ix + 1f, iy + 1f);
        float ab = a + (b - a) * fx;
        return ab + ((c + (d - c) * fx) - ab) * fy;
    }

    static float field(float wx, float wy) {
        float n = noise(wx * 0.35f, wy * 0.35f) * 0.55f;
        n += noise((0.8f * wx - 0.6f * wy) * 1.1f + 7.3f, (0.6f * wx + 0.8f * wy) * 1.1f + 7.3f) * 0.3f;
        n += noise((0.6f * wx + 0.8f * wy) * 3.7f - 2.1f, (-0.8f * wx + 0.6f * wy) * 3.7f - 2.1f) * 0.15f;
        return n;
    }

    private static float smooth(float a, float b, float x) {
        return WeatherFronts.smooth(a, b, x);
    }

    // Squares that hold snow by the game's own rule (PixelLight.snowFlags) and whether they're road
    private static boolean holds(zombie.iso.IsoGridSquare sq) {
        if (sq == null || sq.z != 0 || sq.room != null) return false;
        zombie.core.properties.PropertyContainer pr = sq.getProperties();
        if (pr == null || !pr.has(zombie.iso.SpriteDetails.IsoFlagType.exterior) || !pr.has(zombie.iso.SpriteDetails.IsoFlagType.solidfloor)
            || pr.has(zombie.iso.SpriteDetails.IsoFlagType.water)) return false;
        return sq.getWater() == null || !sq.getWater().isValid();
    }

    private static boolean road(zombie.iso.IsoGridSquare sq) {
        zombie.iso.IsoObject floor = sq.getFloor();
        String name = floor == null || floor.sprite == null ? null : floor.sprite.getName();
        return name != null && (name.contains("blends_street") || name.contains("floors_exterior_street"));
    }

    // A wall, fence, window or door frame on a square's north or west edge
    private static boolean wallEdge(zombie.iso.IsoGridSquare sq, boolean north) {
        if (sq == null) return false;
        zombie.core.properties.PropertyContainer pr = sq.getProperties();
        if (pr == null) return false;
        if (north) return pr.has(zombie.iso.SpriteDetails.IsoFlagType.collideN) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.WindowN)
            || pr.has(zombie.iso.SpriteDetails.IsoFlagType.DoorWallN);
        return pr.has(zombie.iso.SpriteDetails.IsoFlagType.collideW) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.WindowW)
            || pr.has(zombie.iso.SpriteDetails.IsoFlagType.DoorWallW);
    }

    /** Snow height in levels at a world point (ground level only), 0 where none lies. */
    public static float depthAt(float x, float y, float z) {
        float total = com.pzoptimizer.pzoe.GroundSnow.depthNow();
        if (total <= 0.001f || z >= 0.5f || z < -0.5f) return 0f;
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return 0f;
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y);
        if (cleared(sx, sy)) return 0f;
        zombie.iso.IsoGridSquare sq = cell.getGridSquare(sx, sy, 0);
        if (!holds(sq)) return 0f;
        float fx = x - sx, fy = y - sy;
        float a = WeatherLook.windAngleRadians(), ws = WeatherLook.wind();
        float wx = (float) Math.cos(a) * ws, wy = (float) Math.sin(a) * ws;
        float drift = 0f;
        if (wallEdge(sq, true)) drift = Math.max(drift, (1f - smooth(0f, 0.9f, fy)) * (0.75f + 0.5f * wy));
        if (wallEdge(sq, false)) drift = Math.max(drift, (1f - smooth(0f, 0.9f, fx)) * (0.75f + 0.5f * wx));
        if (wallEdge(cell.getGridSquare(sx, sy + 1, 0), true)) drift = Math.max(drift, (1f - smooth(0f, 0.9f, 1f - fy)) * (0.75f - 0.5f * wy));
        if (wallEdge(cell.getGridSquare(sx + 1, sy, 0), false)) drift = Math.max(drift, (1f - smooth(0f, 0.9f, 1f - fx)) * (0.75f - 0.5f * wx));
        float d = total * (road(sq) ? 0.85f : 1f);
        float dl = d + drift * (0.45f * Math.min(d, 1f) + 0.8f * Math.max(d - 1f, 0f));
        float deep = Math.max(d - 1f, 0f);
        float n = field(x, y);
        float h = 0.012f + 0.05f * Math.min(dl, 1f) + 0.06f * deep + 0.1f * deep * smooth(0.45f, 0.8f, n) + 0.18f * drift * Math.max(dl - 0.5f, 0f);
        // Trodden down: a well used path is a quarter as deep
        h *= 1f - 0.75f * packedAt(x, y);
        return Math.min(MAX_H, h);
    }

    // Snow pressure 0..1 from depth: full by waist deep
    private static float pressure(float h) {
        return h <= 0f ? 0f : (float) Math.pow(Math.min(1.0, h / 0.3), 0.8);
    }

    private static boolean skip(zombie.characters.IsoGameCharacter c) {
        if (c == null || c.getVehicle() != null) return true;
        if (c instanceof zombie.characters.IsoPlayer p) return p.isNoClip() || !p.isLocalPlayer() && !zombie.network.GameServer.server;
        return false;
    }

    /** End of IsoGameCharacter.getDeferredMovement(result, reset): packs the snow it walks on, and the step shrinks. */
    public static void slow(zombie.characters.IsoGameCharacter c, zombie.iso.Vector2 v) {
        try {
            if (v == null || c == null || !onNow || com.pzoptimizer.pzoe.GroundSnow.depthNow() <= 0.001f) return;
            Slot s = slot(c);
            float x = c.getX(), y = c.getY(), z = c.getZ();
            // Trodden snow follows the distance really walked, however often the movement is read in a frame
            if (Float.isNaN(s.lx)) {
                s.lx = x;
                s.ly = y;
            } else {
                float dx = x - s.lx, dy = y - s.ly, dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 0.05f) {
                    if (dist < 2f && z < 0.5f && z > -0.5f) trample(x, y, dist);
                    s.lx = x;
                    s.ly = y;
                }
            }
            if ((v.x == 0f && v.y == 0f) || skip(c)) return;
            float len = (float) Math.sqrt(v.x * v.x + v.y * v.y);
            float h = cachedDepth(s, x, y, z, v.x / len * 0.3f, v.y / len * 0.3f);
            if (h <= 0.005f) return;
            float k = 1f - Math.min(0.9f, 1.4f * pressure(h)) / 2f;
            v.x *= k;
            v.y *= k;
        } catch (Throwable ignored) {
        }
    }

    /** PuddleSplash (game thread): how deep the snow is where this character is, for its trench. Any character. */
    public static float trenchDepth(zombie.characters.IsoGameCharacter c) {
        if (c == null || com.pzoptimizer.pzoe.GroundSnow.depthNow() <= 1.0f) return 0f;
        return cachedDepth(slot(c), c.getX(), c.getY(), c.getZ(), 0f, 0f);
    }

    /** Wading now (the game's walk through bushes), with a margin so it doesn't flicker at the edge of a drift. */
    static boolean wades(zombie.characters.IsoGameCharacter c) {
        if (!onNow || com.pzoptimizer.pzoe.GroundSnow.depthNow() <= 0.001f || skip(c)) return false;
        Slot s = slot(c);
        float h = cachedDepth(s, c.getX(), c.getY(), c.getZ(), 0f, 0f);
        s.wade = h >= (s.wade ? WADE_OUT : WADE_IN);
        return s.wade;
    }

    /** Return of IsoGameCharacter.isInTreesNoBush: also yes while wading. */
    public static boolean inTreesNoBush(boolean v, zombie.characters.IsoGameCharacter c) {
        if (v) return true;
        try {
            return wades(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Start of IsoGameCharacter.damageWhileInTrees: no bush scratches from snow, only from real trees. */
    public static boolean skipTreeDamage(zombie.characters.IsoGameCharacter c) {
        try {
            return onNow && c != null && slot(c).wade && !c.isInTrees();
        } catch (Throwable t) {
            return false;
        }
    }

    // Trodden snow: per chunk, 2 x 2 cells a square, 0 fresh .. 1 packed. Values are stored as of a base snowfall and
    // time; reads apply what has fallen and passed since, and a write folds that in first once it's worth it.

    private static final class Patch {
        final float[] cell = new float[256];
        volatile double baseSnow, baseHours;
    }

    private static final ConcurrentHashMap<Long, Patch> patches = new ConcurrentHashMap<>();

    private static long chunkKey(int cx, int cy) {
        return ((long) cx << 32) ^ (cy & 0xffffffffL);
    }

    private static double hoursNow() {
        zombie.GameTime gt = zombie.GameTime.getInstance();
        return gt == null ? 0.0 : gt.getWorldAgeHours();
    }

    // Packed snow fills in under about 0.7 of a blanket of new snow, and settles over a day and a half
    private static float fade(Patch p, double snow, double hours) {
        return (float) (Math.exp(-(snow - p.baseSnow) * 1.5) * Math.exp(-Math.max(0.0, hours - p.baseHours) / 36.0));
    }

    private static int cellIndex(float x, float y) {
        int lx = Math.floorMod((int) Math.floor(x * 2f), 16), ly = Math.floorMod((int) Math.floor(y * 2f), 16);
        return ly * 16 + lx;
    }

    static float packedAt(float x, float y) {
        if (patches.isEmpty()) return 0f;
        Patch p = patches.get(chunkKey(Math.floorDiv((int) Math.floor(x), 8), Math.floorDiv((int) Math.floor(y), 8)));
        if (p == null) return 0f;
        return p.cell[cellIndex(x, y)] * fade(p, com.pzoptimizer.pzoe.GroundSnow.snowfall, hoursNow());
    }

    private static void trample(float x, float y, float dist) {
        long key = chunkKey(Math.floorDiv((int) Math.floor(x), 8), Math.floorDiv((int) Math.floor(y), 8));
        Patch p = patches.get(key);
        double snow = com.pzoptimizer.pzoe.GroundSnow.snowfall, hours = hoursNow();
        if (p == null) {
            if (patches.size() > 4096) return;
            p = new Patch();
            p.baseSnow = snow;
            p.baseHours = hours;
            Patch had = patches.putIfAbsent(key, p);
            if (had != null) p = had;
        }
        float f = fade(p, snow, hours);
        if (f < 0.97f) {
            for (int i = 0; i < 256; i++) p.cell[i] *= f;
            p.baseSnow = snow;
            p.baseHours = hours;
            f = 1f;
        }
        int i = cellIndex(x, y);
        // A few passes along the same line make a path: each step packs what's left by a share of the distance walked
        float v = p.cell[i] * f;
        p.cell[i] = Math.min(1f, (v + Math.min(1f, dist * 0.9f) * (1f - v)) / f);
    }

    // Cleared squares (a shovel): no snow until enough new snow has fallen on them

    private static final ConcurrentHashMap<Long, Double> clearedAt = new ConcurrentHashMap<>();

    private static long squareKey(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    /** A square shovelled clear and not filled in again yet. */
    public static boolean cleared(int x, int y) {
        if (clearedAt.isEmpty()) return false;
        Double s = clearedAt.get(squareKey(x, y));
        return s != null && com.pzoptimizer.pzoe.GroundSnow.snowfall - s < REFILL;
    }

    /** A shovel cleared this square (every role applies it; the server tells clients). */
    public static void clear(int x, int y) {
        if (clearedAt.size() > 8192) return;
        clearedAt.put(squareKey(x, y), com.pzoptimizer.pzoe.GroundSnow.snowfall);
        redraw(x, y);
    }

    /** Server, for a client that just joined: the squares still clear, "x:y|x:y". */
    public static String clearedList() {
        StringBuilder sb = new StringBuilder();
        double now = com.pzoptimizer.pzoe.GroundSnow.snowfall;
        int n = 0;
        for (java.util.Map.Entry<Long, Double> e : clearedAt.entrySet()) {
            if (now - e.getValue() >= REFILL || n++ >= 1024) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append((int) (e.getKey() >> 32)).append(':').append((int) (long) e.getKey());
        }
        return sb.toString();
    }

    private static int refillTick;

    // Every 60th frame: squares that have filled in again leave the list and get their snow drawn again
    private static void refillCheck() {
        if (clearedAt.isEmpty() || ++refillTick < 60) return;
        refillTick = 0;
        double now = com.pzoptimizer.pzoe.GroundSnow.snowfall;
        clearedAt.entrySet().removeIf(e -> {
            if (now - e.getValue() < REFILL) return false;
            redraw((int) (e.getKey() >> 32), (int) (long) e.getKey());
            return true;
        });
        if (patches.size() > 64) {
            double h = hoursNow();
            patches.entrySet().removeIf(e -> fade(e.getValue(), now, h) < 0.02f);
        }
    }

    // The chunk's height map holds the "this square holds snow" flags the ground snow shader reads
    private static void redraw(int x, int y) {
        if (zombie.network.GameServer.server) return;
        try {
            zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
            zombie.iso.IsoGridSquare sq = cell == null ? null : cell.getGridSquare(x, y, 0);
            if (sq != null && sq.chunk != null) com.pzoptimizer.pzoe.PixelLight.chunkChanged(sq.chunk);
        } catch (Throwable ignored) {
        }
    }

    // Vehicles

    private static float vehicleFactor(float h) {
        return 1f - Math.min(0.9f, 1.3f * pressure(h));
    }

    private static float vehicleDepth(zombie.vehicles.BaseVehicle v) {
        if (v == null || com.pzoptimizer.pzoe.GroundSnow.depthNow() <= 0.001f) return 0f;
        return cachedDepth(slot(v), v.getX(), v.getY(), v.getZ(), 0f, 0f);
    }

    /** CarController.checkTire: engine force through the snow. */
    public static float engine(float force, zombie.vehicles.BaseVehicle v) {
        try {
            if (!onNow) return force;
            float h = vehicleDepth(v);
            return h <= 0.005f ? force : force * vehicleFactor(h);
        } catch (Throwable t) {
            return force;
        }
    }

    /** CarController.checkTire: deep snow drags on a moving vehicle like a light brake. */
    public static float brake(float force, zombie.vehicles.BaseVehicle v) {
        try {
            if (!onNow || v == null) return force;
            float h = vehicleDepth(v);
            if (h <= 0.005f) return force;
            float drag = v.getBrakingForce() * 1.1f * pressure(h) * Math.min(1f, Math.abs(v.getCurrentSpeedKmHour()) / 12f);
            return Math.max(0f, force) + drag;
        } catch (Throwable t) {
            return force;
        }
    }

    /** Return of BaseVehicle.getMaxSpeed. */
    public static float maxSpeed(float speed, zombie.vehicles.BaseVehicle v) {
        try {
            if (!onNow) return speed;
            float h = vehicleDepth(v);
            return h <= 0.005f ? speed : speed * vehicleFactor(h);
        } catch (Throwable t) {
            return speed;
        }
    }

    // Patches

    public static final String CAR = "zombie/core/physics/CarController";

    /** IsoGameCharacter: deferred movement, isInTreesNoBush, damageWhileInTrees. Returns how many of the 3 went in. */
    static int patchCharacter(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            if (m.name.equals("getDeferredMovement") && m.desc.equals("(Lzombie/iso/Vector2;Z)Lzombie/iso/Vector2;")) {
                int k = 0;
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() != Opcodes.ARETURN) continue;
                    InsnList l = new InsnList();
                    l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    l.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherSnow", "slow",
                        "(Lzombie/characters/IsoGameCharacter;Lzombie/iso/Vector2;)V", false));
                    m.instructions.insertBefore(insn, l);
                    k++;
                }
                if (k > 0) n++;
            } else if (m.name.equals("isInTreesNoBush") && m.desc.equals("()Z")) {
                int k = 0;
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() != Opcodes.IRETURN) continue;
                    InsnList l = new InsnList();
                    l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherSnow", "inTreesNoBush",
                        "(ZLzombie/characters/IsoGameCharacter;)Z", false));
                    m.instructions.insertBefore(insn, l);
                    k++;
                }
                if (k > 0) n++;
            } else if (m.name.equals("damageWhileInTrees") && m.desc.equals("()V")) {
                InsnList l = new InsnList();
                LabelNode go = new LabelNode();
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherSnow", "skipTreeDamage",
                    "(Lzombie/characters/IsoGameCharacter;)Z", false));
                l.add(new JumpInsnNode(Opcodes.IFEQ, go));
                l.add(new InsnNode(Opcodes.RETURN));
                l.add(go);
                m.instructions.insert(l);
                n++;
            }
        }
        return n;
    }

    /**
     * CarController.checkTire(BulletVariables): before each return, bv.engineForce and bv.brakingForce go through
     * engine() / brake(). CarController is BulletVariables' nest host, so it may write those private fields.
     */
    public static boolean patchCar(ClassNode node) {
        String bv = "zombie/core/physics/CarController$BulletVariables";
        for (MethodNode m : node.methods) {
            if (!m.name.equals("checkTire") || !m.desc.equals("(L" + bv + ";)V")) continue;
            int k = 0;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList l = new InsnList();
                for (String f : new String[] {"engineForce", "brakingForce"}) {
                    l.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    l.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    l.add(new FieldInsnNode(Opcodes.GETFIELD, bv, f, "F"));
                    l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    l.add(new FieldInsnNode(Opcodes.GETFIELD, CAR, "vehicleObject", "Lzombie/vehicles/BaseVehicle;"));
                    l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherSnow", f.equals("engineForce") ? "engine" : "brake",
                        "(FLzombie/vehicles/BaseVehicle;)F", false));
                    l.add(new FieldInsnNode(Opcodes.PUTFIELD, bv, f, "F"));
                }
                m.instructions.insertBefore(insn, l);
                k++;
            }
            return k > 0;
        }
        return false;
    }

    /** BaseVehicle.getMaxSpeed: result through maxSpeed(). */
    static int patchMaxSpeed(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (!m.name.equals("getMaxSpeed") || !m.desc.equals("()F")) continue;
            int k = 0;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.FRETURN) continue;
                InsnList l = new InsnList();
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherSnow", "maxSpeed", "(FLzombie/vehicles/BaseVehicle;)F", false));
                m.instructions.insertBefore(insn, l);
                k++;
            }
            return k;
        }
        return 0;
    }

    static String stateText() {
        zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
        float h = p == null ? 0f : depthAt(p.getX(), p.getY(), p.getZ());
        return "snowDepth=" + Math.round(com.pzoptimizer.pzoe.GroundSnow.depthNow() * 100) + ";snowHere=" + Math.round(h * 1000)
            + ";wading=" + (p != null && slot(p).wade ? 1 : 0) + ";snowPaths=" + patches.size() + ";snowCleared=" + clearedAt.size();
    }
}
