package io.github.kdroidfilter.nucleus.window

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_layout_direction"

internal object NativeLayoutDirectionBridge {
    init {
        NativeLibraryLoader.load(LIBRARY_NAME, NativeLayoutDirectionBridge::class.java)
    }

    @JvmStatic
    external fun nativeIsRTL(): Boolean

    /**
     * Returns the GNOME `button-layout` GSettings value
     * (e.g. `"appmenu:close"` or `"close,minimize,maximize:"`),
     * or `null` if the schema is unavailable (non-GNOME desktop).
     */
    @JvmStatic
    external fun nativeGetButtonLayout(): String?
}
