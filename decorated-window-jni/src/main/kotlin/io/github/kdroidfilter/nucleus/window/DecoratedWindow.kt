package io.github.kdroidfilter.nucleus.window

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.utils.windows.JniWindowsDecorationBridge
import io.github.kdroidfilter.nucleus.window.utils.windows.JniWindowsWindowUtil

/**
 * Composition local that indicates whether the window is currently in
 * native (JNI-managed) fullscreen mode.
 */
internal val LocalNativeFullscreen = compositionLocalOf { false }

/**
 * Composition local providing a callback to exit native fullscreen.
 */
internal val LocalExitFullscreen = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Holder for the fullscreen title bar content.
 * [NativeWindowsTitleBar] stores its rendering lambda here when in fullscreen,
 * and [DecoratedWindow] renders it as an overlay outside the normal layout.
 */
internal class FullscreenTitleBarHolder {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
    var titleBarHeight: Dp by mutableStateOf(0.dp)
}

internal val LocalFullscreenTitleBarHolder = compositionLocalOf<FullscreenTitleBarHolder?> { null }

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DecoratedWindowScope.() -> Unit,
) {
    val undecorated =
        when (Platform.Current) {
            Platform.Windows -> !JniWindowsDecorationBridge.isLoaded
            Platform.Linux -> true
            else -> false
        }

    val useNativeFullscreen = Platform.Current == Platform.Windows && JniWindowsDecorationBridge.isLoaded
    val windowState =
        if (useNativeFullscreen) {
            remember(state) { NativeFullscreenWindowState(state) }
        } else {
            state
        }

    Window(
        onCloseRequest,
        windowState,
        visible,
        title,
        icon,
        undecorated,
        transparent = false,
        resizable,
        enabled,
        focusable,
        alwaysOnTop,
        onPreviewKeyEvent,
        onKeyEvent,
    ) {
        if (useNativeFullscreen) {
            NativeFullscreenEffect(state)
        }

        val isNativeFullscreen = useNativeFullscreen && state.placement == WindowPlacement.Fullscreen
        val exitFullscreen: (() -> Unit)? =
            if (isNativeFullscreen) {
                {
                    val target =
                        (windowState as? NativeFullscreenWindowState)
                            ?.placementBeforeFullscreen ?: WindowPlacement.Floating
                    state.placement = target
                }
            } else {
                null
            }

        val titleBarHolder = remember { FullscreenTitleBarHolder() }

        // Clear holder content when leaving fullscreen
        if (!isNativeFullscreen) {
            titleBarHolder.content = null
        }

        Box {
            CompositionLocalProvider(
                LocalNativeFullscreen provides isNativeFullscreen,
                LocalExitFullscreen provides exitFullscreen,
                LocalFullscreenTitleBarHolder provides titleBarHolder,
            ) {
                DecoratedWindowBody(
                    title = title,
                    icon = icon,
                    undecorated = undecorated,
                    content = content,
                )
            }

            // Render the fullscreen title bar as an overlay on top of content
            if (isNativeFullscreen) {
                CompositionLocalProvider(
                    LocalTitleBarInfo provides TitleBarInfo(title, icon),
                ) {
                    FullscreenTitleBarOverlay(
                        holder = titleBarHolder,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

/**
 * Renders the fullscreen title bar as a sliding overlay.
 * Hidden above the top edge by default; slides down when hovering the top.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
private fun FullscreenTitleBarOverlay(
    holder: FullscreenTitleBarHolder,
    modifier: Modifier = Modifier,
) {
    val titleBarContent = holder.content ?: return
    val titleBarHeight = holder.titleBarHeight

    var topHovered by remember { mutableStateOf(false) }
    var barHovered by remember { mutableStateOf(false) }
    val visible = topHovered || barHovered

    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else -titleBarHeight,
        animationSpec = tween(durationMillis = 200),
    )

    Box(modifier = modifier.fillMaxWidth()) {
        // 1px invisible hover detector at the very top edge of the screen
        Spacer(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .onPointerEvent(PointerEventType.Enter, PointerEventPass.Main) {
                        topHovered = true
                    }.onPointerEvent(PointerEventType.Exit, PointerEventPass.Main) {
                        topHovered = false
                    },
        )

        // Sliding title bar
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset(y = offsetY)
                    .onPointerEvent(PointerEventType.Enter, PointerEventPass.Main) {
                        barHovered = true
                    }.onPointerEvent(PointerEventType.Exit, PointerEventPass.Main) {
                        barHovered = false
                    },
        ) {
            titleBarContent()
        }
    }
}

/**
 * Watches [state].placement and enters/exits native Win32 fullscreen accordingly.
 * A local [isNativeFullscreen] flag guards against redundant JNI calls if
 * [snapshotFlow] emits the same placement multiple times in quick succession.
 */
@Composable
private fun FrameWindowScope.NativeFullscreenEffect(state: WindowState) {
    LaunchedEffect(state, window) {
        var isNativeFullscreen = false
        snapshotFlow { state.placement }.collect { placement ->
            val hwnd = JniWindowsWindowUtil.getHwnd(window)
            if (hwnd == 0L) return@collect

            if (placement == WindowPlacement.Fullscreen && !isNativeFullscreen) {
                JniWindowsDecorationBridge.nativeSetFullscreen(hwnd, true)
                isNativeFullscreen = true
            } else if (placement != WindowPlacement.Fullscreen && isNativeFullscreen) {
                JniWindowsDecorationBridge.nativeSetFullscreen(hwnd, false)
                isNativeFullscreen = false
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
//  NativeFullscreenWindowState wrapper
// ──────────────────────────────────────────────────────────────────────

/**
 * Wraps a [WindowState] to prevent Compose from seeing [WindowPlacement.Fullscreen].
 *
 * When the delegate's placement is Fullscreen, this wrapper:
 * - **getter**: returns the placement that was active before fullscreen, so Compose's
 *   Window never triggers its own (broken) exclusive fullscreen mode.
 * - **setter**: blocks all writes from Compose's internal sync (which would overwrite
 *   the Fullscreen value with Floating/Maximized and trigger an immediate exit).
 *
 * User code writes directly to the delegate (the original [WindowState]), not through
 * this wrapper. Only Compose's [Window] composable writes through the wrapper.
 */
internal class NativeFullscreenWindowState(
    private val delegate: WindowState,
) : WindowState {
    internal var placementBeforeFullscreen: WindowPlacement =
        delegate.placement.takeIf { it != WindowPlacement.Fullscreen }
            ?: WindowPlacement.Floating

    /** True when the delegate holds [WindowPlacement.Fullscreen]. */
    private val isInNativeFullscreen: Boolean
        get() = delegate.placement == WindowPlacement.Fullscreen

    override var placement: WindowPlacement
        get() {
            val p = delegate.placement
            return if (p == WindowPlacement.Fullscreen) placementBeforeFullscreen else p
        }
        set(value) {
            if (isInNativeFullscreen) return

            if (delegate.placement != WindowPlacement.Fullscreen && value == WindowPlacement.Fullscreen) {
                placementBeforeFullscreen = delegate.placement
            }
            delegate.placement = value
        }

    override var isMinimized: Boolean
        get() = delegate.isMinimized
        set(value) {
            if (isInNativeFullscreen) return
            delegate.isMinimized = value
        }

    override var position: WindowPosition
        get() = delegate.position
        set(value) {
            if (isInNativeFullscreen) return
            delegate.position = value
        }

    override var size: DpSize
        get() = delegate.size
        set(value) {
            if (isInNativeFullscreen) return
            delegate.size = value
        }
}
