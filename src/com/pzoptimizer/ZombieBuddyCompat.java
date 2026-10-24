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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the enabled ZombieBuddy mods patch, read from their jars before any game class loads, and what PZO leaves alone
 * because of it.
 *
 * ZombieBuddy applies each mod's @Patch(className, methodName) classes with one ByteBuddy retransformation per mod,
 * every already-loaded target class in a single retransformClasses call. If PZO changed the members of one of those
 * classes, the JVM refuses ZombieBuddy's version and the whole mod's batch is lost. So:
 *  - a PZO patch may add or rename members only in a class no enabled ZombieBuddy mod patches;
 *  - a PZO patch that rewrites a method a mod also patches stands down, since the mod's advice or replacement was
 *    written against the stock body (ZombieBuddy's own hooks are exempt: they ran alongside PZO since 1.0).
 * Jars are read as bytes, never loaded. A jar that cannot be read makes every class keep its stock shape.
 * Everything found and every stand-down is written to Zomboid/PZO_mod_compat.txt.
 */
public final class ZombieBuddyCompat {

    private static final String PATCH_DESC = "Lme/zed_0xff/zombie_buddy/Patch;";
    private static final byte[] PATCH_MARK = "me/zed_0xff/zombie_buddy/Patch".getBytes(StandardCharsets.US_ASCII);
    private static final String OWN_MOD = "ZombieBuddy";

    // internal class name -> method name -> mods patching it
    private static final Map<String, Map<String, Set<String>>> targets = new HashMap<>();
    private static final Map<String, Integer> patchesPerMod = new TreeMap<>();
    private static final List<String> scanProblems = new ArrayList<>();
    private static final Set<String> standDowns = new LinkedHashSet<>();
    private static volatile boolean scanned;
    private static boolean zombieBuddy, unreadable;

    private ZombieBuddyCompat() {}

    private static synchronized void scan() {
        if (scanned) return;
        try {
            zombieBuddy = JavaModLoader.isZombieBuddyPresent();
            if (!zombieBuddy) return;
            Set<String> enabled = JavaModLoader.getEnabledModIds();
            for (File jar : JavaModLoader.deduplicateModJars(JavaModLoader.findJavaModJars())) {
                JavaModLoader.ModMetadata meta = JavaModLoader.findModMetadataForJar(jar);
                boolean own = false;
                for (String id : meta.modIds) own |= id.equalsIgnoreCase(OWN_MOD);
                boolean zbMod = own || meta.isZombieBuddyMod || meta.javaPkgName != null || meta.requiresZombieBuddy;
                if (!zbMod) continue;
                boolean on = own || enabled.isEmpty();
                for (String id : meta.modIds) on |= enabled.contains(id) || enabled.contains(id.toLowerCase());
                if (!on) continue;
                String mod = own ? OWN_MOD : meta.primaryId != null ? meta.primaryId : meta.modIds.isEmpty() ? jar.getName() : meta.modIds.iterator().next();
                scanJar(mod, jar);
            }
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
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!PATCH_DESC.equals(desc)) return null;
                String[] cm = new String[2];
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("className".equals(name)) cm[0] = String.valueOf(value);
                        else if ("methodName".equals(name)) cm[1] = String.valueOf(value);
                    }

                    @Override
                    public void visitEnd() {
                        if (cm[0] == null || cm[1] == null) return;
                        targets.computeIfAbsent(cm[0].replace('.', '/'), k -> new HashMap<>())
                            .computeIfAbsent(cm[1], k -> new LinkedHashSet<>()).add(mod);
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

    /** Whether a patch must leave this class's fields, methods and interfaces as they are. */
    public static boolean keepClassShape(String internalName) {
        scan();
        if (!zombieBuddy) return false;
        return unreadable || targets.containsKey(internalName);
    }

    /** Whether any enabled ZombieBuddy mod patches this class (cheap; decides whether a patch is fingerprinted). */
    static boolean hasTargets(String internalName) {
        scan();
        return zombieBuddy && targets.containsKey(internalName);
    }

    /** The mods (ZombieBuddy's own hooks aside) that patch one of the given methods of this class, or null. */
    static String conflict(String internalName, Set<String> methodNames) {
        scan();
        Map<String, Set<String>> byMethod = targets.get(internalName);
        if (byMethod == null) return null;
        Set<String> mods = new LinkedHashSet<>();
        for (String m : methodNames) {
            Set<String> s = byMethod.get(m);
            if (s == null) continue;
            for (String mod : s) {
                if (!OWN_MOD.equals(mod)) mods.add(mod + " (" + internalName.substring(internalName.lastIndexOf('/') + 1) + "." + m + ")");
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

    private static void logSummary() {
        if (!zombieBuddy) return;
        int classes = targets.size();
        PZOLogger.info("[ZombieBuddyCompat] ZombieBuddy mods scanned: " + patchesPerMod + " patches; " + classes
            + " game classes patched" + (unreadable ? "; a jar could not be read, so every class keeps its stock shape" : ""));
    }

    private static synchronized void writeReport() {
        if (!zombieBuddy) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("PZO ").append(UpdateChecker.CURRENT_VERSION).append(" - ZombieBuddy mod compatibility\r\n\r\n");
            sb.append("ZombieBuddy mods found (patches):\r\n");
            for (Map.Entry<String, Integer> e : patchesPerMod.entrySet()) sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            for (String p : scanProblems) sb.append("  problem: ").append(p).append("\r\n");
            sb.append("\r\nGame classes they patch (PZO keeps these classes' stock shape):\r\n");
            for (String cls : new TreeMap<>(targets).keySet()) {
                sb.append("  ").append(cls.replace('/', '.')).append(": ").append(new TreeMap<>(targets.get(cls)).keySet()).append("\r\n");
            }
            sb.append("\r\nPZO optimisations left out because of them:\r\n");
            List<String> lines;
            synchronized (ZombieBuddyCompat.class) {
                lines = new ArrayList<>(standDowns);
            }
            if (lines.isEmpty()) sb.append("  none so far this session\r\n");
            for (String s : lines) sb.append("  ").append(s).append("\r\n");
            File f = new File(System.getProperty("user.home"), "Zomboid" + File.separator + "PZO_mod_compat.txt");
            Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }
}
