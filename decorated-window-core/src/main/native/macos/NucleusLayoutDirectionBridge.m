#import <Cocoa/Cocoa.h>
#include <jni.h>

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_NativeLayoutDirectionBridge_nativeIsRTL(
    JNIEnv *env, jclass clazz) {
    @autoreleasepool {
        return [NSApplication sharedApplication].userInterfaceLayoutDirection
                   == NSUserInterfaceLayoutDirectionRightToLeft
               ? JNI_TRUE
               : JNI_FALSE;
    }
}
