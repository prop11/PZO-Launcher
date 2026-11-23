package com.pzoptimizer.pzoe.gore;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What PZOE Gore knows about one character or corpse: where it was hurt, which limbs are gone, whether it was cut in
 * two, how far it has rotted. Plain data, no GL, main thread only (the server's main thread on a dedicated server).
 *
 * Wounds sit on a body part (BloodBodyPartType index) at a point along that part's bone and an angle around it, so
 * the render side can place them in any model's own bind pose (the body and every piece of clothing have their own
 * skeleton map). Only characters that were hurt have a record; nothing walks all zombies.
 */
public final class GoreState {
    // Wound kinds; the shader draws each its own way
    public static final int SCRATCH = 0, CUT = 1, BULLET = 2, BLUNT = 3, BITE = 4, BURN = 5, STAB = 6, SCAR = 7, BANDAGE = 8,
        EXIT = 9, BUCKSHOT = 10;
    public static final int HEAD_DENT = 1, HEAD_SPLIT = 2, HEAD_BUCKSHOT = 3, HEAD_CRUSHED = 4;
    public static final int MAX_WOUNDS = 8;

    // Limbs that can come off (cut bits); JAW has no bone of its own, it's a wound plus no bite
    public static final int HEAD = 1, ARM_L_UP = 2, ARM_L_LOW = 4, ARM_R_UP = 8, ARM_R_LOW = 16, LEG_L_UP = 32,
        LEG_L_LOW = 64, LEG_R_UP = 128, LEG_R_LOW = 256, JAW = 512;
    public static final int ARMS_L = ARM_L_UP | ARM_L_LOW, ARMS_R = ARM_R_UP | ARM_R_LOW, LEGS_L = LEG_L_UP | LEG_L_LOW,
        LEGS_R = LEG_R_UP | LEG_R_LOW;

    // Bisect planes (slice): 0 none, 1 waist, 2 chest down to the left, 3 chest down to the right
    public final boolean[] directed = new boolean[MAX_WOUNDS];
    public final byte[] part = new byte[MAX_WOUNDS], kind = new byte[MAX_WOUNDS];
    public final float[] along = new float[MAX_WOUNDS], around = new float[MAX_WOUNDS], size = new float[MAX_WOUNDS];
    private long[] impactPoints;
    public int count, next;
    public int flowPart = -1, flowSide, flowPower, corpseFlowStage;
    public long flowPoint;
    public float flowBorn;
    public int cut, slice;
    public int detachedRoot, detachedOmit;
    public int squashPart = -1;
    public float squashAt;
    public float camHitAt = -100f, camHitX, camHitY, camHitZ;
    public int camHitPart = -1;
    // Head smashed in (blunt kills, stomps): the skull flattens and opens, it doesn't come off
    public boolean crush;
    public int headStyle, headSide;
    // 0 fresh .. 1 a week dead (corpses only)
    public float decay;
    // Bumped on every change, so models and the corpse atlas know to rebuild
    public int version;
    public int hits, combatHits;
    boolean deathEffects;
    public float resolvedAt = -100f;
    // Last hit: body part and weapon class (GoreCore.W_*), when a vehicle hit it, when it was last fed on
    public int lastPart = -1, lastWeapon;
    public float vehicleHitAt = -100f, fedAt = -100f, bloodAt = -100f;
    public String uid;
    public float lastSeen;

    /** Adds a wound; past MAX_WOUNDS the oldest goes (bandages and scars are re-made each poll for players). */
    public void wound(int bodyPart, int k, float a, float r, float s) {
        wound(bodyPart, k, a, r, s, false, 0L);
    }

    private void wound(int bodyPart, int k, float a, float r, float s, boolean aimed, long point) {
        int i;
        if (count < MAX_WOUNDS) i = count++;
        else {
            i = next;
            next = (next + 1) % MAX_WOUNDS;
        }
        part[i] = (byte) bodyPart;
        kind[i] = (byte) k;
        along[i] = a;
        around[i] = r;
        size[i] = s;
        directed[i] = aimed;
        if (point != 0L && impactPoints == null) impactPoints = new long[MAX_WOUNDS];
        if (impactPoints != null) impactPoints[i] = point;
        version++;
    }

    public long point(int i) {
        return impactPoints == null ? 0L : impactPoints[i];
    }

    public float impact(int bodyPart, int k, float a, float r, float s) {
        return impact(bodyPart, k, a, r, s, 0L);
    }

    public float impact(int bodyPart, int k, float a, float r, float s, long point) {
        for (int i = 0; i < count; i++) {
            if (!directed[i] || part[i] != bodyPart || kind[i] != k || !GoreImpact.samePoint(point(i), point)) continue;
            if (point == 0L) {
                float turn = Math.abs(around[i] - r);
                if (Math.min(turn, 1f - turn) > 0.13f || Math.abs(along[i] - a) > 0.23f) continue;
            }
            size[i] = Math.min(1.85f, Math.max(s, size[i]) + s * 0.48f);
            version++;
            return size[i];
        }
        wound(bodyPart, k, a, r, s, true, point);
        return s;
    }

    public void bleed(int part, int side, int power, long point, float hours) {
        if (power < 1 || part < 0 || part > 17 || !Float.isFinite(hours) || hours < 0f) return;
        if (flowStage(hours) >= 3 && flowPower > power) return;
        flowPart = part;
        flowSide = side & 3;
        flowPower = Math.min(4, power);
        flowPoint = point;
        flowBorn = hours;
        version++;
    }

    public int flowStage(double hours) {
        if (flowPart < 0 || flowPower == 0) return 0;
        double elapsed = Math.max(0.0, hours - flowBorn);
        return elapsed < 0.12 ? 5 : elapsed < 0.40 ? 4 : elapsed < 0.85 ? 3 : elapsed < 1.65 ? 2 : elapsed < 3.5 ? 1 : 0;
    }

    public static int decayStage(float decay) {
        return Math.max(0, Math.min(4, (int) (decay * 5f)));
    }

    public void shapeHead(int style, int side) {
        if (headStyle >= style) return;
        headStyle = style;
        headSide = side;
        version++;
    }

    public void clearWounds() {
        if (count == 0) return;
        count = 0;
        next = 0;
        version++;
    }

    public boolean lost(int limb) {
        return (cut & limb) != 0;
    }

    /** Takes a limb off; the lower part of an arm or leg goes with the upper. */
    public boolean sever(int limb) {
        int add = limb;
        if ((limb & ARM_L_UP) != 0) add |= ARM_L_LOW;
        if ((limb & ARM_R_UP) != 0) add |= ARM_R_LOW;
        if ((limb & LEG_L_UP) != 0) add |= LEG_L_LOW;
        if ((limb & LEG_R_UP) != 0) add |= LEG_R_LOW;
        if ((cut | add) == cut) return false;
        cut |= add;
        version++;
        return true;
    }

    // Corpse mod data. Older records omit the head shape and the directed hit flag.
    public String encode() {
        StringBuilder b = new StringBuilder(64).append("c=").append(cut).append(";s=").append(slice).append(";d=")
            .append(Math.round(decay * 100)).append(";k=").append(crush ? 1 : 0);
        if (headStyle > 0) b.append(";h=").append(headStyle * 4 + headSide);
        if (flowPart >= 0 && flowPower > 0) {
            b.append(";f=").append(flowPart).append(',').append(flowSide).append(',').append(flowPower).append(',')
                .append(flowBorn).append(',').append(Long.toHexString(flowPoint));
        }
        b.append(";w=");
        for (int i = 0; i < count; i++) {
            if (i > 0) b.append('|');
            b.append(part[i]).append(',').append(kind[i]).append(',').append(Math.round(along[i] * 100)).append(',')
                .append(Math.round(around[i] * 100)).append(',').append(Math.round(size[i] * 100));
            if (directed[i]) b.append(",1");
            if (point(i) != 0L) b.append(',').append(Long.toHexString(point(i)));
        }
        return b.toString();
    }

    public static GoreState decode(String s) {
        if (s == null || s.isEmpty()) return null;
        GoreState g = new GoreState();
        try {
            for (String kv : s.split(";")) {
                int e = kv.indexOf('=');
                if (e < 0) continue;
                String k = kv.substring(0, e), v = kv.substring(e + 1);
                switch (k) {
                    case "c" -> g.cut = Integer.parseInt(v);
                    case "s" -> g.slice = Integer.parseInt(v);
                    case "d" -> g.decay = Integer.parseInt(v) / 100f;
                    case "k" -> g.crush = "1".equals(v);
                    case "h" -> {
                        int shape = Integer.parseInt(v);
                        if (shape >= HEAD_DENT * 4 && shape < (HEAD_CRUSHED + 1) * 4) {
                            g.headStyle = shape / 4;
                            g.headSide = shape % 4;
                        }
                    }
                    case "f" -> {
                        String[] f = v.split(",");
                        if (f.length != 5) break;
                        long point = Long.parseLong(f[4], 16);
                        g.bleed(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                            GoreImpact.valid(point) ? point : 0L, Float.parseFloat(f[3]));
                    }
                    case "w" -> {
                        if (v.isEmpty()) break;
                        for (String w : v.split("\\|")) {
                            String[] f = w.split(",");
                            if (f.length < 5) continue;
                            long point = f.length > 6 ? Long.parseLong(f[6], 16) : 0L;
                            g.wound(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]) / 100f,
                                Integer.parseInt(f[3]) / 100f, Integer.parseInt(f[4]) / 100f, f.length > 5 && "1".equals(f[5]),
                                GoreImpact.valid(point) ? point : 0L);
                        }
                    }
                    default -> {
                    }
                }
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return g;
    }

    /** Short key of what the picture depends on, for the corpse atlas (decay in 5 steps). */
    public String signature() {
        long hash = 17L;
        for (int i = 0; i < count; i++) {
            hash = hash * 31 + part[i];
            hash = hash * 31 + kind[i];
            hash = hash * 31 + Math.round(along[i] * 100);
            hash = hash * 31 + Math.round(around[i] * 100);
            hash = hash * 31 + Math.round(size[i] * 100);
            hash = hash * 31 + (directed[i] ? 1 : 0);
            hash = hash * 31 + point(i);
        }
        if (corpseFlowStage > 0) hash = ((hash * 31 + flowPart * 64 + flowSide * 8 + flowPower) * 31 + flowPoint) * 31 + corpseFlowStage;
        return "_G" + cut + (crush ? "k" : "") + "s" + slice + "d" + decayStage(decay)
            + "h" + (headStyle * 4 + headSide) + "w" + count + "_" + Long.toHexString(hash);
    }

    // Registry. Zombies are pooled and reused: a record whose uid no longer matches belongs to an earlier zombie.
    private static final Map<Object, GoreState> records = new IdentityHashMap<>();

    public static GoreState of(Object o) {
        if (o == null) return null;
        GoreState g = records.get(o);
        if (g != null && o instanceof zombie.iso.IsoMovingObject m && g.uid != null && !g.uid.equals(m.getUID())) {
            records.remove(o);
            return null;
        }
        return g;
    }

    public static GoreState make(Object o) {
        GoreState g = of(o);
        if (g == null && o != null) {
            g = new GoreState();
            if (o instanceof zombie.iso.IsoMovingObject m) g.uid = m.getUID();
            records.put(o, g);
        }
        return g;
    }

    public static void put(Object o, GoreState g) {
        if (o == null) return;
        if (g == null) records.remove(o);
        else {
            if (o instanceof zombie.iso.IsoMovingObject m) g.uid = m.getUID();
            records.put(o, g);
        }
    }

    public static void forget(Object o) {
        records.remove(o);
    }

    public static int size() {
        return records.size();
    }

    /** Drops records not touched for a while (objects gone from the world). now = GoreCore clock seconds. */
    public static void prune(float now, float keep) {
        for (Iterator<Map.Entry<Object, GoreState>> it = records.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, GoreState> e = it.next();
            Object o = e.getKey();
            boolean gone = o instanceof zombie.iso.IsoMovingObject m && m.getCurrentSquare() == null;
            if (gone && now - e.getValue().lastSeen > keep) it.remove();
        }
    }

    public static Iterable<Map.Entry<Object, GoreState>> all() {
        return records.entrySet();
    }
}
