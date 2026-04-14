package schedulerdemo

import androidx.compose.ui.Alignment
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import io.github.kdroidfilter.nucleus.darkmodedetector.isSystemInDarkMode
import io.github.kdroidfilter.nucleus.scheduler.DesktopBootReceiver
import io.github.kdroidfilter.nucleus.scheduler.TaskRegistry
import io.github.kdroidfilter.nucleus.window.jewel.JewelDecoratedWindow
import io.github.kdroidfilter.nucleus.window.jewel.JewelTitleBar
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import schedulerdemo.task.BackupTask
import schedulerdemo.task.NotificationTask
import schedulerdemo.task.SyncTask

private fun buildRegistry() =
    TaskRegistry
        .Builder()
        .register("sync") { SyncTask() }
        .register("backup") { BackupTask() }
        .register("notification") { NotificationTask() }
        .build()

fun main(args: Array<String>) {
    if (DesktopBootReceiver.isSchedulerInvocation(args)) {
        DesktopBootReceiver.handle(args = args, registry = buildRegistry())
        return
    }

    application {
        val textStyle = JewelTheme.createDefaultTextStyle()
        val editorStyle = JewelTheme.createEditorTextStyle()
        val isDark = isSystemInDarkMode()

        val theme =
            if (isDark) {
                JewelTheme.darkThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
            } else {
                JewelTheme.lightThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
            }

        IntUiTheme(
            theme = theme,
            styling = ComponentStyling.default(),
        ) {
            JewelDecoratedWindow(
                onCloseRequest = { exitApplication() },
                title = "Scheduler Demo",
                state =
                    androidx.compose.ui.window.rememberWindowState(
                        position = WindowPosition.Aligned(Alignment.Center),
                    ),
                content = {
                    JewelTitleBar()
                    SchedulerDemoView()
                },
            )
        }
    }
}
