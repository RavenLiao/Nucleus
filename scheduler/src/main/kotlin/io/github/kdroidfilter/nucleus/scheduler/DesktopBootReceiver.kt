package io.github.kdroidfilter.nucleus.scheduler

import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.scheduler.internal.LinuxSystemdScheduler
import io.github.kdroidfilter.nucleus.scheduler.internal.MacOSLaunchdScheduler
import io.github.kdroidfilter.nucleus.scheduler.internal.TaskMetadataStore
import io.github.kdroidfilter.nucleus.scheduler.internal.WindowsTaskScheduler
import kotlinx.coroutines.runBlocking
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Entry point for scheduler-triggered invocations.
 *
 * Place this check at the very top of `main()`:
 * ```kotlin
 * fun main(args: Array<String>) {
 *     if (DesktopBootReceiver.isSchedulerInvocation(args)) {
 *         DesktopBootReceiver.handle(args = args, registry = myRegistry)
 *         return   // <-- important: don't open the UI
 *     }
 *     // ... normal app startup ...
 * }
 * ```
 */
public object DesktopBootReceiver {
    private val logger = Logger.getLogger(DesktopBootReceiver::class.java.name)
    internal const val SCHEDULER_ARG = "--nucleus-scheduler-run"

    /**
     * Returns `true` if the current process was invoked by the Nucleus scheduler
     * (i.e. the args contain the scheduler trigger flag).
     */
    public fun isSchedulerInvocation(args: Array<String>): Boolean = args.contains(SCHEDULER_ARG)

    /**
     * Executes the scheduled task identified by the args.
     *
     * This method blocks until the task completes, then exits.
     *
     * @param args the command-line arguments passed to `main()`
     * @param registry the task registry mapping IDs to task factories
     */
    public fun handle(
        args: Array<String>,
        registry: TaskRegistry,
    ) {
        val argIndex = args.indexOf(SCHEDULER_ARG)
        val taskId = args.getOrNull(argIndex + 1)
        if (taskId == null) {
            logger.warning("$SCHEDULER_ARG flag present but no task ID provided")
            return
        }

        val appId = NucleusApp.appId

        val task =
            try {
                registry.create(taskId)
            } catch (e: TaskNotFoundException) {
                logger.log(Level.SEVERE, "Cannot resolve task '$taskId'", e)
                return
            }

        val context = TaskMetadataStore.loadContext(appId, taskId)

        val result =
            runBlocking {
                try {
                    task.doWork(context)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    logger.log(Level.SEVERE, "Task '$taskId' threw an exception", e)
                    TaskResult.Failure("Exception: ${e.message}")
                }
            }

        when (result) {
            is TaskResult.Success -> {
                TaskMetadataStore.recordSuccess(appId, taskId)
                logger.info("Task '$taskId' completed successfully")
            }
            is TaskResult.Retry -> handleRetry(appId, taskId, context, result)
            is TaskResult.Failure -> {
                TaskMetadataStore.recordFailure(appId, taskId, result.message)
                logger.warning("Task '$taskId' failed: ${result.message}")
            }
        }
    }

    private const val DEFAULT_RETRY_DELAY_SECONDS = 300L

    private fun handleRetry(
        appId: String,
        taskId: String,
        @Suppress("UnusedParameter") context: TaskContext,
        result: TaskResult.Retry,
    ) {
        TaskMetadataStore.recordRetry(appId, taskId, result.message)
        logger.info("Task '$taskId' requested retry: ${result.message}")

        // Retry scheduling is best-effort — the periodic timer will catch up eventually.
        // For explicit retry, we'd need the RetryPolicy from the original TaskRequest,
        // which we don't persist. The next periodic/calendar trigger will re-run the task
        // with an incremented runAttemptCount.
        when (Platform.Current) {
            Platform.Linux -> LinuxSystemdScheduler.scheduleRetry(taskId, DEFAULT_RETRY_DELAY_SECONDS)
            Platform.Windows -> WindowsTaskScheduler.scheduleRetry(taskId, DEFAULT_RETRY_DELAY_SECONDS)
            Platform.MacOS -> MacOSLaunchdScheduler.scheduleRetry(taskId, DEFAULT_RETRY_DELAY_SECONDS)
            else -> logger.warning("Retry scheduling not supported on ${Platform.Current}")
        }
    }
}
