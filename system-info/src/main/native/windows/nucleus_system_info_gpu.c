// GPU information via DXGI + live metrics via NVML (NVIDIA) and DXGI 1.4 (VRAM usage).
// NVML is loaded dynamically — not required at compile time.

#include "nucleus_system_info_common.h"

// DXGI headers
#include <dxgi.h>
#include <dxgi1_4.h>
#include <initguid.h>
#include <math.h>

// IDXGIFactory1 GUID
DEFINE_GUID(IID_IDXGIFactory1_local,
    0x770aae78, 0xf26f, 0x4dba, 0xa8, 0x29, 0x25, 0x3c, 0x83, 0xd1, 0xb3, 0x87);

// IDXGIAdapter3 GUID (DXGI 1.4 for QueryVideoMemoryInfo)
DEFINE_GUID(IID_IDXGIAdapter3_local,
    0x645967a4, 0x1392, 0x4310, 0xa7, 0x98, 0x80, 0x53, 0xce, 0x3e, 0x93, 0xfd);

#define MAX_GPUS 16

typedef struct {
    char name[256];
    UINT vendor_id;
    UINT device_id;
    SIZE_T dedicated_video_memory;
    SIZE_T dedicated_system_memory;
    SIZE_T shared_system_memory;
    char driver_version[64];
    // Live metrics
    float temperature;       // NaN if unavailable
    float gpu_usage;         // NaN if unavailable
    long long memory_used;   // -1 if unavailable
    int core_clock_mhz;     // -1 if unavailable
    int memory_clock_mhz;   // -1 if unavailable
    float fan_speed_pct;     // NaN if unavailable
    float power_draw_watts;  // NaN if unavailable
} gpu_entry_t;

static gpu_entry_t g_gpus[MAX_GPUS];
static int g_gpu_count = 0;

// ---- NVML dynamic loading ----

typedef int nvmlReturn_t;
#define NVML_SUCCESS 0
typedef void* nvmlDevice_t;
typedef enum { NVML_TEMPERATURE_GPU = 0 } nvmlTemperatureSensors_t;
typedef enum { NVML_CLOCK_GRAPHICS = 0, NVML_CLOCK_SM = 1, NVML_CLOCK_MEM = 2 } nvmlClockType_t;

typedef struct {
    unsigned int gpu;
    unsigned int memory;
} nvmlUtilization_t;

typedef struct {
    unsigned long long total;
    unsigned long long free;
    unsigned long long used;
} nvmlMemory_t;

typedef struct {
    char busId[32];
    unsigned int domain;
    unsigned int bus;
    unsigned int device;
    unsigned int pciDeviceId;     // (deviceId << 16) | vendorId
    unsigned int pciSubSystemId;
    char busIdLegacy[16];
} nvmlPciInfo_t;

// Function pointer types
typedef nvmlReturn_t (*pfn_nvmlInit)(void);
typedef nvmlReturn_t (*pfn_nvmlShutdown)(void);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetCount)(unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetHandleByIndex)(unsigned int, nvmlDevice_t*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetTemperature)(nvmlDevice_t, nvmlTemperatureSensors_t, unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetUtilizationRates)(nvmlDevice_t, nvmlUtilization_t*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetClockInfo)(nvmlDevice_t, nvmlClockType_t, unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetFanSpeed)(nvmlDevice_t, unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetPowerUsage)(nvmlDevice_t, unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetPciInfo)(nvmlDevice_t, nvmlPciInfo_t*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetMemoryInfo)(nvmlDevice_t, nvmlMemory_t*);


static struct {
    HMODULE hLib;
    pfn_nvmlInit                    Init;
    pfn_nvmlShutdown                Shutdown;
    pfn_nvmlDeviceGetCount          DeviceGetCount;
    pfn_nvmlDeviceGetHandleByIndex  DeviceGetHandleByIndex;
    pfn_nvmlDeviceGetTemperature    DeviceGetTemperature;
    pfn_nvmlDeviceGetUtilizationRates DeviceGetUtilizationRates;
    pfn_nvmlDeviceGetClockInfo      DeviceGetClockInfo;
    pfn_nvmlDeviceGetFanSpeed       DeviceGetFanSpeed;
    pfn_nvmlDeviceGetPowerUsage     DeviceGetPowerUsage;
    pfn_nvmlDeviceGetPciInfo        DeviceGetPciInfo;
    pfn_nvmlDeviceGetMemoryInfo     DeviceGetMemoryInfo;
    int initialized;
} nvml = { 0 };

static int load_nvml(void) {
    if (nvml.hLib) return nvml.initialized;

    // nvml.dll ships with NVIDIA drivers, typically in System32
    nvml.hLib = LoadLibraryA("nvml.dll");
    if (!nvml.hLib) return 0;

    nvml.Init = (pfn_nvmlInit)GetProcAddress(nvml.hLib, "nvmlInit_v2");
    nvml.Shutdown = (pfn_nvmlShutdown)GetProcAddress(nvml.hLib, "nvmlShutdown");
    nvml.DeviceGetCount = (pfn_nvmlDeviceGetCount)GetProcAddress(nvml.hLib, "nvmlDeviceGetCount_v2");
    nvml.DeviceGetHandleByIndex = (pfn_nvmlDeviceGetHandleByIndex)GetProcAddress(nvml.hLib, "nvmlDeviceGetHandleByIndex_v2");
    nvml.DeviceGetTemperature = (pfn_nvmlDeviceGetTemperature)GetProcAddress(nvml.hLib, "nvmlDeviceGetTemperature");
    nvml.DeviceGetUtilizationRates = (pfn_nvmlDeviceGetUtilizationRates)GetProcAddress(nvml.hLib, "nvmlDeviceGetUtilizationRates");
    nvml.DeviceGetClockInfo = (pfn_nvmlDeviceGetClockInfo)GetProcAddress(nvml.hLib, "nvmlDeviceGetClockInfo");
    nvml.DeviceGetFanSpeed = (pfn_nvmlDeviceGetFanSpeed)GetProcAddress(nvml.hLib, "nvmlDeviceGetFanSpeed");
    nvml.DeviceGetPowerUsage = (pfn_nvmlDeviceGetPowerUsage)GetProcAddress(nvml.hLib, "nvmlDeviceGetPowerUsage");
    nvml.DeviceGetPciInfo = (pfn_nvmlDeviceGetPciInfo)GetProcAddress(nvml.hLib, "nvmlDeviceGetPciInfo_v3");
    nvml.DeviceGetMemoryInfo = (pfn_nvmlDeviceGetMemoryInfo)GetProcAddress(nvml.hLib, "nvmlDeviceGetMemoryInfo");

    if (!nvml.Init || !nvml.DeviceGetCount || !nvml.DeviceGetHandleByIndex) {
        FreeLibrary(nvml.hLib);
        nvml.hLib = NULL;
        return 0;
    }

    if (nvml.Init() != NVML_SUCCESS) {
        FreeLibrary(nvml.hLib);
        nvml.hLib = NULL;
        return 0;
    }

    nvml.initialized = 1;
    return 1;
}

// Match an NVML device to a DXGI adapter by vendor+device ID
static nvmlDevice_t find_nvml_device_for(UINT vendor_id, UINT device_id) {
    if (!nvml.initialized || vendor_id != 0x10DE) return NULL;

    unsigned int count = 0;
    if (nvml.DeviceGetCount(&count) != NVML_SUCCESS) return NULL;

    for (unsigned int i = 0; i < count; i++) {
        nvmlDevice_t dev = NULL;
        if (nvml.DeviceGetHandleByIndex(i, &dev) != NVML_SUCCESS) continue;

        if (nvml.DeviceGetPciInfo) {
            nvmlPciInfo_t pci;
            if (nvml.DeviceGetPciInfo(dev, &pci) == NVML_SUCCESS) {
                // pciDeviceId = (deviceId << 16) | vendorId
                UINT nvml_device = (pci.pciDeviceId >> 16) & 0xFFFF;
                if (nvml_device == device_id) return dev;
            }
        }
    }

    // Fallback: if only one NVML device and one NVIDIA adapter, return it
    if (count == 1) {
        nvmlDevice_t dev = NULL;
        if (nvml.DeviceGetHandleByIndex(0, &dev) == NVML_SUCCESS) return dev;
    }

    return NULL;
}

static void fill_nvml_metrics(gpu_entry_t *g, nvmlDevice_t dev) {
    unsigned int val;

    if (nvml.DeviceGetTemperature && nvml.DeviceGetTemperature(dev, NVML_TEMPERATURE_GPU, &val) == NVML_SUCCESS) {
        g->temperature = (float)val;
    }

    if (nvml.DeviceGetUtilizationRates) {
        nvmlUtilization_t util;
        if (nvml.DeviceGetUtilizationRates(dev, &util) == NVML_SUCCESS) {
            g->gpu_usage = (float)util.gpu;
        }
    }

    if (nvml.DeviceGetClockInfo) {
        if (nvml.DeviceGetClockInfo(dev, NVML_CLOCK_GRAPHICS, &val) == NVML_SUCCESS) {
            g->core_clock_mhz = (int)val;
        }
        if (nvml.DeviceGetClockInfo(dev, NVML_CLOCK_MEM, &val) == NVML_SUCCESS) {
            g->memory_clock_mhz = (int)val;
        }
    }

    if (nvml.DeviceGetFanSpeed && nvml.DeviceGetFanSpeed(dev, &val) == NVML_SUCCESS) {
        g->fan_speed_pct = (float)val;
    }

    if (nvml.DeviceGetPowerUsage && nvml.DeviceGetPowerUsage(dev, &val) == NVML_SUCCESS) {
        g->power_draw_watts = (float)val / 1000.0f;  // milliwatts -> watts
    }

    // Try v2 memory API first (needed for RTX 50 series / newer drivers)
    {
        typedef struct {
            unsigned int version;
            unsigned long long total;
            unsigned long long reserved;
            unsigned long long free;
            unsigned long long used;
        } nvml_mem2_t;
        typedef nvmlReturn_t (*pfn_mem_v2)(nvmlDevice_t, nvml_mem2_t*);
        pfn_mem_v2 fn = (pfn_mem_v2)GetProcAddress(nvml.hLib, "nvmlDeviceGetMemoryInfo_v2");
        if (fn) {
            nvml_mem2_t m2;
            memset(&m2, 0, sizeof(m2));
            m2.version = (unsigned int)(sizeof(nvml_mem2_t)) | (2U << 24U);
            if (fn(dev, &m2) == NVML_SUCCESS && m2.used > 0) {
                g->memory_used = (long long)m2.used;
            }
        }
    }

    // Fall back to v1
    if (g->memory_used <= 0 && nvml.DeviceGetMemoryInfo) {
        nvmlMemory_t mem;
        memset(&mem, 0, sizeof(mem));
        if (nvml.DeviceGetMemoryInfo(dev, &mem) == NVML_SUCCESS) {
            if (mem.used > 0) {
                g->memory_used = (long long)mem.used;
            } else if (mem.total > 0 && mem.free <= mem.total) {
                g->memory_used = (long long)(mem.total - mem.free);
            }
        }
    }
}

// Try to read driver version from registry for a given adapter
static void read_driver_version(UINT vendor_id, UINT device_id, char *out, size_t out_size) {
    out[0] = '\0';

    const wchar_t *class_key = L"SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}";
    HKEY hk;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, class_key, 0, KEY_READ, &hk) != ERROR_SUCCESS) return;

    wchar_t subkey_name[32];
    for (DWORD i = 0; i < 64; i++) {
        DWORD name_len = sizeof(subkey_name) / sizeof(subkey_name[0]);
        if (RegEnumKeyExW(hk, i, subkey_name, &name_len, NULL, NULL, NULL, NULL) != ERROR_SUCCESS) break;

        HKEY hk_sub;
        if (RegOpenKeyExW(hk, subkey_name, 0, KEY_READ, &hk_sub) != ERROR_SUCCESS) continue;

        wchar_t match_buf[256];
        DWORD match_size = sizeof(match_buf);
        DWORD type;
        if (RegQueryValueExW(hk_sub, L"MatchingDeviceId", NULL, &type, (LPBYTE)match_buf, &match_size) == ERROR_SUCCESS
            && type == REG_SZ) {
            char match_utf8[256];
            WideCharToMultiByte(CP_UTF8, 0, match_buf, -1, match_utf8, sizeof(match_utf8), NULL, NULL);
            _strlwr(match_utf8);

            char ven_str[16], dev_str[16];
            snprintf(ven_str, sizeof(ven_str), "ven_%04x", vendor_id);
            snprintf(dev_str, sizeof(dev_str), "dev_%04x", device_id);

            if (strstr(match_utf8, ven_str) && strstr(match_utf8, dev_str)) {
                wchar_t ver_buf[128];
                DWORD ver_size = sizeof(ver_buf);
                if (RegQueryValueExW(hk_sub, L"DriverVersion", NULL, &type, (LPBYTE)ver_buf, &ver_size) == ERROR_SUCCESS
                    && type == REG_SZ) {
                    WideCharToMultiByte(CP_UTF8, 0, ver_buf, -1, out, (int)out_size, NULL, NULL);
                }
                RegCloseKey(hk_sub);
                break;
            }
        }
        RegCloseKey(hk_sub);
    }
    RegCloseKey(hk);
}

static void refresh_gpus(void) {
    g_gpu_count = 0;

    // Try to load NVML for live metrics
    load_nvml();

    // Create DXGI factory
    IDXGIFactory1 *factory = NULL;
    HRESULT hr = CreateDXGIFactory1(&IID_IDXGIFactory1_local, (void **)&factory);
    if (FAILED(hr) || !factory) return;

    IDXGIAdapter1 *adapter = NULL;
    for (UINT i = 0; i < MAX_GPUS; i++) {
        hr = factory->lpVtbl->EnumAdapters1(factory, i, &adapter);
        if (hr == DXGI_ERROR_NOT_FOUND || FAILED(hr)) break;

        DXGI_ADAPTER_DESC1 desc;
        hr = adapter->lpVtbl->GetDesc1(adapter, &desc);
        if (FAILED(hr)) {
            adapter->lpVtbl->Release(adapter);
            continue;
        }

        // Skip software adapters
        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) {
            adapter->lpVtbl->Release(adapter);
            continue;
        }

        gpu_entry_t *g = &g_gpus[g_gpu_count];
        memset(g, 0, sizeof(*g));

        // Initialize optional fields to unavailable
        g->temperature = NAN;
        g->gpu_usage = NAN;
        g->memory_used = -1;
        g->core_clock_mhz = -1;
        g->memory_clock_mhz = -1;
        g->fan_speed_pct = NAN;
        g->power_draw_watts = NAN;

        // Name
        char *name = wchar_to_utf8(desc.Description);
        if (name) {
            strncpy(g->name, name, sizeof(g->name) - 1);
            free(name);
        }

        g->vendor_id = desc.VendorId;
        g->device_id = desc.DeviceId;
        g->dedicated_video_memory = desc.DedicatedVideoMemory;
        g->dedicated_system_memory = desc.DedicatedSystemMemory;
        g->shared_system_memory = desc.SharedSystemMemory;

        // Driver version from registry
        read_driver_version(desc.VendorId, desc.DeviceId, g->driver_version, sizeof(g->driver_version));

        // NVML live metrics for NVIDIA GPUs (includes system-wide VRAM usage)
        nvmlDevice_t nvml_dev = find_nvml_device_for(desc.VendorId, desc.DeviceId);
        if (nvml_dev) {
            fill_nvml_metrics(g, nvml_dev);
        }

        // Fallback: VRAM usage via IDXGIAdapter3 if NVML didn't provide it
        if (g->memory_used < 0) {
            IDXGIAdapter3 *adapter3 = NULL;
            hr = adapter->lpVtbl->QueryInterface(adapter, &IID_IDXGIAdapter3_local, (void**)&adapter3);
            if (SUCCEEDED(hr) && adapter3) {
                DXGI_QUERY_VIDEO_MEMORY_INFO mem_info;
                hr = adapter3->lpVtbl->QueryVideoMemoryInfo(adapter3, 0, DXGI_MEMORY_SEGMENT_GROUP_LOCAL, &mem_info);
                if (SUCCEEDED(hr) && mem_info.CurrentUsage > 0) {
                    g->memory_used = (long long)mem_info.CurrentUsage;
                }
                adapter3->lpVtbl->Release(adapter3);
            }
        }

        adapter->lpVtbl->Release(adapter);
        g_gpu_count++;
    }

    factory->lpVtbl->Release(factory);
}

// --- JNI exports ---

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuCount(
    JNIEnv *env, jclass clazz) {
    refresh_gpus();
    return g_gpu_count;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuNames(
    JNIEnv *env, jclass clazz) {
    const char *names[MAX_GPUS];
    for (int i = 0; i < g_gpu_count; i++) names[i] = g_gpus[i].name;
    return to_string_array(env, names, g_gpu_count);
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuVendorIds(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].vendor_id;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDeviceIds(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].device_id;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDedicatedVideoMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_video_memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDedicatedSystemMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_system_memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuSharedSystemMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].shared_system_memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDriverVersions(
    JNIEnv *env, jclass clazz) {
    const char *versions[MAX_GPUS];
    for (int i = 0; i < g_gpu_count; i++) versions[i] = g_gpus[i].driver_version;
    return to_string_array(env, versions, g_gpu_count);
}

// Live metrics JNI exports

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuTemperatures(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_gpu_count);
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].temperature;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuUsages(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_gpu_count);
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].gpu_usage;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuMemoryUsed(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].memory_used;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuCoreClocks(
    JNIEnv *env, jclass clazz) {
    jintArray arr = (*env)->NewIntArray(env, g_gpu_count);
    jint *vals = (jint *)malloc(g_gpu_count * sizeof(jint));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].core_clock_mhz;
    (*env)->SetIntArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuMemoryClocks(
    JNIEnv *env, jclass clazz) {
    jintArray arr = (*env)->NewIntArray(env, g_gpu_count);
    jint *vals = (jint *)malloc(g_gpu_count * sizeof(jint));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].memory_clock_mhz;
    (*env)->SetIntArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuFanSpeeds(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_gpu_count);
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].fan_speed_pct;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuPowerDraws(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_gpu_count);
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].power_draw_watts;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}
