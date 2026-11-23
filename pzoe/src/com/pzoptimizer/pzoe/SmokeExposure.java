package com.pzoptimizer.pzoe;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.Map;
import java.util.WeakHashMap;
import zombie.WorldSoundManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;
import zombie.network.GameClient;
import zombie.network.GameServer;

public final class SmokeExposure {
    static final String CHARACTER = "zombie/characters/IsoGameCharacter";
    private static final long SAMPLE_INTERVAL = 500_000_000L;
    private static final RoomSmoke rooms = new RoomSmoke();
    private static final Map<IsoPlayer, Dose> doses = new WeakHashMap<>();
    private static final IsoPlayer[] localPlayers = new IsoPlayer[4];
    private static final float[] localSeverity = new float[4];
    private static IsoCell cell;
    private static long sampled;
    private static boolean localActive;

    private SmokeExposure() {}

    private static final class Dose {
        float amount;
        long lastTick, lastCough;
        int reported;
    }

    static Double pulse(IsoPlayer player, boolean enabled) {
        if (GameClient.client) return null;
        long now = System.nanoTime();
        IsoCell current = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell != current) {
            cell = current;
            sampled = 0L;
            doses.clear();
        }
        Dose dose = doses.get(player);
        IsoGridSquare square = player.getCurrentSquare();
        float density = 0f;
        if (enabled && cell != null && square != null && !player.isDead() && !square.isOutside() && square.getRoom() != null) {
            if (sampled == 0L || now - sampled >= SAMPLE_INTERVAL) sampleRooms(now);
            density = rooms.density(square.getRoom());
        }
        if (density == 0f && dose == null) return null;
        if (dose == null) {
            dose = new Dose();
            doses.put(player, dose);
        }
        float dt = dose.lastTick == 0L ? 0f : Math.min(5f, (now - dose.lastTick) * 1e-9f);
        dose.lastTick = now;
        float breath = density * (player.isSneaking() ? 0.28f + 0.5f * density : 1f);
        dose.amount = enabled ? Math.max(0f, Math.min(1f, dose.amount + dt * (0.34f * breath - 0.10f))) : 0f;
        if (dose.amount >= 0.30f && breath >= 0.25f && now - dose.lastCough >= 7_000_000_000L
            && player.getBodyDamage().IsSneezingCoughing() == 0) {
            dose.lastCough = now;
            cough(player, square);
        }
        int severity = Math.round(dose.amount * 10f);
        if (dose.amount == 0f && density == 0f) doses.remove(player);
        if (!GameServer.server) setLocal(player, severity);
        if (severity == dose.reported) return null;
        dose.reported = severity;
        return (double) severity;
    }

    private static void sampleRooms(long now) {
        float dt = sampled == 0L ? 0.5f : (now - sampled) * 1e-9f;
        sampled = now;
        rooms.begin(cell, now);
        for (int i = 0, size = IsoFireManager.FireStack.size(); i < size; i++) {
            IsoFire fire = IsoFireManager.FireStack.get(i);
            IsoGridSquare square = fire == null ? null : fire.getSquare();
            if (square == null || square.isOutside() || square.getRoom() == null) continue;
            rooms.addFire(square, RoomSmoke.intensity(fire));
        }
        var climate = zombie.iso.weather.ClimateManager.getInstance();
        float angle = climate == null ? 0f : climate.getWindAngleRadians();
        float wind = climate == null ? 0f : climate.getWindIntensity();
        rooms.advance(dt, (float) Math.cos(angle) * wind, (float) Math.sin(angle) * wind);
    }

    private static void cough(IsoPlayer player, IsoGridSquare square) {
        player.getBodyDamage().setSneezeCoughActive(2);
        player.getBodyDamage().setSneezeCoughTime(player.getBodyDamage().getSneezeCoughDelay());
        WorldSoundManager.WorldSound sound = WorldSoundManager.instance.addSound(player, square.getX(), square.getY(),
            square.getZ(), 20, 25, false);
        if (sound != null) sound.stressAnimals = false;
        if (GameServer.server) GameServer.sendSneezingCoughing(player, 2, (byte) 0);
    }

    static void setLocal(IsoPlayer player, int severity) {
        int slot = player.getPlayerNum();
        if (slot < 0 || slot >= localPlayers.length) return;
        localPlayers[slot] = severity > 0 ? player : null;
        localSeverity[slot] = Math.max(0, Math.min(10, severity)) * 0.085f;
        localActive = false;
        for (float value : localSeverity) localActive |= value > 0f;
    }

    public static float blur(float vanilla, IsoGameCharacter actor) {
        if (!localActive || !(actor instanceof IsoPlayer player)) return vanilla;
        int slot = player.getPlayerNum();
        return slot >= 0 && slot < localPlayers.length && localPlayers[slot] == player
            ? Math.max(vanilla, localSeverity[slot]) : vanilla;
    }

    static boolean patchBlur(ClassNode type) {
        boolean patched = false;
        for (var method : type.methods) {
            if (!method.name.equals("getBlurFactor") || !method.desc.equals("()F")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.FRETURN) continue;
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/SmokeExposure", "blur",
                    "(FLzombie/characters/IsoGameCharacter;)F", false));
                method.instructions.insertBefore(instruction, hook);
                patched = true;
            }
        }
        return patched;
    }
}
