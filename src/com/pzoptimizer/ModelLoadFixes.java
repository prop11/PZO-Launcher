package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.nio.file.InvalidPathException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Settings screen "Quieter model loading" (JVM_ModelLoadFixes). Two exception storms on the game thread while new
 * models load during world streaming, each exception carrying a full stack trace:
 *
 * SkinningBoneHierarchy.buildBoneHierarchy resolves every bone name with StringUtils.tryParseEnum, i.e. Enum.valueOf
 * inside a try/catch; every bone that is not a SkeletonBone (props, vehicle parts, mod rigs) throws and is caught. The
 * same answer comes from a name table built once per enum type: exact name match, default when absent.
 *
 * A model whose texture name holds a character the file system rejects ("|" on Windows) throws InvalidPathException
 * from Bucket.HasTexture on every load and logs it with a stack walk; the game's own null-texture cache is never
 * reached. The first exception for a name is kept and thrown again (same exception, same null texture, no new stack
 * trace), and the "Failed to load shared texture" error is logged once per name.
 */
public final class ModelLoadFixes {

    public static volatile boolean active = true;
    public static final AtomicLong boneThrowsAvoided = new AtomicLong();
    public static final AtomicLong textureFailuresRepeated = new AtomicLong();

    private static final int MAX_REMEMBERED = 4096;

    private static final ClassValue<Map<String, Enum<?>>> BY_NAME = new ClassValue<>() {
        @Override
        protected Map<String, Enum<?>> computeValue(Class<?> type) {
            Map<String, Enum<?>> m = new HashMap<>();
            Object[] constants = type.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) m.put(((Enum<?>) c).name(), (Enum<?>) c);
            }
            return m;
        }
    };

    private static final ConcurrentHashMap<String, InvalidPathException> badPaths = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> loggedFailures = new ConcurrentHashMap<>();

    private ModelLoadFixes() {}

    /** Replaces StringUtils.tryParseEnum(Class, String, Enum) in SkinningBoneHierarchy.buildBoneHierarchy. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Enum parseEnum(Class enumClass, String name, Enum defaultVal) {
        if (!active || enumClass == null || !enumClass.isEnum()) {
            return zombie.util.StringUtils.tryParseEnum(enumClass, name, defaultVal);
        }
        Enum<?> v = name == null ? null : BY_NAME.get(enumClass).get(name);
        if (v == null) {
            boneThrowsAvoided.incrementAndGet();
            return defaultVal;
        }
        return v;
    }

    /** Replaces BucketManager.Shared().HasTexture(name) in Texture.getSharedTextureInternal. */
    public static boolean hasTexture(zombie.core.bucket.Bucket bucket, String name) {
        if (!active || name == null) return bucket.HasTexture(name);
        InvalidPathException known = badPaths.get(name);
        if (known != null) {
            textureFailuresRepeated.incrementAndGet();
            throw known;
        }
        try {
            return bucket.HasTexture(name);
        } catch (InvalidPathException e) {
            if (badPaths.size() < MAX_REMEMBERED) badPaths.put(name, e);
            throw e;
        }
    }

    /** Replaces DebugType.printException(ex, message, severity) in Texture.getSharedTexture(String, int). */
    public static void printException(zombie.debug.DebugType type, Throwable ex, String message, zombie.debug.LogSeverity severity) {
        if (active && message != null && ex instanceof InvalidPathException) {
            if (loggedFailures.size() >= MAX_REMEMBERED || loggedFailures.putIfAbsent(message, Boolean.TRUE) != null) return;
        }
        type.printException(ex, message, severity);
    }

    static boolean patchBoneHierarchy(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "buildBoneHierarchy", "(Lzombie/core/skinnedmodel/model/SkinningData;)V");
        if (m == null) return false;
        // A static call with the identical descriptor: only the owner and name change
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && mi.owner.equals("zombie/util/StringUtils")
                    && mi.name.equals("tryParseEnum") && mi.desc.equals("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Enum;)Ljava/lang/Enum;")) {
                mi.owner = "com/pzoptimizer/ModelLoadFixes";
                mi.name = "parseEnum";
                n++;
            }
        }
        return n > 0;
    }

    static boolean patchTexture(ClassNode node) {
        MethodNode internal = AsmSupport.findMethod(node, "getSharedTextureInternal", "(Ljava/lang/String;I)Lzombie/core/textures/Texture;");
        MethodNode outer = AsmSupport.findMethod(node, "getSharedTexture", "(Ljava/lang/String;I)Lzombie/core/textures/Texture;");
        if (internal == null || outer == null) return false;
        int a = AsmSupport.redirectCalls(internal, "zombie/core/bucket/Bucket", "HasTexture", "(Ljava/lang/String;)Z",
            "com/pzoptimizer/ModelLoadFixes", "hasTexture", "(Lzombie/core/bucket/Bucket;Ljava/lang/String;)Z");
        int b = AsmSupport.redirectCalls(outer, "zombie/debug/DebugType", "printException",
            "(Ljava/lang/Throwable;Ljava/lang/String;Lzombie/debug/LogSeverity;)V",
            "com/pzoptimizer/ModelLoadFixes", "printException",
            "(Lzombie/debug/DebugType;Ljava/lang/Throwable;Ljava/lang/String;Lzombie/debug/LogSeverity;)V");
        return a > 0 && b > 0;
    }
}
