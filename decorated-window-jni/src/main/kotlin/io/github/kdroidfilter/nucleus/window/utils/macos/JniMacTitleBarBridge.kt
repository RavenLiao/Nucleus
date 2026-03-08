package io.github.kdroidfilter.nucleus.window.utils.macos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

internal object JniMacTitleBarBridge {
    private val logger = Logger.getLogger(JniMacTitleBarBridge::class.java.simpleName)

    @Volatile
    private var loaded = false

    init {
        loadNativeLibrary()
    }

    private fun loadNativeLibrary() {
        if (loaded) return

        // Try system library path first (packaged app)
        try {
            System.loadLibrary("nucleus_macos_jni")
            loaded = true
            return
        } catch (_: UnsatisfiedLinkError) {
            // Fall through to JAR extraction
        }

        // Fallback: extract from JAR resources
        @Suppress("TooGenericExceptionCaught")
        try {
            val arch =
                System.getProperty("os.arch").let {
                    if (it == "aarch64" || it == "arm64") "aarch64" else "x64"
                }
            val resourcePath = "/nucleus/native/darwin-$arch/libnucleus_macos_jni.dylib"
            val stream =
                JniMacTitleBarBridge::class.java
                    .getResourceAsStream(resourcePath)
                    ?: throw UnsatisfiedLinkError("Native library not found in JAR at $resourcePath")
            val tempDir = Files.createTempDirectory("nucleus-jni-native")
            val tempLib = tempDir.resolve("libnucleus_macos_jni.dylib")
            stream.use { Files.copy(it, tempLib) }
            tempLib.toFile().deleteOnExit()
            tempDir.toFile().deleteOnExit()
            System.load(tempLib.toAbsolutePath().toString())
            loaded = true
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to load nucleus_macos_jni native library", e)
        }
    }

    val isLoaded: Boolean get() = loaded

    // ── Menu bar offset (event-driven via native NSEvent monitor) ──

    private val menuBarOffsetFlows = ConcurrentHashMap<Long, MutableStateFlow<Float>>()
    private val emptyFlow = MutableStateFlow(0f)

    // Returns a StateFlow that emits the current menu bar offset for
    // the given window. Updated by the native event monitor callback.
    fun menuBarOffsetFlow(nsWindowPtr: Long): StateFlow<Float> {
        if (nsWindowPtr == 0L) return emptyFlow
        return menuBarOffsetFlows.getOrPut(nsWindowPtr) { MutableStateFlow(0f) }
    }

    fun removeMenuBarOffsetFlow(nsWindowPtr: Long) {
        menuBarOffsetFlows.remove(nsWindowPtr)
    }

    // Called from native (macOS main thread) when the menu bar offset
    // changes. MutableStateFlow.value is thread-safe.
    @JvmStatic
    fun onMenuBarOffsetChanged(
        nsWindowPtr: Long,
        offset: Float,
    ) {
        menuBarOffsetFlows.getOrPut(nsWindowPtr) { MutableStateFlow(0f) }.value = offset
    }

    // ── JNI methods ──

    // Sets up (or updates) the custom title bar and repositions traffic light buttons.
    // heightPt: title bar height in NSPoints (= dp on macOS).
    // Returns the left inset in points to reserve space for the traffic lights.
    @JvmStatic
    external fun nativeApplyTitleBar(
        nsWindowPtr: Long,
        heightPt: Float,
    ): Float

    // Removes all custom constraints, fullscreen observer, and restores AppKit defaults.
    @JvmStatic
    external fun nativeResetTitleBar(nsWindowPtr: Long)

    // Updates the position of the replacement fullscreen buttons (called on layout passes).
    @JvmStatic
    external fun nativeUpdateFullScreenButtons(nsWindowPtr: Long)

    // Performs the macOS title bar double-click action (zoom or minimize)
    // respecting the user's AppleActionOnDoubleClick system preference.
    @JvmStatic
    external fun nativePerformTitleBarDoubleClickAction(nsWindowPtr: Long)

    // Initiates a native window drag using the saved mouseDown event.
    // Called from Compose when an unconsumed drag is detected in the title bar.
    @JvmStatic
    external fun nativeStartWindowDrag(nsWindowPtr: Long)

    // Extracts the native NSWindow pointer from an AWT Window via JNI.
    // JNI bypasses module access checks, so this works in GraalVM native-image
    // where Kotlin reflection cannot access sun.awt.AWTAccessor.
    @JvmStatic
    external fun nativeGetNSWindowPtr(awtWindow: java.awt.Window): Long

    // Stores the newFullscreenControls flag on the NSWindow.
    // When enabled, the title bar and traffic-light buttons are pushed down
    // by the menu bar height when the auto-hidden menu bar appears in fullscreen.
    @JvmStatic
    external fun nativeSetNewFullscreenControls(
        nsWindowPtr: Long,
        enabled: Boolean,
    )

    // Returns the current menu bar offset in points (reads the stored value).
    @JvmStatic
    external fun nativeGetMenuBarOffset(nsWindowPtr: Long): Float

    // Stores the current menu bar offset (in points) and repositions
    // the native traffic-light buttons to match the Compose title bar.
    @JvmStatic
    external fun nativeSetMenuBarOffset(
        nsWindowPtr: Long,
        offsetPt: Float,
    )

    // Installs a native NSEvent local monitor that detects menu bar
    // visibility changes and calls onMenuBarOffsetChanged via JNI.
    @JvmStatic
    external fun nativeInstallMenuBarMonitor(nsWindowPtr: Long)

    // Removes the native event monitor installed by nativeInstallMenuBarMonitor.
    @JvmStatic
    external fun nativeRemoveMenuBarMonitor(nsWindowPtr: Long)

    // Installs or removes an invisible NSToolbar to trigger the macOS 26pt
    // corner radius. When disabled, the window uses the standard ~10pt radius.
    @JvmStatic
    external fun nativeSetLargeCornerRadius(
        nsWindowPtr: Long,
        enabled: Boolean,
    )
}
