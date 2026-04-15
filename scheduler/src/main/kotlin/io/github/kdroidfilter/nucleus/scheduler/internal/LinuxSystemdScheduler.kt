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
 * Linux implementation using systemd user timers.
 *
 * Generated unit files go to `~/.config/systemd/user/` and are managed via
 * `systemctl --user` commands.
 */
@Suppress("TooManyFunctions")
internal object LinuxSystemdScheduler : PlatformScheduler {
    private val logger = Logger.getLogger(LinuxSystemdScheduler::class.java.name)
    private const val SCHEDULER_ARG = "--nucleus-scheduler-run"
    private const val COMMAND_TIMEOUT_SECONDS = 10L

    internal const val UNIT_PREFIX = "nucleus"
    private const val USEC_PER_MS = 1000L
    private const val BOOT_DELAY_FRACTION = 10L
    private const val MIN_BOOT_DELAY_SECONDS = 60L
    private const val MAX_BOOT_DELAY_SECONDS = 300L

    private val systemdUserDir: File
        get() {
            val configHome =
                System.getenv("XDG_CONFIG_HOME")
                    ?: "${System.getProperty("user.home")}/.config"
            return File(configHome, "systemd/user")
        }

    private val appId: String
        get() = NucleusApp.appId

    private val executablePath: String?
        get() =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)

    // -- Unit naming ----------------------------------------------------------

    internal fun unitBaseName(taskId: String): String = "$UNIT_PREFIX-$appId-$taskId"

    private fun serviceFileName(taskId: String): String = "${unitBaseName(taskId)}.service"

    private fun timerFileName(taskId: String): String = "${unitBaseName(taskId)}.timer"

    // -- PlatformScheduler implementation -------------------------------------

    override fun enqueue(request: TaskRequest): Boolean {
        if (request.existingTaskPolicy == ExistingTaskPolicy.KEEP && isScheduled(request.taskId)) {
            return true
        }

        val execPath = executablePath
        if (execPath == null) {
            logger.warning("Cannot resolve executable path — task '${request.taskId}' not scheduled")
            return false
        }

        systemdUserDir.mkdirs()

        // Save inputData for later retrieval by DesktopBootReceiver
        if (request.inputData.isNotEmpty()) {
            TaskMetadataStore.save(appId, request.taskId, request.inputData)
        }

        // Generate wrapper script
        val serviceFile = File(systemdUserDir, serviceFileName(request.taskId))
        val timerFile = File(systemdUserDir, timerFileName(request.taskId))
        val metadataDir = TaskMetadataStore.storeDir(appId).absolutePath
        val wrapperScript = TaskWrapperScript.generateLinuxScript(
            appId = appId,
            taskId = request.taskId,
            execPath = execPath,
            timerUnit = timerFileName(request.taskId),
            serviceUnit = serviceFileName(request.taskId),
            serviceFilePath = serviceFile.absolutePath,
            timerFilePath = timerFile.absolutePath,
            metadataDir = metadataDir,
        )

        // Generate .service file pointing to wrapper script
        val serviceContent = buildServiceUnit(request.taskId, wrapperScript.absolutePath)
        serviceFile.writeText(serviceContent)

        // Generate .timer file (not needed for onBoot without timer)
        val needsTimer = request.type != TaskRequest.Type.ON_BOOT
        if (needsTimer) {
            val timerContent = buildTimerUnit(request)
            File(systemdUserDir, timerFileName(request.taskId)).writeText(timerContent)
        }

        // Reload and enable
        systemctl("daemon-reload")

        return if (needsTimer) {
            systemctl("enable", "--now", timerFileName(request.taskId))
        } else {
            // onBoot: enable the service to start at user login
            systemctl("enable", serviceFileName(request.taskId))
        }
    }

    override fun cancel(taskId: String): Boolean {
        val timerFile = File(systemdUserDir, timerFileName(taskId))
        val serviceFile = File(systemdUserDir, serviceFileName(taskId))

        if (!timerFile.exists() && !serviceFile.exists()) return false

        if (timerFile.exists()) {
            systemctl("disable", "--now", timerFileName(taskId))
        }
        if (serviceFile.exists()) {
            systemctl("disable", serviceFileName(taskId))
        }

        timerFile.delete()
        serviceFile.delete()
        systemctl("daemon-reload")
        TaskWrapperScript.deleteScript(appId, taskId)
        TaskMetadataStore.delete(appId, taskId)
        return true
    }

    override fun cancelAll() {
        val allIds = listScheduledTaskIds()
        allIds.forEach { cancel(it) }
        TaskWrapperScript.deleteAllScripts(appId)
        TaskMetadataStore.deleteAll(appId)
    }

    override fun isScheduled(taskId: String): Boolean {
        val timerFile = File(systemdUserDir, timerFileName(taskId))
        val serviceFile = File(systemdUserDir, serviceFileName(taskId))
        if (!timerFile.exists() && !serviceFile.exists()) return false
        return systemctlQuery("is-enabled", timerFileName(taskId)) == "enabled" ||
            systemctlQuery("is-enabled", serviceFileName(taskId)) == "enabled"
    }

    override fun getTaskInfo(taskId: String): TaskInfo? {
        if (!isScheduled(taskId)) return null

        val state = resolveState(taskId)
        val nextRunMs = parseNextElapse(taskId)

        return TaskInfo(
            taskId = taskId,
            state = state,
            lastRunMs = TaskMetadataStore.getLastRunMs(appId, taskId),
            nextRunMs = nextRunMs,
            runCount = TaskMetadataStore.getRunCount(appId, taskId),
            lastResult = TaskMetadataStore.getLastResult(appId, taskId),
        )
    }

    override fun getAllTasks(): List<TaskInfo> = listScheduledTaskIds().mapNotNull { getTaskInfo(it) }

    // -- Retry support --------------------------------------------------------

    /**
     * Schedules a one-shot retry timer that fires after [delaySeconds].
     */
    fun scheduleRetry(
        taskId: String,
        delaySeconds: Long,
    ): Boolean {
        val retryTimerName = "${unitBaseName(taskId)}-retry.timer"
        val content =
            buildString {
                appendLine("[Unit]")
                appendLine("Description=Nucleus retry timer: $taskId")
                appendLine()
                appendLine("[Timer]")
                appendLine("OnActiveSec=${delaySeconds}s")
                appendLine("RemainAfterElapse=false")
                appendLine()
                appendLine("[Install]")
                appendLine("WantedBy=timers.target")
            }

        val retryTimerFile = File(systemdUserDir, retryTimerName)
        retryTimerFile.writeText(content)
        systemctl("daemon-reload")
        return systemctl("start", retryTimerName)
    }

    // -- Unit file generation -------------------------------------------------

    private fun buildServiceUnit(
        taskId: String,
        scriptPath: String,
    ): String =
        buildString {
            appendLine("[Unit]")
            appendLine("Description=Nucleus scheduled task: $taskId")
            appendLine()
            appendLine("[Service]")
            appendLine("Type=oneshot")
            appendLine("ExecStart=$scriptPath")
            appendLine()
            appendLine("[Install]")
            appendLine("WantedBy=default.target")
        }

    internal fun buildTimerUnit(request: TaskRequest): String =
        buildString {
            appendLine("[Unit]")
            appendLine("Description=Nucleus timer: ${request.taskId}")
            appendLine()
            appendLine("[Timer]")

            when (request.type) {
                TaskRequest.Type.PERIODIC -> {
                    val seconds = request.interval!!.inWholeSeconds
                    appendLine("OnUnitActiveSec=${seconds}s")
                    // Trigger after boot so the first run doesn't wait a full interval.
                    // Use 10% of the interval, clamped to [60s, 300s].
                    val bootDelaySec = (seconds / BOOT_DELAY_FRACTION)
                        .coerceIn(MIN_BOOT_DELAY_SECONDS, MAX_BOOT_DELAY_SECONDS)
                    appendLine("OnBootSec=${bootDelaySec}s")
                }
                TaskRequest.Type.CALENDAR -> {
                    appendLine("OnCalendar=${request.cronExpression!!.expression}")
                }
                TaskRequest.Type.ON_BOOT -> {
                    // Should not reach here — onBoot tasks don't use timers
                }
            }

            appendLine("Persistent=true")
            appendLine()
            appendLine("[Install]")
            appendLine("WantedBy=timers.target")
        }

    // -- systemctl helpers ----------------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private fun systemctl(vararg args: String): Boolean =
        try {
            val process =
                ProcessBuilder("systemctl", "--user", *args)
                    .redirectErrorStream(true)
                    .start()
            val exited = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                logger.warning("systemctl --user ${args.joinToString(" ")} timed out")
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "systemctl --user ${args.joinToString(" ")} failed", e)
            false
        }

    @Suppress("TooGenericExceptionCaught")
    private fun systemctlQuery(vararg args: String): String? =
        try {
            val process =
                ProcessBuilder("systemctl", "--user", *args)
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val exited = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                null
            } else {
                output
            }
        } catch (_: Exception) {
            null
        }

    // -- Introspection --------------------------------------------------------

    private fun listScheduledTaskIds(): List<String> {
        val prefix = "$UNIT_PREFIX-$appId-"
        val dir = systemdUserDir
        if (!dir.isDirectory) return emptyList()
        return dir
            .listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".service") }
            ?.map { it.name.removePrefix(prefix).removeSuffix(".service") }
            ?.filter { !it.endsWith("-retry") }
            ?: emptyList()
    }

    private fun resolveState(taskId: String): TaskState {
        val activeState =
            systemctlQuery(
                "show",
                "-p",
                "ActiveState",
                "--value",
                serviceFileName(taskId),
            )
        return when (activeState) {
            "active", "activating" -> TaskState.RUNNING
            else -> TaskState.SCHEDULED
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun parseNextElapse(taskId: String): Long? {
        val raw =
            systemctlQuery(
                "show",
                "-p",
                "NextElapseUSecRealtime",
                "--value",
                timerFileName(taskId),
            ) ?: return null
        return try {
            // systemd returns microseconds since epoch
            val usec = raw.toLong()
            if (usec == 0L) null else usec / USEC_PER_MS
        } catch (_: Exception) {
            null
        }
    }
}
