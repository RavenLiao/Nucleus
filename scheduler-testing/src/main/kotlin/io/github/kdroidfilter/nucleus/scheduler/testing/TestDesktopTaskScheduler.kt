package io.github.kdroidfilter.nucleus.scheduler.testing

import io.github.kdroidfilter.nucleus.scheduler.Constraints
import io.github.kdroidfilter.nucleus.scheduler.DesktopTaskScheduler
import io.github.kdroidfilter.nucleus.scheduler.ExistingTaskPolicy
import io.github.kdroidfilter.nucleus.scheduler.InternalSchedulerApi
import io.github.kdroidfilter.nucleus.scheduler.TaskContext
import io.github.kdroidfilter.nucleus.scheduler.TaskId
import io.github.kdroidfilter.nucleus.scheduler.TaskInfo
import io.github.kdroidfilter.nucleus.scheduler.TaskRegistry
import io.github.kdroidfilter.nucleus.scheduler.TaskRequest
import io.github.kdroidfilter.nucleus.scheduler.TaskResult
import io.github.kdroidfilter.nucleus.scheduler.TaskState
import io.github.kdroidfilter.nucleus.scheduler.internal.ConstraintChecker
import io.github.kdroidfilter.nucleus.scheduler.internal.ConstraintResult
import io.github.kdroidfilter.nucleus.scheduler.internal.PlatformScheduler
import java.io.Closeable
import kotlin.time.Duration

/**
 * In-memory scheduler for integration tests.
 *
 * Replaces the real [DesktopTaskScheduler] backend so you can enqueue, query,
 * and execute tasks without touching the OS scheduler. Supports virtual time
 * advancement to test periodic and calendar schedules.
 *
 * ```kotlin
 * TestDesktopTaskScheduler().use { testScheduler ->
 *     testScheduler.install()
 *
 *     DesktopTaskScheduler.enqueue(TaskRequest.periodic("sync", 2.hours))
 *
 *     // Advance 6 hours — triggers 3 executions
 *     val results = testScheduler.advanceTimeBy(6.hours, registry)
 *     assertEquals(3, results.size)
 *
 *     // Inspect execution history
 *     val history = testScheduler.getExecutionHistory("sync")
 *     assertEquals(3, history.size)
 * }
 * ```
 */
@OptIn(InternalSchedulerApi::class)
public class TestDesktopTaskScheduler :
    PlatformScheduler,
    Closeable {
    private val tasks = mutableMapOf<TaskId, TaskRequest>()
    private val metadata = mutableMapOf<TaskId, TaskMetadata>()
    private val executionHistories = mutableMapOf<TaskId, MutableList<ExecutionRecord>>()
    private var virtualTimeMs: Long = 0L
    private val enqueueTimeMs = mutableMapOf<TaskId, Long>()

    /**
     * Constraint checker used to evaluate task constraints before execution.
     *
     * Defaults to an all-satisfied checker. Set a [TestConstraintChecker] to
     * simulate constraint failures.
     */
    public var constraintChecker: ConstraintChecker =
        object : ConstraintChecker {
            override fun check(constraints: Constraints): ConstraintResult =
                ConstraintResult(satisfied = true, unsatisfied = emptySet())
        }

    private data class TaskMetadata(
        var runCount: Int = 0,
        var runAttemptCount: Int = 1,
        var lastRunMs: Long? = null,
        var lastResult: String? = null,
    )

    /**
     * A recorded task execution.
     *
     * @property taskId the task that was executed
     * @property result the outcome of `doWork()`
     * @property runAttemptCount the attempt number at the time of execution (1-based)
     * @property virtualTimeMs the virtual time at which the execution occurred
     */
    public data class ExecutionRecord(
        public val taskId: TaskId,
        public val result: TaskResult,
        public val runAttemptCount: Int,
        public val virtualTimeMs: Long,
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
        enqueueTimeMs[request.taskId] = virtualTimeMs
        return true
    }

    override fun cancel(taskId: TaskId): Boolean {
        val removed = tasks.remove(taskId) != null
        if (removed) {
            metadata.remove(taskId)
            enqueueTimeMs.remove(taskId)
        }
        return removed
    }

    override fun cancelAll() {
        tasks.clear()
        metadata.clear()
        enqueueTimeMs.clear()
    }

    override fun isScheduled(taskId: TaskId): Boolean = taskId in tasks

    override fun getTaskInfo(taskId: TaskId): TaskInfo? {
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
     * If the task has [Constraints] and the [constraintChecker] reports them as unsatisfied,
     * the task is **not** executed. For periodic tasks this returns `null` (silent skip).
     * For calendar/on-boot tasks this returns [TaskResult.Retry].
     *
     * @throws IllegalStateException if the task is not enqueued
     * @throws io.github.kdroidfilter.nucleus.scheduler.TaskNotFoundException if [taskId] is not in [registry]
     */
    public suspend fun runTask(
        taskId: TaskId,
        registry: TaskRegistry,
    ): TaskResult? {
        val request =
            tasks[taskId]
                ?: error("Task '$taskId' is not enqueued in the test scheduler")
        val meta = metadata.getOrPut(taskId) { TaskMetadata() }

        // Check constraints before executing
        if (request.constraints.hasConstraints()) {
            val checkResult = constraintChecker.check(request.constraints)
            if (!checkResult.satisfied) {
                val isPeriodic = request.type == TaskRequest.Type.PERIODIC
                if (isPeriodic) {
                    meta.lastRunMs = virtualTimeMs
                    meta.lastResult = "ConstraintsNotMet: ${checkResult.unsatisfied}"
                    return null
                } else {
                    val retryResult = TaskResult.Retry("Constraints not met: ${checkResult.unsatisfied}")
                    val record =
                        ExecutionRecord(
                            taskId = taskId,
                            result = retryResult,
                            runAttemptCount = meta.runAttemptCount,
                            virtualTimeMs = virtualTimeMs,
                        )
                    executionHistories.getOrPut(taskId) { mutableListOf() }.add(record)
                    meta.runAttemptCount++
                    meta.lastRunMs = virtualTimeMs
                    meta.lastResult = "Retry: Constraints not met: ${checkResult.unsatisfied}"
                    return retryResult
                }
            }
        }

        val context =
            TaskContext(
                taskId = taskId,
                inputData = request.inputData,
                runAttemptCount = meta.runAttemptCount,
            )

        val task = registry.create(taskId)
        val result = task.doWork(context)

        val record =
            ExecutionRecord(
                taskId = taskId,
                result = result,
                runAttemptCount = meta.runAttemptCount,
                virtualTimeMs = virtualTimeMs,
            )
        executionHistories.getOrPut(taskId) { mutableListOf() }.add(record)

        when (result) {
            is TaskResult.Success -> {
                meta.runCount++
                meta.runAttemptCount = 1
                meta.lastRunMs = virtualTimeMs
                meta.lastResult = "Success"
            }
            is TaskResult.Retry -> {
                meta.runAttemptCount++
                meta.lastRunMs = virtualTimeMs
                meta.lastResult = "Retry: ${result.message}"
            }
            is TaskResult.Failure -> {
                meta.runAttemptCount = 1
                meta.lastRunMs = virtualTimeMs
                meta.lastResult = "Failure: ${result.message}"
            }
        }

        return result
    }

    // -- Virtual time ---------------------------------------------------------

    /**
     * Returns the current virtual time.
     */
    public val currentVirtualTimeMs: Long get() = virtualTimeMs

    /**
     * Advances virtual time by [duration] and executes all periodic tasks whose
     * interval has elapsed during that window.
     *
     * For a task with interval `I` enqueued at virtual time `T`, executions
     * occur at `T + I`, `T + 2I`, etc. Only periodic tasks are triggered;
     * calendar and on-boot tasks are skipped (use [runTask] for those).
     *
     * Returns all [ExecutionRecord]s produced during the time advancement,
     * in chronological order.
     *
     * ```kotlin
     * DesktopTaskScheduler.enqueue(TaskRequest.periodic("sync", 2.hours))
     * val results = testScheduler.advanceTimeBy(6.hours, registry)
     * assertEquals(3, results.size)
     * ```
     */
    public suspend fun advanceTimeBy(
        duration: Duration,
        registry: TaskRegistry,
    ): List<ExecutionRecord> {
        val targetMs = virtualTimeMs + duration.inWholeMilliseconds
        val records = mutableListOf<ExecutionRecord>()

        // Collect all periodic tasks and compute their fire times
        data class PendingFire(
            val taskId: TaskId,
            val fireAtMs: Long,
        )

        val fires = mutableListOf<PendingFire>()

        for ((taskId, request) in tasks) {
            val interval = request.interval
            if (request.type != TaskRequest.Type.PERIODIC || interval == null) continue
            val intervalMs = interval.inWholeMilliseconds
            val baseMs = enqueueTimeMs[taskId] ?: 0L

            // Compute the next fire time after current virtualTimeMs
            val elapsedSinceEnqueue = virtualTimeMs - baseMs
            val nextIndex = (elapsedSinceEnqueue / intervalMs) + 1
            var nextFireMs = baseMs + nextIndex * intervalMs

            while (nextFireMs <= targetMs) {
                fires.add(PendingFire(taskId, nextFireMs))
                nextFireMs += intervalMs
            }
        }

        // Sort chronologically and execute
        fires.sortBy { it.fireAtMs }

        for (fire in fires) {
            // Task may have been cancelled by a previous execution
            if (fire.taskId !in tasks) continue
            virtualTimeMs = fire.fireAtMs
            val result = runTask(fire.taskId, registry)
            if (result != null) {
                val history = executionHistories[fire.taskId]
                if (history != null && history.isNotEmpty()) {
                    records.add(history.last())
                }
            }
        }

        virtualTimeMs = targetMs
        return records
    }

    // -- Execution history ----------------------------------------------------

    /**
     * Returns the full execution history for [taskId], in chronological order.
     *
     * Each entry records the [TaskResult], `runAttemptCount`, and virtual time
     * at which the execution occurred.
     *
     * ```kotlin
     * val history = testScheduler.getExecutionHistory("sync")
     * assertEquals(TaskResult.Success, history.last().result)
     * assertEquals(1, history.last().runAttemptCount)
     * ```
     */
    public fun getExecutionHistory(taskId: TaskId): List<ExecutionRecord> =
        executionHistories[taskId]?.toList() ?: emptyList()

    /**
     * Returns the full execution history across all tasks, in chronological order.
     */
    public fun getAllExecutionHistory(): List<ExecutionRecord> =
        executionHistories.values.flatten().sortedBy { it.virtualTimeMs }

    // -- Enqueue introspection ------------------------------------------------

    /**
     * Returns the [TaskRequest] for an enqueued task, or `null` if not found.
     */
    public fun getEnqueuedRequest(taskId: TaskId): TaskRequest? = tasks[taskId]

    /**
     * Returns all currently enqueued [TaskRequest]s.
     */
    public fun getEnqueuedRequests(): List<TaskRequest> = tasks.values.toList()
}
