// Temperature sensor information via multiple sources (cascading fallback):
// 1. Libre Hardware Monitor WMI (root\LibreHardwareMonitor\Sensor)
// 2. Open Hardware Monitor WMI  (root\OpenHardwareMonitor\Sensor)
// 3. WMI thermal zone perf data (Win32_PerfFormattedData, no admin needed)
// 4. ACPI thermal zones WMI     (root\WMI\MSAcpi_ThermalZoneTemperature, requires admin)
//
// Sources 1 & 2 require LHM/OHM to be running — they expose rich per-core CPU,
// GPU, and motherboard temperature data via WMI without admin privileges.
// Source 3 uses WMI performance data classes (English names, locale-independent,
// no admin) for ACPI thermal zones.
// Source 4 is the legacy WMI fallback: admin-only, often empty or unreliable.

#include "nucleus_system_info_common.h"
#include <math.h>

// WMI COM interfaces
#include <objbase.h>
#include <wbemcli.h>

#define MAX_COMPONENTS 128

typedef struct {
    char label[128];
    float temperature; // Celsius, NAN if unavailable
    float max_temp;    // NAN if unavailable
    float critical;    // NAN if unavailable
} component_entry_t;

static component_entry_t g_components[MAX_COMPONENTS];
static int g_component_count = 0;

// Convert tenths-of-Kelvin to Celsius (for ACPI thermal zones)
static float kelvin_tenths_to_celsius(long val) {
    return (float)val / 10.0f - 273.15f;
}

// Helper: connect to a WMI namespace. Returns IWbemServices* or NULL.
static IWbemServices *wmi_connect(const wchar_t *ns_path) {
    IWbemLocator *locator = NULL;
    HRESULT hr = CoCreateInstance(&CLSID_WbemLocator, NULL, CLSCTX_INPROC_SERVER,
        &IID_IWbemLocator, (void **)&locator);
    if (FAILED(hr)) return NULL;

    IWbemServices *services = NULL;
    BSTR ns = SysAllocString(ns_path);
    hr = locator->lpVtbl->ConnectServer(locator, ns, NULL, NULL, NULL, 0, NULL, NULL, &services);
    SysFreeString(ns);
    locator->lpVtbl->Release(locator);
    if (FAILED(hr)) return NULL;

    CoSetProxyBlanket((IUnknown *)services, RPC_C_AUTHN_WINNT, RPC_C_AUTHZ_NONE, NULL,
        RPC_C_AUTHN_LEVEL_CALL, RPC_C_IMP_LEVEL_IMPERSONATE, NULL, EOAC_NONE);
    return services;
}

// Helper: execute a WQL query and return the enumerator.
static IEnumWbemClassObject *wmi_query(IWbemServices *services, const wchar_t *wql_query) {
    IEnumWbemClassObject *enumerator = NULL;
    BSTR wql = SysAllocString(L"WQL");
    BSTR query = SysAllocString(wql_query);
    HRESULT hr = services->lpVtbl->ExecQuery(services, wql, query,
        WBEM_FLAG_FORWARD_ONLY | WBEM_FLAG_RETURN_IMMEDIATELY, NULL, &enumerator);
    SysFreeString(wql);
    SysFreeString(query);
    if (FAILED(hr)) return NULL;
    return enumerator;
}

// Helper: extract a float from a VARIANT
static float variant_to_float(VARIANT *v) {
    switch (v->vt) {
        case VT_R4:  return v->fltVal;
        case VT_R8:  return (float)v->dblVal;
        case VT_I4:  return (float)v->lVal;
        case VT_UI4: return (float)v->ulVal;
        case VT_I2:  return (float)v->iVal;
        case VT_UI2: return (float)v->uiVal;
        default:     return NAN;
    }
}

// Helper: extract a long from a VARIANT
static long variant_to_long(VARIANT *v) {
    switch (v->vt) {
        case VT_I4:  return v->lVal;
        case VT_UI4: return (long)v->ulVal;
        case VT_I8:  return (long)v->llVal;
        case VT_UI8: return (long)v->ullVal;
        case VT_I2:  return (long)v->iVal;
        case VT_UI2: return (long)v->uiVal;
        default:     return 0;
    }
}

// ============================================================================
// Source 1 & 2: Libre Hardware Monitor / Open Hardware Monitor WMI
// ============================================================================

static int query_hardware_monitor(const wchar_t *ns_path) {
    IWbemServices *services = wmi_connect(ns_path);
    if (!services) return 0;

    IEnumWbemClassObject *enumerator = wmi_query(services,
        L"SELECT Name, Value, Min, Max FROM Sensor WHERE SensorType='Temperature'");
    if (!enumerator) {
        services->lpVtbl->Release(services);
        return 0;
    }

    int found = 0;
    IWbemClassObject *obj = NULL;
    ULONG returned = 0;

    while (g_component_count < MAX_COMPONENTS) {
        HRESULT hr = enumerator->lpVtbl->Next(enumerator, 3000, 1, &obj, &returned);
        if (hr != S_OK || returned == 0) break;

        component_entry_t *c = &g_components[g_component_count];
        c->temperature = NAN;
        c->max_temp = NAN;
        c->critical = NAN;
        strcpy(c->label, "Temperature");

        VARIANT var;

        // Name
        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, L"Name", 0, &var, NULL, NULL);
        if (SUCCEEDED(hr) && var.vt == VT_BSTR && var.bstrVal) {
            char *name = wchar_to_utf8(var.bstrVal);
            if (name && name[0]) {
                strncpy(c->label, name, sizeof(c->label) - 1);
                c->label[sizeof(c->label) - 1] = '\0';
                free(name);
            } else {
                if (name) free(name);
            }
        }
        VariantClear(&var);

        // Value (current temperature in Celsius)
        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, L"Value", 0, &var, NULL, NULL);
        if (SUCCEEDED(hr)) c->temperature = variant_to_float(&var);
        VariantClear(&var);

        // Max (highest recorded since LHM started)
        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, L"Max", 0, &var, NULL, NULL);
        if (SUCCEEDED(hr)) c->max_temp = variant_to_float(&var);
        VariantClear(&var);

        obj->lpVtbl->Release(obj);

        if (!isnan(c->temperature)) {
            g_component_count++;
            found++;
        }
    }

    enumerator->lpVtbl->Release(enumerator);
    services->lpVtbl->Release(services);
    return found;
}

// ============================================================================
// Source 3: WMI thermal zone performance data (no admin needed)
// ============================================================================
// Win32_PerfFormattedData_Counters_ThermalZoneInformation uses English property
// names regardless of OS locale. No admin privileges needed.
//
// Key properties:
//   Name                    — thermal zone instance e.g. "\_TZ.TZ00"
//   Temperature             — temperature in Kelvin (integer)
//   HighPrecisionTemperature — temperature in tenths of Kelvin

static int query_wmi_thermal_perf_data(void) {
    IWbemServices *services = wmi_connect(L"root\\cimv2");
    if (!services) return 0;

    IEnumWbemClassObject *enumerator = wmi_query(services,
        L"SELECT Name, Temperature, HighPrecisionTemperature "
        L"FROM Win32_PerfFormattedData_Counters_ThermalZoneInformation");
    if (!enumerator) {
        services->lpVtbl->Release(services);
        return 0;
    }

    int found = 0;
    IWbemClassObject *obj = NULL;
    ULONG returned = 0;

    while (g_component_count < MAX_COMPONENTS) {
        HRESULT hr = enumerator->lpVtbl->Next(enumerator, 3000, 1, &obj, &returned);
        if (hr != S_OK || returned == 0) break;

        component_entry_t *c = &g_components[g_component_count];
        c->temperature = NAN;
        c->max_temp = NAN;
        c->critical = NAN;
        strcpy(c->label, "Thermal Zone");

        VARIANT var;

        // Name
        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, L"Name", 0, &var, NULL, NULL);
        if (SUCCEEDED(hr) && var.vt == VT_BSTR && var.bstrVal) {
            char *name = wchar_to_utf8(var.bstrVal);
            if (name && name[0]) {
                // Clean up the label
                if (strstr(name, "_TZ") || strstr(name, "TZ") || strstr(name, "\\")) {
                    snprintf(c->label, sizeof(c->label), "Thermal Zone %s", name);
                } else {
                    strncpy(c->label, name, sizeof(c->label) - 1);
                    c->label[sizeof(c->label) - 1] = '\0';
                }
                free(name);
            } else {
                if (name) free(name);
            }
        }
        VariantClear(&var);

        // Try HighPrecisionTemperature first (tenths of Kelvin → Celsius)
        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, L"HighPrecisionTemperature", 0, &var, NULL, NULL);
        if (SUCCEEDED(hr)) {
            long hp_val = variant_to_long(&var);
            if (hp_val > 0) {
                c->temperature = (float)hp_val / 10.0f - 273.15f;
            }
        }
        VariantClear(&var);

        // Fallback to Temperature (Kelvin integer)
        if (isnan(c->temperature)) {
            VariantInit(&var);
            hr = obj->lpVtbl->Get(obj, L"Temperature", 0, &var, NULL, NULL);
            if (SUCCEEDED(hr)) {
                long temp_k = variant_to_long(&var);
                if (temp_k > 0) {
                    c->temperature = (float)temp_k - 273.15f;
                }
            }
            VariantClear(&var);
        }

        obj->lpVtbl->Release(obj);

        // Sanity check: skip obviously wrong values
        if (!isnan(c->temperature) && c->temperature > -40.0f && c->temperature < 150.0f) {
            g_component_count++;
            found++;
        }
    }

    enumerator->lpVtbl->Release(enumerator);
    services->lpVtbl->Release(services);
    return found;
}

// ============================================================================
// Source 4: ACPI thermal zones WMI (MSAcpi_ThermalZoneTemperature)
// ============================================================================

static int query_acpi_thermal_zones(void) {
    IWbemServices *services = wmi_connect(L"root\\WMI");
    if (!services) return 0;

    IEnumWbemClassObject *enumerator = wmi_query(services,
        L"SELECT InstanceName, CurrentTemperature, CriticalTripPoint FROM MSAcpi_ThermalZoneTemperature");
    if (!enumerator) {
        services->lpVtbl->Release(services);
        return 0;
    }

    int found = 0;
    IWbemClassObject *obj = NULL;
    ULONG returned = 0;

    while (g_component_count < MAX_COMPONENTS) {
        HRESULT hr = enumerator->lpVtbl->Next(enumerator, 3000, 1, &obj, &returned);
        if (hr != S_OK || returned == 0) break;

        component_entry_t *c = &g_components[g_component_count];
        c->temperature = NAN;
        c->max_temp = NAN;
        c->critical = NAN;
        strcpy(c->label, "ACPI Thermal Zone");

        VARIANT var;

        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, L"InstanceName", 0, &var, NULL, NULL);
        if (SUCCEEDED(hr) && var.vt == VT_BSTR && var.bstrVal) {
            char *name = wchar_to_utf8(var.bstrVal);
            if (name && name[0]) {
                strncpy(c->label, name, sizeof(c->label) - 1);
                c->label[sizeof(c->label) - 1] = '\0';
                free(name);
            } else {
                if (name) free(name);
            }
        }
        VariantClear(&var);

        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, L"CurrentTemperature", 0, &var, NULL, NULL);
        if (SUCCEEDED(hr) && (var.vt == VT_I4 || var.vt == VT_UI4)) {
            c->temperature = kelvin_tenths_to_celsius(var.lVal);
        }
        VariantClear(&var);

        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, L"CriticalTripPoint", 0, &var, NULL, NULL);
        if (SUCCEEDED(hr) && (var.vt == VT_I4 || var.vt == VT_UI4)) {
            c->critical = kelvin_tenths_to_celsius(var.lVal);
        }
        VariantClear(&var);

        obj->lpVtbl->Release(obj);

        if (!isnan(c->temperature)) {
            g_component_count++;
            found++;
        }
    }

    enumerator->lpVtbl->Release(enumerator);
    services->lpVtbl->Release(services);
    return found;
}

// ============================================================================
// Main refresh — cascading fallback
// ============================================================================

static void refresh_components(void) {
    g_component_count = 0;

    HRESULT com_hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    int we_initialized_com = (com_hr == S_OK);
    if (FAILED(com_hr) && com_hr != RPC_E_CHANGED_MODE) return;

    // 1. Libre Hardware Monitor
    int found = query_hardware_monitor(L"root\\LibreHardwareMonitor");

    // 2. Open Hardware Monitor
    if (found == 0)
        found = query_hardware_monitor(L"root\\OpenHardwareMonitor");

    // 3. WMI thermal zone perf data (no admin, locale-independent)
    if (found == 0)
        found = query_wmi_thermal_perf_data();

    // 4. ACPI thermal zones via WMI (needs admin)
    if (found == 0)
        query_acpi_thermal_zones();

    if (we_initialized_com) CoUninitialize();
}

// ============================================================================
// JNI exports
// ============================================================================

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentCount(
    JNIEnv *env, jclass clazz) {
    refresh_components();
    return g_component_count;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentLabels(
    JNIEnv *env, jclass clazz) {
    const char *labels[MAX_COMPONENTS];
    for (int i = 0; i < g_component_count; i++) labels[i] = g_components[i].label;
    return to_string_array(env, labels, g_component_count);
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentTemperatures(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_component_count);
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].temperature;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_component_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentMaxTemperatures(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_component_count);
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].max_temp;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_component_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentCriticalTemperatures(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_component_count);
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].critical;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_component_count, vals);
    free(vals);
    return arr;
}
