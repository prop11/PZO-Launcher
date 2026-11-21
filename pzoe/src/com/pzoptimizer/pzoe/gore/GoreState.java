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
        EXIT = 9;
    public static final int MAX_WOUNDS = 8;

    // Limbs that can come off (cut bits); JAW has no bone of its own, it's a wound plus no bite
    public static final int HEAD = 1, ARM_L_UP = 2, ARM_L_LOW = 4, ARM_R_UP = 8, ARM_R_LOW = 16, LEG_L_UP = 32,
        LEG_L_LOW = 64, LEG_R_UP = 128, LEG_R_LOW = 256, JAW = 512;
    public static final int ARMS_L = ARM_L_UP | ARM_L_LOW, ARMS_R = ARM_R_UP | ARM_R_LOW, LEGS_L = LEG_L_UP | LEG_L_LOW,
        LEGS_R = LEG_R_UP | LEG_R_LOW;

    // Bisect planes (slice): 0 none, 1 waist, 2 chest down to the left, 3 chest down to the right
    public final byte[] part = new byte[MAX_WOUNDS], kind = new byte[MAX_WOUNDS];
    public final float[] along = new float[MAX_WOUNDS], around = new float[MAX_WOUNDS], size = new float[MAX_WOUNDS];
    public int count, next;
    public int cut, slice;
    // Head smashed in (blunt kills, stomps): the skull flattens and opens, it doesn't come off
    public boolean crush;
    // 0 fresh .. 1 a week dead (corpses only)
    public float decay;
    // Bumped on every change, so models and the corpse atlas know to rebuild
    public int version;
    public int hits;
    // Last hit: body part and weapon class (GoreCore.W_*), when a vehicle hit it, when it was last fed on
    public int lastPart = -1, lastWeapon;
    public float vehicleHitAt = -100f, fedAt = -100f, bloodAt = -100f;
    public String uid;
    public float lastSeen;

    /** Adds a wound; past MAX_WOUNDS the oldest goes (bandages and scars are re-made each poll for players). */
    public void wound(int bodyPart, int k, float a, float r, float s) {
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

    // Compact text for corpse mod data and MP: c=cut;s=slice;d=decay;w=part,kind,along,around,size|...
    public String encode() {
        StringBuilder b = new StringBuilder(64).append("c=").append(cut).append(";s=").append(slice).append(";d=")
            .append(Math.round(decay * 100)).append(";k=").append(crush ? 1 : 0).append(";w=");
        for (int i = 0; i < count; i++) {
            if (i > 0) b.append('|');
            b.append(part[i]).append(',').append(kind[i]).append(',').append(Math.round(along[i] * 100)).append(',')
                .append(Math.round(around[i] * 100)).append(',').append(Math.round(size[i] * 100));
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
                    case "w" -> {
                        if (v.isEmpty()) break;
                        for (String w : v.split("\\|")) {
                            String[] f = w.split(",");
                            if (f.length < 5) continue;
                            g.wound(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]) / 100f,
                                Integer.parseInt(f[3]) / 100f, Integer.parseInt(f[4]) / 100f);
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
        return "_G" + cut + (crush ? "k" : "") + "s" + slice + "d" + Math.min(4, (int) (decay * 5f)) + "w" + count + "v" + version;
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
