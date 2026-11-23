package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.pzoe.gore.GoreAtlas;
import com.pzoptimizer.pzoe.gore.GoreCore;
import com.pzoptimizer.pzoe.gore.GoreImpact;
import com.pzoptimizer.pzoe.gore.GoreListener;
import com.pzoptimizer.pzoe.gore.GoreState;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.SpriteRenderer;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDeadBody;
import zombie.vehicles.BaseVehicle;

/**
 * PZOE Gore's moving parts on the client: blood sprays, mist, gibs, severed limbs, flies, arterial spurts, pools that
 * spread under bodies, bloody footprints, drag and crawl smears, red tyre tracks, dripping weapons, blood on windscreens,
 * player wounds from their own body damage, and the single-player feel effects (hit-stop, kill cam, lens blood).
 *
 * Particles: one fixed pool in plain arrays (no object per gib or drop), moved once a frame, drawn at the end of the
 * game's rain splash pass for each level with the game's depth (walls in front hide them) and its sprite batching
 * (same texture, one draw). What lands goes into GoreMap: drops become stains, gibs leave a mark, spray that meets a
 * wall marks the wall. Far from player 0 or off screen nothing is spawned but the stain.
 *
 * Main thread only. Every card's switch comes in through PZOApi ("gore"); with every card off it does nothing.
 */
public final class GoreFx implements GoreListener {
    static final GoreFx INSTANCE = new GoreFx();

    // Cards
    static volatile boolean sprayOn, gibsOn, poolsOn, tracksOn, weaponOn, vehicleOn, decayOn, fliesOn, playerWoundsOn, ragdollBreaks,
        hitStopOn, squashOn, killCamOn, lensOn, detailedGibsOn;
    private static volatile boolean active;
    static volatile float amount = 1f;
    static volatile int quality = 2, palette;

    static boolean anyOn() {
        return active;
    }

    // Particle kinds
    private static final int DROP = 0, MIST = 1, GIB = 2, BONE = 3, ARM = 4, LEG = 5, HEAD = 6, FLY = 7, BRAIN = 8, WEAPON_DROP = 9;
    private static final int MAX = 4096;
    private static final float GRAVITY = 4.2f, RANGE = 30f;
    private static final float[] px = new float[MAX], py = new float[MAX], pz = new float[MAX], vx = new float[MAX], vy = new float[MAX],
        vz = new float[MAX], age = new float[MAX], life = new float[MAX], size = new float[MAX], ground = new float[MAX];
    private static final byte[] kind = new byte[MAX], resting = new byte[MAX];
    private static int count;

    private static final int MAX_PARTS = 24, MAX_DETAILED_PARTS = 12;
    private static final byte[] partSlot = new byte[MAX];
    private static final int[] partParticle = new int[MAX_PARTS], partChunkId = new int[MAX_PARTS];
    private static final IsoChunk[] partChunk = new IsoChunk[MAX_PARTS];
    private static final short[] partTile = new short[MAX_PARTS];
    private static final GoreDetached.Part[] partCapture = new GoreDetached.Part[MAX_PARTS];
    private static final byte[] partAngle = new byte[MAX_PARTS];
    private static final boolean[] partFlipped = new boolean[MAX_PARTS], partBounced = new boolean[MAX_PARTS],
        partDetailed = new boolean[MAX_PARTS];
    private static int activeParts, detailedParts;
    private static IsoCell partCell;
    private static long nextPartCleanup;

    private static int combatGibletFrame = Integer.MIN_VALUE, combatGiblets;
    private static long spawned;

    private static final Texture[] tex = new Texture[13];
    private static final String[] TEX = {"gore_drop", "gore_mist", "gore_gib_0", "gore_bone", "gore_limb_arm", "gore_limb_leg",
        "gore_limb_head", "gore_fly", "gore_gib_1", "gore_gib_2", "gore_gib_3", "gore_brain", "gore_px"};

    private static int lastFrame = Integer.MIN_VALUE;
    private static long lastNs, reportNs;
    private static float clock, trackTimer, slowTimer, pollTimer;
    private static String problem;

    // Palette: fresh blood colour per choice (red, dark, green, black)
    private static final float[][] PAL = {{0.56f, 0.068f, 0.048f}, {0.15f, 0.031f, 0.026f}, {0.12f, 0.32f, 0.05f}, {0.06f, 0.05f, 0.05f}};

    private GoreFx() {}

    static void install() {
        GoreCore.fx = INSTANCE;
    }

    /** "spray=1;gibs=1;..." from the cards (PZOE_Settings.lua). */
    static void configure(String s) {
        if (s == null) return;
        int oldAtlasLook = GoreAtlas.look();
        for (String kv : s.split(";")) {
            int e = kv.indexOf('=');
            if (e < 0) continue;
            String k = kv.substring(0, e), v = kv.substring(e + 1);
            boolean on = "1".equals(v) || "true".equals(v);
            float f;
            try {
                f = Float.parseFloat(v);
            } catch (NumberFormatException ex) {
                f = on ? 1f : 0f;
            }
            switch (k) {
                case "spray" -> sprayOn = on;
                case "wounds" -> GoreCore.woundsLook = on;
                case "runoff" -> GoreCore.runoffLook = on;
                case "detailedGibs" -> detailedGibsOn = on;
                case "gibs" -> {
                    if (on && !gibsOn) {
                        GoreParts.warm();
                        texture(GIB, 0);
                        texture(GIB, 1);
                        texture(GIB, 2);
                        texture(GIB, 3);
                        texture(BONE, 0);
                        texture(BRAIN, 0);
                        texture(ARM, 0);
                        texture(LEG, 0);
                        texture(HEAD, 0);
                    }
                    if (!on && activeParts != 0) clearParts();
                    gibsOn = on;
                    GoreCore.giblets = on;
                }
                case "pools" -> poolsOn = on;
                case "tracks" -> tracksOn = on;
                case "weapon" -> weaponOn = on;
                case "vehicle" -> vehicleOn = on;
                case "decay" -> {
                    decayOn = on;
                    GoreAtlas.decayOn = on;
                }
                case "flies" -> fliesOn = on;
                case "playerWounds" -> playerWoundsOn = on;
                case "dismember" -> GoreCore.dismemberLook = on;
                case "ragdoll" -> ragdollBreaks = on;
                case "hitStop" -> hitStopOn = on;
                case "squash" -> squashOn = on;
                case "killCam" -> {
                    killCamOn = on;
                    if (!on && GoreKillCam.active()) GoreKillCam.cancel();
                }
                case "lens" -> lensOn = on;
                case "amount" -> amount = Math.max(0.25f, Math.min(3f, f / 100f));
                case "quality" -> quality = Math.max(1, Math.min(3, (int) f));
                case "woundQuality" -> GoreModel.configure((int) f);
                case "palette" -> palette = Math.max(0, Math.min(3, (int) f));
                default -> {
                }
            }
        }
        boolean single = (hitStopOn || squashOn || killCamOn) && singlePlayer();
        GoreCore.hitLook = GoreCore.woundsLook || GoreCore.dismemberLook || sprayOn || gibsOn || weaponOn || (single && killCamOn)
            || (single && (hitStopOn || squashOn));
        GoreCore.deathLook = GoreCore.dismemberLook || poolsOn || lensOn || (single && killCamOn);
        GoreCore.vehicleLook = sprayOn || vehicleOn || GoreCore.dismemberLook;
        if (oldAtlasLook != GoreAtlas.look()) {
            for (IsoDeadBody body : GoreAtlas.seen.keySet()) GoreAtlas.refreshTexture(body);
        }
        if (!sprayOn) spurts.clear();
        if (!poolsOn) pools.clear();
        if (!vehicleOn) glass.clear();
        if (!tracksOn) {
            walkers.clear();
            tyres.clear();
        }
        if (!lensOn) lens = 0f;
        if (!hitStopOn) stopUntil = -1f;
        if (!fliesOn) flies.clear();
        for (int i = count - 1; i >= 0; i--) {
            int k = kind[i];
            boolean on = switch (k) {
                case DROP, MIST -> sprayOn;
                case WEAPON_DROP -> weaponOn;
                case FLY -> fliesOn;
                default -> gibsOn;
            };
            if (!on) remove(i);
        }
        active = GoreCore.hitLook || GoreCore.deathLook || GoreCore.vehicleLook || tracksOn || decayOn
            || fliesOn || playerWoundsOn || ragdollBreaks;
        if (!active) lastNs = 0L;
    }

    static float rainNow() {
        try {
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            return Math.max(cm.getPrecipitationIntensity(), com.pzoptimizer.pzoe.weather.WeatherLook.precipitation());
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static boolean singlePlayer() {
        return !zombie.network.GameClient.client && !zombie.network.GameServer.server;
    }

    private static IsoPlayer player0() {
        return IsoPlayer.players[0];
    }

    private static boolean near(float x, float y, float r) {
        IsoPlayer p = player0();
        if (p == null) return false;
        float dx = x - p.getX(), dy = y - p.getY();
        return dx * dx + dy * dy < r * r;
    }

    private static int cap() {
        return quality <= 1 ? 1024 : quality == 2 ? 2048 : MAX;
    }

    private static float rnd() {
        return (float) Math.random();
    }

    // Spawning

    private static int add(int k, float x, float y, float z, float ux, float uy, float uz, float lf, float sz) {
        int limit = cap();
        if (count >= limit || (count >= limit * 3 / 4 && (k == MIST || !near(x, y, 9f)))) return -1;
        int i = count++;
        kind[i] = (byte) k;
        px[i] = x;
        py[i] = y;
        pz[i] = z;
        vx[i] = ux;
        vy[i] = uy;
        vz[i] = uz;
        age[i] = 0f;
        life[i] = lf;
        size[i] = sz;
        resting[i] = 0;
        partSlot[i] = 0;
        ground[i] = (float) Math.floor(z + 0.02f);
        spawned++;
        return i;
    }

    /** Drops thrown along (dx, dy) in a cone; strength scales count and speed. */
    static void spray(float x, float y, float z, float dx, float dy, float strength, float spread) {
        if (!sprayOn) return;
        float scale = GoreCore.bloodScale() * amount;
        int n = (int) (strength * 4.5f * Math.min(scale, 1.5f) * (quality == 1 ? 0.5f : quality == 3 ? 1.3f : 1f));
        if (!near(x, y, 9f)) n = (n + 1) / 2;
        if (n == 0 || count >= cap()) return;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) {
            double a = Math.random() * Math.PI * 2;
            dx = (float) Math.cos(a);
            dy = (float) Math.sin(a);
        } else {
            dx /= len;
            dy /= len;
        }
        double direction = Math.atan2(dy, dx);
        for (int i = 0; i < n; i++) {
            double a = direction + (rnd() - 0.5) * spread;
            float sp = (0.22f + 0.53f * rnd()) * (0.7f + 0.3f * Math.min(strength, 1.5f));
            if (add(DROP, x + (rnd() - 0.5f) * 0.1f, y + (rnd() - 0.5f) * 0.1f, z,
                (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, 0.07f + 0.30f * rnd(), 3f, 0.4f + 0.6f * rnd()) < 0) break;
        }
    }

    static void mist(float x, float y, float z, int n) {
        if (!sprayOn || !near(x, y, 12f)) return;
        for (int i = 0; i < n; i++) {
            if (add(MIST, x, y, z, (rnd() - 0.5f) * 0.32f, (rnd() - 0.5f) * 0.32f, 0.1f * rnd(),
                0.6f + 0.4f * rnd(), 0.8f + rnd()) < 0) break;
        }
    }

    static void gibs(float x, float y, float z, int n, float speed) {
        if (!gibsOn) return;
        for (int i = 0; i < n; i++) {
            double a = Math.random() * Math.PI * 2;
            float sp = speed * (0.5f + rnd());
            add(i % 6 == 5 ? BONE : GIB, x, y, z, (float) Math.cos(a) * sp * 0.34f, (float) Math.sin(a) * sp * 0.34f, 0.20f + 0.38f * rnd(), 40f,
                0.7f + 0.5f * rnd());
        }
    }

    /** Bits of brain from a smashed or blown-apart head; they stick where they land. */
    static void brains(float x, float y, float z, int n, float speed) {
        if (!gibsOn) return;
        for (int i = 0; i < n; i++) {
            double a = Math.random() * Math.PI * 2;
            float sp = speed * (0.4f + rnd());
            add(BRAIN, x, y, z, (float) Math.cos(a) * sp * 0.28f, (float) Math.sin(a) * sp * 0.28f, 0.14f + 0.32f * rnd(), 60f, 0.6f + 0.6f * rnd());
        }
    }

    private static void severedParts(IsoGameCharacter victim, int cuts, int cutBefore, float dx, float dy) {
        if (!gibsOn) return;
        int roots = GoreParts.cutRoots(cuts & ~cutBefore);
        if (roots == 0) return;
        float x = victim.getX(), y = victim.getY(), floor = victim.getZ();
        IsoCell cell = victim.getCell();
        if (cell == null) return;
        if (activeParts > 0 && partCell != cell) clearParts();
        var square = victim.getCurrentSquare();
        IsoChunk chunk = square == null ? null : square.getChunk();
        int chunkId = partLoadId(chunk);
        if (chunkId < Short.MIN_VALUE) return;
        boolean close = near(x, y, 12f);
        while (roots != 0) {
            int root = Integer.lowestOneBit(roots);
            roots ^= root;
            if (activeParts >= MAX_PARTS) {
                GoreMap.blob(x, y, (int) Math.floor(floor), 0.10f, 1f, 0.45f);
                break;
            }
            int k = root == GoreState.HEAD ? HEAD : (root & (GoreState.LEGS_L | GoreState.LEGS_R)) != 0 ? LEG : ARM;
            float height = victim.isOnFloor() ? 0.05f : k == HEAD ? 0.58f : k == LEG ? 0.19f : 0.42f;
            float speed = 1.2f + rnd();
            int i = add(k, x, y, floor + height, (dx * speed + rnd() - 0.5f) * 0.28f,
                (dy * speed + rnd() - 0.5f) * 0.28f, 0.18f + 0.32f * rnd(), 150f, 1f);
            if (i < 0) {
                GoreMap.blob(x, y, (int) Math.floor(floor), 0.10f, 1f, 0.45f);
                break;
            }

            boolean shortened = GoreParts.shortened(root, cutBefore);
            int appearance = close && detailedParts < MAX_DETAILED_PARTS ? GoreParts.snapshot(victim, root, shortened) : -1;
            GoreDetached.Part capture = detailedGibsOn && close && quality >= 2 && GoreCore.dismemberLook && zombie.core.PerformanceSettings.fboRenderChunk
                ? GoreDetached.request(victim, root, shortened) : null;
            rememberPart(i, cell, chunk, chunkId, root, shortened, appearance, capture);
        }
    }

    private static void rememberPart(int i, IsoCell cell, IsoChunk chunk, int chunkId, int root, boolean shortened, int appearance,
                                     GoreDetached.Part capture) {
        int slot = 0;
        while (partParticle[slot] != 0) slot++;
        partSlot[i] = (byte) (slot + 1);
        partParticle[slot] = i + 1;
        partCell = cell;
        partChunk[slot] = chunk;
        partChunkId[slot] = chunkId;
        partTile[slot] = (short) (appearance >= 0 ? appearance : GoreParts.neutralTile(root, shortened));
        partCapture[slot] = capture;
        partAngle[slot] = (byte) (rnd() * GoreParts.TUMBLE_STEPS);
        partFlipped[slot] = GoreParts.left(root);
        partBounced[slot] = false;
        partDetailed[slot] = appearance >= 0;
        activeParts++;
        if (partDetailed[slot]) detailedParts++;
    }

    private static int partLoadId(IsoChunk chunk) {
        if (chunk == null || chunk.refs.isEmpty()) return Integer.MIN_VALUE;
        try {
            return chunk.getLoadID();
        } catch (IllegalStateException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static void clearParts() {
        for (int slot = 0; slot < MAX_PARTS; slot++) {
            if (partParticle[slot] != 0) remove(partParticle[slot] - 1);
        }
        nextPartCleanup = 0;
    }

    private static void checkParts(IsoCell cell, long now) {
        if (cell != partCell) {
            clearParts();
            return;
        }
        if (now < nextPartCleanup) return;
        nextPartCleanup = now + 250_000_000L;
        for (int slot = 0; slot < MAX_PARTS; slot++) {
            int i = partParticle[slot] - 1;
            if (i < 0) continue;
            IsoChunk chunk = cell.getChunk((int) Math.floor(px[i]) >> 3, (int) Math.floor(py[i]) >> 3);
            if (chunk != partChunk[slot] || partLoadId(chunk) != partChunkId[slot]) remove(i);
        }
    }

    private static float heightOf(IsoGameCharacter c, int part) {
        if (c.isOnFloor() || c.isDead()) return 0.05f;
        return switch (part) {
            case GoreCore.P_HEAD, GoreCore.P_NECK -> 0.6f;
            case GoreCore.P_TORSO_UP, GoreCore.P_BACK, GoreCore.P_UPPERARM_L, GoreCore.P_UPPERARM_R -> 0.48f;
            case GoreCore.P_TORSO_LOW, GoreCore.P_FOREARM_L, GoreCore.P_FOREARM_R, GoreCore.P_HAND_L, GoreCore.P_HAND_R -> 0.38f;
            case GoreCore.P_GROIN, GoreCore.P_THIGH_L, GoreCore.P_THIGH_R -> 0.28f;
            default -> 0.12f;
        };
    }

    // Arterial spurts: pulsing for a few seconds from a stump or an opened neck
    private static final class Spurt {
        IsoGameCharacter chr;
        float until, next, h;
    }

    private static final ArrayList<Spurt> spurts = new ArrayList<>();

    private static void spurt(IsoGameCharacter c, float h, float seconds) {
        if (!sprayOn || spurts.size() > 24) return;
        Spurt s = new Spurt();
        s.chr = c;
        s.h = h;
        s.until = clock + seconds;
        s.next = clock;
        spurts.add(s);
    }

    // Pools spreading under bodies
    private static final class Pool {
        float x, y, start, max, last;
        int lvl;
        boolean done;
    }

    private static final ArrayList<Pool> pools = new ArrayList<>();

    private static void pool(float x, float y, float z, float max) {
        if (!poolsOn || pools.size() > 40 || GoreCore.bloodScale() <= 0f) return;
        Pool p = new Pool();
        p.x = x;
        p.y = y;
        p.lvl = (int) Math.floor(z + 0.02f);
        p.start = clock;
        p.max = max * Math.min(1.5f, 0.6f + 0.4f * GoreCore.bloodScale());
        pools.add(p);
    }

    // GoreListener

    private static float lastPlayerHit;

    @Override
    public void hit(IsoGameCharacter v, IsoGameCharacter att, int part, int k, int weapon, boolean openingHit, GoreImpact contact) {
        if (!near(v.getX(), v.getY(), RANGE)) return;
        float dx = 0f, dy = 0f;
        if (att != null) {
            dx = v.getX() - att.getX();
            dy = v.getY() - att.getY();
        }
        float h = heightOf(v, part);
        float x = contact == null ? v.getX() : contact.worldX(), y = contact == null ? v.getY() : contact.worldY();
        float z = contact == null ? v.getZ() + h : contact.worldZ();
        float strength = switch (k) {
            case GoreState.BULLET -> weapon == GoreCore.W_SHOTGUN ? 1.4f : 0.9f;
            case GoreState.CUT -> 1.1f;
            case GoreState.BLUNT -> 0.7f;
            case GoreState.STAB -> 0.8f;
            default -> 0.4f;
        };
        spray(x, y, z, dx, dy, openingHit ? strength * 0.5f : strength, k == GoreState.BLUNT ? 2.0f : 0.95f);
        if (k == GoreState.BULLET && (part == GoreCore.P_HEAD || weapon == GoreCore.W_SHOTGUN)) {
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            float offset = 0.08f / Math.max(0.08f, distance);
            mist(x + dx * offset, y + dy * offset, z, openingHit ? 1 : 2);
        }
        if (att instanceof IsoPlayer p && p.isLocalPlayer()) {
            lastPlayerHit = clock;
            if (killCamOn && contact != null && singlePlayer()) {
                GoreState g = GoreState.of(v);
                if (g != null) {
                    g.camHitAt = GoreCore.clock();
                    g.camHitPart = part;
                    g.camHitX = x;
                    g.camHitY = y;
                    g.camHitZ = z;
                }
            }
            if ((hitStopOn || squashOn) && singlePlayer() && (k == GoreState.CUT || (k == GoreState.BLUNT && att.isCriticalHit()))) {
                if (hitStopOn) stopUntil = GoreCore.clock() + 0.05f;
                if (squashOn && GoreModel.wanted && near(v.getX(), v.getY(), 12f)) {
                    GoreState g = GoreState.of(v);
                    if (g != null) {
                        g.squashPart = part;
                        g.squashAt = GoreCore.clock();
                        g.version++;
                    }
                }
            }
        }
    }

    @Override
    public void death(IsoGameCharacter v, IsoGameCharacter killer, GoreState g, int taken, int cutBefore, int burst, int weapon, int part, boolean gory) {
        if (!near(v.getX(), v.getY(), RANGE)) return;
        float dx = 0f, dy = 0f;
        if (killer != null) {
            dx = v.getX() - killer.getX();
            dy = v.getY() - killer.getY();
            float l = (float) Math.sqrt(dx * dx + dy * dy);
            if (l > 1e-4f) {
                dx /= l;
                dy /= l;
            }
        }
        float x = v.getX(), y = v.getY(), z = v.getZ();
        // The head's height: standing for a shot, at the floor for a zombie finished off lying down
        float hz = v.isOnFloor() ? 0.06f : 0.6f;
        if (burst == 1) {
            spray(x, y, z + hz, dx, dy, 2.4f, 1.6f);
            mist(x + dx * 0.15f, y + dy * 0.15f, z + hz, 7);
            gibs(x, y, z + hz, 6, 1.9f);
            brains(x + dx * 0.1f, y + dy * 0.1f, z + hz, 9, 1.8f);
        } else if (burst == 2) {
            spray(x, y, z + hz, dx, dy, 1.9f, 4.5f);
            gibs(x, y, z + hz, 5, 1.0f);
            brains(x, y, z + hz, 7, 1.0f);
            spurt(v, hz, 3f);
        }
        if (taken != 0) {
            if (burst != 1) severedParts(v, taken, cutBefore, dx, dy);
            spurt(v, (taken & GoreState.HEAD) != 0 ? 0.55f : 0.4f, 3.5f + 3f * rnd());
            spray(x, y, z + 0.45f, dx, dy, 1.5f, 1.4f);
        }
        if (g.slice != 0) {
            spray(x, y, z + 0.4f, dx, dy, 2f, 2.5f);
            gibs(x, y, z + 0.4f, 5, 1.2f);
        }
        if (gory) pool(x, y, z, 0.22f + 0.05f * Math.min(4, g.count) + (taken != 0 || g.slice != 0 || g.crush ? 0.15f : 0f));
        if (killCamOn && gory && killer instanceof IsoPlayer p && p.isLocalPlayer() && singlePlayer()
            && (burst != 0 || taken != 0 || g.slice != 0 || (weapon == GoreCore.W_GUN && part == GoreCore.P_HEAD && g.lastPart == part))) {
            GoreKillCam.start(v, p, g, part);
        }
        if (killer instanceof IsoPlayer p && p.isLocalPlayer() && lensOn && weapon != GoreCore.W_GUN && weapon != GoreCore.W_SHOTGUN) {
            float ddx = killer.getX() - x, ddy = killer.getY() - y;
            if (ddx * ddx + ddy * ddy < 1.6f * 1.6f && (taken != 0 || burst != 0 || rnd() < 0.25f)) {
                lens = Math.min(1f, lens + 0.55f);
                lensSeed = rnd() * 100f;
                lensAt = clock;
            }
        }
    }

    @Override
    public void severed(IsoGameCharacter v, int limbs, int cutBefore) {
        if (!near(v.getX(), v.getY(), RANGE)) return;
        float z = v.getZ() + ((limbs & (GoreState.LEGS_L | GoreState.LEGS_R)) != 0 ? 0.15f : 0.42f);
        severedParts(v, limbs, cutBefore, rnd() - 0.5f, rnd() - 0.5f);
        spray(v.getX(), v.getY(), z, 0f, 0f, 1.3f, 6.3f);
        spurt(v, z - v.getZ(), 4f);
    }

    // Blood on the glass of the car that did it
    private static final Map<BaseVehicle, float[]> glass = new IdentityHashMap<>();

    @Override
    public void vehicleHit(IsoZombie z, float speed) {
        if (!near(z.getX(), z.getY(), RANGE)) return;
        spray(z.getX(), z.getY(), z.getZ() + 0.35f, 0f, 0f, Math.min(2f, speed / 30f), 6.3f);
        if (!vehicleOn) return;
        IsoCell cell = IsoWorld.instance.currentCell;
        BaseVehicle best = null;
        float bd = 16f;
        for (BaseVehicle v : cell.getVehicles()) {
            float dx = v.getX() - z.getX(), dy = v.getY() - z.getY(), d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = v;
            }
        }
        if (best == null) return;
        float[] s = glass.computeIfAbsent(best, k -> new float[] {0f, rnd() * 50f});
        s[0] = Math.min(1f, s[0] + 0.15f + speed / 120f);
        if (tracksOn) tyres.put(best, new float[] {10f});
    }

    @Override
    public void corpse(IsoDeadBody body, GoreState g) {
    }

    @Override
    public void fed(Object body, GoreState g) {
        if (body instanceof IsoDeadBody b) {
            try {
                zombie.core.skinnedmodel.DeadBodyAtlas.instance.invalidateBodyTexture(b.getAtlasTexture(), b);
            } catch (Throwable ignored) {
            }
            if (near(b.getX(), b.getY(), RANGE)) {
                spray(b.getX(), b.getY(), b.getZ() + 0.1f, 0f, 0f, 0.4f, 6.3f);
                pool(b.getX(), b.getY(), b.getZ(), 0.7f);
            }
        }
    }

    @Override
    public boolean combatGiblet(float x, float y, float z, float dx, float dy) {
        if (!gibsOn || !near(x, y, RANGE)) return false;
        int frame = IsoCamera.frameState.frameCount;
        if (frame != combatGibletFrame) {
            combatGibletFrame = frame;
            combatGiblets = 0;
        }
        if (combatGiblets < 4 && compactGiblet(x, y, z, dx, dy)) combatGiblets++;
        return true;
    }

    @Override
    public boolean giblet(zombie.iso.objects.IsoZombieGiblets gib) {
        if (!gibsOn || !near(gib.getX(), gib.getY(), RANGE)) return false;
        return compactGiblet(gib.getX(), gib.getY(), gib.getZ(), gib.velX, gib.velY);
    }

    private static boolean compactGiblet(float x, float y, float z, float dx, float dy) {
        float speed = (float) Math.sqrt(dx * dx + dy * dy);
        float scale = Math.min(0.65f, 0.8f / Math.max(speed, 0.01f));
        return add(rnd() < 0.2f ? BONE : GIB, x, y, z, dx * scale, dy * scale, 0.20f + 0.38f * rnd(), 40f,
            0.7f + 0.4f * rnd()) >= 0;
    }

    // Feel: hit-stop and kill cam (single player only), lens blood
    private static float stopUntil = -1f;
    static volatile float lens, lensSeed, lensAt;

    @Override
    public float timeScale() {
        if (!singlePlayer() || (!hitStopOn && !killCamOn)) return 1f;
        if (hitStopOn && GoreCore.clock() < stopUntil) return 0.08f;
        if (!killCamOn || !GoreKillCam.active()) return 1f;
        float left = GoreKillCam.remaining();
        return 1f - 0.7f * Math.min(1f, left / 0.2f);
    }

    /** Lens blood for the screen pass: amount, seed, palette, 1 = live. */
    static float[] lensUniform() {
        float a = lensOn ? lens : 0f;
        return new float[] {a, lensSeed, palette, a > 0.003f ? 1f : 0f};
    }

    // Frame: called at the end of the game's rain splash pass per level (RainRunoff's hook)

    public static void render(int player, int z) {
        try {
            int f = IsoCamera.frameState.frameCount;
            if (f != lastFrame) {
                lastFrame = f;
                long now = System.nanoTime();
                float dt = lastNs == 0 ? 0f : Math.min(0.1f, (now - lastNs) / 1e9f);
                lastNs = now;
                clock = GoreCore.clock();
                if (zombie.GameTime.isGamePaused()) dt = 0f;
                // Particles follow game time (slow motion slows them too)
                float gdt = dt * timeScaleNow();
                if (activeParts > 0) checkParts(IsoWorld.instance == null ? null : IsoWorld.instance.currentCell, now);
                update(gdt);
                if (GoreDetached.hasWork()) GoreDetached.bake();
                if (dt > 0f) tick(dt);
                if (now - reportNs > 30_000_000_000L) {
                    reportNs = now;
                    GoreCore.report(now, GoreMap.counters() + ", " + count + " particles, " + spawned + " spawned");
                }
            }
            if (count > 0) draw(player, z);
            if (killCamOn && GoreKillCam.active()) GoreKillCam.draw(player, z);
        } catch (Throwable t) {
            if (problem == null) {
                problem = t.toString();
                PZOLogger.warn("[PZOE gore] fx: " + t);
            }
        }
    }

    private static float timeScaleNow() {
        return INSTANCE.timeScale();
    }

    private static void update(float dt) {
        if (dt <= 0f) return;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) {
            if (activeParts > 0) clearParts();
            count = 0;
            return;
        }
        float fresh = 1f;
        for (int i = 0; i < count; i++) {
            age[i] += dt;
            if (age[i] > life[i]) {
                remove(i--);
                continue;
            }
            int k = kind[i];
            if (k == FLY) {
                float t = age[i] * (3f + size[i]) + size[i] * 10f;
                px[i] = vx[i] + (float) Math.cos(t) * 0.25f * (0.6f + 0.4f * (float) Math.sin(t * 0.37f));
                py[i] = vy[i] + (float) Math.sin(t * 1.3f) * 0.25f;
                pz[i] = vz[i] + 0.08f + 0.05f * (float) Math.sin(t * 2.1f);
                continue;
            }
            if (k == MIST) {
                px[i] += vx[i] * dt;
                py[i] += vy[i] * dt;
                size[i] += dt * 1.5f;
                continue;
            }
            if (resting[i] != 0) continue;
            float ox = px[i], oy = py[i];
            vz[i] -= GRAVITY * dt;
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            pz[i] += vz[i] * dt;
            int lvl = (int) ground[i];
            // Crossing into the next square: a wall in the way stops it and takes the mark
            int sx0 = (int) Math.floor(ox), sy0 = (int) Math.floor(oy), sx1 = (int) Math.floor(px[i]), sy1 = (int) Math.floor(py[i]);
            if (sx0 != sx1 || sy0 != sy1) {
                int slot = partSlot[i] - 1;
                if ((pz[i] > lvl || slot >= 0) && hitWall(cell, i, sx0, sy0, sx1, sy1, ox, oy, lvl)) {
                    if (slot < 0) {
                        remove(i--);
                        continue;
                    }
                    px[i] = ox;
                    py[i] = oy;
                    vx[i] = vy[i] = 0f;
                } else if (slot >= 0 && ((sx0 >> 3) != (sx1 >> 3) || (sy0 >> 3) != (sy1 >> 3))) {
                    IsoChunk chunk = cell.getChunk(sx1 >> 3, sy1 >> 3);
                    int loadId = partLoadId(chunk);
                    if (loadId < Short.MIN_VALUE) {
                        remove(i--);
                        continue;
                    }
                    partChunk[slot] = chunk;
                    partChunkId[slot] = loadId;
                }
            }
            if (pz[i] <= lvl) {
                pz[i] = lvl;
                land(cell, i, lvl, fresh);
                if (isDrop(k)) {
                    remove(i--);
                    continue;
                }
            }
        }
    }

    private static boolean hitWall(IsoCell cell, int i, int sx0, int sy0, int sx1, int sy1, float ox, float oy, int lvl) {
        float h = pz[i] - lvl;
        if (h > 0.98f) return false;
        // North edge of the square entered from above (y grows) or left (y shrinks), west edge likewise
        if (sy1 != sy0) {
            int row = Math.max(sy0, sy1);
            IsoGridSquare sq = cell.getGridSquare(sx0, row, lvl);
            if (sq != null && sq.getWall(true) != null) {
                if (isDrop(kind[i])) GoreMap.wall(ox, row, h, lvl, 0, 0.035f + 0.036f * size[i]);
                return isDrop(kind[i]) || partSlot[i] != 0;
            }
        }
        if (sx1 != sx0) {
            int col = Math.max(sx0, sx1);
            IsoGridSquare sq = cell.getGridSquare(col, sy0, lvl);
            if (sq != null && sq.getWall(false) != null) {
                if (isDrop(kind[i])) GoreMap.wall(oy, col, h, lvl, 1, 0.035f + 0.036f * size[i]);
                return isDrop(kind[i]) || partSlot[i] != 0;
            }
        }
        return false;
    }

    private static void land(IsoCell cell, int i, int lvl, float fresh) {
        int k = kind[i];
        if (isDrop(k)) {
            if (GoreMap.live()) {
                float speed = (float) Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i]);
                float r = 0.035f + 0.03f * size[i];
                float length = speed > 0.9f ? r * (1f + speed * 0.25f) : r;
                if (!GoreMap.deposit(px[i], py[i], lvl, vx[i], vy[i], length, r, 0.2f)) {
                    GoreMap.blob(px[i], py[i], lvl, r, fresh, 0.2f);
                }
            } else if (Math.random() < 0.2) {
                IsoGridSquare sq = cell.getGridSquare((int) Math.floor(px[i]), (int) Math.floor(py[i]), lvl);
                if (sq != null && sq.getChunk() != null) sq.getChunk().addBloodSplat(px[i], py[i], lvl, zombie.core.random.Rand.Next(8));
            }
            return;
        }
        int slot = partSlot[i] - 1;
        if ((slot < 0 || !partBounced[slot]) && Math.abs(vz[i]) > 0.8f && age[i] < 2f && vz[i] < 0f) {
            if (slot >= 0) partBounced[slot] = true;
            vz[i] = -vz[i] * 0.25f;
            vx[i] *= 0.45f;
            vy[i] *= 0.45f;
            pz[i] = lvl + 0.001f;
            if (slot < 0) GoreMap.blob(px[i], py[i], lvl, 0.05f, fresh, 0.5f);
            return;
        }
        if (slot >= 0) partAngle[slot] = (byte) GoreParts.angle(partAngle[slot], age[i]);
        resting[i] = 1;
        vx[i] = vy[i] = vz[i] = 0f;
        float stain = k >= ARM ? 0.19f : 0.08f;
        if (!GoreMap.deposit(px[i], py[i], lvl, 0f, 0f, stain, stain, 0.7f)) {
            GoreMap.blob(px[i], py[i], lvl, stain, fresh, 0.7f);
        }
    }

    private static void remove(int i) {
        int slot = partSlot[i] - 1;
        if (slot >= 0) {
            partParticle[slot] = 0;
            partChunk[slot] = null;
            GoreDetached.release(partCapture[slot]);
            partCapture[slot] = null;
            if (partDetailed[slot]) detailedParts--;
            if (--activeParts == 0) {
                partCell = null;
                nextPartCleanup = 0;
            }
        }
        int j = --count;
        if (i == j) {
            partSlot[i] = 0;
            return;
        }
        px[i] = px[j];
        py[i] = py[j];
        pz[i] = pz[j];
        vx[i] = vx[j];
        vy[i] = vy[j];
        vz[i] = vz[j];
        age[i] = age[j];
        life[i] = life[j];
        size[i] = size[j];
        ground[i] = ground[j];
        kind[i] = kind[j];
        resting[i] = resting[j];
        partSlot[i] = partSlot[j];
        partSlot[j] = 0;
        if (partSlot[i] != 0) partParticle[partSlot[i] - 1] = i + 1;
    }

    // Everything that isn't a particle: spurts, pools, tracks, weapons, glass, flies, rot, player wounds, ragdolls

    private static void tick(float dt) {
        if (lens > 0f) lens = Math.max(0f, lens - dt / 6f);
        for (Iterator<Spurt> it = spurts.iterator(); it.hasNext(); ) {
            Spurt s = it.next();
            if (clock > s.until || s.chr == null) {
                it.remove();
                continue;
            }
            if (clock >= s.next) {
                s.next = clock + 0.7f;
                float left = (s.until - clock) / 6f;
                float h = s.chr.isDead() || s.chr.isOnFloor() ? 0.06f : s.h;
                spray(s.chr.getX(), s.chr.getY(), s.chr.getZ() + h, rnd() - 0.5f, rnd() - 0.5f, 0.6f + left, 1.2f);
            }
        }
        for (Iterator<Pool> it = pools.iterator(); it.hasNext(); ) {
            Pool p = it.next();
            if (clock - p.last < 1.5f) continue;
            p.last = clock;
            float t = Math.min(1f, (clock - p.start) / 90f);
            float r = p.max * (float) Math.sqrt(Math.max(0.05f, t));
            boolean stored = r >= 0.09f && GoreMap.deposit(p.x, p.y, p.lvl, 0f, 0f, r, r, 0.9f);
            if (t >= 1f) {
                if (!stored) {
                    IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare((int) Math.floor(p.x), (int) Math.floor(p.y), p.lvl);
                    if (sq != null && sq.getChunk() != null) sq.getChunk().addBloodSplat(p.x, p.y, p.lvl, 16 + zombie.core.random.Rand.Next(4));
                }
                it.remove();
            } else if (!stored) {
                GoreMap.blob(p.x, p.y, p.lvl, r, 1f, 0.9f);
            }
        }
        trackTimer += dt;
        if (trackTimer >= 0.1f) {
            float tdt = trackTimer;
            trackTimer = 0f;
            if (tracksOn && GoreMap.live()) tracks(tdt);
            if (weaponOn) weapons(tdt);
            if (vehicleOn) vehicles(tdt);
        }
        slowTimer += dt;
        if (slowTimer >= 2f) {
            float sdt = slowTimer;
            slowTimer = 0f;
            if (decayOn || fliesOn || GoreCore.runoffLook) corpses();
            if (ragdollBreaks) ragdolls();
            GoreState.prune(clock, 60f);
            if (glass.size() > 64) glass.clear();
            if (walkers.size() > 400) walkers.clear();
        }
        pollTimer += dt;
        if (pollTimer >= 1f) {
            pollTimer = 0f;
            playerWounds();
        }
    }

    // Bloody footprints, crawl and drag smears
    private static final Map<IsoGameCharacter, float[]> walkers = new IdentityHashMap<>();
    private static final Map<BaseVehicle, float[]> tyres = new IdentityHashMap<>();

    private static void tracks(float dt) {
        IsoCell cell = IsoWorld.instance.currentCell;
        for (zombie.iso.IsoMovingObject o : cell.getObjectList()) {
            if (!(o instanceof IsoGameCharacter c) || c.isDead() || !near(c.getX(), c.getY(), 25f)) continue;
            int lvl = (int) Math.floor(c.getZ() + 0.02f);
            if (lvl < 0 || lvl > 3 || c.getVehicle() != null) continue;
            float x = c.getX(), y = c.getY();
            float[] s = walkers.get(c);
            if (s == null) {
                walkers.put(c, s = new float[] {x, y, 0f, 0f, 1f});
                continue;
            }
            float fr = GoreMap.freshAt((int) Math.floor(x), (int) Math.floor(y), lvl);
            if (fr > 0.3f) s[3] = Math.max(s[3], 6f + 6f * fr);
            float dx = x - s[0], dy = y - s[1];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 1e-4f) continue;
            s[0] = x;
            s[1] = y;
            if (d > 2f) continue;
            boolean crawl = c instanceof IsoZombie z && z.isCrawling();
            boolean dragged = c instanceof IsoZombie z2 && z2.isReanimatedForGrappleOnly();
            GoreState g = GoreState.of(c);
            if ((crawl && g != null && (g.count > 0 || g.cut != 0)) || dragged) {
                s[2] += d;
                if (s[2] >= 0.22f) {
                    s[2] = 0f;
                    GoreMap.streak(x - dx * 0.5f, y - dy * 0.5f, lvl, dx, dy, 0.22f, dragged ? 0.16f : 0.12f, 0.65f, 0.8f);
                }
                continue;
            }
            if (s[3] <= 0f) continue;
            s[2] += d;
            if (s[2] >= 0.42f) {
                s[2] = 0f;
                s[4] = -s[4];
                float nx = dx / d, ny = dy / d;
                float side = 0.07f * s[4];
                GoreMap.streak(x - ny * side, y + nx * side, lvl, nx, ny, 0.09f, 0.045f, Math.min(1f, 0.35f + s[3] / 12f), 0.55f);
                s[3] -= 1f;
            }
        }
        for (Iterator<Map.Entry<IsoGameCharacter, float[]>> it = walkers.entrySet().iterator(); it.hasNext(); ) {
            IsoGameCharacter c = it.next().getKey();
            if (c.getCurrentSquare() == null || c.isDead()) it.remove();
        }
        // Red tyre tracks out of fresh blood
        org.joml.Vector3f fwd = new org.joml.Vector3f();
        for (BaseVehicle v : cell.getVehicles()) {
            if (!near(v.getX(), v.getY(), 25f)) continue;
            float kmh = Math.abs(v.getCurrentSpeedKmHour());
            int lvl = (int) Math.floor(v.getZ() + 0.02f);
            if (kmh < 2f || lvl < 0 || lvl > 3) continue;
            v.getForwardVector(fwd);
            float fx = fwd.x, fy = fwd.z, l = (float) Math.sqrt(fx * fx + fy * fy);
            if (l < 1e-4f) continue;
            fx /= l;
            fy /= l;
            float[] t = tyres.get(v);
            float fr = GoreMap.freshAt((int) Math.floor(v.getX()), (int) Math.floor(v.getY()), lvl);
            if (fr > 0.3f) tyres.put(v, t = new float[] {12f});
            if (t == null || t[0] <= 0f) continue;
            float step = kmh / 3.6f * dt;
            t[0] -= step;
            for (int side = -1; side <= 1; side += 2) {
                float wx = v.getX() - fx * 1.2f - fy * 0.8f * side, wy = v.getY() - fy * 1.2f + fx * 0.8f * side;
                GoreMap.streak(wx, wy, lvl, fx, fy, Math.max(0.15f, step * 0.6f), 0.09f, Math.min(1f, t[0] / 8f), 0.6f);
            }
        }
    }

    // Dripping weapons after a kill; rain rinses the player's weapon
    private static float dripTimer;

    private static void weapons(float dt) {
        float rain = rainNow();
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if (p == null || p.isDead()) continue;
            if (!(p.getPrimaryHandItem() instanceof zombie.inventory.types.HandWeapon w)) continue;
            float bl = w.getBloodLevel();
            if (bl <= 0.01f) continue;
            if (rain > 0.1f && p.isOutside()) w.setBloodLevel(Math.max(0f, bl - dt * rain / 180f));
            dripTimer += dt;
            if (clock - lastPlayerHit < 6f && bl > 0.15f && dripTimer >= 0.35f) {
                dripTimer = 0f;
                zombie.iso.Vector2 f = p.getForwardDirection();
                add(WEAPON_DROP, p.getX() + f.x * 0.35f, p.getY() + f.y * 0.35f, p.getZ() + 0.22f, 0f, 0f, 0f, 2f, 0.35f);
            }
        }
    }

    // Windscreen blood: rain and the wipers (engine running in rain) clear it slowly
    private static void vehicles(float dt) {
        float rain = rainNow();
        for (Iterator<Map.Entry<BaseVehicle, float[]>> it = glass.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BaseVehicle, float[]> e = it.next();
            BaseVehicle v = e.getKey();
            float[] s = e.getValue();
            IsoGridSquare sq = v.getSquare();
            boolean out = sq != null && sq.isOutside();
            if (out && rain > 0.05f) s[0] -= dt * rain * (v.isEngineRunning() ? 1f / 20f : 1f / 90f);
            if (s[0] <= 0f || sq == null) it.remove();
        }
    }

    /** Main thread: a vehicle's windscreen blood (0..1) and its splatter seed, for GoreModel. */
    static float[] glassOf(BaseVehicle v) {
        return vehicleOn ? glass.get(v) : null;
    }

    // Flies on old bodies, rot refresh
    private static final Map<IsoDeadBody, float[]> flies = new IdentityHashMap<>();

    private static void corpses() {
        double hours = GoreMap.hours();
        ArrayList<IsoDeadBody> list = new ArrayList<>(GoreAtlas.seen.keySet());
        for (IsoDeadBody b : list) {
            if (b == null || b.getSquare() == null || !near(b.getX(), b.getY(), 20f)) continue;
            float dtH = b.getDeathTime();
            double age = dtH < 0f ? 0 : hours - dtH;
            GoreState g = GoreState.of(b);
            float decay = GoreAtlas.decayOf(b, hours);
            if (g == null && GoreState.decayStage(decay) > 0) g = GoreState.make(b);
            if (g != null && GoreAtlas.updateStages(g, decay, GoreCore.runoffLook ? hours : Double.POSITIVE_INFINITY)) GoreAtlas.refreshTexture(b);
            if (fliesOn && quality >= 2 && age > 12.0) {
                float[] f = flies.get(b);
                if (f == null || clock > f[0]) {
                    flies.put(b, new float[] {clock + 18f});
                    int n = age > 72.0 ? 9 : 5;
                    for (int i = 0; i < n; i++) {
                        int j = add(FLY, b.getX(), b.getY(), b.getZ(), b.getX() + (rnd() - 0.5f) * 0.5f, b.getY() + (rnd() - 0.5f) * 0.5f,
                            b.getZ(), 19f, rnd() * 3f);
                        if (j < 0) break;
                    }
                }
            }
        }
        if (flies.size() > 128) flies.clear();
    }

    // Ragdolls: a limb that's gone stops pulling on the body (the game's own joint break, off in vanilla)
    private static final Map<IsoGameCharacter, int[]> broken = new IdentityHashMap<>();
    private static final int[][] JOINT = {{GoreState.HEAD, 1}, {GoreState.ARM_L_UP, 6}, {GoreState.ARM_L_LOW, 7}, {GoreState.ARM_R_UP, 8},
        {GoreState.ARM_R_LOW, 9}, {GoreState.LEG_L_UP, 2}, {GoreState.LEG_L_LOW, 3}, {GoreState.LEG_R_UP, 4}, {GoreState.LEG_R_LOW, 5}};

    private static void ragdolls() {
        for (Map.Entry<Object, GoreState> e : GoreState.all()) {
            if (!(e.getKey() instanceof IsoGameCharacter c) || e.getValue().cut == 0) continue;
            try {
                if (!c.isRagdollSimulationActive()) continue;
                int[] done = broken.computeIfAbsent(c, k -> new int[1]);
                int cut = e.getValue().cut;
                for (int[] j : JOINT) {
                    if ((cut & j[0]) == 0 || (done[0] & j[0]) != 0) continue;
                    // The upper joint already freed the whole limb
                    if (j[0] == GoreState.ARM_L_LOW && (cut & GoreState.ARM_L_UP) != 0) continue;
                    if (j[0] == GoreState.ARM_R_LOW && (cut & GoreState.ARM_R_UP) != 0) continue;
                    if (j[0] == GoreState.LEG_L_LOW && (cut & GoreState.LEG_L_UP) != 0) continue;
                    if (j[0] == GoreState.LEG_R_LOW && (cut & GoreState.LEG_R_UP) != 0) continue;
                    zombie.core.physics.Bullet.detachConstraint(c.getID(), j[1]);
                    done[0] |= j[0];
                }
            } catch (Throwable ignored) {
            }
        }
        if (broken.size() > 256) broken.clear();
    }

    // Player wounds from the body damage the health panel shows; healed deep wounds leave scars (saved on the player)
    private static final Map<IsoPlayer, String> lastWoundSig = new IdentityHashMap<>();

    private static void playerWounds() {
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if (p == null || p.getBodyDamage() == null) continue;
            if (!playerWoundsOn || p.isDead()) {
                GoreState g = GoreState.of(p);
                if (g != null && g.count > 0 && !p.isDead()) g.clearWounds();
                continue;
            }
            StringBuilder sig = new StringBuilder();
            ArrayList<int[]> list = new ArrayList<>();
            for (zombie.characters.BodyDamage.BodyPart bp : p.getBodyDamage().getBodyParts()) {
                int idx;
                try {
                    idx = zombie.characterTextures.BloodBodyPartType.valueOf(bp.getType().name()).index();
                } catch (RuntimeException ex) {
                    continue;
                }
                int k = -1;
                if (bp.bandaged()) k = GoreState.BANDAGE;
                else if (bp.bitten()) k = GoreState.BITE;
                else if (bp.deepWounded() || bp.isCut()) k = GoreState.CUT;
                else if (bp.haveBullet()) k = GoreState.BULLET;
                else if (bp.haveGlass()) k = GoreState.STAB;
                else if (bp.isBurnt()) k = GoreState.BURN;
                else if (bp.scratched()) k = GoreState.SCRATCH;
                if (k < 0) continue;
                list.add(new int[] {idx, k});
                sig.append(idx).append(':').append(k).append(',');
            }
            String scars = scarsOf(p, list);
            sig.append('|').append(scars);
            String s = sig.toString();
            if (s.equals(lastWoundSig.get(p))) continue;
            lastWoundSig.put(p, s);
            GoreState g = GoreState.make(p);
            g.clearWounds();
            for (int[] w : list) {
                if (g.count >= GoreState.MAX_WOUNDS) break;
                float h = ((w[0] * 2654435761L) >>> 8 & 0xFFFF) / 65535f;
                g.wound(w[0], w[1], 0.3f + 0.4f * h, h, w[1] == GoreState.SCRATCH ? 0.6f : w[1] == GoreState.BITE ? 0.55f : 0.7f);
            }
            if (!scars.isEmpty()) {
                for (String sc : scars.split(";")) {
                    if (g.count >= GoreState.MAX_WOUNDS || sc.isEmpty()) break;
                    String[] f = sc.split(",");
                    if (f.length < 2) continue;
                    try {
                        g.wound(Integer.parseInt(f[0]), GoreState.SCAR, 0.5f, Float.parseFloat(f[1]), 0.6f);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
    }

    private static final Map<IsoPlayer, ArrayList<int[]>> lastDeep = new IdentityHashMap<>();

    // A bite or deep cut that was there and now isn't (healed, not just bandaged) becomes a scar
    private static String scarsOf(IsoPlayer p, ArrayList<int[]> now) {
        String saved = "";
        try {
            if (p.hasModData() && p.getModData().rawget("PZOE_GoreScars") instanceof String s) saved = s;
        } catch (Throwable ignored) {
        }
        ArrayList<int[]> before = lastDeep.get(p);
        ArrayList<int[]> deep = new ArrayList<>();
        for (int[] w : now) {
            if (w[1] == GoreState.BITE || w[1] == GoreState.CUT || w[1] == GoreState.BANDAGE) deep.add(w);
        }
        if (before != null) {
            StringBuilder add = new StringBuilder(saved);
            for (int[] b : before) {
                if (b[1] == GoreState.BANDAGE) continue;
                boolean still = false;
                for (int[] w : deep) still |= w[0] == b[0];
                if (!still) {
                    if (add.length() > 0) add.append(';');
                    add.append(b[0]).append(',').append(String.format(java.util.Locale.ROOT, "%.2f", rnd()));
                }
            }
            String s = add.toString();
            String[] parts = s.split(";");
            if (parts.length > 6) s = String.join(";", java.util.Arrays.copyOfRange(parts, parts.length - 6, parts.length));
            if (!s.equals(saved)) {
                p.getModData().rawset("PZOE_GoreScars", s);
                saved = s;
            }
        }
        lastDeep.put(p, deep);
        return saved;
    }

    // Drawing

    private static Texture texture(int k, int variant) {
        int idx = isDrop(k) ? 12 : k == BRAIN ? 11 : k == GIB && variant > 0 ? 7 + variant : k;
        Texture t = tex[idx];
        if (t == null) {
            t = Texture.getSharedTexture("media/textures/PZOE/" + TEX[idx] + ".png");
            tex[idx] = t;
        }
        return t;
    }

    private static boolean isDrop(int kind) { return kind == DROP || kind == WEAPON_DROP; }

    private static void draw(int player, int z) {
        if (!zombie.core.PerformanceSettings.fboRenderChunk) return;
        IsoCamera.FrameState fs = IsoCamera.frameState;
        if (fs.playerIndex != player) return;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        int cx = (int) Math.floor(fs.camCharacterX), cy = (int) Math.floor(fs.camCharacterY);
        float scale = zombie.core.Core.tileScale;
        float[] pal = PAL[palette];
        boolean started = false;
        Texture partAtlas = activeParts == 0 ? null : GoreParts.readyAtlas();
        for (int i = 0; i < count; i++) {
            if ((int) Math.floor(pz[i] + 0.0001f) != z) continue;
            IsoGridSquare sq = cell.getGridSquare((int) Math.floor(px[i]), (int) Math.floor(py[i]), z);
            if (sq == null || !sq.isCouldSee(player)) continue;
            int k = kind[i], slot = partSlot[i] - 1;
            int tile = slot >= 0 && partAtlas != null ? partTile[slot] : -1;
            GoreDetached.Part capture = slot < 0 || !detailedGibsOn || quality < 2 || !GoreCore.dismemberLook || !GoreModel.wanted ? null : partCapture[slot];
            Texture captured = capture == null ? null : capture.image();
            Texture t = captured != null ? captured : tile >= 0 ? partAtlas : texture(k, k == GIB ? (i & 3) : 0);
            if (t == null || !t.isReady()) continue;
            float w = slot >= 0 ? 0f : t.getWidth() * scale;
            float h = slot >= 0 ? 0f : t.getHeight() * scale;
            float s = switch (k) {
                case DROP, WEAPON_DROP -> 0.18f + 0.12f * size[i];
                case MIST -> 0.9f * size[i];
                case GIB -> 0.55f * size[i];
                case BONE -> 0.5f * size[i];
                case BRAIN -> 0.28f * size[i];
                case FLY -> 0.5f;
                default -> 0.55f;
            };
            w *= s;
            h *= s;
            float x = IsoUtils.XToScreen(px[i], py[i], pz[i], 0) - fs.offX, y = IsoUtils.YToScreen(px[i], py[i], pz[i], 0) - fs.offY;
            ColorInfo light = sq.getLightInfo(player);
            if (light == null) continue;
            float r = 1f, g = 1f, b = 1f, a = 1f;
            if (isDrop(k) || k == MIST) {
                r = pal[0];
                g = pal[1];
                b = pal[2];
                a = k == MIST ? 0.17f * (1f - age[i] / life[i]) : 1f;
            } else if (k == GIB && palette != 0) {
                r = pal[0] * 1.8f + 0.1f;
                g = pal[1] * 1.8f + 0.05f;
                b = pal[2] * 1.8f + 0.05f;
            }
            // Resting pieces fade out at the end of their time
            if (life[i] - age[i] < 3f) a *= (life[i] - age[i]) / 3f;
            if (!started) {
                SpriteRenderer.instance.StartShader(0, player);
                zombie.IndieGL.enableDepthTest();
                zombie.IndieGL.glDepthFunc(515);
                zombie.IndieGL.glDepthMask(false);
                zombie.IndieGL.glBlendFunc(770, 771);
                started = true;
            }
            TextureDraw.nextZ = IsoDepthHelper.getSquareDepthData(cx, cy, px[i] + 0.1f, py[i] + 0.1f, pz[i]).depthStart * 2f - 1f;
            TextureDraw.nextChunkDepth = 0f;
            if (slot >= 0) {
                int angle = resting[i] != 0 ? partAngle[slot] : GoreParts.angle(partAngle[slot], age[i]);
                if (captured != null) GoreParts.drawCaptured(captured, capture.crop(), angle, x, y, light, a);
                else GoreParts.draw(t, tile, angle, partFlipped[slot], x, y, scale, light, a);
                continue;
            }
            if (isDrop(k)) {
                // Project the flight direction and taper opacity from the drop toward the tail.
                float dx = vx[i] - vy[i];
                float dy = (vx[i] + vy[i]) * 0.5f - vz[i] * 3f;
                float speed = (float) Math.sqrt(dx * dx + dy * dy);
                if (speed < 1e-4f) {
                    dx = 0f;
                    dy = 1f;
                    speed = 1f;
                }
                float halfWidth = scale * (0.55f + 0.25f * size[i]);
                float length = scale * Math.min(4f, 1.3f + speed * 0.8f);
                float nx = -dy * halfWidth / speed, ny = dx * halfWidth / speed;
                float tx = x - dx * length / speed, ty = y - dy * length / speed;
                r *= light.r;
                g *= light.g;
                b *= light.b;
                SpriteRenderer.instance.render(t, tx - nx, ty - ny, x - nx, y - ny, x + nx, y + ny, tx + nx, ty + ny,
                    r, g, b, a * 0.35f, r, g, b, a, r, g, b, a, r, g, b, a * 0.35f, null);
                continue;
            }
            SpriteRenderer.instance.render(t, x - w * 0.5f, y - h * 0.5f, w, h, r * light.r, g * light.g, b * light.b, a, null);
        }
    }

    static String stateText() {
        return "goreParticles=" + count + ";goreSpawned=" + spawned + ";goreSpurts=" + spurts.size() + ";gorePools=" + pools.size()
            + ";goreLens=" + Math.round(lens * 100) + (problem != null ? ";goreProblem=" + problem : "");
    }
}
