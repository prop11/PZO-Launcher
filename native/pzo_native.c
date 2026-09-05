/*
 * Project Zomboid Optimiser (PZO) - Native Kernel & Hardware Governor
 * Target: Windows x64 (Build 41 & Build 42)
 *
 * Implements OS-level kernel governors:
 *  - 0.5ms Interrupt Timer Resolution Lock (NtSetTimerResolution + timeBeginPeriod)
 *  - Windows 11 Power Throttling / EcoQoS Complete Exemption (SetProcessInformation)
 *  - Windows Multimedia Class Scheduler Service (MMCSS "Games" profile via Avrt)
 *  - CPU Hybrid Topology (P-Cores vs E-Cores / AMD 3D V-Cache) Affinity Binding
 *  - AVX2 Vectorized SIMD Batch Spatial & Distance Processor (zero-copy NIO)
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mmsystem.h>
#include <timeapi.h>
#include <avrt.h>
#include <immintrin.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <winioctl.h>

#include "miniz.h"
#include "miniz_tinfl.c"

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

// CPU Topology & State Cache
static DWORD_PTR g_pCoreAffinityMask = 0;
static int g_physicalCores = 0;
static int g_pCoresCount = 0;
static int g_logicalProcessors = 0;
static BOOL g_avx2Supported = FALSE;
static BOOL g_timerLocked = FALSE;
static ULONG g_activeTimerResolution100ns = 156250;
static HANDLE g_mmcssTaskHandle = NULL;

// Check AVX2 CPUID
static BOOL checkCpuAvx2Support(void) {
    int cpuInfo[4] = {0};
    __cpuid(cpuInfo, 0);
    int nIds = cpuInfo[0];
    if (nIds >= 7) {
        __cpuidex(cpuInfo, 7, 0);
        return (cpuInfo[1] & (1 << 5)) != 0; // EBX bit 5 = AVX2
    }
    return FALSE;
}

// Query CPU Topology: distinguishes Performance Cores (P-Cores) from Efficiency Cores (E-Cores)
static void detectCpuTopology(void) {
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

    // Pass 1: find highest efficiency class and count cores
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

    // Pass 2: gather affinity mask for highest efficiency class (P-cores)
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
}

// 0.5ms Interrupt Timer Lock
static BOOL setHighPrecisionTimer(BOOL enable) {
    if (enable) {
        timeBeginPeriod(1);

        if (g_NtSetTimerResolution) {
            ULONG minRes = 0, maxRes = 0, curRes = 0;
            if (g_NtQueryTimerResolution) {
                g_NtQueryTimerResolution(&minRes, &maxRes, &curRes);
            }
            // Request 5000 (0.5ms = 5000 * 100ns) or maxRes if hardware supports finer
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
}

// Complete Windows 11 Power Throttling / EcoQoS Exemption
static BOOL disablePowerThrottling(void) {
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
}

// MMCSS "Games" Profile via Avrt
static BOOL setMMCSSProfile(const wchar_t* profile) {
    DWORD taskIndex = 0;
    HANDLE hTask = AvSetMmThreadCharacteristicsW(profile ? profile : L"Games", &taskIndex);
    if (hTask != NULL) {
        g_mmcssTaskHandle = hTask;
        return TRUE;
    }
    return FALSE;
}

// Windows Process Priority Governor
static BOOL setProcessPriority(int level) {
    DWORD pClass = ABOVE_NORMAL_PRIORITY_CLASS;
    if (level == 2) {
        pClass = HIGH_PRIORITY_CLASS;
    } else if (level == 0) {
        pClass = NORMAL_PRIORITY_CLASS;
    }
    return SetPriorityClass(GetCurrentProcess(), pClass);
}

// P-Core Affinity Binding for Current Thread
static BOOL bindCurrentThreadToPCores(void) {
    if (g_pCoreAffinityMask != 0) {
        DWORD_PTR prev = SetThreadAffinityMask(GetCurrentThread(), g_pCoreAffinityMask);
        return (prev != 0);
    }
    return FALSE;
}

// Complete Calling Thread Optimization: Priority, MMCSS "Games", and P-Core Affinity
static BOOL optimizeCallingThread(int priorityLevel, BOOL bindPCores, const wchar_t* mmcssProfile) {
    HANDLE hThread = GetCurrentThread();

    // 1. Thread priority
    int p = THREAD_PRIORITY_NORMAL;
    if (priorityLevel >= 3) {
        p = THREAD_PRIORITY_TIME_CRITICAL;
    } else if (priorityLevel == 2) {
        p = THREAD_PRIORITY_HIGHEST;
    } else if (priorityLevel == 1) {
        p = THREAD_PRIORITY_ABOVE_NORMAL;
    }
    SetThreadPriority(hThread, p);

    // 2. Performance Core affinity
    if (bindPCores && g_pCoreAffinityMask != 0) {
        SetThreadAffinityMask(hThread, g_pCoreAffinityMask);
    }

    // 3. Multimedia Class Scheduler (MMCSS)
    if (mmcssProfile != NULL && mmcssProfile[0] != L'\0') {
        DWORD taskIndex = 0;
        AvSetMmThreadCharacteristicsW(mmcssProfile, &taskIndex);
    }

    return TRUE;
}

// Windows Working Set & Physical RAM Locking
static BOOL lockProcessWorkingSet(void) {
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

    if (SetProcessWorkingSetSizeEx(GetCurrentProcess(), minSize, maxSize, QUOTA_LIMITS_HARDWS_MIN_ENABLE)) {
        return TRUE;
    }
    return SetProcessWorkingSetSizeEx(GetCurrentProcess(), minSize, maxSize, 0);
}

// AVX2 Vectorized 2D Distance Calculation (Zero-copy, 8 floats per SIMD instruction)
static int batchCalculateDistancesAVX2(const float* coords, int count, float ox, float oy, float* outDistances) {
    if (!coords || !outDistances || count <= 0) return 0;

    int i = 0;

    if (g_avx2Supported && count >= 8) {
        __m256 vOx = _mm256_set1_ps(ox);
        __m256 vOy = _mm256_set1_ps(oy);
        const __m256i permIdx = _mm256_setr_epi32(0, 1, 4, 5, 2, 3, 6, 7);

        for (; i <= count - 8; i += 8) {
            // Load 8 (x,y) pairs = 16 contiguous floats
            __m256 c0 = _mm256_loadu_ps(&coords[(i + 0) * 2]); // x0, y0, x1, y1, x2, y2, x3, y3
            __m256 c1 = _mm256_loadu_ps(&coords[(i + 4) * 2]); // x4, y4, x5, y5, x6, y6, x7, y7

            // De-interleave into separate xs and ys
            __m256 shuf0 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(2, 0, 2, 0)); // x0, x1, x4, x5, x2, x3, x6, x7
            __m256 shuf1 = _mm256_shuffle_ps(c0, c1, _MM_SHUFFLE(3, 1, 3, 1)); // y0, y1, y4, y5, y2, y3, y6, y7
            __m256 xs = _mm256_permutevar8x32_ps(shuf0, permIdx);
            __m256 ys = _mm256_permutevar8x32_ps(shuf1, permIdx);

            __m256 dx = _mm256_sub_ps(xs, vOx);
            __m256 dy = _mm256_sub_ps(ys, vOy);
            __m256 distSq = _mm256_add_ps(_mm256_mul_ps(dx, dx), _mm256_mul_ps(dy, dy));
            __m256 dist = _mm256_sqrt_ps(distSq);

            _mm256_storeu_ps(&outDistances[i], dist);
        }
    }

    // Scalar fallback for remainder
    for (; i < count; i++) {
        float dx = coords[i * 2] - ox;
        float dy = coords[i * 2 + 1] - oy;
        outDistances[i] = sqrtf(dx * dx + dy * dy);
    }

    return count;
}

// Phase 3: AVX2 Vectorized 2D Squared Distance Calculation (Zero-copy, 8 floats per SIMD instruction, no sqrt)
static int batchCalculateDistancesSqAVX2(const float* coords, int count, float ox, float oy, float* outDistSq) {
    if (!coords || !outDistSq || count <= 0) return 0;

    int i = 0;

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

    for (; i < count; i++) {
        float dx = coords[i * 2] - ox;
        float dy = coords[i * 2 + 1] - oy;
        outDistSq[i] = dx * dx + dy * dy;
    }

    return count;
}

// Phase 3: AVX2 Vectorized Radial Proximity Culling (Returns count inside radius, writes 0/1 byte mask)
static int batchCullRadialAVX2(const float* coords, int count, float ox, float oy, float maxRadiusSq, unsigned char* outMask) {
    if (!coords || !outMask || count <= 0) return 0;

    int insideCount = 0;
    int i = 0;

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

// Phase 3: AVX2 Vectorized 2D AABB / Screen Viewport Culling (Returns count inside AABB, writes 0/1 byte mask)
static int batchCullAABBAVX2(const float* coords, int count, float minX, float minY, float maxX, float maxY, unsigned char* outMask) {
    if (!coords || !outMask || count <= 0) return 0;

    int insideCount = 0;
    int i = 0;

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

    for (; i < count; i++) {
        float x = coords[i * 2];
        float y = coords[i * 2 + 1];
        unsigned char inBox = (x >= minX && x <= maxX && y >= minY && y <= maxY) ? 1 : 0;
        outMask[i] = inBox;
        insideCount += inBox;
    }

    return insideCount;
}

// Phase 3: AVX2 Vectorized Multi-Tier Distance Classification (Tier 0: <=t0, Tier 1: <=t1, Tier 2: <=t2, Tier 3: >t2)
static int batchClassifyTiersAVX2(const float* coords, int count, float ox, float oy,
                                  float t0Sq, float t1Sq, float t2Sq, unsigned char* outTiers) {
    if (!coords || !outTiers || count <= 0) return 0;

    int i = 0;

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

// ============================================================================
// JNI Exports for com.pzoptimizer.PZONative
// ============================================================================

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_initNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;

    HMODULE hNtdll = GetModuleHandleW(L"ntdll.dll");
    if (hNtdll) {
        g_NtSetTimerResolution = (pfnNtSetTimerResolution)GetProcAddress(hNtdll, "NtSetTimerResolution");
        g_NtQueryTimerResolution = (pfnNtQueryTimerResolution)GetProcAddress(hNtdll, "NtQueryTimerResolution");
    }

    HMODULE hKernel32 = GetModuleHandleW(L"kernel32.dll");
    if (hKernel32) {
        g_SetProcessInformation = (pfnSetProcessInformation)GetProcAddress(hKernel32, "SetProcessInformation");
    }

    // Set NVIDIA & GPU multi-threaded driver optimizations for butter-smooth OpenGL command translation
    SetEnvironmentVariableW(L"__GL_THREADED_OPTIMIZATIONS", L"1");
    SetEnvironmentVariableW(L"__GL_YIELD", L"NOTHING");

    g_avx2Supported = checkCpuAvx2Support();
    detectCpuTopology();

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_optimizeCallingThread(
    JNIEnv *env, jclass cls, jint priorityLevel, jboolean bindPCores, jstring profileStr) {
    (void)cls;
    const jchar* chars = profileStr ? (*env)->GetStringChars(env, profileStr, NULL) : NULL;
    BOOL res = optimizeCallingThread((int)priorityLevel, bindPCores == JNI_TRUE, (const wchar_t*)chars);
    if (chars) {
        (*env)->ReleaseStringChars(env, profileStr, chars);
    }
    return res ? JNI_TRUE : JNI_FALSE;
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
    const jchar* chars = profileStr ? (*env)->GetStringChars(env, profileStr, NULL) : NULL;
    BOOL res = setMMCSSProfile((const wchar_t*)chars);
    if (chars) {
        (*env)->ReleaseStringChars(env, profileStr, chars);
    }
    return res ? JNI_TRUE : JNI_FALSE;
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

// ============================================================================
// Phase 2: High-Speed SIMD Decompression & Win32 Chunk Stream Acceleration
// ============================================================================

static jint decompressBuffer(const unsigned char *src, size_t srcLen, unsigned char *dst, size_t dstCap) {
    if (!src || !dst || srcLen == 0 || dstCap == 0) return -1;

    // Detect RFC 1950 zlib stream:
    // Byte 0: CM=8 (deflate), CINFO <= 7 (window size up to 32K) -> (src[0] & 0x0F) == 8 && (src[0] >> 4) <= 7
    // Byte 1: FCHECK check bits -> (src[0] * 256 + src[1]) % 31 == 0
    int flags = TINFL_FLAG_USING_NON_WRAPPING_OUTPUT_BUF;
    if (srcLen >= 2) {
        unsigned int hdr = ((unsigned int)src[0] << 8) | (unsigned int)src[1];
        if ((src[0] & 0x0F) == 8 && ((src[0] >> 4) <= 7) && (hdr % 31 == 0)) {
            flags |= TINFL_FLAG_PARSE_ZLIB_HEADER;
        }
    }

    size_t decompressedBytes = tinfl_decompress_mem_to_mem(dst, dstCap, src, srcLen, flags);
    if (decompressedBytes == TINFL_DECOMPRESS_MEM_TO_MEM_FAILED && (flags & TINFL_FLAG_PARSE_ZLIB_HEADER)) {
        // Fallback: try raw deflate without zlib header
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
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_prewarmFileNative(
    JNIEnv *env, jclass cls, jstring filePath) {
    (void)cls;
    if (!filePath) return JNI_FALSE;

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
        (*env)->DeleteLocalRef(env, filePath);
    }
    return successCount;
}

JNIEXPORT jboolean JNICALL Java_com_pzoptimizer_PZONative_isDriveSSDNative(JNIEnv *env, jclass cls, jstring pathStr) {
    (void)cls;
    if (!pathStr) return JNI_TRUE;

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
}


