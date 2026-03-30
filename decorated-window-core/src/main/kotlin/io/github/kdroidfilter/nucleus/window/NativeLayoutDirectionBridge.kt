package io.github.kdroidfilter.nucleus.window

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_layout_direction"

// Known RTL languages used as fallback when the native library is unavailable
private val RTL_LANGUAGES = setOf("ar", "he", "fa", "ur", "yi", "ps", "sd", "ckb", "ku", "ug", "syr", "dv")

internal object NativeLayoutDirectionBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLayoutDirectionBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeIsRTL(): Boolean

    fun isSystemRTL(): Boolean =
        if (loaded) {
            nativeIsRTL()
        } else {
            val language = System.getProperty("user.language") ?: return false
            language in RTL_LANGUAGES
        }
}
