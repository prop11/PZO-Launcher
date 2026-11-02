package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Debug-only breakdown of the main thread, built from the game's own GameProfiler areas. The agent sends GameProfiler's
 * calls into GameProfileRecording here, so while this is on the timings stay in memory instead of going to the vanilla
 * per-frame CSV.
 */
public final class PhaseProfiler {

    private static final int MAX_DEPTH = 5;

    private static volatile boolean active = false;
    // The user already had the vanilla profiler on, so leave its recording working
    private static boolean passThrough = false;
    private static Thread mainThread;

    private static Node frame = new Node("frame");
    private static Node lastFrame = new Node("frame");
    private static Node window = new Node("window");

    private static final class Node {
        final String key;
        long ns;
        HashMap<String, Node> kids;

        Node(String key) {
            this.key = key;
        }

        Node child(String k) {
            if (kids == null) kids = new HashMap<>();
            Node n = kids.get(k);
            if (n == null) {
                n = new Node(k);
                kids.put(k, n);
            }
            return n;
        }

        void mergeFrom(Node other) {
            ns += other.ns;
            if (other.kids == null) return;
            for (Node o : other.kids.values()) child(o.key).mergeFrom(o);
        }
    }

    public static void enable() {
        if (active) return;
        try {
            zombie.debug.BooleanDebugOption opt = zombie.debug.DebugOptions.instance.gameProfilerEnabled;
            passThrough = opt.getValue();
            if (!passThrough) opt.setValue(true);
            mainThread = Thread.currentThread();
            active = true;
            PZOLogger.info("[PhaseProfiler] Main thread phase timings active (debug launch)");
        } catch (Throwable t) {
            PZOLogger.warn("[PhaseProfiler] Could not enable the game profiler: " + t.getMessage());
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** Redirect target for GameProfiler.end -> GameProfileRecording.logTimeSpan. */
    public static void logTimeSpan(Object recorder, Object area) {
        if (!active || passThrough) {
            ((zombie.GameProfileRecording) recorder).logTimeSpan((zombie.GameProfiler.ProfileArea) area);
            return;
        }
        if (Thread.currentThread() != mainThread) return;
        try {
            record(frame, (zombie.GameProfiler.ProfileArea) area, 0);
        } catch (Throwable ignored) {}
    }

    /** Redirect target for GameProfiler.startFrame -> GameProfileRecording.writeLine. */
    public static void writeLine(Object recorder) {
        if (!active || passThrough) {
            ((zombie.GameProfileRecording) recorder).writeLine();
        }
    }

    private static void record(Node parent, zombie.GameProfiler.ProfileArea area, int depth) {
        if (area == null || area.key == null) return;
        Node n = parent.child(area.key);
        n.ns += area.total;
        if (parent == frame) frame.ns += area.total;
        if (depth + 1 >= MAX_DEPTH) return;
        List<zombie.GameProfiler.ProfileArea> kids = area.children;
        for (int i = 0, c = kids.size(); i < c; i++) {
            record(n, kids.get(i), depth + 1);
        }
    }

    /** Called once a frame on the main thread to close off the frame that just ended. */
    public static void onFrameBoundary() {
        if (!active) return;
        window.mergeFrom(frame);
        lastFrame = frame;
        frame = new Node("frame");
    }

    /** Main thread time covered by profiled areas in the frame that just finished, in ms. */
    public static double lastFrameAccountedMs() {
        return lastFrame.ns / 1_000_000.0;
    }

    public static String describeLastFrame() {
        return describe(lastFrame, 1);
    }

    /** Writes up the window as per-frame averages, then starts a new one. */
    public static String describeAndResetWindow(int frames) {
        String s = describe(window, Math.max(1, frames));
        window = new Node("window");
        return s;
    }

    public static void resetWindow() {
        window = new Node("window");
    }

    /** Top three root areas, each followed by its most expensive chain of children. */
    private static String describe(Node root, int divisor) {
        if (!active || root.kids == null || root.kids.isEmpty()) return "n/a";
        List<Node> roots = sorted(root);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, roots.size()); i++) {
            Node n = roots.get(i);
            if (sb.length() > 0) sb.append(" ; ");
            sb.append(n.key).append(' ').append(ms(n.ns, divisor));
            Node cur = n;
            StringBuilder chain = new StringBuilder();
            while (cur.kids != null && !cur.kids.isEmpty()) {
                List<Node> k = sorted(cur);
                Node top = k.get(0);
                // Stop once the chain splits into lots of small pieces; the parent is the useful answer then
                if (top.ns * 3 < cur.ns) break;
                if (chain.length() > 0) chain.append(" > ");
                chain.append(top.key).append(' ').append(ms(top.ns, divisor));
                if (k.size() > 1) {
                    Node second = k.get(1);
                    if (second.ns * 4 >= top.ns) {
                        chain.append(" (+").append(second.key).append(' ').append(ms(second.ns, divisor)).append(')');
                    }
                }
                cur = top;
            }
            if (chain.length() > 0) sb.append(" [").append(chain).append(']');
        }
        return sb.toString();
    }

    private static List<Node> sorted(Node parent) {
        List<Node> list = new ArrayList<>(parent.kids.values());
        list.sort((a, b) -> Long.compare(b.ns, a.ns));
        return list;
    }

    private static String ms(long ns, int divisor) {
        return String.format("%.1fms", ns / 1_000_000.0 / divisor);
    }

    /** GameProfiler: its recorder calls (end -> logTimeSpan, startFrame -> writeLine) come through here. */
    static boolean patch(ClassNode node) {
        int spans = 0, lines = 0;
        for (MethodNode m : node.methods) {
            spans += AsmSupport.redirectCalls(m, "zombie/GameProfileRecording", "logTimeSpan", "(Lzombie/GameProfiler$ProfileArea;)V",
                "com/pzoptimizer/PhaseProfiler", "logTimeSpan", "(Ljava/lang/Object;Ljava/lang/Object;)V");
            lines += AsmSupport.redirectCalls(m, "zombie/GameProfileRecording", "writeLine", "()V",
                "com/pzoptimizer/PhaseProfiler", "writeLine", "(Ljava/lang/Object;)V");
        }
        return spans > 0 && lines > 0;
    }
}
