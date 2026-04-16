// Idle time stub for Windows (not yet implemented).

#include "nucleus_system_info_common.h"

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeIdleTimeSeconds(
    JNIEnv *env, jclass clazz) {
    return (jlong)-1;
}
