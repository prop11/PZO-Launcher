package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.weather.WeatherLook;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.lwjgl.opengl.GL20;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

public final class WeatherGround {
    private static final int SIDE = 33, MAX_MARKS = 4096, MARKS_PER_CHUNK = 64;
    private static final IsoDirections[] CARDINAL = {IsoDirections.N, IsoDirections.W, IsoDirections.S, IsoDirections.E};
    private static final Map<Long, Mark> marks = new HashMap<>();
    private static final int[] cursors = new int[4];
    private static final FloatBuffer upload = ByteBuffer.allocateDirect(MARKS_PER_CHUNK * 4 * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static volatile Map<Long, float[]> published = Map.of();
    private static IsoCell world;
    private static final java.util.concurrent.atomic.AtomicIntegerArray observerLevel = new java.util.concurrent.atomic.AtomicIntegerArray(4);
    private static long nextScan, nextPublish;
    private static int ingressBudget;
    private static double minute;
    static volatile boolean leaks, drainage, debris, wanted;

    private static final class Mark {
        final int x, y, z;
        float leak, pool, litter, drainRate = 1f;
        float ox, oy, lx, ly, urbanShare;
        double touched;
        long rainScan, stormScan;

        Mark(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private WeatherGround() {}

    // Rain through roof holes: the game's weather mask leaves out every square of the building you're in, so rain
    // never fell through a missing roof. Squares open to the sky are let back in. Cached for 2 s a square, since the
    // mask asks about the same squares several times a frame.
    static final String MASK = "zombie/iso/weather/fx/WeatherFxMask";
    private static final String OWNER = "com/pzoptimizer/pzoe/WeatherGround";
    private static final HashMap<Long, Long> skyCache = new HashMap<>();
    private static long skyFrame;

    /** Hook: head of WeatherFxMask.isInPlayerBuilding (main thread). True = treat the square as outside. */
    public static boolean openSky(IsoGridSquare square) {
        // Not gated on rain: the game keeps the mask until you move, so it has to be right before the rain starts
        if (!wanted || !leaks || square == null || !square.isInARoom() || square.z < 0) return false;
        try {
            long now = System.nanoTime();
            if (now - skyFrame > 2_000_000_000L) {
                skyFrame = now;
                if (skyCache.size() > 20000) skyCache.clear();
            }
            long key = key(square.x, square.y, square.z);
            Long cached = skyCache.get(key);
            if (cached != null && now - (cached >>> 1) < 2_000_000_000L) return (cached & 1L) != 0;
            boolean open = IsoWorld.instance != null && RainRunoff.exposedFloor(IsoWorld.instance.currentCell, square);
            skyCache.put(key, (now << 1) | (open ? 1L : 0L));
            return open;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean patchMask(com.pzoptimizer.shaded.asm.tree.ClassNode node) {
        var method = com.pzoptimizer.AsmSupport.findMethod(node, "isInPlayerBuilding", "(Lzombie/iso/IsoGridSquare;III)Z");
        if (method == null) return false;
        var hook = new com.pzoptimizer.shaded.asm.tree.InsnList();
        var go = new com.pzoptimizer.shaded.asm.tree.LabelNode();
        hook.add(new com.pzoptimizer.shaded.asm.tree.VarInsnNode(com.pzoptimizer.shaded.asm.Opcodes.ALOAD, 0));
        hook.add(new com.pzoptimizer.shaded.asm.tree.MethodInsnNode(com.pzoptimizer.shaded.asm.Opcodes.INVOKESTATIC, OWNER,
            "openSky", "(Lzombie/iso/IsoGridSquare;)Z", false));
        hook.add(new com.pzoptimizer.shaded.asm.tree.JumpInsnNode(com.pzoptimizer.shaded.asm.Opcodes.IFEQ, go));
        hook.add(new com.pzoptimizer.shaded.asm.tree.InsnNode(com.pzoptimizer.shaded.asm.Opcodes.ICONST_0));
        hook.add(new com.pzoptimizer.shaded.asm.tree.InsnNode(com.pzoptimizer.shaded.asm.Opcodes.IRETURN));
        hook.add(go);
        method.instructions.insert(hook);
        return true;
    }

    private static long key(int x, int y, int z) {
        return ((long) (z + 32) << 52) | ((long) (x & 0x3ffffff) << 26) | (y & 0x3ffffffL);
    }

    private static Mark at(int x, int y, int z) {
        long key = key(x, y, z);
        Mark mark = marks.get(key);
        if (mark == null && marks.size() < MAX_MARKS) {
            mark = new Mark(x, y, z);
            mark.touched = minute;
            marks.put(key, mark);
        }
        return mark;
    }

    static void frame(long now) {
        if (!wanted || !(leaks || drainage || debris)) {
            if (!marks.isEmpty()) marks.clear();
            if (!published.isEmpty()) published = Map.of();
            return;
        }
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell != world) {
            world = cell;
            marks.clear();
            published = Map.of();
            java.util.Arrays.fill(cursors, 0);
            nextScan = nextPublish = 0;
        }
        if (cell == null || now < nextScan || GameTime.isGamePaused()) return;
        nextScan = now + 100_000_000L;
        double previous = minute;
        minute = GameTime.getInstance().getWorldAgeHours() * 60.0;
        float elapsed = (float) Math.max(0, Math.min(15, minute - previous));
        float precipitation = WeatherLook.isSnow() ? 0f : WeatherLook.precipitation();
        ingressBudget = 6;
        float wind = WeatherLook.wind();
        double angle = WeatherLook.windAngleRadians();
        float windX = (float) Math.cos(angle), windY = (float) Math.sin(angle);
        for (int i = 0; i < IsoPlayer.players.length; i++) {
            var player = IsoPlayer.players[i];
            if (player == null || player.getCurrentSquare() == null) continue;
            int z = player.getCurrentSquare().z;
            observerLevel.set(i, z);
            if (!((leaks || drainage) && precipitation > 0.04f || debris && wind > 0.57f)) continue;
            int anchorX = player.getCurrentSquare().x, anchorY = player.getCurrentSquare().y;
            for (int n = 0; n < 94; n++) {
                int index = cursors[i];
                cursors[i] = (index + 1) % (SIDE * SIDE);
                int x = anchorX + index % SIDE - SIDE / 2, y = anchorY + index / SIDE - SIDE / 2;
                IsoGridSquare square = cell.getGridSquare(x, y, z);
                if (square == null || square.getFloor() == null || square.getWater() != null && square.getWater().isValid()) continue;
                if (leaks && precipitation > 0.04f && square.isInARoom()) leak(cell, square, precipitation, windX * wind, windY * wind, now);
                if (drainage && precipitation > 0.10f && !square.isInARoom()
                    && (square.isOutside() || square.haveRoof)) pool(square, precipitation, now);
                if (debris && wind > 0.57f && square.isOutside()) debris(square, wind, windX, windY, now);
            }
        }
        if (now >= nextPublish) {
            nextPublish = now + 200_000_000L;
            publish(elapsed);
        } else if (elapsed > 0f) {
            decay(elapsed);
        }
    }

    private static void leak(IsoCell cell, IsoGridSquare square, float rain, float wx, float wy, long now) {
        float chance = 0f, ox = 0.5f, oy = 0.5f;
        if (square.z >= 0 && RainRunoff.exposedFloor(cell, square)) chance = rain;
        if (chance == 0f) {
            for (IsoDirections side : CARDINAL) {
                var other = square.getAdjacentSquare(side);
                if (other == null || !other.isOutside() || other.haveRoof) continue;
                float inward = -(side.dx() * wx + side.dy() * wy);
                if (inward < 0.09f) continue;
                var window = square.getWindowTo(other);
                var frame = square.getWindowFrameTo(other);
                boolean aperture = window != null
                    ? !window.isBarricaded() && (window.isOpen() || window.isSmashed() || window.isGlassRemoved())
                    : frame != null && !frame.isBarricaded() && !frame.hasWindow();
                if (!aperture || rain * inward <= chance) continue;
                chance = rain * inward;
                ox = 0.5f + side.dx() * 0.25f;
                oy = 0.5f + side.dy() * 0.25f;
            }
        }
        if (chance <= 0f) return;
        Mark mark = at(square.x, square.y, square.z);
        if (mark == null) return;
        mark.leak = Math.max(mark.leak, Math.min(1f, 0.12f + chance * 1.15f));
        int seed = hash(square.x + (int) (now / 100_000_000L), square.y);
        if (ingressBudget > 0 && chance > 0.10f && (seed & 31) < chance * 12f) {
            float dx = (float) ((seed >>> 8 & 255) / 255.0 - 0.5) * 0.30f;
            float dy = (float) ((seed >>> 16 & 255) / 255.0 - 0.5) * 0.30f;
            RainRunoff.ingress(cell, square.x + ox + dx, square.y + oy + dy, square.z,
                ox == 0.5f && oy == 0.5f ? 0.91f : 0.64f);
            ingressBudget--;
        }
        mark.ox = ox;
        mark.oy = oy;
        mark.rainScan = now;
    }

    private static void pool(IsoGridSquare square, float rain, long now) {
        int seed = hash(square.x, square.y);
        boolean roof = square.haveRoof;
        if (roof || (seed & 7) > 2) return;
        float shade = 0f;
        for (IsoDirections side : CARDINAL) {
            var other = square.getAdjacentSquare(side);
            if (other != null && (other.isInARoom() || square.isWallTo(other) || other.getTree() != null)) shade += 0.25f;
        }
        String name = square.getFloor().getSprite() == null ? null : square.getFloor().getSprite().getName();
        boolean paving = name != null && (name.contains("street") || name.contains("concrete") || name.contains("pavement"));
        if (!paving && shade == 0f && (seed & 31) != 0) return;
        Mark mark = at(square.x, square.y, square.z);
        if (mark == null) return;
        mark.pool = Math.max(mark.pool, Math.min(0.9f, rain * (0.38f + shade * 0.5f + ((seed >>> 8) & 7) * 0.035f)));
        mark.drainRate = (paving ? 1.45f : 0.95f) * (1f - shade * 0.80f);
        mark.ox = 0.30f + ((seed >>> 12) & 15) * 0.024f;
        mark.oy = 0.30f + ((seed >>> 16) & 15) * 0.024f;
        mark.rainScan = now;
    }

    static void runoff(float x, float y, int z) {
        if (!wanted || !drainage || world == null) return;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        var square = world.getGridSquare(ix, iy, z);
        if (square == null || square.isInARoom() || square.getFloor() == null
            || square.getWater() != null && square.getWater().isValid()) return;
        Mark mark = at(ix, iy, z);
        if (mark == null) return;
        mark.ox = Math.max(0.12f, Math.min(0.88f, x - ix));
        mark.oy = Math.max(0.12f, Math.min(0.88f, y - iy));
        mark.pool = Math.min(0.82f, mark.pool + 0.07f);
        mark.drainRate = 0.52f;
        mark.rainScan = System.nanoTime();
    }

    static void landDebris(IsoGridSquare square, float x, float y, float amount, boolean urban) {
        if (!wanted || !debris || world == null || square == null || !square.isOutside() || square.haveRoof
            || square.getFloor() == null || square.has(zombie.iso.SpriteDetails.IsoFlagType.water)) return;
        Mark mark = at(square.x, square.y, square.z);
        if (mark == null) return;
        float blend = amount / Math.max(amount, mark.litter + amount);
        mark.lx += (Math.max(0.14f, Math.min(0.86f, x)) - mark.lx) * blend;
        mark.ly += (Math.max(0.14f, Math.min(0.86f, y)) - mark.ly) * blend;
        addLitter(mark, amount, urban ? 1f : 0f);
    }

    private static void addLitter(Mark mark, float amount, float urbanShare) {
        mark.urbanShare += (urbanShare - mark.urbanShare) * (amount / (mark.litter + amount));
        mark.litter = Math.min(1f, mark.litter + amount);
    }

    private static void debris(IsoGridSquare square, float wind, float wx, float wy, long now) {
        float strongest = 0f;
        IsoDirections edge = null;
        int surface = WindDebris.pavement(square);
        for (IsoDirections side : CARDINAL) {
            var other = square.getAdjacentSquare(side);
            float direction = wx * side.dx() + wy * side.dy();
            float along = direction < 0f ? -direction : 0.65f * direction;
            if (other == null || along <= strongest) continue;
            boolean catches = square.isWallTo(other) || square.isHoppableTo(other)
                || surface != 0 && WindDebris.curb(surface, WindDebris.pavement(other));
            if (!catches) continue;
            strongest = along;
            edge = side;
        }
        if (edge == null) return;
        int seed = hash(square.x, square.y);
        if ((seed & 3) == 0) return;
        Mark mark = at(square.x, square.y, square.z);
        if (mark == null) return;
        if (now - mark.stormScan > 1_000_000_000L) {
            addLitter(mark, (wind - 0.53f) * strongest * 0.30f, surface == 0 ? 0f : 0.68f);
            mark.stormScan = now;
        }
        mark.lx = 0.5f + edge.dx() * 0.31f;
        mark.ly = 0.5f + edge.dy() * 0.31f;
    }

    private static int hash(int x, int y) {
        int n = (x * 73856093) ^ (y * 19349663);
        n ^= n >>> 16;
        return n * 0x7feb352d;
    }

    private static void decay(float elapsed) {
        long now = System.nanoTime();
        for (Iterator<Mark> iterator = marks.values().iterator(); iterator.hasNext();) {
            Mark mark = iterator.next();
            if (now - mark.rainScan >= 3_000_000_000L) {
                mark.leak = Math.max(0f, mark.leak - elapsed * 0.023f);
                mark.pool = Math.max(0f, mark.pool - elapsed * 0.023f * mark.drainRate);
            }
            if (now - mark.stormScan >= 3_000_000_000L) mark.litter = Math.max(0f, mark.litter - elapsed * 0.0008f);
            boolean near = false;
            for (var player : IsoPlayer.players) {
                if (player != null && Math.abs(player.getX() - mark.x) <= 29 && Math.abs(player.getY() - mark.y) <= 29) {
                    near = true;
                    break;
                }
            }
            if ((mark.leak + mark.pool + mark.litter < 0.006f) || (!near && minute - mark.touched > 2.0)) iterator.remove();
            else if (near) mark.touched = minute;
        }
    }

    private static void publish(float elapsed) {
        decay(elapsed);
        if (marks.isEmpty()) {
            published = Map.of();
            return;
        }
        Map<Long, float[]> chunks = new HashMap<>();
        for (var mark : marks.values()) {
            long key = key(Math.floorDiv(mark.x, 8), Math.floorDiv(mark.y, 8), mark.z);
            var values = chunks.computeIfAbsent(key, unused -> new float[MARKS_PER_CHUNK * 4]);
            int tile = ((mark.y & 7) * 8 + (mark.x & 7)) * 4;
            values[tile] = leaks ? mark.leak : 0f;
            values[tile + 1] = drainage ? mark.pool : 0f;
            values[tile + 2] = debris ? mark.litter : 0f;
            values[tile + 3] = quantize(mark.ox) | (quantize(mark.oy) << 5)
                | (quantize(mark.lx) << 10) | (quantize(mark.ly) << 15) | (Math.round(mark.urbanShare * 15f) << 20);
        }
        published = chunks;
    }

    private static int quantize(float offset) {
        return Math.round(Math.max(0f, Math.min(1f, offset)) * 31f);
    }

    static void upload(int locArray, int locInfo, int x, int y, int player) {
        if (locInfo < 0) return;
        if (locArray < 0 || player < 0 || player >= observerLevel.length()) {
            GL20.glUniform1i(locInfo, 0);
            return;
        }
        int level = observerLevel.get(player);
        float[] data = published.get(key(Math.floorDiv(x, 8), Math.floorDiv(y, 8), level));
        if (data != null) {
            upload.clear();
            upload.put(data).flip();
            GL20.glUniform4fv(locArray, upload);
        }
        GL20.glUniform1i(locInfo, data == null ? 0 : level + 33);
    }
}
