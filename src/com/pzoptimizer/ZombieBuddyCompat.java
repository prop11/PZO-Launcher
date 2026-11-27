package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.AnnotationVisitor;
import com.pzoptimizer.shaded.asm.ClassReader;
import com.pzoptimizer.shaded.asm.ClassVisitor;
import com.pzoptimizer.shaded.asm.Opcodes;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the installed ZombieBuddy mods patch (read from their jars before any game class loads), which of them really
 * load this session, and what PZO leaves alone because of it.
 *
 * ZombieBuddy applies each mod's @Patch(className, methodName) classes with one ByteBuddy retransformation per mod,
 * every already-loaded target class in a single retransformClasses call. If PZO changed the members of one of those
 * classes, the JVM refuses ZombieBuddy's version and that mod's whole batch is lost.
 *
 * Only mods that load count. Before 2.2 every mod named in any save's mods.txt counted, so one old save with a 3D camera mod
 * switched PZO optimisations off in every session. Now a mod counts once it's on the main menu list (ZombieBuddy loads
 * those at startup) or once ZombieBuddy loads its patch classes (a save or server that has it), which the transformer
 * sees. ZombieBuddy loads them right before it retransforms, and my transformer runs again in that retransform, so a
 * PZO patch on a method the mod then patches drops out there and then (method bodies can change mid-session, members
 * can't, so a patch that added members stays and says so).
 *
 * Advice patches (@Patch isAdvice, ZombieBuddy's default) wrap a method with enter/exit code and leave its body alone.
 * PZO patches in ADVICE_SAFE only time things or have their own runtime stand-down, so they stay next to advice. Every
 * other PZO patch stands down for advice too, as before.
 */
public final class ZombieBuddyCompat {

    private static final String PATCH_DESC = "Lme/zed_0xff/zombie_buddy/Patch;";
    private static final byte[] PATCH_MARK = "me/zed_0xff/zombie_buddy/Patch".getBytes(StandardCharsets.US_ASCII);
    private static final String OWN_MOD = "ZombieBuddy";

    // PZO patches that can stay next to a mod's advice on the same method. Lua Event.trigger keeps stock dispatch while
    // such a mod is loaded (eventTriggerShared), the rest only time the method
    private static final Set<String> ADVICE_SAFE = Set.of(
        "Lua Event.trigger",
        "IngameState profiler chunk timing",
        "IsoCell profiler chunk timing",
        "GameWindow profiler update timing",
        "GameWindow profiler render timing",
        "GameWindow profiler save marker");

    // internal class name -> method name -> mod -> true while all of that mod's patches on it are advice
    private static final Map<String, Map<String, Map<String, Boolean>>> targets = new HashMap<>();
    // patch class (internal name) -> mod, to see a mod load
    private static final Map<String, String> patchClassMod = new HashMap<>();
    private static final Map<String, Integer> patchesPerMod = new TreeMap<>();
    // On the main menu list: ZombieBuddy loads these at startup
    private static final Set<String> menuMods = new LinkedHashSet<>();
    private static final Set<String> loadedMods = ConcurrentHashMap.newKeySet();
    // Whether a class keeps its stock shape, decided at its first load and kept for its retransforms
    private static final Map<String, Boolean> shapeKept = new ConcurrentHashMap<>();
    private static final List<String> scanProblems = new ArrayList<>();
    private static final Set<String> standDowns = new LinkedHashSet<>();
    private static volatile boolean scanned;
    private static boolean zombieBuddy, unreadable;

    /** True while a loaded ZombieBuddy mod hooks Lua Event.trigger: event dispatch then stays stock. */
    public static volatile boolean eventTriggerShared;

    private ZombieBuddyCompat() {}

    private static synchronized void scan() {
        if (scanned) return;
        try {
            zombieBuddy = JavaModLoader.isZombieBuddyPresent();
            if (!zombieBuddy) return;
            Set<String> menu = JavaModLoader.getMenuModIds();
            for (File jar : JavaModLoader.deduplicateModJars(JavaModLoader.findJavaModJars())) {
                JavaModLoader.ModMetadata meta = JavaModLoader.findModMetadataForJar(jar);
                boolean own = false;
                for (String id : meta.modIds) own |= id.equalsIgnoreCase(OWN_MOD);
                boolean zbMod = own || meta.isZombieBuddyMod || meta.javaPkgName != null || meta.requiresZombieBuddy;
                if (!zbMod) continue;
                String mod = own ? OWN_MOD : meta.primaryId != null ? meta.primaryId : meta.modIds.isEmpty() ? jar.getName() : meta.modIds.iterator().next();
                boolean onMenu = own;
                for (String id : meta.modIds) onMenu |= menu.contains(id) || menu.contains(id.toLowerCase());
                if (onMenu) menuMods.add(mod);
                scanJar(mod, jar);
                SharedPatchTakeover.noteModJar(mod, meta.javaPkgName, jar);
            }
            for (String mod : menuMods) noteActive(mod);
        } catch (Throwable t) {
            unreadable = true;
            scanProblems.add("scan failed: " + t);
        } finally {
            scanned = true;
            logSummary();
            writeReport();
        }
    }

    private static void scanJar(String mod, File jar) {
        int found = 0;
        try (ZipFile zip = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (!contains(bytes, PATCH_MARK)) continue;
                found += readPatches(mod, bytes);
            }
        } catch (Throwable t) {
            unreadable = true;
            scanProblems.add(mod + ": could not read " + jar.getName() + ": " + t);
        }
        patchesPerMod.merge(mod, found, Integer::sum);
    }

    private static int readPatches(String mod, byte[] bytes) {
        int[] n = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            String self;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                self = name;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!PATCH_DESC.equals(desc)) return null;
                String[] cm = new String[2];
                boolean[] advice = {true}; // ZombieBuddy's default
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("className".equals(name)) cm[0] = String.valueOf(value);
                        else if ("methodName".equals(name)) cm[1] = String.valueOf(value);
                        else if ("isAdvice".equals(name) && value instanceof Boolean b) advice[0] = b;
                    }

                    @Override
                    public void visitEnd() {
                        if (cm[0] == null || cm[1] == null) return;
                        targets.computeIfAbsent(cm[0].replace('.', '/'), k -> new HashMap<>())
                            .computeIfAbsent(cm[1], k -> new HashMap<>())
                            .merge(mod, advice[0], (a, b) -> a && b);
                        if (self != null) patchClassMod.put(self, mod);
                        n[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return n[0];
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** Every class the transformer sees load outside the game: a mod's patch class means ZombieBuddy is loading it. */
    static void noteClassLoad(String internalName) {
        if (!scanned || !zombieBuddy) return;
        String mod = patchClassMod.get(internalName);
        if (mod != null && !loadedMods.contains(mod)) noteActive(mod);
    }

    private static void noteActive(String mod) {
        if (!loadedMods.add(mod)) return;
        Map<String, Map<String, Boolean>> event = targets.get("zombie/Lua/Event");
        Map<String, Boolean> trigger = event == null ? null : event.get("trigger");
        if (trigger != null && trigger.containsKey(mod) && !OWN_MOD.equals(mod)) eventTriggerShared = true;
        if (scanned) {
            PZOLogger.info("[ZombieBuddyCompat] " + mod + " is loading this session" + (eventTriggerShared ? "; Lua event dispatch stays stock" : ""));
            writeReport();
        }
    }

    private static boolean isActive(String mod) {
        return loadedMods.contains(mod);
    }

    /**
     * Whether a patch must leave this class's fields, methods and interfaces as they are: a mod that loads this
     * session (main menu list, or loaded already) patches it. Decided at the class's first load.
     */
    public static boolean keepClassShape(String internalName) {
        scan();
        if (!zombieBuddy) return false;
        if (unreadable) return true;
        return shapeKept.computeIfAbsent(internalName, c -> {
            Map<String, Map<String, Boolean>> byMethod = targets.get(c);
            if (byMethod == null) return false;
            for (Map<String, Boolean> mods : byMethod.values()) {
                for (String mod : mods.keySet()) {
                    if (isActive(mod)) return true;
                }
            }
            return false;
        });
    }

    /** Whether any installed ZombieBuddy mod patches this class (cheap; decides whether a patch gets fingerprinted). */
    static boolean hasTargets(String internalName) {
        scan();
        return zombieBuddy && targets.containsKey(internalName);
    }

    /**
     * The loaded mods (ZombieBuddy's own hooks aside) that patch one of the given methods of this class, or null.
     * Advice doesn't count against a patch in ADVICE_SAFE.
     */
    static String conflict(String internalName, Set<String> methodNames, String what) {
        scan();
        Map<String, Map<String, Boolean>> byMethod = targets.get(internalName);
        if (byMethod == null) return null;
        boolean adviceSafe = ADVICE_SAFE.contains(what);
        Set<String> mods = new LinkedHashSet<>();
        for (String m : methodNames) {
            Map<String, Boolean> s = byMethod.get(m);
            if (s == null) continue;
            for (Map.Entry<String, Boolean> e : s.entrySet()) {
                String mod = e.getKey();
                if (OWN_MOD.equals(mod) || !isActive(mod)) continue;
                if (adviceSafe && e.getValue()) continue;
                // PZO keeps its own version here, see SharedPatchTakeover
                if (SharedPatchTakeover.covers(mod, internalName, m)) continue;
                mods.add(mod + " (" + internalName.substring(internalName.lastIndexOf('/') + 1) + "." + m + ")");
            }
        }
        return mods.isEmpty() ? null : String.join(", ", mods);
    }

    /** Records why a PZO patch was left out, for the console and the report file. */
    static void recordStandDown(String what, String reason) {
        synchronized (ZombieBuddyCompat.class) {
            if (!standDowns.add(what + ": " + reason)) return;
        }
        PZOLogger.info("[ZombieBuddyCompat] " + what + " stands down: " + reason);
        writeReport();
    }

    /** Every stand-down so far, as "what: reason" lines, for the settings screen. */
    public static synchronized List<String> standDownLines() {
        return new ArrayList<>(standDowns);
    }

    private static void logSummary() {
        if (!zombieBuddy) return;
        int classes = targets.size();
        PZOLogger.info("[ZombieBuddyCompat] ZombieBuddy mods installed: " + patchesPerMod + " patches; " + classes
            + " game classes patched; on the main menu list: " + menuMods
            + (unreadable ? "; a jar could not be read, so every class keeps its stock shape" : ""));
    }

    private static synchronized void writeReport() {
        if (!zombieBuddy) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("PZO ").append(UpdateChecker.CURRENT_VERSION).append(" - ZombieBuddy mod compatibility\r\n\r\n");
            sb.append("Only mods that load this session count. Mods named only in other saves change nothing.\r\n\r\n");
            sb.append("ZombieBuddy mods installed (patches):\r\n");
            for (Map.Entry<String, Integer> e : patchesPerMod.entrySet()) {
                String mod = e.getKey();
                String state = loadedMods.contains(mod) ? (menuMods.contains(mod) ? "loaded (main menu list)" : "loaded") : "not loaded";
                sb.append("  ").append(mod).append(": ").append(e.getValue()).append(", ").append(state).append("\r\n");
            }
            for (String p : scanProblems) sb.append("  problem: ").append(p).append("\r\n");
            sb.append("\r\nGame classes the loaded mods patch:\r\n");
            int listed = 0;
            for (Map.Entry<String, Map<String, Map<String, Boolean>>> cls : new TreeMap<>(targets).entrySet()) {
                Set<String> methods = new java.util.TreeSet<>();
                for (Map.Entry<String, Map<String, Boolean>> m : cls.getValue().entrySet()) {
                    for (String mod : m.getValue().keySet()) {
                        if (loadedMods.contains(mod)) methods.add(m.getKey());
                    }
                }
                if (methods.isEmpty()) continue;
                sb.append("  ").append(cls.getKey().replace('/', '.')).append(": ").append(methods).append("\r\n");
                listed++;
            }
            if (listed == 0) sb.append("  none\r\n");
            sb.append("\r\nPZO optimisations left out because of them:\r\n");
            List<String> lines;
            synchronized (ZombieBuddyCompat.class) {
                lines = new ArrayList<>(standDowns);
            }
            if (lines.isEmpty()) sb.append("  none so far this session\r\n");
            for (String s : lines) sb.append("  ").append(s).append("\r\n");
            File f = ZomboidDir.sub("PZO_mod_compat.txt");
            Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }
}
