package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.pzoe.weather.WeatherEvents;
import com.pzoptimizer.pzoe.weather.WeatherLook;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.LdcInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import fmod.fmod.FMODManager;
import fmod.javafmod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import zombie.GameTime;
import zombie.audio.FMODParameterUtils;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.scripting.objects.CharacterTrait;

public final class WeatherAudio {
    static final String AMBIENT = "zombie/AmbientStreamManager";
    private static final String PREFIX = "zombie/audio/parameters/Parameter";
    static final String RAIN = PREFIX + "RainIntensity", SNOW = PREFIX + "SnowIntensity", WIND = PREFIX + "WindIntensity";
    static final String[] PARAMETERS = {RAIN, SNOW, WIND};
    private static final String OWNER = "com/pzoptimizer/pzoe/WeatherAudio";
    private static final long HAIL_MODE = 0x00000002L | 0x00000008L; // FMOD_LOOP_NORMAL | FMOD_2D
    static volatile boolean enabled;
    private static IsoGameCharacter listener;
    private static long lastUpdate, nextSample;
    private static float exposure = 1f, target = 1f;
    private static long hailSound, hailChannel;
    private static boolean hailFailed, shelterFailed;

    private WeatherAudio() {}

    public static void update() {
        RoomAcoustics.update();
        if (!enabled || Core.soundDisabled || GameTime.isGamePaused()) {
            stopHail();
            if (!enabled) listener = null;
            return;
        }
        long now = System.nanoTime();
        var player = FMODParameterUtils.getFirstListener();
        float dt = Math.min(0.1f, (now - lastUpdate) * 1e-9f);
        lastUpdate = now;
        if (player != listener) {
            listener = player;
            nextSample = 0;
            exposure = 1f;
        }
        if (now >= nextSample) {
            nextSample = now + 150_000_000L;
            if (!shelterFailed) {
                try {
                    target = shelter(player, now);
                } catch (RuntimeException | LinkageError problem) {
                    target = 1f;
                    shelterFailed = true;
                    PZOLogger.warn("[PZOE] Weather shelter unavailable: " + problem);
                }
            }
        }
        exposure += (target - exposure) * (1f - (float) Math.exp(-dt / 0.28f));
        if (hailFailed) return;
        try {
            float hail = WeatherEvents.hail();
            if (hail < 0.005f || FMODManager.instance.getNumListeners() == 0
                || player == null || player.getCurrentSquare() == null || player.hasTrait(CharacterTrait.DEAF)) {
                stopHail();
                return;
            }
            if (hailSound == 0) {
                try (var source = WeatherAudio.class.getResourceAsStream("hail.wav.gz.b64")) {
                    if (source == null) throw new IllegalStateException("hail.wav missing");
                    Path wave = Files.createTempFile("pzoe-hail-", ".wav");
                    try {
                        try (var pcm = new GZIPInputStream(Base64.getMimeDecoder().wrap(source))) {
                            Files.copy(pcm, wave, StandardCopyOption.REPLACE_EXISTING);
                        }
                        hailSound = javafmod.FMOD_System_CreateSound(wave.toString(), HAIL_MODE);
                    } finally {
                        try {
                            Files.deleteIfExists(wave);
                        } catch (java.io.IOException inUse) {
                            wave.toFile().deleteOnExit();
                        }
                    }
                }
                if (hailSound == 0) throw new IllegalStateException("FMOD could not load hail.wav");
            }
            boolean starting = hailChannel == 0;
            if (starting) hailChannel = javafmod.FMOD_System_PlaySound(hailSound, true);
            if (hailChannel == 0) return;
            Core options = Core.getInstance();
            float volume = options.getOptionAmbientVolume() * options.getOptionSoundVolume() / 100f;
            float gain = volume * hail * (0.12f + 0.30f * exposure);
            if (javafmod.FMOD_Channel_SetVolume(hailChannel, gain) != 0) {
                stopHail();
                return;
            }
            javafmod.FMOD_Channel_SetLowPassGain(hailChannel, 0.25f + 0.75f * exposure);
            if (starting) javafmod.FMOD_Channel_SetPaused(hailChannel, false);
        } catch (Exception | LinkageError problem) {
            stopHail();
            hailFailed = true;
            PZOLogger.warn("[PZOE] Hail audio unavailable: " + problem);
        }
    }

    public static void stop() {
        RoomAcoustics.clear();
        stopHail();
        if (hailSound != 0) {
            javafmod.FMOD_Sound_Release(hailSound);
            hailSound = 0;
        }
        listener = null;
        target = exposure = 1f;
        nextSample = 0;
    }

    private static void stopHail() {
        if (hailChannel == 0) return;
        javafmod.FMOD_Channel_Stop(hailChannel);
        hailChannel = 0;
    }

    private static float shelter(IsoGameCharacter player, long now) {
        if (player == null || player.getCurrentSquare() == null) return 1f;
        IsoGridSquare square = player.getCurrentSquare();
        float outside = square.haveRoof ? 0.67f : 1f;
        var cell = IsoWorld.instance.currentCell;
        if (outside == 1f && cell != null) {
            IsoGridSquare above = cell.getGridSquare(square.x, square.y, square.z + 1);
            if (above != null && (above.TreatAsSolidFloor() || above.haveRoof)) outside = 0.67f;
        }
        var room = square.getRoom();
        if (room != null && cell != null) {
            outside = 0.16f;
            for (var opening : SmokeOpenings.of(room, cell, now)) {
                float weight = opening.weight(0f, 0f);
                if (weight <= 0f || !opening.to().isOutside()) continue;
                float distance = Math.abs(player.getX() - opening.from().getX() - 0.5f)
                    + Math.abs(player.getY() - opening.from().getY() - 0.5f);
                outside = Math.max(outside, 0.16f + 0.76f * weight / (1f + 0.20f * distance));
            }
        }
        var vehicle = player.getVehicle();
        if (vehicle == null) return outside;
        int seat = vehicle.getSeat(player);
        var part = seat >= 0 ? vehicle.getPassengerDoor(seat) : null;
        var window = part == null ? null : part.findWindow();
        var door = part == null ? null : part.getDoor();
        boolean open = door != null && door.isOpen()
            || window != null && (window.isOpen() || window.isDestroyed() || window.getOpenDelta() > 0.1f);
        return outside * (open ? 0.80f : 0.43f);
    }

    public static float intensity(float vanilla, int kind) {
        if (!enabled) return vanilla;
        return switch (kind) {
            case 0 -> (WeatherLook.isSnow() ? 0f : WeatherLook.precipitation()) * (1f - WeatherEvents.hail() * 0.45f) * (0.18f + exposure * 0.82f);
            case 1 -> (WeatherLook.isSnow() ? WeatherLook.precipitation() : 0f) * (0.18f + exposure * 0.82f);
            case 2 -> WeatherLook.wind() * (0.12f + exposure * 0.88f);
            default -> vanilla;
        };
    }

    public static float thunder(float volume) {
        return enabled ? volume * (0.63f + 0.37f * exposure) : volume;
    }

    static boolean patchAmbient(ClassNode node) {
        var update = AsmSupport.findMethod(node, "update", "()V");
        if (update == null) return false;
        update.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "update", "()V", false));
        var stop = AsmSupport.findMethod(node, "stop", "()V");
        if (stop != null) stop.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "stop", "()V", false));
        for (var instruction : update.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("fmod/javafmod")
                && call.name.equals("FMOD_Studio_EventInstance_SetParameterByName")
                && call.desc.startsWith("(JLjava/lang/String;F)")) {
                for (var previous = call.getPrevious(); previous != null; previous = previous.getPrevious()) {
                    if (!(previous instanceof LdcInsnNode constant)) continue;
                    if ("RainIntensity".equals(constant.cst)) update.instructions.insertBefore(call, intensityHook(0));
                    break;
                }
            }
        }
        return true;
    }

    static boolean patchParameter(ClassNode node) {
        var method = AsmSupport.findMethod(node, "calculateCurrentValue", "()F");
        if (method == null) return false;
        int kind = node.name.equals(RAIN) ? 0 : node.name.equals(SNOW) ? 1 : 2;
        boolean found = false;
        for (var instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.FRETURN) continue;
            method.instructions.insertBefore(instruction, intensityHook(kind));
            found = true;
        }
        return found;
    }

    private static InsnList intensityHook(int kind) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(kind));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "intensity", "(FI)F", false));
        return instructions;
    }

    static boolean patchThunder(ClassNode node) {
        var method = AsmSupport.findMethod(node, "update", "(D)V");
        if (method == null) return false;
        boolean found = false;
        for (var instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals("fmod/javafmod")
                || !call.name.equals("FMOD_Studio_EventInstance_SetVolume")) continue;
            method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "thunder", "(F)F", false));
            found = true;
        }
        return found;
    }
}
