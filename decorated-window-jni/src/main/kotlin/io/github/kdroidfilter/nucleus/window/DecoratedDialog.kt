package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import io.github.kdroidfilter.nucleus.core.runtime.Platform

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
        LaunchedEffect(window) {
            window.setLocationRelativeTo(window.owner)
        }
        DecoratedDialogBody(
            title = title,
            icon = icon,
            undecorated = undecorated,
            content = content,
        )
    }
}
