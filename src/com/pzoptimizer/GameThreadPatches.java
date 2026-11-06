package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.FieldNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.TypeInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;

/**
 * Small main-thread fixes done with ASM, each with its own toggle.
 *
 * LuaTableFastGet (KahluaTableImpl.rawget): stock does containsKey and then get, two hash lookups per Lua table read.
 * I call get first and only containsKey on a miss when there's a metatable, which returns exactly what stock does,
 * including a null stored directly in the map.
 *
 * WorldSoundChunkClamp (WorldSoundManager.addSound): a sound gets attached to every chunk in its hearing range by
 * calling IsoCell.getChunk for each chunk coordinate, tens of thousands for an alarm or a helicopter, though only
 * chunks in a player's loaded grid can answer. The loop is clamped to the loaded grids, so the same chunks get it.
 *
 * LightSwitchPowerCache (IsoLightSwitch.hasElectricityAround): the lighting pass asks every light switch for power
 * every frame (grid power plus a 3x3x2 scan). The answer is kept for a few frames per switch, so a power change reaches
 * a lamp that many frames late.
 *
 * ChunkHandoffPacer (IsoChunkMap.updateInternal): newly loaded chunks are handed to the main thread (loot rolls,
 * recalc, lighting, 1-5 ms each) several at a time, which shows up as long frames when moving fast. Hand-offs stop once
 * the frame has used its budget (at least one per frame), and a backlog of a grid row or more lets stock's count
 * through so loading never falls behind. While driving the budget is 12 ms and half a row of backlog is enough, since a
 * car crosses a chunk border every second or so and the 4 ms budget let it reach unloaded (black) ground. Same chunks, same order, they just land in different frames.
 *
 * EcsClassCache (ECSComponent.getECSClass(Class)): every lookup by type walks the superclass chain to find the class
 * right below ECSComponent. That never changes for a class, so it goes in a ClassValue after the first walk.
 *
 * ProfilerThreadCheck (GameProfiler.isValidThread): called on every profiler probe even with the profiler off, and it
 * reads the thread name and scans a list. Threads that said yes are remembered by identity; anything else still gets
 * the stock scan.
 *
 * StateMachineComponentCache (IsoGameCharacter.getStateMachineComponent): getStateMachine and getActionContext look the
 * component up in the character's HashMap on every call, many times per zombie per frame. Each character keeps the last
 * one it found and reuses it while that component still names the character as its owner. ECSEntity's set and remove
 * keep ownership and the map in step, so a replaced or removed component fails the check and stock runs. When the class
 * has to keep its stock shape (loaded before I attached, or a ZombieBuddy Java mod is active), I read the component
 * straight from the map instead, skipping the argument checks, the ECS class walk and the cast, and fall back to stock.
 */
public final class GameThreadPatches {

    public static volatile boolean luaFastGet = true;
    public static volatile boolean soundClamp = true;
    public static volatile boolean lightSwitchCache = true;
    public static volatile boolean handoffPacer = true;
    public static volatile boolean ecsClassCache = true;
    public static volatile boolean profilerThreadCheck = true;
    public static volatile boolean stateMachineCache = true;

    public static final AtomicLong lightSwitchHits = new AtomicLong();
    public static final AtomicLong handoffsDeferred = new AtomicLong();

    private static final int LIGHT_SWITCH_FRAMES = 10;
    private static final long HANDOFF_BUDGET_NS = 4_000_000L;
    private static final long HANDOFF_DRIVING_BUDGET_NS = 12_000_000L;

    private GameThreadPatches() {}

    // KahluaTableImpl.rawget

    public static Object rawgetTail(Map<Object, Object> delegate, KahluaTable metatable, Object key) {
        if (!luaFastGet) {
            return !delegate.containsKey(key) && metatable != null ? metatable.rawget(key) : delegate.get(key);
        }
        Object v = delegate.get(key);
        if (v != null || metatable == null) {
            return v;
        }
        return delegate.containsKey(key) ? null : metatable.rawget(key);
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode n) {
        do {
            n = n.getPrevious();
        } while (n != null && n.getOpcode() < 0);
        return n;
    }

    static boolean patchKahluaTable(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "rawget", "(Ljava/lang/Object;)Ljava/lang/Object;");
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEINTERFACE && "java/util/Map".equals(mi.owner)
                    && "containsKey".equals(mi.name)) {
                // aload_0, getfield delegate, aload_1 come before the call; the new tail starts there and the old one
                // becomes unreachable
                AbstractInsnNode start = prevReal(prevReal(prevReal(mi)));
                if (start == null || start.getOpcode() != Opcodes.ALOAD) return false;
                InsnList tail = new InsnList();
                tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
                tail.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "delegate", "Ljava/util/Map;"));
                tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
                tail.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "metatable", "Lse/krka/kahlua/vm/KahluaTable;"));
                tail.add(new VarInsnNode(Opcodes.ALOAD, 1));
                tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "rawgetTail",
                    "(Ljava/util/Map;Lse/krka/kahlua/vm/KahluaTable;Ljava/lang/Object;)Ljava/lang/Object;", false));
                tail.add(new InsnNode(Opcodes.ARETURN));
                m.instructions.insertBefore(start, tail);
                return true;
            }
        }
        return false;
    }

    // WorldSoundManager.addSound

    // Bounds of all loaded chunk grids together, in world chunk coords: {minX, minY, maxX, maxY}, max exclusive
    private static int[] loadedBounds() {
        IsoWorld w = IsoWorld.instance;
        IsoCell cell = w == null ? null : w.currentCell;
        if (cell == null) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int width = IsoChunkMap.chunkGridWidth;
        for (int n = 0; n < IsoPlayer.numPlayers; n++) {
            IsoChunkMap cm = cell.chunkMap[n];
            if (cm == null || cm.ignore) continue;
            minX = Math.min(minX, cm.getWorldXMin());
            minY = Math.min(minY, cm.getWorldYMin());
            maxX = Math.max(maxX, cm.getWorldXMin() + width);
            maxY = Math.max(maxY, cm.getWorldYMin() + width);
        }
        return minX == Integer.MAX_VALUE ? new int[] {0, 0, 0, 0} : new int[] {minX, minY, maxX, maxY};
    }

    private static int[] soundBounds;

    public static int clampSoundMinX(int v) {
        soundBounds = soundClamp ? loadedBounds() : null;
        return soundBounds == null ? v : Math.max(v, soundBounds[0]);
    }

    public static int clampSoundMinY(int v) {
        return soundBounds == null ? v : Math.max(v, soundBounds[1]);
    }

    public static int clampSoundMaxX(int v) {
        return soundBounds == null ? v : Math.min(v, soundBounds[2]);
    }

    public static int clampSoundMaxY(int v) {
        return soundBounds == null ? v : Math.min(v, soundBounds[3]);
    }

    static boolean patchWorldSound(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (!"addSound".equals(m.name) || !m.desc.endsWith("ZZZZS)Lzombie/WorldSoundManager$WorldSound;")) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode mi) || !"zombie/core/math/PZMath".equals(mi.owner) || !"ceil".equals(mi.name)) {
                    continue;
                }
                // radiusMax = (int) PZMath.ceil(...); then chunkMinX, chunkMinY, chunkMaxX, chunkMaxY are stored in
                // order
                int[] locals = new int[4];
                int found = -1;
                VarInsnNode last = null;
                for (AbstractInsnNode p = mi.getNext(); p != null && found < 4; p = p.getNext()) {
                    if (p.getOpcode() == Opcodes.ISTORE) {
                        if (found >= 0) {
                            locals[found] = ((VarInsnNode) p).var;
                            last = (VarInsnNode) p;
                        }
                        found++;
                    }
                }
                if (found < 4 || last == null) return false;
                String[] fns = {"clampSoundMinX", "clampSoundMinY", "clampSoundMaxX", "clampSoundMaxY"};
                InsnList clamp = new InsnList();
                for (int i = 0; i < 4; i++) {
                    clamp.add(new VarInsnNode(Opcodes.ILOAD, locals[i]));
                    clamp.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", fns[i], "(I)I", false));
                    clamp.add(new VarInsnNode(Opcodes.ISTORE, locals[i]));
                }
                m.instructions.insert(last, clamp);
                return true;
            }
        }
        return false;
    }

    // IsoLightSwitch.hasElectricityAround

    // Switch -> (frame << 1 | answer). Kept here rather than in fields so IsoLightSwitch keeps its stock shape (see
    // ZombieBuddyCompat). IsoObject uses identity equality, and the map gets emptied every few hundred frames.
    private static final java.util.concurrent.ConcurrentHashMap<Object, Long> lightSwitchPower = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int lightSwitchPurgeFrame;
    private static final int LIGHT_SWITCH_PURGE_FRAMES = 600;

    /** Prefix of hasElectricityAround: the cached answer (0 / 1) while it's fresh, otherwise -1 to run stock. */
    public static int lightSwitchCached(Object lightSwitch) {
        if (!lightSwitchCache) return -1;
        int now = IsoWorld.instance.getFrameNo();
        if (now - lightSwitchPurgeFrame > LIGHT_SWITCH_PURGE_FRAMES || now < lightSwitchPurgeFrame) {
            lightSwitchPurgeFrame = now;
            lightSwitchPower.clear();
            return -1;
        }
        Long v = lightSwitchPower.get(lightSwitch);
        if (v == null) return -1;
        int d = now - (int) (v >> 1);
        if (d >= 0 && d < LIGHT_SWITCH_FRAMES) {
            lightSwitchHits.incrementAndGet();
            return (int) (v & 1);
        }
        return -1;
    }

    /** Before each return in the stock body: remember what it worked out. */
    public static int lightSwitchStore(int answer, Object lightSwitch) {
        if (lightSwitchCache) lightSwitchPower.put(lightSwitch, ((long) IsoWorld.instance.getFrameNo() << 1) | (answer & 1));
        return answer;
    }

    static boolean patchLightSwitch(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "hasElectricityAround", "()Z");
        if (m == null) return false;
        java.util.List<AbstractInsnNode> returns = new java.util.ArrayList<>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            // Already has this patch (another agent handed the bytes back)
            if (insn instanceof MethodInsnNode mi && "lightSwitchCached".equals(mi.name) && "com/pzoptimizer/GameThreadPatches".equals(mi.owner)) {
                return false;
            }
            if (insn.getOpcode() == Opcodes.IRETURN) returns.add(insn);
        }
        if (returns.isEmpty()) return false;
        for (AbstractInsnNode ret : returns) {
            InsnList store = new InsnList();
            store.add(new VarInsnNode(Opcodes.ALOAD, 0));
            store.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "lightSwitchStore", "(ILjava/lang/Object;)I", false));
            m.instructions.insertBefore(ret, store);
        }
        InsnList head = new InsnList();
        LabelNode miss = new LabelNode();
        head.add(new VarInsnNode(Opcodes.ALOAD, 0));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "lightSwitchCached", "(Ljava/lang/Object;)I", false));
        head.add(new InsnNode(Opcodes.DUP));
        head.add(new JumpInsnNode(Opcodes.IFLT, miss));
        head.add(new InsnNode(Opcodes.IRETURN));
        head.add(miss);
        head.add(new InsnNode(Opcodes.POP));
        m.instructions.insert(head);
        return true;
    }

    // ECSComponent.getECSClass

    // Only the walk itself touches ECSComponent, and only the first time a class is asked about
    private static final ClassValue<Class<?>> ECS_CLASS = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> type) {
            Class<?> stop = zombie.characters.ecs.ECSComponent.class;
            Class<?> found = null;
            for (Class<?> c = type; c != null && c != stop; c = c.getSuperclass()) {
                found = c;
            }
            return found;
        }
    };

    public static boolean ecsClassCached() {
        return ecsClassCache;
    }

    public static Class<?> ecsClass(Class<?> type) {
        return ECS_CLASS.get(type);
    }

    /**
     * Prefixes getECSClass(Class) with: if the cache is on and clazz != null, return GameThreadPatches.ecsClass(clazz).
     */
    static boolean patchEcsComponent(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "getECSClass", "(Ljava/lang/Class;)Ljava/lang/Class;");
        if (m == null || (m.access & Opcodes.ACC_STATIC) == 0) return false;
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "ecsClassCached", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new JumpInsnNode(Opcodes.IFNULL, stock));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "ecsClass",
            "(Ljava/lang/Class;)Ljava/lang/Class;", false));
        pre.add(new InsnNode(Opcodes.ARETURN));
        pre.add(stock);
        m.instructions.insert(pre);
        return true;
    }

    // GameProfiler.isValidThread

    private static volatile Thread profilerThreadA, profilerThreadB;

    public static boolean profilerThreadKnown() {
        if (!profilerThreadCheck) return false;
        Thread t = Thread.currentThread();
        return t == profilerThreadA || t == profilerThreadB;
    }

    // Stock's answer for a thread I didn't recognise; a yes gets remembered for next time
    public static boolean profilerThreadResult(boolean valid) {
        if (valid) {
            Thread t = Thread.currentThread();
            if (profilerThreadA == null) {
                profilerThreadA = t;
            } else if (profilerThreadA != t && profilerThreadB == null) {
                profilerThreadB = t;
            }
        }
        return valid;
    }

    /** Prefixes isValidThread with the known-thread check, passing stock's answer through profilerThreadResult. */
    static boolean patchGameProfiler(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "isValidThread", "()Z");
        if (m == null || (m.access & Opcodes.ACC_STATIC) == 0) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.IRETURN) {
                m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches",
                    "profilerThreadResult", "(Z)Z", false));
            }
        }
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "profilerThreadKnown", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(new InsnNode(Opcodes.ICONST_1));
        pre.add(new InsnNode(Opcodes.IRETURN));
        pre.add(stock);
        m.instructions.insert(pre);
        return true;
    }

    // IsoGameCharacter.getStateMachineComponent

    // Params are Object so the patched class's verifier never has to load the component types
    public static boolean stateMachineCached(Object component, Object character) {
        return stateMachineCache && ((zombie.characters.ecs.ECSComponent) component).getECSOwnerEntity() == character;
    }

    // In-place variant: the component under its ECS class (StateMachineComponent extends ECSComponent directly)
    public static Object stateMachineFromMap(java.util.HashMap<?, ?> map) {
        if (!stateMachineCache || map == null) return null;
        Object c = map.get(zombie.characters.component.StateMachineComponent.class);
        return c instanceof zombie.characters.component.StateMachineComponent ? c : null;
    }

    static volatile boolean stateMachineInPlace;

    static boolean patchStateMachineComponent(ClassNode node) {
        String desc = "()Lzombie/characters/component/StateMachineComponent;";
        MethodNode orig = AsmSupport.findMethod(node, "getStateMachineComponent", desc);
        if (orig == null || (orig.access & Opcodes.ACC_STATIC) != 0) return false;
        // The field version renames the stock method and adds a field, which a retransform of an already-loaded class
        // can't do, and it's skipped while a ZombieBuddy mod patches the class (see ZombieBuddyCompat)
        Class<?> live = AsmSupport.REDEFINING.get();
        boolean liveHasField = live != null && hasDeclaredField(live, "pzo$smc");
        if (!liveHasField && (live != null || ZombieBuddyCompat.keepClassShape(node.name))) {
            return patchStateMachineInPlace(node, orig);
        }
        stateMachineInPlace = false;
        String owner = node.name;
        String fieldDesc = "Lzombie/characters/component/StateMachineComponent;";
        int access = orig.access;
        orig.name = "pzo$getStateMachineComponentStock";
        orig.access = (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT, "pzo$smc", fieldDesc, null, null));

        MethodNode m = new MethodNode(access, "getStateMachineComponent", desc, null, null);
        InsnList c = m.instructions;
        LabelNode miss = new LabelNode();
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pzo$smc", fieldDesc));
        c.add(new VarInsnNode(Opcodes.ASTORE, 1));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "stateMachineCached",
            "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        c.add(new JumpInsnNode(Opcodes.IFEQ, miss));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new InsnNode(Opcodes.ARETURN));
        c.add(miss);
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "pzo$getStateMachineComponentStock", desc, false));
        c.add(new VarInsnNode(Opcodes.ASTORE, 1));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "pzo$smc", fieldDesc));
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new InsnNode(Opcodes.ARETURN));
        m.maxLocals = 2;
        m.maxStack = 2;
        node.methods.add(m);
        return true;
    }

    private static boolean hasDeclaredField(Class<?> c, String name) {
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (f.getName().equals(name)) return true;
        }
        return false;
    }

    // Body-only so a retransform accepts it: map lookup first, stock body if that finds nothing
    private static boolean patchStateMachineInPlace(ClassNode node, MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "com/pzoptimizer/GameThreadPatches".equals(mi.owner)) {
                stateMachineInPlace = true;
                return true;
            }
        }
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getECSComponentMap", "()Ljava/util/HashMap;", false));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "stateMachineFromMap",
            "(Ljava/util/HashMap;)Ljava/lang/Object;", false));
        pre.add(new InsnNode(Opcodes.DUP));
        pre.add(new JumpInsnNode(Opcodes.IFNULL, stock));
        pre.add(new TypeInsnNode(Opcodes.CHECKCAST, "zombie/characters/component/StateMachineComponent"));
        pre.add(new InsnNode(Opcodes.ARETURN));
        pre.add(stock);
        pre.add(new InsnNode(Opcodes.POP));
        m.instructions.insert(pre);
        stateMachineInPlace = true;
        return true;
    }

    // IsoChunkMap.updateInternal

    private static long handoffStart;
    private static int handoffsThisFrame;

    // Called once a frame with stock's hand-off count
    public static int handoffBegin(int stockCount) {
        handoffStart = System.nanoTime();
        handoffsThisFrame = 0;
        return stockCount;
    }

    // Loop condition: how many are left, or 0 to stop handing off this frame
    public static int handoffContinue(int count) {
        if (count <= 0) return count;
        if (handoffsThisFrame++ == 0 || !handoffPacer) return count;
        boolean driving = ChunkIngestionPacer.PacedConcurrentQueue.isPlayerDriving();
        int backlog = IsoChunk.loadGridSquare.size();
        if (backlog >= (driving ? Math.max(1, IsoChunkMap.chunkGridWidth / 2) : IsoChunkMap.chunkGridWidth)) return count;
        if (System.nanoTime() - handoffStart < (driving ? HANDOFF_DRIVING_BUDGET_NS : HANDOFF_BUDGET_NS)) return count;
        handoffsDeferred.addAndGet(count);
        return 0;
    }

    static boolean patchChunkMap(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "updateInternal", "()V");
        if (m == null) return false;
        // The count local is the first int stored from CappedConcurrentQueue.size(); its second store is stock's
        // hand-off count, and the first "iload count; ifle" after that is the loop condition
        int countVar = -1;
        VarInsnNode secondStore = null;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "size".equals(mi.name) && mi.owner.endsWith("CappedConcurrentQueue")
                    && insn.getNext() instanceof VarInsnNode v && v.getOpcode() == Opcodes.ISTORE) {
                countVar = v.var;
                insn = v;
                continue;
            }
            if (countVar >= 0 && insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ISTORE && v.var == countVar) {
                secondStore = v;
                break;
            }
        }
        if (secondStore == null) return false;
        JumpInsnNode loopTest = null;
        for (AbstractInsnNode insn = secondStore.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFLE && j.getPrevious() instanceof VarInsnNode v
                    && v.getOpcode() == Opcodes.ILOAD && v.var == countVar) {
                loopTest = j;
                break;
            }
        }
        if (loopTest == null) return false;
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ILOAD, countVar));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "handoffBegin", "(I)I", false));
        begin.add(new VarInsnNode(Opcodes.ISTORE, countVar));
        m.instructions.insert(secondStore, begin);
        m.instructions.insertBefore(loopTest,
            new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GameThreadPatches", "handoffContinue", "(I)I", false));
        return true;
    }
}
