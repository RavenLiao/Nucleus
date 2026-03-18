package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.awt.ComponentOrientation

/**
 * Controls the placement direction of window control buttons (close, minimize, maximize)
 * independently of the title bar content direction.
 *
 * - [Auto]: follows `LocalLayoutDirection` from Compose (previous default behavior).
 * - [System]: follows the JVM platform locale.
 * - [Ltr]: always place buttons as in a left-to-right layout (trailing = right side).
 * - [Rtl]: always place buttons as in a right-to-left layout (trailing = left side).
 */
enum class ControlButtonsDirection {
    Auto,
    System,
    Ltr,
    Rtl,
    ;

    @Composable
    fun resolve(): LayoutDirection =
        when (this) {
            Auto -> LocalLayoutDirection.current
            System ->
                if (ComponentOrientation.getOrientation(java.util.Locale.getDefault()).isLeftToRight) {
                    LayoutDirection.Ltr
                } else {
                    LayoutDirection.Rtl
                }

            Ltr -> LayoutDirection.Ltr
            Rtl -> LayoutDirection.Rtl
        }
}
