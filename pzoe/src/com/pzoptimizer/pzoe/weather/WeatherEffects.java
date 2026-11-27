package com.pzoptimizer.pzoe.weather;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FrameNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.LdcInsnNode;
import com.pzoptimizer.shaded.asm.tree.LineNumberNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/**
 * PZOE weather phase 3: weather that changes how the game plays. All of it needs the PZOEWeather.Effects sandbox switch
 * and each part has its own; PZOEWeather.Strength scales them (mild by default). Off, every hook hands back vanilla's
 * value untouched. The looks never depend on any of this.
 *
 * Built on vanilla's own weather effects where it has them, never a second copy:
 * - Hearing: vanilla's IsoZombie.getWeatherHearingMultiplier already lets rain and fog cover noise; wind and hail now
 *   do too.
 * - Sight: vanilla's IsoZombie.updateVisionRadius counts rain and fog but not snow; a snowstorm and hail now cut how
 *   far zombies see as well.
 * - Vehicle grip: vanilla takes 0.3 off wheel grip while raining; snow on the ground, ice and wet roads after the rain
 *   now count too (BaseVehicle.updateBulletStats).
 * - Footing: running or sprinting outdoors on ice or packed snow can make you slip and fall (same fall as vanilla's
 *   drunk stumble).
 * - Body: wind chill bites harder when you're wet (ClimateManager.getAirTemperatureForCharacter with wind chill).
 * - Mud: dirt, grass, sand and gravel soften after rain; cars slow, slide and bog, walkers slow (Mud).
 * - Footsteps: fresh snow muffles them, ice and old crusted snow in a hard frost make them louder
 *   (IsoGameCharacter.DoFootstepSound's noise for zombies, radius and volume).
 * - Hail stings without a hat. Thunder draws zombies and dry lightning can start fires (WeatherStorm).
 */
public final class WeatherEffects {
    private static long lastFrameNanos;

    private WeatherEffects() {}

    /** An effect is on: Effects on (and on the server, for an MP client) and its own sandbox switch on. */
    static boolean on(String option, boolean dflt) {
        return WeatherCore.effectsOn() && WeatherCore.sandboxBool(option, dflt);
    }

    /** PZOEWeather.Strength: 1 mild (default), 2 normal, 3 strong. */
    static float strength() {
        int s = WeatherCore.sandboxInt("PZOEWeather.Strength", 1);
        return s <= 1 ? 0.5f : s == 2 ? 1f : 1.5f;
    }

    private static zombie.iso.weather.ClimateManager cm() {
        return zombie.iso.weather.ClimateManager.getInstance();
    }

    // Hearing

    /** Return of IsoZombie.getWeatherHearingMultiplier. */
    public static float hearing(float v) {
        try {
            if (!on("PZOEWeather.Hearing", true)) return v;
            float w = cm().getWindIntensity();
            float mask = strength() * (0.25f * w * (float) Math.sqrt(w) + 0.2f * WeatherEvents.hail);
            return Math.max(0.35f, v - mask);
        } catch (Throwable t) {
            return v;
        }
    }

    // Sight

    /** Replaces the first ClimateManager.getRainIntensity in IsoZombie.updateVisionRadius (its rain penalty). */
    public static float sightRain(zombie.iso.weather.ClimateManager cm) {
        float r = cm.getRainIntensity();
        try {
            if (!on("PZOEWeather.Sight", true)) return r;
            float snow = cm.getPrecipitationIsSnow() ? cm.getPrecipitationIntensity() : 0f;
            return r + strength() * (0.8f * snow * (0.5f + 0.5f * cm.getWindIntensity()) + 0.4f * WeatherEvents.hail);
        } catch (Throwable t) {
            return r;
        }
    }

    // Vehicle grip

    /** After vanilla's rain grip in BaseVehicle.updateBulletStats: snow, ice and wet roads. */
    public static float grip(float frictionMul, zombie.vehicles.BaseVehicle v) {
        try {
            if (!on("PZOEWeather.VehicleGrip", true) || v == null) return frictionMul;
            zombie.iso.IsoGridSquare sq = v.getSquare();
            if (sq != null && (sq.isInARoom() || !sq.isOutside())) return frictionMul;
            zombie.iso.weather.ClimateManager cm = cm();
            float snow = cm.getSnowFracNow();
            float wet = cm.getRainIntensity() > 0f ? 0f : zombie.iso.IsoPuddles.getInstance().getWetGroundFinalValue();
            float loss = strength() * (0.3f * snow + 0.5f * WeatherEvents.groundIce(sq) + 0.1f * wet) + Mud.gripLoss(sq);
            return Math.max(0.15f, frictionMul - loss);
        } catch (Throwable t) {
            return frictionMul;
        }
    }

    // Body

    /** Return of ClimateManager.getAirTemperatureForCharacter(character, windChill). */
    public static float bodyAir(float t, zombie.characters.IsoGameCharacter c, boolean windChill) {
        try {
            if (!windChill || c == null || !on("PZOEWeather.Body", true) || c.getVehicle() != null || !c.isOutside()) return t;
            float wet = c.getStats().get(zombie.characters.CharacterStat.WETNESS) / 100f;
            return t - strength() * 3f * cm().getWindIntensity() * wet;
        } catch (Throwable e) {
            return t;
        }
    }

    // Footsteps

    /**
     * Replaces WorldSoundManager.addSound in IsoGameCharacter.DoFootstepSound: the footstep's noise radius and volume
     * scaled by the ground underfoot (outdoors, ground level). Everything else goes through as vanilla had it.
     */
    public static zombie.WorldSoundManager.WorldSound footstep(zombie.WorldSoundManager wsm, Object source, int x, int y, int z,
                                                               int radius, int volume, boolean stress, float ignore, float stressMod,
                                                               boolean fromZombie, boolean send, boolean remote) {
        try {
            float m = footstepGround(x, y, z);
            if (m != 1f) {
                radius = Math.max(1, Math.round(radius * m));
                volume = Math.max(1, Math.round(volume * m));
            }
        } catch (Throwable ignored) {
        }
        return wsm.addSound(source, x, y, z, radius, volume, stress, ignore, stressMod, fromZombie, send, remote);
    }

    static float footstepGround(int x, int y, int z) {
        if (z != 0 || !on("PZOEWeather.FootstepGround", true)) return 1f;
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        zombie.iso.IsoGridSquare sq = cell == null ? null : cell.getGridSquare(x, y, 0);
        if (sq == null || !sq.isOutside()) return 1f;
        float m = 1f;
        float ice = WeatherEvents.groundIce(sq);
        float cover = WeatherSnow.coverAt(x + 0.5f, y + 0.5f);
        if (ice > 0.3f) {
            m = 1.3f;
        } else if (cover >= 0.5f) {
            float fresh = com.pzoptimizer.pzoe.GroundSnow.freshNow();
            // Powder swallows the sound; old snow in a hard frost crusts over and crunches
            if (cm().getTemperature() < -5f && fresh < 0.3f) m = 1.15f;
            else m = 0.75f - 0.15f * fresh * Math.min(1f, com.pzoptimizer.pzoe.GroundSnow.depthNow());
        }
        return m == 1f ? 1f : Math.max(0.3f, 1f + (m - 1f) * strength() * 1.5f);
    }

    // Footing and hail, once a frame for this machine's own players

    static void localFrame() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : (float) Math.min(0.25, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        if (dt <= 0f || zombie.GameTime.isGamePaused() || zombie.network.GameServer.server) return;
        boolean footing = on("PZOEWeather.Footing", true), hailHurts = on("PZOEWeather.HailHurts", true);
        if (!footing && !hailHurts) return;
        zombie.iso.weather.ClimateManager cm = cm();
        float hail = WeatherEvents.hail;
        float packed = cm.getTemperature() < -1f ? Math.max(0f, cm.getSnowFracNow() - 0.4f) : 0f;
        float s = strength();
        for (zombie.characters.IsoPlayer p : zombie.characters.IsoPlayer.players) {
            if (p == null || p.isDead() || !p.isLocalPlayer() || p.getVehicle() != null || !p.isOutside()) continue;
            if (footing && (p.isRunning() || p.isSprinting())) {
                float ice = WeatherEvents.groundIce(p.getCurrentSquare());
                if (ice > 0.1f || packed > 0f) {
                    float rate = s * (0.03f * ice + 0.01f * packed) * (p.isSprinting() ? 2f : 1f);
                    if (zombie.core.random.Rand.Next(1_000_000) < (1.0 - Math.exp(-rate * dt)) * 1_000_000) slip(p);
                }
            }
            if (hailHurts && hail > 0.2f && p.getWornItem(zombie.scripting.objects.ItemBodyLocation.HAT) == null
                && p.getWornItem(zombie.scripting.objects.ItemBodyLocation.FULL_HAT) == null) {
                // Pain goes on the head itself: the pain stat is rebuilt from the body parts every tick
                zombie.characters.BodyDamage.BodyPart head = p.getBodyDamage().getBodyPart(zombie.characters.BodyDamage.BodyPartType.Head);
                if (head != null) head.setAdditionalPain(Math.min(30f * s, head.getAdditionalPain() + s * hail * 6f * dt));
            }
        }
    }

    // The fall vanilla uses for a drunk stumble while running (IsoPlayer)
    private static void slip(zombie.characters.IsoPlayer p) {
        p.setVariable("BumpDone", false);
        p.clearVariable("BumpFallType");
        p.setBumpType("trippingFromSprint");
        p.setBumpFall(true);
        p.setBumpFallType(zombie.core.random.Rand.NextBool(3) ? "pushedFront" : "pushedBehind");
        p.getActionContext().reportEvent("wasBumped");
    }

    // Dry lightning

    static void maybeStrikeFire(int x, int y) {
        zombie.iso.weather.ClimateManager cm = cm();
        if (cm.getPrecipitationIntensity() > 0.15f || cm.getSnowFracNow() > 0.2f) return;
        if (zombie.core.random.Rand.Next(1000) >= 250 * strength()) return;
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        zombie.iso.IsoGridSquare sq = cell == null ? null : cell.getGridSquare(x, y, 0);
        if (sq == null || !sq.isOutside() || sq.isWaterSquare()) return;
        zombie.iso.objects.IsoFireManager.StartFire(cell, sq, true, 100);
        PZOLogger.info("[PZOE weather] lightning started a fire at " + x + "," + y);
    }

    // Patches

    private static final String FOOTSTEP_ADD = "(Ljava/lang/Object;IIIIIZFFZZZ)Lzombie/WorldSoundManager$WorldSound;";

    public static final String ZOMBIE = "zombie/characters/IsoZombie", VEHICLE = "zombie/vehicles/BaseVehicle",
        CHARACTER = "zombie/characters/IsoGameCharacter";

    /** IsoGameCharacter.getWeatherHearingMultiplier (zombies use it for hearing): result through hearing(). */
    public static boolean patchCharacter(ClassNode node) {
        int hear = 0;
        for (MethodNode m : node.methods) {
            if (m.name.equals("getWeatherHearingMultiplier") && m.desc.equals("()F")) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.FRETURN) {
                        m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherEffects", "hearing", "(F)F", false));
                        hear++;
                    }
                }
            }
        }
        // Footsteps by ground: the one noise call in DoFootstepSound(F)
        int steps = 0;
        for (MethodNode m : node.methods) {
            if (!m.name.equals("DoFootstepSound") || !m.desc.equals("(F)V")) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode mi && mi.owner.equals("zombie/WorldSoundManager")
                    && mi.name.equals("addSound") && mi.desc.equals(FOOTSTEP_ADD)) {
                    m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherEffects", "footstep",
                        "(Lzombie/WorldSoundManager;" + FOOTSTEP_ADD.substring(1), false));
                    steps++;
                    break;
                }
            }
        }
        if (steps != 1) com.pzoptimizer.PZOLogger.warn("[PZOE weather] footsteps: DoFootstepSound noise call not matched");
        // Deep snow wading (WeatherSnow) goes in the same pass over this class
        int snow = WeatherSnow.patchCharacter(node);
        if (snow < 3) com.pzoptimizer.PZOLogger.warn("[PZOE weather] deep snow: " + snow + " of 3 IsoGameCharacter hooks matched");
        return hear > 0 || snow > 0 || steps > 0;
    }

    /** IsoZombie.updateVisionRadius: its rain penalty's rain through sightRain(). */
    public static boolean patchZombie(ClassNode node) {
        int sight = 0;
        for (MethodNode m : node.methods) {
            if (m.name.equals("updateVisionRadius") && m.desc.equals("()V")) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode mi && mi.owner.equals("zombie/iso/weather/ClimateManager")
                        && mi.name.equals("getRainIntensity") && mi.desc.equals("()F")) {
                        MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherEffects", "sightRain",
                            "(Lzombie/iso/weather/ClimateManager;)F", false);
                        m.instructions.set(insn, call);
                        sight++;
                        break;
                    }
                }
            }
        }
        return sight == 1;
    }

    /**
     * BaseVehicle.updateBulletStats: right after vanilla's "if raining, frictionMul -= 0.3", frictionMul goes through
     * grip(). Found by the shape: RainManager.isRaining (+ booleanValue), IFEQ, FLOAD n, LDC 0.3, FSUB, FSTORE n, then the jump target.
     */
    public static boolean patchVehicle(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (!m.name.equals("updateBulletStats") || !m.desc.equals("()V")) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode mi) || insn.getOpcode() != Opcodes.INVOKESTATIC || !mi.name.equals("isRaining")
                    || !mi.owner.endsWith("RainManager")) continue;
                AbstractInsnNode j = next(insn);
                // isRaining() returns a Boolean
                if (j instanceof MethodInsnNode unbox && unbox.name.equals("booleanValue")) j = next(j);
                if (!(j instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) continue;
                AbstractInsnNode load = next(j), ldc = next(load), sub = next(ldc), store = next(sub);
                if (!(load instanceof VarInsnNode lv) || lv.getOpcode() != Opcodes.FLOAD || !(ldc instanceof LdcInsnNode)
                    || sub.getOpcode() != Opcodes.FSUB || !(store instanceof VarInsnNode sv) || sv.getOpcode() != Opcodes.FSTORE || sv.var != lv.var) continue;
                // Code goes after the label vanilla jumps to (and its frame), so it runs raining or not
                AbstractInsnNode at = jump.label;
                while (at.getNext() instanceof FrameNode || at.getNext() instanceof LineNumberNode) at = at.getNext();
                InsnList l = new InsnList();
                l.add(new VarInsnNode(Opcodes.FLOAD, sv.var));
                l.add(new VarInsnNode(Opcodes.ALOAD, 0));
                l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherEffects", "grip", "(FL" + VEHICLE + ";)F", false));
                l.add(new VarInsnNode(Opcodes.FSTORE, sv.var));
                m.instructions.insert(at, l);
                if (WeatherSnow.patchMaxSpeed(node) == 0) com.pzoptimizer.PZOLogger.warn("[PZOE weather] deep snow: BaseVehicle.getMaxSpeed not matched");
                return true;
            }
        }
        return false;
    }

    /** ClimateManager.getAirTemperatureForCharacter(IsoGameCharacter, boolean): its result through bodyAir(). */
    static int patchBodyAir(MethodNode m) {
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.FRETURN) continue;
            InsnList l = new InsnList();
            l.add(new VarInsnNode(Opcodes.ALOAD, 1));
            l.add(new VarInsnNode(Opcodes.ILOAD, 2));
            l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/weather/WeatherEffects", "bodyAir",
                "(FLzombie/characters/IsoGameCharacter;Z)F", false));
            m.instructions.insertBefore(insn, l);
            n++;
        }
        return n;
    }

    private static AbstractInsnNode next(AbstractInsnNode n) {
        AbstractInsnNode x = n.getNext();
        while (x != null && (x instanceof LabelNode || x instanceof LineNumberNode || x instanceof FrameNode)) x = x.getNext();
        return x;
    }
}
