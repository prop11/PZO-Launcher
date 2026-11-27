package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.Set;

final class MuzzleFlashHooks {
    private static final String LIGHTS = "com/pzoptimizer/pzoe/FlashLights";
    private static final String EFFECTS = "zombie/EffectsManager";
    private static final String PLAYER = "zombie/characters/IsoPlayer";
    private static final String CHARACTER = "Lzombie/characters/IsoGameCharacter;";
    private static final String SOUND_PACKET = "zombie/network/packets/sound/RangedWeaponSoundPacket";
    private static final String HIT_PACKET = "zombie/network/packets/hit/PlayerHit";
    private static final String MODELS = "zombie/core/skinnedmodel/model/ModelSlotRenderData";
    private static final String MODEL = "zombie/core/skinnedmodel/model/Model";
    private static final String SLOT = "Lzombie/core/skinnedmodel/ModelManager$ModelSlot;";
    private static final String FLASH = "(" + CHARACTER + "I)V";
    static final Set<String> TARGETS = Set.of(EFFECTS, PLAYER, SOUND_PACKET, HIT_PACKET, MODELS);

    private MuzzleFlashHooks() {}

    static boolean patch(ClassNode node) {
        return switch (node.name) {
            case EFFECTS -> replaceCall(node, "startMuzzleFlash", FLASH, "java/util/Stack", "add",
                "(Ljava/lang/Object;)Z", "submitVanilla", "(Ljava/util/Stack;Ljava/lang/Object;" + CHARACTER + ")Z", 1);
            case HIT_PACKET -> replaceCall(node, "processTracers", "()V", EFFECTS, "startMuzzleFlash",
                FLASH, "tracerFlash", "(L" + EFFECTS + ";" + CHARACTER + "I)V", -1);
            case PLAYER -> patchPlayer(node);
            case SOUND_PACKET -> patchRemoteSound(node);
            case MODELS -> patchModel(node);
            default -> false;
        };
    }

    private static boolean replaceCall(ClassNode node, String method, String signature, String owner, String name,
                                       String descriptor, String hook, String hookDescriptor, int characterSlot) {
        var target = AsmSupport.findMethod(node, method, signature);
        if (target == null) return false;
        MethodInsnNode call = null;
        for (var insn : target.instructions.toArray()) {
            if (insn instanceof MethodInsnNode candidate && candidate.owner.equals(owner) && candidate.name.equals(name)
                && candidate.desc.equals(descriptor) && candidate.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                if (call != null) return false;
                call = candidate;
            }
        }
        if (call == null) return false;
        if (characterSlot >= 0) target.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, characterSlot));
        target.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, LIGHTS, hook, hookDescriptor, false));
        return true;
    }

    private static boolean patchPlayer(ClassNode node) {
        boolean changed = false;
        var shot = AsmSupport.findMethod(node, "playRangedWeaponShootSound", "(Ljava/lang/String;)J");
        if (shot != null) {
            for (var insn : shot.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.LRETURN) continue;
                shot.instructions.insertBefore(insn, hookWithThis("audio", "(" + CHARACTER + ")V"));
                changed = true;
            }
        }
        var loop = AsmSupport.findMethod(node, "startAttackLoopSound", "(Ljava/lang/String;)V");
        if (loop != null) {
            for (var insn : loop.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals("zombie/audio/LoopedRangedWeaponSounds")
                    && call.name.equals("startAttackLoopSound") && call.desc.equals("(Ljava/lang/Object;Ljava/lang/String;)V")) {
                    loop.instructions.insert(call, hookWithThis("audio", "(" + CHARACTER + ")V"));
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean patchRemoteSound(ClassNode node) {
        var method = AsmSupport.findMethod(node, "processClient", "(Lzombie/core/raknet/UdpConnection;)V");
        if (method == null || node.fields.stream().noneMatch(field -> field.name.equals("playerId") && field.desc.equals("S"))) {
            return false;
        }
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.equals("zombie/audio/BaseSoundEmitter")
                || !call.name.equals("tick") || !call.desc.equals("()V")) continue;
            var hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new FieldInsnNode(Opcodes.GETFIELD, SOUND_PACKET, "playerId", "S"));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LIGHTS, "remoteAudio", "(S)V", false));
            method.instructions.insert(call, hook);
            return true;
        }
        return false;
    }

    private static boolean patchModel(ClassNode node) {
        var method = AsmSupport.findMethod(node, "init", "(" + SLOT + ")L" + MODELS + ";");
        if (method == null) return false;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(MODEL) || !call.name.equals("setLightsInst")
                || !call.desc.equals("(L" + MODELS + ";I)V")) continue;
            method.instructions.insertBefore(call, hookWithThis("model", "(L" + MODELS + ";)V"));
            return true;
        }
        return false;
    }

    private static InsnList hookWithThis(String name, String descriptor) {
        var hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LIGHTS, name, descriptor, false));
        return hook;
    }
}
