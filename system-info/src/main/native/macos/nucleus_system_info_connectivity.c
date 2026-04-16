// Network connectivity stub for macOS (not yet implemented).

#include "nucleus_system_info_common.h"

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeIsNetworkConnected(
    JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGetMeteredStatus(
    JNIEnv *env, jclass clazz) {
    return 0; // UNKNOWN
}
