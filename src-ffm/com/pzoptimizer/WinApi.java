package com.pzoptimizer;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * pzo_native.c's Windows OS calls done from Java with the foreign function API (Java 22+), so players whose DLL is
 * blocked by Smart App Control still get the timer, power throttling, MMCSS, priority, P-core and drive checks.
 * Only kernel32, winmm, ntdll and avrt are touched, all signed by Microsoft. Built with --release 22 into the same
 * jar and only loaded by PZONative after a version check, so Java 17 never sees this class.
 */
final class WinApi implements OsApi {

    private static final int NORMAL_PRIORITY_CLASS = 0x20;
    private static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x8000;
    private static final int HIGH_PRIORITY_CLASS = 0x80;
    private static final int PROCESS_POWER_THROTTLING = 4;
    private static final int RELATION_PROCESSOR_CORE = 0;
    private static final int IOCTL_STORAGE_QUERY_PROPERTY = 0x2D1400;
    private static final int STORAGE_DEVICE_SEEK_PENALTY_PROPERTY = 7;

    private final Arena arena = Arena.global();

    private final MethodHandle getCurrentProcess, getCurrentThread, setPriorityClass, setThreadPriority,
        setThreadAffinityMask, setProcessInformation, setProcessWorkingSetSizeEx, globalMemoryStatusEx,
        getLogicalProcessorInformationEx, getVolumePathNameW, createFileW, deviceIoControl, closeHandle,
        setEnvironmentVariableW, timeBeginPeriod, timeEndPeriod, ntSetTimerResolution, ntQueryTimerResolution,
        avSetMmThreadCharacteristicsW;

    private int physicalCores, performanceCores, logicalProcessors;
    private long pCoreMask;
    private volatile boolean timerLocked;
    private volatile int timerRes100ns = 156250;

    WinApi() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32.dll", arena);
        SymbolLookup winmm = SymbolLookup.libraryLookup("winmm.dll", arena);
        SymbolLookup ntdll = SymbolLookup.libraryLookup("ntdll.dll", arena);
        SymbolLookup avrt = SymbolLookup.libraryLookup("avrt.dll", arena);

        getCurrentProcess = fn(linker, k32, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
        getCurrentThread = fn(linker, k32, "GetCurrentThread", FunctionDescriptor.of(ADDRESS));
        setPriorityClass = fn(linker, k32, "SetPriorityClass", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        setThreadPriority = fn(linker, k32, "SetThreadPriority", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        setThreadAffinityMask = fn(linker, k32, "SetThreadAffinityMask", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
        setProcessInformation = fn(linker, k32, "SetProcessInformation",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        setProcessWorkingSetSizeEx = fn(linker, k32, "SetProcessWorkingSetSizeEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT));
        globalMemoryStatusEx = fn(linker, k32, "GlobalMemoryStatusEx", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getLogicalProcessorInformationEx = fn(linker, k32, "GetLogicalProcessorInformationEx",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        getVolumePathNameW = fn(linker, k32, "GetVolumePathNameW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        createFileW = fn(linker, k32, "CreateFileW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        deviceIoControl = fn(linker, k32, "DeviceIoControl",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        closeHandle = fn(linker, k32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        setEnvironmentVariableW = fn(linker, k32, "SetEnvironmentVariableW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        timeBeginPeriod = fn(linker, winmm, "timeBeginPeriod", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        timeEndPeriod = fn(linker, winmm, "timeEndPeriod", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        ntSetTimerResolution = fn(linker, ntdll, "NtSetTimerResolution",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_BYTE, ADDRESS));
        ntQueryTimerResolution = fn(linker, ntdll, "NtQueryTimerResolution",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        avSetMmThreadCharacteristicsW = fn(linker, avrt, "AvSetMmThreadCharacteristicsW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

        // Same GPU driver hints initNative sets
        try {
            int r1 = (int) setEnvironmentVariableW.invokeExact(wide("__GL_THREADED_OPTIMIZATIONS"), wide("1"));
            int r2 = (int) setEnvironmentVariableW.invokeExact(wide("__GL_YIELD"), wide("NOTHING"));
        } catch (Throwable ignored) {}

        detectTopology();
    }

    private static MethodHandle fn(Linker linker, SymbolLookup lib, String name, FunctionDescriptor desc) {
        return lib.find(name).map(addr -> linker.downcallHandle(addr, desc)).orElse(null);
    }

    private MemorySegment wide(String s) {
        return arena.allocateFrom(s, StandardCharsets.UTF_16LE);
    }

    private MemorySegment process() throws Throwable {
        return (MemorySegment) getCurrentProcess.invokeExact();
    }

    private MemorySegment thread() throws Throwable {
        return (MemorySegment) getCurrentThread.invokeExact();
    }

    private void detectTopology() {
        int logical = Runtime.getRuntime().availableProcessors();
        physicalCores = performanceCores = logicalProcessors = logical;
        pCoreMask = logical >= 64 ? -1L : (1L << logical) - 1;
        if (getLogicalProcessorInformationEx == null) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment len = a.allocate(JAVA_INT);
            int probe = (int) getLogicalProcessorInformationEx.invokeExact(RELATION_PROCESSOR_CORE, MemorySegment.NULL, len);
            int size = len.get(JAVA_INT, 0);
            if (size <= 0) return;
            MemorySegment buf = a.allocate(size, 8);
            if ((int) getLogicalProcessorInformationEx.invokeExact(RELATION_PROCESSOR_CORE, buf, len) == 0) return;

            // SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX: Relationship @0, Size @4, then PROCESSOR_RELATIONSHIP with
            // EfficiencyClass @9, GroupCount @30 and 16-byte GROUP_AFFINITY entries (Mask first) from @32
            int maxClass = 0, phys = 0, logicalCount = 0;
            for (long off = 0; off < size; ) {
                int entrySize = buf.get(JAVA_INT, off + 4);
                if (entrySize <= 0) break;
                if (buf.get(JAVA_INT, off) == RELATION_PROCESSOR_CORE) {
                    phys++;
                    maxClass = Math.max(maxClass, buf.get(JAVA_BYTE, off + 9) & 0xFF);
                    int groups = buf.get(JAVA_SHORT, off + 30) & 0xFFFF;
                    for (int g = 0; g < groups; g++) {
                        logicalCount += Long.bitCount(buf.get(JAVA_LONG, off + 32 + g * 16L));
                    }
                }
                off += entrySize;
            }
            int pCount = 0;
            long mask = 0;
            for (long off = 0; off < size; ) {
                int entrySize = buf.get(JAVA_INT, off + 4);
                if (entrySize <= 0) break;
                if (buf.get(JAVA_INT, off) == RELATION_PROCESSOR_CORE && (buf.get(JAVA_BYTE, off + 9) & 0xFF) == maxClass) {
                    pCount++;
                    if ((buf.get(JAVA_SHORT, off + 30) & 0xFFFF) > 0) mask |= buf.get(JAVA_LONG, off + 32);
                }
                off += entrySize;
            }
            if (phys > 0) {
                physicalCores = phys;
                logicalProcessors = logicalCount > 0 ? logicalCount : logical;
                performanceCores = pCount;
                if (mask != 0) pCoreMask = mask;
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean setTimerResolution(boolean enable) {
        try {
            if (enable) {
                if (timeBeginPeriod != null) { int r = (int) timeBeginPeriod.invokeExact(1); }
                if (ntSetTimerResolution != null) {
                    try (Arena a = Arena.ofConfined()) {
                        MemorySegment min = a.allocate(JAVA_INT), max = a.allocate(JAVA_INT), cur = a.allocate(JAVA_INT);
                        if (ntQueryTimerResolution != null) { int r = (int) ntQueryTimerResolution.invokeExact(min, max, cur); }
                        // "max" is the finest resolution, in 100 ns units; 5000 is 0.5 ms
                        int finest = max.get(JAVA_INT, 0);
                        int desired = finest > 5000 ? finest : 5000;
                        MemorySegment actual = a.allocate(JAVA_INT);
                        int status = (int) ntSetTimerResolution.invokeExact(desired, (byte) 1, actual);
                        if (status == 0) {
                            timerRes100ns = actual.get(JAVA_INT, 0);
                            timerLocked = true;
                            return true;
                        }
                    }
                }
                timerRes100ns = 10000;
                timerLocked = true;
                return true;
            }
            if (timeEndPeriod != null) { int r = (int) timeEndPeriod.invokeExact(1); }
            if (ntSetTimerResolution != null && timerLocked) {
                try (Arena a = Arena.ofConfined()) {
                    MemorySegment actual = a.allocate(JAVA_INT);
                    int ignored = (int) ntSetTimerResolution.invokeExact(5000, (byte) 0, actual);
                    timerRes100ns = actual.get(JAVA_INT, 0);
                }
            }
            timerLocked = false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int timerResolution100ns() {
        return timerRes100ns;
    }

    @Override
    public boolean disablePowerThrottling() {
        if (setProcessInformation == null) return false;
        try (Arena a = Arena.ofConfined()) {
            // PROCESS_POWER_THROTTLING_STATE {Version 1, ControlMask EXECUTION_SPEED|IGNORE_TIMER_RESOLUTION, StateMask 0}
            MemorySegment state = a.allocate(12, 4);
            state.set(JAVA_INT, 0, 1);
            state.set(JAVA_INT, 4, 0x1 | 0x4);
            state.set(JAVA_INT, 8, 0);
            return (int) setProcessInformation.invokeExact(process(), PROCESS_POWER_THROTTLING, state, 12) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean setMmcss(String profile) {
        if (avSetMmThreadCharacteristicsW == null) return false;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment name = a.allocateFrom(profile == null || profile.isEmpty() ? "Games" : profile, StandardCharsets.UTF_16LE);
            MemorySegment task = (MemorySegment) avSetMmThreadCharacteristicsW.invokeExact(name, a.allocate(JAVA_INT));
            return task.address() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean setPriorityClass(int level) {
        if (setPriorityClass == null) return false;
        int cls = level == 2 ? HIGH_PRIORITY_CLASS : level == 0 ? NORMAL_PRIORITY_CLASS : ABOVE_NORMAL_PRIORITY_CLASS;
        try {
            return (int) setPriorityClass.invokeExact(process(), cls) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean lockWorkingSet() {
        if (setProcessWorkingSetSizeEx == null) return false;
        try (Arena a = Arena.ofConfined()) {
            // pzo_native.c raises the class to HIGH here too
            setPriorityClass(2);
            long gb = 1024L * 1024 * 1024;
            long min = gb, max = 8 * gb;
            MemorySegment status = a.allocate(64, 8);
            status.set(JAVA_INT, 0, 64);
            if (globalMemoryStatusEx != null && (int) globalMemoryStatusEx.invokeExact(status) != 0) {
                long total = status.get(JAVA_LONG, 8);
                min = total >= 8 * gb ? 2 * gb : 512L * 1024 * 1024;
                max = total > 4 * gb ? total * 3 / 4 : total;
            }
            // Flags 0: soft limits, so the memory manager never suspends threads on a hard quota
            return (int) setProcessWorkingSetSizeEx.invokeExact(process(), min, max, 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean optimizeCallingThread(int priorityLevel, boolean bindPCores, String mmcssProfile) {
        try {
            int p = priorityLevel >= 3 ? 15 : priorityLevel == 2 ? 2 : priorityLevel == 1 ? 1 : 0;
            if (setThreadPriority != null) { int r = (int) setThreadPriority.invokeExact(thread(), p); }
            if (bindPCores) bindCallingThreadToPCores();
            if (mmcssProfile != null && !mmcssProfile.isEmpty()) setMmcss(mmcssProfile);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean bindCallingThreadToPCores() {
        if (setThreadAffinityMask == null || pCoreMask == 0) return false;
        try {
            return (long) setThreadAffinityMask.invokeExact(thread(), pCoreMask) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int physicalCores() { return physicalCores; }

    @Override
    public int performanceCores() { return performanceCores; }

    @Override
    public int logicalProcessors() { return logicalProcessors; }

    @Override
    public long performanceCoreMask() { return pCoreMask; }

    @Override
    public boolean isSolidStateDrive(String path) {
        if (path == null || getVolumePathNameW == null || createFileW == null || deviceIoControl == null) return true;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment vol = a.allocate(520 * 2, 2);
            if ((int) getVolumePathNameW.invokeExact(a.allocateFrom(path, StandardCharsets.UTF_16LE), vol, 520) == 0) return true;
            String volume = vol.getString(0, StandardCharsets.UTF_16LE);
            if (volume.endsWith("\\")) volume = volume.substring(0, volume.length() - 1);

            MemorySegment dev = (MemorySegment) createFileW.invokeExact(
                a.allocateFrom("\\\\.\\" + volume, StandardCharsets.UTF_16LE), 0, 3, MemorySegment.NULL, 3, 0, MemorySegment.NULL);
            if (dev.address() == -1L || dev.address() == 0) return true;
            try {
                // STORAGE_PROPERTY_QUERY {PropertyId, QueryType 0, AdditionalParameters} -> DEVICE_SEEK_PENALTY_DESCRIPTOR
                MemorySegment query = a.allocate(12, 4);
                query.set(JAVA_INT, 0, STORAGE_DEVICE_SEEK_PENALTY_PROPERTY);
                MemorySegment result = a.allocate(12, 4);
                MemorySegment returned = a.allocate(JAVA_INT);
                int ok = (int) deviceIoControl.invokeExact(dev, IOCTL_STORAGE_QUERY_PROPERTY, query, 12, result, 12,
                    returned, MemorySegment.NULL);
                return ok == 0 || result.get(JAVA_BYTE, 8) == 0;
            } finally {
                int ignored = (int) closeHandle.invokeExact(dev);
            }
        } catch (Throwable t) {
            return true;
        }
    }
}
