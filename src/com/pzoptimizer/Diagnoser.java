package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out what most likely caused a crash from console.txt, pzo_engine.log and any JVM crash logs.
 *
 * Every rule lives in diagnose_rules.json (packed into the jar from the repo root), which the Discord bot reads too,
 * so the two can't disagree. The file's "_doc" entry describes how a rule is applied; this class is that description
 * in code and nothing more.
 */
final class Diagnoser {

    enum Verdict { PZO, PZO_POSSIBLE, MOD, INSTALL, USER, UNKNOWN }

    static final class Result {
        Verdict verdict = Verdict.UNKNOWN;
        String ruleId = "", title = "No known cause found in the logs", advice = "", mod = "", evidence = "";
        final List<String> alsoSeen = new ArrayList<>();

        /** One line for the clipboard and the report. */
        String headline() {
            String v = switch (verdict) {
                case PZO -> "PZO error";
                case PZO_POSSIBLE -> "possibly PZO";
                case MOD -> "mod conflict" + (mod.isEmpty() ? "" : " (" + mod + ")");
                case INSTALL -> "install error";
                case USER -> "setup problem";
                default -> "unknown";
            };
            return v + ": " + title;
        }
    }

    private static final int CONSOLE_TAIL_BYTES = 4 * 1024 * 1024;
    private static final int HS_ERR_MAX_LINES = 400;
    private static final Pattern FRAME = Pattern.compile("((?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*)\\.([\\w$<>]+)\\(");

    private Diagnoser() {}

    static Result diagnose(File console, File engineLog, List<File> hsErr) {
        try (InputStream in = Diagnoser.class.getResourceAsStream("diagnose_rules.json")) {
            if (in == null) throw new IllegalStateException("diagnose_rules.json is not in the jar");
            return diagnose(new String(in.readAllBytes(), StandardCharsets.UTF_8), console, engineLog, hsErr);
        } catch (Throwable t) {
            PZOLogger.warn("[Diagnoser] could not diagnose: " + t);
            return new Result();
        }
    }

    @SuppressWarnings("unchecked")
    static Result diagnose(String rulesJson, File console, File engineLog, List<File> hsErr) {
        Map<String, Object> root = (Map<String, Object>) MiniJson.parse(rulesJson);
        List<Object> rules = (List<Object>) root.get("rules");

        List<String> consoleLines = tail(console, CONSOLE_TAIL_BYTES);
        List<String> engineLines = tail(engineLog, CONSOLE_TAIL_BYTES);
        List<String> hsLines = new ArrayList<>();
        if (hsErr != null) {
            for (File f : hsErr) hsLines.addAll(crashThreadPart(f));
        }
        Set<String> rewritten = rewrittenMethods(engineLines);

        Result best = new Result();
        int bestPriority = Integer.MIN_VALUE;
        List<String[]> matched = new ArrayList<>();
        for (Object o : rules) {
            Map<String, Object> rule = (Map<String, Object>) o;
            String[] hit = apply(rule, consoleLines, engineLines, hsLines, rewritten);
            if (hit == null) continue;
            int priority = ((Number) rule.getOrDefault("priority", 0.0)).intValue();
            String title = String.valueOf(rule.getOrDefault("title", rule.get("id")));
            matched.add(new String[] {String.valueOf(rule.get("id")), title});
            if (priority <= bestPriority) continue;
            bestPriority = priority;
            best.ruleId = String.valueOf(rule.get("id"));
            best.title = title;
            best.advice = String.valueOf(rule.getOrDefault("advice", ""));
            best.verdict = verdict(String.valueOf(rule.get("verdict")));
            best.evidence = hit[0];
            best.mod = hit[1];
        }
        for (String[] m : matched) {
            if (!m[0].equals(best.ruleId)) best.alsoSeen.add(m[1]);
        }
        return best;
    }

    private static Verdict verdict(String s) {
        try {
            return Verdict.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Verdict.UNKNOWN;
        }
    }

    // {evidence line, mod name} of the last match, or null
    @SuppressWarnings("unchecked")
    private static String[] apply(Map<String, Object> rule, List<String> console, List<String> engine, List<String> hs, Set<String> rewritten) {
        boolean frames = "rewritten_frame".equals(rule.get("kind"));
        Pattern p = null;
        if (!frames) {
            Object pattern = rule.get("pattern");
            if (pattern == null) return null;
            p = Pattern.compile(String.valueOf(pattern), String.valueOf(rule.getOrDefault("flags", "")).contains("i") ? Pattern.CASE_INSENSITIVE : 0);
        } else if (rewritten.isEmpty()) {
            return null;
        }
        int tailLines = rule.get("tail_lines") == null ? Integer.MAX_VALUE : ((Number) rule.get("tail_lines")).intValue();
        int modGroup = rule.get("mod_group") == null ? 0 : ((Number) rule.get("mod_group")).intValue();

        String[] hit = null;
        for (Object file : (List<Object>) rule.getOrDefault("files", List.of())) {
            List<String> lines = "console".equals(file) ? console : "engine".equals(file) ? engine : "hs_err".equals(file) ? hs : List.of();
            int from = "hs_err".equals(file) ? 0 : Math.max(0, lines.size() - tailLines);
            for (int i = from; i < lines.size(); i++) {
                String line = lines.get(i);
                if (frames) {
                    Matcher m = FRAME.matcher(line);
                    while (m.find()) {
                        if (rewritten.contains(m.group(1) + "." + m.group(2))) hit = new String[] {line.trim(), ""};
                    }
                } else {
                    Matcher m = p.matcher(line);
                    if (m.find()) hit = new String[] {line.trim(), modGroup > 0 && m.groupCount() >= modGroup && m.group(modGroup) != null ? m.group(modGroup).trim() : ""};
                }
            }
        }
        return hit;
    }

    // "class.method" for every method named on a "[PZO Rewrites] class: a, b" line
    private static Set<String> rewrittenMethods(List<String> engineLines) {
        Set<String> out = new HashSet<>();
        for (String line : engineLines) {
            int at = line.indexOf(RewriteLog.PREFIX);
            if (at < 0) continue;
            String rest = line.substring(at + RewriteLog.PREFIX.length());
            int colon = rest.indexOf(": ");
            if (colon < 0) continue;
            String cls = rest.substring(0, colon).trim();
            for (String m : rest.substring(colon + 2).split(",")) {
                if (!m.trim().isEmpty()) out.add(cls + "." + m.trim());
            }
        }
        return out;
    }

    private static List<String> tail(File f, int maxBytes) {
        List<String> lines = new ArrayList<>();
        if (f == null || !f.isFile()) return lines;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            boolean cut = len > maxBytes;
            if (cut) raf.seek(len - maxBytes);
            BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(raf.getChannel()), StandardCharsets.UTF_8), 65536);
            String line;
            // the first line after a seek is usually half a line
            if (cut) in.readLine();
            while ((line = in.readLine()) != null) lines.add(line);
        } catch (Throwable ignored) {}
        return lines;
    }

    // The header and the crashing thread's stack; everything from the process section down is every other thread
    private static List<String> crashThreadPart(File f) {
        List<String> lines = new ArrayList<>();
        if (f == null || !f.isFile()) return lines;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null && lines.size() < HS_ERR_MAX_LINES) {
                if (line.startsWith("---------------  P R O C E S S")) break;
                lines.add(line);
            }
        } catch (Throwable ignored) {}
        return lines;
    }
}
