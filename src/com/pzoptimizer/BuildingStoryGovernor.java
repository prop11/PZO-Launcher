package com.pzoptimizer;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Building Story Randomization Governor.
 * When rolling random building stories during chunk generation (e.g. `RBTrashed.trashHouse`),
 * the game queries `RoomDef.isKidsRoom` repeatedly for every square in the building.
 * Each query re-scans every square of the room against a list of 19 tile names.
 * 
 * BuildingStoryGovernor memoizes room classification for the duration of the building
 * story generation pass, eliminating quadratic tile string comparisons and avoiding 10-100ms
 * frame hitches during world streaming.
 */
public final class BuildingStoryGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong roomScansSaved = new AtomicLong(0);

    public static final Set<String> KIDS_ROOM_TILES;

    static {
        Set<String> s = new HashSet<>();
        s.add("furniture_bedding_01_36");
        s.add("furniture_bedding_01_38");
        s.add("furniture_seating_indoor_02_12");
        s.add("furniture_seating_indoor_02_13");
        s.add("furniture_seating_indoor_02_14");
        s.add("furniture_seating_indoor_02_15");
        s.add("walls_decoration_01_50");
        s.add("walls_decoration_01_51");
        s.add("location_community_school_01_62");
        s.add("location_community_school_01_63");
        s.add("floors_rugs_01_63");
        s.add("floors_rugs_01_64");
        s.add("floors_rugs_01_65");
        s.add("floors_rugs_01_66");
        s.add("floors_rugs_01_67");
        s.add("floors_rugs_01_68");
        s.add("floors_rugs_01_69");
        s.add("floors_rugs_01_70");
        s.add("floors_rugs_01_71");
        KIDS_ROOM_TILES = Collections.unmodifiableSet(s);
    }

    private static final ThreadLocal<PassContext> CONTEXT = ThreadLocal.withInitial(PassContext::new);

    private static final class PassContext {
        int depth = 0;
        final IdentityHashMap<Object, Boolean> memo = new IdentityHashMap<>();
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static void beginPass() {
        if (!active) return;
        PassContext ctx = CONTEXT.get();
        ctx.depth++;
    }

    public static void endPass() {
        if (!active) return;
        PassContext ctx = CONTEXT.get();
        if (ctx.depth > 0) {
            ctx.depth--;
            if (ctx.depth == 0) {
                ctx.memo.clear();
            }
        }
    }

    public static boolean isPassActive() {
        return active && CONTEXT.get().depth > 0;
    }

    /**
     * Retrieves cached kids-room evaluation if available within current pass.
     * Returns null if uncomputed.
     */
    public static Boolean getCachedResult(Object roomDef) {
        if (!active || roomDef == null) return null;
        PassContext ctx = CONTEXT.get();
        if (ctx.depth <= 0) return null;
        Boolean res = ctx.memo.get(roomDef);
        if (res != null) {
            roomScansSaved.incrementAndGet();
        }
        return res;
    }

    /**
     * Stores computed kids-room result for the active building pass.
     */
    public static void putCachedResult(Object roomDef, boolean isKidsRoom) {
        if (!active || roomDef == null) return;
        PassContext ctx = CONTEXT.get();
        if (ctx.depth > 0) {
            ctx.memo.put(roomDef, isKidsRoom);
        }
    }
}
