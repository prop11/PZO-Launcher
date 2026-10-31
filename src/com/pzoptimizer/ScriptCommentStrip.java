package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Settings screen "Faster file and map loading" (JVM_FastLoad, next launch).
 *
 * ScriptParser.stripComments removes block comments by walking back from the end of the text and calling
 * StringBuilder.replace for each one, which moves everything after it. media/tileGeometry.txt alone is 6.5 MB with
 * 22,080 comments, so every boot moved tens of gigabytes of characters; script files, sandbox options and mods'
 * files go through the same method. This runs the same search, in the same order, over a view of the text in which
 * removed regions are skipped instead of moved, so the result is identical and the cost is linear.
 *
 * The view is the untouched prefix a[0, prefix) followed by the kept tail. Every search after a removal starts at or
 * before the removal point, and a two-character search starting there reads at most one character of the tail, so
 * only the tail's first two characters are ever looked at and the tail can stay a list of untouched ranges.
 */
public final class ScriptCommentStrip {

    public static volatile boolean active = PZOConfig.isFastLoadEnabled();
    public static final AtomicLong charsStripped = new AtomicLong();

    private ScriptCommentStrip() {}

    /** Start of ScriptParser.stripComments: the stripped text, or null to run the original. */
    public static String stripOrNull(String text) {
        if (!active || text == null) return null;
        return strip(text);
    }

    static String strip(String text) {
        final char[] a = text.toCharArray();
        // Kept tail as ranges of a, first range first: starts[i]..ends[i]
        int[] starts = new int[16], ends = new int[16];
        int head = starts.length; // ranges live in [head, starts.length), prepended by decrementing head
        int tailLen = 0;
        int prefix = a.length;

        View v = new View(a);
        v.bind(prefix, starts, ends, head, tailLen);
        int end = v.lastIndexOf('*', '/', v.length());
        while (end != -1) {
            int start = v.lastIndexOf('/', '*', end - 1);
            if (start == -1) break;
            int innerCommentEnd = v.lastIndexOf('*', '/', end - 1);
            while (innerCommentEnd > start) {
                int innerCommentStart = start;
                start = v.lastIndexOf('/', '*', start - 2);
                if (start == -1) break;
                innerCommentEnd = v.lastIndexOf('*', '/', innerCommentStart - 2);
            }
            if (start == -1) break;

            // Remove [start, end + 2) from the view
            int cut = end + 2;
            if (cut > prefix) {
                // The removed range runs into the tail: drop that many characters from the tail's front
                int drop = cut - prefix;
                tailLen -= drop;
                while (drop > 0) {
                    int len = ends[head] - starts[head];
                    if (drop >= len) {
                        drop -= len;
                        head++;
                    } else {
                        starts[head] += drop;
                        drop = 0;
                    }
                }
                cut = prefix;
            }
            if (cut < prefix) {
                if (head == 0) {
                    int n = starts.length;
                    int[] ns = new int[n * 2], ne = new int[n * 2];
                    System.arraycopy(starts, 0, ns, n, n);
                    System.arraycopy(ends, 0, ne, n, n);
                    starts = ns;
                    ends = ne;
                    head += n;
                }
                head--;
                starts[head] = cut;
                ends[head] = prefix;
                tailLen += prefix - cut;
            }
            prefix = start;
            v.bind(prefix, starts, ends, head, tailLen);
            end = v.lastIndexOf('*', '/', start);
        }

        if (prefix == a.length) return text;
        StringBuilder sb = new StringBuilder(prefix + tailLen);
        sb.append(a, 0, prefix);
        for (int i = head; i < starts.length; i++) sb.append(a, starts[i], ends[i] - starts[i]);
        charsStripped.addAndGet(a.length - sb.length());
        return sb.toString();
    }

    // The current text: a[0, prefix) then the tail ranges
    private static final class View {
        final char[] a;
        int prefix, head, tailLen;
        int[] starts, ends;

        View(char[] a) {
            this.a = a;
        }

        void bind(int prefix, int[] starts, int[] ends, int head, int tailLen) {
            this.prefix = prefix;
            this.starts = starts;
            this.ends = ends;
            this.head = head;
            this.tailLen = tailLen;
        }

        int length() {
            return prefix + tailLen;
        }

        char charAt(int i) {
            if (i < prefix) return a[i];
            int off = i - prefix;
            for (int r = head; r < starts.length; r++) {
                int len = ends[r] - starts[r];
                if (off < len) return a[starts[r] + off];
                off -= len;
            }
            throw new IndexOutOfBoundsException();
        }

        // String.lastIndexOf("" + c0 + c1, from) / StringBuilder.lastIndexOf semantics
        int lastIndexOf(char c0, char c1, int from) {
            int k = Math.min(from, length() - 2);
            for (; k >= 0; k--) {
                if (k + 1 < prefix) {
                    if (a[k] == c0 && a[k + 1] == c1) return k;
                } else if (charAt(k) == c0 && charAt(k + 1) == c1) {
                    return k;
                }
            }
            return -1;
        }
    }

    static boolean patchScriptParser(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "stripComments", "(Ljava/lang/String;)Ljava/lang/String;");
        if (m == null) return false;
        // String s = stripOrNull(text); if (s != null) return s;
        LabelNode orig = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ScriptCommentStrip", "stripOrNull",
            "(Ljava/lang/String;)Ljava/lang/String;", false));
        pre.add(new InsnNode(Opcodes.DUP));
        pre.add(new JumpInsnNode(Opcodes.IFNULL, orig));
        pre.add(new InsnNode(Opcodes.ARETURN));
        pre.add(orig);
        pre.add(new InsnNode(Opcodes.POP));
        m.instructions.insert(pre);
        return true;
    }
}
