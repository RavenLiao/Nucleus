package io.github.kdroidfilter.nucleus.scheduler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Execution context passed to [DesktopTask.doWork].
 *
 * @property taskId the unique identifier of this task
 * @property inputData the serialized input payload — decode it with [inputData]
 * @property runAttemptCount 1-based attempt counter (1 = first run, 2 = first retry, etc.)
 */
public data class TaskContext(
    val taskId: TaskId,
    val inputData: TaskData = TaskData.EMPTY,
    val runAttemptCount: Int = 1,
)

/**
 * Decodes the [TaskContext.inputData] payload as [T] using [serializer].
 *
 * Returns `null` when no payload was attached at enqueue time.
 */
public fun <T> TaskContext.inputData(serializer: KSerializer<T>): T? = inputData.decode(serializer)

/**
 * Decodes the [TaskContext.inputData] payload as [T] using the contextually-resolved serializer.
 *
 * Returns `null` when no payload was attached at enqueue time.
 */
public inline fun <reified T> TaskContext.inputData(): T? = inputData.decode(serializer<T>())
