package io.github.kdroidfilter.nucleus.scheduler.internal

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import java.io.File

/**
 * Generates and manages wrapper scripts that act as the OS scheduler's execution target.
 *
 * Instead of registering the application executable directly with the OS scheduler,
 * we register a small wrapper script. The script checks whether the application
 * executable still exists before running it. If the executable is missing (e.g. after
 * uninstall), the script **self-destructs**: it unregisters the scheduled task from the
 * OS and deletes all associated files (script, plist/unit files, metadata).
 *
 * This ensures orphaned scheduled tasks are cleaned up automatically without requiring
 * explicit uninstall hooks.
 */
internal object TaskWrapperScript {

    private fun scriptsDir(appId: String): File {
        val baseDir = when (Platform.Current) {
            Platform.Windows ->
                System.getenv("LOCALAPPDATA")
                    ?: "${System.getProperty("user.home")}\\AppData\\Local"
            Platform.MacOS ->
                "${System.getProperty("user.home")}/Library/Application Support"
            else ->
                System.getenv("XDG_DATA_HOME")
                    ?: "${System.getProperty("user.home")}/.local/share"
        }
        return File(baseDir, "nucleus/scheduler/$appId/scripts")
    }

    fun scriptFile(appId: String, taskId: String): File {
        val dir = scriptsDir(appId)
        val ext = if (Platform.Current == Platform.Windows) "vbs" else "sh"
        return File(dir, "$taskId.$ext")
    }

    fun deleteScript(appId: String, taskId: String) {
        scriptFile(appId, taskId).delete()
    }

    fun deleteAllScripts(appId: String) {
        val dir = scriptsDir(appId)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    // -- Windows VBScript wrapper ------------------------------------------------

    /**
     * Generates a `.vbs` script that checks whether the application executable exists.
     * If not, it removes the scheduled task via the Task Scheduler 2.0 COM API
     * (consistent with how the task was created), cleans up metadata, and self-deletes.
     *
     * The script is invoked by the Task Scheduler via:
     *   `wscript.exe "script.vbs"`
     *
     * Using `wscript.exe` (the GUI script host) instead of `cscript.exe`, `cmd.exe`,
     * or `powershell.exe` guarantees **zero visible console window** — wscript is a
     * Windows-subsystem process that never allocates a console.
     */
    fun generateWindowsScript(
        appId: String,
        taskId: String,
        execPath: String,
        taskFolder: String,
        metadataDir: String,
    ): File {
        val file = scriptFile(appId, taskId)
        file.parentFile.mkdirs()

        val metadataFile = "$metadataDir\\$taskId.properties"

        val content = buildString {
            appendLine("Set fso = CreateObject(\"Scripting.FileSystemObject\")")
            appendLine("If Not fso.FileExists(${vbsQuote(execPath)}) Then")
            appendLine("    On Error Resume Next")
            appendLine("    Set svc = CreateObject(\"Schedule.Service\")")
            appendLine("    svc.Connect")
            appendLine("    Set folder = svc.GetFolder(${vbsQuote(taskFolder)})")
            appendLine("    folder.DeleteTask ${vbsQuote(taskId)}, 0")
            appendLine("    folder.DeleteTask ${vbsQuote("$taskId-retry")}, 0")
            appendLine("    On Error GoTo 0")
            appendLine("    If fso.FileExists(${vbsQuote(metadataFile)}) Then fso.DeleteFile ${vbsQuote(metadataFile)}")
            appendLine("    fso.DeleteFile WScript.ScriptFullName")
            appendLine("    WScript.Quit 0")
            appendLine("End If")
            appendLine("Set shell = CreateObject(\"WScript.Shell\")")
            // shell.Run expects a command string; inner quotes wrap the exe path for spaces.
            // VBS string: "..." with doubled quotes inside → literal quotes in the value.
            appendLine("shell.Run \"\"\"${vbsEscape(execPath)}\"\" --nucleus-scheduler-run $taskId\", 0, True")
        }
        file.writeText(content)
        return file
    }

    /** Wraps a value in VBS double quotes, doubling any inner quotes. */
    private fun vbsQuote(s: String): String = "\"${vbsEscape(s)}\""

    /** Escapes double quotes for use inside a VBS string literal. */
    private fun vbsEscape(s: String): String = s.replace("\"", "\"\"")

    // -- macOS bash wrapper ---------------------------------------------------

    fun generateMacOSScript(
        appId: String,
        taskId: String,
        execPath: String,
        plistPath: String,
        retryPlistPath: String,
        metadataDir: String,
    ): File {
        val file = scriptFile(appId, taskId)
        file.parentFile.mkdirs()

        val content = buildString {
            appendLine("#!/bin/bash")
            appendLine("EXEC=${quote(execPath)}")
            appendLine("if [ ! -x \"${'$'}EXEC\" ]; then")
            appendLine("    launchctl unload ${quote(plistPath)} 2>/dev/null")
            appendLine("    rm -f ${quote(plistPath)}")
            appendLine("    rm -f ${quote(retryPlistPath)}")
            appendLine("    rm -f ${quote(metadataDir + "/" + taskId + ".properties")}")
            appendLine("    rm -f ${quote(file.absolutePath)}")
            appendLine("    exit 0")
            appendLine("fi")
            appendLine("\"${'$'}EXEC\" --nucleus-scheduler-run $taskId")
        }
        file.writeText(content)
        file.setExecutable(true)
        return file
    }

    // -- Linux bash wrapper ---------------------------------------------------

    fun generateLinuxScript(
        appId: String,
        taskId: String,
        execPath: String,
        timerUnit: String,
        serviceUnit: String,
        serviceFilePath: String,
        timerFilePath: String,
        metadataDir: String,
    ): File {
        val file = scriptFile(appId, taskId)
        file.parentFile.mkdirs()

        val content = buildString {
            appendLine("#!/bin/bash")
            appendLine("EXEC=${quote(execPath)}")
            appendLine("if [ ! -x \"${'$'}EXEC\" ]; then")
            appendLine("    systemctl --user disable --now ${quote(timerUnit)} 2>/dev/null")
            appendLine("    systemctl --user disable ${quote(serviceUnit)} 2>/dev/null")
            appendLine("    rm -f ${quote(timerFilePath)}")
            appendLine("    rm -f ${quote(serviceFilePath)}")
            appendLine("    systemctl --user daemon-reload 2>/dev/null")
            appendLine("    rm -f ${quote(metadataDir + "/" + taskId + ".properties")}")
            appendLine("    rm -f ${quote(file.absolutePath)}")
            appendLine("    exit 0")
            appendLine("fi")
            appendLine("\"${'$'}EXEC\" --nucleus-scheduler-run $taskId")
        }
        file.writeText(content)
        file.setExecutable(true)
        return file
    }

    private fun quote(s: String): String = "\"$s\""
}
