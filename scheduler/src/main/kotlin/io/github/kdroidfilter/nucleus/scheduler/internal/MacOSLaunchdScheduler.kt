package io.github.kdroidfilter.nucleus.scheduler.internal

import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
import io.github.kdroidfilter.nucleus.scheduler.ExistingTaskPolicy
import io.github.kdroidfilter.nucleus.scheduler.TaskInfo
import io.github.kdroidfilter.nucleus.scheduler.TaskRequest
import io.github.kdroidfilter.nucleus.scheduler.TaskState
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * macOS implementation using launchd user agents.
 *
 * Generated plist files go to `~/Library/LaunchAgents/` and are managed via
 * `launchctl` commands. Each task creates a plist with label
 * `io.github.kdroidfilter.nucleus.{appId}.{taskId}`.
 */
@Suppress("TooManyFunctions")
internal object MacOSLaunchdScheduler : PlatformScheduler {
    private val logger = Logger.getLogger(MacOSLaunchdScheduler::class.java.name)
    private const val SCHEDULER_ARG = "--nucleus-scheduler-run"
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val LABEL_PREFIX = "io.github.kdroidfilter.nucleus"

    private val launchAgentsDir: File
        get() = File(System.getProperty("user.home"), "Library/LaunchAgents")

    private val appId: String
        get() = NucleusApp.appId

    private val executablePath: String?
        get() =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)

    // -- Naming ---------------------------------------------------------------

    internal fun label(taskId: String): String = "$LABEL_PREFIX.$appId.$taskId"

    private fun retryLabel(taskId: String): String = "$LABEL_PREFIX.$appId.$taskId-retry"

    private fun plistFileName(taskId: String): String = "${label(taskId)}.plist"

    private fun retryPlistFileName(taskId: String): String = "${retryLabel(taskId)}.plist"

    private fun plistFile(taskId: String): File = File(launchAgentsDir, plistFileName(taskId))

    private fun retryPlistFile(taskId: String): File = File(launchAgentsDir, retryPlistFileName(taskId))

    // -- PlatformScheduler implementation -------------------------------------

    override fun enqueue(request: TaskRequest): Boolean {
        if (request.existingTaskPolicy == ExistingTaskPolicy.KEEP && isScheduled(request.taskId)) {
            TaskMetadataStore.save(appId, request.taskId, request.inputData)
            return true
        }

        val execPath = executablePath
        if (execPath == null) {
            logger.warning("Cannot resolve executable path — task '${request.taskId}' not scheduled")
            return false
        }

        launchAgentsDir.mkdirs()

        // If replacing, unload and remove the existing plist first
        if (request.existingTaskPolicy == ExistingTaskPolicy.REPLACE && isScheduled(request.taskId)) {
            unloadAndDelete(request.taskId)
        }

        // Save metadata
        TaskMetadataStore.save(appId, request.taskId, request.inputData)

        // Generate plist
        val plistContent =
            try {
                buildPlist(request, execPath)
            } catch (e: IllegalArgumentException) {
                logger.warning("Task '${request.taskId}' not scheduled: ${e.message}")
                return false
            }
        val file = plistFile(request.taskId)
        file.writeText(plistContent)

        // Load into launchd
        return launchctl("load", "-w", file.absolutePath)
    }

    override fun cancel(taskId: String): Boolean {
        val file = plistFile(taskId)
        if (!file.exists()) return false

        unloadAndDelete(taskId)
        // Also clean up any pending retry
        val retryFile = retryPlistFile(taskId)
        if (retryFile.exists()) {
            launchctl("unload", retryFile.absolutePath)
            retryFile.delete()
        }
        TaskMetadataStore.delete(appId, taskId)
        return true
    }

    override fun cancelAll() {
        val allIds = listScheduledTaskIds()
        allIds.forEach { cancel(it) }
        TaskMetadataStore.deleteAll(appId)
    }

    override fun isScheduled(taskId: String): Boolean = plistFile(taskId).exists()

    override fun getTaskInfo(taskId: String): TaskInfo? {
        if (!plistFile(taskId).exists()) return null

        // Plist exists → task is scheduled. Check if launchd has it loaded right now.
        val state = if (isLoaded(label(taskId))) TaskState.SCHEDULED else TaskState.INACTIVE

        return TaskInfo(
            taskId = taskId,
            state = state,
            lastRunMs = TaskMetadataStore.getLastRunMs(appId, taskId),
            nextRunMs = null, // launchd does not expose next fire date via CLI
            runCount = TaskMetadataStore.getRunCount(appId, taskId),
            lastResult = TaskMetadataStore.getLastResult(appId, taskId),
        )
    }

    override fun getAllTasks(): List<TaskInfo> =
        listScheduledTaskIds().mapNotNull { getTaskInfo(it) }

    // -- Retry support --------------------------------------------------------

    /**
     * Schedules a one-shot retry that fires after [delaySeconds].
     *
     * Uses a background thread to wait for the delay, then loads a RunAtLoad-only
     * plist that fires immediately. The retry plist is cleaned up after execution
     * by [cleanupRetryPlist].
     */
    fun scheduleRetry(
        taskId: String,
        delaySeconds: Long,
    ): Boolean {
        val execPath = executablePath ?: return false

        // Remove any previous retry plist
        val retryFile = retryPlistFile(taskId)
        if (retryFile.exists()) {
            launchctl("unload", retryFile.absolutePath)
            retryFile.delete()
        }

        val plist = buildString {
            appendLine(PLIST_HEADER)
            appendLine("<dict>")
            appendLine("  <key>Label</key>")
            appendLine("  <string>${retryLabel(taskId)}</string>")
            appendLine("  <key>ProgramArguments</key>")
            appendLine("  <array>")
            appendLine("    <string>$execPath</string>")
            appendLine("    <string>$SCHEDULER_ARG</string>")
            appendLine("    <string>$taskId</string>")
            appendLine("  </array>")
            appendLine("  <key>RunAtLoad</key>")
            appendLine("  <true/>")
            appendLine("  <key>KeepAlive</key>")
            appendLine("  <false/>")
            appendLine("  <key>ProcessType</key>")
            appendLine("  <string>Background</string>")
            appendLine("</dict>")
            appendLine("</plist>")
        }

        launchAgentsDir.mkdirs()
        retryFile.writeText(plist)

        // Delay the load so the retry fires after the requested wait
        val thread = Thread({
            try {
                Thread.sleep(delaySeconds * MILLIS_PER_SECOND)
                launchctl("load", retryFile.absolutePath)
            } catch (_: InterruptedException) {
                logger.warning("Retry delay interrupted for task '$taskId'")
            }
        }, "nucleus-retry-$taskId")
        thread.isDaemon = true
        thread.start()

        return true
    }

    /**
     * Removes the retry plist for a task after it has executed.
     * Called by [DesktopBootReceiver] after a retry run completes.
     */
    fun cleanupRetryPlist(taskId: String) {
        val retryFile = retryPlistFile(taskId)
        if (retryFile.exists()) {
            launchctl("unload", retryFile.absolutePath)
            retryFile.delete()
        }
    }

    private const val MILLIS_PER_SECOND = 1000L

    // -- Plist generation -----------------------------------------------------

    private const val PLIST_HEADER =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">"""

    private fun buildPlist(request: TaskRequest, execPath: String): String =
        buildString {
            appendLine(PLIST_HEADER)
            appendLine("<dict>")
            appendLine("  <key>Label</key>")
            appendLine("  <string>${label(request.taskId)}</string>")
            appendLine("  <key>ProgramArguments</key>")
            appendLine("  <array>")
            appendLine("    <string>$execPath</string>")
            appendLine("    <string>$SCHEDULER_ARG</string>")
            appendLine("    <string>${request.taskId}</string>")
            appendLine("  </array>")

            when (request.type) {
                TaskRequest.Type.PERIODIC -> {
                    val seconds = request.interval!!.inWholeSeconds
                    appendLine("  <key>StartInterval</key>")
                    appendLine("  <integer>$seconds</integer>")
                }
                TaskRequest.Type.CALENDAR -> {
                    appendCalendarInterval(this, request.cronExpression!!.expression)
                }
                TaskRequest.Type.ON_BOOT -> {
                    appendLine("  <key>RunAtLoad</key>")
                    appendLine("  <true/>")
                }
            }

            // Keep the agent loaded even after it exits
            appendLine("  <key>KeepAlive</key>")
            appendLine("  <false/>")
            appendLine("  <key>ProcessType</key>")
            appendLine("  <string>Background</string>")
            appendLine("</dict>")
            appendLine("</plist>")
        }

    /**
     * Converts a systemd OnCalendar expression to launchd's StartCalendarInterval.
     *
     * Supported patterns:
     * - `*-*-* HH:MM:00`            → every day at HH:MM
     * - `*-*-* *:00:00`             → every hour
     * - `Mon *-*-* HH:MM:00`        → every Monday at HH:MM
     * - `Mon..Fri *-*-* HH:MM:00`   → weekdays at HH:MM (generates array of dicts)
     */
    @Suppress("CyclomaticComplexity")
    internal fun appendCalendarInterval(sb: StringBuilder, expression: String) {
        val trimmed = expression.trim()

        // Pattern: every hour — *-*-* *:00:00
        if (trimmed == "*-*-* *:00:00") {
            sb.appendLine("  <key>StartCalendarInterval</key>")
            sb.appendLine("  <dict>")
            sb.appendLine("    <key>Minute</key>")
            sb.appendLine("    <integer>0</integer>")
            sb.appendLine("  </dict>")
            return
        }

        // Pattern: day range (Mon..Fri) with time
        val rangeMatch = Regex("""^(\w{3})\.\.(\w{3})\s+\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""")
            .matchEntire(trimmed)
        if (rangeMatch != null) {
            val (startDay, endDay, hour, minute) = rangeMatch.destructured
            val days = expandDayRange(startDay, endDay)
            if (days != null) {
                sb.appendLine("  <key>StartCalendarInterval</key>")
                sb.appendLine("  <array>")
                for (dayNum in days) {
                    sb.appendLine("    <dict>")
                    sb.appendLine("      <key>Weekday</key>")
                    sb.appendLine("      <integer>$dayNum</integer>")
                    sb.appendLine("      <key>Hour</key>")
                    sb.appendLine("      <integer>${hour.toInt()}</integer>")
                    sb.appendLine("      <key>Minute</key>")
                    sb.appendLine("      <integer>${minute.toInt()}</integer>")
                    sb.appendLine("    </dict>")
                }
                sb.appendLine("  </array>")
                return
            }
        }

        // Pattern: specific weekday with time — Mon *-*-* HH:MM:00
        val weekdayMatch = Regex("""^(\w{3})\s+\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""")
            .matchEntire(trimmed)
        if (weekdayMatch != null) {
            val (day, hour, minute) = weekdayMatch.destructured
            val dayNum = dayToLaunchdWeekday(day)
            if (dayNum != null) {
                sb.appendLine("  <key>StartCalendarInterval</key>")
                sb.appendLine("  <dict>")
                sb.appendLine("    <key>Weekday</key>")
                sb.appendLine("    <integer>$dayNum</integer>")
                sb.appendLine("    <key>Hour</key>")
                sb.appendLine("    <integer>${hour.toInt()}</integer>")
                sb.appendLine("    <key>Minute</key>")
                sb.appendLine("    <integer>${minute.toInt()}</integer>")
                sb.appendLine("  </dict>")
                return
            }
        }

        // Pattern: daily at specific time — *-*-* HH:MM:00
        val dailyMatch = Regex("""^\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""").matchEntire(trimmed)
        if (dailyMatch != null) {
            val (hour, minute) = dailyMatch.destructured
            sb.appendLine("  <key>StartCalendarInterval</key>")
            sb.appendLine("  <dict>")
            sb.appendLine("    <key>Hour</key>")
            sb.appendLine("    <integer>${hour.toInt()}</integer>")
            sb.appendLine("    <key>Minute</key>")
            sb.appendLine("    <integer>${minute.toInt()}</integer>")
            sb.appendLine("  </dict>")
            return
        }

        // Unsupported expression — fail explicitly instead of silently degrading
        throw IllegalArgumentException(
            "Unsupported cron expression '$expression' for macOS launchd. " +
                "Supported patterns: '*-*-* HH:MM:00', '*-*-* *:00:00', " +
                "'Mon *-*-* HH:MM:00', 'Mon..Fri *-*-* HH:MM:00'. " +
                "Use CronExpression factory methods instead of CronExpression.custom()."
        )
    }

    // -- Day mapping (launchd uses 0=Sunday, 1=Monday, ..., 6=Saturday) -------

    private val dayMap = mapOf(
        "MON" to 1, "TUE" to 2, "WED" to 3, "THU" to 4,
        "FRI" to 5, "SAT" to 6, "SUN" to 0,
    )

    private val orderedDays = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    private fun dayToLaunchdWeekday(day: String): Int? = dayMap[day.uppercase()]

    private fun expandDayRange(start: String, end: String): List<Int>? {
        val startIdx = orderedDays.indexOf(start.uppercase())
        val endIdx = orderedDays.indexOf(end.uppercase())
        if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) return null
        return orderedDays.subList(startIdx, endIdx + 1).mapNotNull { dayMap[it] }
    }

    // -- launchctl helpers ----------------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private fun launchctl(vararg args: String): Boolean =
        try {
            val process =
                ProcessBuilder("launchctl", *args)
                    .redirectErrorStream(true)
                    .start()
            // Drain output to prevent blocking
            process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                logger.warning("launchctl ${args.joinToString(" ")} timed out")
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "launchctl ${args.joinToString(" ")} failed", e)
            false
        }

    /**
     * Checks whether a job with the given label is currently loaded in launchd.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun isLoaded(jobLabel: String): Boolean =
        try {
            val process =
                ProcessBuilder("launchctl", "list", jobLabel)
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Exception) {
            false
        }

    private fun unloadAndDelete(taskId: String) {
        val file = plistFile(taskId)
        if (file.exists()) {
            launchctl("unload", file.absolutePath)
            file.delete()
        }
    }

    // -- Introspection --------------------------------------------------------

    private fun listScheduledTaskIds(): List<String> {
        val prefix = "$LABEL_PREFIX.$appId."
        val dir = launchAgentsDir
        if (!dir.isDirectory) return emptyList()
        return dir
            .listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".plist") }
            ?.map { it.name.removePrefix(prefix).removeSuffix(".plist") }
            ?.filter { !it.endsWith("-retry") }
            ?: emptyList()
    }
}
