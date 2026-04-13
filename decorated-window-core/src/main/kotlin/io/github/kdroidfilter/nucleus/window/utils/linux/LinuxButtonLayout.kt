package io.github.kdroidfilter.nucleus.window.utils.linux

import io.github.kdroidfilter.nucleus.window.NativeLayoutDirectionBridge

/**
 * Represents a titlebar button type from the GNOME `button-layout` GSettings key.
 */
enum class LinuxTitleBarButton {
    CLOSE,
    MINIMIZE,
    MAXIMIZE,
}

/**
 * Parsed representation of the GNOME `org.gnome.desktop.wm.preferences` → `button-layout` value.
 *
 * @property buttons Ordered list of buttons to display (edge-first: first element is closest to the window edge).
 * @property controlsOnRight `true` if buttons are placed on the right side of the titlebar.
 */
data class LinuxButtonLayout(
    val buttons: List<LinuxTitleBarButton>,
    val controlsOnRight: Boolean,
) {
    val hasClose: Boolean get() = LinuxTitleBarButton.CLOSE in buttons
    val hasMinimize: Boolean get() = LinuxTitleBarButton.MINIMIZE in buttons
    val hasMaximize: Boolean get() = LinuxTitleBarButton.MAXIMIZE in buttons

    companion object {
        /** Default layout: close on the right (GNOME default). */
        val Default = LinuxButtonLayout(
            buttons = listOf(
                LinuxTitleBarButton.CLOSE,
                LinuxTitleBarButton.MAXIMIZE,
                LinuxTitleBarButton.MINIMIZE,
            ),
            controlsOnRight = true,
        )

        /**
         * Reads and caches the system button layout.
         * Falls back to [Default] if GSettings is unavailable.
         */
        val System: LinuxButtonLayout by lazy {
            try {
                val raw = NativeLayoutDirectionBridge.nativeGetButtonLayout()
                if (raw != null) parse(raw) else Default
            } catch (_: UnsatisfiedLinkError) {
                Default
            }
        }

        /**
         * Parses a GNOME `button-layout` string like `"appmenu:minimize,maximize,close"`
         * or `"close,minimize,maximize:"`.
         *
         * The colon separates left and right sides. The side containing `close`
         * determines where control buttons are placed.
         */
        internal fun parse(raw: String): LinuxButtonLayout {
            val colonIndex = raw.indexOf(':')
            val (left, right) = if (colonIndex >= 0) {
                raw.substring(0, colonIndex) to raw.substring(colonIndex + 1)
            } else {
                "" to raw
            }

            val leftButtons = parseButtons(left)
            val rightButtons = parseButtons(right)

            // The side with "close" is the control buttons side
            val controlsOnRight = LinuxTitleBarButton.CLOSE in rightButtons ||
                LinuxTitleBarButton.CLOSE !in leftButtons

            return if (controlsOnRight) {
                // Right side: reverse to get edge-first order (close = rightmost = emitted first)
                LinuxButtonLayout(
                    buttons = rightButtons.reversed(),
                    controlsOnRight = true,
                )
            } else {
                // Left side: already edge-first (close = leftmost = emitted first)
                LinuxButtonLayout(
                    buttons = leftButtons,
                    controlsOnRight = false,
                )
            }
        }

        private fun parseButtons(side: String): List<LinuxTitleBarButton> =
            side.split(',')
                .mapNotNull { token ->
                    when (token.trim()) {
                        "close" -> LinuxTitleBarButton.CLOSE
                        "minimize" -> LinuxTitleBarButton.MINIMIZE
                        "maximize" -> LinuxTitleBarButton.MAXIMIZE
                        else -> null // skip unknown tokens like "appmenu"
                    }
                }
    }
}
