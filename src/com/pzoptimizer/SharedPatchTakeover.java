package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.ClassReader;
import com.pzoptimizer.shaded.asm.ClassVisitor;
import com.pzoptimizer.shaded.asm.MethodVisitor;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.Type;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.IntInsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PZO keeps its own versions of the optimisations a 3D camera mod also ships, instead of standing down
 * (JVM_ModPatchTakeover).
 *
 * That mod wraps game methods with ZombieBuddy advice that calls small static bridge methods of its own. Where it
 * replaces a method body with its own version of the same speed-up (Lua table reads, ECS class lookups, stat reads,
 * wall checks during separation, light switch power, the cull sort, fish noise), each of those bridges gets a guard
 * at its top: while the switch is on and PZO's patch on that game method went in, the bridge answers "run the game's
 * body" / "keep the game's result", so the body with PZO's patch runs. Its usage counter still ticks first. Off, the
 * bridge runs as written and the mod's version takes over again, live.
 *
 * The cull sort guard only acts outside its own view: there its sort also picks which zombies get models for its 3D
 * camera. Its zombie posing has no bridge to guard; it has a setting of its own that it never saves, which I turn off
 * while BoneBatch runs.
 *
 * Game methods where its advice only watches, or only lowers the result (the scheduler's sim level), go in KEEP: PZO's
 * patch on them stays next to the advice. That is decided per class load, so it follows the setting at launch.
 * Anything I can't verify (another version of the mod, a bridge gone or changed) leaves everything as it was: PZO
 * stands down there like before.
 */
public final class SharedPatchTakeover {

    private static final String PKG = "viewpoint";
    // Versions whose advice and bridges I checked by hand. Another version keeps the old stand-downs.
    private static final Set<String> KNOWN_VERSIONS = Set.of("0.1.7");

    private static final String FIX = PKG + "/FixHooks";
    private static final String HOOKS = PKG + "/Hooks";
    private static final String HIT_OWNER = PKG + "/Compat";

    static final int TABLE = 0, ECS = 1, STATS = 2, BONE = 3, MIN_LEVEL = 4, FISH = 5, WALLS = 6, POWER = 7, LOS = 8,
        CULL = 9, FRAME = 10, FEATURES = 11;
    private static final String[] FEATURE_NAMES = {"Lua table reads", "ECS class lookups", "stat reads",
        "bone reparent checks", "minimum sim level", "fish noise", "separation wall checks", "light switch power",
        "line of sight lists", "zombie cull sort", "frame hook"};

    private enum Kind { SKIP, PASS, VOID, CALL }

    // owner, name, desc, feature, kind, argument passed back (PASS)
    private record Bridge(String owner, String name, String desc, int feature, Kind kind, int arg) {}

    private static final Bridge[] BRIDGES = {
        new Bridge(FIX, "skipTableGet", "(Ljava/lang/Object;)Z", TABLE, Kind.SKIP, -1),
        new Bridge(FIX, "tableGet", "(Lse/krka/kahlua/j2se/KahluaTableImpl;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", TABLE, Kind.PASS, 2),
        new Bridge(FIX, "skipEcsClass", "()Z", ECS, Kind.SKIP, -1),
        new Bridge(FIX, "ecsClass", "(Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/Class;", ECS, Kind.PASS, 1),
        new Bridge(FIX, "skipStatsGet", "()Z", STATS, Kind.SKIP, -1),
        new Bridge(FIX, "statsGet", "(Lzombie/characters/Stats;Lzombie/characters/CharacterStat;F)F", STATS, Kind.PASS, 2),
        new Bridge(FIX, "skipBoneReparented", "()Z", BONE, Kind.SKIP, -1),
        new Bridge(FIX, "boneReparented", "(Lzombie/core/skinnedmodel/animation/AnimationPlayer;IZ)Z", BONE, Kind.PASS, 2),
        new Bridge(FIX, "skipMinimumLevel", "()Z", MIN_LEVEL, Kind.SKIP, -1),
        new Bridge(FIX, "minimumLevel", "(Lzombie/ai/StateMachine;Lzombie/UpdateSchedulerSimulationLevel;)Lzombie/UpdateSchedulerSimulationLevel;", MIN_LEVEL, Kind.PASS, 1),
        new Bridge(FIX, "skipFishNoise", "(Lzombie/iso/FishSchoolManager;III)Z", FISH, Kind.SKIP, -1),
        new Bridge(FIX, "skipBlockedTo", "(Lzombie/iso/IsoGridSquare;Lzombie/iso/IsoGridSquare;)Z", WALLS, Kind.SKIP, -1),
        new Bridge(FIX, "blockedTo", "(Lzombie/iso/IsoGridSquare;Lzombie/iso/IsoGridSquare;Z)Z", WALLS, Kind.PASS, 2),
        new Bridge(HOOKS, "skipSwitchPower", "(Lzombie/iso/objects/IsoLightSwitch;)Z", POWER, Kind.SKIP, -1),
        new Bridge(HOOKS, "switchPower", "(Lzombie/iso/objects/IsoLightSwitch;Z)Z", POWER, Kind.PASS, 1),
        new Bridge(HOOKS, "lineOfSightStarting", "(Lzombie/characters/IsoPlayer;)V", LOS, Kind.VOID, -1),
        new Bridge(FIX, "skipCullCompare", "()Z", CULL, Kind.SKIP, -1),
        new Bridge(FIX, "cullCompared", "(Ljava/util/Comparator;Lzombie/characters/IsoZombie;Lzombie/characters/IsoZombie;I)I", CULL, Kind.PASS, 3),
        new Bridge(FIX, "objectsUpdating", "()V", FRAME, Kind.CALL, -1),
    };

    // The game method PZO's own version patches, per feature: the guard only acts once that patch went in
    private static final String[] FEATURE_TARGET = {
        "se/krka/kahlua/j2se/KahluaTableImpl.rawget",
        "zombie/characters/ecs/ECSComponent.getECSClass",
        "zombie/characters/Stats.get",
        "zombie/core/skinnedmodel/animation/AnimationPlayer.isBoneReparented",
        "zombie/ai/StateMachine.getMinimumSimulationLevel",
        "zombie/iso/FishSchoolManager.addSoundNoise",
        "zombie/iso/IsoMovingObject.separate",
        "zombie/iso/objects/IsoLightSwitch.hasElectricityAround",
        "zombie/characters/IsoPlayer.updateLOS",
        "zombie/iso/IsoWorld$CompScoreToPlayer.compare",
        null,
    };

    // Game methods where the mod's advice watches only or only lowers the result: PZO's patches stay next to it
    private static final Set<String> KEEP = Set.of(
        "zombie/MovingObjectUpdateScheduler.getUpdateSchedulerSimulationLevelForObject",
        "zombie/MovingObjectUpdateScheduler.update",
        "zombie/MovingObjectUpdateScheduler.postupdate",
        "zombie/core/skinnedmodel/ModelManager$ModelSlot.Update",
        "zombie/core/skinnedmodel/animation/AnimationPlayer.updateModelTransforms",
        "zombie/iso/IsoWorld.sceneCullZombies",
        "zombie/iso/IsoWorld.update",
        "zombie/iso/IsoCell.render",
        "zombie/iso/IsoCell.update",
        "zombie/core/physics/WorldSimulation.update",
        "zombie/SoundManager.Update",
        "zombie/iso/LightingJNI.update",
        "zombie/iso/FishSchoolManager.update",
        "zombie/iso/PlayerCamera.update",
        "zombie/characters/IsoPlayer.getDeferredMovement",
        "zombie/core/SpriteRenderer.drawGeneric",
        "zombie/core/SpriteRenderer.postRender",
        "org/lwjglx/opengl/Display.update",
        "zombie/iso/fboRenderChunk/FBORenderLevels.isOnScreen",
        "zombie/EffectsManager.startMuzzleFlash",
        "zombie/iso/weather/ThunderStorm.enqueueThunderEvent",
        "zombie/iso/IsoGridSquare.startWaterSplash",
        "zombie/ui/Clock.render",
        "zombie/Lua/LuaManager.init",
        "zombie/gameStates/GameLoadingState.Done",
        "zombie/gameStates/GameLoadingState.SendDone",
        "zombie/gameStates/GameLoadingState.render",
        "zombie/gameStates/GameLoadingState.update",
        "zombie/core/skinnedmodel/model/Model.CreateShader");

    // Live switch for the guards; the KEEP/feature decisions follow the setting as it was at launch
    public static volatile boolean active = PZOConfig.isModPatchTakeoverEnabled();
    private static final boolean atLaunch = active;

    private static volatile String mod;
    private static volatile String version;
    private static final boolean[] verified = new boolean[FEATURES];
    private static final boolean[] installed = new boolean[FEATURES];
    private static final Map<String, Integer> targetFeature = new HashMap<>();
    private static final List<String> notes = new ArrayList<>();
    private static volatile boolean bridgesPatched;

    // Read by the cull guard on every comparison, so frame() keeps a plain copy of the view flag
    private static volatile boolean ownView = true;
    private static volatile Boolean skeletonApplied;
    private static volatile boolean skeletonFailed;

    static {
        for (int i = 0; i < FEATURES; i++) {
            if (FEATURE_TARGET[i] != null) targetFeature.put(FEATURE_TARGET[i], i);
        }
    }

    private SharedPatchTakeover() {}

    public static void setActive(boolean value) {
        active = value;
        PZOConfig.setModPatchTakeoverEnabled(value);
    }

    /** From the ZombieBuddy scan, before any game class loads: checks the mod's version and bridges in its jar. */
    static synchronized void noteModJar(String modName, String pkg, File jar) {
        if (!PKG.equals(pkg) || mod != null) return;
        try (ZipFile zip = new ZipFile(jar)) {
            String ver = null;
            ZipEntry props = zip.getEntry(PKG + "/build.properties");
            if (props != null) {
                Properties p = new Properties();
                try (InputStream in = zip.getInputStream(props)) {
                    p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                }
                ver = p.getProperty("version");
            }
            version = ver;
            if (ver == null || !KNOWN_VERSIONS.contains(ver)) {
                PZOLogger.info("[SharedPatchTakeover] " + modName + " " + ver + " is not a version I checked, so PZO stands down on its methods as before");
                return;
            }
            Map<String, Set<String>> methods = new HashMap<>();
            for (String owner : new String[] {FIX, HOOKS}) {
                ZipEntry e = zip.getEntry(owner + ".class");
                if (e == null) continue;
                Set<String> found = new HashSet<>();
                try (InputStream in = zip.getInputStream(e)) {
                    new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                            if ((access & Opcodes.ACC_STATIC) != 0) found.add(name + desc);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                }
                methods.put(owner, found);
            }
            boolean[] ok = new boolean[FEATURES];
            java.util.Arrays.fill(ok, true);
            for (Bridge b : BRIDGES) {
                Set<String> found = methods.get(b.owner);
                if (found == null || !found.contains(b.name + b.desc)) ok[b.feature] = false;
            }
            System.arraycopy(ok, 0, verified, 0, FEATURES);
            mod = modName;
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < FEATURES; i++) if (!ok[i]) missing.add(FEATURE_NAMES[i]);
            PZOLogger.info("[SharedPatchTakeover] " + modName + " " + ver + ": PZO keeps its own versions"
                + (atLaunch ? "" : " (off in settings)") + (missing.isEmpty() ? "" : "; not found, left to it: " + missing));
        } catch (Throwable t) {
            PZOLogger.warn("[SharedPatchTakeover] could not read " + jar.getName() + ": " + t);
        }
    }

    /** Whether ZombieBuddyCompat can leave PZO's patch on this method although that mod advises it too. */
    static boolean covers(String modName, String internalClass, String method) {
        if (!atLaunch || mod == null || !mod.equals(modName)) return false;
        String key = internalClass + "." + method;
        if (KEEP.contains(key)) return true;
        Integer f = targetFeature.get(key);
        return f != null && verified[f];
    }

    /** A PZO patch went into these methods of a class the mod also patches. */
    static void noteInstalled(String internalClass, Set<String> methods) {
        if (mod == null) return;
        if ("zombie/MovingObjectUpdateScheduler".equals(internalClass) && methods.contains("postupdate")) postupdateInstalled = true;
        for (String m : methods) {
            Integer f = targetFeature.get(internalClass + "." + m);
            if (f != null && !installed[f]) {
                installed[f] = true;
                synchronized (notes) {
                    notes.add(FEATURE_NAMES[f]);
                }
            }
        }
    }

    // MovingObjectUpdateScheduler.postupdate is where BoneBatch hooks in
    private static volatile boolean postupdateInstalled;

    /** Called from the mod's guarded bridges: true = answer for the game's own body (with PZO's patch). */
    public static boolean on(int feature) {
        if (!active || !installed[feature]) return false;
        switch (feature) {
            case TABLE: return GameThreadPatches.luaFastGet;
            case ECS: return GameThreadPatches.ecsClassCache;
            case STATS: case BONE: case MIN_LEVEL: return HotPathTrims.active;
            case FISH: return FishNoiseCache.active;
            case WALLS: return CrowdEdgeMemo.active;
            case POWER: return GameThreadPatches.lightSwitchCache;
            case LOS: return SpottedListGovernor.isActive();
            case CULL: return CullScoreMemo.active && !ownView;
            default: return false;
        }
    }

    /** Called once a frame from the mod's object update bridge, on the game thread. */
    public static void frame() {
        ownView = ViewpointStanddownGovernor.isOwnViewActive();
        if (skeletonFailed) return;
        boolean takeOver = active && postupdateInstalled && BoneBatch.isActive();
        Boolean applied = skeletonApplied;
        if (applied != null && applied == takeOver) return;
        skeletonApplied = takeOver;
        try {
            ClassLoader loader = ClassSightings.viewpointLoader;
            Class<?> port = Class.forName(PKG + ".sim.SkeletonPort", true, loader);
            Field on = port.getDeclaredField("ON");
            on.setAccessible(true);
            Object toggle = on.get(null);
            Method set = toggle.getClass().getMethod("set", boolean.class);
            // Never saved in its release builds, so this lasts for the session only
            set.invoke(toggle, !takeOver);
            PZOLogger.info("[SharedPatchTakeover] zombie posing: " + (takeOver ? "BoneBatch" : "left to the 3D camera mod"));
        } catch (Throwable t) {
            skeletonFailed = true;
            PZOLogger.warn("[SharedPatchTakeover] zombie posing toggle not found, left as it is: " + t);
        }
    }

    static boolean isBridgeClass(String internalName) {
        return mod != null && (FIX.equals(internalName) || HOOKS.equals(internalName));
    }

    /** Adds the guards to the mod's bridge class as it loads. */
    static byte[] transformBridges(ClassLoader loader, String internalName, byte[] bytes) {
        try {
            // The guards call this class by name, so it has to be the same one the bridge's loader sees
            if (Class.forName(SharedPatchTakeover.class.getName(), false, loader) != SharedPatchTakeover.class) {
                PZOLogger.warn("[SharedPatchTakeover] the bridge's loader sees another copy of PZO, left as it is");
                return null;
            }
        } catch (Throwable t) {
            PZOLogger.warn("[SharedPatchTakeover] the bridge's loader can't see PZO, left as it is: " + t);
            return null;
        }
        byte[] out = AsmSupport.transform(bytes, loader, "SharedPatchTakeover " + internalName, node -> guard(node));
        if (out != null) bridgesPatched = true;
        return out;
    }

    private static boolean guard(ClassNode node) {
        int done = 0;
        for (Bridge b : BRIDGES) {
            if (!b.owner.equals(node.name) || !verified[b.feature]) continue;
            MethodNode m = AsmSupport.findMethod(node, b.name, b.desc);
            if (m == null || (m.access & Opcodes.ACC_STATIC) == 0) continue;
            InsnList g = new InsnList();
            if (b.kind == Kind.CALL) {
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/SharedPatchTakeover", "frame", "()V", false));
            } else {
                LabelNode own = new LabelNode();
                g.add(new IntInsnNode(Opcodes.BIPUSH, b.feature));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/SharedPatchTakeover", "on", "(I)Z", false));
                g.add(new JumpInsnNode(Opcodes.IFEQ, own));
                if (b.kind == Kind.SKIP) {
                    g.add(new InsnNode(Opcodes.ICONST_0));
                    g.add(new InsnNode(Opcodes.IRETURN));
                } else if (b.kind == Kind.VOID) {
                    g.add(new InsnNode(Opcodes.RETURN));
                } else {
                    Type[] args = Type.getArgumentTypes(b.desc);
                    int slot = 0;
                    for (int i = 0; i < b.arg; i++) slot += args[i].getSize();
                    g.add(new VarInsnNode(args[b.arg].getOpcode(Opcodes.ILOAD), slot));
                    g.add(new InsnNode(Type.getReturnType(b.desc).getOpcode(Opcodes.IRETURN)));
                }
                g.add(own);
            }
            // After its usage counter, so its "patches seen working" report stays right
            AbstractInsnNode after = null;
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && HIT_OWNER.equals(mi.owner)
                        && "hit".equals(mi.name)) {
                    after = n;
                    break;
                }
            }
            if (after != null) m.instructions.insert(after, g);
            else m.instructions.insert(g);
            done++;
        }
        if (done > 0) PZOLogger.info("[SharedPatchTakeover] " + node.name + ": " + done + " bridges guarded");
        return done > 0;
    }

    /** For the settings screen: what PZO runs itself next to the mod this session. */
    public static String statusLines() {
        StringBuilder sb = new StringBuilder();
        if (mod == null) return "";
        sb.append("version=").append(version).append('\n');
        sb.append("atLaunch=").append(atLaunch).append('\n');
        sb.append("active=").append(active).append('\n');
        sb.append("bridges=").append(bridgesPatched).append('\n');
        synchronized (notes) {
            for (String n : notes) sb.append("own=").append(n).append('\n');
        }
        Boolean sk = skeletonApplied;
        if (sk != null && sk) sb.append("own=zombie posing").append('\n');
        return sb.toString();
    }
}
