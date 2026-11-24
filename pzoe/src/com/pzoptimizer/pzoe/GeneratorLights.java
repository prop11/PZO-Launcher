package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.WeakHashMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoGenerator;
import zombie.network.GameServer;

public final class GeneratorLights {
    static final String TARGET = "zombie/iso/objects/IsoGenerator";
    private static final String OWNER = "com/pzoptimizer/pzoe/GeneratorLights";
    private static final WeakHashMap<IsoGenerator, State> sources = new WeakHashMap<>();
    private static final long STARTUP_NS = 2_300_000_000L;
    static volatile boolean enabled = true;

    private static final class State {
        boolean active;
        long start;
    }

    private GeneratorLights() {}

    public static void observe(IsoGenerator generator) {
        if (GameServer.server || !enabled || !FlickerLights.wanted || generator.getSquare() == null) return;
        boolean active = generator.isActivated();
        var state = sources.get(generator);
        if (state == null) {
            if (sources.size() >= 128) return;
            state = new State();
            state.active = active;
            sources.put(generator, state);
        } else if (state.active != active) {
            state.active = active;
            state.start = active ? System.nanoTime() : 0;
        }
    }

    static void gather(float x, float y, zombie.iso.IsoCell cell) {
        if (!enabled || sources.isEmpty()) return;
        long now = System.nanoTime();
        sources.keySet().removeIf(generator -> generator.getSquare() == null || generator.getObjectIndex() < 0);
        boolean disturbed = false;
        for (var entry : sources.entrySet()) {
            if (disturbance(entry.getKey(), entry.getValue(), now) != 0f) {
                disturbed = true;
                break;
            }
        }
        if (!disturbed) return;
        for (var light : cell.getLamppostPositions()) {
            if (light == null || !light.isActive() || !light.isHydroPowered()
                || Math.abs(x - light.getX()) > 30f || Math.abs(y - light.getY()) > 30f) continue;
            var square = cell.getGridSquare(light.getX(), light.getY(), light.getZ());
            float effect = powerAt(square, now);
            boolean fluorescent = light.getB() >= light.getR() * 0.94f;
            FlickerLights.offerGenerator(x, y, light.getX(), light.getY(), light.getZ(), Math.max(2, light.getRadius()),
                fluorescent ? 2f : 3f, effect);
        }
        for (var light : cell.roomLights) {
            if (light == null || !light.active || !light.hydroPowered) continue;
            int lx = light.x + light.width / 2, ly = light.y + light.height / 2;
            if (Math.abs(x - lx) > 30f || Math.abs(y - ly) > 30f) continue;
            float effect = powerAt(cell.getGridSquare(lx, ly, light.z), now);
            String room = light.room == null ? "" : light.room.getName();
            boolean fluorescent = room != null && (room.contains("garage") || room.contains("storage") || room.contains("warehouse")
                || room.contains("office") || room.contains("bath"));
            FlickerLights.offerGenerator(x, y, lx, ly, light.z, Math.max(2, Math.min(10, Math.max(light.width, light.height))),
                fluorescent ? 2f : 3f, effect);
        }
    }

    private static float powerAt(IsoGridSquare square, long now) {
        if (square == null || !square.haveElectricity() || square.hasGridPower()) return 0f;
        float effect = 0f;
        for (var entry : sources.entrySet()) {
            var generator = entry.getKey();
            var source = generator.getSquare();
            if (!generator.isActivated() || source == null
                || !IsoGenerator.isPoweringSquare(source.x, source.y, source.z, square.x, square.y, square.z)) continue;
            float next = disturbance(generator, entry.getValue(), now);
            // A stable generator can keep a shared circuit lit.
            if (next == 0f) return 0f;
            if (effect == 0f || Math.abs(next) < Math.abs(effect)) effect = next;
        }
        return effect;
    }

    private static float disturbance(IsoGenerator generator, State state, long now) {
        if (!state.active || !generator.isActivated()) return 0f;
        if (state.start > 0 && now - state.start < STARTUP_NS) return 1f - (now - state.start) / (float) STARTUP_NS;
        float fuel = generator.getFuelPercentage();
        return fuel < 9f ? -Math.max(0.02f, (9f - fuel) / 9f) : 0f;
    }

    static boolean patch(ClassNode node) {
        boolean patched = false;
        for (String name : new String[] {"update", "setActivated"}) {
            var method = AsmSupport.findMethod(node, name, name.equals("update") ? "()V" : "(Z)V");
            if (method == null) continue;
            for (var instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.RETURN) continue;
                var hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "observe", "(L" + TARGET + ";)V", false));
                method.instructions.insertBefore(instruction, hook);
                patched = true;
            }
        }
        return patched;
    }
}
