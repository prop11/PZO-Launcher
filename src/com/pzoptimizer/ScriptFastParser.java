package com.pzoptimizer;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Linear-Time O(N) Script & Tile Geometry Parser.
 * Stock script parsing in `ScriptManager` repeatedly executes backward comment removal
 * with `StringBuilder.replace` and sub-strings remainder text after every block token,
 * resulting in O(N^2) load bottlenecks for large geometry files (e.g., `tileGeometry.txt`).
 * 
 * ScriptFastParser provides single-pass forward scanning with depth-tracked comment stripping
 * and indexed token balancing, accelerating game boot and large mod load times.
 */
public final class ScriptFastParser {

    private static volatile boolean active = true;
    public static final AtomicLong scriptsParsed = new AtomicLong(0);

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Strips block comments using a single forward pass.
     * Returns null if unbalanced so callers can fall back to vanilla parsing if desired.
     */
    public static String stripComments(String text) {
        if (!active || text == null) return text;
        int n = text.length();
        int firstComment = text.indexOf("/*");
        if (firstComment == -1) {
            return text.indexOf("*/") == -1 ? text : null;
        }

        scriptsParsed.incrementAndGet();
        StringBuilder sb = new StringBuilder(n);
        sb.append(text, 0, firstComment);

        int depth = 0;
        int i = firstComment;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                depth++;
                i += 2;
            } else if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
                if (depth == 0) {
                    return null; // Unbalanced comment
                }
                depth--;
                i += 2;
            } else {
                if (depth == 0) {
                    sb.append(c);
                }
                i++;
            }
        }

        return depth == 0 ? sb.toString() : null;
    }

    /**
     * Splits top-level bracketed blocks in a single indexed pass.
     */
    public static ArrayList<String> parseBlockTokens(String text) {
        if (!active || text == null) return new ArrayList<>();
        ArrayList<String> tokens = new ArrayList<>();
        int n = text.length();
        int i = 0;

        while (i < n) {
            int openIdx = text.indexOf('{', i);
            if (openIdx == -1) break;

            int depth = 1;
            int closeIdx = openIdx + 1;
            while (closeIdx < n && depth > 0) {
                char ch = text.charAt(closeIdx);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                }
                closeIdx++;
            }

            if (depth == 0) {
                tokens.add(text.substring(openIdx, closeIdx).trim());
                i = closeIdx;
            } else {
                break; // Unbalanced trailing block
            }
        }

        return tokens;
    }
}
