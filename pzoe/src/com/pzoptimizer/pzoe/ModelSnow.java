package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.ShaderPatchHost;
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
 */
public final class ModelSnow {
    static final String UNIFORM = "PZOSnowModel";
    // In the patches' text so Java can tell which cards are on
    static final String MARK_CHARS = "PZO_SNOW_CHARS", MARK_VEHICLES = "PZO_SNOW_VEHICLES";
    private static final String SELF = "com/pzoptimizer/pzoe/ModelSnow";
    private static final String RENDER_DATA = "zombie/core/skinnedmodel/advancedanimation/AnimatedModel$AnimatedModelInstanceRenderData";
    private static final String SHADER = "zombie/core/skinnedmodel/shader/Shader";
    private static final String MODEL = "zombie/core/skinnedmodel/model/Model";
    private static final String ANIMATED_MODEL = "zombie/core/skinnedmodel/advancedanimation/AnimatedModel";
    static final Set<String> TARGETS = Set.of(RENDER_DATA, SHADER, MODEL, ANIMATED_MODEL);

    static volatile boolean wanted, charsWanted, vehiclesWanted;
    private static long lastCheckNs, lastTrackNs;
    private static float clock;
    // Main thread only: {snow, ice, last seen (clock)}
    private static final Map<Object, float[]> state = new IdentityHashMap<>();
    private static int tracked;
    private static float lastIntensity;

    private ModelSnow() {}

    /** Once a frame from PixelLight's frame hook, main thread. */
    static void frame(long now) {
        if (now - lastCheckNs > 1_000_000_000L) {
            lastCheckNs = now;
            boolean c = ShaderPatchHost.liveTextContains(MARK_CHARS);
            boolean v = ShaderPatchHost.liveTextContains(MARK_VEHICLES);
            charsWanted = c;
            vehiclesWanted = v;
            if (!(c || v) && wanted) {
                state.clear();
                tracked = 0;
            }
            wanted = c || v;
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
        float intensity = snowing ? Math.max(0.15f, cm.getPrecipitationIntensity()) : 0f;
        float ground = cm.getSnowFracNow();
        float temp = cm.getTemperature();
        lastIntensity = intensity;
        // No snow falling, none on the ground and nothing left on anyone: nothing to do
        if (!snowing && ground < 0.01f && state.isEmpty()) return;
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
        if (charsWanted) {
            for (IsoMovingObject o : cell.getObjectList()) {
                if (o instanceof IsoGameCharacter c && near(o.getX(), o.getY(), px, py, np)) {
                    character(c, dt, snowing, intensity, ground, temp);
                }
            }
        }
        if (vehiclesWanted) {
            for (BaseVehicle v : cell.getVehicles()) {
                if (near(v.getX(), v.getY(), px, py, np)) vehicle(v, dt, snowing, intensity, ground, temp);
            }
        }
        // Gone from view for half a minute: forget it (comes back as a new arrival)
        float cutoff = clock - 30f;
        for (Iterator<float[]> it = state.values().iterator(); it.hasNext(); ) {
            if (it.next()[2] < cutoff) it.remove();
        }
        tracked = state.size();
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

    private static void character(IsoGameCharacter c, float dt, boolean snowing, float intensity, float ground, float temp) {
        boolean zombie = c instanceof IsoZombie;
        boolean out = outside(c.getCurrentSquare());
        float[] s = state.get(c);
        if (s == null) {
            s = new float[3];
            // Zombies walk in looking like they've been out in it; players and animals start clean
            if (zombie && out) {
                s[0] = ground * (0.55f + 0.4f * spread(c));
                s[1] = s[0] * 0.6f;
            }
            state.put(c, s);
        }
        float a = s[0];
        if (out && snowing) a += dt * intensity / (zombie ? 150f : 200f);
        else if (!out) a -= dt / 40f;
        else if (temp > 1f) a -= dt * temp / 600f;
        // Running and fighting shake some off
        if (!zombie && a > 0f && c.isRunning()) a -= dt / 300f;
        a = clamp01(a);
        s[0] = a;
        // Icing: snow that sat on a zombie melts and freezes again, slowly, and stays a while after
        s[1] = zombie ? clamp01(s[1] + (a * 0.75f - s[1]) * Math.min(1f, dt / 240f)) : 0f;
        s[2] = clock;
    }

    private static void vehicle(BaseVehicle v, float dt, boolean snowing, float intensity, float ground, float temp) {
        boolean out = outside(v.getSquare());
        float speed = Math.abs(v.getCurrentSpeedKmHour());
        boolean engine = v.isEngineRunning();
        float[] s = state.get(v);
        if (s == null) {
            s = new float[3];
            // A car that's been parked out in it already wears the snow on the ground
            if (out) {
                s[0] = ground * (0.85f + 0.15f * spread(v));
                s[1] = temp < 0f ? Math.min(1f, ground * 1.2f) : 0f;
            }
            state.put(v, s);
        }
        float a = s[0];
        if (out && snowing && speed < 5f) a += dt * intensity / 400f;
        // Blown off at speed: about 15 s at 60 km/h
        if (speed > 25f) a -= dt * (speed - 25f) / 500f;
        if (!out) a -= dt / 180f;
        if (temp > 1f) a -= dt * temp / 900f;
        if (engine) a -= dt / 1500f;
        s[0] = clamp01(a);
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
        if (!wanted || data == null || mi == null) return;
        try {
            Object owner = null;
            for (ModelInstance m = mi; m != null && owner == null; m = m.parent) {
                owner = m.object != null ? m.object : m.character;
            }
            float a = 0f, ice = 0f, kind = 0f;
            if (owner instanceof BaseVehicle) {
                kind = 2f;
                if (vehiclesWanted) {
                    float[] s = state.get(owner);
                    if (s != null) {
                        a = s[0];
                        ice = s[1];
                    }
                }
            } else if (owner instanceof IsoGameCharacter) {
                kind = owner instanceof IsoZombie ? 1f : 0f;
                if (charsWanted) {
                    float[] s = state.get(owner);
                    if (s != null) {
                        a = s[0];
                        ice = s[1];
                    }
                }
            }
            float scale = mi.modelScript != null && mi.modelScript.scale > 0f ? mi.modelScript.scale : 1f;
            data.properties.SetVector4(UNIFORM, a, ice, kind, scale);
        } catch (Throwable ignored) {
        }
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
        }
        float[] c = programs.get(prog);
        if (c == null) {
            c = new float[] {GL20.glGetUniformLocation(prog, UNIFORM), Float.NaN, Float.NaN, Float.NaN, Float.NaN};
            programs.put(prog, c);
        }
        if (c[0] < 0f) return;
        float x = 0f, y = 0f, z = 0f, w = 1f;
        ShaderParameter p = d == null ? null : d.properties.GetParameter(UNIFORM);
        if (p != null && p.GetValue() instanceof Vector4f v) {
            x = v.x;
            y = v.y;
            z = v.z;
            w = v.w;
        }
        if (c[1] == x && c[2] == y && c[3] == z && c[4] == w) return;
        c[1] = x;
        c[2] = y;
        c[3] = z;
        c[4] = w;
        GL20.glUniform4f((int) c[0], x, y, z, w);
    }

    // End of Shader.startCharacter: characters, their clothes and what they hold, program bound
    public static void character(Shader shader, ModelInstanceRenderData d) {
        if (!wanted || shader == null) return;
        try {
            upload(shader.getID(), d);
        } catch (Throwable ignored) {
        }
    }

    // Start of Model.DrawVehicle
    public static void vehicleBegin(ModelInstanceRenderData d) {
        vehicleData = wanted ? d : null;
    }

    // Replaces effect.setTint(...) inside Model.DrawVehicle, where the vehicle program is bound
    public static void vehicleTint(Shader shader, float r, float g, float b) {
        shader.setTint(r, g, b);
        if (!wanted) return;
        try {
            upload(shader.getID(), vehicleData);
        } catch (Throwable ignored) {
        }
    }

    // Start of AnimatedModel.DrawCharSingular: UI previews (character creation, the character screen, clothing) use the
    // same program but never set PZOSnowModel, so without this they showed the last world character's snow, e.g. the
    // character who just died on the new one's creation screen
    public static void previewBegin(AnimatedModel.AnimatedModelInstanceRenderData d) {
        previewData = wanted ? d : null;
    }

    // Replaces effect.setTint(...) inside AnimatedModel.DrawCharSingular, program bound
    public static void previewTint(Shader shader, float r, float g, float b) {
        shader.setTint(r, g, b);
        if (!wanted) return;
        try {
            upload(shader.getID(), previewData);
        } catch (Throwable ignored) {
        }
    }

    static String stateText() {
        return "snowModels=" + (wanted ? 1 : 0) + ";snowTracked=" + tracked + ";snowFall=" + lastIntensity;
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
