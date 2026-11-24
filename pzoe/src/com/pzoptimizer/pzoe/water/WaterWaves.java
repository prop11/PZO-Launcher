package com.pzoptimizer.pzoe.water;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.pzoe.PixelLight;
import com.pzoptimizer.pzoe.PuddleSplash;
import com.pzoptimizer.pzoe.weather.WeatherLook;
import com.pzoptimizer.pzoe.weather.WeatherStorm;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.WeakHashMap;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.GameTime;
import zombie.core.Core;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.textures.TextureDraw;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.ClimateMoon;

public final class WaterWaves {
    public static final String TARGET = "zombie/iso/WaterShader";
    private static final String OWNER = "com/pzoptimizer/pzoe/water/WaterWaves";
    private static final int SIZE = 128;
    private static final int RIPPLE_TILE = SIZE / 4;
    private static final WeakHashMap<Shader, Uniforms> programs = new WeakHashMap<>();
    private static int texture;
    private static final int MAX_LIGHTS = 4;
    private static final float[] LIGHTS = new float[MAX_LIGHTS * 8];
    private static final int MAX_RIPPLES = 8;
    private static final float[] RIPPLES = new float[MAX_RIPPLES * 4];
    public static final int MAX_WAKES = 8;
    private static final float[] WAKES = new float[MAX_WAKES * 8];
    private static final int MAX_ICE_HOLES = 12;
    private static final float[] ICE_HOLES = new float[MAX_ICE_HOLES * 2];
    private static volatile long seasonsAskedNanos;
    private static volatile long sunAskedNanos;
    private static long sunSampleNanos;
    private static volatile float[] sun = new float[4];
    private static volatile float ice, algae, leaves, pollen, life, snow, thaw;
    private static double seasonHour = Double.NaN;
    private static java.lang.ref.WeakReference<zombie.iso.IsoCell> seasonCell = new java.lang.ref.WeakReference<>(null);

    private record Uniforms(int sampler, int ready, int lights, int lightCount, int[] scene, int ripples, int rippleCount,
                            int seasons, int iceHoles, int iceHoleCount, int condition, int sun, int zoom, int wakes, int wakeCount,
                            int terrain, int terrainRegion, int life, int storm, int stormReady, int cloud, int cloudInfo,
                            int rippleField, int rippleRegion) {}

    private WaterWaves() {}

    public static void compiled(Shader shader, ShaderProgram program) {
        int sampler = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaveAtlas");
        int ready = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaveReady");
        int lights = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterLights[0]");
        int lightCount = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterLightCount");
        int sceneOrigin = GL20.glGetUniformLocation(program.getShaderID(), "PZOReflOrigin");
        int[] scene = null;
        if (sceneOrigin >= 0) {
            scene = new int[23];
            scene[15] = GL20.glGetUniformLocation(program.getShaderID(), "PZOHeight");
            scene[16] = GL20.glGetUniformLocation(program.getShaderID(), "PZOHist");
            scene[17] = sceneOrigin;
            scene[18] = GL20.glGetUniformLocation(program.getShaderID(), "PZOReflRef");
            scene[19] = GL20.glGetUniformLocation(program.getShaderID(), "PZOReflU");
            scene[20] = GL20.glGetUniformLocation(program.getShaderID(), "PZOReflV");
            scene[21] = GL20.glGetUniformLocation(program.getShaderID(), "PZOReflActors");
            scene[22] = GL20.glGetUniformLocation(program.getShaderID(), "PZOReflActorRegion");
        }
        int ripples = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterRipples[0]");
        int rippleCount = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterRippleCount");
        int seasons = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterSeason");
        int iceHoles = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterIceHoles[0]");
        int iceHoleCount = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterIceHoleCount");
        int condition = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterIceCondition");
        int sun = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterSun");
        int zoom = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterZoom");
        int wakes = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterWakes[0]");
        int wakeCount = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterWakeCount");
        int terrain = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterTerrain");
        int terrainRegion = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterTerrainRegion");
        int life = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterLife");
        int storm = GL20.glGetUniformLocation(program.getShaderID(), "PZOEStormAtlas");
        int stormReady = GL20.glGetUniformLocation(program.getShaderID(), "PZOEStormReady");
        int cloud = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterCloud");
        int cloudInfo = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterCloudInfo");
        int rippleField = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterRippleField");
        int rippleRegion = GL20.glGetUniformLocation(program.getShaderID(), "PZOEWaterRippleRegion");
        if (sampler < 0 && lights < 0 && scene == null && ripples < 0 && seasons < 0 && sun < 0 && zoom < 0 && storm < 0) programs.remove(shader);
        else programs.put(shader, new Uniforms(sampler, ready, lights, lightCount, scene, ripples, rippleCount, seasons,
            iceHoles, iceHoleCount, condition, sun, zoom, wakes, wakeCount, terrain, terrainRegion, life, storm, stormReady, cloud, cloudInfo,
            rippleField, rippleRegion));
    }

    public static void bind(Shader shader, TextureDraw draw, int player) {
        Uniforms uniforms = programs.get(shader);
        if (uniforms == null) return;
        if (uniforms.sun() >= 0) {
            sunAskedNanos = System.nanoTime();
            float[] light = WeatherStorm.flashSun();
            if (light == null) light = sun;
            GL20.glUniform4f(uniforms.sun(), (light[0] - light[1]) * 0.7071068f,
                light[2] * 2.45f, (light[0] + light[1]) * 0.7071068f, light[3]);
        }
        if (uniforms.zoom() >= 0) GL20.glUniform1f(uniforms.zoom(), Core.getInstance().getZoom(player));
        if (uniforms.cloud() >= 0) PixelLight.bindWaterClouds(uniforms.cloud(), uniforms.cloudInfo(), player);
        if (uniforms.lights() >= 0) uploadLights(uniforms);
        if (uniforms.scene() != null) PuddleSplash.waterSceneUniforms(uniforms.scene(), player);
        if (uniforms.ripples() >= 0) uploadRipples(uniforms);
        if (uniforms.rippleField() >= 0) WaterRippleField.bind(uniforms.rippleField(), uniforms.rippleRegion(), player);
        if (uniforms.wakes() >= 0) uploadWakes(uniforms);
        if (uniforms.terrain() >= 0) WaterTerrain.bind(uniforms.terrain(), uniforms.terrainRegion(), player);
        if (uniforms.storm() >= 0) WaterSpectrum.bind(uniforms.storm(), uniforms.stormReady(), draw.u2 * 0.1f);
        if (uniforms.seasons() >= 0) {
            seasonsAskedNanos = System.nanoTime();
            GL20.glUniform4f(uniforms.seasons(), WaterIce.coverage(ice), algae, leaves, pollen);
            if (uniforms.life() >= 0) GL20.glUniform3f(uniforms.life(), life, snow, thaw);
            WaterIce.bindCondition(uniforms.condition(), player);
            if (uniforms.iceHoles() >= 0) uploadIceHoles(uniforms, player);
        }
        if (uniforms.sampler() < 0 || uniforms.ready() < 0) return;
        // Stock water leaves unit 2 active and uses units 0 through 2.
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        try {
            if (texture == 0) texture = createAtlas();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL20.glUniform1i(uniforms.sampler(), 3);
            GL20.glUniform1f(uniforms.ready(), 1.0f);
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
        }
    }

    public static boolean needsSun(long now) {
        if (sunAskedNanos == 0L || now - sunAskedNanos >= 1_000_000_000L
            || now - sunSampleNanos < 100_000_000L) return false;
        sunSampleNanos = now;
        return true;
    }

    public static void setSun(float[] sample) {
        GameTime time = GameTime.getInstance();
        ClimateManager climate = ClimateManager.getInstance();
        if (sample[2] == 0f && time != null && climate != null) {
            var season = climate.getSeason();
            float dawn = season == null ? 6f : season.getDawn();
            float dusk = season == null ? 20f : season.getDusk();
            float hour = time.getTimeOfDay();
            float night = 24f - dusk + dawn;
            float progress = ((hour + 24f - dusk) % 24f) / Math.max(1f, night);
            float altitude = (float) Math.sin(Math.PI * progress);
            float brightness = ClimateMoon.getInstance().getMoonFloat() * Math.max(0f, altitude)
                * Math.max(0f, 1f - WeatherLook.cloud() * 1.65f) * 0.36f;
            if (brightness > 0f) {
                sample[0] = (float) -Math.cos(Math.PI * progress);
                sample[1] = -altitude;
                sample[2] = 0.12f + 0.45f * altitude;
                sample[3] = -brightness;
            }
        }
        sun = sample;
    }

    private static void uploadIceHoles(Uniforms uniforms, int player) {
        float[] holes = WaterIce.visibleHoles();
        var observer = zombie.characters.IsoPlayer.getInstance();
        if (player >= 0 && player < zombie.characters.IsoPlayer.players.length) observer = zombie.characters.IsoPlayer.players[player];
        int count = 0;
        if (holes != null && observer != null) {
            for (int i = holes.length - 2; i >= 0 && count < MAX_ICE_HOLES; i -= 2) {
                if (Math.abs(holes[i] - observer.getX()) > 36f || Math.abs(holes[i + 1] - observer.getY()) > 36f) continue;
                ICE_HOLES[count * 2] = holes[i];
                ICE_HOLES[count * 2 + 1] = holes[i + 1];
                count++;
            }
        }
        GL20.glUniform1i(uniforms.iceHoleCount(), count);
        if (count > 0) GL20.glUniform2fv(uniforms.iceHoles(), ICE_HOLES);
    }

    public static void updateEnvironment(long now, float snowDepth) {
        WaterTerrain.update(now);
        if (seasonsAskedNanos == 0L || now - seasonsAskedNanos >= 1_000_000_000L) {
            seasonHour = Double.NaN;
            return;
        }
        WaterIce.sampleObservers(now);
        var world = zombie.iso.IsoWorld.instance;
        var time = zombie.GameTime.getInstance();
        var climate = zombie.iso.weather.ClimateManager.getInstance();
        if (world == null || world.currentCell == null || time == null || climate == null) return;
        double hour = time.getWorldAgeHours();
        if (world.currentCell != seasonCell.get() || hour < seasonHour) {
            seasonCell = new java.lang.ref.WeakReference<>(world.currentCell);
            seasonHour = hour;
            ice = 0f;
        }
        double dt = Double.isNaN(seasonHour) ? 0 : Math.min(1.0, hour - seasonHour);
        seasonHour = hour;
        float temperature = climate.getTemperature();
        if (temperature < -2f) ice = Math.min(1f, ice + (float) dt * Math.min(0.06f, (-2f - temperature) * 0.004f));
        if (temperature > 1f) ice = Math.max(0f, ice - (float) dt * Math.min(0.09f, (temperature - 1f) * 0.008f));
        algae = (float) Math.min(1.0, Math.max(0.0, (time.getWorldAgeDaysSinceBegin() - 7.0) / 55.0));
        int month = time.getMonth();
        leaves = month == 9 ? 1f : month == 8 || month == 10 ? 0.45f : 0f;
        pollen = (month >= 3 && month <= 5 ? 1f : 0f) * (1f - climate.getPrecipitationIntensity());
        life = Math.max(0f, Math.min(1f, (temperature - 12f) / 10f)) * (1f - climate.getPrecipitationIntensity());
        snow = Math.max(0f, Math.min(1f, snowDepth * 4f));
        thaw = temperature > 1f && ice > 0f ? Math.min(1f, (temperature - 1f) / 8f) : 0f;
    }

    private static void uploadLights(Uniforms uniforms) {
        float[] lights = PuddleSplash.outdoorLightSnapshot();
        int count = 0;
        for (int i = 1; i + 7 < lights.length && count < MAX_LIGHTS; i += 8) {
            if (lights[i + 2] != 0.0f) continue;
            int out = count++ * 8;
            LIGHTS[out] = (lights[i] - lights[i + 1]) * 64.0f;
            LIGHTS[out + 1] = (lights[i] + lights[i + 1]) * 32.0f;
            LIGHTS[out + 2] = lights[i + 3];
            LIGHTS[out + 3] = lights[i + 7];
            System.arraycopy(lights, i + 4, LIGHTS, out + 4, 3);
        }
        GL20.glUniform1i(uniforms.lightCount(), count);
        if (count > 0) GL20.glUniform4fv(uniforms.lights(), LIGHTS);
    }

    private static void uploadRipples(Uniforms uniforms) {
        float[] ripples = PuddleSplash.waterRippleSnapshot();
        int count = 0;
        for (int i = 2; i + 4 < ripples.length; i += 5) {
            if (ripples[i + 4] >= 0.0f) continue;
            int out = count++ % MAX_RIPPLES * 4;
            RIPPLES[out] = (ripples[i] - ripples[i + 1]) * 64.0f;
            RIPPLES[out + 1] = (ripples[i] + ripples[i + 1]) * 32.0f;
            RIPPLES[out + 2] = ripples[i + 3];
            RIPPLES[out + 3] = -ripples[i + 4];
        }
        GL20.glUniform1i(uniforms.rippleCount(), Math.min(count, MAX_RIPPLES));
        if (count > 0) GL20.glUniform4fv(uniforms.ripples(), RIPPLES);
    }

    private static void uploadWakes(Uniforms uniforms) {
        float[] snapshot = PuddleSplash.waterWakeSnapshot();
        int count = (int) snapshot[0];
        GL20.glUniform1i(uniforms.wakeCount(), count);
        if (count == 0) return;
        for (int i = 0; i < count; i++) {
            int in = 1 + i * 9, out = i * 8;
            System.arraycopy(snapshot, in, WAKES, out, 4);
            System.arraycopy(snapshot, in + 5, WAKES, out + 4, 4);
        }
        GL20.glUniform4fv(uniforms.wakes(), WAKES);
    }

    private static int createAtlas() {
        float[] heights = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = (float) x / SIZE;
                float v = (float) y / SIZE;
                heights[y * SIZE + x] = (noise(u + v, u - v, 12)
                    + noise(u - v, v, 10) + noise(u, u + v, 8) + noise(u, v, 6)) * 0.2f;
            }
        }
        var pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float center = height(heights, x, y);
                // 32 shader units per tile means one texel spans 0.25 units. Stock samples 0.08 units apart.
                pixels.put(encode((height(heights, x - 1, y) - height(heights, x + 1, y)) * 0.16f));
                pixels.put(encode((height(heights, x, y - 1) - height(heights, x, y + 1)) * 0.16f));
                pixels.put(encode(center));
                pixels.put(encode(ripple(x, y)));
            }
        }
        pixels.flip();
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        return id;
    }

    private static float height(float[] heights, int x, int y) {
        return heights[(y & (SIZE - 1)) * SIZE + (x & (SIZE - 1))];
    }

    private static byte encode(float v) {
        return (byte) Math.round(128.0f + 127.0f * Math.max(-1.0f, Math.min(1.0f, v)));
    }

    private static float ripple(int x, int y) {
        int frame = (y / RIPPLE_TILE) * 4 + x / RIPPLE_TILE;
        float age = frame / 15.0f;
        float dx = (x % RIPPLE_TILE - 15.5f) / 15.5f;
        float dy = (y % RIPPLE_TILE - 15.5f) / 15.5f;
        float radius = (float) Math.sqrt(dx * dx + dy * dy);
        float windDown = 4.0f * age * (1.0f - age);
        float offset = radius - age * 0.8f;
        float band = Math.max(0.0f, 1.0f - Math.abs(offset) * 7.0f);
        float rim = Math.max(0.0f, 1.0f - radius);
        return (float) (Math.sin(offset * 26.0f) * band * windDown * rim);
    }

    private static float noise(float u, float v, int period) {
        float px = u * period;
        float py = v * period;
        int x = (int) Math.floor(px);
        int y = (int) Math.floor(py);
        float tx = px - x;
        float ty = py - y;
        tx *= tx * (3.0f - 2.0f * tx);
        ty *= ty * (3.0f - 2.0f * ty);
        float a = lattice(x, y, period);
        float b = lattice(x + 1, y, period);
        float c = lattice(x, y + 1, period);
        float d = lattice(x + 1, y + 1, period);
        float low = a + (b - a) * tx;
        float high = c + (d - c) * tx;
        return low + (high - low) * ty;
    }

    private static float lattice(int x, int y, int period) {
        int n = Math.floorMod(x, period) * 0x1f123bb5 ^ Math.floorMod(y, period) * 0x5f356495 ^ period;
        n = (n ^ (n >>> 16)) * 0x7feb352d;
        n = (n ^ (n >>> 15)) * 0x846ca68b;
        return ((n ^ (n >>> 16)) >>> 8) * (2.0f / 16777215.0f) - 1.0f;
    }

    public static boolean patch(ClassNode node) {
        var compile = AsmSupport.findMethod(node, "onCompileSuccess", "(Lzombie/core/opengl/ShaderProgram;)V");
        var update = AsmSupport.findMethod(node, "updateWaterParams", "(Lzombie/core/textures/TextureDraw;I)V");
        if (compile == null || update == null) return false;
        for (var insn = compile.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new VarInsnNode(Opcodes.ALOAD, 1));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "compiled",
                "(Lzombie/core/opengl/Shader;Lzombie/core/opengl/ShaderProgram;)V", false));
            compile.instructions.insertBefore(insn, call);
        }
        for (var insn = update.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new VarInsnNode(Opcodes.ALOAD, 1));
            call.add(new VarInsnNode(Opcodes.ILOAD, 2));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "bind",
                "(Lzombie/core/opengl/Shader;Lzombie/core/textures/TextureDraw;I)V", false));
            update.instructions.insertBefore(insn, call);
        }
        return true;
    }
}
