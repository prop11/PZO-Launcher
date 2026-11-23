package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.pzoe.gore.GoreCore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoFloorBloodSplat;
import zombie.iso.IsoGridSquare;
import zombie.core.properties.IsoPropertyType;

/**
 * Ground Blood's map (PZOE Gore): blood on floors and walls of levels 0-3 as one RGBA8 texture tied to the world,
 * drawn by the chunk shader's post pass, so blood never needs a chunk bake. Same ring as TrailMap (player 0's chunk
 * map), in 12 bands stacked top to bottom: floors of levels 0-3, then per level the north and the west wall faces
 * (a square's face gets T texels along it and T up it). T texels a square from the card's quality (4, 6 or 8).
 *   R = blood (soft, the shader cuts the edge with noise), G = how fresh (1 wet red .. 0 dried brown),
 *   B = how thick (pools darker in the middle), A = not yet washed (rain and snow take it down outdoors).
 *
 * The game's floor splats stay the record. Blood events mark a chunk dirty and a rotating slot check discovers streamed
 * chunks. Rebuilding a slot includes clipped edge stamps from neighbours. Each splat becomes a stretched blob plus a
 * few droplets from a stable hash. PZOE impact deposits, pools and wall runs use bounded saved chunk pages. Tracks
 * still use transient stamps. Drying and washing run on game time.
 */
public final class GoreMap {

    static final int LEVELS = GoreCore.LEVELS, BANDS = LEVELS * 3;
    private static final int MAX_STAMPS = 6144, FLOATS = 12, BATCH = 64;
    private static final int DISCOVER_PER_FRAME = 16, MAX_REBUILDS = 2, MAX_NEW_SPLATS = 128;
    private static final int DENSE_SPLATS = 128;
    private static final int BORDER = 2, BORDER_SIZE = 8 + BORDER * 2;
    // Dense chunks rebuild one vanilla mark per square and level. Each stored wall mark can also have a run.
    private static final int MAX_REBUILD_STAMPS = DENSE_SPLATS * 4 + (BORDER_SIZE * BORDER_SIZE - 64) * LEVELS
        + GoreSurfaces.MAX_MARKS * 10;
    // Fresh blood is dark and matte after this many game hours; the stain itself stays
    static final double DRY_HOURS = 8.0;
    private static final int MAX_TRANSIENT_STAMPS = 768;
    private static final float REACH = 1.7f;

    static volatile boolean wanted;
    private static volatile int quality = 2;
    private static volatile int maxTexSize;

    // Game thread
    private static final float[] stamps = new float[MAX_STAMPS * FLOATS];
    private static int stampCount;
    private static int[] clears = new int[256];
    private static int clearCount;
    private static int grid, ring, tps;
    private static IsoChunkMap chunkMap;
    private static zombie.iso.IsoCell surfaceWorld;
    private static int mapX, mapY, discoveryCursor, generation;
    private static final ArrayDeque<Integer> dirty = new ArrayDeque<>();
    private static final boolean[] borderSeen = new boolean[BORDER_SIZE * BORDER_SIZE * LEVELS];
    private static long[] slotKey;
    private static IsoChunk[] slotChunk;
    private static Object[] slotTail;
    private static GoreSurfaces.Page[] slotPages;
    private static final boolean[] denseSeen = new boolean[64 * LEVELS];
    private static final float[] COS = new float[256], SIN = new float[256];
    private static boolean[] restamp, queued;
    private static int clipSlot = -1;
    private static volatile boolean drawing;
    private static boolean recreate, everything, submittedInitialPaint;
    private static int bloodOption = -1;
    private static double lastHours = -1, dryAcc, washAcc, dryUntil = -1;
    private static boolean painted;
    private static long stampedTotal, reportedStamps, chunkStamps, reportedChunks;
    private static boolean warned;
    // Squares with fresh blood on them (key -> world hours it fell), for bloody footprints and tyres
    private static final Map<Long, Float> fresh = new HashMap<>();
    private static long lastPruneNs;

    // Render thread
    private static int texId, fboId, texW, texH, stampProg, fadeProg, vao;
    private static int locStamps, locSize, locBlock, locFade;
    private static volatile int liveRingTexels, liveTps, readyGeneration;
    private static volatile boolean renderFailed;
    private static final FloatBuffer stampBuf = ByteBuffer.allocateDirect(BATCH * 12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    static {
        for (int i = 0; i < COS.length; i++) {
            double angle = i * Math.PI / 128.0;
            COS[i] = (float) Math.cos(angle);
            SIN[i] = (float) Math.sin(angle);
        }
    }

    private GoreMap() {}

    static void configure(int q) {
        quality = Math.max(1, Math.min(3, q));
    }

    static boolean live() {
        return GoreCore.mapDraws;
    }

    public static boolean trackingGroundBlood() {
        return drawing;
    }

    static int texture() {
        return texId;
    }

    static int ringTexels() {
        return liveRingTexels;
    }

    static int texelsPerSquare() {
        return liveTps;
    }

    static String stateText() {
        return GoreCore.stateText() + ";goreTexels=" + liveTps + ";goreFresh=" + fresh.size();
    }

    private static long key(int wx, int wy) {
        return ((long) wx << 32) ^ (wy & 0xffffffffL);
    }

    private static long sqKey(int x, int y, int z) {
        return ((long) x << 36) ^ ((long) (y & 0xFFFFFFF) << 4) ^ (z & 0xF);
    }

    private static int texelsFor(int q, int g) {
        int t = q <= 1 ? 4 : q >= 3 ? 8 : 6;
        int max = maxTexSize > 0 ? maxTexSize : 4096;
        // Also kept under about 40 MB: at full view distance and 8 texels the 12 bands would be over 70 MB
        while (t > 2 && (g * 8 * t * BANDS > max || (long) (g * 8 * t) * (g * 8 * t) * BANDS * 4L > 40L * 1024 * 1024)) t--;
        return t;
    }

    static double hours() {
        zombie.GameTime gt = zombie.GameTime.getInstance();
        return gt == null ? 0 : gt.getWorldAgeHours();
    }

    /** How fresh the blood on a square is (0 none or dry .. 1 just fell). */
    static float freshAt(int x, int y, int z) {
        Float h = fresh.get(sqKey(x, y, z));
        if (h == null) return 0f;
        return (float) Math.max(0.0, 1.0 - (hours() - h) / (DRY_HOURS * 0.4));
    }

    static void markFresh(float x, float y, int z, double when) {
        long k = sqKey((int) Math.floor(x), (int) Math.floor(y), z);
        Float old = fresh.get(k);
        if (old == null || old < when) fresh.put(k, (float) when);
    }

    /** Once a frame on the game thread (PixelLight.globalFrame). */
    static void frame() {
        try {
            frameInner();
        } catch (Throwable t) {
            renderFailed = true;
            setReplacement(false);
            if (!warned) {
                warned = true;
                PZOLogger.warn("[PZOE gore] map frame failed: " + t);
            }
        }
    }

    private static void frameInner() {
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell != surfaceWorld) {
            surfaceWorld = cell;
            GoreSurfaces.world(cell);
        }
        IsoChunkMap map = cell == null ? null : cell.getChunkMap(0);
        int opt = zombie.core.Core.getInstance().getOptionBloodDecals();
        // Split screen players see other ground than player 0's ring holds: they keep the game's own splats
        boolean draws = wanted && map != null && !renderFailed && zombie.core.PerformanceSettings.fboRenderChunk
            && zombie.characters.IsoPlayer.numPlayers <= 1 && opt != 0;
        if (!draws) {
            if (drawing) {
                drawing = false;
                chunkMap = null;
                slotChunk = null;
                slotTail = null;
                slotPages = null;
                GoreSurfaces.releaseAll();
                GoreSurfaces.rain().resume(hours());
                setReplacement(false);
                GoreCore.dropCleaned();
                dirty.clear();
                fresh.clear();
            }
            stampCount = 0;
            clearCount = 0;
            return;
        }
        int g = IsoChunkMap.chunkGridWidth;
        int t = texelsFor(quality, g);
        if (!drawing || map != chunkMap || g != grid || t != tps || opt != bloodOption) {
            if (map != chunkMap) {
                fresh.clear();
                lastHours = -1;
                dryAcc = washAcc = 0;
            }
            if (drawing && map == chunkMap) GoreSurfaces.releaseAll();
            drawing = true;
            chunkMap = map;
            painted = false;
            dryUntil = -1;
            dryAcc = washAcc = 0;
            grid = g;
            ring = g * 8;
            tps = t;
            bloodOption = opt;
            slotKey = new long[g * g];
            Arrays.fill(slotKey, Long.MIN_VALUE);
            slotChunk = new IsoChunk[g * g];
            slotTail = new Object[g * g];
            slotPages = new GoreSurfaces.Page[g * g];
            restamp = new boolean[g * g];
            queued = new boolean[g * g];
            dirty.clear();
            stampCount = 0;
            discoveryCursor = 0;
            generation++;
            submittedInitialPaint = false;
            recreate = true;
            everything = true;
            setReplacement(false);
        }
        int minX = map.getWorldXMin(), minY = map.getWorldYMin();
        boolean moved = minX != mapX || minY != mapY;
        mapX = minX;
        mapY = minY;
        int slotsToCheck = everything || moved ? g * g : Math.min(g * g, DISCOVER_PER_FRAME);
        everything = false;
        for (int i = 0; i < slotsToCheck; i++) {
            int index = discoveryCursor;
            discoveryCursor = (index + 1) % (g * g);
            discover(map, index % g, index / g);
        }
        double hours = hours();
        float dry = 0f, wash = 0f;
        float dose = GoreSurfaces.rain().advance(hours, GoreFx.rainNow());
        if (painted) washAcc += dose;
        if (painted && lastHours >= 0 && lastHours < dryUntil && hours > lastHours) {
            dryAcc += Math.min(1.0, hours - lastHours) / DRY_HOURS;
        }
        lastHours = hours;
        if (dryAcc >= 2.0 / 255.0) {
            dry = (float) Math.min(1.0, dryAcc);
            dryAcc = 0;
        }
        if (washAcc >= 2.0 / 255.0) {
            wash = (float) Math.min(1.0, washAcc);
            washAcc = 0;
        }
        for (int i = 0; i < MAX_STAMPS; i++) {
            int[] c = GoreCore.pollCleaned();
            if (c == null) break;
            if (c[3] == 0) fresh.remove(sqKey(c[0], c[1], c[2]));
            for (int wy = Math.floorDiv(c[1] - BORDER, 8); wy <= Math.floorDiv(c[1] + BORDER, 8); wy++) {
                for (int wx = Math.floorDiv(c[0] - BORDER, 8); wx <= Math.floorDiv(c[0] + BORDER, 8); wx++) {
                    int slot = slotOf(wx, wy);
                    if (slotKey[slot] == key(wx, wy) && slotChunk[slot] != null) {
                        restamp[slot] = true;
                        queue(slot);
                    }
                }
            }
        }
        flushDirty(hours, opt, true);
        flushDirty(hours, opt, false);
        long now = System.nanoTime();
        if (now - lastPruneNs > 5_000_000_000L) {
            lastPruneNs = now;
            for (Iterator<Float> it = fresh.values().iterator(); it.hasNext(); ) {
                if (hours - it.next() > DRY_HOURS * 0.4) it.remove();
            }
        }
        if (dirty.isEmpty() && readyGeneration == generation) setReplacement(true);
        boolean initialPaint = !submittedInitialPaint && dirty.isEmpty();
        if (stampCount == 0 && clearCount == 0 && dry == 0f && wash == 0f && !recreate && !initialPaint) return;
        zombie.core.SpriteRenderer.instance.drawGeneric(new Batch(generation, ring * tps, tps, recreate, initialPaint,
            Arrays.copyOf(clears, clearCount), dry, wash, Arrays.copyOf(stamps, stampCount * FLOATS)));
        submittedInitialPaint |= initialPaint;
        recreate = false;
        stampCount = 0;
        clearCount = 0;
    }

    static String counters() {
        String s = (stampedTotal - reportedStamps) + " stamps, " + (chunkStamps - reportedChunks) + " chunks re-stamped, " + tps + " texels a square";
        reportedStamps = stampedTotal;
        reportedChunks = chunkStamps;
        return s;
    }

    private static void flushDirty(double hours, int opt, boolean rebuild) {
        int rebuilds = 0;
        for (int left = dirty.size(); left > 0; left--) {
            int slot = dirty.removeFirst();
            queued[slot] = false;
            IsoChunk ch = slotChunk[slot];
            if (ch == null) continue;
            if (restamp[slot] != rebuild) {
                queue(slot);
                continue;
            }
            if (rebuild) {
                if (rebuilds == MAX_REBUILDS || stampCount + MAX_REBUILD_STAMPS > MAX_STAMPS) {
                    queue(slot);
                    continue;
                }
                rebuilds++;
                rebuildSlot(ch, slot, hours, opt);
            } else if (!stampNew(ch, slot, hours, opt)) {
                queue(slot);
            }
        }
    }

    private static void setReplacement(boolean enabled) {
        if (GoreCore.mapDraws == enabled) return;
        GoreCore.mapDraws = enabled;
        RebakeQueue.now(zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_REDRAW);
    }

    private static int slotOf(int wx, int wy) {
        return Math.floorMod(wy, grid) * grid + Math.floorMod(wx, grid);
    }

    private static void queue(int slot) {
        if (queued[slot]) return;
        queued[slot] = true;
        dirty.addLast(slot);
    }

    // IsoChunk calls the invalidation hook on the game thread after appending the splat.
    public static void splatAdded(IsoChunk ch) {
        if (!drawing) return;
        int slot = slotOf(ch.wx, ch.wy);
        if (slotChunk[slot] == ch && slotKey[slot] == key(ch.wx, ch.wy)) queue(slot);
    }

    private static void discover(IsoChunkMap map, int x, int y) {
        int wx = mapX + x, wy = mapY + y;
        int slot = slotOf(wx, wy);
        IsoChunk ch = map.getChunk(x, y);
        if (ch != null && (ch.wx != wx || ch.wy != wy)) ch = null;
        long k = key(wx, wy);
        if (slotKey[slot] != k || slotChunk[slot] != ch) {
            long old = slotKey[slot];
            if (old != Long.MIN_VALUE && slotPages[slot] != null) GoreSurfaces.release((int) (old >> 32), (int) old);
            slotKey[slot] = k;
            slotChunk[slot] = ch;
            slotTail[slot] = null;
            var page = ch == null ? null : GoreSurfaces.page(wx, wy, false);
            slotPages[slot] = page;
            restamp[slot] = ch != null && (!ch.floorBloodSplats.isEmpty() || (page != null && page.count != 0) || hasNeighbourBlood(wx, wy));
            if (!recreate) addSlotClear(slot, BANDS);
            if (restamp[slot]) {
                queue(slot);
                if (page != null || (!recreate && !ch.floorBloodSplats.isEmpty())) dirtyBorder(wx, wy, slot);
            }
        } else if (ch != null && tail(ch) != slotTail[slot]) {
            queue(slot);
        }
    }

    private static IsoChunk loadedChunk(int wx, int wy) {
        IsoChunk ch = chunkMap.getChunk(wx - mapX, wy - mapY);
        return ch != null && ch.wx == wx && ch.wy == wy ? ch : null;
    }

    private static boolean hasNeighbourBlood(int wx, int wy) {
        for (int y = wy - 1; y <= wy + 1; y++) {
            for (int x = wx - 1; x <= wx + 1; x++) {
                if (x == wx && y == wy) continue;
                IsoChunk ch = loadedChunk(x, y);
                if (ch != null && (!ch.floorBloodSplats.isEmpty()
                    || (slotChunk[slotOf(x, y)] == ch && slotPages[slotOf(x, y)] != null))) return true;
            }
        }
        return false;
    }

    private static void dirtyBorder(int wx, int wy, int sourceSlot) {
        for (int y = wy - 1; y <= wy + 1; y++) {
            for (int x = wx - 1; x <= wx + 1; x++) {
                int slot = slotOf(x, y);
                if (slot != sourceSlot && slotKey[slot] == key(x, y) && slotChunk[slot] != null) {
                    restamp[slot] = true;
                    queue(slot);
                }
            }
        }
    }

    private static Object tail(IsoChunk ch) {
        int n = ch.floorBloodSplats.size();
        return n == 0 ? null : ch.floorBloodSplats.get(n - 1);
    }

    private static boolean stampNew(IsoChunk ch, int slot, double hours, int opt) {
        var q = ch.floorBloodSplats;
        int n = q.size();
        Object seen = slotTail[slot];
        if (tail(ch) == seen) return true;
        int first = 0;
        if (seen != null) {
            first = n;
            while (first > 0 && q.get(first - 1) != seen) first--;
            if (first == 0) {
                restamp[slot] = true;
                return false;
            }
        }
        int end = Math.min(n, first + MAX_NEW_SPLATS);
        for (int i = first; i < end; i++) {
            if (stampCount + 4 > MAX_STAMPS) return false;
            IsoFloorBloodSplat b = q.get(i);
            stampSplat(ch, b, hours, opt, true);
            slotTail[slot] = b;
        }
        return end == n;
    }

    private static void rebuildSlot(IsoChunk ch, int slot, double hours, int opt) {
        addSlotClear(slot, BANDS);
        clipSlot = slot;
        try {
            var q = ch.floorBloodSplats;
            int n = q.size();
            if (n <= DENSE_SPLATS) {
                for (int i = 0; i < n; i++) stampSplat(ch, q.get(i), hours, opt, true);
            } else {
                Arrays.fill(denseSeen, false);
                for (int i = n - 1; i >= 0; i--) {
                    var splat = q.get(i);
                    int x = (int) Math.floor(splat.x), y = (int) Math.floor(splat.y), z = (int) Math.floor(splat.z);
                    if ((x | y | z) < 0 || x >= 8 || y >= 8 || z >= LEVELS) continue;
                    int tile = (z * 8 + y) * 8 + x;
                    if (!denseSeen[tile]) denseSeen[tile] = stampSplat(ch, splat, hours, opt, false);
                }
            }
            var page = slotPages[slot];
            if (page != null) {
                for (int i = 0; i < page.count; i++) paint(ch, page, i, hours);
            }
            stampNeighbours(ch, hours, opt);
            slotTail[slot] = tail(ch);
            restamp[slot] = false;
            chunkStamps++;
        } finally {
            clipSlot = -1;
        }
    }

    private static void stampNeighbours(IsoChunk ch, double hours, int opt) {
        Arrays.fill(borderSeen, false);
        int x0 = ch.wx * 8, y0 = ch.wy * 8;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int wx = ch.wx + dx, wy = ch.wy + dy;
                IsoChunk n = loadedChunk(wx, wy);
                if (n == null) continue;
                var q = n.floorBloodSplats;
                for (int i = q.size() - 1; i >= 0; i--) {
                    IsoFloorBloodSplat b = q.get(i);
                    float x = wx * 8 + b.x - x0, y = wy * 8 + b.y - y0;
                    int z = (int) Math.floor(b.z);
                    if (x < -REACH || x >= 8 + REACH || y < -REACH || y >= 8 + REACH || z < 0 || z >= LEVELS) continue;
                    int cell = (z * BORDER_SIZE + (int) Math.floor(y) + BORDER) * BORDER_SIZE + (int) Math.floor(x) + BORDER;
                    if (!borderSeen[cell]) borderSeen[cell] = stampSplat(n, b, hours, opt, false);
                }
                int slot = slotOf(wx, wy);
                var page = slotChunk[slot] == n ? slotPages[slot] : null;
                if (page == null) continue;
                for (int i = 0; i < page.count; i++) {
                    int loc = page.loc[i];
                    if (GoreSurfaces.type(loc) != GoreSurfaces.FLOOR) continue;
                    float x = wx * 8 + GoreSurfaces.x(loc) - x0, y = wy * 8 + GoreSurfaces.y(loc) - y0;
                    if (x >= -REACH && x < 8 + REACH && y >= -REACH && y < 8 + REACH) paint(n, page, i, hours);
                }
            }
        }
    }

    private static void addSlotClear(int slot, int bands) {
        int block = 8 * tps, rt = ring * tps;
        for (int l = 0; l < bands; l++) {
            if (clearCount + 4 > clears.length) clears = Arrays.copyOf(clears, clears.length * 2);
            clears[clearCount++] = (westBand(l) ? slot / grid : slot % grid) * block;
            clears[clearCount++] = (westBand(l) ? slot % grid : slot / grid) * block + l * rt;
            clears[clearCount++] = block;
            clears[clearCount++] = block;
        }
    }

    private static boolean westBand(int band) {
        return band >= LEVELS && ((band - LEVELS) & 1) != 0;
    }

    private static boolean porous(IsoGridSquare sq) {
        if (sq == null) return false;
        String material = sq.getProperties().get(IsoPropertyType.FOOTSTEP_MATERIAL);
        if (material == null) {
            var floor = sq.getFloor();
            if (floor != null && floor.getProperties() != null) {
                material = floor.getProperties().get(IsoPropertyType.FOOTSTEP_MATERIAL);
                if (material == null) material = floor.getProperties().get(IsoPropertyType.FLOOR_MATERIAL);
            }
        }
        return "Grass".equalsIgnoreCase(material) || "Dirt".equalsIgnoreCase(material) || "Sand".equalsIgnoreCase(material)
            || "Carpet".equalsIgnoreCase(material) || "Gravel".equalsIgnoreCase(material) || "Snow".equalsIgnoreCase(material);
    }

    private static float freshness(double now, float birth, boolean porous) {
        return (float) Math.max(0.0, (porous ? 0.55 : 1.0) - Math.max(0.0, now - birth) / DRY_HOURS);
    }

    private static int hash(int h) {
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    private static float unit(int h) {
        return (h >>> 8) / 16777216f;
    }

    private static boolean stampSplat(IsoChunk ch, IsoFloorBloodSplat b, double hours, int opt, boolean detail) {
        int lvl = (int) Math.floor(b.z);
        if (lvl < 0 || lvl >= LEVELS || b.type < 0) return false;
        if (b.index >= 1 && b.index <= 10 && opt >= 1 && opt <= IsoChunk.renderByIndex.length
            && IsoChunk.renderByIndex[opt - 1][b.index - 1] == 0) return false;
        float x = ch.wx * 8 + b.x, y = ch.wy * 8 + b.y;
        float unwashed = GoreSurfaces.rain().remaining(b.worldAge);
        IsoGridSquare sq = ch.getGridSquare((int) Math.floor(b.x), (int) Math.floor(b.y), lvl);
        if (unwashed <= 0f && sq != null && sq.isOutside()) return false;
        boolean absorb = porous(sq);
        float fr = freshness(hours, b.worldAge, absorb);
        if (fr > 0.4f && (sq == null || !sq.isOutside() || unwashed > 0.35f)) markFresh(x, y, lvl, b.worldAge);
        int h = hash(Float.floatToIntBits(x) * 31 + Float.floatToIntBits(y) * 17 + b.type);
        float size, thick;
        int drops;
        if (b.type < 8) {
            size = 0.09f + 0.07f * unit(h);
            thick = 0.25f;
            drops = unit(hash(h + 1)) > 0.6f ? 1 : 0;
        } else if (b.type < 16) {
            size = 0.18f + 0.1f * unit(h);
            thick = 0.55f;
            drops = 2;
        } else {
            size = 0.28f + 0.14f * unit(h);
            thick = 0.85f;
            drops = 3;
        }
        if (absorb) {
            size *= 1.20f;
            thick *= 0.42f;
            drops = Math.min(drops, 1);
        }
        if (!detail) drops = 0;
        else if (ch.floorBloodSplats.size() > DENSE_SPLATS) drops = Math.min(drops, 1);
        int ang = hash(h + 2) & 255;
        float dx = COS[ang], dy = SIN[ang];
        float stretch = 1f + 0.6f * unit(hash(h + 3));
        put(x, y, lvl, dx, dy, size * stretch, size, 1f, fr, thick, unwashed);
        for (int i = 0; i < drops; i++) {
            int hi = hash(h + 10 + i);
            int a = (ang + (int) ((unit(hi) - 0.5f) * 57f)) & 255;
            float ax = COS[a], ay = SIN[a];
            float dist = size * stretch * (1.15f + 0.9f * unit(hash(hi + 1)));
            float r = 0.03f + 0.05f * unit(hash(hi + 2)) * (0.5f + size);
            put(x + ax * dist, y + ay * dist, lvl, ax, ay, r * 1.7f, r, 0.9f, fr, 0.1f, unwashed);
        }
        return true;
    }

    // Stamps from GoreFx (game thread). Levels outside 0-3 are left to the game's own splats.

    /** A round-ish blob on the floor: a landed drop, a gib's mark, a pool (radius in squares). */
    static void blob(float x, float y, int lvl, float r, float freshness, float thick) {
        if (!drawing || stampCount >= MAX_TRANSIENT_STAMPS || lvl < 0 || lvl >= LEVELS) return;
        put(x, y, lvl, 1f, 0f, r, r, 1f, freshness, thick, 1f);
        if (freshness > 0.4f && r > 0.12f) markFresh(x, y, lvl, hours());
    }

    /** A streak along (dx, dy): footprints, drag smears, tyre lines, spray landing at speed. */
    static void streak(float x, float y, int lvl, float dx, float dy, float halfLen, float halfWid, float amount, float freshness) {
        if (!drawing || stampCount >= MAX_TRANSIENT_STAMPS || lvl < 0 || lvl >= LEVELS) return;
        put(x, y, lvl, dx, dy, halfLen, halfWid, amount, freshness, 0.15f, 1f);
    }

    static boolean deposit(float x, float y, int z, float dx, float dy, float length, float width, float thickness) {
        if (!drawing || z < 0 || z >= LEVELS) return false;
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y);
        IsoChunk ch = loadedChunk(sx >> 3, sy >> 3);
        if (ch == null) return false;
        IsoGridSquare square = ch.getGridSquare(sx & 7, sy & 7, z);
        if (square == null || !square.isSolidFloor()) return false;
        boolean absorb = porous(square);
        int angle = dx == 0f && dy == 0f ? 0 : (int) Math.round(Math.atan2(dy, dx) * 128.0 / Math.PI) & 255;
        return record(ch, GoreSurfaces.loc(x - ch.wx * 8, y - ch.wy * 8, z, GoreSurfaces.FLOOR),
            GoreSurfaces.shape(angle, length, width, 1f, thickness, absorb));
    }

    static boolean wall(float along, int row, float h, int z, int face, float radius) {
        if (!drawing || z < 0 || z >= LEVELS) return false;
        float high = Math.max(0.12f, Math.min(0.87f, h));
        boolean north = face == 0;
        float x = north ? along : row + high, y = north ? row + high : along;
        IsoChunk ch = loadedChunk((int) Math.floor(x) >> 3, (int) Math.floor(y) >> 3);
        return ch != null && record(ch, GoreSurfaces.loc(x - ch.wx * 8, y - ch.wy * 8, z, north ? GoreSurfaces.NORTH : GoreSurfaces.WEST),
            GoreSurfaces.shape(0, Math.max(0.10f, radius), Math.max(0.09f, radius * 0.8f), 1f, 0.38f, false));
    }

    public static boolean takeVanillaWall(IsoGridSquare square, boolean north, int textureSeed, float alpha) {
        int seed = hash(textureSeed ^ (square.getX() * 31 + square.getY()));
        float along = (north ? square.getX() : square.getY()) + 0.24f + 0.52f * unit(seed);
        int row = north ? square.getY() : square.getX();
        float height = 0.34f + 0.31f * unit(hash(seed));
        return wall(along, row, height, square.getZ(), north ? 0 : 1, 0.12f + Math.max(0f, Math.min(alpha, 1f)) * 0.045f);
    }

    private static boolean record(IsoChunk ch, int loc, long shape) {
        int slot = slotOf(ch.wx, ch.wy);
        if (slotChunk[slot] != ch) return false;
        var page = slotPages[slot];
        if (page == null) slotPages[slot] = page = GoreSurfaces.page(ch.wx, ch.wy, true);
        if (page == null) return false;
        double now = hours();
        int i = page.add(loc, shape, (float) now);
        if (i == GoreSurfaces.Page.FULL && page.reclaimWashed(ch)) i = page.add(loc, shape, (float) now);
        if (i >= 0) {
            if (restamp[slot] || stampCount + 2 > MAX_TRANSIENT_STAMPS) {
                restamp[slot] = true;
                queue(slot);
                if (GoreSurfaces.type(loc) == GoreSurfaces.FLOOR) {
                    float x = GoreSurfaces.x(loc), y = GoreSurfaces.y(loc);
                    if (x < REACH || x >= 8 - REACH || y < REACH || y >= 8 - REACH) dirtyBorder(ch.wx, ch.wy, slot);
                }
            } else {
                paint(ch, page, i, now);
            }
        }
        return true;
    }

    private static void paint(IsoChunk ch, GoreSurfaces.Page page, int i, double now) {
        int loc = page.loc[i], type = GoreSurfaces.type(loc), z = GoreSurfaces.level(loc);
        long shape = page.shape[i];
        float x = ch.wx * 8 + GoreSurfaces.x(loc), y = ch.wy * 8 + GoreSurfaces.y(loc);
        var square = ch.getGridSquare((int) GoreSurfaces.x(loc), (int) GoreSurfaces.y(loc), z);
        if (square == null) return;
        float rain = GoreSurfaces.rain().remaining(page.born[i]);
        if (rain <= 0f && square.isOutside()) return;
        boolean absorb = GoreSurfaces.porous(shape);
        float fresh = freshness(now, page.born[i], absorb);
        float len = GoreSurfaces.length(shape), width = GoreSurfaces.width(shape), amount = GoreSurfaces.amount(shape);
        float thick = GoreSurfaces.thickness(shape);
        if (type == GoreSurfaces.FLOOR) {
            int angle = GoreSurfaces.angle(shape);
            if (fresh > 0.4f && len > 0.12f && (!square.isOutside() || rain > 0.35f)) markFresh(x, y, z, page.born[i]);
            float stretch = absorb ? 1.18f : 1f;
            put(x, y, z, COS[angle], SIN[angle], len * stretch, width * stretch, amount, thick <= 0.14f ? fresh * 0.8f : fresh,
                absorb ? thick * 0.42f : thick, rain);
            return;
        }

        boolean north = type == GoreSurfaces.NORTH;
        if (square.getWall(north) == null) return;
        float along = north ? x : y, height = north ? y : x;
        float row = (float) Math.floor(height), top = height - row;
        int band = LEVELS + z * 2 + (north ? 0 : 1);
        // Use the wall's own tile and height so the run cannot spill onto the next face.
        along = (float) Math.floor(along) + Math.max(0.17f, Math.min(0.83f, along - (float) Math.floor(along)));
        put(along, height, band, 0f, 1f, Math.min(len, Math.min(top, 1f - top) * 0.76f), width, amount, fresh, thick, rain);
        if (top > 0.20f) {
            float bottom = Math.max(0.045f, top - 0.30f);
            float mid = (top + bottom) * 0.5f;
            put(along, row + mid, band, 0f, 1f, (top - bottom) * 0.40f, Math.max(0.055f, width * 0.52f),
                amount * 0.78f, fresh, thick * 0.50f, rain);
        }
    }

    public static boolean visibleVanilla(IsoFloorBloodSplat splat, boolean outside) {
        return !outside || GoreSurfaces.rain().remaining(splat.worldAge) > 0.18f;
    }

    public static boolean hasSurface(IsoGridSquare sq, boolean wall) {
        if (!trackingGroundBlood() || slotChunk == null || sq.getZ() < 0 || sq.getZ() >= LEVELS) return false;
        var ch = sq.getChunk();
        if (ch == null) return false;
        int slot = slotOf(ch.wx, ch.wy);
        if (slotChunk[slot] != ch) return false;
        var page = slotPages[slot];
        return page != null && page.visible(sq.getX() & 7, sq.getY() & 7, sq.getZ(), wall, sq.isOutside());
    }

    public static void cleanSurface(IsoGridSquare sq, boolean onlyWall) {
        if (sq.getZ() < 0 || sq.getZ() >= LEVELS || sq.getChunk() == null) return;
        var ch = sq.getChunk();
        GoreSurfaces.world(sq.getCell());
        boolean mapped = drawing && surfaceWorld == sq.getCell() && slotChunk != null;
        int slot = mapped ? slotOf(ch.wx, ch.wy) : -1;
        mapped = mapped && slotChunk[slot] == ch;
        var page = mapped ? slotPages[slot] : GoreSurfaces.page(ch.wx, ch.wy, false);
        if (page != null) page.clean(sq.getX() & 7, sq.getY() & 7, sq.getZ(), onlyWall);
        if (!mapped) GoreSurfaces.release(ch.wx, ch.wy);
    }

    static void saveSurfaces() {
        var world = zombie.iso.IsoWorld.instance;
        if (world != null) {
            GoreSurfaces.world(world.currentCell);
            GoreSurfaces.save();
        }
    }

    private static void put(float x, float y, int band, float dx, float dy, float halfLen, float halfWid, float r, float g, float b, float a) {
        if (stampCount >= MAX_STAMPS) return;
        if (clipSlot < 0
            && (x < mapX * 8 || x >= (mapX + grid) * 8 || y < mapY * 8 || y >= (mapY + grid) * 8)) return;
        painted = true;
        if (g > 0f) dryUntil = Math.max(dryUntil, lastHours + g * DRY_HOURS);
        float lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-10f) {
            dx = 1f;
            dy = 0f;
        } else if (Math.abs(1f - lenSq) > 1e-5f) {
            float invLen = 1f / (float) Math.sqrt(lenSq);
            dx *= invLen;
            dy *= invLen;
        }
        int o = stampCount * FLOATS;
        stamps[o] = x;
        stamps[o + 1] = y;
        stamps[o + 2] = band;
        stamps[o + 3] = dx;
        stamps[o + 4] = dy;
        stamps[o + 5] = halfLen;
        stamps[o + 6] = halfWid;
        stamps[o + 7] = r;
        stamps[o + 8] = g;
        stamps[o + 9] = b;
        stamps[o + 10] = a;
        stamps[o + 11] = clipSlot;
        stampCount++;
        stampedTotal++;
    }

    private static final class Batch extends TextureDraw.GenericDrawer {
        final int generation, ringTexels, tps;
        final boolean recreate, initialPaint;
        final int[] clears;
        final float dry, wash;
        final float[] stamps;

        Batch(int generation, int ringTexels, int tps, boolean recreate, boolean initialPaint, int[] clears, float dry, float wash, float[] stamps) {
            this.generation = generation;
            this.ringTexels = ringTexels;
            this.tps = tps;
            this.recreate = recreate;
            this.initialPaint = initialPaint;
            this.clears = clears;
            this.dry = dry;
            this.wash = wash;
            this.stamps = stamps;
        }

        @Override
        public void render() {
            try {
                draw(this);
            } catch (Throwable t) {
                renderFailed = true;
                PZOLogger.warn("[PZOE gore] " + t);
            }
        }
    }

    private static final String VERTEX_STAMP = "#version 140\n"
        + "uniform vec4 uStamps[" + (BATCH * 3) + "];\nuniform vec2 uSize;\nout vec2 vLocal;\nout vec4 vValue;\nflat out vec2 vClip;\n"
        + "void main()\n{\n"
        + "    vec2 c[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));\n"
        + "    vec4 a = uStamps[gl_InstanceID * 3];\n"
        + "    vec4 v = uStamps[gl_InstanceID * 3 + 1];\n    vClip = v.zw;\n"
        + "    vValue = uStamps[gl_InstanceID * 3 + 2];\n"
        + "    vLocal = c[gl_VertexID] * 1.25;\n"
        + "    vec2 p = a.xy + a.zw * vLocal.x + v.xy * vLocal.y;\n"
        + "    gl_Position = vec4(p / uSize * 2.0 - 1.0, 0.0, 1.0);\n}\n";
    // A soft blob; A (unwashed) is the stamp's value wherever it covers, so new blood is never shown washed
    private static final String FRAGMENT_STAMP = "#version 140\nin vec2 vLocal;\nin vec4 vValue;\nflat in vec2 vClip;\nuniform float uBlock;\nout vec4 fragColor;\n"
        + "void main()\n{\n    if (vClip.x >= 0.0 && any(notEqual(floor(gl_FragCoord.xy / uBlock), vClip))) discard;\n    float e = length(vLocal);\n"
        + "    float m = 1.0 - smoothstep(0.3, 1.2, e);\n"
        + "    float cover = smoothstep(0.0, 0.15, m);\n"
        + "    fragColor = vec4(vValue.r * m, vValue.g * cover, vValue.b * m, vValue.a * cover);\n}\n";
    private static final String VERTEX_FULL = "#version 130\nvoid main()\n{\n"
        + "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
        + "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n}\n";
    private static final String FRAGMENT_FADE = "#version 130\nuniform vec4 uFade;\nvoid main()\n{\n    gl_FragColor = uFade;\n}\n";

    private static int program(String vs, String fs) {
        int prog = GL20.glCreateProgram();
        for (int k = 0; k < 2; k++) {
            int s = GL20.glCreateShader(k == 0 ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(s, k == 0 ? vs : fs);
            GL20.glCompileShader(s);
            if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
                PZOLogger.warn("[PZOE gore] shader didn't compile: " + GL20.glGetShaderInfoLog(s, 2048).trim());
                GL20.glDeleteShader(s);
                GL20.glDeleteProgram(prog);
                return 0;
            }
            GL20.glAttachShader(prog, s);
            GL20.glDeleteShader(s);
        }
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
            PZOLogger.warn("[PZOE gore] shader didn't link: " + GL20.glGetProgramInfoLog(prog, 2048).trim());
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static final int GL12_CLAMP_TO_EDGE = 0x812F;

    private static void draw(Batch b) {
        if (stampProg == 0) {
            maxTexSize = GlGet.integer(GL11.GL_MAX_TEXTURE_SIZE);
            stampProg = program(VERTEX_STAMP, FRAGMENT_STAMP);
            fadeProg = program(VERTEX_FULL, FRAGMENT_FADE);
            vao = GL30.glGenVertexArrays();
            if (stampProg != 0) {
                locStamps = GL20.glGetUniformLocation(stampProg, "uStamps");
                locSize = GL20.glGetUniformLocation(stampProg, "uSize");
                locBlock = GL20.glGetUniformLocation(stampProg, "uBlock");
            }
            if (fadeProg != 0) locFade = GL20.glGetUniformLocation(fadeProg, "uFade");
        }
        if (stampProg == 0 || fadeProg == 0) {
            renderFailed = true;
            return;
        }
        int rt = b.ringTexels, w = rt, h = rt * BANDS;
        int prevFbo = GlGet.integer(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GlGet.integer(GL30.GL_VERTEX_ARRAY_BINDING);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_VIEWPORT_BIT
            | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        try {
            boolean fresh = false;
            if (texId == 0 || b.recreate || texW != w || texH != h) {
                if (texId != 0) GL11.glDeleteTextures(texId);
                if (fboId != 0) GL30.glDeleteFramebuffers(fboId);
                int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
                GL13.glActiveTexture(GL13.GL_TEXTURE15);
                int prevTex = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
                texId = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                // Bands are stacked in y: the shader keeps its reads inside one band itself
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
                GL13.glActiveTexture(prevActive);
                fboId = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texId, 0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    renderFailed = true;
                    PZOLogger.warn("[PZOE gore] blood map framebuffer is incomplete");
                    return;
                }
                texW = w;
                texH = h;
                fresh = true;
            } else {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            }
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glViewport(0, 0, w, h);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            if (fresh) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            } else if (b.clears.length > 0) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                for (int i = 0; i < b.clears.length; i += 4) {
                    GL11.glScissor(b.clears[i], b.clears[i + 1], b.clears[i + 2], b.clears[i + 3]);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                }
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindVertexArray(vao);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            if ((b.dry > 0f || b.wash > 0f) && !fresh) {
                // Drying takes G down, rain and snow take A down; the shader only applies A outdoors
                GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
                GL20.glUseProgram(fadeProg);
                GL11.glColorMask(false, b.dry > 0f, false, b.wash > 0f);
                GL20.glUniform4f(locFade, 0f, b.dry, 0f, b.wash);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                GL11.glColorMask(true, true, true, true);
            }
            if (b.stamps.length > 0 && locStamps >= 0) {
                GL14.glBlendEquation(GL14.GL_MAX);
                GL20.glUseProgram(stampProg);
                GL20.glUniform2f(locSize, w, h);
                GL20.glUniform1f(locBlock, 8 * b.tps);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                for (int l = 0; l < BANDS; l++) {
                    GL11.glScissor(0, l * rt, w, rt);
                    drawBand(b, l, rt);
                }
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            liveRingTexels = rt;
            liveTps = b.tps;
            if (b.initialPaint) readyGeneration = b.generation;
        } finally {
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
        }
    }

    // Stamps of one band, with a copy one ring over wherever a stamp reaches past the ring's edge (the map wraps)
    private static void drawBand(Batch b, int band, int rt) {
        float t = b.tps;
        int n = b.stamps.length / FLOATS, inBatch = 0;
        int g = rt / (8 * b.tps);
        stampBuf.clear();
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS;
            if ((int) b.stamps[o + 2] != band) continue;
            double fx = b.stamps[o] * t, fy = b.stamps[o + 1] * t;
            float cx = (float) (Math.floorMod((long) Math.floor(fx), (long) rt) + (fx - Math.floor(fx)));
            float cy = (float) (Math.floorMod((long) Math.floor(fy), (long) rt) + (fy - Math.floor(fy)));
            float dx = b.stamps[o + 3], dy = b.stamps[o + 4];
            float hl = b.stamps[o + 5] * t, hw = b.stamps[o + 6] * t;
            float value = b.stamps[o + 7];
            int clip = (int) b.stamps[o + 11];
            int clipX = clip < 0 ? -1 : westBand(band) ? clip / g : clip % g;
            int clipY = clip < 0 ? -1 : (westBand(band) ? clip % g : clip / g) + band * g;
            // Under a texel a droplet can't show: keep it a texel wide but fainter, the shader's cut then drops it
            if (hw < 0.75f) {
                value *= hw / 0.75f;
                hl = Math.max(hl, 0.75f);
                hw = 0.75f;
            }
            float reach = Math.max(hl, hw) * 1.25f + 1f;
            for (int sy = -1; sy <= 1; sy++) {
                float py = cy + sy * rt;
                if (py + reach < 0 || py - reach > rt) continue;
                for (int sx = -1; sx <= 1; sx++) {
                    float px = cx + sx * rt;
                    if (px + reach < 0 || px - reach > rt) continue;
                    stampBuf.put(px).put(py + band * rt).put(dx * hl).put(dy * hl);
                    stampBuf.put(-dy * hw).put(dx * hw).put(clipX).put(clipY);
                    stampBuf.put(value).put(b.stamps[o + 8]).put(b.stamps[o + 9]).put(b.stamps[o + 10]);
                    if (++inBatch == BATCH) {
                        flush(inBatch);
                        inBatch = 0;
                    }
                }
            }
        }
        if (inBatch > 0) flush(inBatch);
    }

    private static void flush(int count) {
        stampBuf.flip();
        GL20.glUniform4fv(locStamps, stampBuf);
        org.lwjgl.opengl.GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, count);
        stampBuf.clear();
    }
}
