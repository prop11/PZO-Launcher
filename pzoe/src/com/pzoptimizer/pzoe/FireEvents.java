package com.pzoptimizer.pzoe;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.WeakHashMap;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;

public final class FireEvents {
    static final String FIRE = "zombie/iso/objects/IsoFire";
    static final String MANAGER = "zombie/iso/objects/IsoFireManager";
    private static final String HOOKS = "com/pzoptimizer/pzoe/FireEvents";
    private static final String SCORCH = "com/pzoptimizer/pzoe/Scorch";
    private static final String SQUARE = "Lzombie/iso/IsoGridSquare;";
    private static final String FIRE_ARG = "L" + FIRE + ";";
    private static final String START = "(Lzombie/iso/IsoCell;" + SQUARE + "ZI)V";
    private static final WeakHashMap<IsoFire, Boolean> origins = new WeakHashMap<>();
    private static boolean spreading;

    private FireEvents() {}

    public static void accept(IsoFire fire) {
        if (!spreading && !fire.smoke && fire.getSquare().getRoom() != null) origins.put(fire, Boolean.TRUE);
    }

    public static void spread(IsoCell cell, IsoGridSquare square, boolean igniteOnAny, int energy) {
        boolean previous = spreading;
        spreading = true;
        try {
            IsoFireManager.StartFire(cell, square, igniteOnAny, energy);
        } finally {
            spreading = previous;
        }
    }

    public static void burn(IsoGridSquare square, IsoFire fire) {
        square.Burn();
        Scorch.damage(square, origins.remove(fire) != null, fire.getEnergy());
    }

    static boolean patchManager(ClassNode type) {
        for (var method : type.methods) {
            if (!method.name.equals("StartFire") || !method.desc.equals("(Lzombie/iso/IsoCell;" + SQUARE + "ZII)V")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(MANAGER)
                    || !call.name.equals("Add") || !call.desc.equals("(" + FIRE_ARG + ")V")) continue;
                method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
                method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "accept", call.desc, false));
                return true;
            }
        }
        return false;
    }

    static boolean patchFire(ClassNode type) {
        boolean spread = false, burn = false;
        for (var method : type.methods) {
            if (!method.name.equals("Spread") && !method.name.equals("updateFromTimer")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (method.name.equals("Spread") && call.owner.equals(MANAGER) && call.name.equals("StartFire") && call.desc.equals(START)) {
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "spread", START, false));
                    spread = true;
                }
                if (method.name.equals("updateFromTimer") && call.owner.equals("zombie/iso/IsoGridSquare")
                    && call.name.equals("Burn") && call.desc.equals("()V")) {
                    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "burn",
                        "(" + SQUARE + FIRE_ARG + ")V", false));
                    burn = true;
                }
            }
        }
        return spread && burn;
    }

    static boolean patchCleaning(ClassNode type) {
        boolean patched = false;
        for (var method : type.methods) {
            boolean stains = method.name.equals("haveStains") && method.desc.equals("()Z");
            boolean clean = method.name.equals("removeGrime") && method.desc.equals("()V");
            if (!stains && !clean) continue;
            for (var instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() != (stains ? Opcodes.IRETURN : Opcodes.RETURN)) continue;
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SCORCH, stains ? "hasSoot" : "clean",
                    stains ? "(Z" + SQUARE + ")Z" : "(" + SQUARE + ")V", false));
                method.instructions.insertBefore(instruction, hook);
                patched = true;
            }
        }
        return patched;
    }
}
