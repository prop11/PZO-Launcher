package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Iterator;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.vehicles.BaseVehicle;

/**
 * Moving ground fog for the High setting of PZO Enhancements' fog (the interactive fog idea and its fluid model are from
 * fmac's Atmospherics, with his permission; this is a lighter 2.5D take that feeds the fog we already draw).
 *
 * A window of 64 x 64 squares around player 0, 2 cells a square, 4 layers up through the fog, holds how much fog each
 * cell has compared with the undisturbed fog (1 = as usual) and how it's moving. The chunk shader multiplies its own
 * fog by that inside the window and fades to 1 at the edge, so outside nothing changes and there's no seam. People,
 * zombies, animals and vehicles part it ahead of them, drag it along, leave swirling wakes and clear a hole where they
 * stand; it flows back in from thicker fog around. Walls and closed edges stop it, it stays out of buildings except
 * what drifts through an open door and fades there, and it sits around solid objects.
 *
 * Game thread: squares are read 512 a frame into a 64 x 64 tile map (outdoors, solid, north and west walls), stored
 * wrapped by world position so moving the window needs no copy. 30 times a second a step is queued with the window,
 * the wind and up to 32 movers as swept capsules. Render thread: one fragment per cell writes the next state into the
 * other of two RGBA16F textures (the 4 layers side by side, a 3D texture fails the game's program validation next to
 * the chunk texture). Nothing runs unless the chunk shader declares PZOFogField.
 *
 * Also works out the morning mist: cold, damp, calm dawns raise the fog the chunk shader draws for a few hours.
 */
final class FogField {

    static final int GRID = 128, LAYERS = 4, TILES = 64, MAX_ENT = 32;
    static final float CELL = 0.5f, HEIGHT = 0.7f;
    private static final double STEP = 1.0 / 30.0;

    // Set from the chunk shader's uniform lookups
    static volatile boolean wanted;
    static volatile float mist;

    // Game thread
    private static final byte[] tiles = new byte[TILES * TILES * 4];
    // Open outdoor ground until the scan has read a square, so a fresh window doesn't start as all indoors
    static {
        for (int i = 0; i < tiles.length; i += 4) tiles[i] = (byte) 85;
    }
    private static int scanCursor;
    private static boolean tilesDirty = true;
    private static final IdentityHashMap<Object, float[]> movers = new IdentityHashMap<>();
    private static double acc;
    private static long lastNs;
    private static int lastOx = Integer.MIN_VALUE, lastOy;
    private static boolean wasWanted;

    // Render thread
    private static final int[] tex = new int[2];
    private static int cur, fbo, tileTex, prog, vao;
    private static int locPrev, locTiles, locOrigin, locOriginOld, locDt, locWind, locReset, locCount, locEntA, locEntB, locHeight;
    private static float simOx = Float.NaN, simOy;
    private static boolean failed;
    // Read by the chunk draws (render thread)
    static volatile float fieldOx, fieldOy;
    static volatile boolean ready;
    private static final FloatBuffer entBuf = ByteBuffer.allocateDirect(MAX_ENT * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static ByteBuffer tileBuf;

    private FogField() {}

    static int texture() {
        return ready ? tex[cur] : 0;
    }

    /** Main thread, once a frame (PixelLight's frame) while the light map effects run. */
    static void frame() {
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        mist = wanted ? morningMist(cm) : 0f;
        if (!wanted) {
            if (wasWanted) {
                wasWanted = false;
                movers.clear();
                lastOx = Integer.MIN_VALUE;
                ready = false;
            }
            return;
        }
        wasWanted = true;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        IsoPlayer p0 = IsoPlayer.players[0];
        if (cell == null || p0 == null) return;
        int ox = (int) Math.floor(p0.getX()) - TILES / 2, oy = (int) Math.floor(p0.getY()) - TILES / 2;
        scanTiles(cell, ox, oy);
        long now = System.nanoTime();
        double dt = lastNs == 0 ? 0 : Math.min(0.25, (now - lastNs) / 1e9);
        lastNs = now;
        if (zombie.GameTime.isGamePaused()) return;
        acc += dt;
        if (acc < STEP) return;
        int steps = (int) Math.min(2, acc / STEP);
        acc -= steps * STEP;
        if (acc > STEP) acc = 0;
        float stepDt = (float) (steps * STEP);
        boolean reset = lastOx == Integer.MIN_VALUE || Math.abs(ox - lastOx) > TILES / 4 || Math.abs(oy - lastOy) > TILES / 4;
        lastOx = ox;
        lastOy = oy;
        float[] ent = new float[MAX_ENT * 8];
        int n = gatherMovers(cell, ox, oy, stepDt, ent);
        // The fog's own drift, as the chunk shader moves its noise: against the screen wind turned onto the ground
        float sx = PixelLight.windX, sy = PixelLight.windY;
        float wx = sy + 0.5f * sx, wy = sy - 0.5f * sx, speed = (0.15f + 0.6f * PixelLight.windStrength) / 0.22f;
        byte[] tileCopy = null;
        if (tilesDirty) {
            tileCopy = tiles.clone();
            tilesDirty = false;
        }
        zombie.core.SpriteRenderer.instance.drawGeneric(new Step(ox, oy, stepDt, -wx * speed, -wy * speed, reset, n, ent, tileCopy));
    }

    // Radiation mist (fmac's model): strongest from dawn for a few hours when the air is cold, near its dew point and calm
    private static float morningMist(zombie.iso.weather.ClimateManager cm) {
        float hour = zombie.GameTime.getInstance().getTimeOfDay(), dawn = 6f;
        zombie.erosion.season.ErosionSeason season = cm.getSeason();
        if (season != null) dawn = season.getDawn();
        float since = (float) (hour - dawn - 24 * Math.floor((hour - dawn + 12) / 24));
        float envelope = smooth(-1.5f, 0f, since) * (1f - smooth(1.2f, 3f, since));
        if (envelope <= 0f) return 0f;
        float t = cm.getTemperature(), rh = Math.max(0.001f, Math.min(1f, cm.getHumidity()));
        double g = Math.log(rh) + 17.625 * t / (243.04 + t);
        float dew = (float) (243.04 * g / (17.625 - g));
        float cold = 1f - smooth(8f, 14f, t);
        float damp = 1f - smooth(0.75f, 3f, Math.max(0f, t - dew));
        float calm = 1f - smooth(0.175f, 0.35f, cm.getWindIntensity());
        return 0.5f * envelope * cold * damp * calm;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    // 512 squares a frame: the whole window every 8 frames. Stored at (x mod 64, y mod 64).
    private static void scanTiles(IsoCell cell, int ox, int oy) {
        for (int k = 0; k < 512; k++) {
            int i = scanCursor;
            scanCursor = (scanCursor + 1) % (TILES * TILES);
            int x = ox + i % TILES, y = oy + i / TILES;
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            int r, g = 0;
            if (sq == null) {
                // Not loaded: open ground
                r = 1;
            } else {
                r = (sq.isOutside() ? 1 : 0) | (sq.isSolid() ? 2 : 0);
                g = (sq.has(IsoFlagType.collideN) ? 1 : 0) | (sq.has(IsoFlagType.collideW) ? 2 : 0);
            }
            int at = ((Math.floorMod(y, TILES)) * TILES + Math.floorMod(x, TILES)) * 4;
            byte br = (byte) (r * 255 / 3), bg = (byte) (g * 255 / 3);
            if (tiles[at] != br || tiles[at + 1] != bg) {
                tiles[at] = br;
                tiles[at + 1] = bg;
                tilesDirty = true;
            }
        }
    }

    // Swept capsules: last position to now, velocity, radius, body height (levels). Ground level only.
    private static int gatherMovers(IsoCell cell, int ox, int oy, float dt, float[] ent) {
        int n = 0;
        float cx = ox + TILES * 0.5f, cy = oy + TILES * 0.5f, half = TILES * 0.5f + 2f;
        for (IsoMovingObject o : cell.getObjectList()) {
            if (n >= MAX_ENT) break;
            if (!(o instanceof IsoGameCharacter c) || c.getZ() >= 1f || c.getVehicle() != null) continue;
            float x = c.getX(), y = c.getY();
            if (Math.abs(x - cx) > half || Math.abs(y - cy) > half) continue;
            n = add(ent, n, o, x, y, dt, 0.32f, 0.75f);
        }
        for (BaseVehicle v : cell.getVehicles()) {
            if (n >= MAX_ENT) break;
            float x = v.getX(), y = v.getY();
            if (v.getZ() >= 1f || Math.abs(x - cx) > half || Math.abs(y - cy) > half) continue;
            zombie.scripting.objects.VehicleScript s = v.getScript();
            float r = s == null ? 1.1f : Math.max(0.8f, Math.min(1.8f, Math.max(s.getExtents().x, s.getExtents().z) * 0.5f));
            n = add(ent, n, v, x, y, dt, r, 0.6f);
        }
        // Forget movers that weren't seen this step
        for (Iterator<float[]> it = movers.values().iterator(); it.hasNext(); ) {
            float[] m = it.next();
            if (m[4] < 0.5f) it.remove();
            else m[4] = 0f;
        }
        return n;
    }

    private static int add(float[] ent, int n, Object key, float x, float y, float dt, float radius, float height) {
        float[] m = movers.get(key);
        if (m == null) movers.put(key, m = new float[] {x, y, 0f, 0f, 1f});
        float px = m[0], py = m[1];
        float dx = x - px, dy = y - py;
        // A jump (teleport, vehicle exit) is a fresh start, not a streak across the map
        if (dx * dx + dy * dy > 4f) {
            px = x;
            py = y;
            dx = dy = 0f;
        }
        float vx = dt > 0f ? dx / dt : 0f, vy = dt > 0f ? dy / dt : 0f;
        m[2] += (vx - m[2]) * 0.5f;
        m[3] += (vy - m[3]) * 0.5f;
        m[0] = x;
        m[1] = y;
        m[4] = 1f;
        int o = n * 8;
        ent[o] = px;
        ent[o + 1] = py;
        ent[o + 2] = x;
        ent[o + 3] = y;
        ent[o + 4] = m[2];
        ent[o + 5] = m[3];
        ent[o + 6] = radius;
        ent[o + 7] = height;
        return n + 1;
    }

    private static final class Step extends TextureDraw.GenericDrawer {
        final int ox, oy, count;
        final float dt, windX, windY;
        final boolean reset;
        final float[] ent;
        final byte[] tileData;

        Step(int ox, int oy, float dt, float windX, float windY, boolean reset, int count, float[] ent, byte[] tileData) {
            this.ox = ox;
            this.oy = oy;
            this.dt = dt;
            this.windX = windX;
            this.windY = windY;
            this.reset = reset;
            this.count = count;
            this.ent = ent;
            this.tileData = tileData;
        }

        @Override
        public void render() {
            if (failed) return;
            try {
                simulate(this);
            } catch (Throwable t) {
                failed = true;
                ready = false;
                PZOLogger.warn("[FogField] " + t);
            }
        }
    }

    private static final String VERTEX = "#version 330\nvoid main()\n{\n"
        + "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
        + "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n}\n";

    // One fragment per cell: x = layer * GRID + column. State: xy = how the fog is pushed (squares a second, on top of
    // its drift), z = amount against the undisturbed fog.
    private static final String FRAGMENT = """
        #version 330
        uniform sampler2D uPrev;
        uniform sampler2D uTiles;
        uniform vec2 uOrigin;
        uniform vec2 uOriginOld;
        uniform float uDt;
        uniform vec2 uWind;
        uniform int uReset;
        uniform int uCount;
        uniform vec4 uEntA[32];
        uniform vec4 uEntB[32];
        uniform float uHeight;
        out vec4 o;
        const float G = 128.0;
        const float L = 4.0;
        const float CELL = 0.5;

        ivec2 flags(vec2 w)
        {
            ivec2 t = ivec2(floor(w));
            vec4 v = texelFetch(uTiles, ivec2(t.x & 63, t.y & 63), 0);
            return ivec2(int(v.r * 3.0 + 0.5), int(v.g * 3.0 + 0.5));
        }

        vec4 prev(vec2 w, float layer)
        {
            vec2 g = (w - uOriginOld) / (G * CELL);
            if (uReset != 0 || g.x < 0.0 || g.y < 0.0 || g.x > 1.0 || g.y > 1.0) return vec4(0.0, 0.0, 1.0, 0.0);
            g.x = clamp(g.x, 0.5 / G, 1.0 - 0.5 / G);
            return texture(uPrev, vec2((g.x + layer) / L, g.y));
        }

        // A wall on the tile edge between w and w + d (d one cell along x or y)
        bool wall(vec2 w, vec2 d)
        {
            vec2 a = floor(w), b = floor(w + d);
            if (a == b) return false;
            if (b.x > a.x) return (flags(w + d).y & 2) != 0;
            if (b.x < a.x) return (flags(w).y & 2) != 0;
            if (b.y > a.y) return (flags(w + d).y & 1) != 0;
            return (flags(w).y & 1) != 0;
        }

        void main()
        {
            ivec2 px = ivec2(gl_FragCoord.xy);
            int layer = px.x / int(G);
            float lf = float(layer);
            vec2 w = uOrigin + (vec2(float(px.x - layer * int(G)), float(px.y)) + 0.5) * CELL;
            float h = (lf + 0.5) / L * uHeight;
            ivec2 f = flags(w);
            if ((f.x & 2) != 0) {
                o = vec4(0.0);
                return;
            }
            bool outdoor = (f.x & 1) != 0;
            vec4 c = prev(w, lf);
            bool bxp = wall(w, vec2(CELL, 0.0)), bxm = wall(w, vec2(-CELL, 0.0));
            bool byp = wall(w, vec2(0.0, CELL)), bym = wall(w, vec2(0.0, -CELL));
            vec4 xp = bxp ? c : prev(w + vec2(CELL, 0.0), lf);
            vec4 xm = bxm ? c : prev(w - vec2(CELL, 0.0), lf);
            vec4 yp = byp ? c : prev(w + vec2(0.0, CELL), lf);
            vec4 ym = bym ? c : prev(w - vec2(0.0, CELL), lf);
            // Carried along by its push and the drift, but not through a wall
            vec2 back = (c.xy + uWind) * uDt;
            float bl = length(back);
            if (bl > 2.0 * CELL) back *= 2.0 * CELL / bl;
            vec2 from = w - back;
            if (floor(from.x) != floor(w.x) && wall(w, vec2(sign(-back.x) * CELL, 0.0))) from.x = w.x;
            if (floor(from.y) != floor(w.y) && wall(w, vec2(0.0, sign(-back.y) * CELL))) from.y = w.y;
            vec4 s = prev(from, lf);
            vec2 u = s.xy;
            float m = s.z;
            // Thick fog runs into thin: holes close by inflow and heaps spread out as low waves
            u -= vec2(xp.z - xm.z, yp.z - ym.z) / (2.0 * CELL) * 0.9 * uDt;
            m += ((xp.z + xm.z + yp.z + ym.z) * 0.25 - c.z) * 0.15;
            u *= exp(-1.2 * uDt);
            float carve = 0.0;
            for (int i = 0; i < 32; i++) {
                if (i >= uCount) break;
                vec4 A = uEntA[i], B = uEntB[i];
                if (h > B.w * 1.25) continue;
                vec2 ab = A.zw - A.xy;
                float t = clamp(dot(w - A.xy, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
                vec2 d = w - (A.xy + ab * t);
                float dist = length(d), r = B.z, reach = r * 3.0;
                if (dist >= reach) continue;
                float spd = length(B.xy);
                vec2 dir = spd > 1e-3 ? B.xy / spd : vec2(0.0);
                vec2 n = dist > 1e-4 ? d / dist : vec2(0.0);
                float inside = 1.0 - smoothstep(r * 0.6, r, dist);
                float ring = 1.0 - smoothstep(r, reach, dist);
                float hf = 1.0 - smoothstep(B.w * 0.75, B.w * 1.25, h);
                float along = dot(n, dir);
                float front = max(along, 0.0), behind = max(-along, 0.0);
                float side = sqrt(max(0.0, 1.0 - along * along));
                // Bow wave and parting to the sides, strongest ahead
                u += n * (hf * spd * (0.3 + 0.7 * front + 0.5 * side) * ring * 1.6 * uDt);
                // Dragged along inside and just behind
                u = mix(u, B.xy, clamp(hf * (inside + 0.45 * ring * behind) * 2.5 * uDt, 0.0, 1.0));
                // A swirl each side of the path behind it
                float handed = dir.x * n.y - dir.y * n.x;
                u += vec2(-n.y, n.x) * (sign(handed) * hf * spd * behind * ring * 1.2 * uDt);
                carve = max(carve, inside * hf * 0.85);
            }
            if (bxp && u.x > 0.0) u.x = 0.0;
            if (bxm && u.x < 0.0) u.x = 0.0;
            if (byp && u.y > 0.0) u.y = 0.0;
            if (bym && u.y < 0.0) u.y = 0.0;
            float sp = length(u);
            if (sp > 3.0) u *= 3.0 / sp;
            // Back to the usual amount outdoors; indoors only what drifts in through a door, and it fades
            if (outdoor) m += (1.0 - m) * (1.0 - exp(-0.25 * uDt));
            else m *= exp(-1.5 * uDt);
            m *= 1.0 - carve;
            // The window's border hands back to the undisturbed fog
            vec2 g = (w - uOrigin) / CELL;
            float edge = min(min(g.x, G - g.x), min(g.y, G - g.y));
            float relax = 1.0 - smoothstep(1.0, 4.0, edge);
            u *= 1.0 - relax;
            m = mix(m, 1.0, relax);
            o = vec4(u, clamp(m, 0.0, 2.5), 1.0);
        }
        """;

    private static int compile(String vs, String fs) {
        int p = GL20.glCreateProgram();
        for (int k = 0; k < 2; k++) {
            int s = GL20.glCreateShader(k == 0 ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(s, k == 0 ? vs : fs);
            GL20.glCompileShader(s);
            if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
                PZOLogger.warn("[FogField] shader didn't compile: " + GL20.glGetShaderInfoLog(s, 4096).trim());
                GL20.glDeleteShader(s);
                GL20.glDeleteProgram(p);
                return 0;
            }
            GL20.glAttachShader(p, s);
            GL20.glDeleteShader(s);
        }
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            PZOLogger.warn("[FogField] shader didn't link: " + GL20.glGetProgramInfoLog(p, 4096).trim());
            GL20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int stateTexture() {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, GRID * LAYERS, GRID, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        return t;
    }

    private static boolean init() {
        prog = compile(VERTEX, FRAGMENT);
        if (prog == 0) return false;
        locPrev = GL20.glGetUniformLocation(prog, "uPrev");
        locTiles = GL20.glGetUniformLocation(prog, "uTiles");
        locOrigin = GL20.glGetUniformLocation(prog, "uOrigin");
        locOriginOld = GL20.glGetUniformLocation(prog, "uOriginOld");
        locDt = GL20.glGetUniformLocation(prog, "uDt");
        locWind = GL20.glGetUniformLocation(prog, "uWind");
        locReset = GL20.glGetUniformLocation(prog, "uReset");
        locCount = GL20.glGetUniformLocation(prog, "uCount");
        locEntA = GL20.glGetUniformLocation(prog, "uEntA");
        locEntB = GL20.glGetUniformLocation(prog, "uEntB");
        locHeight = GL20.glGetUniformLocation(prog, "uHeight");
        GL13.glActiveTexture(GL13.GL_TEXTURE8);
        tex[0] = stateTexture();
        tex[1] = stateTexture();
        tileTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, TILES, TILES, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        fbo = GL30.glGenFramebuffers();
        vao = GL30.glGenVertexArrays();
        tileBuf = ByteBuffer.allocateDirect(TILES * TILES * 4).order(ByteOrder.nativeOrder());
        return true;
    }

    private static void simulate(Step s) {
        int prevFbo = GlGet.integer(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GlGet.integer(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE8);
        int prev8 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE9);
        int prev9 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_VIEWPORT_BIT
            | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        try {
            if (prog == 0 && !init()) {
                failed = true;
                return;
            }
            if (s.tileData != null) {
                tileBuf.clear();
                tileBuf.put(s.tileData).flip();
                GL13.glActiveTexture(GL13.GL_TEXTURE9);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, TILES, TILES, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, tileBuf);
            }
            boolean reset = s.reset || Float.isNaN(simOx);
            int dst = 1 - cur;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex[dst], 0);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glViewport(0, 0, GRID * LAYERS, GRID);
            GL20.glUseProgram(prog);
            GL13.glActiveTexture(GL13.GL_TEXTURE8);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[cur]);
            GL13.glActiveTexture(GL13.GL_TEXTURE9);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
            GL20.glUniform1i(locPrev, 8);
            GL20.glUniform1i(locTiles, 9);
            GL20.glUniform2f(locOrigin, s.ox, s.oy);
            GL20.glUniform2f(locOriginOld, reset ? s.ox : simOx, reset ? s.oy : simOy);
            GL20.glUniform1f(locDt, s.dt);
            GL20.glUniform2f(locWind, s.windX, s.windY);
            GL20.glUniform1i(locReset, reset ? 1 : 0);
            GL20.glUniform1f(locHeight, HEIGHT);
            GL20.glUniform1i(locCount, s.count);
            if (s.count > 0) {
                entBuf.clear();
                for (int i = 0; i < s.count; i++) entBuf.put(s.ent, i * 8, 4);
                entBuf.flip();
                GL20.glUniform4fv(locEntA, entBuf);
                entBuf.clear();
                for (int i = 0; i < s.count; i++) entBuf.put(s.ent, i * 8 + 4, 4);
                entBuf.flip();
                GL20.glUniform4fv(locEntB, entBuf);
            }
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            cur = dst;
            simOx = s.ox;
            simOy = s.oy;
            fieldOx = s.ox;
            fieldOy = s.oy;
            ready = true;
        } finally {
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
            GL13.glActiveTexture(GL13.GL_TEXTURE9);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev9);
            GL13.glActiveTexture(GL13.GL_TEXTURE8);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev8);
            GL13.glActiveTexture(prevActive);
        }
    }

    static String stateText() {
        return "fogSim=" + (wanted ? 1 : 0) + ";fogSimReady=" + (ready ? 1 : 0) + ";mist=" + Math.round(mist * 100) + ";fogMovers=" + movers.size();
    }
}
