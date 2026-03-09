package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun DecoratedDialog(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = false,
    enabled: Boolean = true,
    focusable: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DecoratedDialogScope.() -> Unit,
) {
    val undecorated = Platform.Linux == Platform.Current || Platform.Windows == Platform.Current

    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = title,
        icon = icon,
        undecorated = undecorated,
        transparent = false,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
    ) {
        // Centre the dialog on its parent window automatically.
        // ComposeDialog.owner is set by Compose Desktop to the nearest parent
        // Window in the composition tree (the DecoratedWindow). When used
        // outside a Window (no parent), setLocationRelativeTo(null) centres
        // on the screen instead.
        // We use windowOpened (fires when the native window is first shown)
        // so that Compose Desktop has already applied the DialogState position
        // before we override it with relative placement.
        DisposableEffect(window) {
            val listener =
                object : WindowAdapter() {
                    override fun windowOpened(e: WindowEvent?) {
                        window.setLocationRelativeTo(window.owner)
                    }
                }
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener) }
        }
        DecoratedDialogBody(
            title = title,
            icon = icon,
            undecorated = undecorated,
            content = content,
        )
    }
}
