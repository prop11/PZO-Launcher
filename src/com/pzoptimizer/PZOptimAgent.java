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
        instrumentationInstance = inst;
        PZOLogger.info("[PZO Agent] Build 42 JVM Instrumentation Agent Active");
        
        try {
            inst.addTransformer(new EngineTransformer(), true);
        } catch (Throwable ignored) {}

        // Early-boot runtime properties & core memory tuning
        HotSpotJITCompilerTuner.tuneRuntimeProperties();
        PZOEngineBridge.initialize();
        HighPrecisionTimer.initialize();
        LuaInterpreterAccelerator.apply();
        FileSystemWhitelistShield.initialize();
        PopTemplateGuard.initialize();
        StreamBufferBooster.applyStreamTweaks();
        SaveGameStreamBooster.tuneSaveEngine();
        EngineFeaturesTuner.initializeEngineFeatures();
        VehicleTravelOptimizer.initialize();
        RainAndWeatherOptimizer.initialize();
        WorldStreamerBooster.startDaemon();
        PZOFastMath.initialize();
        GenerationalHeapCleaner.startGovernor();
        AsyncEntityDistanceCache.initialize();
        CorpseAudioGovernor.applyCorpseAudioLimits();
        EngineGLStateGovernor.initialize();
        EngineFramePacer.initialize();
        NativeDirectMemoryPool.initialize();
        FastBitwiseChunkIndexer.initialize();
        DirectBufferAllocatorGovernor.initialize();
        FrameDropDiagnosticEngine.initialize();
        ChunkIngestionPacer.initialize();
        PredictiveChunkStreamer.initialize();
        ContainerConfiguratorGuard.initialize();
        com.pzoptimizer.multicore.PZOMultiCoreEngine.initialize();
        if (UnstableChannelGuard.isUnstableBuild()) {
            PZOLogger.success("[PZO Agent] Unstable Render Acceleration & Telemetry Matrix Armed");
        }
        
        // Dedicated Server optimizations if running in headless / server environment
        try {
            boolean isHeadless = java.awt.GraphicsEnvironment.isHeadless();
            if (isHeadless || System.getProperty("zomboid.steam") != null || System.getProperty("zomboid.server") != null) {
                com.pzoptimizer.server.LinuxSteamServerSanitizer.sanitize();
                com.pzoptimizer.server.ServerNetworkTuner.apply();
                com.pzoptimizer.server.ServerHordeSimEngine.apply();
                com.pzoptimizer.server.ServerChunkStreamBooster.apply();
            }
        } catch (Throwable ignored) {}

        PZOLogger.success("[PZO Agent] Live Bytecode Instrumentation engine attached");

        // Automatically load and hook any ZombieBuddy / Java Workshop mods with full Instrumentation
        try {
            JavaModLoader.loadMods(inst);
        } catch (Throwable t) {
            PZOLogger.error("[PZO Agent] Error during Java mod discovery", t);
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
    }
}
