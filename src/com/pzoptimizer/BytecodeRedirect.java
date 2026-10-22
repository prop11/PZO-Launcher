package com.pzoptimizer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Rewrites invokevirtual calls of one method into invokestatic calls of a static replacement taking the receiver as
 * its first argument. Both instructions are 3 bytes and the operand stack is identical, so no offsets, stack map
 * frames or max_stack values change. Only real instructions inside Code attributes are rewritten: the method bodies
 * are decoded opcode by opcode, so constant data that happens to look like the call is never touched.
 */
final class BytecodeRedirect {

    private BytecodeRedirect() {}

    /** Returns the rewritten class, or null when the call is not present or the class could not be parsed. */
    static byte[] virtualToStatic(byte[] b, String owner, String name, String desc,
                                  String newOwner, String newName, String newDesc) {
        try {
            int cpCount = u2(b, 8);
            int[] offsets = new int[cpCount];
            int[] tags = new int[cpCount];
            int pos = 10;
            for (int i = 1; i < cpCount; i++) {
                int tag = b[pos] & 0xFF;
                tags[i] = tag;
                offsets[i] = pos;
                switch (tag) {
                    case 1: pos += 3 + u2(b, pos + 1); break;
                    case 7: case 8: case 16: case 19: case 20: pos += 3; break;
                    case 15: pos += 4; break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: pos += 5; break;
                    case 5: case 6: pos += 9; i++; break;
                    default: return null;
                }
            }
            int cpEnd = pos;

            int target = -1;
            for (int i = 1; i < cpCount; i++) {
                if (tags[i] != 10) continue;
                int classIdx = u2(b, offsets[i] + 1);
                int natIdx = u2(b, offsets[i] + 3);
                String cls = utf8(b, offsets, u2(b, offsets[classIdx] + 1));
                String n = utf8(b, offsets, u2(b, offsets[natIdx] + 1));
                String d = utf8(b, offsets, u2(b, offsets[natIdx] + 3));
                if (owner.equals(cls) && name.equals(n) && desc.equals(d)) {
                    target = i;
                    break;
                }
            }
            if (target < 0) return null;

            // New entries: Utf8 owner, Class, Utf8 name, Utf8 desc, NameAndType, Methodref
            int base = cpCount;
            int newRef = base + 5;
            ByteArrayOutputStream extra = new ByteArrayOutputStream();
            DataOutputStream e = new DataOutputStream(extra);
            e.writeByte(1); e.writeUTF(newOwner);
            e.writeByte(7); e.writeShort(base);
            e.writeByte(1); e.writeUTF(newName);
            e.writeByte(1); e.writeUTF(newDesc);
            e.writeByte(12); e.writeShort(base + 2); e.writeShort(base + 3);
            e.writeByte(10); e.writeShort(base + 1); e.writeShort(base + 4);

            byte[] rest = new byte[b.length - cpEnd];
            System.arraycopy(b, cpEnd, rest, 0, rest.length);
            int patched = rewriteCalls(rest, offsets, b, target, newRef);
            if (patched == 0) return null;

            ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + extra.size());
            DataOutputStream o = new DataOutputStream(out);
            o.write(b, 0, 8);
            o.writeShort(cpCount + 6);
            o.write(b, 10, cpEnd - 10);
            o.write(extra.toByteArray());
            o.write(rest);
            o.flush();
            return out.toByteArray();
        } catch (Throwable t) {
            PZOLogger.warn("[BytecodeRedirect] " + owner + "." + name + " redirect skipped: " + t);
            return null;
        }
    }

    // rest starts at access_flags, right after the constant pool
    private static int rewriteCalls(byte[] r, int[] cpOffsets, byte[] cls, int target, int newRef) {
        int p = 6; // access_flags, this_class, super_class
        int interfaces = u2(r, p);
        p += 2 + interfaces * 2;
        int fields = u2(r, p);
        p += 2;
        for (int i = 0; i < fields; i++) {
            p = skipMember(r, p);
        }
        int methods = u2(r, p);
        p += 2;
        int patched = 0;
        for (int i = 0; i < methods; i++) {
            int attrCount = u2(r, p + 6);
            int a = p + 8;
            for (int k = 0; k < attrCount; k++) {
                int nameIdx = u2(r, a);
                int len = u4(r, a + 2);
                if ("Code".equals(utf8(cls, cpOffsets, nameIdx))) {
                    int codeLen = u4(r, a + 10);
                    patched += rewriteCode(r, a + 14, codeLen, target, newRef);
                }
                a += 6 + len;
            }
            p = a;
        }
        return patched;
    }

    private static int skipMember(byte[] r, int p) {
        int attrCount = u2(r, p + 6);
        int a = p + 8;
        for (int k = 0; k < attrCount; k++) {
            a += 6 + u4(r, a + 2);
        }
        return a;
    }

    private static int rewriteCode(byte[] r, int start, int len, int target, int newRef) {
        int patched = 0;
        int pc = 0;
        while (pc < len) {
            int at = start + pc;
            int op = r[at] & 0xFF;
            if (op == 0xB6 && u2(r, at + 1) == target) {
                r[at] = (byte) 0xB8;
                r[at + 1] = (byte) (newRef >> 8);
                r[at + 2] = (byte) newRef;
                patched++;
            }
            pc += instructionLength(r, start, pc, op);
        }
        return patched;
    }

    private static int instructionLength(byte[] r, int start, int pc, int op) {
        switch (op) {
            case 0xAA: { // tableswitch
                int p = (pc + 4) & ~3;
                int low = u4(r, start + p + 4);
                int high = u4(r, start + p + 8);
                return p - pc + 12 + (high - low + 1) * 4;
            }
            case 0xAB: { // lookupswitch
                int p = (pc + 4) & ~3;
                int n = u4(r, start + p + 4);
                return p - pc + 8 + n * 8;
            }
            case 0xC4: // wide
                return (r[start + pc + 1] & 0xFF) == 0x84 ? 6 : 4;
            default:
                return LENGTHS[op];
        }
    }

    private static final int[] LENGTHS = new int[256];
    static {
        java.util.Arrays.fill(LENGTHS, 1);
        for (int op : new int[]{0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC}) LENGTHS[op] = 2;
        for (int op = 0x99; op <= 0xA8; op++) LENGTHS[op] = 3;
        for (int op : new int[]{0x11, 0x13, 0x14, 0x84, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7}) LENGTHS[op] = 3;
        LENGTHS[0xC5] = 4;
        LENGTHS[0xB9] = 5;
        LENGTHS[0xBA] = 5;
        LENGTHS[0xC8] = 5;
        LENGTHS[0xC9] = 5;
    }

    private static String utf8(byte[] b, int[] offsets, int idx) {
        int p = offsets[idx];
        return new String(b, p + 3, u2(b, p + 1), StandardCharsets.UTF_8);
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int u4(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }
}
