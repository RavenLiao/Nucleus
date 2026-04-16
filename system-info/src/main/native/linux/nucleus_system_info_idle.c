// Idle time stub for Linux (not yet implemented).

#include "nucleus_system_info_common.h"

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeIdleTimeSeconds(
    JNIEnv *env, jclass clazz) {
    return (jlong)-1;
}
