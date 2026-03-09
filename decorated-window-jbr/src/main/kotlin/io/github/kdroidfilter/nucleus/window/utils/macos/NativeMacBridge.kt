package io.github.kdroidfilter.nucleus.window.utils.macos

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_macos"

internal object NativeMacBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMacBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeUpdateColors(nsWindowPtr: Long)

    @JvmStatic
    external fun nativeUpdateFullScreenButtons(nsWindowPtr: Long)
}
