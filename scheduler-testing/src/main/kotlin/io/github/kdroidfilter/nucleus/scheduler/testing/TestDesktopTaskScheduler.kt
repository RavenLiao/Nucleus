package io.github.kdroidfilter.nucleus.scheduler.testing

import io.github.kdroidfilter.nucleus.scheduler.DesktopTaskScheduler
import io.github.kdroidfilter.nucleus.scheduler.ExistingTaskPolicy
import io.github.kdroidfilter.nucleus.scheduler.InternalSchedulerApi
import io.github.kdroidfilter.nucleus.scheduler.TaskContext
import io.github.kdroidfilter.nucleus.scheduler.TaskInfo
import io.github.kdroidfilter.nucleus.scheduler.TaskRegistry
import io.github.kdroidfilter.nucleus.scheduler.TaskRequest
import io.github.kdroidfilter.nucleus.scheduler.TaskResult
import io.github.kdroidfilter.nucleus.scheduler.TaskState
import io.github.kdroidfilter.nucleus.scheduler.internal.PlatformScheduler
import java.io.Closeable

/**
 * In-memory scheduler for integration tests.
 *
 * Replaces the real [DesktopTaskScheduler] backend so you can enqueue, query,
 * and execute tasks without touching the OS scheduler.
 *
 * ```kotlin
 * val testScheduler = TestDesktopTaskScheduler()
 * testScheduler.install()
 *
 * DesktopTaskScheduler.enqueue(TaskRequest.periodic("sync", 1.hours))
 * assertTrue(DesktopTaskScheduler.isScheduled("sync"))
 *
 * val result = testScheduler.runTask("sync", registry)
 * assertEquals(TaskResult.Success, result)
 *
 * testScheduler.close() // restores platform default
 * ```
 */
@OptIn(InternalSchedulerApi::class)
public class TestDesktopTaskScheduler : PlatformScheduler, Closeable {

    private val tasks = mutableMapOf<String, TaskRequest>()
    private val metadata = mutableMapOf<String, TaskMetadata>()

    private data class TaskMetadata(
        var runCount: Int = 0,
        var runAttemptCount: Int = 1,
        var lastRunMs: Long? = null,
        var lastResult: String? = null,
    )

    // -- Install / Uninstall --------------------------------------------------

    /**
     * Installs this test scheduler as the active backend for [DesktopTaskScheduler].
     *
     * After calling this, all calls to `DesktopTaskScheduler.enqueue()`, `.cancel()`, etc.
     * are routed to this in-memory implementation.
     */
    public fun install() {
        DesktopTaskScheduler.setTestDelegate(this)
    }

    /**
     * Restores the platform-default scheduler backend.
     */
    public fun uninstall() {
        DesktopTaskScheduler.resetDelegate()
    }

    override fun close() {
        uninstall()
    }

    // -- PlatformScheduler implementation -------------------------------------

    override fun enqueue(request: TaskRequest): Boolean {
        val existing = tasks[request.taskId]
        if (existing != null && request.existingTaskPolicy == ExistingTaskPolicy.KEEP) {
            return true
        }
        tasks[request.taskId] = request
        metadata.getOrPut(request.taskId) { TaskMetadata() }
        return true
    }

    override fun cancel(taskId: String): Boolean {
        val removed = tasks.remove(taskId) != null
        if (removed) metadata.remove(taskId)
        return removed
    }

    override fun cancelAll() {
        tasks.clear()
        metadata.clear()
    }

    override fun isScheduled(taskId: String): Boolean = taskId in tasks

    override fun getTaskInfo(taskId: String): TaskInfo? {
        if (taskId !in tasks) return null
        val meta = metadata[taskId] ?: return null
        return TaskInfo(
            taskId = taskId,
            state = TaskState.SCHEDULED,
            lastRunMs = meta.lastRunMs,
            nextRunMs = null,
            runCount = meta.runCount,
            lastResult = meta.lastResult,
        )
    }

    override fun getAllTasks(): List<TaskInfo> = tasks.keys.mapNotNull { getTaskInfo(it) }

    // -- Test helpers ---------------------------------------------------------

    /**
     * Immediately executes the task identified by [taskId] using the given [registry].
     *
     * Builds a [TaskContext] from the stored input data, calls `doWork()`, and
     * records the result in memory (accessible via [getTaskInfo]).
     *
     * @throws IllegalStateException if the task is not enqueued
     * @throws io.github.kdroidfilter.nucleus.scheduler.TaskNotFoundException if [taskId] is not in [registry]
     */
    public suspend fun runTask(
        taskId: String,
        registry: TaskRegistry,
    ): TaskResult {
        val request = tasks[taskId]
            ?: error("Task '$taskId' is not enqueued in the test scheduler")
        val meta = metadata.getOrPut(taskId) { TaskMetadata() }

        val context = TaskContext(
            taskId = taskId,
            inputData = request.inputData,
            runAttemptCount = meta.runAttemptCount,
        )

        val task = registry.create(taskId)
        val result = task.doWork(context)

        when (result) {
            is TaskResult.Success -> {
                meta.runCount++
                meta.runAttemptCount = 1
                meta.lastRunMs = System.currentTimeMillis()
                meta.lastResult = "Success"
            }
            is TaskResult.Retry -> {
                meta.runAttemptCount++
                meta.lastRunMs = System.currentTimeMillis()
                meta.lastResult = "Retry: ${result.message}"
            }
            is TaskResult.Failure -> {
                meta.runAttemptCount = 1
                meta.lastRunMs = System.currentTimeMillis()
                meta.lastResult = "Failure: ${result.message}"
            }
        }

        return result
    }

    /**
     * Returns the [TaskRequest] for an enqueued task, or `null` if not found.
     */
    public fun getEnqueuedRequest(taskId: String): TaskRequest? = tasks[taskId]

    /**
     * Returns all currently enqueued [TaskRequest]s.
     */
    public fun getEnqueuedRequests(): List<TaskRequest> = tasks.values.toList()
}
