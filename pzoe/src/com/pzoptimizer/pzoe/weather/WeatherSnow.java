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
        heatCheck();
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

    // Banked up against walls and fences, with the settled wind the shader's drifts use, so wading matches what's drawn
    private static float driftAt(zombie.iso.IsoCell cell, zombie.iso.IsoGridSquare sq, int sx, int sy, float fx, float fy) {
        float wx = com.pzoptimizer.pzoe.PixelLight.snowWindX, wy = com.pzoptimizer.pzoe.PixelLight.snowWindY;
        float drift = 0f;
        if (wallEdge(sq, true)) drift = Math.max(drift, (1f - smooth(0f, 0.9f, fy)) * (0.75f + 0.5f * wy));
        if (wallEdge(sq, false)) drift = Math.max(drift, (1f - smooth(0f, 0.9f, fx)) * (0.75f + 0.5f * wx));
        if (wallEdge(cell.getGridSquare(sx, sy + 1, 0), true)) drift = Math.max(drift, (1f - smooth(0f, 0.9f, 1f - fy)) * (0.75f - 0.5f * wy));
        if (wallEdge(cell.getGridSquare(sx + 1, sy, 0), false)) drift = Math.max(drift, (1f - smooth(0f, 0.9f, 1f - fx)) * (0.75f - 0.5f * wx));
        return drift;
    }

    /**
     * How much of the ground the snow covers at a world point (ground level only), 0..1: the shader's patch rule
     * (arg / edge / cover in the Better Ground Snow block) without the tufts and tree crowns. Patches open where the
     * field is low and join into a blanket by about depth 0.8. Shovelled squares and melt rings count as bare.
     */
    public static float coverAt(float x, float y) {
        float total = com.pzoptimizer.pzoe.GroundSnow.depthNow();
        if (total <= 0.001f) return 0f;
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return 0f;
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y);
        if (cleared(sx, sy)) return 0f;
        zombie.iso.IsoGridSquare sq = cell.getGridSquare(sx, sy, 0);
        if (!holds(sq)) return 0f;
        float d = total * (road(sq) ? 0.85f : 1f);
        float dl = d + driftAt(cell, sq, sx, sy, x - sx, y - sy) * (0.45f * Math.min(d, 1f) + 0.8f * Math.max(d - 1f, 0f));
        float edge = field(x, y) * 0.9f;
        float cover = smooth(edge - 0.05f, edge + 0.12f, dl * 1.35f - 0.05f);
        if (cover <= 0f) return 0f;
        float melt = meltAt(x, y);
        return melt > 0f ? cover * (1f - melt) : cover;
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
        float drift = driftAt(cell, sq, sx, sy, fx, fy);
        float d = total * (road(sq) ? 0.85f : 1f);
        float dl = d + drift * (0.45f * Math.min(d, 1f) + 0.8f * Math.max(d - 1f, 0f));
        float deep = Math.max(d - 1f, 0f);
        float n = field(x, y);
        float h = 0.012f + 0.05f * Math.min(dl, 1f) + 0.06f * deep + 0.1f * deep * smooth(0.45f, 0.8f, n) + 0.18f * drift * Math.max(dl - 0.5f, 0f);
        // Trodden down: a well used path is a quarter as deep
        h *= 1f - 0.75f * packedAt(x, y);
        // Something standing here keeps only a thin layer round its foot (the shader does the same from the object bit)
        if (pushesSnow(sq)) h *= 1f - 0.85f * smooth(0.6f, 0.3f, Math.max(Math.abs(fx - 0.5f), Math.abs(fy - 0.5f)));
        float melt = meltAt(x, y);
        h = (h + pileAt(x, y)) * (1f - melt);
        return Math.min(MAX_H, h);
    }

    /**
     * A square with something standing on it that the snow should go round instead of burying: furniture, crates,
     * barrels, campfires, generators, bins, signs. Not walls, fences, doors or windows (edges), not trees, grass,
     * plants or floor clutter. PixelLight puts the same answer in the height map for the ground snow shader.
     */
    public static boolean pushesSnow(zombie.iso.IsoGridSquare sq) {
        if (sq == null || sq.z != 0) return false;
        java.util.List<zombie.iso.IsoObject> objects = com.pzoptimizer.GameCompat.objects(sq);
        for (int k = 0, n = objects.size(); k < n; k++) {
            zombie.iso.IsoObject o = objects.get(k);
            if (o == null || o.sprite == null || o instanceof zombie.iso.objects.IsoTree || o instanceof zombie.iso.objects.IsoDoor
                || o instanceof zombie.iso.objects.IsoWindow || o instanceof zombie.iso.objects.IsoWindowFrame) continue;
            if (o.sprite.cutN || o.sprite.cutW || o.isWallSE()) continue;
            zombie.core.properties.PropertyContainer pr = o.getProperties();
            if (pr == null || pr.has(zombie.iso.SpriteDetails.IsoFlagType.solidfloor) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.vegitation)
                || pr.has(zombie.iso.SpriteDetails.IsoFlagType.FloorOverlay) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.attachedFloor)
                || pr.has(zombie.iso.SpriteDetails.IsoFlagType.WallNW) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.collideN)
                || pr.has(zombie.iso.SpriteDetails.IsoFlagType.collideW) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.HoppableN)
                || pr.has(zombie.iso.SpriteDetails.IsoFlagType.HoppableW) || pr.has(zombie.iso.SpriteDetails.IsoFlagType.TallHoppableN)
                || pr.has(zombie.iso.SpriteDetails.IsoFlagType.TallHoppableW)) continue;
            String name = o.sprite.getName();
            if (name != null && (name.startsWith("blends_") || name.startsWith("floors_") || name.startsWith("e_newgrass")
                || name.startsWith("d_plants") || name.startsWith("d_generic") || name.startsWith("vegetation_"))) continue;
            return true;
        }
        return false;
    }

    // Heat melting the snow: lit campfires, barbecues, fireplaces outdoors and burning squares. Each source melts a
    // patch round itself over about 25 game minutes and keeps new snow off while it burns; once it's out, fresh snow
    // fills the patch in again (about half a blanket). Worked out by every role from the fires it has, so nothing goes
    // over the network. Not saved: a fire still burning after a load melts its patch again.

    private static final class Melt {
        float amount, radius, strength;
        double snowAt;
        boolean hot;
    }

    private static final ConcurrentHashMap<Long, Melt> melts = new ConcurrentHashMap<>();
    // x, y (square centre), radius, melted 0..1: every live patch for depthAt, the 8 nearest player 0 for the shader
    private static volatile float[] heatAll = new float[0];
    public static volatile float[] heatShader = new float[0];
    private static double meltHours = Double.NaN;
    private static int heatTick;
    private static java.lang.reflect.Field heatField;
    private static boolean heatTried;

    private static float meltEff(Melt m, double snow) {
        return m.hot ? m.amount : (float) (m.amount * Math.exp(-Math.max(0.0, snow - m.snowAt) * 4.0));
    }

    /** How melted the snow is here, 0..1, from fires nearby. */
    public static float meltAt(float x, float y) {
        float[] a = heatAll;
        float best = 0f;
        for (int i = 0; i + 3 < a.length; i += 4) {
            float dx = x - a[i], dy = y - a[i + 1], r = a[i + 2];
            float d2 = dx * dx + dy * dy;
            if (d2 >= r * r) continue;
            best = Math.max(best, a[i + 3] * (1f - smooth(r * 0.45f, r, (float) Math.sqrt(d2))));
        }
        return best;
    }

    private static void heatAt(int x, int y, float radius, float strength, float atLeast) {
        long k = squareKey(x, y);
        Melt m = melts.get(k);
        if (m == null) {
            if (melts.size() > 512) return;
            m = new Melt();
            melts.put(k, m);
        }
        m.radius = m.hot ? Math.max(m.radius, radius) : radius;
        m.strength = Math.max(m.strength, strength);
        m.amount = Math.max(m.amount, atLeast);
        m.hot = true;
    }

    private static java.util.ArrayList<?> heatSources(zombie.iso.IsoCell cell) {
        if (!heatTried) {
            heatTried = true;
            try {
                heatField = zombie.iso.IsoCell.class.getDeclaredField("heatSources");
                heatField.setAccessible(true);
            } catch (Throwable t) {
                heatField = null;
            }
        }
        try {
            return heatField == null ? null : (java.util.ArrayList<?>) heatField.get(cell);
        } catch (Throwable t) {
            return null;
        }
    }

    // Every 30th frame
    private static void heatCheck() {
        if (++heatTick < 30) return;
        heatTick = 0;
        marksSaveLoad();
        try {
            double hours = hoursNow();
            float dtMin = Double.isNaN(meltHours) ? 0f : (float) Math.max(0.0, Math.min(1.0, hours - meltHours)) * 60f;
            meltHours = hours;
            if (com.pzoptimizer.pzoe.GroundSnow.depthNow() <= 0.001f && melts.isEmpty()) {
                if (heatAll.length > 0) heatAll = new float[0];
                if (heatShader.length > 0) heatShader = new float[0];
                return;
            }
            zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
            if (cell == null) return;
            for (Melt m : melts.values()) {
                m.hot = false;
                m.strength = 0f;
            }
            java.util.ArrayList<zombie.iso.objects.IsoFire> fires = zombie.iso.objects.IsoFireManager.FireStack;
            for (int i = 0, n = fires.size(); i < n; i++) {
                zombie.iso.objects.IsoFire f = fires.get(i);
                zombie.iso.IsoGridSquare sq = f == null ? null : f.getSquare();
                if (sq == null || sq.z != 0) continue;
                // Flames on the square flash the snow off at once, then the patch spreads over a few minutes
                heatAt(sq.x, sq.y, 1.8f, 5f, 0.95f);
            }
            java.util.ArrayList<?> heat = heatSources(cell);
            if (heat != null) {
                for (int i = 0, n = heat.size(); i < n; i++) {
                    if (!(heat.get(i) instanceof zombie.iso.IsoHeatSource h) || h.getZ() != 0) continue;
                    zombie.iso.IsoGridSquare sq = cell.getGridSquare(h.getX(), h.getY(), 0);
                    if (sq == null || !sq.isOutside()) continue;
                    heatAt(h.getX(), h.getY(), Math.max(1.4f, Math.min(2.6f, h.getRadius() * 0.5f)), Math.min(1f, h.getTemperature() / 35f), 0f);
                }
            }
            double snow = com.pzoptimizer.pzoe.GroundSnow.snowfall;
            float[] all = new float[Math.min(64, melts.size()) * 4];
            int na = 0;
            for (java.util.Iterator<java.util.Map.Entry<Long, Melt>> it = melts.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<Long, Melt> e = it.next();
                Melt m = e.getValue();
                if (m.hot) {
                    m.amount = Math.min(1f, m.amount + dtMin / 25f * Math.max(0.3f, m.strength));
                    m.snowAt = snow;
                }
                float eff = meltEff(m, snow);
                if (!m.hot && eff < 0.02f) {
                    it.remove();
                    continue;
                }
                if (na + 4 > all.length) continue;
                long k = e.getKey();
                all[na] = (int) (k >> 32) + 0.5f;
                all[na + 1] = (int) k + 0.5f;
                all[na + 2] = m.radius;
                all[na + 3] = eff;
                na += 4;
            }
            heatAll = na == all.length ? all : java.util.Arrays.copyOf(all, na);
            heatShader = nearestN(heatAll, 8, 4);
            pileCheck();
        } catch (Throwable ignored) {
        }
    }

    // Piles of snow a vehicle pushed ahead of its bumper. A live pile rides in front of a car while it ploughs; when
    // the car stops or backs up it stays where it is. New snow buries a pile (it stands out less and less), and it
    // goes when the ground snow melts away. Per pile: x, y, half width, height (levels), and the direction the car
    // was going. Piles are made where the vehicle is seen moving (PuddleSplash), so in MP each client makes its own
    // and the server keeps none.

    private static final class Pile {
        float x, y, dx, dy, halfWid, h;
        double snowAt;
    }

    private static final java.util.concurrent.CopyOnWriteArrayList<Pile> piles = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.WeakHashMap<Object, float[]> livePiles = new java.util.WeakHashMap<>();
    // 8 floats a pile: x, y, half width, height, dir x, dir y, 0, 0
    private static volatile float[] pileAll = new float[0];
    public static volatile float[] pileShader = new float[0];

    private static float pileEff(Pile p, double snow) {
        return (float) (p.h * Math.exp(-Math.max(0.0, snow - p.snowAt) * 1.2));
    }

    /** Height a pile adds here, in levels. The shader draws the same shape. */
    public static float pileAt(float x, float y) {
        float[] a = pileAll;
        float best = 0f;
        for (int i = 0; i + 7 < a.length; i += 8) {
            float ddx = x - a[i], ddy = y - a[i + 1];
            if (ddx * ddx + ddy * ddy > 4f) continue;
            float along = ddx * a[i + 4] + ddy * a[i + 5], across = -ddx * a[i + 5] + ddy * a[i + 4];
            float e = along * along / 0.3f + across * across / Math.max(0.05f, a[i + 2] * a[i + 2]);
            best = Math.max(best, a[i + 3] * (1f - smooth(0.2f, 1f, e)));
        }
        return best;
    }

    /** PuddleSplash: the pile riding in front of this vehicle (height 0 = none). Game thread. */
    public static void livePile(Object vehicle, float x, float y, float dx, float dy, float halfWid, float h) {
        synchronized (livePiles) {
            if (h <= 0.005f) livePiles.remove(vehicle);
            else livePiles.put(vehicle, new float[] {x, y, halfWid, h, dx, dy, nowSec(), 0f});
        }
        rebuildPiles();
    }

    /** PuddleSplash: the vehicle stopped or backed off, so its pile stays here. */
    public static void dropPile(Object vehicle, float x, float y, float dx, float dy, float halfWid, float h) {
        synchronized (livePiles) {
            livePiles.remove(vehicle);
        }
        if (h > 0.01f) {
            Pile p = new Pile();
            p.x = x;
            p.y = y;
            p.dx = dx;
            p.dy = dy;
            p.halfWid = halfWid;
            p.h = h;
            p.snowAt = com.pzoptimizer.pzoe.GroundSnow.snowfall;
            piles.add(p);
            while (piles.size() > 64) piles.remove(0);
        }
        rebuildPiles();
    }

    private static void rebuildPiles() {
        double snow = com.pzoptimizer.pzoe.GroundSnow.snowfall;
        java.util.ArrayList<float[]> list = new java.util.ArrayList<>();
        for (Pile p : piles) {
            float e = pileEff(p, snow);
            if (e > 0.005f) list.add(new float[] {p.x, p.y, p.halfWid, e, p.dx, p.dy, 0f, 0f});
        }
        synchronized (livePiles) {
            list.addAll(livePiles.values());
        }
        float[] all = new float[list.size() * 8];
        for (int i = 0; i < list.size(); i++) System.arraycopy(list.get(i), 0, all, i * 8, 8);
        pileAll = all;
        pileShader = nearestN(all, 8, 8);
    }

    // Every heat check: piles buried by new snow or melted away with the ground snow leave the list
    private static void pileCheck() {
        if (piles.isEmpty() && pileAll.length == 0) return;
        if (com.pzoptimizer.pzoe.GroundSnow.depthNow() <= 0.001f) {
            piles.clear();
            synchronized (livePiles) {
                livePiles.clear();
            }
        } else {
            double snow = com.pzoptimizer.pzoe.GroundSnow.snowfall;
            piles.removeIf(p -> pileEff(p, snow) < 0.005f);
            // A car that drove out of range takes its live pile with it
            float now = nowSec();
            synchronized (livePiles) {
                livePiles.values().removeIf(e -> now - e[6] > 2f || now < e[6]);
            }
        }
        rebuildPiles();
    }

    private static float nowSec() {
        return (float) ((System.nanoTime() / 1_000_000L) % 100_000_000L) / 1000f;
    }

    // Save: melted patches and piles in the save's GameTime mod data, as "m,x,y,r,amount,snow since;p,x,y,dx,dy,w,h,
    // snow since". "Snow since" is how much has fallen since, because the snowfall counter starts again each session.
    // The server sends the same text to joining clients.
    private static final String MARKS_KEY = "PZOE_SnowMarks";
    private static Object marksCell;
    private static double marksSavedAt = -1;

    /** The melted patches and piles as text (save, and the server's hello to clients). */
    public static String marksText() {
        double snow = com.pzoptimizer.pzoe.GroundSnow.snowfall;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Long, Melt> e : melts.entrySet()) {
            Melt m = e.getValue();
            if (meltEff(m, snow) < 0.02f) continue;
            if (sb.length() > 0) sb.append(';');
            long k = e.getKey();
            sb.append("m,").append((int) (k >> 32)).append(',').append((int) k).append(',').append(m.radius).append(',')
                .append(m.amount).append(',').append(m.hot ? 0.0 : Math.max(0.0, snow - m.snowAt));
        }
        for (Pile p : piles) {
            if (pileEff(p, snow) < 0.005f) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append("p,").append(p.x).append(',').append(p.y).append(',').append(p.dx).append(',').append(p.dy).append(',')
                .append(p.halfWid).append(',').append(p.h).append(',').append(Math.max(0.0, snow - p.snowAt));
        }
        return sb.toString();
    }

    /** Takes melted patches and piles from text (a load, or the server's list when joining). */
    public static void applyMarks(String s) {
        if (s == null || s.isEmpty()) return;
        double snow = com.pzoptimizer.pzoe.GroundSnow.snowfall;
        for (String part : s.split(";")) {
            String[] f = part.split(",");
            try {
                if (f[0].equals("m") && f.length >= 6) {
                    int x = Integer.parseInt(f[1]), y = Integer.parseInt(f[2]);
                    Melt m = melts.computeIfAbsent(squareKey(x, y), k -> new Melt());
                    m.radius = Float.parseFloat(f[3]);
                    m.amount = Math.max(m.amount, Float.parseFloat(f[4]));
                    m.snowAt = snow - Double.parseDouble(f[5]);
                } else if (f[0].equals("p") && f.length >= 8) {
                    Pile p = new Pile();
                    p.x = Float.parseFloat(f[1]);
                    p.y = Float.parseFloat(f[2]);
                    p.dx = Float.parseFloat(f[3]);
                    p.dy = Float.parseFloat(f[4]);
                    p.halfWid = Float.parseFloat(f[5]);
                    p.h = Float.parseFloat(f[6]);
                    p.snowAt = snow - Double.parseDouble(f[7]);
                    piles.add(p);
                }
            } catch (RuntimeException ignored) {
            }
        }
        while (piles.size() > 64) piles.remove(0);
        heatTick = 30;
        rebuildPiles();
    }

    // A new cell is another save: its own marks come from its mod data. SP and the server save; clients get the
    // server's list when they join.
    private static void marksSaveLoad() {
        try {
            zombie.GameTime gt = zombie.GameTime.getInstance();
            Object cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
            if (gt == null) return;
            double hours = gt.getWorldAgeHours();
            if (cell != marksCell) {
                // Only a change from one world to another wipes them: a client's list from the server can arrive
                // before the first check after loading
                if (marksCell != null) {
                    melts.clear();
                    piles.clear();
                    synchronized (livePiles) {
                        livePiles.clear();
                    }
                }
                marksCell = cell;
                marksSavedAt = hours;
                if (!zombie.network.GameClient.client) {
                    Object v = gt.getModData().rawget(MARKS_KEY);
                    if (v != null) applyMarks(v.toString());
                }
                return;
            }
            if (zombie.network.GameClient.client || (hours - marksSavedAt < 1.0 / 6.0 && hours >= marksSavedAt)) return;
            marksSavedAt = hours;
            gt.getModData().rawset(MARKS_KEY, marksText());
        } catch (Throwable ignored) {
        }
    }

    // The n entries (stride floats each) nearest player 0, for a shader's fixed array
    private static float[] nearestN(float[] all, int n, int stride) {
        int count = all.length / stride;
        if (count <= n) return all;
        zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.players[0];
        if (p == null) return java.util.Arrays.copyOf(all, n * stride);
        float px = p.getX(), py = p.getY();
        Integer[] idx = new Integer[count];
        for (int i = 0; i < count; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            float ax = all[a * stride] - px, ay = all[a * stride + 1] - py, bx = all[b * stride] - px, by = all[b * stride + 1] - py;
            return Float.compare(ax * ax + ay * ay, bx * bx + by * by);
        });
        float[] out = new float[n * stride];
        for (int i = 0; i < n; i++) System.arraycopy(all, idx[i] * stride, out, i * stride, stride);
        return out;
    }


    /**
     * Where the snow's surface is for a character, zombie or body standing in it, in levels above its own feet, for
     * the model shaders' snow line. Lying down they rest on the snow they pressed flat, so less of them goes under.
     * Main thread.
     */
    public static float surfaceFor(zombie.iso.IsoMovingObject o) {
        if (o == null || com.pzoptimizer.pzoe.GroundSnow.depthNow() <= 0.001f) return 0f;
        float z = o.getZ();
        if (z >= 0.5f || z < -0.5f) return 0f;
        float h = depthAt(o.getX(), o.getY(), z);
        if (h <= 0.015f) return 0f;
        // Feet press into what they stand on: a little under the surface round them
        h *= 0.85f;
        boolean lying = o.isOnFloor() || o instanceof zombie.iso.objects.IsoDeadBody
            || o instanceof zombie.characters.IsoGameCharacter c && c.isSitOnGround()
            || o instanceof zombie.characters.IsoZombie zz && zz.isCrawling();
        if (lying) h *= 0.3f;
        return h - (z - (float) Math.floor(z + 0.03f));
    }

    /** A vehicle ploughing through deep snow packs its whole width down like a well used path. Game thread. */
    public static void plow(float x, float y, float dirX, float dirY, float halfLen, float halfWid) {
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (len < 1e-5f) return;
        dirX /= len;
        dirY /= len;
        float sx = -dirY, sy = dirX;
        for (float a = -halfLen; a <= halfLen + 1e-4f; a += 0.4f) {
            for (float b = -halfWid; b <= halfWid + 1e-4f; b += 0.4f) trample(x + dirX * a + sx * b, y + dirY * a + sy * b, 2f);
        }
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
            + ";wading=" + (p != null && slot(p).wade ? 1 : 0) + ";snowPaths=" + patches.size() + ";snowCleared=" + clearedAt.size()
            + ";snowMelts=" + melts.size();
    }
}
