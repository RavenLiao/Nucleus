@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.nucleus.notification.windows

import io.github.kdroidfilter.nucleus.core.runtime.ExecutableRuntime
import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
import java.util.logging.Logger

/**
 * Kotlin API for Windows Toast Notifications (WinRT).
 *
 * Provides full access to the Windows toast notification system via JNI.
 * All methods are no-op on non-Windows platforms (check [isAvailable]).
 *
 * Call [initialize] once before showing any notification. The AUMID is
 * resolved automatically based on the packaging type:
 * - **APPX/MSIX**: empty AUMID — Windows uses the package identity.
 * - **Other (EXE, MSI, dev, etc.)**: uses [NucleusApp.appId] or you can
 *   pass an explicit AUMID override.
 *
 * Thread-safe singleton.
 */
object WindowsNotificationCenter {
    private val logger = Logger.getLogger(WindowsNotificationCenter::class.java.simpleName)
    private var initialized = false

    /** Whether the native library is loaded and functional on this platform. */
    val isAvailable: Boolean get() = NativeWindowsNotificationBridge.isLoaded

    /**
     * Initialize the notification subsystem.
     *
     * The AUMID is resolved automatically:
     * - **APPX/MSIX**: pass `null` or omit — Windows uses the package identity.
     * - **Unpackaged apps**: pass `null` to auto-derive from [NucleusApp.appId],
     *   or provide an explicit AUMID string.
     *
     * The native code calls `SetCurrentProcessExplicitAppUserModelID` for
     * unpackaged apps so that Windows can identify the process.
     *
     * @param aumid Explicit AUMID override, or `null` to auto-resolve.
     * @return true if initialization succeeded.
     */
    fun initialize(aumid: String? = null): Boolean {
        if (!isAvailable) {
            logger.warning("Windows notification native library not available")
            return false
        }
        val isAppx = ExecutableRuntime.isAppX()
        val resolvedAumid = resolveAumid(aumid, isAppx)
        logger.fine("Initializing with AUMID: '${resolvedAumid.ifEmpty { "<package-identity>" }}' isAppx=$isAppx")
        initialized = NativeWindowsNotificationBridge.nativeInitialize(resolvedAumid, isAppx)
        if (!initialized) {
            logger.warning("Failed to initialize Windows notification subsystem (AUMID='$resolvedAumid')")
        }
        return initialized
    }

    // -- Show --

    /**
     * Show a toast notification.
     *
     * @param content The toast content model.
     * @param tag Notification tag for identification/updates (max 16 chars).
     * @param group Notification group (max 16 chars).
     * @param expiresOnReboot Remove the notification on system reboot.
     * @param expirationTimeMs Auto-remove after this duration in ms (0 = no expiration).
     * @param suppressPopup Send directly to Action Center without popup.
     * @param callback Optional callback with error string (null on success).
     */
    fun show(
        content: ToastContent,
        tag: String = "",
        group: String = "",
        expiresOnReboot: Boolean = false,
        expirationTimeMs: Long = 0,
        suppressPopup: Boolean = false,
        initialData: ToastNotificationData? = null,
        callback: ((error: String?) -> Unit)? = null,
    ) {
        if (!ensureReady(callback)) return

        val xml = ToastXmlBuilder.buildXml(content)
        val noOpCallback: (String?) -> Unit = {}
        val id = NativeWindowsNotificationBridge.registerCallback(callback ?: noOpCallback)
        NativeWindowsNotificationBridge.nativeShowToast(
            xml = xml,
            tag = tag,
            group = group,
            expiresOnReboot = expiresOnReboot,
            expirationTimeMs = expirationTimeMs,
            suppressPopup = suppressPopup,
            dataKeys = initialData?.values?.keys?.toTypedArray() ?: emptyArray(),
            dataValues = initialData?.values?.values?.toTypedArray() ?: emptyArray(),
            dataSequenceNumber = initialData?.sequenceNumber ?: 0,
            callbackId = id,
        )
    }

    /**
     * Show a toast from raw XML.
     *
     * Use this for advanced scenarios where you build the XML manually.
     */
    fun showFromXml(
        xml: String,
        tag: String = "",
        group: String = "",
        expiresOnReboot: Boolean = false,
        expirationTimeMs: Long = 0,
        suppressPopup: Boolean = false,
        initialData: ToastNotificationData? = null,
        callback: ((error: String?) -> Unit)? = null,
    ) {
        if (!ensureReady(callback)) return

        val noOpCallback: (String?) -> Unit = {}
        val id = NativeWindowsNotificationBridge.registerCallback(callback ?: noOpCallback)
        NativeWindowsNotificationBridge.nativeShowToast(
            xml = xml,
            tag = tag,
            group = group,
            expiresOnReboot = expiresOnReboot,
            expirationTimeMs = expirationTimeMs,
            suppressPopup = suppressPopup,
            dataKeys = initialData?.values?.keys?.toTypedArray() ?: emptyArray(),
            dataValues = initialData?.values?.values?.toTypedArray() ?: emptyArray(),
            dataSequenceNumber = initialData?.sequenceNumber ?: 0,
            callbackId = id,
        )
    }

    /**
     * Convenience: show a simple text-only toast notification.
     *
     * @param title The notification title.
     * @param body The notification body text.
     * @param body2 Optional second line of body text.
     * @param tag Notification tag for identification.
     * @param group Notification group.
     * @param callback Optional callback with error string (null on success).
     */
    fun showSimple(
        title: String,
        body: String = "",
        body2: String = "",
        tag: String = "",
        group: String = "",
        callback: ((error: String?) -> Unit)? = null,
    ) {
        val children = mutableListOf<ToastVisualChild>()
        children.add(AdaptiveText(title))
        if (body.isNotEmpty()) children.add(AdaptiveText(body))
        if (body2.isNotEmpty()) children.add(AdaptiveText(body2))

        val content =
            ToastContent(
                visual =
                    ToastVisual(
                        binding = ToastBindingGeneric(children = children),
                    ),
            )
        show(content, tag = tag, group = group, callback = callback)
    }

    // -- Update (progress bars via data binding) --

    /**
     * Update data-bound fields of an existing toast without replacing it.
     *
     * Primarily used for progress bar updates.
     *
     * @param tag Notification tag (must match the original).
     * @param group Notification group (must match the original).
     * @param data The new data values.
     * @param callback Optional callback with error string (null on success).
     */
    fun update(
        tag: String,
        group: String = "",
        data: ToastNotificationData,
        callback: ((error: String?) -> Unit)? = null,
    ) {
        if (!ensureReady(callback)) return

        val noOpCallback: (String?) -> Unit = {}
        val id = NativeWindowsNotificationBridge.registerCallback(callback ?: noOpCallback)
        NativeWindowsNotificationBridge.nativeUpdateToast(
            tag = tag,
            group = group,
            sequenceNumber = data.sequenceNumber,
            keys = data.values.keys.toTypedArray(),
            values = data.values.values.toTypedArray(),
            callbackId = id,
        )
    }

    // -- Remove --

    /** Remove a specific toast from Action Center. */
    fun remove(
        tag: String,
        group: String = "",
    ) {
        if (!ensureReady(null)) return
        NativeWindowsNotificationBridge.nativeRemoveToast(tag, group)
    }

    /** Remove all toasts in a group from Action Center. */
    fun removeGroup(group: String) {
        if (!ensureReady(null)) return
        NativeWindowsNotificationBridge.nativeRemoveGroupToasts(group)
    }

    /** Remove all toasts from Action Center for this app. */
    fun clearAll() {
        if (!ensureReady(null)) return
        NativeWindowsNotificationBridge.nativeClearAllToasts()
    }

    // -- History --

    /**
     * Get the list of notifications currently in Action Center.
     *
     * @param callback Receives the list of history entries and an optional error.
     */
    fun getHistory(callback: (List<HistoryEntry>, String?) -> Unit) {
        if (!ensureReady(null)) {
            callback(emptyList(), "Not available")
            return
        }
        val id = NativeWindowsNotificationBridge.registerCallback(callback)
        NativeWindowsNotificationBridge.nativeGetHistory(id)
    }

    // -- Listeners --

    /** Register a listener for toast lifecycle events (activated, dismissed, failed). */
    fun addListener(listener: ToastNotificationListener) {
        NativeWindowsNotificationBridge.addListener(listener)
    }

    /** Unregister a toast lifecycle listener. */
    fun removeListener(listener: ToastNotificationListener) {
        NativeWindowsNotificationBridge.removeListener(listener)
    }

    // -- Lifecycle --

    /**
     * Clean up native resources.
     * Call on app shutdown or when notifications are no longer needed.
     */
    fun uninitialize() {
        if (!isAvailable || !initialized) return
        NativeWindowsNotificationBridge.nativeUninitialize()
        initialized = false
    }

    // -- Internal --

    private fun resolveAumid(
        explicit: String?,
        isAppx: Boolean,
    ): String {
        // Explicit override takes priority
        if (explicit != null) return explicit

        // APPX: empty AUMID — Windows uses the MSIX package identity
        if (isAppx) return ""

        // Unpackaged apps: derive AUMID from NucleusApp metadata
        return NucleusApp.appId
    }

    private fun ensureReady(callback: ((String?) -> Unit)?): Boolean {
        if (!isAvailable) {
            callback?.invoke("Not available on this platform")
            return false
        }
        if (!initialized) {
            callback?.invoke("Not initialized — call WindowsNotificationCenter.initialize() first")
            return false
        }
        return true
    }
}
