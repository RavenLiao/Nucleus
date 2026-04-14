package io.github.kdroidfilter.nucleus.scheduler

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.scheduler.internal.LinuxSystemdScheduler
import io.github.kdroidfilter.nucleus.scheduler.internal.MacOSLaunchdScheduler
import io.github.kdroidfilter.nucleus.scheduler.internal.NoopScheduler
import io.github.kdroidfilter.nucleus.scheduler.internal.PlatformScheduler
import io.github.kdroidfilter.nucleus.scheduler.internal.WindowsTaskScheduler

/**
 * Schedules background tasks with the OS so they run even when the app is closed.
 *
 * On Linux, this uses systemd user timers. On other platforms, all operations
 * are no-ops that return `false` / empty results.
 *
 * ```kotlin
 * val scheduler = DesktopTaskScheduler.getInstance()
 * scheduler.enqueue(TaskRequest.periodic("sync", 1.hours))
 * ```
 */
public object DesktopTaskScheduler {
    private val delegate: PlatformScheduler =
        when (Platform.Current) {
            Platform.Linux -> LinuxSystemdScheduler
            Platform.Windows -> WindowsTaskScheduler
            Platform.MacOS -> MacOSLaunchdScheduler
            else -> NoopScheduler
        }

    /**
     * Returns the singleton scheduler instance.
     *
     * Kept for API parity with the Android WorkManager style — calling
     * `DesktopTaskScheduler.getInstance()` is equivalent to using the object directly.
     */
    @JvmStatic
    public fun getInstance(): DesktopTaskScheduler = this

    /**
     * Returns `true` if scheduling is available on this platform.
     */
    @JvmStatic
    public fun isAvailable(): Boolean = delegate !is NoopScheduler

    /**
     * Registers a task with the OS scheduler.
     *
     * By default, if a task with the same ID is already scheduled, this is a no-op
     * (returns `true`). Use [ExistingTaskPolicy.REPLACE] in the [TaskRequest] builder
     * to overwrite the existing schedule.
     *
     * @return `true` if the task was successfully scheduled (or already existed)
     */
    @JvmStatic
    public fun enqueue(request: TaskRequest): Boolean = delegate.enqueue(request)

    /**
     * Cancels a scheduled task.
     *
     * @return `true` if the task was found and removed
     */
    @JvmStatic
    public fun cancel(taskId: String): Boolean = delegate.cancel(taskId)

    /**
     * Cancels all tasks belonging to this application.
     */
    @JvmStatic
    public fun cancelAll(): Unit = delegate.cancelAll()

    /**
     * Returns `true` if the given task is currently scheduled.
     */
    @JvmStatic
    public fun isScheduled(taskId: String): Boolean = delegate.isScheduled(taskId)

    /**
     * Returns detailed runtime info about a task, or `null` if not found.
     */
    @JvmStatic
    public fun getTaskInfo(taskId: String): TaskInfo? = delegate.getTaskInfo(taskId)

    /**
     * Returns info for all tasks registered by this application.
     */
    @JvmStatic
    public fun getAllTasks(): List<TaskInfo> = delegate.getAllTasks()
}
