package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.PZOLogger;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import zombie.core.textures.TextureDraw;
import zombie.iso.weather.fx.IsoWeatherFX;
import zombie.iso.weather.fx.ParticleRectangle;
import zombie.iso.weather.fx.SteppedUpdateFloat;

/**
 * Falling rain and snow on the GPU (PZO Enhancements' Better Rain and Snow).
 *
 * Vanilla keeps 1024 rain and 1024 snow particles in a 512 px tile, moves every one on the main thread each frame and
 * repeats the tile over the screen, so the same pattern shows in every tile and every drop sits at one depth. With
 * the card on, the rain and snow rectangles are given no particles (their strength is set to 0 where IsoWeatherFX
 * sets it), so the game stops updating and drawing them, and one full-screen pass draws the precipitation instead, at
 * the same point in the frame: inside the weather mask's particle buffer when masking is on (so it stops at roofs and
 * indoors exactly like vanilla's), or straight onto the world when it's off.
 *
 * The pass: rain falls in 2-4 layers at different sizes and speeds, each moving with the camera by a different
 * amount, as thin motion-blurred streaks slanted by vanilla's own wind angle, longer and denser in a downpour, with a
 * faint drifting mist when it pours. Snow is soft round flakes in layers that flutter and drift sideways with the
 * wind. Every layer is a hashed grid in screen pixels (offscreen pixels plus the camera offset, like vanilla's tile,
 * so it's fixed to the world), one cell read per layer per pixel. Drops and flakes take the light from PZO's light map
 * at the ground under them when it's running, so they go dim at night and glow under lamps and headlights.
 *
 * Main thread: the hooks in IsoWeatherFX snapshot what the pass needs into a drawer for that frame. Render thread:
 * the drawer draws it.
 */
public final class Precipitation {
    static final String WEATHER_FX = "zombie/iso/weather/fx/IsoWeatherFX";

    private static volatile boolean enabled;
    private static volatile int quality = 2;
    private static volatile float amount = 1f;
    private static volatile boolean failed;

    private static VarHandle vhInstance, vhIndoors, vhPlayerIndoors, vhSnow, vhRain, vhWindAngle, vhWindSpeed;
    private static boolean handlesTried, handlesOk;

    private static long lastNanos;
    private static float clock;
    private static int lastFrame = Integer.MIN_VALUE;

    // Render thread
    private static int program, vao, locA, locB, locC, locL, locD, locLight;
    private static volatile long draws;

    private Precipitation() {}

    static void configure(boolean on, int q, double pct) {
        enabled = on;
        quality = Math.max(1, Math.min(3, q));
        amount = (float) Math.max(0.25, Math.min(3.0, pct / 100.0));
    }

    private static boolean active() {
        return enabled && !failed && handles();
    }

    private static boolean handles() {
        if (handlesTried) return handlesOk;
        handlesTried = true;
        try {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(IsoWeatherFX.class, MethodHandles.lookup());
            vhInstance = l.findStaticVarHandle(IsoWeatherFX.class, "instance", IsoWeatherFX.class);
            vhIndoors = l.findVarHandle(IsoWeatherFX.class, "indoorsAlphaMod", SteppedUpdateFloat.class);
            vhPlayerIndoors = l.findVarHandle(IsoWeatherFX.class, "playerIndoors", boolean.class);
            vhSnow = l.findVarHandle(IsoWeatherFX.class, "precipitationIntensitySnow", SteppedUpdateFloat.class);
            vhRain = l.findVarHandle(IsoWeatherFX.class, "precipitationIntensityRain", SteppedUpdateFloat.class);
            vhWindAngle = l.findVarHandle(IsoWeatherFX.class, "windAngle", float.class);
            vhWindSpeed = l.findVarHandle(IsoWeatherFX.class, "windSpeed", float.class);
            handlesOk = true;
        } catch (Throwable t) {
            PZOLogger.warn("[Precipitation] can't read the game's weather state, vanilla rain and snow stay: " + t);
            handlesOk = false;
        }
        return handlesOk;
    }

    /** Replaces snowParticles/rainParticles.SetParticlesStrength(v) in IsoWeatherFX.update. */
    public static void strength(ParticleRectangle r, float v) {
        r.SetParticlesStrength(active() ? 0f : v);
    }

    /** Start of IsoWeatherFX.renderPrecipitation: the masked path, drawing into the weather particle buffer. */
    public static void renderMasked() {
        submit();
    }

    /** Start of IsoWeatherFX.render: the path without masking, drawing onto the world. */
    public static void renderUnmasked() {
        if (zombie.core.Core.getInstance().getOptionRenderPrecipitation() > 2) return;
        submit();
    }

    private static void submit() {
        try {
            if (!active() || zombie.network.GameServer.server) return;
            IsoWeatherFX fx = (IsoWeatherFX) vhInstance.get();
            if (fx == null) return;
            float rain = ((SteppedUpdateFloat) vhRain.get(fx)).value();
            float snow = ((SteppedUpdateFloat) vhSnow.get(fx)).value();
            // Once a frame, not once per split-screen player
            int frame = zombie.iso.IsoCamera.frameState.frameCount;
            if (frame != lastFrame) {
                lastFrame = frame;
                long now = System.nanoTime();
                float dt = lastNanos == 0 ? 0f : (float) Math.min(0.1, (now - lastNanos) / 1e9);
                lastNanos = now;
                if (zombie.GameTime.isGamePaused()) dt = 0f;
                clock = (clock + dt * zombie.core.Core.getInstance().getOptionPrecipitationSpeedMultiplier()) % 600f;
            }
            if (rain <= 0.001f && snow <= 0.001f && com.pzoptimizer.pzoe.weather.WeatherEvents.hail() <= 0.001f) return;
            float alpha = ((SteppedUpdateFloat) vhIndoors.get(fx)).value() * ((boolean) vhPlayerIndoors.get(fx) ? 0.5f : 1f);
            if (alpha <= 0.001f) return;
            zombie.iso.IsoCamera.FrameState fs = zombie.iso.IsoCamera.frameState;
            int p = fs.playerIndex;
            // Vanilla thins the particles out when zoomed out, the same here
            float mod = 1f - (fs.zoom - 0.5f) * 0.375f;
            float angle = (float) Math.toRadians((float) vhWindAngle.get(fx));
            float speed = (float) vhWindSpeed.get(fx);
            float day = zombie.iso.weather.ClimateManager.getInstance().getDayLightStrength();
            float[] v = {
                fs.offscreenWidth, fs.offscreenHeight, fs.offX, fs.offY,
                clock, rain, snow, alpha,
                (float) Math.cos(angle), (float) Math.sin(angle), speed, 64f * zombie.core.Core.tileScale,
                0f, PixelLight.BASE_LEVEL, (float) Math.floor(fs.camCharacterZ), 0f,
                day, quality + 1, amount * Math.max(0.3f, mod), com.pzoptimizer.pzoe.weather.WeatherEvents.hail(),
            };
            zombie.core.SpriteRenderer.instance.drawGeneric(new Draw(v, p, PixelLight.deferred || PixelLight.atmos));
        } catch (Throwable t) {
            failed = true;
            PZOLogger.warn("[Precipitation] off for this session, vanilla rain and snow are back: " + t);
        }
    }

    private static final class Draw extends TextureDraw.GenericDrawer {
        private final float[] v;
        private final int player;
        private final boolean lightMapLive;

        Draw(float[] v, int player, boolean lightMapLive) {
            this.v = v;
            this.player = player;
            this.lightMapLive = lightMapLive;
        }

        @Override
        public void render() {
            if (failed) return;
            try {
                if (program == 0 && !build()) return;
                int tex = PixelLight.lightTexture(player), ring = PixelLight.lightRing(player);
                boolean lit = lightMapLive && tex != 0 && ring > 0;
                GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                try {
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                    GL11.glDisable(GL11.GL_CULL_FACE);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glDepthMask(false);
                    GL20.glUseProgram(program);
                    if (lit) {
                        GL13.glActiveTexture(GL13.GL_TEXTURE7);
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                        GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    }
                    GL20.glUniform1i(locLight, 7);
                    GL20.glUniform4f(locA, v[0], v[1], v[2], v[3]);
                    GL20.glUniform4f(locB, v[4], v[5], v[6], v[7]);
                    GL20.glUniform4f(locC, v[8], v[9], v[10], v[11]);
                    GL20.glUniform4f(locL, ring, v[13], v[14], lit ? 1f : 0f);
                    GL20.glUniform4f(locD, v[16], v[17], v[18], v[19]);
                    GL30.glBindVertexArray(vao);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                    GL30.glBindVertexArray(0);
                    draws++;
                } finally {
                    GL20.glUseProgram(0);
                    zombie.core.ShaderHelper.forgetCurrentlyBound();
                    zombie.core.DefaultShader.isActive = false;
                    GL11.glPopAttrib();
                }
            } catch (Throwable t) {
                failed = true;
                PZOLogger.warn("[Precipitation] draw failed, vanilla rain and snow are back: " + t);
            }
        }
    }

    private static boolean build() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERTEX, "vertex"), fs = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT, "fragment");
        if (vs == 0 || fs == 0) {
            failed = true;
            return false;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
            PZOLogger.warn("[Precipitation] didn't link: " + GL20.glGetProgramInfoLog(prog, 4096).trim());
            GL20.glDeleteProgram(prog);
            failed = true;
            return false;
        }
        program = prog;
        vao = GL30.glGenVertexArrays();
        locA = GL20.glGetUniformLocation(prog, "PZOPrecipA");
        locB = GL20.glGetUniformLocation(prog, "PZOPrecipB");
        locC = GL20.glGetUniformLocation(prog, "PZOPrecipC");
        locL = GL20.glGetUniformLocation(prog, "PZOPrecipL");
        locD = GL20.glGetUniformLocation(prog, "PZOPrecipD");
        locLight = GL20.glGetUniformLocation(prog, "PZOLight");
        PZOLogger.info("[Precipitation] GPU rain and snow ready");
        return true;
    }

    private static int compile(int type, String src, String what) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            PZOLogger.warn("[Precipitation] " + what + " didn't compile: " + GL20.glGetShaderInfoLog(s, 4096).trim());
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    static final String VERTEX = "#version 130\nvoid main()\n{\n"
        + "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
        + "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n}\n";

    // Uniforms: A = offscreen size, camera offset. B = clock (s), rain, snow, alpha (indoors fade).
    // C = fall direction on screen (y down), wind speed (1..6), one square's width in pixels times 2 (64 * tileScale).
    // L = light ring size, first level, camera level, 1 when the light map can be read. D = daylight, layers, density,
    // hail (PZOE weather, 0..1).
    static final String FRAGMENT = """
        #version 130
        uniform vec4 PZOPrecipA;
        uniform vec4 PZOPrecipB;
        uniform vec4 PZOPrecipC;
        uniform vec4 PZOPrecipL;
        uniform vec4 PZOPrecipD;
        uniform sampler2D PZOLight;

        float pzoPHash(vec2 p)
        {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
        }

        float pzoPNoise(vec2 p)
        {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(pzoPHash(i), pzoPHash(i + vec2(1.0, 0.0)), f.x),
                mix(pzoPHash(i + vec2(0.0, 1.0)), pzoPHash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        // Light at the ground under a screen point (offscreen pixels plus camera offset), from PZO's light map: the
        // floor corners of that square on the camera's level, the same atlas the chunk shader reads
        vec3 pzoPLight(vec2 P)
        {
            float z = PZOPrecipL.z;
            vec2 w = vec2((P.x + 2.0 * P.y) / PZOPrecipC.w, (2.0 * P.y - P.x) / PZOPrecipC.w) + 3.0 * z;
            vec2 sq = floor(w);
            float tile = 2.0 * PZOPrecipL.x;
            float s = 2.0 * clamp(z - PZOPrecipL.y, 0.0, 23.0);
            vec2 cell = mod(sq, PZOPrecipL.x);
            vec2 uv = (vec2(mod(s, 8.0), floor(s / 8.0)) * tile + 2.0 * cell + 0.5 + (w - sq)) / (vec2(8.0, 6.0) * tile);
            return texture2D(PZOLight, uv).rgb;
        }

        // One layer of rain streaks. k = 0 nearest .. 1 farthest. px = offscreen pixel (y down).
        float pzoRainLayer(vec2 px, float k, float t, vec2 dir, float density, float rain)
        {
            float scale = mix(1.0, 0.42, k);
            float parallax = mix(1.12, 0.72, k);
            vec2 q = (px + PZOPrecipA.zw * parallax) / scale;
            vec2 across = vec2(-dir.y, dir.x);
            float speed = mix(1000.0, 560.0, k) * (1.0 + PZOPrecipC.z * 0.08);
            float u = dot(q, across);
            float v = dot(q, dir) - speed * t / scale;
            vec2 cs = vec2(16.0, 130.0);
            vec2 cell = floor(vec2(u, v) / cs);
            vec2 lc = vec2(u, v) / cs - cell;
            cell += k * 17.0;
            if (pzoPHash(cell) > density) return 0.0;
            float x0 = 0.2 + 0.6 * pzoPHash(cell + 1.7);
            float len = (0.28 + 0.3 * pzoPHash(cell + 3.7)) * (0.7 + 0.6 * rain);
            float v0 = (1.0 - len) * pzoPHash(cell + 9.1);
            // Width in screen pixels, about 1.5 px near and under 1 px far (fainter instead of thinner)
            float du = abs(lc.x - x0) * cs.x * scale;
            float a = clamp(1.0 - du / mix(1.1, 0.7, k), 0.0, 1.0);
            float tt = (lc.y - v0) / len;
            if (tt < 0.0 || tt > 1.0) return 0.0;
            // The leading end is the drop itself, the rest its blur
            a *= sqrt(tt) * (1.0 - smoothstep(0.88, 1.0, tt));
            return a * mix(0.85, 0.32, k);
        }

        // One layer of snowflakes
        float pzoSnowLayer(vec2 px, float k, float t, float density, vec2 drift)
        {
            float scale = mix(1.0, 0.38, k);
            float parallax = mix(1.18, 0.62, k);
            vec2 q = (px + PZOPrecipA.zw * parallax) / scale;
            q -= vec2(drift.x * mix(1.0, 0.6, k), mix(62.0, 34.0, k)) * t;
            float cs = 46.0;
            vec2 cell = floor(q / cs);
            vec2 lc = q / cs - cell;
            cell += k * 31.0;
            float r = pzoPHash(cell);
            if (r > density) return 0.0;
            vec2 c = vec2(0.28 + 0.44 * pzoPHash(cell + 1.3), 0.28 + 0.44 * pzoPHash(cell + 5.9));
            float ph = pzoPHash(cell + 8.2) * 6.2832;
            float w = 0.7 + pzoPHash(cell + 2.4);
            // Flutter: a slow sideways sway and a little bob, each flake on its own
            c.x += sin(t * w + ph) * 0.17;
            c.y += sin(t * w * 0.7 + ph * 1.3) * 0.05;
            float rad = mix(2.4, 0.9, k) * (0.6 + 0.7 * pzoPHash(cell + 4.4));
            float soft = mix(1.6, 0.6, k);
            float d = length((lc - c) * cs) * scale;
            return (1.0 - smoothstep(rad - 0.5 * soft, rad + soft, d)) * mix(0.95, 0.42, k);
        }

        // A hailstone seen from d (pixels from its centre) with radius rad: x = coverage, y = shade. A small ice ball
        // lit from the upper left: bright cap, a glint, cloudy grey-blue toward the edge.
        vec2 pzoHailBall(vec2 d, float rad)
        {
            float r = length(d) / rad;
            float a = 1.0 - smoothstep(0.75, 1.15, r);
            if (a <= 0.0) return vec2(0.0);
            vec2 nn = d / rad;
            float z = sqrt(max(0.0, 1.0 - dot(nn, nn)));
            float lit = clamp(dot(vec3(nn, z), normalize(vec3(-0.5, -0.6, 0.65))), 0.0, 1.0);
            float glint = smoothstep(0.86, 0.97, lit);
            return vec2(a, 0.62 + 0.38 * lit + 0.35 * glint - 0.18 * smoothstep(0.6, 1.0, r));
        }

        // One layer of falling hail: hard round stones coming down fast and straight, a short blur trailing above
        vec2 pzoHailLayer(vec2 px, float k, float t, float density, vec2 dir)
        {
            float scale = mix(1.0, 0.45, k);
            float parallax = mix(1.12, 0.74, k);
            vec2 q = (px + PZOPrecipA.zw * parallax) / scale;
            vec2 across = vec2(-dir.y, dir.x);
            float u = dot(q, across);
            float v = dot(q, dir) - mix(900.0, 560.0, k) * t / scale;
            float cs = 44.0;
            vec2 cell = floor(vec2(u, v) / cs);
            vec2 lc = vec2(u, v) / cs - cell;
            cell += k * 43.0;
            if (pzoPHash(cell) > density) return vec2(0.0);
            vec2 c = vec2(0.2 + 0.6 * pzoPHash(cell + 2.1), 0.2 + 0.6 * pzoPHash(cell + 6.3));
            vec2 d = (lc - c) * cs * scale;
            float rad = mix(2.8, 1.3, k) * (0.7 + 0.6 * pzoPHash(cell + 4.8));
            vec2 ball = pzoHailBall(d, rad);
            // Blur trailing above the stone (toward where it came from)
            float behind = -d.y;
            float trail = behind > 0.0 ? (1.0 - smoothstep(rad * 0.5, rad * 0.9, abs(d.x))) * (1.0 - smoothstep(0.0, rad * 3.5, behind)) * 0.35 : 0.0;
            float fade = mix(1.0, 0.55, k);
            return vec2(max(ball.x, trail) * fade, ball.x > trail ? ball.y : 0.75);
        }

        // Stones bouncing where they land: each cell has a spot where one hits every so often and hops twice
        vec2 pzoHailBounce(vec2 px, float t, float density)
        {
            vec2 q = px + PZOPrecipA.zw;
            float cs = 70.0;
            vec2 cell = floor(q / cs);
            if (pzoPHash(cell + 19.1) > density) return vec2(0.0);
            float period = 0.55 + 0.5 * pzoPHash(cell + 7.7);
            float ph = fract(t / period + pzoPHash(cell + 1.9));
            float hop = 0.0;
            if (ph < 0.35) hop = 9.0 * 4.0 * (ph / 0.35) * (1.0 - ph / 0.35);
            else if (ph < 0.55) hop = 3.5 * 4.0 * ((ph - 0.35) / 0.2) * (1.0 - (ph - 0.35) / 0.2);
            else if (ph > 0.75) return vec2(0.0);
            vec2 spot = (cell + vec2(0.2 + 0.6 * pzoPHash(cell + 3.3), 0.2 + 0.6 * pzoPHash(cell + 8.8))) * cs;
            spot.x += (pzoPHash(cell + 5.5) - 0.5) * 10.0 * ph;
            vec2 d = q - (spot - vec2(0.0, hop));
            return pzoHailBall(d, 2.2 + 0.8 * pzoPHash(cell + 4.4));
        }

        void main()
        {
            float rain = PZOPrecipB.y, snow = PZOPrecipB.z;
            vec2 px = vec2(gl_FragCoord.x, PZOPrecipA.y - gl_FragCoord.y);
            float t = PZOPrecipB.x;
            float layers = PZOPrecipD.y;
            float aRain = 0.0, aSnow = 0.0;
            if (rain > 0.001) {
                vec2 dir = normalize(PZOPrecipC.xy);
                // Hail takes over most of a downpour
                float density = clamp(rain * PZOPrecipD.z * 0.55, 0.0, 0.9) * (1.0 - 0.75 * clamp(PZOPrecipD.w, 0.0, 1.0));
                for (int i = 0; i < 4; i++) {
                    if (float(i) >= layers) break;
                    float k = float(i) / max(layers - 1.0, 1.0);
                    aRain += pzoRainLayer(px, k, t, dir, density * mix(0.8, 1.2, k), rain);
                }
                // A downpour hides the distance in a faint drifting mist
                vec2 m = (px + PZOPrecipA.zw) * 0.004 + PZOPrecipC.xy * t * 0.3;
                aRain += rain * rain * 0.07 * (0.55 + 0.45 * pzoPNoise(m));
            }
            if (snow > 0.001) {
                float density = clamp(snow * PZOPrecipD.z * 0.6, 0.0, 0.9);
                vec2 drift = vec2(PZOPrecipC.x * PZOPrecipC.z * 14.0, 0.0);
                for (int i = 0; i < 4; i++) {
                    if (float(i) >= layers) break;
                    float k = float(i) / max(layers - 1.0, 1.0);
                    aSnow += pzoSnowLayer(px, k, t, density * mix(0.7, 1.3, k), drift);
                }
            }
            float aHail = 0.0, hailShade = 0.0;
            if (PZOPrecipD.w > 0.001) {
                // Hail falls nearly straight, whatever slant the rain had
                vec2 hdir = normalize(mix(vec2(0.0, 1.0), rain > 0.001 ? normalize(PZOPrecipC.xy) : vec2(0.0, 1.0), 0.3));
                float hd = clamp(PZOPrecipD.w * PZOPrecipD.z * 0.45, 0.0, 0.75);
                for (int i = 0; i < 4; i++) {
                    if (float(i) >= layers) break;
                    float k = float(i) / max(layers - 1.0, 1.0);
                    vec2 h = pzoHailLayer(px, k, t, hd * mix(0.8, 1.2, k), hdir);
                    hailShade = mix(hailShade, h.y, h.x / max(aHail + h.x, 0.001));
                    aHail += h.x;
                }
                vec2 b = pzoHailBounce(px, t, clamp(PZOPrecipD.w * 0.7, 0.0, 0.7));
                hailShade = mix(hailShade, b.y, b.x / max(aHail + b.x, 0.001));
                aHail += b.x;
            }
            float a = clamp((aRain * 0.6 + aSnow * 0.85 + aHail) * PZOPrecipB.w, 0.0, 1.0);
            if (a < 0.004) discard;
            vec3 light = PZOPrecipL.w > 0.5 ? pzoPLight(px + PZOPrecipA.zw) : vec3(0.35 + 0.65 * PZOPrecipD.x);
            // Lit like the world under it, never quite black so a dark downpour still reads
            vec3 col = mix(vec3(0.78, 0.85, 0.93), vec3(0.96, 0.97, 1.0), aSnow / max(aRain + aSnow, 0.001));
            // Hailstones: their own shading, icy white with a blue-grey edge
            col = mix(col, vec3(0.86, 0.91, 0.97) * hailShade, clamp(aHail / max(aRain + aSnow + aHail, 0.001), 0.0, 1.0));
            col *= 0.22 + 0.9 * light;
            gl_FragColor = vec4(col, a);
        }
        """;

    /**
     * IsoWeatherFX: update's two SetParticlesStrength calls for snowParticles and rainParticles go through strength(),
     * render() and renderPrecipitation() call the pass first.
     */
    static boolean patchWeatherFx(ClassNode node) {
        MethodNode update = AsmSupport.findMethod(node, "update", "()V");
        MethodNode render = AsmSupport.findMethod(node, "render", "()V");
        MethodNode precip = AsmSupport.findMethod(node, "renderPrecipitation", "()V");
        if (update == null || render == null || precip == null) return false;
        int n = 0;
        String lastField = null;
        for (AbstractInsnNode insn = update.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode fi && insn.getOpcode() == Opcodes.GETFIELD) {
                if (fi.desc.equals("Lzombie/iso/weather/fx/ParticleRectangle;")) lastField = fi.name;
            } else if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                && mi.owner.equals("zombie/iso/weather/fx/ParticleRectangle") && mi.name.equals("SetParticlesStrength")
                && ("snowParticles".equals(lastField) || "rainParticles".equals(lastField))) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/Precipitation", "strength",
                    "(Lzombie/iso/weather/fx/ParticleRectangle;F)V", false);
                update.instructions.set(mi, call);
                insn = call;
                lastField = null;
                n++;
            }
        }
        if (n != 2) return false;
        render.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/Precipitation", "renderUnmasked", "()V", false));
        precip.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/Precipitation", "renderMasked", "()V", false));
        return true;
    }

    static String stateText() {
        return "precip=" + (active() ? 1 : 0) + ";precipDraws=" + draws + ";precipFailed=" + (failed ? 1 : 0);
    }
}
