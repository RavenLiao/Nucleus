package io.github.kdroidfilter.nucleus.scheduler.testing

import io.github.kdroidfilter.nucleus.scheduler.DesktopTask
import io.github.kdroidfilter.nucleus.scheduler.TaskContext
import io.github.kdroidfilter.nucleus.scheduler.TaskData
import io.github.kdroidfilter.nucleus.scheduler.TaskId
import io.github.kdroidfilter.nucleus.scheduler.TaskResult

/**
 * Runs a [DesktopTask] in isolation without any OS scheduler involvement.
 *
 * Use this to unit-test your task's `doWork()` logic with a controlled [TaskContext]:
 *
 * ```kotlin
 * val result = TestTaskRunner.runTask(
 *     task = SyncTask(),
 *     taskId = "sync",
 *     inputData = TaskData.Builder().putString("endpoint", "https://test.api").build(),
 * )
 * assertEquals(TaskResult.Success, result)
 * ```
 */
public object TestTaskRunner {
    /**
     * Executes [task]`.doWork()` with a fabricated [TaskContext] and returns the result.
     *
     * @param task the task to execute
     * @param taskId the task identifier passed in the context (default: `"test-task"`)
     * @param inputData typed key-value data available via [TaskContext.inputData]
     * @param runAttemptCount 1-based attempt counter (default: `1`)
     */
    public suspend fun runTask(
        task: DesktopTask,
        taskId: TaskId = TaskId("test-task"),
        inputData: TaskData = TaskData.EMPTY,
        runAttemptCount: Int = 1,
    ): TaskResult {
        val context =
            TaskContext(
                taskId = taskId,
                inputData = inputData,
                runAttemptCount = runAttemptCount,
            )
        return task.doWork(context)
    }
}
