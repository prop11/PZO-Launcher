package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import fmod.fmod.FMODSoundEmitter;
import fmod.javafmod;
import java.util.ArrayList;
import java.util.Locale;
import zombie.GameTime;
import zombie.audio.FMODParameterUtils;
import zombie.audio.GameSoundClip;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.areas.IsoRoom;
import zombie.scripting.objects.CharacterTrait;

public final class RoomAcoustics {
    static final String EMITTER = "fmod/fmod/FMODSoundEmitter";
    static final String OCCLUSION = "zombie/audio/parameters/ParameterOcclusion";
    private static final String OWNER = "com/pzoptimizer/pzoe/RoomAcoustics";
    static volatile boolean enabled;
    private static boolean failed;
    private static final int MAX_PENDING = 20;
    private static final ArrayList<Reflection> pending = new ArrayList<>(MAX_PENDING);

    private record Reflection(long when, long event, float x, float y, float z, float gain) {}

    private RoomAcoustics() {}

    public static void started(FMODSoundEmitter emitter, GameSoundClip clip, boolean remote) {
        if (!enabled || failed || Core.soundDisabled || pending.size() >= MAX_PENDING || clip == null
            || clip.event == null || clip.gameSound.isLooped()) return;
        String event = clip.event.toLowerCase(Locale.ROOT);
        String name = clip.gameSound.name;
        if (!(event.contains("footstep") || event.contains("door") || event.contains("vocal")
            || event.contains("voice") || name != null && (name.startsWith("HumanFoot")
                || name.startsWith("Door") || name.contains("Cough") || name.contains("Sneeze")))) return;
        try {
            var listener = FMODParameterUtils.getFirstListener();
            var cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            if (listener == null || cell == null || listener.getCurrentSquare() == null || listener.hasTrait(CharacterTrait.DEAF)) return;
            if (Math.abs(listener.getX() - emitter.x) + Math.abs(listener.getY() - emitter.y) > 16f) return;
            IsoGridSquare source = cell.getGridSquare(emitter.x, emitter.y, emitter.z);
            IsoRoom room = source == null ? null : source.getRoom();
            if (room == null) return;
            long now = System.nanoTime();
            IsoGridSquare hearing = listener.getCurrentSquare();
            float transmission = hearing == source || hearing.getRoom() == room ? 1f : opening(source, hearing, now);
            if (transmission <= 0f) return;
            var description = remote && clip.eventDescriptionMp != null ? clip.eventDescriptionMp : clip.eventDescription;
            if (description == null || description.address == 0) return;
            int area = room.def == null ? room.getSquares().size() : room.def.getArea();
            String roomName = room.getName();
            var sprite = source.getFloor() == null ? null : source.getFloor().getSprite();
            String floor = sprite == null ? null : sprite.getName();
            boolean hard = roomName != null && (roomName.contains("bath") || roomName.contains("garage"))
                || floor != null && (floor.contains("tile") || floor.contains("concrete"));
            boolean hall = area >= 48;
            if (!hard && !hall) return;
            float gain = clip.getEffectiveVolume() * transmission * (hall ? 0.14f : 0.12f);
            add(now, hall ? Math.min(135, 40 + area / 3) : 24, description.address, emitter, gain);
            if (hall) add(now, Math.min(230, 75 + area / 2), description.address, emitter, gain * 0.47f);
        } catch (RuntimeException | LinkageError problem) {
            disable(problem);
        }
    }

    private static void add(long now, int delayMs, long event, FMODSoundEmitter emitter, float gain) {
        if (pending.size() < MAX_PENDING && gain > 0.003f) {
            pending.add(new Reflection(now + delayMs * 1_000_000L, event, emitter.x, emitter.y, emitter.z, gain));
        }
    }

    static void update() {
        if (pending.isEmpty()) return;
        if (!enabled || GameTime.isGamePaused() || Core.soundDisabled) {
            clear();
            return;
        }
        long now = System.nanoTime();
        try {
            for (int i = pending.size() - 1; i >= 0; i--) {
                Reflection tap = pending.get(i);
                if (tap.when > now) continue;
                pending.remove(i);
                if (now - tap.when > 300_000_000L) continue;
                long instance = javafmod.FMOD_Studio_System_CreateEventInstance(tap.event);
                if (instance == 0) continue;
                try {
                    javafmod.FMOD_Studio_EventInstance3D(instance, tap.x, tap.y, tap.z * 3f);
                    javafmod.FMOD_Studio_EventInstance_SetVolume(instance, tap.gain);
                    javafmod.FMOD_Studio_StartEvent(instance);
                } finally {
                    javafmod.FMOD_Studio_ReleaseEventInstance(instance);
                }
            }
        } catch (RuntimeException | LinkageError problem) {
            disable(problem);
        }
    }

    static void clear() {
        pending.clear();
    }

    private static void disable(Throwable problem) {
        failed = true;
        clear();
        PZOLogger.warn("[PZOE] Room acoustics unavailable: " + problem);
    }

    public static float occlusion(float vanilla, FMODSoundEmitter emitter, int playerIndex) {
        if (!enabled || failed || vanilla <= 0f || emitter == null) return vanilla;
        try {
            var player = IsoPlayer.players[playerIndex];
            var cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            if (player == null || cell == null || player.getCurrentSquare() == null
                || Math.abs(player.getX() - emitter.x) + Math.abs(player.getY() - emitter.y) > 16f) return vanilla;
            var source = cell.getGridSquare(emitter.x, emitter.y, emitter.z);
            if (source == null) return vanilla;
            float path = opening(source, player.getCurrentSquare(), System.nanoTime());
            return vanilla * (1f - path * 0.75f);
        } catch (RuntimeException | LinkageError problem) {
            disable(problem);
            return vanilla;
        }
    }

    private static float opening(IsoGridSquare source, IsoGridSquare listener, long now) {
        if (source.z != listener.z) return 0f;
        var room = source.getRoom();
        var other = listener.getRoom();
        if (room == other) return room == null ? 0f : 0.6f;
        boolean reverse = room == null;
        if (reverse) room = other;
        IsoGridSquare inside = reverse ? listener : source;
        IsoGridSquare outside = reverse ? source : listener;
        float path = 0f;
        for (var portal : SmokeOpenings.of(room, IsoWorld.instance.currentCell, now)) {
            if (portal.to().getRoom() != outside.getRoom() || portal.to().z != source.z) continue;
            int distance = Math.abs(inside.x - portal.from().x) + Math.abs(inside.y - portal.from().y)
                + Math.abs(outside.x - portal.to().x) + Math.abs(outside.y - portal.to().y);
            if (distance <= 12) path = Math.max(path, portal.weight(0f, 0f) / (1f + distance * 0.10f));
        }
        return Math.min(1f, path);
    }

    static boolean patchEmitter(ClassNode node) {
        var method = AsmSupport.findMethod(node, "startEvent", "(JLzombie/audio/GameSoundClip;Z)V");
        if (method == null) return false;
        var hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
        hook.add(new VarInsnNode(Opcodes.ILOAD, 4));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "started",
            "(L" + EMITTER + ";Lzombie/audio/GameSoundClip;Z)V", false));
        method.instructions.insert(hook);
        return true;
    }

    static boolean patchOcclusion(ClassNode node) {
        var method = AsmSupport.findMethod(node, "calculateValueForPlayer", "(I)F");
        if (method == null) return false;
        boolean patched = false;
        for (var instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.FRETURN) continue;
            var hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new FieldInsnNode(Opcodes.GETFIELD, OCCLUSION, "emitter", "L" + EMITTER + ";"));
            hook.add(new VarInsnNode(Opcodes.ILOAD, 1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "occlusion", "(FL" + EMITTER + ";I)F", false));
            method.instructions.insertBefore(instruction, hook);
            patched = true;
        }
        return patched;
    }
}
