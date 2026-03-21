package io.github.kdroidfilter.nucleus.taskbarprogress.linux

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_taskbar_progress"

internal object NativeLinuxTaskbarBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxTaskbarBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeSetProgress(
        desktopFilename: String,
        completed: Long,
        total: Long,
    ): Int

    @JvmStatic
    external fun nativeSetProgressState(
        desktopFilename: String,
        flags: Int,
    ): Int

    @JvmStatic
    external fun nativeSetUrgent(
        desktopFilename: String,
        urgent: Boolean,
    ): Int
}
