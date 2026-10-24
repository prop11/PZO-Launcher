/*
 * PZO native library for Windows x64, Linux (x86_64 / AArch64) and macOS (Apple Silicon / Intel):
 *  - Windows: 0.5 ms timer resolution (NtSetTimerResolution + timeBeginPeriod)
 *  - Windows: Windows 11 power throttling / EcoQoS exemption (SetProcessInformation)
 *  - Windows: MMCSS "Games" scheduling via Avrt
 *  - POSIX: high-resolution monotonic timers, real-time thread priority and mlockall
 *  - hybrid CPU topology detection (P-cores vs E-cores) and thread affinity
 *  - AVX2 batch distance and culling helpers over direct NIO buffers, with a scalar fallback for ARM64
 *  - zlib / raw deflate decompression through miniz tinfl
 */

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mmsystem.h>
#include <timeapi.h>
#include <avrt.h>
#include <winioctl.h>
#include <intrin.h>
#else
#define _GNU_SOURCE
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/time.h>
#include <sys/resource.h>
#include <pthread.h>
#include <sched.h>
#include <errno.h>
#include <dlfcn.h>
#if defined(__APPLE__)
#include <sys/sysctl.h>
#include <mach/mach.h>
#elif defined(__linux__)
#include <sys/sysinfo.h>
#endif
#if defined(__x86_64__) || defined(__i386__)
#include <cpuid.h>
#endif

typedef int BOOL;
#define TRUE 1
#define FALSE 0
typedef unsigned long DWORD_PTR;
typedef unsigned long DWORD;
typedef unsigned long ULONG;
typedef unsigned char BYTE;
typedef unsigned short WORD;
typedef void* HANDLE;
#define INVALID_HANDLE_VALUE ((HANDLE)(long)-1)
#endif

#if defined(__x86_64__) || defined(_M_X64)
#include <immintrin.h>
#endif

#include <jni.h>
#include <jvmti.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "miniz.h"
#include "miniz_tinfl.c"

#ifdef _WIN32
// Dynamically resolved ntdll functions for high-precision sub-millisecond timer
typedef LONG (NTAPI *pfnNtSetTimerResolution)(ULONG DesiredResolution, BOOLEAN SetResolution, PULONG CurrentResolution);
typedef LONG (NTAPI *pfnNtQueryTimerResolution)(PULONG MinimumResolution, PULONG MaximumResolution, PULONG CurrentResolution);

static pfnNtSetTimerResolution g_NtSetTimerResolution = NULL;
static pfnNtQueryTimerResolution g_NtQueryTimerResolution = NULL;

typedef BOOL (WINAPI *pfnSetProcessInformation)(
    HANDLE hProcess,
    PROCESS_INFORMATION_CLASS ProcessInformationClass,
    LPVOID ProcessInformation,
    DWORD ProcessInformationSize
);

static pfnSetProcessInformation g_SetProcessInformation = NULL;
static HANDLE g_mmcssTaskHandle = NULL;
#endif

// CPU Topology & State Cache
static DWORD_PTR g_pCoreAffinityMask = 0;
static int g_physicalCores = 0;
static int g_pCoresCount = 0;
static int g_logicalProcessors = 0;
static BOOL g_avx2Supported = FALSE;
static BOOL g_timerLocked = FALSE;
static ULONG g_activeTimerResolution100ns = 10000;

static BOOL checkCpuAvx2Support(void) {
#if defined(_WIN32)
    int cpuInfo[4] = {0};
    __cpuid(cpuInfo, 0);
    int nIds = cpuInfo[0];
    if (nIds >= 7) {
        __cpuidex(cpuInfo, 7, 0);
        return (cpuInfo[1] & (1 << 5)) != 0; // EBX bit 5 = AVX2
    }
    return FALSE;
#elif (defined(__x86_64__) || defined(__i386__))
    unsigned int eax = 0, ebx = 0, ecx = 0, edx = 0;
    if (__get_cpuid(0, &eax, &ebx, &ecx, &edx)) {
        if (eax >= 7) {
            __cpuid_count(7, 0, eax, ebx, ecx, edx);
            return (ebx & (1 << 5)) != 0;
        }
    }
    return FALSE;
#else
    return FALSE;
#endif
}

// Distinguishes performance cores from efficiency cores
static void detectCpuTopology(void) {
#if defined(_WIN32)
    DWORD length = 0;
    GetLogicalProcessorInformationEx(RelationProcessorCore, NULL, &length);
    if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || length == 0) {
        SYSTEM_INFO si;
        GetNativeSystemInfo(&si);
        g_logicalProcessors = (int)si.dwNumberOfProcessors;
        g_physicalCores = g_logicalProcessors;
        g_pCoresCount = g_physicalCores;
        g_pCoreAffinityMask = (g_logicalProcessors >= 64) ? ~(DWORD_PTR)0 : (((DWORD_PTR)1 << g_logicalProcessors) - 1);
        return;
    }

    PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX buffer = (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)malloc(length);
    if (!buffer) return;

    if (!GetLogicalProcessorInformationEx(RelationProcessorCore, buffer, &length)) {
        free(buffer);
        return;
    }

    BYTE maxEfficiencyClass = 0;
    int totalPhysical = 0;
    int totalLogical = 0;

    // First pass: find the highest efficiency class and count cores
    BYTE* ptr = (BYTE*)buffer;
    BYTE* end = ptr + length;
    while (ptr < end) {
        PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX info = (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)ptr;
        if (info->Relationship == RelationProcessorCore) {
            totalPhysical++;
            if (info->Processor.EfficiencyClass > maxEfficiencyClass) {
                maxEfficiencyClass = info->Processor.EfficiencyClass;
            }
            for (WORD g = 0; g < info->Processor.GroupCount; g++) {
                DWORD_PTR mask = info->Processor.GroupMask[g].Mask;
                while (mask) {
                    if (mask & 1) totalLogical++;
                    mask >>= 1;
                }
            }
        }
        ptr += info->Size;
    }

    g_physicalCores = totalPhysical;
    g_logicalProcessors = totalLogical;

    // Second pass: affinity mask of that class (the P-cores)
    DWORD_PTR pCoreMask = 0;
    int pCoreCount = 0;

    ptr = (BYTE*)buffer;
    while (ptr < end) {
        PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX info = (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)ptr;
        if (info->Relationship == RelationProcessorCore) {
            if (info->Processor.EfficiencyClass == maxEfficiencyClass) {
                pCoreCount++;
                if (info->Processor.GroupCount > 0) {
                    pCoreMask |= info->Processor.GroupMask[0].Mask;
                }
            }
        }
        ptr += info->Size;
    }

    free(buffer);

    g_pCoresCount = pCoreCount;
    if (pCoreMask != 0) {
        g_pCoreAffinityMask = pCoreMask;
    } else {
        g_pCoreAffinityMask = (g_logicalProcessors >= 64) ? ~(DWORD_PTR)0 : (((DWORD_PTR)1 << g_logicalProcessors) - 1);
    }
#elif defined(__APPLE__)
    int count = 0;
    size_t size = sizeof(count);
    if (sysctlbyname("hw.logicalcpu", &count, &size, NULL, 0) == 0 && count > 0) {
        g_logicalProcessors = count;
    } else {
        g_logicalProcessors = 4;
    }

    int phys = 0;
    size = sizeof(phys);
    if (sysctlbyname("hw.physicalcpu", &phys, &size, NULL, 0) == 0 && phys > 0) {
        g_physicalCores = phys;
    } else {
        g_physicalCores = g_logicalProcessors;
    }

    int pcores = 0;
    size = sizeof(pcores);
    if (sysctlbyname("hw.perflevel0.physicalcpu", &pcores, &size, NULL, 0) == 0 && pcores > 0) {
        g_pCoresCount = pcores;
    } else {
        g_pCoresCount = g_physicalCores;
    }
    g_pCoreAffinityMask = (g_logicalProcessors >= 64) ? ~(DWORD_PTR)0 : (((DWORD_PTR)1 << g_logicalProcessors) - 1);
#else
    // Linux
    g_logicalProcessors = (int)sysconf(_SC_NPROCESSORS_ONLN);
    if (g_logicalProcessors <= 0) g_logicalProcessors = 4;
    g_physicalCores = g_logicalProcessors;
    g_pCoresCount = g_physicalCores;
    g_pCoreAffinityMask = (g_logicalProcessors >= 64) ? ~(DWORD_PTR)0 : (((DWORD_PTR)1 << g_logicalProcessors) - 1);
#endif
}

// Timer resolution
static BOOL setHighPrecisionTimer(BOOL enable) {
#if defined(_WIN32)
    if (enable) {
        timeBeginPeriod(1);

        if (g_NtSetTimerResolution) {
            ULONG minRes = 0, maxRes = 0, curRes = 0;
            if (g_NtQueryTimerResolution) {
                g_NtQueryTimerResolution(&minRes, &maxRes, &curRes);
            }
            ULONG desired = (maxRes > 0 && maxRes > 5000) ? maxRes : 5000;
            ULONG newRes = 0;
            LONG status = g_NtSetTimerResolution(desired, TRUE, &newRes);
            if (status == 0) {
                g_activeTimerResolution100ns = newRes;
                g_timerLocked = TRUE;
                return TRUE;
            }
        }
        g_activeTimerResolution100ns = 10000;
        g_timerLocked = TRUE;
        return TRUE;
    } else {
        timeEndPeriod(1);
        if (g_NtSetTimerResolution && g_timerLocked) {
            ULONG curRes = 0;
            g_NtSetTimerResolution(5000, FALSE, &curRes);
            g_activeTimerResolution100ns = curRes;
        }
        g_timerLocked = FALSE;
        return TRUE;
    }
#else
    (void)enable;
    g_activeTimerResolution100ns = 10000; // Sub-millisecond on POSIX
    g_timerLocked = TRUE;
    return TRUE;
#endif
}

// Windows 11 power throttling / EcoQoS exemption
static BOOL disablePowerThrottling(void) {
#if defined(_WIN32)
    if (g_SetProcessInformation) {
        PROCESS_POWER_THROTTLING_STATE state = {0};
        state.Version = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
        state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION;
        state.StateMask = 0; // Turn off EcoQoS throttling

        return g_SetProcessInformation(
            GetCurrentProcess(),
            ProcessPowerThrottling,
            &state,
            sizeof(state)
        );
    }
    return FALSE;
#else
    return TRUE;
#endif
}

// MMCSS profile
#if defined(_WIN32)
static BOOL setMMCSSProfile(const wchar_t* profile) {
    DWORD taskIndex = 0;
    HANDLE hTask = AvSetMmThreadCharacteristicsW(profile ? profile : L"Games", &taskIndex);
    if (hTask != NULL) {
        g_mmcssTaskHandle = hTask;
        return TRUE;
    }
    return FALSE;
}
#else
static BOOL setMMCSSProfile(const char* profile) {
    (void)profile;
    setpriority(PRIO_PROCESS, 0, -5);
    return TRUE;
}
#endif

// Process Priority Governor
static BOOL setProcessPriority(int level) {
#if defined(_WIN32)
    DWORD pClass = ABOVE_NORMAL_PRIORITY_CLASS;
    if (level == 2) {
        pClass = HIGH_PRIORITY_CLASS;
    } else if (level == 0) {
        pClass = NORMAL_PRIORITY_CLASS;
    }
    return SetPriorityClass(GetCurrentProcess(), pClass);
#else
    int niceVal = (level >= 2) ? -10 : ((level == 1) ? -5 : 0);
    setpriority(PRIO_PROCESS, 0, niceVal);
    return TRUE;
#endif
}

// P-Core Affinity Binding for Current Thread
static BOOL bindCurrentThreadToPCores(void) {
#if defined(_WIN32)
    if (g_pCoreAffinityMask != 0) {
        DWORD_PTR prev = SetThreadAffinityMask(GetCurrentThread(), g_pCoreAffinityMask);
        return (prev != 0);
    }
    return FALSE;
#elif defined(__linux__)
    if (g_pCoresCount > 0) {
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        for (int i = 0; i < g_pCoresCount && i < CPU_SETSIZE; i++) {
            CPU_SET(i, &cpuset);
        }
        return (pthread_setaffinity_np(pthread_self(), sizeof(cpu_set_t), &cpuset) == 0);
    }
    return FALSE;
#else
    return FALSE;
#endif
}

// Full optimization for the calling thread
#if defined(_WIN32)
static BOOL optimizeCallingThread(int priorityLevel, BOOL bindPCores, const wchar_t* mmcssProfile) {
    HANDLE hThread = GetCurrentThread();

    int p = THREAD_PRIORITY_NORMAL;
    if (priorityLevel >= 3) {
        p = THREAD_PRIORITY_TIME_CRITICAL;
    } else if (priorityLevel == 2) {
        p = THREAD_PRIORITY_HIGHEST;
    } else if (priorityLevel == 1) {
        p = THREAD_PRIORITY_ABOVE_NORMAL;
    }
    SetThreadPriority(hThread, p);

    if (bindPCores && g_pCoreAffinityMask != 0) {
        SetThreadAffinityMask(hThread, g_pCoreAffinityMask);
    }

    if (mmcssProfile != NULL && mmcssProfile[0] != L'\0') {
        DWORD taskIndex = 0;
        AvSetMmThreadCharacteristicsW(mmcssProfile, &taskIndex);
    }

    return TRUE;
}
#else
static BOOL optimizeCallingThread(int priorityLevel, BOOL bindPCores, const char* profile) {
    (void)profile;
    int niceVal = (priorityLevel >= 2) ? -10 : ((priorityLevel == 1) ? -5 : 0);
    setpriority(PRIO_PROCESS, 0, niceVal);
#if defined(__linux__)
    if (bindPCores && g_pCoresCount > 0) {
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        for (int i = 0; i < g_pCoresCount && i < CPU_SETSIZE; i++) {
            CPU_SET(i, &cpuset);
        }
        pthread_setaffinity_np(pthread_self(), sizeof(cpu_set_t), &cpuset);
    }
#else
    (void)bindPCores;
#endif
    return TRUE;
}
#endif

// Working set locking
static BOOL lockProcessWorkingSet(void) {
#if defined(_WIN32)
    SetPriorityClass(GetCurrentProcess(), HIGH_PRIORITY_CLASS);

    MEMORYSTATUSEX memStatus;
    memStatus.dwLength = sizeof(memStatus);
    SIZE_T minSize = (SIZE_T)1024 * 1024 * 1024; // Default 1 GB
    SIZE_T maxSize = (SIZE_T)8 * 1024 * 1024 * 1024; // Default 8 GB

    if (GlobalMemoryStatusEx(&memStatus)) {
        SIZE_T total = (SIZE_T)memStatus.ullTotalPhys;
        minSize = (total >= (SIZE_T)8 * 1024 * 1024 * 1024) ? (SIZE_T)2 * 1024 * 1024 * 1024 : (SIZE_T)512 * 1024 * 1024;
        maxSize = (total > (SIZE_T)4 * 1024 * 1024 * 1024) ? (total * 3 / 4) : total;
    }

    // Soft working set limits (flag 0), so the memory manager never suspends threads
    // on a hard quota.
    return SetProcessWorkingSetSizeEx(GetCurrentProcess(), minSize, maxSize, 0);
#elif defined(MCL_CURRENT)
    return (mlockall(MCL_CURRENT) == 0);
#else
    return TRUE;
#endif
}

#if (defined(__x86_64__) || defined(_M_X64)) && (defined(__GNUC__) || defined(__clang__))
#pragma GCC push_options
#pragma GCC target("avx2")
#endif

// AVX2 2D distance, 8 floats per instruction
static int batchCalculateDistancesAVX2(const float* coords, int count, float ox, float oy, float* outDistances) {
    if (!coords || !outDistances || count <= 0) return 0;

    int i = 0;

#if defined(__x86_64__) || defined(_M_X64)
    if (g_avx2Supported && count >= 8) {
        __m256 vOx = _mm256_set1_ps(ox);
        __m256 vOy = _mm256_set1_ps(oy);
        const __m256i permIdx = _mm256_setr_epi32(0, 1, 4, 5, 2, 3, 6, 7);

        for (; i <= count - 8; i += 8) {
            __m256 c0 = _mm256_loadu_ps(&coords[(i + 0) * 2]);
            __m256 c1 = _mm256_loadu_ps(&coords[(i + 4) * 2]);

            __m256 shuf0 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(2, 0, 2, 0));
            __m256 shuf1 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(3, 1, 3, 1));
            __m256 xs = _mm256_permutevar8x32_ps(shuf0, permIdx);
            __m256 ys = _mm256_permutevar8x32_ps(shuf1, permIdx);

            __m256 dx = _mm256_sub_ps(xs, vOx);
            __m256 dy = _mm256_sub_ps(ys, vOy);
            __m256 distSq = _mm256_add_ps(_mm256_mul_ps(dx, dx), _mm256_mul_ps(dy, dy));
            __m256 dist = _mm256_sqrt_ps(distSq);

            _mm256_storeu_ps(&outDistances[i], dist);
        }
    }
#endif

    // Scalar fallback for remainder or non-AVX2 / ARM64
    for (; i < count; i++) {
        float dx = coords[i * 2] - ox;
        float dy = coords[i * 2 + 1] - oy;
        outDistances[i] = sqrtf(dx * dx + dy * dy);
    }

    return count;
}

// AVX2 2D squared distance, 8 floats per instruction
static int batchCalculateDistancesSqAVX2(const float* coords, int count, float ox, float oy, float* outDistSq) {
    if (!coords || !outDistSq || count <= 0) return 0;

    int i = 0;

#if defined(__x86_64__) || defined(_M_X64)
    if (g_avx2Supported && count >= 8) {
        __m256 vOx = _mm256_set1_ps(ox);
        __m256 vOy = _mm256_set1_ps(oy);
        const __m256i permIdx = _mm256_setr_epi32(0, 1, 4, 5, 2, 3, 6, 7);

        for (; i <= count - 8; i += 8) {
            __m256 c0 = _mm256_loadu_ps(&coords[(i + 0) * 2]);
            __m256 c1 = _mm256_loadu_ps(&coords[(i + 4) * 2]);

            __m256 shuf0 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(2, 0, 2, 0));
            __m256 shuf1 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(3, 1, 3, 1));
            __m256 xs = _mm256_permutevar8x32_ps(shuf0, permIdx);
            __m256 ys = _mm256_permutevar8x32_ps(shuf1, permIdx);

            __m256 dx = _mm256_sub_ps(xs, vOx);
            __m256 dy = _mm256_sub_ps(ys, vOy);
            __m256 distSq = _mm256_add_ps(_mm256_mul_ps(dx, dx), _mm256_mul_ps(dy, dy));

            _mm256_storeu_ps(&outDistSq[i], distSq);
        }
    }
#endif

    for (; i < count; i++) {
        float dx = coords[i * 2] - ox;
        float dy = coords[i * 2 + 1] - oy;
        outDistSq[i] = dx * dx + dy * dy;
    }

    return count;
}

// AVX2 radial proximity test
static int batchCullRadialAVX2(const float* coords, int count, float ox, float oy, float maxRadiusSq, unsigned char* outMask) {
    if (!coords || !outMask || count <= 0) return 0;

    int insideCount = 0;
    int i = 0;

#if defined(__x86_64__) || defined(_M_X64)
    if (g_avx2Supported && count >= 8) {
        __m256 vOx = _mm256_set1_ps(ox);
        __m256 vOy = _mm256_set1_ps(oy);
        __m256 vMaxRadSq = _mm256_set1_ps(maxRadiusSq);
        const __m256i permIdx = _mm256_setr_epi32(0, 1, 4, 5, 2, 3, 6, 7);

        for (; i <= count - 8; i += 8) {
            __m256 c0 = _mm256_loadu_ps(&coords[(i + 0) * 2]);
            __m256 c1 = _mm256_loadu_ps(&coords[(i + 4) * 2]);

            __m256 shuf0 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(2, 0, 2, 0));
            __m256 shuf1 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(3, 1, 3, 1));
            __m256 xs = _mm256_permutevar8x32_ps(shuf0, permIdx);
            __m256 ys = _mm256_permutevar8x32_ps(shuf1, permIdx);

            __m256 dx = _mm256_sub_ps(xs, vOx);
            __m256 dy = _mm256_sub_ps(ys, vOy);
            __m256 distSq = _mm256_add_ps(_mm256_mul_ps(dx, dx), _mm256_mul_ps(dy, dy));

            __m256 cmp = _mm256_cmp_ps(distSq, vMaxRadSq, _CMP_LE_OQ);
            int mask = _mm256_movemask_ps(cmp);
            for (int b = 0; b < 8; b++) {
                unsigned char inRad = (unsigned char)((mask >> b) & 1);
                outMask[i + b] = inRad;
                insideCount += inRad;
            }
        }
    }
#endif

    for (; i < count; i++) {
        float dx = coords[i * 2] - ox;
        float dy = coords[i * 2 + 1] - oy;
        float distSq = dx * dx + dy * dy;
        unsigned char inRad = (distSq <= maxRadiusSq) ? 1 : 0;
        outMask[i] = inRad;
        insideCount += inRad;
    }

    return insideCount;
}

// AVX2 2D AABB / viewport test
static int batchCullAABBAVX2(const float* coords, int count, float minX, float minY, float maxX, float maxY, unsigned char* outMask) {
    if (!coords || !outMask || count <= 0) return 0;

    int insideCount = 0;
    int i = 0;

#if defined(__x86_64__) || defined(_M_X64)
    if (g_avx2Supported && count >= 8) {
        __m256 vMinX = _mm256_set1_ps(minX);
        __m256 vMinY = _mm256_set1_ps(minY);
        __m256 vMaxX = _mm256_set1_ps(maxX);
        __m256 vMaxY = _mm256_set1_ps(maxY);
        const __m256i permIdx = _mm256_setr_epi32(0, 1, 4, 5, 2, 3, 6, 7);

        for (; i <= count - 8; i += 8) {
            __m256 c0 = _mm256_loadu_ps(&coords[(i + 0) * 2]);
            __m256 c1 = _mm256_loadu_ps(&coords[(i + 4) * 2]);

            __m256 shuf0 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(2, 0, 2, 0));
            __m256 shuf1 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(3, 1, 3, 1));
            __m256 xs = _mm256_permutevar8x32_ps(shuf0, permIdx);
            __m256 ys = _mm256_permutevar8x32_ps(shuf1, permIdx);

            __m256 mX1 = _mm256_cmp_ps(xs, vMinX, _CMP_GE_OQ);
            __m256 mX2 = _mm256_cmp_ps(xs, vMaxX, _CMP_LE_OQ);
            __m256 mY1 = _mm256_cmp_ps(ys, vMinY, _CMP_GE_OQ);
            __m256 mY2 = _mm256_cmp_ps(ys, vMaxY, _CMP_LE_OQ);

            __m256 inAABB = _mm256_and_ps(_mm256_and_ps(mX1, mX2), _mm256_and_ps(mY1, mY2));
            int mask = _mm256_movemask_ps(inAABB);
            for (int b = 0; b < 8; b++) {
                unsigned char inBox = (unsigned char)((mask >> b) & 1);
                outMask[i + b] = inBox;
                insideCount += inBox;
            }
        }
    }
#endif

    for (; i < count; i++) {
        float x = coords[i * 2];
        float y = coords[i * 2 + 1];
        unsigned char inBox = (x >= minX && x <= maxX && y >= minY && y <= maxY) ? 1 : 0;
        outMask[i] = inBox;
        insideCount += inBox;
    }

    return insideCount;
}

// AVX2 distance tier classification
static int batchClassifyTiersAVX2(const float* coords, int count, float ox, float oy,
                                  float t0Sq, float t1Sq, float t2Sq, unsigned char* outTiers) {
    if (!coords || !outTiers || count <= 0) return 0;

    int i = 0;

#if defined(__x86_64__) || defined(_M_X64)
    if (g_avx2Supported && count >= 8) {
        __m256 vOx = _mm256_set1_ps(ox);
        __m256 vOy = _mm256_set1_ps(oy);
        __m256 vT0 = _mm256_set1_ps(t0Sq);
        __m256 vT1 = _mm256_set1_ps(t1Sq);
        __m256 vT2 = _mm256_set1_ps(t2Sq);
        const __m256i permIdx = _mm256_setr_epi32(0, 1, 4, 5, 2, 3, 6, 7);

        for (; i <= count - 8; i += 8) {
            __m256 c0 = _mm256_loadu_ps(&coords[(i + 0) * 2]);
            __m256 c1 = _mm256_loadu_ps(&coords[(i + 4) * 2]);

            __m256 shuf0 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(2, 0, 2, 0));
            __m256 shuf1 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(3, 1, 3, 1));
            __m256 xs = _mm256_permutevar8x32_ps(shuf0, permIdx);
            __m256 ys = _mm256_permutevar8x32_ps(shuf1, permIdx);

            __m256 dx = _mm256_sub_ps(xs, vOx);
            __m256 dy = _mm256_sub_ps(ys, vOy);
            __m256 distSq = _mm256_add_ps(_mm256_mul_ps(dx, dx), _mm256_mul_ps(dy, dy));

            int m0 = _mm256_movemask_ps(_mm256_cmp_ps(distSq, vT0, _CMP_LE_OQ));
            int m1 = _mm256_movemask_ps(_mm256_cmp_ps(distSq, vT1, _CMP_LE_OQ));
            int m2 = _mm256_movemask_ps(_mm256_cmp_ps(distSq, vT2, _CMP_LE_OQ));

            for (int b = 0; b < 8; b++) {
                if ((m0 >> b) & 1) {
                    outTiers[i + b] = 0;
                } else if ((m1 >> b) & 1) {
                    outTiers[i + b] = 1;
                } else if ((m2 >> b) & 1) {
                    outTiers[i + b] = 2;
                } else {
                    outTiers[i + b] = 3;
                }
            }
        }
    }
#endif

    for (; i < count; i++) {
        float dx = coords[i * 2] - ox;
        float dy = coords[i * 2 + 1] - oy;
        float dSq = dx * dx + dy * dy;
        if (dSq <= t0Sq) outTiers[i] = 0;
        else if (dSq <= t1Sq) outTiers[i] = 1;
        else if (dSq <= t2Sq) outTiers[i] = 2;
        else outTiers[i] = 3;
    }

    return count;
}

// AVX2 field-of-view test: range, direction dot product and FOV cone for 8 entities at a time
static int batchCalculateFovAVX2(
    const float* coords, const float* headings, int count,
    float targetX, float targetY, float viewDistSq, float cosHalfFov, float closeRadiusSq,
    unsigned char* outMask) {
    if (!coords || !headings || !outMask || count <= 0) return 0;

    int visibleCount = 0;
    int i = 0;
    float cosSq = cosHalfFov * cosHalfFov;

#if defined(__x86_64__) || defined(_M_X64)
    if (g_avx2Supported && count >= 8) {
        __m256 vTargetX = _mm256_set1_ps(targetX);
        __m256 vTargetY = _mm256_set1_ps(targetY);
        __m256 vMaxDistSq = _mm256_set1_ps(viewDistSq);
        __m256 vCloseRadSq = _mm256_set1_ps(closeRadiusSq);
        __m256 vCosSq = _mm256_set1_ps(cosSq);
        __m256 vZero = _mm256_setzero_ps();
        __m256 vMinDist = _mm256_set1_ps(0.0001f);
        const __m256i permIdx = _mm256_setr_epi32(0, 1, 4, 5, 2, 3, 6, 7);

        for (; i <= count - 8; i += 8) {
            __m256 c0 = _mm256_loadu_ps(&coords[(i + 0) * 2]);
            __m256 c1 = _mm256_loadu_ps(&coords[(i + 4) * 2]);
            __m256 xs = _mm256_permutevar8x32_ps(_mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(2, 0, 2, 0)), permIdx);
            __m256 ys = _mm256_permutevar8x32_ps(_mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(3, 1, 3, 1)), permIdx);

            __m256 h0 = _mm256_loadu_ps(&headings[(i + 0) * 2]);
            __m256 h1 = _mm256_loadu_ps(&headings[(i + 4) * 2]);
            __m256 hx = _mm256_permutevar8x32_ps(_mm256_shuffle_ps(h0, h1, _MM_SHUFFLE(2, 0, 2, 0)), permIdx);
            __m256 hy = _mm256_permutevar8x32_ps(_mm256_shuffle_ps(h0, h1, _MM_SHUFFLE(3, 1, 3, 1)), permIdx);

            __m256 dx = _mm256_sub_ps(vTargetX, xs);
            __m256 dy = _mm256_sub_ps(vTargetY, ys);
            __m256 distSq = _mm256_add_ps(_mm256_mul_ps(dx, dx), _mm256_mul_ps(dy, dy));

            __m256 mClose = _mm256_cmp_ps(distSq, vCloseRadSq, _CMP_LE_OQ);
            __m256 mDist = _mm256_and_ps(_mm256_cmp_ps(distSq, vMaxDistSq, _CMP_LE_OQ),
                                         _mm256_cmp_ps(distSq, vMinDist, _CMP_GT_OQ));

            __m256 dot = _mm256_add_ps(_mm256_mul_ps(dx, hx), _mm256_mul_ps(dy, hy));
            __m256 mFront = _mm256_cmp_ps(dot, vZero, _CMP_GT_OQ);

            __m256 dotSq = _mm256_mul_ps(dot, dot);
            __m256 threshold = _mm256_mul_ps(vCosSq, distSq);
            __m256 mCone = _mm256_cmp_ps(dotSq, threshold, _CMP_GE_OQ);

            __m256 mFov = _mm256_and_ps(mDist, _mm256_and_ps(mFront, mCone));
            __m256 mFinal = _mm256_or_ps(mFov, mClose);

            int mask = _mm256_movemask_ps(mFinal);
            for (int b = 0; b < 8; b++) {
                unsigned char vis = (unsigned char)((mask >> b) & 1);
                outMask[i + b] = vis;
                visibleCount += vis;
            }
        }
    }
#endif

    for (; i < count; i++) {
        float dx = targetX - coords[i * 2];
        float dy = targetY - coords[i * 2 + 1];
        float distSq = dx * dx + dy * dy;

        if (distSq <= closeRadiusSq) {
            outMask[i] = 1;
            visibleCount++;
            continue;
        }

        if (distSq <= viewDistSq && distSq > 0.0001f) {
            float hx = headings[i * 2];
            float hy = headings[i * 2 + 1];
            float dot = dx * hx + dy * hy;
            if (dot > 0.0f && (dot * dot) >= (cosSq * distSq)) {
                outMask[i] = 1;
                visibleCount++;
                continue;
            }
        }
        outMask[i] = 0;
    }

    return visibleCount;
}

// AVX2 crowd separation: for each entity i, the accumulated repulsion away from nearby entities
static int batchCalculateRepulsionAVX2(
    const float* coords, int count, float separationRadius, float maxForce, float* outForces) {
    if (!coords || !outForces || count <= 0) return 0;

    float radSq = separationRadius * separationRadius;
    int i = 0;

#if defined(__x86_64__) || defined(_M_X64)
    if (g_avx2Supported && count >= 8) {
        __m256 vRadSq = _mm256_set1_ps(radSq);
        __m256 vMinDistSq = _mm256_set1_ps(0.0001f);
        const __m256i permIdx = _mm256_setr_epi32(0, 1, 4, 5, 2, 3, 6, 7);

        for (i = 0; i < count; i++) {
            float xi = coords[i * 2];
            float yi = coords[i * 2 + 1];
            __m256 vXi = _mm256_set1_ps(xi);
            __m256 vYi = _mm256_set1_ps(yi);
            __m256 vTotalFx = _mm256_setzero_ps();
            __m256 vTotalFy = _mm256_setzero_ps();

            int j = 0;
            for (; j <= count - 8; j += 8) {
                __m256 c0 = _mm256_loadu_ps(&coords[(j + 0) * 2]);
                __m256 c1 = _mm256_loadu_ps(&coords[(j + 4) * 2]);
                __m256 xj = _mm256_permutevar8x32_ps(_mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(2, 0, 2, 0)), permIdx);
                __m256 yj = _mm256_permutevar8x32_ps(_mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(3, 1, 3, 1)), permIdx);

                __m256 dx = _mm256_sub_ps(vXi, xj);
                __m256 dy = _mm256_sub_ps(vYi, yj);
                __m256 dSq = _mm256_add_ps(_mm256_mul_ps(dx, dx), _mm256_mul_ps(dy, dy));

                __m256 mask = _mm256_and_ps(_mm256_cmp_ps(dSq, vRadSq, _CMP_LT_OQ),
                                            _mm256_cmp_ps(dSq, vMinDistSq, _CMP_GT_OQ));

                __m256 invDistSq = _mm256_rcp_ps(dSq);
                invDistSq = _mm256_and_ps(invDistSq, mask);

                vTotalFx = _mm256_add_ps(vTotalFx, _mm256_mul_ps(dx, invDistSq));
                vTotalFy = _mm256_add_ps(vTotalFy, _mm256_mul_ps(dy, invDistSq));
            }

            float fxArr[8], fyArr[8];
            _mm256_storeu_ps(fxArr, vTotalFx);
            _mm256_storeu_ps(fyArr, vTotalFy);
            float accumFx = fxArr[0] + fxArr[1] + fxArr[2] + fxArr[3] + fxArr[4] + fxArr[5] + fxArr[6] + fxArr[7];
            float accumFy = fyArr[0] + fyArr[1] + fyArr[2] + fyArr[3] + fyArr[4] + fyArr[5] + fyArr[6] + fyArr[7];

            for (; j < count; j++) {
                if (i == j) continue;
                float dx = xi - coords[j * 2];
                float dy = yi - coords[j * 2 + 1];
                float dSq = dx * dx + dy * dy;
                if (dSq < radSq && dSq > 0.0001f) {
                    float invD = 1.0f / dSq;
                    accumFx += dx * invD;
                    accumFy += dy * invD;
                }
            }

            if (accumFx > maxForce) accumFx = maxForce;
            else if (accumFx < -maxForce) accumFx = -maxForce;
            if (accumFy > maxForce) accumFy = maxForce;
            else if (accumFy < -maxForce) accumFy = -maxForce;

            outForces[i * 2] = accumFx;
            outForces[i * 2 + 1] = accumFy;
        }
        return count;
    }
#endif

    for (i = 0; i < count; i++) {
        float xi = coords[i * 2];
        float yi = coords[i * 2 + 1];
        float accumFx = 0.0f, accumFy = 0.0f;

        for (int j = 0; j < count; j++) {
            if (i == j) continue;
            float dx = xi - coords[j * 2];
            float dy = yi - coords[j * 2 + 1];
            float dSq = dx * dx + dy * dy;
            if (dSq < radSq && dSq > 0.0001f) {
                float invD = 1.0f / dSq;
                accumFx += dx * invD;
                accumFy += dy * invD;
            }
        }

        if (accumFx > maxForce) accumFx = maxForce;
        else if (accumFx < -maxForce) accumFx = -maxForce;
        if (accumFy > maxForce) accumFy = maxForce;
        else if (accumFy < -maxForce) accumFy = -maxForce;

        outForces[i * 2] = accumFx;
        outForces[i * 2 + 1] = accumFy;
    }

    return count;
}

#if (defined(__x86_64__) || defined(_M_X64)) && (defined(__GNUC__) || defined(__clang__))
#pragma GCC pop_options
#endif

// JNI exports for com.pzoptimizer.PZONative

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_initNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;

#if defined(_WIN32)
    HMODULE hNtdll = GetModuleHandleW(L"ntdll.dll");
    if (hNtdll) {
        g_NtSetTimerResolution = (pfnNtSetTimerResolution)GetProcAddress(hNtdll, "NtSetTimerResolution");
        g_NtQueryTimerResolution = (pfnNtQueryTimerResolution)GetProcAddress(hNtdll, "NtQueryTimerResolution");
    }

    HMODULE hKernel32 = GetModuleHandleW(L"kernel32.dll");
    if (hKernel32) {
        g_SetProcessInformation = (pfnSetProcessInformation)GetProcAddress(hKernel32, "SetProcessInformation");
    }

    // GPU driver threading hints
    SetEnvironmentVariableW(L"__GL_THREADED_OPTIMIZATIONS", L"1");
    SetEnvironmentVariableW(L"__GL_YIELD", L"NOTHING");
#else
    setenv("__GL_THREADED_OPTIMIZATIONS", "1", 1);
    setenv("__GL_YIELD", "NOTHING", 1);
#endif

    g_avx2Supported = checkCpuAvx2Support();
    detectCpuTopology();

    return JNI_TRUE;
}

#define PZO_NATIVE_VERSION "1.5"

JNIEXPORT jstring JNICALL Java_com_pzoptimizer_PZONative_getNativeVersion(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, PZO_NATIVE_VERSION);
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_optimizeCallingThread(
    JNIEnv *env, jclass cls, jint priorityLevel, jboolean bindPCores, jstring profileStr) {
    (void)cls;
#if defined(_WIN32)
    const jchar* chars = profileStr ? (*env)->GetStringChars(env, profileStr, NULL) : NULL;
    BOOL res = optimizeCallingThread((int)priorityLevel, bindPCores == JNI_TRUE, (const wchar_t*)chars);
    if (chars) {
        (*env)->ReleaseStringChars(env, profileStr, chars);
    }
    return res ? JNI_TRUE : JNI_FALSE;
#else
    const char* chars = profileStr ? (*env)->GetStringUTFChars(env, profileStr, NULL) : NULL;
    BOOL res = optimizeCallingThread((int)priorityLevel, bindPCores == JNI_TRUE, chars);
    if (chars) {
        (*env)->ReleaseStringUTFChars(env, profileStr, chars);
    }
    return res ? JNI_TRUE : JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_lockProcessWorkingSet(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return lockProcessWorkingSet() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_setHighPrecisionTimer(JNIEnv *env, jclass cls, jboolean enable) {
    (void)env; (void)cls;
    return setHighPrecisionTimer(enable == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_disablePowerThrottling(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return disablePowerThrottling() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_setMMCSSProfile(JNIEnv *env, jclass cls, jstring profileStr) {
    (void)cls;
#if defined(_WIN32)
    const jchar* chars = profileStr ? (*env)->GetStringChars(env, profileStr, NULL) : NULL;
    BOOL res = setMMCSSProfile((const wchar_t*)chars);
    if (chars) {
        (*env)->ReleaseStringChars(env, profileStr, chars);
    }
    return res ? JNI_TRUE : JNI_FALSE;
#else
    const char* chars = profileStr ? (*env)->GetStringUTFChars(env, profileStr, NULL) : NULL;
    BOOL res = setMMCSSProfile(chars);
    if (chars) {
        (*env)->ReleaseStringUTFChars(env, profileStr, chars);
    }
    return res ? JNI_TRUE : JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_setProcessPriority(JNIEnv *env, jclass cls, jint level) {
    (void)env; (void)cls;
    return setProcessPriority((int)level) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_pzoptimizer_PZONative_getPerformanceCoreMask(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)g_pCoreAffinityMask;
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_bindThreadToPerformanceCores(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return bindCurrentThreadToPCores() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_getPhysicalCores(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)g_physicalCores;
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_getPerformanceCores(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)g_pCoresCount;
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_getLogicalProcessors(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)g_logicalProcessors;
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_getTimerResolution100ns(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)g_activeTimerResolution100ns;
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_isAVX2Supported(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return g_avx2Supported ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_batchCalculateDistancesAVX2(
    JNIEnv *env, jclass cls, jobject inDirectBuf, jint count, jfloat ox, jfloat oy, jobject outDirectBuf) {
    (void)cls;
    if (!inDirectBuf || !outDirectBuf || count <= 0) return 0;
    float* inCoords = (float*)(*env)->GetDirectBufferAddress(env, inDirectBuf);
    float* outDist = (float*)(*env)->GetDirectBufferAddress(env, outDirectBuf);
    if (!inCoords || !outDist) return 0;
    return (jint)batchCalculateDistancesAVX2(inCoords, (int)count, (float)ox, (float)oy, outDist);
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_batchCalculateDistancesSqAVX2(
    JNIEnv *env, jclass cls, jobject inDirectBuf, jint count, jfloat ox, jfloat oy, jobject outDirectBuf) {
    (void)cls;
    if (!inDirectBuf || !outDirectBuf || count <= 0) return 0;
    float* inCoords = (float*)(*env)->GetDirectBufferAddress(env, inDirectBuf);
    float* outDistSq = (float*)(*env)->GetDirectBufferAddress(env, outDirectBuf);
    if (!inCoords || !outDistSq) return 0;
    return (jint)batchCalculateDistancesSqAVX2(inCoords, (int)count, (float)ox, (float)oy, outDistSq);
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_batchCullRadialAVX2(
    JNIEnv *env, jclass cls, jobject inDirectBuf, jint count, jfloat ox, jfloat oy, jfloat maxRadiusSq, jobject outMaskBuf) {
    (void)cls;
    if (!inDirectBuf || !outMaskBuf || count <= 0) return 0;
    float* inCoords = (float*)(*env)->GetDirectBufferAddress(env, inDirectBuf);
    unsigned char* outMask = (unsigned char*)(*env)->GetDirectBufferAddress(env, outMaskBuf);
    if (!inCoords || !outMask) return 0;
    return (jint)batchCullRadialAVX2(inCoords, (int)count, (float)ox, (float)oy, (float)maxRadiusSq, outMask);
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_batchCullAABBAVX2(
    JNIEnv *env, jclass cls, jobject inDirectBuf, jint count, jfloat minX, jfloat minY, jfloat maxX, jfloat maxY, jobject outMaskBuf) {
    (void)cls;
    if (!inDirectBuf || !outMaskBuf || count <= 0) return 0;
    float* inCoords = (float*)(*env)->GetDirectBufferAddress(env, inDirectBuf);
    unsigned char* outMask = (unsigned char*)(*env)->GetDirectBufferAddress(env, outMaskBuf);
    if (!inCoords || !outMask) return 0;
    return (jint)batchCullAABBAVX2(inCoords, (int)count, (float)minX, (float)minY, (float)maxX, (float)maxY, outMask);
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_batchClassifyTiersAVX2(
    JNIEnv *env, jclass cls, jobject inDirectBuf, jint count, jfloat ox, jfloat oy,
    jfloat t0Sq, jfloat t1Sq, jfloat t2Sq, jobject outTiersBuf) {
    (void)cls;
    if (!inDirectBuf || !outTiersBuf || count <= 0) return 0;
    float* inCoords = (float*)(*env)->GetDirectBufferAddress(env, inDirectBuf);
    unsigned char* outTiers = (unsigned char*)(*env)->GetDirectBufferAddress(env, outTiersBuf);
    if (!inCoords || !outTiers) return 0;
    return (jint)batchClassifyTiersAVX2(inCoords, (int)count, (float)ox, (float)oy, (float)t0Sq, (float)t1Sq, (float)t2Sq, outTiers);
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_batchCalculateFovAVX2(
    JNIEnv *env, jclass cls, jobject inCoordsDirectBuf, jobject inHeadingsDirectBuf, jint count,
    jfloat targetX, jfloat targetY, jfloat viewDistSq, jfloat cosHalfFov, jfloat closeRadiusSq,
    jobject outMaskDirectBuf) {
    (void)cls;
    if (!inCoordsDirectBuf || !inHeadingsDirectBuf || !outMaskDirectBuf || count <= 0) return 0;
    float* inCoords = (float*)(*env)->GetDirectBufferAddress(env, inCoordsDirectBuf);
    float* inHeadings = (float*)(*env)->GetDirectBufferAddress(env, inHeadingsDirectBuf);
    unsigned char* outMask = (unsigned char*)(*env)->GetDirectBufferAddress(env, outMaskDirectBuf);
    if (!inCoords || !inHeadings || !outMask) return 0;
    return (jint)batchCalculateFovAVX2(
        inCoords, inHeadings, (int)count,
        (float)targetX, (float)targetY, (float)viewDistSq, (float)cosHalfFov, (float)closeRadiusSq,
        outMask
    );
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_batchCalculateRepulsionAVX2(
    JNIEnv *env, jclass cls, jobject inCoordsDirectBuf, jint count, jfloat separationRadius, jfloat maxForce,
    jobject outForcesDirectBuf) {
    (void)cls;
    if (!inCoordsDirectBuf || !outForcesDirectBuf || count <= 0) return 0;
    float* inCoords = (float*)(*env)->GetDirectBufferAddress(env, inCoordsDirectBuf);
    float* outForces = (float*)(*env)->GetDirectBufferAddress(env, outForcesDirectBuf);
    if (!inCoords || !outForces) return 0;
    return (jint)batchCalculateRepulsionAVX2(
        inCoords, (int)count, (float)separationRadius, (float)maxForce, outForces
    );
}

// Chunk decompression and Win32/POSIX file streaming

static jint decompressBuffer(const unsigned char *src, size_t srcLen, unsigned char *dst, size_t dstCap) {
    if (!src || !dst || srcLen == 0 || dstCap == 0) return -1;

    int flags = TINFL_FLAG_USING_NON_WRAPPING_OUTPUT_BUF;
    if (srcLen >= 2) {
        unsigned int hdr = ((unsigned int)src[0] << 8) | (unsigned int)src[1];
        if ((src[0] & 0x0F) == 8 && ((src[0] >> 4) <= 7) && (hdr % 31 == 0)) {
            flags |= TINFL_FLAG_PARSE_ZLIB_HEADER;
        }
    }

    size_t decompressedBytes = tinfl_decompress_mem_to_mem(dst, dstCap, src, srcLen, flags);
    if (decompressedBytes == TINFL_DECOMPRESS_MEM_TO_MEM_FAILED && (flags & TINFL_FLAG_PARSE_ZLIB_HEADER)) {
        decompressedBytes = tinfl_decompress_mem_to_mem(dst, dstCap, src, srcLen, TINFL_FLAG_USING_NON_WRAPPING_OUTPUT_BUF);
    }
    if (decompressedBytes == TINFL_DECOMPRESS_MEM_TO_MEM_FAILED) {
        return -1;
    }
    return (jint)decompressedBytes;
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_decompressDirect(
    JNIEnv *env, jclass cls,
    jobject srcBuf, jint srcPos, jint srcLen,
    jobject dstBuf, jint dstPos, jint dstCap) {
    (void)cls;
    if (!srcBuf || !dstBuf || srcLen <= 0 || dstCap <= 0) return -1;
    unsigned char *src = (unsigned char *)(*env)->GetDirectBufferAddress(env, srcBuf);
    unsigned char *dst = (unsigned char *)(*env)->GetDirectBufferAddress(env, dstBuf);
    if (!src || !dst) return -1;
    return decompressBuffer(src + srcPos, (size_t)srcLen, dst + dstPos, (size_t)dstCap);
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_decompressBytes(
    JNIEnv *env, jclass cls,
    jbyteArray srcArray, jint srcOff, jint srcLen,
    jbyteArray dstArray, jint dstOff, jint dstCap) {
    (void)cls;
    if (!srcArray || !dstArray || srcLen <= 0 || dstCap <= 0) return -1;

    jbyte *srcPtr = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, srcArray, NULL);
    if (!srcPtr) return -1;

    jbyte *dstPtr = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, dstArray, NULL);
    if (!dstPtr) {
        (*env)->ReleasePrimitiveArrayCritical(env, srcArray, srcPtr, JNI_ABORT);
        return -1;
    }

    jint res = decompressBuffer(
        (const unsigned char *)(srcPtr + srcOff), (size_t)srcLen,
        (unsigned char *)(dstPtr + dstOff), (size_t)dstCap
    );

    (*env)->ReleasePrimitiveArrayCritical(env, dstArray, dstPtr, (res > 0) ? 0 : JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, srcArray, srcPtr, JNI_ABORT);
    return res;
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_readChunkFileNative(
    JNIEnv *env, jclass cls, jstring filePath, jbyteArray dstArray, jint maxCap) {
    (void)cls;
    if (!filePath || !dstArray || maxCap <= 0) return -1;

#if defined(_WIN32)
    const jchar *wPath = (*env)->GetStringChars(env, filePath, NULL);
    if (!wPath) return -1;

    HANDLE hFile = CreateFileW(
        (LPCWSTR)wPath,
        GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN,
        NULL
    );

    (*env)->ReleaseStringChars(env, filePath, wPath);
    if (hFile == INVALID_HANDLE_VALUE) return -1;

    LARGE_INTEGER fileSize;
    if (!GetFileSizeEx(hFile, &fileSize) || fileSize.QuadPart <= 0 || fileSize.QuadPart > (LONGLONG)maxCap) {
        CloseHandle(hFile);
        return -1;
    }

    jbyte *dstPtr = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, dstArray, NULL);
    if (!dstPtr) {
        CloseHandle(hFile);
        return -1;
    }

    DWORD bytesRead = 0;
    BOOL ok = ReadFile(hFile, dstPtr, (DWORD)fileSize.QuadPart, &bytesRead, NULL);
    CloseHandle(hFile);

    (*env)->ReleasePrimitiveArrayCritical(env, dstArray, dstPtr, ok ? 0 : JNI_ABORT);
    return ok ? (jint)bytesRead : -1;
#else
    const char *uPath = (*env)->GetStringUTFChars(env, filePath, NULL);
    if (!uPath) return -1;

    int fd = open(uPath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, filePath, uPath);
    if (fd < 0) return -1;

    struct stat st;
    if (fstat(fd, &st) < 0 || st.st_size <= 0 || st.st_size > (off_t)maxCap) {
        close(fd);
        return -1;
    }

#if defined(POSIX_FADV_SEQUENTIAL)
    posix_fadvise(fd, 0, st.st_size, POSIX_FADV_SEQUENTIAL);
#endif

    jbyte *dstPtr = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, dstArray, NULL);
    if (!dstPtr) {
        close(fd);
        return -1;
    }

    ssize_t bytesRead = read(fd, dstPtr, (size_t)st.st_size);
    close(fd);

    (*env)->ReleasePrimitiveArrayCritical(env, dstArray, dstPtr, (bytesRead > 0) ? 0 : JNI_ABORT);
    return (bytesRead > 0) ? (jint)bytesRead : -1;
#endif
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_readAndDecompressChunkDirect(
    JNIEnv *env, jclass cls, jstring filePath, jobject dstDirectBuf, jint dstCap) {
    (void)cls;
    if (!filePath || !dstDirectBuf || dstCap <= 0) return -1;

    unsigned char *dst = (unsigned char *)(*env)->GetDirectBufferAddress(env, dstDirectBuf);
    if (!dst) return -1;

#if defined(_WIN32)
    const jchar *wPath = (*env)->GetStringChars(env, filePath, NULL);
    if (!wPath) return -1;

    HANDLE hFile = CreateFileW(
        (LPCWSTR)wPath,
        GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN,
        NULL
    );
    (*env)->ReleaseStringChars(env, filePath, wPath);
    if (hFile == INVALID_HANDLE_VALUE) return -1;

    LARGE_INTEGER fileSize;
    if (!GetFileSizeEx(hFile, &fileSize) || fileSize.QuadPart <= 0 || fileSize.QuadPart > 4194304) {
        CloseHandle(hFile);
        return -1;
    }

    DWORD compLen = (DWORD)fileSize.QuadPart;
    unsigned char stackBuf[131072];
    unsigned char *compBuf = stackBuf;
    BOOL heapAllocated = FALSE;
    if (compLen > sizeof(stackBuf)) {
        compBuf = (unsigned char *)malloc(compLen);
        if (!compBuf) {
            CloseHandle(hFile);
            return -1;
        }
        heapAllocated = TRUE;
    }

    DWORD bytesRead = 0;
    BOOL ok = ReadFile(hFile, compBuf, compLen, &bytesRead, NULL);
    CloseHandle(hFile);

    if (!ok || bytesRead == 0) {
        if (heapAllocated) free(compBuf);
        return -1;
    }

    jint decompBytes = decompressBuffer(compBuf, (size_t)bytesRead, dst, (size_t)dstCap);
    if (heapAllocated) free(compBuf);

    return decompBytes;
#else
    const char *uPath = (*env)->GetStringUTFChars(env, filePath, NULL);
    if (!uPath) return -1;

    int fd = open(uPath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, filePath, uPath);
    if (fd < 0) return -1;

    struct stat st;
    if (fstat(fd, &st) < 0 || st.st_size <= 0 || st.st_size > 4194304) {
        close(fd);
        return -1;
    }

#if defined(POSIX_FADV_SEQUENTIAL)
    posix_fadvise(fd, 0, st.st_size, POSIX_FADV_SEQUENTIAL);
#endif

    size_t compLen = (size_t)st.st_size;
    unsigned char stackBuf[131072];
    unsigned char *compBuf = stackBuf;
    BOOL heapAllocated = FALSE;
    if (compLen > sizeof(stackBuf)) {
        compBuf = (unsigned char *)malloc(compLen);
        if (!compBuf) {
            close(fd);
            return -1;
        }
        heapAllocated = TRUE;
    }

    ssize_t bytesRead = read(fd, compBuf, compLen);
    close(fd);

    if (bytesRead <= 0) {
        if (heapAllocated) free(compBuf);
        return -1;
    }

    jint decompBytes = decompressBuffer(compBuf, (size_t)bytesRead, dst, (size_t)dstCap);
    if (heapAllocated) free(compBuf);

    return decompBytes;
#endif
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_prewarmFileNative(
    JNIEnv *env, jclass cls, jstring filePath) {
    (void)cls;
    if (!filePath) return JNI_FALSE;

#if defined(_WIN32)
    const jchar *wPath = (*env)->GetStringChars(env, filePath, NULL);
    if (!wPath) return JNI_FALSE;

    HANDLE hFile = CreateFileW(
        (LPCWSTR)wPath,
        GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN,
        NULL
    );

    (*env)->ReleaseStringChars(env, filePath, wPath);
    if (hFile == INVALID_HANDLE_VALUE) return JNI_FALSE;

    char scratch[65536];
    DWORD bytesRead = 0;
    ReadFile(hFile, scratch, sizeof(scratch), &bytesRead, NULL);
    CloseHandle(hFile);

    return JNI_TRUE;
#else
    const char *uPath = (*env)->GetStringUTFChars(env, filePath, NULL);
    if (!uPath) return JNI_FALSE;

    int fd = open(uPath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, filePath, uPath);
    if (fd < 0) return JNI_FALSE;

#if defined(POSIX_FADV_WILLNEED)
    posix_fadvise(fd, 0, 0, POSIX_FADV_WILLNEED);
#endif
    char scratch[65536];
    read(fd, scratch, sizeof(scratch));
    close(fd);

    return JNI_TRUE;
#endif
}

JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_prewarmFilesNative(
    JNIEnv *env, jclass cls, jobjectArray filePaths) {
    (void)cls;
    if (!filePaths) return 0;
    jsize len = (*env)->GetArrayLength(env, filePaths);
    if (len <= 0) return 0;

    int successCount = 0;
    char scratch[65536];
    for (jsize i = 0; i < len; i++) {
        jstring filePath = (jstring)(*env)->GetObjectArrayElement(env, filePaths, i);
        if (!filePath) continue;
#if defined(_WIN32)
        const jchar *wPath = (*env)->GetStringChars(env, filePath, NULL);
        if (wPath) {
            HANDLE hFile = CreateFileW(
                (LPCWSTR)wPath,
                GENERIC_READ,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                NULL,
                OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN,
                NULL
            );
            if (hFile != INVALID_HANDLE_VALUE) {
                DWORD bytesRead = 0;
                ReadFile(hFile, scratch, sizeof(scratch), &bytesRead, NULL);
                CloseHandle(hFile);
                successCount++;
            }
            (*env)->ReleaseStringChars(env, filePath, wPath);
        }
#else
        const char *uPath = (*env)->GetStringUTFChars(env, filePath, NULL);
        if (uPath) {
            int fd = open(uPath, O_RDONLY);
            if (fd >= 0) {
#if defined(POSIX_FADV_WILLNEED)
                posix_fadvise(fd, 0, 0, POSIX_FADV_WILLNEED);
#endif
                read(fd, scratch, sizeof(scratch));
                close(fd);
                successCount++;
            }
            (*env)->ReleaseStringUTFChars(env, filePath, uPath);
        }
#endif
        (*env)->DeleteLocalRef(env, filePath);
    }
    return successCount;
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_isDriveSSDNative(JNIEnv *env, jclass cls, jstring pathStr) {
    (void)cls;
    if (!pathStr) return JNI_TRUE;

#if defined(_WIN32)
    const jchar *wPath = (*env)->GetStringChars(env, pathStr, NULL);
    if (!wPath) return JNI_TRUE;

    wchar_t volumePath[MAX_PATH];
    if (!GetVolumePathNameW((LPCWSTR)wPath, volumePath, MAX_PATH)) {
        (*env)->ReleaseStringChars(env, pathStr, wPath);
        return JNI_TRUE;
    }
    (*env)->ReleaseStringChars(env, pathStr, wPath);

    size_t len = wcslen(volumePath);
    if (len > 0 && volumePath[len - 1] == L'\\') {
        volumePath[len - 1] = L'\0';
    }

    wchar_t devicePath[MAX_PATH];
    swprintf_s(devicePath, MAX_PATH, L"\\\\.\\%s", volumePath);

    HANDLE hDevice = CreateFileW(
        devicePath, 0, FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL, OPEN_EXISTING, 0, NULL
    );
    if (hDevice == INVALID_HANDLE_VALUE) return JNI_TRUE;

    STORAGE_PROPERTY_QUERY query;
    ZeroMemory(&query, sizeof(query));
    query.PropertyId = StorageDeviceSeekPenaltyProperty;
    query.QueryType = PropertyStandardQuery;

    DEVICE_SEEK_PENALTY_DESCRIPTOR result;
    ZeroMemory(&result, sizeof(result));
    DWORD bytesReturned = 0;

    BOOL isSSD = TRUE;
    if (DeviceIoControl(hDevice, IOCTL_STORAGE_QUERY_PROPERTY,
                        &query, sizeof(query),
                        &result, sizeof(result),
                        &bytesReturned, NULL)) {
        isSSD = !result.IncursSeekPenalty;
    }

    CloseHandle(hDevice);
    return isSSD ? JNI_TRUE : JNI_FALSE;
#else
    (void)env; (void)pathStr;
    return JNI_TRUE;
#endif
}

/*
 * Replacement for the A* search in PZPathFind64.dll (B42, Windows build only; RVA 0xc6d0).
 * Vanilla finds a node it wants to decrease-key by scanning the whole open heap, which makes
 * every improved neighbour O(open list). This port keeps the exact heap algorithm, node
 * flags, cost rounding and virtual-call order, and only adds a node -> heap index table so
 * the decrease-key lookup is O(1). It is installed only when every byte of the original
 * search, heap and free helpers matches, so any game update simply leaves it off.
 */
#ifdef _WIN32

#define PZPF_ASTAR_RVA 0xc6d0
#define PZPF_HEAP_RVA  0x25180
#define PZPF_FREE_RVA  0x3c500
#define PZPF_PATCH_LEN 14

static const unsigned char PZPF_ASTAR_SIG[708] = {
    0x48, 0x8b, 0xc4, 0x48, 0x89, 0x58, 0x08, 0x48, 0x89, 0x68, 0x10, 0x48, 0x89, 0x70, 0x18, 0x57,
    0x41, 0x54, 0x41, 0x55, 0x41, 0x56, 0x41, 0x57, 0x48, 0x83, 0xec, 0x70, 0x0f, 0x29, 0x70, 0xc8,
    0x0f, 0x29, 0x78, 0xb8, 0x44, 0x0f, 0x29, 0x40, 0xa8, 0x44, 0x0f, 0x29, 0x48, 0x98, 0x4d, 0x8b,
    0xe8, 0x48, 0x8b, 0xda, 0x4c, 0x8b, 0xf9, 0x48, 0x8b, 0x02, 0x33, 0xd2, 0x48, 0x8b, 0xcb, 0xff,
    0x50, 0x50, 0x49, 0x8b, 0x4f, 0x18, 0x8b, 0x51, 0x0c, 0x8d, 0x42, 0x01, 0x89, 0x41, 0x0c, 0x4c,
    0x8b, 0xc3, 0xe8, 0x59, 0x8a, 0x01, 0x00, 0x83, 0x63, 0x0c, 0xf9, 0x83, 0x4b, 0x0c, 0x01, 0x48,
    0x8b, 0x03, 0x48, 0x8b, 0xcb, 0xff, 0x50, 0x20, 0x48, 0xc7, 0x44, 0x24, 0x20, 0x00, 0x00, 0x00,
    0x00, 0x48, 0xc7, 0x44, 0x24, 0x28, 0x00, 0x00, 0x00, 0x00, 0x41, 0xc7, 0x47, 0x08, 0x00, 0x00,
    0x00, 0x00, 0x49, 0x8b, 0x4f, 0x18, 0x83, 0x79, 0x0c, 0x00, 0x0f, 0x84, 0xeb, 0x01, 0x00, 0x00,
    0x33, 0xd2, 0xf2, 0x44, 0x0f, 0x10, 0x05, 0xa5, 0xc1, 0x10, 0x00, 0xf2, 0x44, 0x0f, 0x10, 0x0d,
    0x94, 0xc1, 0x10, 0x00, 0x41, 0x8b, 0x47, 0x0c, 0x85, 0xc0, 0x78, 0x08, 0x3b, 0xd0, 0x0f, 0x8d,
    0xc7, 0x01, 0x00, 0x00, 0x4c, 0x8b, 0x09, 0x49, 0x8b, 0x31, 0x48, 0x63, 0x51, 0x0c, 0x8d, 0x42,
    0xff, 0x89, 0x41, 0x0c, 0x4d, 0x8b, 0x44, 0xd1, 0xf8, 0x33, 0xd2, 0xe8, 0x50, 0x8a, 0x01, 0x00,
    0x83, 0x66, 0x0c, 0xfe, 0x83, 0x4e, 0x0c, 0x02, 0x49, 0x8b, 0x45, 0x00, 0x48, 0x8b, 0xd6, 0x49,
    0x8b, 0xcd, 0xff, 0x10, 0x84, 0xc0, 0x0f, 0x85, 0xd1, 0x01, 0x00, 0x00, 0xc7, 0x44, 0x24, 0x24,
    0x00, 0x00, 0x00, 0x00, 0x48, 0x8b, 0x06, 0x48, 0x8d, 0x54, 0x24, 0x20, 0x48, 0x8b, 0xce, 0xff,
    0x50, 0x30, 0x4c, 0x8b, 0x74, 0x24, 0x28, 0x48, 0x63, 0x44, 0x24, 0x24, 0x4d, 0x8d, 0x24, 0xc6,
    0x4d, 0x3b, 0xf4, 0x0f, 0x84, 0x4c, 0x01, 0x00, 0x00, 0x0f, 0x1f, 0x80, 0x00, 0x00, 0x00, 0x00,
    0x49, 0x8b, 0x3e, 0x48, 0x8b, 0x06, 0x48, 0x8b, 0xce, 0xff, 0x50, 0x38, 0x48, 0x3b, 0xf8, 0x0f,
    0x84, 0x23, 0x01, 0x00, 0x00, 0x49, 0x8b, 0x07, 0x4c, 0x8b, 0xc7, 0x48, 0x8b, 0xd6, 0x49, 0x8b,
    0xcf, 0xff, 0x50, 0x08, 0x8b, 0x6f, 0x0c, 0x83, 0xe5, 0x01, 0x48, 0x8b, 0x06, 0x48, 0x8b, 0xd7,
    0x48, 0x8b, 0xce, 0xff, 0x50, 0x28, 0x0f, 0x28, 0xf0, 0x48, 0x8b, 0x06, 0x48, 0x8b, 0xce, 0xff,
    0x50, 0x10, 0x0f, 0x28, 0xf8, 0xf2, 0x0f, 0x58, 0xfe, 0x48, 0x8b, 0x07, 0x48, 0x8b, 0xcf, 0xff,
    0x50, 0x20, 0x0f, 0x28, 0xf0, 0x49, 0x8b, 0x45, 0x00, 0x48, 0x8b, 0xd7, 0x49, 0x8b, 0xcd, 0xff,
    0x50, 0x08, 0x84, 0xc0, 0x74, 0x0d, 0x48, 0x8b, 0x07, 0x48, 0x8b, 0xcf, 0xff, 0x50, 0x20, 0xf2,
    0x0f, 0x58, 0xf8, 0xf2, 0x0f, 0x58, 0xf7, 0xf2, 0x41, 0x0f, 0x59, 0xf0, 0xf2, 0x41, 0x0f, 0x58,
    0xf1, 0x0f, 0x28, 0xc6, 0xe8, 0x87, 0xf5, 0x09, 0x00, 0xf2, 0x41, 0x0f, 0x5e, 0xc0, 0x0f, 0x57,
    0xf6, 0xf2, 0x0f, 0x5a, 0xf0, 0x40, 0x84, 0xed, 0x74, 0x0a, 0x0f, 0x2f, 0x77, 0x08, 0x0f, 0x83,
    0x94, 0x00, 0x00, 0x00, 0xf6, 0x47, 0x0c, 0x02, 0x74, 0x0a, 0x0f, 0x2f, 0x77, 0x08, 0x0f, 0x83,
    0x84, 0x00, 0x00, 0x00, 0x48, 0x8b, 0x07, 0x48, 0x8b, 0xd6, 0x48, 0x8b, 0xcf, 0xff, 0x50, 0x40,
    0x48, 0x8b, 0x1f, 0x48, 0x8b, 0x06, 0x48, 0x8b, 0xce, 0xff, 0x50, 0x48, 0x8d, 0x50, 0x01, 0x48,
    0x8b, 0xcf, 0xff, 0x53, 0x50, 0x83, 0x67, 0x0c, 0xfd, 0x48, 0x8b, 0x07, 0x0f, 0x28, 0xcf, 0x48,
    0x8b, 0xcf, 0xff, 0x50, 0x18, 0xf3, 0x0f, 0x11, 0x77, 0x08, 0x40, 0x84, 0xed, 0x74, 0x30, 0x4d,
    0x8b, 0x4f, 0x18, 0x33, 0xd2, 0x33, 0xc9, 0x49, 0x63, 0x41, 0x0c, 0x85, 0xc0, 0x7e, 0x39, 0x4c,
    0x8b, 0xc0, 0x49, 0x8b, 0x01, 0x48, 0x39, 0x38, 0x74, 0x10, 0xff, 0xc2, 0x48, 0xff, 0xc1, 0x48,
    0x83, 0xc0, 0x08, 0x49, 0x3b, 0xc8, 0x7c, 0xed, 0xeb, 0x1e, 0x49, 0x8b, 0xc9, 0xeb, 0x11, 0x83,
    0x4f, 0x0c, 0x01, 0x49, 0x8b, 0x4f, 0x18, 0x8b, 0x51, 0x0c, 0x8d, 0x42, 0x01, 0x89, 0x41, 0x0c,
    0x4c, 0x8b, 0xc7, 0xe8, 0x58, 0x88, 0x01, 0x00, 0x49, 0x83, 0xc6, 0x08, 0x4d, 0x3b, 0xf4, 0x0f,
    0x85, 0xbb, 0xfe, 0xff, 0xff, 0x41, 0xff, 0x47, 0x08, 0x41, 0x8b, 0x57, 0x08, 0x49, 0x8b, 0x4f,
    0x18, 0x83, 0x79, 0x0c, 0x00, 0x0f, 0x85, 0x29, 0xfe, 0xff, 0xff, 0x33, 0xf6, 0x48, 0x8b, 0x4c,
    0x24, 0x28, 0xe8, 0xa9, 0xfb, 0x02, 0x00, 0x90, 0x48, 0x8b, 0xc6, 0x4c, 0x8d, 0x5c, 0x24, 0x70,
    0x49, 0x8b, 0x5b, 0x30, 0x49, 0x8b, 0x6b, 0x38, 0x49, 0x8b, 0x73, 0x40, 0x0f, 0x28, 0x74, 0x24,
    0x60, 0x0f, 0x28, 0x7c, 0x24, 0x50, 0x45, 0x0f, 0x28, 0x43, 0xd0, 0x45, 0x0f, 0x28, 0x4b, 0xc0,
    0x49, 0x8b, 0xe3, 0x41, 0x5f, 0x41, 0x5e, 0x41, 0x5d, 0x41, 0x5c, 0x5f, 0xc3, 0x49, 0x89, 0x77,
    0x10, 0xeb, 0xba, 0xcc,
};
static const unsigned char PZPF_HEAP_SIG[246] = {
    0x48, 0x89, 0x5c, 0x24, 0x08, 0x44, 0x8b, 0xd2, 0x8d, 0x42, 0xff, 0x99, 0x49, 0x8b, 0xd8, 0x2b,
    0xc2, 0x4c, 0x8b, 0xd9, 0xd1, 0xf8, 0x44, 0x8b, 0xc8, 0x45, 0x85, 0xd2, 0x7e, 0x42, 0xf3, 0x41,
    0x0f, 0x10, 0x48, 0x08, 0x0f, 0x1f, 0x40, 0x00, 0x0f, 0x1f, 0x84, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x49, 0x8b, 0x13, 0x49, 0x63, 0xc9, 0x4c, 0x8b, 0x04, 0xca, 0xf3, 0x41, 0x0f, 0x10, 0x40, 0x08,
    0x0f, 0x2f, 0xc1, 0x76, 0x1b, 0x41, 0x8b, 0xc2, 0x45, 0x8b, 0xd1, 0x4c, 0x89, 0x04, 0xc2, 0x41,
    0x8d, 0x41, 0xff, 0x99, 0x2b, 0xc2, 0xd1, 0xf8, 0x44, 0x8b, 0xc8, 0x45, 0x85, 0xd2, 0x7f, 0xd0,
    0x49, 0x8b, 0x03, 0x49, 0x63, 0xca, 0x48, 0x89, 0x1c, 0xc8, 0x48, 0x8b, 0x5c, 0x24, 0x08, 0xc3,
    0x40, 0x57, 0x48, 0x83, 0xec, 0x20, 0x8b, 0x41, 0x0c, 0x44, 0x8d, 0x0c, 0x55, 0x01, 0x00, 0x00,
    0x00, 0x4c, 0x8b, 0xd9, 0x44, 0x3b, 0xc8, 0x7d, 0x5f, 0x48, 0x89, 0x5c, 0x24, 0x30, 0x48, 0x63,
    0xda, 0x4d, 0x63, 0xd1, 0x41, 0x8d, 0x51, 0x01, 0x3b, 0xd0, 0x7d, 0x1d, 0x49, 0x8b, 0x0b, 0x4a,
    0x8b, 0x04, 0xd1, 0x4a, 0x8b, 0x4c, 0xd1, 0x08, 0xf3, 0x0f, 0x10, 0x40, 0x08, 0x0f, 0x2f, 0x41,
    0x08, 0x76, 0x06, 0x44, 0x8b, 0xca, 0x49, 0xff, 0xc2, 0x49, 0x8b, 0x0b, 0x41, 0x8b, 0xd1, 0x46,
    0x8d, 0x0c, 0x4d, 0x01, 0x00, 0x00, 0x00, 0x4a, 0x8b, 0x04, 0xd1, 0x48, 0x89, 0x04, 0xd9, 0x49,
    0x8b, 0xda, 0x41, 0x8b, 0x43, 0x0c, 0x4e, 0x8d, 0x14, 0x55, 0x01, 0x00, 0x00, 0x00, 0x44, 0x3b,
    0xc8, 0x7c, 0xb1, 0x48, 0x8b, 0x5c, 0x24, 0x30, 0x49, 0x8b, 0xcb, 0x48, 0x83, 0xc4, 0x20, 0x5f,
    0xe9, 0x0b, 0xff, 0xff, 0xff, 0xcc,
};
static const unsigned char PZPF_FREE_SIG[5] = {
    0xe9, 0xeb, 0x9d, 0x09, 0x00,
};

typedef struct { void **data; int capacity; int count; } PzpfHeap;
typedef struct { int cap; int size; void **data; } PzpfVec;

typedef void   (*pzpf_set_int)(void *, int);
typedef double (*pzpf_get_d)(void *);
typedef double (*pzpf_cost_d)(void *, void *);
typedef void   (*pzpf_set_d)(void *, double);
typedef void  *(*pzpf_get_p)(void *);
typedef void   (*pzpf_set_p)(void *, void *);
typedef int    (*pzpf_get_i)(void *);
typedef void   (*pzpf_fill_vec)(void *, PzpfVec *);
typedef unsigned char (*pzpf_test)(void *, void *);
typedef void   (*pzpf_visit)(void *, void *, void *);
typedef void   (*pzpf_free_fn)(void *);

#define PZPF_VT(o, off) ((*(void ***)(o))[(off) / 8])
#define PZPF_F(n) (*(float *)((char *)(n) + 8))
#define PZPF_FLAGS(n) (*(unsigned int *)((char *)(n) + 0xc))

typedef struct { void *key; int pos; unsigned int stamp; } PzpfSlot;

static __declspec(thread) PzpfSlot *t_pzpf_slots = NULL;
static __declspec(thread) unsigned int t_pzpf_mask = 0;
static __declspec(thread) unsigned int t_pzpf_used = 0;
static __declspec(thread) unsigned int t_pzpf_stamp = 0;

static unsigned char *g_pzpf_target = NULL;
static unsigned char g_pzpf_saved[PZPF_PATCH_LEN];
static volatile LONG g_pzpf_installed = 0;
static pzpf_free_fn g_pzpf_free = NULL;
static volatile LONG64 g_pzpf_searches = 0;
static volatile LONG64 g_pzpf_expanded = 0;
static volatile LONG64 g_pzpf_decrease = 0;

static unsigned int pzpf_hash(void *p, unsigned int mask) {
    unsigned long long h = ((unsigned long long)(ULONG_PTR)p >> 4) * 0xDBF1E80C8D1F0601ULL;
    return (unsigned int)(h >> 32) & mask;
}

static int pzpf_table_reserve(unsigned int minSlots) {
    unsigned int size = 1;
    while (size < minSlots) size <<= 1;
    PzpfSlot *fresh = (PzpfSlot *)calloc(size, sizeof(PzpfSlot));
    if (!fresh) return 0;
    PzpfSlot *old = t_pzpf_slots;
    unsigned int oldSize = old ? t_pzpf_mask + 1 : 0;
    unsigned int mask = size - 1;
    unsigned int used = 0;
    for (unsigned int i = 0; i < oldSize; i++) {
        if (old[i].key && old[i].stamp == t_pzpf_stamp) {
            unsigned int h = pzpf_hash(old[i].key, mask);
            while (fresh[h].key) h = (h + 1) & mask;
            fresh[h] = old[i];
            used++;
        }
    }
    free(old);
    t_pzpf_slots = fresh;
    t_pzpf_mask = mask;
    t_pzpf_used = used;
    return 1;
}

/* Starts a new search: stale entries are invalidated by the stamp instead of clearing the table */
static int pzpf_table_begin(void) {
    if (!t_pzpf_slots && !pzpf_table_reserve(16384)) return 0;
    t_pzpf_stamp++;
    if (t_pzpf_stamp == 0) {
        memset(t_pzpf_slots, 0, (size_t)(t_pzpf_mask + 1) * sizeof(PzpfSlot));
        t_pzpf_stamp = 1;
    }
    t_pzpf_used = 0;
    return 1;
}

static void pzpf_set_pos(void *node, int pos) {
    unsigned int mask = t_pzpf_mask;
    unsigned int h = pzpf_hash(node, mask);
    for (;;) {
        PzpfSlot *s = &t_pzpf_slots[h];
        if (s->stamp != t_pzpf_stamp || !s->key) {
            s->key = node;
            s->pos = pos;
            s->stamp = t_pzpf_stamp;
            if (++t_pzpf_used * 2 > mask + 1) pzpf_table_reserve((mask + 1) * 2);
            return;
        }
        if (s->key == node) {
            s->pos = pos;
            return;
        }
        h = (h + 1) & mask;
    }
}

static int pzpf_get_pos(void *node) {
    unsigned int mask = t_pzpf_mask;
    unsigned int h = pzpf_hash(node, mask);
    for (;;) {
        PzpfSlot *s = &t_pzpf_slots[h];
        if (s->stamp != t_pzpf_stamp || !s->key) return -1;
        if (s->key == node) return s->pos;
        h = (h + 1) & mask;
    }
}

/* Exact port of the DLL's sift-up (0x25180): strict '>' keeps equal-cost ordering identical */
static void pzpf_sift_up(PzpfHeap *heap, int idx, void *node) {
    void **data = heap->data;
    float nf = PZPF_F(node);
    int i = idx;
    int parent = (i - 1) / 2;
    while (i > 0) {
        void *p = data[parent];
        if (!(PZPF_F(p) > nf)) break;
        data[i] = p;
        pzpf_set_pos(p, i);
        i = parent;
        parent = (parent - 1) / 2;
    }
    data[i] = node;
    pzpf_set_pos(node, i);
}

/* Exact port of the DLL's removal (0x251f0): walk the hole to a leaf, then sift the filler up */
static void pzpf_remove_at(PzpfHeap *heap, int idx, void *node) {
    void **data = heap->data;
    int hole = idx;
    int child = 2 * hole + 1;
    while (child < heap->count) {
        if (child + 1 < heap->count && PZPF_F(data[child]) > PZPF_F(data[child + 1])) child++;
        data[hole] = data[child];
        pzpf_set_pos(data[child], hole);
        hole = child;
        child = 2 * hole + 1;
    }
    pzpf_sift_up(heap, hole, node);
}

/* Vanilla rounds each cost with separate multiply and add; a fused FMA would round differently */
#pragma fp_contract(off)
static void *pzpf_astar(char *self, void *start, void *goal) {
    PzpfHeap *heap;
    PzpfVec vec = { 0, 0, NULL };
    void *result = NULL;

    ((pzpf_set_int)PZPF_VT(start, 0x50))(start, 0);
    heap = *(PzpfHeap **)(self + 0x18);

    if (!pzpf_table_begin()) return NULL;
    for (int i = heap->count - 1; i >= 0; i--) pzpf_set_pos(heap->data[i], i);

    {
        int ix = heap->count;
        heap->count = ix + 1;
        pzpf_sift_up(heap, ix, start);
    }
    PZPF_FLAGS(start) = (PZPF_FLAGS(start) & ~6u) | 1u;
    ((pzpf_get_d)PZPF_VT(start, 0x20))(start);
    *(int *)(self + 8) = 0;
    InterlockedIncrement64(&g_pzpf_searches);

    heap = *(PzpfHeap **)(self + 0x18);
    if (heap->count != 0) {
        int iter = 0;
        for (;;) {
            int maxIter = *(int *)(self + 0xc);
            if (maxIter >= 0 && iter >= maxIter) break;

            void **data = heap->data;
            void *cur = data[0];
            int cnt = heap->count;
            heap->count = cnt - 1;
            pzpf_remove_at(heap, 0, data[cnt - 1]);
            PZPF_FLAGS(cur) = (PZPF_FLAGS(cur) & ~1u) | 2u;
            g_pzpf_expanded++;

            if (((pzpf_test)PZPF_VT(goal, 0x00))(goal, cur)) {
                *(void **)(self + 0x10) = cur;
                result = cur;
                break;
            }

            vec.size = 0;
            ((pzpf_fill_vec)PZPF_VT(cur, 0x30))(cur, &vec);
            void **it = vec.data;
            void **end = vec.data + vec.size;
            for (; it != end; ++it) {
                void *n = *it;
                if (n == ((pzpf_get_p)PZPF_VT(cur, 0x38))(cur)) continue;
                ((pzpf_visit)PZPF_VT(self, 0x08))(self, cur, n);
                unsigned int wasOpen = PZPF_FLAGS(n) & 1u;
                double edge = ((pzpf_cost_d)PZPF_VT(cur, 0x28))(cur, n);
                double g = ((pzpf_get_d)PZPF_VT(cur, 0x10))(cur);
                g = g + edge;
                double h = ((pzpf_get_d)PZPF_VT(n, 0x20))(n);
                if (((pzpf_test)PZPF_VT(goal, 0x08))(goal, n)) {
                    double h2 = ((pzpf_get_d)PZPF_VT(n, 0x20))(n);
                    g = g + h2;
                }
                double t = h + g;
                t = t * 1000.0;
                t = t + 0.5;
                t = floor(t);
                t = t / 1000.0;
                float f = (float)t;

                if (wasOpen && f >= PZPF_F(n)) continue;
                if ((PZPF_FLAGS(n) & 2u) && f >= PZPF_F(n)) continue;

                ((pzpf_set_p)PZPF_VT(n, 0x40))(n, cur);
                pzpf_set_int setDepth = (pzpf_set_int)PZPF_VT(n, 0x50);
                int depth = ((pzpf_get_i)PZPF_VT(cur, 0x48))(cur);
                setDepth(n, depth + 1);
                PZPF_FLAGS(n) &= ~2u;
                ((pzpf_set_d)PZPF_VT(n, 0x18))(n, g);
                PZPF_F(n) = f;

                heap = *(PzpfHeap **)(self + 0x18);
                if (wasOpen) {
                    int pos = pzpf_get_pos(n);
                    if (pos >= 0 && pos < heap->count && heap->data[pos] == n) {
                        pzpf_sift_up(heap, pos, n);
                        g_pzpf_decrease++;
                    }
                } else {
                    PZPF_FLAGS(n) |= 1u;
                    int ix = heap->count;
                    /* The heap holds capacity + 1 slots; vanilla would write past the end here */
                    if (ix <= heap->capacity) {
                        heap->count = ix + 1;
                        pzpf_sift_up(heap, ix, n);
                    } else {
                        PZPF_FLAGS(n) &= ~1u;
                    }
                }
            }

            iter = ++*(int *)(self + 8);
            heap = *(PzpfHeap **)(self + 0x18);
            if (heap->count == 0) break;
        }
    }

    if (g_pzpf_free) g_pzpf_free(vec.data);
    return result;
}

static int pzpf_install(void) {
    HMODULE mod = GetModuleHandleA("PZPathFind64.dll");
    if (!mod) return -1;
    unsigned char *base = (unsigned char *)mod;
    if (memcmp(base + PZPF_ASTAR_RVA, PZPF_ASTAR_SIG, sizeof(PZPF_ASTAR_SIG)) != 0 ||
        memcmp(base + PZPF_HEAP_RVA, PZPF_HEAP_SIG, sizeof(PZPF_HEAP_SIG)) != 0 ||
        memcmp(base + PZPF_FREE_RVA, PZPF_FREE_SIG, sizeof(PZPF_FREE_SIG)) != 0) {
        return -2;
    }
    unsigned char *target = base + PZPF_ASTAR_RVA;
    unsigned char patch[PZPF_PATCH_LEN] = { 0xFF, 0x25, 0x00, 0x00, 0x00, 0x00 };
    unsigned long long dest = (unsigned long long)(ULONG_PTR)&pzpf_astar;
    memcpy(patch + 6, &dest, sizeof(dest));
    DWORD oldProtect;
    if (!VirtualProtect(target, PZPF_PATCH_LEN, PAGE_EXECUTE_READWRITE, &oldProtect)) return -3;
    memcpy(g_pzpf_saved, target, PZPF_PATCH_LEN);
    g_pzpf_free = (pzpf_free_fn)(base + PZPF_FREE_RVA);
    memcpy(target, patch, PZPF_PATCH_LEN);
    VirtualProtect(target, PZPF_PATCH_LEN, oldProtect, &oldProtect);
    FlushInstructionCache(GetCurrentProcess(), target, PZPF_PATCH_LEN);
    g_pzpf_target = target;
    InterlockedExchange(&g_pzpf_installed, 1);
    return 1;
}

static int pzpf_uninstall(void) {
    if (!g_pzpf_installed || !g_pzpf_target) return 0;
    DWORD oldProtect;
    if (!VirtualProtect(g_pzpf_target, PZPF_PATCH_LEN, PAGE_EXECUTE_READWRITE, &oldProtect)) return -3;
    memcpy(g_pzpf_target, g_pzpf_saved, PZPF_PATCH_LEN);
    VirtualProtect(g_pzpf_target, PZPF_PATCH_LEN, oldProtect, &oldProtect);
    FlushInstructionCache(GetCurrentProcess(), g_pzpf_target, PZPF_PATCH_LEN);
    InterlockedExchange(&g_pzpf_installed, 0);
    return 0;
}
#endif

/*
 * Returns 1 when the replacement is active, 0 when vanilla is active, -1 if PZPathFind64.dll
 * is not loaded, -2 if the DLL is a different build, -3 if the code page could not be
 * written, -4 on non-Windows platforms. Must be called from the pathfinding thread so the
 * patched function is never mid-execution.
 */
JNIEXPORT jint JNICALL Java_com_pzoptimizer_PZONative_setPathfindAStarHook(JNIEnv *env, jclass cls, jboolean enable) {
    (void)env; (void)cls;
#ifdef _WIN32
    if (enable) {
        if (g_pzpf_installed) return 1;
        return pzpf_install();
    }
    return pzpf_uninstall();
#else
    (void)enable;
    return -4;
#endif
}

JNIEXPORT void JNICALL Java_com_pzoptimizer_PZONative_getPathfindAStarStats(JNIEnv *env, jclass cls, jlongArray out) {
    (void)cls;
    if (!out || (*env)->GetArrayLength(env, out) < 3) return;
    jlong vals[3] = { 0, 0, 0 };
#ifdef _WIN32
    vals[0] = (jlong)g_pzpf_searches;
    vals[1] = (jlong)g_pzpf_expanded;
    vals[2] = (jlong)g_pzpf_decrease;
#endif
    (*env)->SetLongArrayRegion(env, out, 0, 3, vals);
}

/* JVMTI agent (loaded via -agentlib:pzo_native64) */

#define PZO_JAR "PZOptimEngine.jar"
#define PZO_AGENT_OPTIONS_MAX 2048

#ifdef _WIN32
static HMODULE g_hInstrument = NULL;
#else
static void* g_hInstrument = NULL;
#endif

static jint (JNICALL *g_pAgent_OnAttach)(JavaVM*, char*, void*) = NULL;
static jint (JNICALL *g_pAgent_OnLoad)(JavaVM*, char*, void*)   = NULL;
static void (JNICALL *g_pAgent_OnUnload)(JavaVM*)               = NULL;

static void write_pzo_console(const char* msg) {
#ifdef _WIN32
    HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);
    if (hOut && hOut != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        WriteConsoleA(hOut, msg, (DWORD)strlen(msg), &written, NULL);
    }
#else
    fprintf(stdout, "%s", msg);
    fflush(stdout);
#endif
}

static void init_instrument_dll(void) {
    if (g_hInstrument) return;

#ifdef _WIN32
    // Relative jre64\\bin directory first
    SetDllDirectoryA(".\\jre64\\bin");
    g_hInstrument = LoadLibraryA("instrument.dll");
    SetDllDirectoryA(NULL);

    // Then direct path fallbacks
    if (!g_hInstrument) {
        g_hInstrument = LoadLibraryA("jre64\\bin\\instrument.dll");
    }
    if (!g_hInstrument) {
        g_hInstrument = LoadLibraryA("instrument.dll");
    }
    if (!g_hInstrument) {
        g_hInstrument = LoadLibraryA("bin\\instrument.dll");
    }

    if (!g_hInstrument) {
        write_pzo_console("[PZONative] Notice: instrument.dll could not be loaded directly.\n");
        return;
    }

    g_pAgent_OnAttach = (jint (JNICALL *)(JavaVM*, char*, void*))GetProcAddress(g_hInstrument, "Agent_OnAttach");
    g_pAgent_OnLoad   = (jint (JNICALL *)(JavaVM*, char*, void*))GetProcAddress(g_hInstrument, "Agent_OnLoad");
    g_pAgent_OnUnload = (void (JNICALL *)(JavaVM*))GetProcAddress(g_hInstrument, "Agent_OnUnload");
#else
    g_hInstrument = dlopen("libinstrument.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g_hInstrument) {
        g_hInstrument = dlopen("libinstrument.dylib", RTLD_NOW | RTLD_GLOBAL);
    }
    if (!g_hInstrument) {
        g_hInstrument = dlopen("jre/lib/libinstrument.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (!g_hInstrument) {
        g_hInstrument = dlopen("jre64/lib/libinstrument.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (!g_hInstrument) {
        g_hInstrument = dlopen("jre64/lib/server/libinstrument.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (!g_hInstrument) {
        g_hInstrument = dlopen("projectzomboid/jre64/lib/libinstrument.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (!g_hInstrument) {
        g_hInstrument = dlopen("../jre64/lib/libinstrument.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (!g_hInstrument) {
        return;
    }
    g_pAgent_OnAttach = (jint (JNICALL *)(JavaVM*, char*, void*))dlsym(g_hInstrument, "Agent_OnAttach");
    g_pAgent_OnLoad   = (jint (JNICALL *)(JavaVM*, char*, void*))dlsym(g_hInstrument, "Agent_OnLoad");
    g_pAgent_OnUnload = (void (JNICALL *)(JavaVM*))dlsym(g_hInstrument, "Agent_OnUnload");
#endif
}

static int build_pzo_agent_options(const char* tail, char* out, int outSize) {
    const char* jarName = PZO_JAR;
#ifdef _WIN32
    if (GetFileAttributesA(jarName) == INVALID_FILE_ATTRIBUTES) {
        if (GetFileAttributesA("win64\\PZOptimEngine.jar") != INVALID_FILE_ATTRIBUTES) {
            jarName = "win64\\PZOptimEngine.jar";
        }
    }
#endif
    int jarLen = (int)strlen(jarName);
    int tailLen = (tail == NULL) ? 0 : (int)strlen(tail);
    int needsArgs = tailLen > 0;
    int totalLen = jarLen + (needsArgs ? 1 + tailLen : 0);

    if (totalLen + 1 > outSize) {
        write_pzo_console("[PZONative] Error: agent options string too long\n");
        return 0;
    }

    strcpy(out, jarName);
    if (needsArgs) {
        out[jarLen] = '=';
        strcpy(out + jarLen + 1, tail);
    }
    return 1;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    write_pzo_console("[PZONative] JVMTI Agent_OnLoad: Safe PZO Instrumentation Bridge\n");
    if (g_hInstrument == NULL) {
        init_instrument_dll();
    }
    if (!g_pAgent_OnLoad) {
        write_pzo_console("[PZONative] Notice: instrument.dll not hooked, bypassing Agent_OnLoad without error.\n");
        return 0; // JNI_OK: never abort the JVM
    }

    char agentOptions[PZO_AGENT_OPTIONS_MAX];
    if (!build_pzo_agent_options(options, agentOptions, sizeof(agentOptions))) {
        return 0; // JNI_OK: never abort the JVM
    }

    jint res = g_pAgent_OnLoad(vm, agentOptions, reserved);
    if (res == 0) {
        write_pzo_console("[PZONative] JVMTI Agent_OnLoad: PZOptimEngine.jar successfully attached as instrumentation agent!\n");
    } else {
        char msg[128];
        snprintf(msg, sizeof(msg), "[PZONative] Notice: instrument.dll Agent_OnLoad returned %d (bypassing without abort)\n", res);
        write_pzo_console(msg);
    }
    return 0; // Always JNI_OK (0) so HotSpot never aborts VM initialization
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    if (g_hInstrument == NULL) {
        init_instrument_dll();
    }
    if (!g_pAgent_OnAttach) {
        return 0;
    }

    char agentOptions[PZO_AGENT_OPTIONS_MAX];
    if (!build_pzo_agent_options(options, agentOptions, sizeof(agentOptions))) {
        return 0;
    }

    g_pAgent_OnAttach(vm, agentOptions, reserved);
    return 0;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm) {
    if (g_hInstrument == NULL) {
        return;
    }
    if (g_pAgent_OnUnload) {
        g_pAgent_OnUnload(vm);
    }
#ifdef _WIN32
    FreeLibrary(g_hInstrument);
#else
    dlclose(g_hInstrument);
#endif
    g_hInstrument = NULL;
}

