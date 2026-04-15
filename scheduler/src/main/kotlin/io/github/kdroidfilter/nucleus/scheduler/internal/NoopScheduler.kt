package io.github.kdroidfilter.nucleus.scheduler.internal

import io.github.kdroidfilter.nucleus.scheduler.InternalSchedulerApi
import io.github.kdroidfilter.nucleus.scheduler.TaskInfo
import io.github.kdroidfilter.nucleus.scheduler.TaskRequest

/**
 * No-op scheduler for unsupported platforms.
 * All operations silently return failure/empty values.
 */
@OptIn(InternalSchedulerApi::class)
internal object NoopScheduler : PlatformScheduler {
    override fun enqueue(request: TaskRequest): Boolean = false

    override fun cancel(taskId: String): Boolean = false

    override fun cancelAll() = Unit

    override fun isScheduled(taskId: String): Boolean = false

    override fun getTaskInfo(taskId: String): TaskInfo? = null

    override fun getAllTasks(): List<TaskInfo> = emptyList()
}
