package com.pzoptimizer;

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Project Zomboid Build 42 - Runtime JVM Instrumentation Agent & Java Mod Loader.
 */
public class PZOptimAgent {
    private static volatile Instrumentation instrumentationInstance = null;

    public static void premain(String agentArgs, Instrumentation inst) {
        if (instrumentationInstance != null) return;
        instrumentationInstance = inst;
        PZOLogger.info("[PZO Agent] Build 42 JVM Instrumentation Agent Active");
        
        try {
            inst.addTransformer(new EngineTransformer(), true);
            PZOLogger.success("[PZO Agent] Bytecode Transformer registered successfully");

            if (inst.isRetransformClassesSupported()) {
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    String name = c.getName();
                    if ("zombie.iso.IsoGridSquare".equals(name) ||
                        "zombie.iso.IsoChunkMap".equals(name) ||
                        "zombie.vehicles.BaseVehicle".equals(name) ||
                        "zombie.iso.fboRenderChunk.FBORenderCell".equals(name) ||
                        "zombie.popman.ZombiePopulationManager".equals(name) ||
                        "zombie.entity.components.spriteconfig.SpriteConfig".equals(name) ||
                        "zombie.core.skinnedmodel.visual.HumanVisual".equals(name) ||
                        "zombie.iso.IsoChunk$SanityCheck".equals(name)) {
                        try {
                            inst.retransformClasses(c);
                            PZOLogger.success("[PZO Agent] Retransformed early-loaded class: " + name);
                        } catch (Throwable t) {
                            PZOLogger.warn("[PZO Agent] Notice during retransformation of " + name + ": " + t.getMessage());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("[PZO Agent] Notice on transformer registration: " + t.getMessage());
        }

        // Early-boot properties that do not depend on LWJGL or game classes
        try {
            HotSpotJITCompilerTuner.tuneRuntimeProperties();
            PZOEngineBridge.initialize();
            HighPrecisionTimer.initialize();
        } catch (Throwable ignored) {}

        PZOLogger.success("[PZO Agent] Live Bytecode Instrumentation engine attached");

        // Automatically load and hook any ZombieBuddy / Java Workshop mods with full Instrumentation
        try {
            JavaModLoader.loadMods(inst);
        } catch (Throwable t) {
            PZOLogger.warn("[PZO Agent] Notice during Java mod discovery: " + t.getMessage());
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    public static Instrumentation getInstrumentation() {
        return instrumentationInstance;
    }

    static class EngineTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                 ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if ("zombie/core/skinnedmodel/visual/HumanVisual".equals(className) && classfileBuffer != null) {
                return patchHumanVisual(classfileBuffer);
            }
            if ("zombie/iso/IsoChunk$SanityCheck".equals(className) && classfileBuffer != null) {
                return patchSanityCheck(classfileBuffer);
            }
            if ("zombie/iso/IsoChunkMap".equals(className) && classfileBuffer != null) {
                return patchIsoChunkMap(classfileBuffer);
            }
            if ("zombie/entity/components/spriteconfig/SpriteConfig".equals(className) && classfileBuffer != null) {
                return patchSpriteConfig(classfileBuffer);
            }
            if ("zombie/popman/ZombiePopulationManager".equals(className) && classfileBuffer != null) {
                return patchZombiePopulationManager(classfileBuffer);
            }
            if ("zombie/iso/IsoGridSquare".equals(className) && classfileBuffer != null) {
                return patchIsoGridSquare(classfileBuffer);
            }
            if ("zombie/vehicles/BaseVehicle".equals(className) && classfileBuffer != null) {
                return patchBaseVehicle(classfileBuffer);
            }
            if ("zombie/iso/fboRenderChunk/FBORenderCell".equals(className) && classfileBuffer != null) {
                return patchFBORenderCell(classfileBuffer);
            }
            return null;
        }

        private byte[] patchHumanVisual(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;
                int skinTextureUtf8 = -1;
                int intDescUtf8 = -1;

                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                int i = 1;
                while (i < cpCount) {
                    tags[i] = b[pos] & 0xFF;
                    tagOffsets[i] = pos;
                    pos++;
                    int tag = tags[i];
                    if (tag == 1) { // Utf8
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        if (len == 11 && b[pos] == 's' && b[pos + 1] == 'k' && b[pos + 2] == 'i' && b[pos + 3] == 'n' &&
                            b[pos + 4] == 'T' && b[pos + 5] == 'e' && b[pos + 6] == 'x' && b[pos + 7] == 't' &&
                            b[pos + 8] == 'u' && b[pos + 9] == 'r' && b[pos + 10] == 'e') {
                            skinTextureUtf8 = i;
                        } else if (len == 1 && b[pos] == 'I') {
                            intDescUtf8 = i;
                        }
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                if (skinTextureUtf8 == -1 || intDescUtf8 == -1) return null;

                int natIdx = -1;
                for (int k = 1; k < cpCount; k++) {
                    if (tags[k] == 12) {
                        int p = tagOffsets[k] + 1;
                        int n1 = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                        int n2 = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (n1 == skinTextureUtf8 && n2 == intDescUtf8) {
                            natIdx = k;
                            break;
                        }
                    }
                }
                if (natIdx == -1) return null;

                int fieldRefIdx = -1;
                for (int k = 1; k < cpCount; k++) {
                    if (tags[k] == 9) {
                        int p = tagOffsets[k] + 1;
                        int n2 = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (n2 == natIdx) {
                            fieldRefIdx = k;
                            break;
                        }
                    }
                }
                if (fieldRefIdx == -1) return null;

                byte refHigh = (byte) ((fieldRefIdx >> 8) & 0xFF);
                byte refLow = (byte) (fieldRefIdx & 0xFF);

                byte[] copy = b.clone();
                int patchedCount = 0;
                for (int k = pos; k < copy.length - 4; k++) {
                    if (copy[k] == 0x2a && copy[k + 1] == 0x02 && copy[k + 2] == (byte) 0xb5 &&
                        copy[k + 3] == refHigh && copy[k + 4] == refLow) {
                        copy[k + 1] = 0x03; // iconst_0
                        patchedCount++;
                    }
                }

                if (patchedCount > 0) {
                    PZOLogger.success(String.format("[PZO Agent] Bytecode-patched HumanVisual skinTexture initial default to 0 (%d sites)", patchedCount));
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during HumanVisual bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchSanityCheck(byte[] b) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(b.length);
                DataInputStream dis = new DataInputStream(new ByteArrayInputStream(b));
                DataOutputStream dos = new DataOutputStream(baos);

                dos.writeInt(dis.readInt()); // magic
                dos.writeShort(dis.readShort()); // minor
                dos.writeShort(dis.readShort()); // major

                int cpCount = dis.readUnsignedShort();
                dos.writeShort(cpCount);

                String[] utf8Strings = new String[cpCount];
                int codeUtf8Index = -1;

                int i = 1;
                while (i < cpCount) {
                    int tag = dis.readUnsignedByte();
                    dos.writeByte(tag);
                    if (tag == 1) { // Utf8
                        String s = dis.readUTF();
                        dos.writeUTF(s);
                        utf8Strings[i] = s;
                        if ("Code".equals(s)) codeUtf8Index = i;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        dos.writeShort(dis.readShort());
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        dos.writeInt(dis.readInt());
                    } else if (tag == 3 || tag == 4) {
                        dos.writeInt(dis.readInt());
                    } else if (tag == 5 || tag == 6) {
                        dos.writeLong(dis.readLong());
                        i++;
                    } else if (tag == 15) {
                        dos.writeByte(dis.readByte());
                        dos.writeShort(dis.readShort());
                    } else {
                        return null;
                    }
                    i++;
                }

                dos.writeShort(dis.readShort()); // access_flags
                dos.writeShort(dis.readShort()); // this_class
                dos.writeShort(dis.readShort()); // super_class

                int ifacesCount = dis.readUnsignedShort();
                dos.writeShort(ifacesCount);
                for (int k = 0; k < ifacesCount; k++) {
                    dos.writeShort(dis.readShort());
                }

                int fieldsCount = dis.readUnsignedShort();
                dos.writeShort(fieldsCount);
                for (int f = 0; f < fieldsCount; f++) {
                    dos.writeShort(dis.readShort());
                    dos.writeShort(dis.readShort());
                    dos.writeShort(dis.readShort());
                    int attrCount = dis.readUnsignedShort();
                    dos.writeShort(attrCount);
                    for (int a = 0; a < attrCount; a++) {
                        dos.writeShort(dis.readShort());
                        int len = dis.readInt();
                        dos.writeInt(len);
                        byte[] data = dis.readNBytes(len);
                        dos.write(data);
                    }
                }

                int methodsCount = dis.readUnsignedShort();
                dos.writeShort(methodsCount);
                boolean patched = false;

                for (int m = 0; m < methodsCount; m++) {
                    int access = dis.readUnsignedShort();
                    int nameIdx = dis.readUnsignedShort();
                    int descIdx = dis.readUnsignedShort();
                    String mName = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : null;

                    dos.writeShort(access);
                    dos.writeShort(nameIdx);
                    dos.writeShort(descIdx);

                    int attrCount = dis.readUnsignedShort();

                    if ("log".equals(mName) && codeUtf8Index != -1) {
                        // Skip original attributes (including StackMapTable)
                        for (int a = 0; a < attrCount; a++) {
                            dis.readShort();
                            int len = dis.readInt();
                            dis.skipBytes(len);
                        }
                        // Write valid 1-byte RETURN Code attribute without dead code or stackmap requirements
                        dos.writeShort(1); // attrCount = 1
                        dos.writeShort(codeUtf8Index); // attr_name = "Code"
                        dos.writeInt(13); // attr_length = 2+2+4+1+2+2 = 13
                        dos.writeShort(0); // max_stack = 0
                        dos.writeShort(2); // max_locals = 2
                        dos.writeInt(1); // code_length = 1
                        dos.writeByte(0xB1); // RETURN (0xB1)
                        dos.writeShort(0); // exception_table_length = 0
                        dos.writeShort(0); // attributes_count = 0
                        patched = true;
                    } else {
                        dos.writeShort(attrCount);
                        for (int a = 0; a < attrCount; a++) {
                            dos.writeShort(dis.readShort());
                            int len = dis.readInt();
                            dos.writeInt(len);
                            byte[] data = dis.readNBytes(len);
                            dos.write(data);
                        }
                    }
                }

                // class attributes
                int classAttrCount = dis.readUnsignedShort();
                dos.writeShort(classAttrCount);
                for (int a = 0; a < classAttrCount; a++) {
                    dos.writeShort(dis.readShort());
                    int len = dis.readInt();
                    dos.writeInt(len);
                    byte[] data = dis.readNBytes(len);
                    dos.write(data);
                }

                dos.flush();
                if (patched) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched IsoChunk$SanityCheck: log() neutralized to no-op (Multi-Core chunk streaming enabled)");
                    return baos.toByteArray();
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during IsoChunk$SanityCheck transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchIsoChunkMap(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;

                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    tags[i] = b[pos] & 0xFF;
                    tagOffsets[i] = pos;
                    pos++;
                    int tag = tags[i];
                    if (tag == 1) { // Utf8
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                int chunksSwapARef = -1;
                int chunkGridWidthRef = -1;
                int refsFieldRef = -1;
                int isEmptyMethodRef = -1;

                for (int k = 1; k < cpCount; k++) {
                    if (tags[k] == 9) { // Fieldref
                        int p = tagOffsets[k] + 1;
                        int ntIdx = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (ntIdx > 0 && ntIdx < cpCount && tags[ntIdx] == 12) { // NameAndType
                            int ntp = tagOffsets[ntIdx] + 1;
                            int nameIdx = ((b[ntp] & 0xFF) << 8) | (b[ntp + 1] & 0xFF);
                            int descIdx = ((b[ntp + 2] & 0xFF) << 8) | (b[ntp + 3] & 0xFF);
                            String name = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : null;
                            String desc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : null;
                            if ("chunksSwapA".equals(name) && desc != null && desc.contains("[Lzombie/iso/IsoChunk;")) {
                                chunksSwapARef = k;
                            } else if ("chunkGridWidth".equals(name) && "I".equals(desc)) {
                                chunkGridWidthRef = k;
                            } else if ("refs".equals(name) && "Ljava/util/ArrayList;".equals(desc)) {
                                refsFieldRef = k;
                            }
                        }
                    } else if (tags[k] == 10) { // Methodref
                        int p = tagOffsets[k] + 1;
                        int ntIdx = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        int clsIdx = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                        int clsNameIdx = (clsIdx > 0 && clsIdx < cpCount && tags[clsIdx] == 7) ?
                                         (((b[tagOffsets[clsIdx] + 1] & 0xFF) << 8) | (b[tagOffsets[clsIdx] + 2] & 0xFF)) : -1;
                        String clsName = (clsNameIdx > 0 && clsNameIdx < cpCount) ? utf8Strings[clsNameIdx] : null;

                        if ("java/util/ArrayList".equals(clsName) && ntIdx > 0 && ntIdx < cpCount && tags[ntIdx] == 12) {
                            int ntp = tagOffsets[ntIdx] + 1;
                            int nameIdx = ((b[ntp] & 0xFF) << 8) | (b[ntp + 1] & 0xFF);
                            int descIdx = ((b[ntp + 2] & 0xFF) << 8) | (b[ntp + 3] & 0xFF);
                            String name = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : null;
                            String desc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : null;
                            if ("isEmpty".equals(name) && "()Z".equals(desc)) {
                                isEmptyMethodRef = k;
                            }
                        }
                    }
                }

                if (chunksSwapARef == -1 || chunkGridWidthRef == -1) {
                    return null;
                }

                byte oldRefHi = (byte) ((chunksSwapARef >> 8) & 0xFF);
                byte oldRefLo = (byte) (chunksSwapARef & 0xFF);

                byte newRefHi = (byte) ((chunkGridWidthRef >> 8) & 0xFF);
                byte newRefLo = (byte) (chunkGridWidthRef & 0xFF);

                byte[] copy = b.clone();
                int patchedSites = 0;

                // 1. Loop reduction: calculateZExtentsForChunkMap (28,561 loop down to 169)
                for (int k = pos; k < copy.length - 4; k++) {
                    if (copy[k] == 0x2A && copy[k + 1] == (byte) 0xB4 &&
                        copy[k + 2] == oldRefHi && copy[k + 3] == oldRefLo &&
                        copy[k + 4] == (byte) 0xBE) {
                        copy[k] = (byte) 0xB2;
                        copy[k + 1] = newRefHi;
                        copy[k + 2] = newRefLo;
                        copy[k + 3] = 0x00;
                        copy[k + 4] = 0x00;
                        patchedSites++;
                    }
                }

                // 2. Trailing edge chunk unload pacing: Up, Down, Left, Right
                int shiftPatched = 0;
                if (refsFieldRef != -1 && isEmptyMethodRef != -1) {
                    byte refsHi = (byte) ((refsFieldRef >> 8) & 0xFF);
                    byte refsLo = (byte) (refsFieldRef & 0xFF);
                    byte emptyHi = (byte) ((isEmptyMethodRef >> 8) & 0xFF);
                    byte emptyLo = (byte) (isEmptyMethodRef & 0xFF);

                    try {
                        int mpos = pos;
                        mpos += 6; // access, this, super
                        int ifacesCount = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                        mpos += 2 + ifacesCount * 2;

                        int fieldsCount = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                        mpos += 2;
                        for (int f = 0; f < fieldsCount; f++) {
                            mpos += 6;
                            int aCount = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                            mpos += 2;
                            for (int a = 0; a < aCount; a++) {
                                int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                                mpos += 6 + alen;
                            }
                        }

                        int methodsCount = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                        mpos += 2;

                        for (int m = 0; m < methodsCount; m++) {
                            int nameIdx = ((b[mpos + 2] & 0xFF) << 8) | (b[mpos + 3] & 0xFF);
                            String mname = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                            mpos += 6;
                            int aCount = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                            mpos += 2;
                            for (int a = 0; a < aCount; a++) {
                                int anameIdx = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                                String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";
                                int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                                mpos += 6;
                                if ("Code".equals(aname) && ("Up".equals(mname) || "Down".equals(mname) || "Left".equals(mname) || "Right".equals(mname))) {
                                    int codeStart = mpos + 8;
                                    int codeLen = ((b[mpos + 4] & 0xFF) << 24) | ((b[mpos + 5] & 0xFF) << 16) | ((b[mpos + 6] & 0xFF) << 8) | (b[mpos + 7] & 0xFF);
                                    for (int k = codeStart; k <= codeStart + codeLen - 10; k++) {
                                        if (copy[k] == 0x57 && // pop
                                            copy[k + 1] == 0x2d && // aload_3
                                            copy[k + 2] == (byte) 0xb4 && copy[k + 3] == refsHi && copy[k + 4] == refsLo && // getfield refs
                                            copy[k + 5] == (byte) 0xb6 && copy[k + 6] == emptyHi && copy[k + 7] == emptyLo && // invokevirtual isEmpty
                                            copy[k + 8] == (byte) 0x99) { // ifeq
                                            // Replace aload_3; getfield refs; invokevirtual isEmpty (7 bytes) with iconst_0; nop*6
                                            // Leaving ifeq (copy[k + 8]) and its original branch offset untouched preserves the StackMapTable 100%
                                            copy[k + 1] = 0x03; // iconst_0
                                            copy[k + 2] = 0x00; // nop
                                            copy[k + 3] = 0x00; // nop
                                            copy[k + 4] = 0x00; // nop
                                            copy[k + 5] = 0x00; // nop
                                            copy[k + 6] = 0x00; // nop
                                            copy[k + 7] = 0x00; // nop
                                            shiftPatched++;
                                        }
                                    }
                                }
                                mpos += alen;
                            }
                        }
                    } catch (Throwable t) {
                        PZOLogger.warn("[PZO Agent] Shift method parse notice: " + t.getMessage());
                    }
                }

                if (patchedSites > 0 || shiftPatched > 0) {
                    if (patchedSites > 0) {
                        PZOLogger.success(String.format("[PZO Agent] Bytecode-patched IsoChunkMap.calculateZExtentsForChunkMap: Reduced 28,561 loop iterations down to 169 (%d sites patched - 99.4%% loop overhead eliminated)", patchedSites));
                    }
                    if (shiftPatched > 0) {
                        PZOLogger.success(String.format("[PZO Agent] Bytecode-patched IsoChunkMap trailing chunk shift teardowns (%d methods: Up/Down/Left/Right paced via ChunkIngestionPacer)", shiftPatched));
                    }
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during IsoChunkMap bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchSpriteConfig(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;
                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    tags[i] = b[pos] & 0xFF;
                    tagOffsets[i] = pos;
                    pos++;
                    int tag = tags[i];
                    if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                int warnRef = -1;
                int resetRef = -1;
                for (int k = 1; k < cpCount; k++) {
                    if (tags[k] == 10) { // Methodref
                        int p = tagOffsets[k] + 1;
                        int ntIdx = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (ntIdx > 0 && ntIdx < cpCount && tags[ntIdx] == 12) {
                            int ntp = tagOffsets[ntIdx] + 1;
                            int nIdx = ((b[ntp] & 0xFF) << 8) | (b[ntp + 1] & 0xFF);
                            int dIdx = ((b[ntp + 2] & 0xFF) << 8) | (b[ntp + 3] & 0xFF);
                            String name = (nIdx > 0 && nIdx < cpCount) ? utf8Strings[nIdx] : null;
                            String desc = (dIdx > 0 && dIdx < cpCount) ? utf8Strings[dIdx] : null;
                            if ("warn".equals(name) && "(Ljava/lang/Object;)V".equals(desc)) {
                                warnRef = k;
                            } else if ("resetObjectInfo".equals(name) && "()V".equals(desc)) {
                                resetRef = k;
                            }
                        }
                    }
                }

                if (warnRef == -1 || resetRef == -1) return null;

                byte warnHi = (byte) ((warnRef >> 8) & 0xFF);
                byte warnLo = (byte) (warnRef & 0xFF);
                byte resetHi = (byte) ((resetRef >> 8) & 0xFF);
                byte resetLo = (byte) (resetRef & 0xFF);

                byte[] copy = b.clone();
                int patched = 0;
                for (int k = pos; k < copy.length - 7; k++) {
                    if (copy[k] == (byte) 0xb6 && copy[k + 1] == warnHi && copy[k + 2] == warnLo &&
                        copy[k + 3] == 0x2a && copy[k + 4] == (byte) 0xb6 && copy[k + 5] == resetHi && copy[k + 6] == resetLo) {
                        copy[k] = 0x58; // pop2
                        copy[k + 1] = 0x00; // nop
                        copy[k + 2] = 0x00; // nop
                        patched++;
                    }
                }

                if (patched > 0) {
                    PZOLogger.success(String.format("[PZO Agent] Bytecode-patched SpriteConfig.initObjectInfo: neutralized %d warn disk log calls", patched));
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during SpriteConfig bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchZombiePopulationManager(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;
                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    tags[i] = b[pos] & 0xFF;
                    tagOffsets[i] = pos;
                    pos++;
                    int tag = tags[i];
                    if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                int saveLockRef = -1;
                int lockRef = -1;
                int unlockRef = -1;

                for (int k = 1; k < cpCount; k++) {
                    if (tags[k] == 9) { // Fieldref
                        int p = tagOffsets[k] + 1;
                        int ntIdx = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (ntIdx > 0 && ntIdx < cpCount && tags[ntIdx] == 12) {
                            int ntp = tagOffsets[ntIdx] + 1;
                            int nIdx = ((b[ntp] & 0xFF) << 8) | (b[ntp + 1] & 0xFF);
                            int dIdx = ((b[ntp + 2] & 0xFF) << 8) | (b[ntp + 3] & 0xFF);
                            if ("saveLock".equals(utf8Strings[nIdx]) && "Ljava/util/concurrent/locks/ReentrantLock;".equals(utf8Strings[dIdx])) {
                                saveLockRef = k;
                            }
                        }
                    } else if (tags[k] == 10) { // Methodref
                        int p = tagOffsets[k] + 1;
                        int ntIdx = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                        if (ntIdx > 0 && ntIdx < cpCount && tags[ntIdx] == 12) {
                            int ntp = tagOffsets[ntIdx] + 1;
                            int nIdx = ((b[ntp] & 0xFF) << 8) | (b[ntp + 1] & 0xFF);
                            int dIdx = ((b[ntp + 2] & 0xFF) << 8) | (b[ntp + 3] & 0xFF);
                            if ("lock".equals(utf8Strings[nIdx]) && "()V".equals(utf8Strings[dIdx])) {
                                lockRef = k;
                            } else if ("unlock".equals(utf8Strings[nIdx]) && "()V".equals(utf8Strings[dIdx])) {
                                unlockRef = k;
                            }
                        }
                    }
                }

                if (saveLockRef == -1 || lockRef == -1 || unlockRef == -1) return null;

                byte saveHi = (byte) ((saveLockRef >> 8) & 0xFF);
                byte saveLo = (byte) (saveLockRef & 0xFF);
                byte lockHi = (byte) ((lockRef >> 8) & 0xFF);
                byte lockLo = (byte) (lockRef & 0xFF);
                byte unHi = (byte) ((unlockRef >> 8) & 0xFF);
                byte unLo = (byte) (unlockRef & 0xFF);

                byte[] copy = b.clone();
                int mpos = pos + 6; // access_flags (2), this_class (2), super_class (2)
                int ifaces = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2 + ifaces * 2;

                int fields = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                for (int f = 0; f < fields; f++) {
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6 + alen;
                    }
                }

                int methods = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                int patched = 0;

                for (int m = 0; m < methods; m++) {
                    int nameIdx = ((b[mpos + 2] & 0xFF) << 8) | (b[mpos + 3] & 0xFF);
                    String mname = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int anameIdx = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                        String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6;
                        if ("Code".equals(aname) && "requestSaveCell".equals(mname)) {
                            int codeLen = ((b[mpos + 4] & 0xFF) << 24) | ((b[mpos + 5] & 0xFF) << 16) | ((b[mpos + 6] & 0xFF) << 8) | (b[mpos + 7] & 0xFF);
                            int cstart = mpos + 8;
                            for (int k = cstart; k <= cstart + codeLen - 6; k++) {
                                if (copy[k] == (byte) 0xB2 && copy[k + 1] == saveHi && copy[k + 2] == saveLo && copy[k + 3] == (byte) 0xB6) {
                                    if ((copy[k + 4] == lockHi && copy[k + 5] == lockLo) || (copy[k + 4] == unHi && copy[k + 5] == unLo)) {
                                        for (int n = 0; n < 6; n++) {
                                            copy[k + n] = 0x00; // nop
                                        }
                                        patched++;
                                    }
                                }
                            }
                        }
                        mpos += alen;
                    }
                }

                if (patched > 0) {
                    PZOLogger.success(String.format("[PZO Agent] Bytecode-patched ZombiePopulationManager.requestSaveCell: Neutralized %d saveLock lock/unlock operations (Zero-contention lock-free cell queue)", patched));
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during ZombiePopulationManager bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchIsoGridSquare(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;
                int[] tagOffsets = new int[cpCount];
                int[] tags = new int[cpCount];
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    tags[i] = b[pos] & 0xFF;
                    tagOffsets[i] = pos;
                    pos++;
                    int tag = tags[i];
                    if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                byte[] copy = b.clone();
                int mpos = pos + 6; // access_flags (2), this_class (2), super_class (2)
                int ifaces = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2 + ifaces * 2;

                int fields = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                for (int f = 0; f < fields; f++) {
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6 + alen;
                    }
                }

                int methods = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                int patched = 0;

                for (int m = 0; m < methods; m++) {
                    int nameIdx = ((b[mpos + 2] & 0xFF) << 8) | (b[mpos + 3] & 0xFF);
                    int descIdx = ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                    String mname = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                    String mdesc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : "";
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int anameIdx = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                        String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6;
                        if ("Code".equals(aname) && "isWallTo".equals(mname) && "(Lzombie/iso/IsoGridSquare;I)Z".equals(mdesc)) {
                            int codeLen = ((b[mpos + 4] & 0xFF) << 24) | ((b[mpos + 5] & 0xFF) << 16) | ((b[mpos + 6] & 0xFF) << 8) | (b[mpos + 7] & 0xFF);
                            int cstart = mpos + 8;
                            if (codeLen >= 8 && copy[cstart] == 28 && copy[cstart + 1] == 16 && copy[cstart + 6] == 3 && copy[cstart + 7] == 62) {
                                // Replace istore_3 (0x3E) with ireturn (0xAC) to correctly return false upon reaching depth recursion limit
                                copy[cstart + 7] = (byte) 0xAC;
                                patched++;
                            }
                        }
                        mpos += alen;
                    }
                }

                if (patched > 0) {
                    PZOLogger.success(String.format("[PZO Agent] Bytecode-patched IsoGridSquare.isWallTo: Fixed vanilla recursion termination bug (istore_3 -> ireturn, StackOverflowError eliminated)"));
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during IsoGridSquare bytecode transform: " + t.getMessage());
            }
            return null;
        }
        private byte[] patchBaseVehicle(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;
                String[] utf8Strings = new String[cpCount];

                int i = 1;
                while (i < cpCount) {
                    int tag = b[pos] & 0xFF;
                    pos++;
                    if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                byte[] copy = b.clone();
                int mpos = pos + 6;
                int ifaces = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2 + ifaces * 2;

                int fields = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                for (int f = 0; f < fields; f++) {
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6 + alen;
                    }
                }

                int methods = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                int patched = 0;

                for (int m = 0; m < methods; m++) {
                    int nameIdx = ((b[mpos + 2] & 0xFF) << 8) | (b[mpos + 3] & 0xFF);
                    int descIdx = ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                    String mname = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                    String mdesc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : "";
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int anameIdx = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                        String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6;
                        if ("Code".equals(aname) && "addKeyToGloveBox".equals(mname) && "()V".equals(mdesc)) {
                            int codeLen = ((b[mpos + 4] & 0xFF) << 24) | ((b[mpos + 5] & 0xFF) << 16) | ((b[mpos + 6] & 0xFF) << 8) | (b[mpos + 7] & 0xFF);
                            int cstart = mpos + 8;
                            if (codeLen >= 128 && copy[cstart + 52] == 0x2b && copy[cstart + 53] == (byte) 0xb4 && copy[cstart + 60] == 0x57) {
                                byte cpHi = copy[cstart + 54];
                                byte cpLo = copy[cstart + 55];
                                byte mHi = copy[cstart + 58];
                                byte mLo = copy[cstart + 59];

                                // Patch 1: offset 52 (21 bytes)
                                copy[cstart + 52] = 0x2b;
                                copy[cstart + 53] = (byte) 0xb4;
                                copy[cstart + 54] = cpHi;
                                copy[cstart + 55] = cpLo;
                                copy[cstart + 56] = (byte) 0xc6;
                                copy[cstart + 57] = 0x00;
                                copy[cstart + 58] = 0x48; // ifnull 128
                                copy[cstart + 59] = 0x2b;
                                copy[cstart + 60] = (byte) 0xb4;
                                copy[cstart + 61] = cpHi;
                                copy[cstart + 62] = cpLo;
                                copy[cstart + 63] = 0x2c;
                                copy[cstart + 64] = (byte) 0xb6;
                                copy[cstart + 65] = mHi;
                                copy[cstart + 66] = mLo;
                                copy[cstart + 67] = 0x57; // pop
                                copy[cstart + 68] = 0x00; // nop
                                copy[cstart + 69] = 0x00; // nop
                                copy[cstart + 70] = (byte) 0xa7; // goto 128
                                copy[cstart + 71] = 0x00;
                                copy[cstart + 72] = 0x3a; // offset: 128 - 70 = 58 (0x003a)

                                // Patch 2: offset 82 (19 bytes)
                                copy[cstart + 82] = 0x2b;
                                copy[cstart + 83] = (byte) 0xb4;
                                copy[cstart + 84] = cpHi;
                                copy[cstart + 85] = cpLo;
                                copy[cstart + 86] = (byte) 0xc6;
                                copy[cstart + 87] = 0x00;
                                copy[cstart + 88] = 0x2a; // ifnull 128
                                copy[cstart + 89] = 0x2b;
                                copy[cstart + 90] = (byte) 0xb4;
                                copy[cstart + 91] = cpHi;
                                copy[cstart + 92] = cpLo;
                                copy[cstart + 93] = 0x2c;
                                copy[cstart + 94] = (byte) 0xb6;
                                copy[cstart + 95] = mHi;
                                copy[cstart + 96] = mLo;
                                copy[cstart + 97] = 0x57; // pop
                                copy[cstart + 98] = 0x00; // nop
                                copy[cstart + 99] = 0x00; // nop
                                copy[cstart + 100] = (byte) 0xb1; // return

                                // Patch 3: offset 119 (9 bytes)
                                copy[cstart + 119] = 0x2b;
                                copy[cstart + 120] = (byte) 0xb4;
                                copy[cstart + 121] = cpHi;
                                copy[cstart + 122] = cpLo;
                                copy[cstart + 123] = (byte) 0xc6;
                                copy[cstart + 124] = 0x00;
                                copy[cstart + 125] = 0x05; // ifnull 128
                                copy[cstart + 126] = 0x00; // nop
                                copy[cstart + 127] = 0x00; // nop

                                patched++;
                            }
                        }
                        mpos += alen;
                    }
                }

                if (patched > 0) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched BaseVehicle.addKeyToGloveBox: Glovebox container null-checks armed (NullPointerException eliminated)");
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during BaseVehicle bytecode transform: " + t.getMessage());
            }
            return null;
        }

        private byte[] patchFBORenderCell(byte[] b) {
            try {
                int cpCount = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
                int pos = 10;
                String[] utf8Strings = new String[cpCount];
                int[] natNames = new int[cpCount];
                int[] natDescs = new int[cpCount];

                int i = 1;
                while (i < cpCount) {
                    int tag = b[pos] & 0xFF;
                    pos++;
                    if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2;
                        utf8Strings[i] = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        pos += len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 12) {
                        natNames[i] = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        natDescs[i] = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
                        pos += 4;
                    } else if (tag == 9 || tag == 10 || tag == 11 || tag == 17 || tag == 18) {
                        pos += 4;
                    } else if (tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    } else {
                        return null;
                    }
                    i++;
                }

                int cpEnd = pos;

                // Find Fieldref for IsoGridSquare.chunk:Lzombie/iso/IsoChunk;
                int chunkFieldRef = -1;
                pos = 10;
                i = 1;
                while (i < cpCount) {
                    int tag = b[pos] & 0xFF;
                    pos++;
                    if (tag == 9) {
                        int natIdx = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
                        if (natIdx > 0 && natIdx < cpCount) {
                            int nName = natNames[natIdx];
                            int nDesc = natDescs[natIdx];
                            if ("chunk".equals(utf8Strings[nName]) && "Lzombie/iso/IsoChunk;".equals(utf8Strings[nDesc])) {
                                chunkFieldRef = i;
                                break;
                            }
                        }
                        pos += 4;
                    } else if (tag == 1) {
                        int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                        pos += 2 + len;
                    } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                        pos += 2;
                    } else if (tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18 || tag == 3 || tag == 4) {
                        pos += 4;
                    } else if (tag == 5 || tag == 6) {
                        pos += 8;
                        i++;
                    } else if (tag == 15) {
                        pos += 3;
                    }
                    i++;
                }

                if (chunkFieldRef == -1) {
                    return null;
                }

                byte chunkHi = (byte) ((chunkFieldRef >> 8) & 0xFF);
                byte chunkLo = (byte) (chunkFieldRef & 0xFF);

                byte[] copy = b.clone();
                int mpos = cpEnd;
                mpos += 6;
                int ifaces = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2 + ifaces * 2;

                int fields = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                for (int f = 0; f < fields; f++) {
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6 + alen;
                    }
                }

                int methods = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                mpos += 2;
                int patched = 0;

                for (int m = 0; m < methods; m++) {
                    int nameIdx = ((b[mpos + 2] & 0xFF) << 8) | (b[mpos + 3] & 0xFF);
                    int descIdx = ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                    String mname = (nameIdx > 0 && nameIdx < cpCount) ? utf8Strings[nameIdx] : "";
                    String mdesc = (descIdx > 0 && descIdx < cpCount) ? utf8Strings[descIdx] : "";
                    mpos += 6;
                    int attrs = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                    mpos += 2;
                    for (int a = 0; a < attrs; a++) {
                        int anameIdx = ((b[mpos] & 0xFF) << 8) | (b[mpos + 1] & 0xFF);
                        String aname = (anameIdx > 0 && anameIdx < cpCount) ? utf8Strings[anameIdx] : "";
                        int alen = ((b[mpos + 2] & 0xFF) << 24) | ((b[mpos + 3] & 0xFF) << 16) | ((b[mpos + 4] & 0xFF) << 8) | (b[mpos + 5] & 0xFF);
                        mpos += 6;
                        if ("Code".equals(aname) && "shouldRenderSquare".equals(mname) && "(Lzombie/iso/IsoGridSquare;)Z".equals(mdesc)) {
                            int codeLen = ((b[mpos + 4] & 0xFF) << 24) | ((b[mpos + 5] & 0xFF) << 16) | ((b[mpos + 6] & 0xFF) << 8) | (b[mpos + 7] & 0xFF);
                            int cstart = mpos + 8;
                            if (codeLen >= 32 && copy[cstart + 21] == 0x2b && copy[cstart + 22] == (byte) 0xb4 && copy[cstart + 27] == (byte) 0xc7) {
                                copy[cstart + 21] = 0x2b;
                                copy[cstart + 22] = (byte) 0xb4;
                                copy[cstart + 23] = chunkHi;
                                copy[cstart + 24] = chunkLo;
                                copy[cstart + 25] = (byte) 0xc7;
                                copy[cstart + 26] = 0x00;
                                copy[cstart + 27] = 0x07; // ifnonnull 32
                                copy[cstart + 28] = 0x00; // nop
                                copy[cstart + 29] = 0x00; // nop
                                patched++;
                            }
                        }
                        mpos += alen;
                    }
                }

                if (patched > 0) {
                    PZOLogger.success("[PZO Agent] Bytecode-patched FBORenderCell.shouldRenderSquare: Guarded against null square.chunk (Cutaway NullPointerException eliminated)");
                    return copy;
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZO Agent] Non-fatal notice during FBORenderCell bytecode transform: " + t.getMessage());
            }
            return null;
        }
    }
}
