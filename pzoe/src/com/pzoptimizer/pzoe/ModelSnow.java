package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.ShaderPatchHost;
import com.pzoptimizer.pzoe.water.WaterAnimation;
import com.pzoptimizer.pzoe.water.WaterSurface;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Vector4f;
import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.rendering.ShaderParameter;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * Snow that settles on characters, zombies, what they hold, and vehicles, drawn inside the game's own model shaders
 * (patched by PZO Enhancements). One uniform per model, PZOSnowModel = (snow 0..1, ice 0..1, kind, mesh scale),
 * set from the instance's render data, so no extra models, draws or shader variants. Kind 0 = player or animal,
 * 1 = zombie (gets icing), 2 = vehicle (ice = frost on the glass).
 *
 * Main thread: once a second it looks whether a live patch asks for the snow (the card is on); only then does it
 * keep amounts for objects near the players, four times a second, and nothing at all while there's no snow anywhere.
 * Render thread: hands the instance's value to the bound program. Every hook returns at once while no card wants it.
 *
 * The same hooks carry a second value, PZOWetModel = (wet 0..1, grime 0..1, extra rust 0..1, kind), for Rain-Soaked
 * People and Zombies and Weathered Vehicles (the vehicle wear idea, exposure with garages protecting, is from fmac's
 * Atmospherics). Players take the game's own wetness; zombies and vehicles soak while out in the rain and dry
 * indoors. Grime and rust grow with the days since the world started, slower for a car kept under a roof, and only
 * on screen: nothing is saved and the car's real rust is untouched.
 */
public final class ModelSnow {
    static final String UNIFORM = "PZOSnowModel", WET_UNIFORM = "PZOWetModel", CLIP_UNIFORM = "PZOSnowClip";
    private static final String WATER_UNIFORM = "PZOEModelWaterline", MARK_WATER = "PZOE_MODEL_WATERLINE";
    // In the patches' text so Java can tell which cards are on
    static final String MARK_CHARS = "PZO_SNOW_CHARS", MARK_VEHICLES = "PZO_SNOW_VEHICLES", MARK_WET = "PZO_WET_CHARS",
        MARK_WEAR = "PZO_VEHICLE_WEAR", MARK_CLIP = "PZO_SNOW_CLIP";
    // Model Lighting, Model Contact Shadows and Waxy Zombie Skin (one head, PZOE_ModelSnow.lua): where each model
    // stands and turns, and what it is. MARK_MLIGHT = per-pixel light from PZO's light map is compiled in.
    static final String LOOK_POS = "PZOModelPos", LOOK_KIND = "PZOModelLook", MARK_LOOK = "PZO_MODEL_LOOK", MARK_MLIGHT = "PZO_MLIGHT_ON";
    // Levels per unit of a character's posed model space (Model.vectorToWorldCoords: y * 0.61237 = height above its feet)
    private static final float LEVELS_PER_UNIT = 0.61237234f;
    private static final String SELF = "com/pzoptimizer/pzoe/ModelSnow";
    private static final String RENDER_DATA = "zombie/core/skinnedmodel/advancedanimation/AnimatedModel$AnimatedModelInstanceRenderData";
    private static final String SHADER = "zombie/core/skinnedmodel/shader/Shader";
    private static final String MODEL = "zombie/core/skinnedmodel/model/Model";
    private static final String ANIMATED_MODEL = "zombie/core/skinnedmodel/advancedanimation/AnimatedModel";
    static final Set<String> TARGETS = Set.of(RENDER_DATA, SHADER, MODEL, ANIMATED_MODEL);

    // wanted = any of the cards; the rest say which. clipWanted = Better Ground Snow's snow line on people and zombies.
    static volatile boolean wanted, charsWanted, vehiclesWanted, wetWanted, wearWanted, clipWanted, lookWanted, lightWanted;
    private static volatile boolean waterlineWanted;
    private static long lastCheckNs, lastTrackNs, lastFrameNs;
    private static float clock;
    // Main thread only: {snow, ice, last seen (clock), wet, grime, extra rust}; vehicles add {settled snow, caked, engine}
    private static final Map<Object, float[]> state = new IdentityHashMap<>();
    // Main thread only: snow line per character in model units {shown, target, last seen (clock)}
    private static final Map<Object, float[]> clip = new IdentityHashMap<>();
    private static final Map<Object, float[]> waterlines = new java.util.WeakHashMap<>();
    private static int tracked;
    private static float lastIntensity;

    private ModelSnow() {}

    /** Once a frame from PixelLight's frame hook, main thread. */
    static void frame(long now) {
        if (GoreModel.wanted || GoreFx.vehicleOn || com.pzoptimizer.pzoe.gore.GoreCore.woundsLook) GoreModel.frame(now);
        if (now - lastCheckNs > 1_000_000_000L) {
            lastCheckNs = now;
            boolean c = ShaderPatchHost.liveTextContains(MARK_CHARS);
            boolean v = ShaderPatchHost.liveTextContains(MARK_VEHICLES);
            boolean w = ShaderPatchHost.liveTextContains(MARK_WET);
            boolean r = ShaderPatchHost.liveTextContains(MARK_WEAR);
            boolean k = ShaderPatchHost.liveTextContains(MARK_CLIP);
            boolean lk = ShaderPatchHost.liveTextContains(MARK_LOOK);
            waterlineWanted = ShaderPatchHost.liveTextContains(MARK_WATER);
            if (!waterlineWanted) waterlines.clear();
            lightWanted = lk && ShaderPatchHost.liveTextContains(MARK_MLIGHT);
            lookWanted = lk;
            charsWanted = c;
            vehiclesWanted = v;
            wetWanted = w;
            wearWanted = r;
            clipWanted = k;
            if (!k) clip.clear();
            boolean any = c || v || w || r || k || lk || waterlineWanted;
            if (!any && wanted) {
                state.clear();
                tracked = 0;
            }
            wanted = any;
        }
        // The snow line eases every frame toward where the snow is, so it doesn't step as a character walks into a drift
        float fdt = lastFrameNs == 0 ? 0f : Math.min(0.1f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;
        if (!clip.isEmpty()) {
            float k = Math.min(1f, fdt * 6f);
            for (float[] e : clip.values()) e[0] += (e[1] - e[0]) * k;
        }
        if (!wanted || now - lastTrackNs < 250_000_000L) return;
        float dt = lastTrackNs == 0 ? 0f : Math.min(1f, (now - lastTrackNs) / 1e9f);
        lastTrackNs = now;
        if (zombie.GameTime.isGamePaused()) dt = 0f;
        try {
            track(dt);
        } catch (Throwable t) {
            PZOLogger.warn("[ModelSnow] track: " + t);
        }
    }

    private static void track(float dt) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        boolean snowing = cm.isSnowing();
        float intensity = snowing ? Math.max(0.3f, cm.getPrecipitationIntensity()) : 0f;
        // PZOE's own ground snow counts too: vanilla's value can sit low under a deep PZOE blanket
        float ground = Math.max(cm.getSnowFracNow(), Math.min(1f, GroundSnow.depthNow()));
        float temp = cm.getTemperature();
        lastIntensity = intensity;
        // The rain as it's shown (PZOE weather's look: bursts, regional showers, forced events), not vanilla's
        // gameplay value, which stays dry for rain that's only on screen
        float rain = cm.getPrecipitationIsSnow() ? 0f : Math.max(cm.getRainIntensity(), com.pzoptimizer.pzoe.weather.WeatherLook.precipitation());
        boolean snowWork = (charsWanted || vehiclesWanted) && (snowing || ground >= 0.01f || !state.isEmpty());
        boolean clipWork = clipWanted && (GroundSnow.depthNow() > 0.001f || !clip.isEmpty());
        // No snow anywhere and no wet or wear card: nothing to do
        if (!snowWork && !clipWork && !wetWanted && !wearWanted && !waterlineWanted) return;
        float days = wearWanted ? (float) (zombie.GameTime.getInstance().getWorldAgeHours() / 24.0) : 0f;
        clock += dt;
        float px[] = new float[4], py[] = new float[4];
        int np = 0;
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if (p == null) continue;
            px[np] = p.getX();
            py[np] = p.getY();
            np++;
        }
        if (np == 0) return;
        if (charsWanted || wetWanted || clipWork || waterlineWanted) {
            for (IsoMovingObject o : cell.getObjectList()) {
                if (!(o instanceof IsoGameCharacter c) || !near(o.getX(), o.getY(), px, py, np)) continue;
                if (charsWanted || wetWanted) character(c, dt, snowing, intensity, ground, temp, rain);
                if (clipWork) clipLine(c);
                if (waterlineWanted) waterline(c, dt);
            }
        }
        if (!clip.isEmpty()) {
            float gone = clock - 30f;
            for (Iterator<float[]> it = clip.values().iterator(); it.hasNext(); ) {
                if (it.next()[2] < gone) it.remove();
            }
        }
        if (vehiclesWanted || wearWanted) {
            for (BaseVehicle v : cell.getVehicles()) {
                if (near(v.getX(), v.getY(), px, py, np)) vehicle(v, dt, snowing, intensity, ground, temp, rain, days);
            }
        }
        // Gone from view for half a minute: forget it (comes back as a new arrival)
        float cutoff = clock - 30f;
        for (Iterator<float[]> it = state.values().iterator(); it.hasNext(); ) {
            if (it.next()[2] < cutoff) it.remove();
        }
        tracked = state.size();
    }

    // Where this character's snow line sits, in its own model units. In a vehicle there is none.
    private static void clipLine(IsoGameCharacter c) {
        float h = c.getVehicle() != null ? 0f : com.pzoptimizer.pzoe.weather.WeatherSnow.surfaceFor(c);
        float[] e = clip.get(c);
        if (e == null) {
            if (h <= 0f) return;
            e = new float[] {h / LEVELS_PER_UNIT, 0f, 0f};
            clip.put(c, e);
        }
        e[1] = Math.max(0f, h) / LEVELS_PER_UNIT;
        // Getting into a car: no slow slide out of the snow
        if (c.getVehicle() != null) e[0] = 0f;
        e[2] = clock;
    }

    private static void waterline(IsoGameCharacter character, float dt) {
        float depth = character.getVehicle() == null
            ? WaterSurface.liquidDepthAt(character.getCurrentSquare(), character.getX(), character.getY()) : 0f;
        float level = 0.68f * Math.max(0f, (depth - 0.10f) / 0.9f);
        if (level > 0f && character instanceof IsoPlayer player) level = WaterAnimation.waterline(player, level);
        float[] values = waterlines.get(character);
        if (values == null) {
            if (level == 0f) return;
            values = new float[3];
            waterlines.put(character, values);
        }
        values[0] = level;
        if (level > 0f) {
            values[1] = Math.max(values[1], level);
            values[2] = 1f;
        } else {
            values[2] = Math.max(0f, values[2] - dt / 80f);
            if (values[2] == 0f) waterlines.remove(character);
        }
    }

    private static boolean near(float x, float y, float[] px, float[] py, int np) {
        for (int i = 0; i < np; i++) {
            float dx = x - px[i], dy = y - py[i];
            if (dx * dx + dy * dy < 45f * 45f) return true;
        }
        return false;
    }

    private static boolean outside(IsoGridSquare sq) {
        return sq != null && sq.isOutside();
    }

    private static void character(IsoGameCharacter c, float dt, boolean snowing, float intensity, float ground, float temp, float rain) {
        boolean zombie = c instanceof IsoZombie;
        boolean out = outside(c.getCurrentSquare());
        float[] s = state.get(c);
        if (s == null) {
            s = new float[6];
            // Zombies walk in looking like they've been out in it; players and animals start clean
            if (zombie && out) {
                s[0] = ground * (0.55f + 0.4f * spread(c));
                s[1] = s[0] * 0.6f;
                s[3] = rain * (0.6f + 0.4f * spread(c));
            }
            state.put(c, s);
        }
        if (wetWanted) {
            // Soaked through in about a minute of heavy rain; dries in a few minutes, faster indoors
            float w = s[3];
            if (out && rain > 0.05f) w += dt * rain / (zombie ? 60f : 90f);
            else w -= dt / (out ? 300f : 150f);
            w = clamp01(w);
            // A player's own wetness (umbrella, towel, clothes drying) wins when it says wetter
            if (c instanceof IsoPlayer pl) w = Math.max(w, clamp01(pl.getStats().get(CharacterStat.WETNESS) / 100f));
            s[3] = w;
        }
        if (!charsWanted) {
            s[2] = clock;
            return;
        }
        float a = s[0];
        if (out && snowing) a += dt * intensity / (zombie ? 60f : 90f);
        else if (!out) a -= dt / 40f;
        // Zombies seen before the ground snow value settled (just after a load) would stay clean for good, so out in
        // it they ease up to what they'd have arrived with
        if (zombie && out && temp <= 1f) {
            float target = ground * (0.55f + 0.4f * spread(c));
            if (a < target) a += (target - a) * Math.min(1f, dt / 15f);
        }
        else if (temp > 1f) a -= dt * temp / 600f;
        // Running and fighting shake some off
        if (!zombie && a > 0f && c.isRunning()) a -= dt / 300f;
        a = clamp01(a);
        s[0] = a;
        // Icing: snow that sat on a zombie melts and freezes again, slowly, and stays a while after
        s[1] = zombie ? clamp01(s[1] + (a * 0.75f - s[1]) * Math.min(1f, dt / 240f)) : 0f;
        s[2] = clock;
    }

    private static void vehicle(BaseVehicle v, float dt, boolean snowing, float intensity, float ground, float temp, float rain, float days) {
        boolean out = outside(v.getSquare());
        float speed = Math.abs(v.getCurrentSpeedKmHour());
        boolean engine = v.isEngineRunning();
        float[] s = state.get(v);
        boolean fresh = s == null;
        if (fresh) {
            s = new float[10];
            // A car that's been parked out in it already wears the snow on the ground, settled and packed on
            if (out) {
                s[0] = ground * (0.85f + 0.15f * spread(v));
                s[6] = s[0];
                s[1] = temp < 0f ? Math.min(1f, ground * 1.2f) : 0f;
                s[3] = rain;
            }
            state.put(v, s);
        }
        if (wearWanted) {
            float w = s[3];
            if (out && rain > 0.05f) w += dt * rain / 40f;
            else w -= dt / (engine ? 300f : 600f);
            s[3] = clamp01(w);
            // Grime over the first two months, rust creeping on over eight; a roof over it slows both. Rain rinses
            // some grime off while it falls.
            float exposure = out ? 1f : 0.4f, seed = spread(v), seed2 = ((System.identityHashCode(v) >>> 3) & 0xFF) / 255f;
            float grime = clamp01(days / 60f) * (0.45f + 0.55f * seed) * exposure * (out ? 1f - 0.3f * rain : 1f);
            float rust = clamp01(days / 240f) * 0.35f * (0.4f + 0.6f * seed2) * exposure;
            // Driving into a garage shouldn't wipe the car: ease toward the new amount over a couple of minutes
            float k = fresh ? 1f : Math.min(1f, dt / 120f);
            s[4] += (grime - s[4]) * k;
            s[5] += (rust - s[5]) * k;
        }
        if (!vehiclesWanted) {
            s[2] = clock;
            return;
        }
        float a = s[0];
        if (out && snowing && speed < 5f) a += dt * intensity / 240f;
        // Same catch-up as zombies for a car standing cold and parked out in it
        if (out && speed < 1f && !engine && temp <= 1f) {
            float target = ground * (0.85f + 0.15f * spread(v));
            if (a < target) a += (target - a) * Math.min(1f, dt / 15f);
        }
        // Snow that has sat on a parked car for a few minutes packs on: driving only blows the loose fresh snow off
        // (about 15 s at 60 km/h) and leaves the packed patches, which melt in the warm, indoors or off a running engine
        float settled = s[6];
        if (speed < 5f) settled += (a - settled) * Math.min(1f, dt / 300f);
        float keep = settled * 0.6f;
        if (speed > 25f && a > keep) a = Math.max(keep, a - dt * (speed - 25f) / 500f);
        float melt = (out ? 0f : dt / 180f) + (temp > 1f ? dt * temp / 900f : 0f) + (engine ? dt / 1500f : 0f);
        a -= melt;
        settled -= melt;
        s[0] = clamp01(a);
        s[6] = Math.min(clamp01(settled), s[0]);
        // Caked on: thrown up by the wheels and packed against the body by ploughing through snow, heaviest in deep
        // snow at speed; it hangs on long after the rest has blown off
        float cake = s[7];
        float h = speed > 3f ? com.pzoptimizer.pzoe.weather.WeatherSnow.depthAt(v.getX(), v.getY(), v.getZ()) : 0f;
        if (h > 0.02f) cake += dt * Math.min(1f, speed / 30f) * Math.min(1f, h / 0.12f) / 25f;
        else cake -= !out ? dt / 240f : temp > 1f ? dt * temp / 1200f : dt / 3600f;
        s[7] = clamp01(cake);
        // Engine heat: the hood warms through in about 3 minutes of running and cools over 10 once it's off
        s[8] = clamp01(s[8] + (engine ? dt / 180f : -dt / 600f));
        s[9] = engine ? 1f : 0f;
        // Frost on the glass: builds on a cold car standing outside, the heater clears it in about a minute
        float ice = s[1];
        if (engine) ice -= dt / 60f;
        else if (out && temp < 0f) ice += dt * (0.5f + 0.5f * Math.min(1f, -temp / 10f)) / 600f;
        else ice -= dt / 300f;
        s[1] = clamp01(ice);
        s[2] = clock;
    }

    // Stable 0..1 per object, so a crowd doesn't wear identical amounts
    private static float spread(Object o) {
        int h = System.identityHashCode(o) * 0x9E3779B1;
        return ((h >>> 8) & 0xFFFF) / 65535f;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    // Main thread, end of AnimatedModelInstanceRenderData.initModel: the instance's value, from its character or vehicle
    public static void initModel(AnimatedModel.AnimatedModelInstanceRenderData data, ModelInstance mi) {
        if ((!wanted && !GoreModel.wanted) || data == null || mi == null) return;
        try {
            Object owner = null;
            for (ModelInstance m = mi; m != null && owner == null; m = m.parent) {
                owner = m.object != null ? m.object : m.character;
            }
            GoreModel.initModel(data, mi, owner);
            if (!wanted) return;
            float a = 0f, ice = 0f, kind = 0f, wet = 0f, grime = 0f, rust = 0f;
            float[] s = owner == null ? null : state.get(owner);
            float scale = mi.modelScript != null && mi.modelScript.scale > 0f ? mi.modelScript.scale : 1f;
            if (owner instanceof BaseVehicle) {
                kind = 2f;
                // The vehicle shader has no use for the mesh scale: w carries the caked-on snow; z = 2 + 0.5 with the
                // engine running + 0.4 x engine heat
                scale = 0f;
                if (s != null) {
                    if (vehiclesWanted) {
                        a = s[0];
                        ice = s[1];
                        if (s.length > 9) {
                            scale = s[7];
                            kind += 0.5f * s[9] + 0.4f * s[8];
                        }
                    }
                    if (wearWanted) {
                        wet = s[3];
                        grime = s[4];
                        rust = s[5];
                    }
                }
            } else if (owner instanceof IsoGameCharacter) {
                kind = owner instanceof IsoZombie ? 1f : 0f;
                if (s != null) {
                    if (charsWanted) {
                        a = s[0];
                        ice = s[1];
                    }
                    if (wetWanted) wet = s[3];
                }
            }
            data.properties.SetVector4(UNIFORM, a, ice, kind, scale);
            if (lookWanted) look(data, mi, owner);
            if (wetWanted || wearWanted) data.properties.SetVector4(WET_UNIFORM, wet, grime, rust, kind);
            if (waterlineWanted) {
                float[] line = waterlines.get(owner);
                if (line == null) data.properties.SetVector4(WATER_UNIFORM, 0f, 0f, 0f, 0f);
                else data.properties.SetVector4(WATER_UNIFORM, line[0], line[1], line[2], clock);
            }
            if (clipWanted) {
                float[] e = owner == null ? null : clip.get(owner);
                float y = e == null ? 0f : e[0];
                data.properties.SetVector4(CLIP_UNIFORM, y, y > 0.005f ? 1f : 0f, 0f, 0f);
            }
        } catch (Throwable ignored) {
        }
    }

    // Main thread: where the model stands and its rendered turn (Model.vectorToWorldCoords maps the mesh to the world
    // with them), and what it is: y = 0 player or survivor, 1 zombie, 2 animal. x = 0 leaves the look alone (vehicles,
    // world items, anything without a character).
    private static void look(AnimatedModel.AnimatedModelInstanceRenderData data, ModelInstance mi, Object owner) {
        if (!(owner instanceof IsoGameCharacter c) || owner instanceof BaseVehicle) {
            data.properties.SetVector4(LOOK_KIND, 0f, 0f, 0f, 0f);
            return;
        }
        zombie.core.skinnedmodel.animation.AnimationPlayer ap = c.getAnimationPlayer();
        float angle = ap != null ? ap.getRenderedAngle() : 0f;
        data.properties.SetVector4(LOOK_POS, c.getX(), c.getY(), c.getZ(), angle);
        float kind = c instanceof IsoZombie ? 1f : c instanceof zombie.characters.animals.IsoAnimal ? 2f : 0f;
        float attachment = mi.parent != null && mi.model != null && mi.model.isStatic ? 1f : 0f;
        data.properties.SetVector4(LOOK_KIND, 1f, kind, attachment, 0f);
    }

    // Render thread. Location per program, re-read twice a second: a rebuild can hand back the same program id
    private static final Map<Integer, float[]> programs = new HashMap<>();
    private static long programsClearedNs;
    private static ModelInstanceRenderData vehicleData;
    private static AnimatedModel.AnimatedModelInstanceRenderData previewData;

    private static void upload(int prog, AnimatedModel.AnimatedModelInstanceRenderData d) {
        long now = System.nanoTime();
        if (now - programsClearedNs > 500_000_000L) {
            programsClearedNs = now;
            programs.clear();
            localLoc.clear();
        }
        float[] c = programs.get(prog);
        if (c == null) {
            c = new float[] {GL20.glGetUniformLocation(prog, UNIFORM), Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                GL20.glGetUniformLocation(prog, WET_UNIFORM), Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                GL20.glGetUniformLocation(prog, CLIP_UNIFORM), Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                GL20.glGetUniformLocation(prog, LOOK_POS), Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                GL20.glGetUniformLocation(prog, LOOK_KIND), Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                GL20.glGetUniformLocation(prog, "PZOModelLightTex"), GL20.glGetUniformLocation(prog, "PZOModelLightInfo"),
                GL20.glGetUniformLocation(prog, WATER_UNIFORM), Float.NaN, Float.NaN, Float.NaN, Float.NaN};
            programs.put(prog, c);
        }
        if (c[0] >= 0f) send(c, 0, d, UNIFORM, 1f);
        if (c[5] >= 0f) send(c, 5, d, WET_UNIFORM, 0f);
        if (c[10] >= 0f) send(c, 10, d, CLIP_UNIFORM, 0f);
        if (c[15] >= 0f) send(c, 15, d, LOOK_POS, 0f);
        if (c[20] >= 0f) send(c, 20, d, LOOK_KIND, 0f);
        if (c[26] >= 0f) modelLight((int) c[25], (int) c[26]);
        if (c[27] >= 0f) send(c, 27, d, WATER_UNIFORM, 0f);
    }

    /*
     * Model Lighting: player 0's light map on unit 14 (nothing in the model shaders uses it), with its ring, first
     * level and level count. Off with split screen (the map follows player 0) or while PZO isn't keeping the map.
     */
    private static void modelLight(int locTex, int locInfo) {
        int tex = PixelLight.lightTexture(0), ring = PixelLight.lightRing(0);
        boolean ok = tex != 0 && ring > 0 && PixelLight.atmos && IsoPlayer.numPlayers <= 1;
        if (ok && locTex >= 0) {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE14);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, tex);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            GL20.glUniform1i(locTex, 14);
        }
        GL20.glUniform4f(locInfo, ring, PixelLight.BASE_LEVEL, PixelLight.LEVELS, ok ? 1f : 0f);
    }

    // c[at] = location, c[at + 1..4] = last value sent to this program
    private static void send(float[] c, int at, AnimatedModel.AnimatedModelInstanceRenderData d, String name, float w0) {
        float x = 0f, y = 0f, z = 0f, w = w0;
        ShaderParameter p = d == null ? null : d.properties.GetParameter(name);
        if (p != null && p.GetValue() instanceof Vector4f v) {
            x = v.x;
            y = v.y;
            z = v.z;
            w = v.w;
        }
        if (c[at + 1] == x && c[at + 2] == y && c[at + 3] == z && c[at + 4] == w) return;
        c[at + 1] = x;
        c[at + 2] = y;
        c[at + 3] = z;
        c[at + 4] = w;
        GL20.glUniform4f((int) c[at], x, y, z, w);
    }

    // End of Shader.startCharacter: characters, their clothes and what they hold, program bound
    public static void character(Shader shader, ModelInstanceRenderData d) {
        if ((!wanted && !GoreModel.wanted) || shader == null) return;
        try {
            if (GoreModel.wanted) GoreModel.upload(shader.getID(), d);
            if (wanted) upload(shader.getID(), d);
        } catch (Throwable ignored) {
        }
    }

    // Start of Model.DrawVehicle
    public static void vehicleBegin(ModelInstanceRenderData d) {
        vehicleData = wanted || GoreModel.wanted ? d : null;
    }

    // Replaces effect.setTint(...) inside Model.DrawVehicle, where the vehicle program is bound
    public static void vehicleTint(Shader shader, float r, float g, float b) {
        shader.setTint(r, g, b);
        if (!wanted && !GoreModel.wanted) return;
        try {
            if (GoreModel.wanted) GoreModel.upload(shader.getID(), vehicleData);
            if (wanted) upload(shader.getID(), vehicleData);
            if (vehiclesWanted) vehicleLocal(shader.getID(), vehicleData);
        } catch (Throwable ignored) {
        }
    }

    /*
     * Where on the car a pixel is, for the hood melting first and the wipers (game vehicle shaders only):
     * PZOVehLocal maps the mesh's vertices into the car's own box as 0..1 (x across, y up, z rear to front). The
     * car's world turn sits in the model-view: xfrm = chassis turn * (model offset, scale) * mesh transform, and
     * vehicleTransform = chassis turn * (model offset, scale), so vehicleTransform^-1 * xfrm leaves only an offset
     * and a scale in the car's frame, which the box takes out. The box is the union of every part drawn for that car.
     * Render thread.
     */
    static final String LOCAL_UNIFORM = "PZOVehLocal";
    // Which way the car's front points along its local z: +1 if the script's front (centerOfMassOffset.z +
    // extents.z / 2) is +z. If the hood melts at the boot in game, this is the sign to flip.
    private static final float FRONT_SIGN = 1f;
    private static final Map<Integer, Integer> localLoc = new HashMap<>();
    private static final java.util.WeakHashMap<BaseVehicle, float[]> boxes = new java.util.WeakHashMap<>();
    private static final org.joml.Matrix4f mLocal = new org.joml.Matrix4f(), mTmp = new org.joml.Matrix4f();
    private static final org.joml.Vector3f vTmp = new org.joml.Vector3f();
    private static final java.nio.FloatBuffer matBuf = java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();

    private static void vehicleLocal(int prog, ModelInstanceRenderData d) {
        if (d == null || d.modelInstance == null) return;
        Integer loc = localLoc.get(prog);
        if (loc == null) {
            if (localLoc.size() > 64) localLoc.clear();
            loc = GL20.glGetUniformLocation(prog, LOCAL_UNIFORM);
            localLoc.put(prog, loc);
        }
        if (loc < 0) return;
        ModelInstance mi = d.modelInstance;
        BaseVehicle v = null;
        for (ModelInstance m = mi; m != null && v == null; m = m.parent) {
            if (m.object instanceof BaseVehicle bv) v = bv;
        }
        if (v == null || mi.model == null || mi.model.mesh == null) return;
        mLocal.set(v.vehicleTransform).invert().mul(d.xfrm);
        float[] box = boxes.get(v);
        if (box == null) {
            box = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            boxes.put(v, box);
        }
        org.joml.Vector3f lo = mi.model.mesh.minXyz, hi = mi.model.mesh.maxXyz;
        if (lo.x > hi.x) return;
        for (int c = 0; c < 8; c++) {
            vTmp.set((c & 1) == 0 ? lo.x : hi.x, (c & 2) == 0 ? lo.y : hi.y, (c & 4) == 0 ? lo.z : hi.z);
            mLocal.transformPosition(vTmp);
            box[0] = Math.min(box[0], vTmp.x);
            box[1] = Math.min(box[1], vTmp.y);
            box[2] = Math.min(box[2], vTmp.z);
            box[3] = Math.max(box[3], vTmp.x);
            box[4] = Math.max(box[4], vTmp.y);
            box[5] = Math.max(box[5], vTmp.z);
        }
        float sx = Math.max(1e-4f, box[3] - box[0]), sy = Math.max(1e-4f, box[4] - box[1]), sz = Math.max(1e-4f, box[5] - box[2]);
        // To 0..1 in the box; z flipped when the front points down local z
        mTmp.identity();
        if (FRONT_SIGN < 0f) mTmp.translate(0f, 0f, 1f).scale(1f, 1f, -1f);
        mTmp.scale(1f / sx, 1f / sy, 1f / sz).translate(-box[0], -box[1], -box[2]).mul(mLocal);
        matBuf.clear();
        mTmp.get(matBuf);
        GL20.glUniformMatrix4fv(loc, false, matBuf);
    }

    // Start of AnimatedModel.DrawCharSingular: UI previews (character creation, the character screen, clothing) use the
    // same program but never set PZOSnowModel, so without this they showed the last world character's snow, e.g. the
    // character who just died on the new one's creation screen
    public static void previewBegin(AnimatedModel.AnimatedModelInstanceRenderData d) {
        previewData = wanted || GoreModel.wanted ? d : null;
    }

    // Replaces effect.setTint(...) inside AnimatedModel.DrawCharSingular, program bound
    public static void previewTint(Shader shader, float r, float g, float b) {
        shader.setTint(r, g, b);
        if (!wanted && !GoreModel.wanted) return;
        try {
            if (GoreModel.wanted) GoreModel.upload(shader.getID(), previewData);
            if (wanted) upload(shader.getID(), previewData);
        } catch (Throwable ignored) {
        }
    }

    static String stateText() {
        return "snowModels=" + (wanted ? 1 : 0) + ";snowTracked=" + tracked + ";snowFall=" + lastIntensity + ";wetModels=" + (wetWanted ? 1 : 0)
            + ";wearModels=" + (wearWanted ? 1 : 0) + ";lookModels=" + (lookWanted ? 1 : 0) + ";lightModels=" + (lightWanted ? 1 : 0);
    }

    static byte[] postTransform(ClassLoader loader, String cn, byte[] bytes) {
        if (cn == null || bytes == null || !TARGETS.contains(cn)) return null;
        return AsmSupport.transform(bytes, loader, "ModelSnow " + cn, ModelSnow::patch);
    }

    static boolean patch(ClassNode node) {
        switch (node.name) {
            case RENDER_DATA -> {
                MethodNode m = AsmSupport.findMethod(node, "initModel",
                    "(Lzombie/core/skinnedmodel/model/ModelInstance;L" + RENDER_DATA + ";)V");
                if (m == null) return false;
                beforeReturns(m, () -> {
                    InsnList l = new InsnList();
                    l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    l.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "initModel",
                        "(L" + RENDER_DATA + ";Lzombie/core/skinnedmodel/model/ModelInstance;)V", false));
                    return l;
                });
                return true;
            }
            case SHADER -> {
                MethodNode m = AsmSupport.findMethod(node, "startCharacter",
                    "(Lzombie/core/skinnedmodel/model/ModelSlotRenderData;Lzombie/core/skinnedmodel/model/ModelInstanceRenderData;)V");
                if (m == null) return false;
                beforeReturns(m, () -> {
                    InsnList l = new InsnList();
                    l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    l.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "character",
                        "(L" + SHADER + ";Lzombie/core/skinnedmodel/model/ModelInstanceRenderData;)V", false));
                    return l;
                });
                return true;
            }
            case MODEL -> {
                MethodNode m = AsmSupport.findMethod(node, "DrawVehicle",
                    "(Lzombie/core/skinnedmodel/model/ModelSlotRenderData;Lzombie/core/skinnedmodel/model/ModelInstanceRenderData;)V");
                if (m == null) return false;
                if (AsmSupport.redirectCalls(m, SHADER, "setTint", "(FFF)V", SELF, "vehicleTint", "(L" + SHADER + ";FFF)V") == 0) return false;
                InsnList pre = new InsnList();
                pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
                pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "vehicleBegin",
                    "(Lzombie/core/skinnedmodel/model/ModelInstanceRenderData;)V", false));
                m.instructions.insert(pre);
                return true;
            }
            case ANIMATED_MODEL -> {
                MethodNode m = AsmSupport.findMethod(node, "DrawCharSingular", "(L" + RENDER_DATA + ";)V");
                if (m == null) return false;
                if (AsmSupport.redirectCalls(m, SHADER, "setTint", "(FFF)V", SELF, "previewTint", "(L" + SHADER + ";FFF)V") == 0) return false;
                InsnList pre = new InsnList();
                pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "previewBegin", "(L" + RENDER_DATA + ";)V", false));
                m.instructions.insert(pre);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void beforeReturns(MethodNode m, java.util.function.Supplier<InsnList> call) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) m.instructions.insertBefore(insn, call.get());
        }
    }
}
