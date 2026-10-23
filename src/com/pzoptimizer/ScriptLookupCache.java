package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.TypeInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Memo for ScriptManager.FindItem(name, moduleDefaultsToBase). Loot rolling resolves every distribution entry by name
 * on every roll: a full-type check, then for a short or unknown name the module chain (trim, alias map, module
 * cache, module map) and the module's item map. That was over half of the loot time when a new chunk is handed to
 * the game thread. The answer for a name only changes when scripts are (re)loaded, so found items are remembered
 * and the memo is emptied at the start and the end of every ScriptManager method that loads or reloads scripts.
 * A name that resolves to nothing is not remembered and always takes the stock path.
 */
public final class ScriptLookupCache {

    public static volatile boolean active = true;
    public static final LongAdder hits = new LongAdder();
    public static final LongAdder fills = new LongAdder();

    // Items are also created off the game thread (chunk loading on the streamer), so the maps are concurrent
    private static final ConcurrentHashMap<String, Object> withBase = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> withoutBase = new ConcurrentHashMap<>();

    private ScriptLookupCache() {}

    public static Object get(String name, boolean defaultsToBase) {
        if (!active || name == null) return null;
        Object item = (defaultsToBase ? withBase : withoutBase).get(name);
        if (item != null) hits.increment();
        return item;
    }

    public static void put(Object item, String name, boolean defaultsToBase) {
        if (!active || item == null || name == null) return;
        (defaultsToBase ? withBase : withoutBase).put(name, item);
        fills.increment();
    }

    public static void invalidate() {
        withBase.clear();
        withoutBase.clear();
    }

    private static final String[] MUTATORS = {
        "Reset", "Load", "LoadFile", "ParseScript", "ReloadScripts", "LoadedAfterLua", "PostTileDefinitions",
        "PostWorldDictionaryInit",
    };

    static boolean patchScriptManager(ClassNode node) {
        String itemDesc = "Lzombie/scripting/objects/Item;";
        MethodNode find = AsmSupport.findMethod(node, "FindItem", "(Ljava/lang/String;Z)" + itemDesc);
        if (find == null || (find.access & Opcodes.ACC_STATIC) != 0) return false;
        String self = "com/pzoptimizer/ScriptLookupCache";

        // Store every found item on the way out
        for (AbstractInsnNode insn = find.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.ARETURN) continue;
            InsnList store = new InsnList();
            store.add(new InsnNode(Opcodes.DUP));
            store.add(new VarInsnNode(Opcodes.ALOAD, 1));
            store.add(new VarInsnNode(Opcodes.ILOAD, 2));
            store.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "put", "(Ljava/lang/Object;Ljava/lang/String;Z)V", false));
            find.instructions.insertBefore(insn, store);
        }
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "get", "(Ljava/lang/String;Z)Ljava/lang/Object;", false));
        pre.add(new InsnNode(Opcodes.DUP));
        pre.add(new JumpInsnNode(Opcodes.IFNULL, stock));
        pre.add(new TypeInsnNode(Opcodes.CHECKCAST, "zombie/scripting/objects/Item"));
        pre.add(new InsnNode(Opcodes.ARETURN));
        pre.add(stock);
        pre.add(new InsnNode(Opcodes.POP));
        find.instructions.insert(pre);

        int mutators = 0;
        for (MethodNode m : node.methods) {
            if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) continue;
            boolean mutator = false;
            for (String name : MUTATORS) mutator |= name.equals(m.name);
            if (!mutator) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                int op = insn.getOpcode();
                if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                    m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, self, "invalidate", "()V", false));
                }
            }
            m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "invalidate", "()V", false));
            mutators++;
        }
        return mutators >= 4;
    }
}
