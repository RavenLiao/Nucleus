package io.github.kdroidfilter.nucleus.scheduler.testing

import io.github.kdroidfilter.nucleus.scheduler.DesktopTask
import io.github.kdroidfilter.nucleus.scheduler.DesktopTaskScheduler
import io.github.kdroidfilter.nucleus.scheduler.ExistingTaskPolicy
import io.github.kdroidfilter.nucleus.scheduler.TaskContext
import io.github.kdroidfilter.nucleus.scheduler.TaskRegistry
import io.github.kdroidfilter.nucleus.scheduler.TaskRequest
import io.github.kdroidfilter.nucleus.scheduler.TaskResult
import io.github.kdroidfilter.nucleus.scheduler.TaskState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// -- Sample tasks for testing -------------------------------------------------

class SuccessTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Success
}

class FailingTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Failure("something went wrong")
}

class RetryTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult =
        if (context.runAttemptCount < 3) {
            TaskResult.Retry("not ready yet")
        } else {
            TaskResult.Success
        }
}

class InputEchoTask : DesktopTask {
    var receivedData: Map<String, String> = emptyMap()

    override suspend fun doWork(context: TaskContext): TaskResult {
        receivedData = context.inputData
        return TaskResult.Success
    }
}

class CountingTask : DesktopTask {
    var executions = 0

    override suspend fun doWork(context: TaskContext): TaskResult {
        executions++
        return TaskResult.Success
    }
}

// -- Level 1: TestTaskRunner --------------------------------------------------

class TestTaskRunnerTest {
    @Test
    fun `runs a successful task`() =
        runBlocking {
            val result = TestTaskRunner.runTask(task = SuccessTask())
            assertEquals(TaskResult.Success, result)
        }

    @Test
    fun `runs a failing task`() =
        runBlocking {
            val result = TestTaskRunner.runTask(task = FailingTask())
            assertTrue(result is TaskResult.Failure)
            assertEquals("something went wrong", result.message)
        }

    @Test
    fun `passes input data to task context`() =
        runBlocking {
            val task = InputEchoTask()
            TestTaskRunner.runTask(
                task = task,
                inputData = mapOf("key" to "value"),
            )
            assertEquals(mapOf("key" to "value"), task.receivedData)
        }

    @Test
    fun `passes run attempt count to task context`() =
        runBlocking {
            val retryResult = TestTaskRunner.runTask(task = RetryTask(), runAttemptCount = 1)
            assertTrue(retryResult is TaskResult.Retry)

            val successResult = TestTaskRunner.runTask(task = RetryTask(), runAttemptCount = 3)
            assertEquals(TaskResult.Success, successResult)
        }
}

// -- Level 2: TestDesktopTaskScheduler ----------------------------------------

class TestDesktopTaskSchedulerTest {
    private val registry =
        TaskRegistry
            .Builder()
            .register("success") { SuccessTask() }
            .register("failing") { FailingTask() }
            .register("retry") { RetryTask() }
            .build()

    @Test
    fun `enqueue and isScheduled`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(TaskRequest.periodic("success", 1.hours))
            assertTrue(DesktopTaskScheduler.isScheduled("success"))
            assertFalse(DesktopTaskScheduler.isScheduled("unknown"))
        }
    }

    @Test
    fun `cancel removes task`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(TaskRequest.periodic("success", 1.hours))
            assertTrue(DesktopTaskScheduler.cancel("success"))
            assertFalse(DesktopTaskScheduler.isScheduled("success"))
            assertFalse(DesktopTaskScheduler.cancel("success"))
        }
    }

    @Test
    fun `cancelAll clears everything`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(TaskRequest.periodic("success", 1.hours))
            DesktopTaskScheduler.enqueue(TaskRequest.onBoot("retry"))
            assertEquals(2, DesktopTaskScheduler.getAllTasks().size)

            DesktopTaskScheduler.cancelAll()
            assertTrue(DesktopTaskScheduler.getAllTasks().isEmpty())
        }
    }

    @Test
    fun `runTask executes task and updates info`() =
        runBlocking {
            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("success", 1.hours))

                val result = scheduler.runTask("success", registry)
                assertEquals(TaskResult.Success, result)

                val info = DesktopTaskScheduler.getTaskInfo("success")
                assertNotNull(info)
                assertEquals(1, info.runCount)
                assertEquals("Success", info.lastResult)
                assertEquals(TaskState.SCHEDULED, info.state)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `runTask tracks retry attempts automatically`() =
        runBlocking {
            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("retry", 1.hours))

                // First attempt — retry (runAttemptCount = 1)
                val r1 = scheduler.runTask("retry", registry)
                assertTrue(r1 is TaskResult.Retry)

                // Second attempt — retry (runAttemptCount auto-incremented to 2)
                val r2 = scheduler.runTask("retry", registry)
                assertTrue(r2 is TaskResult.Retry)

                // Third attempt — success (runAttemptCount auto-incremented to 3)
                val r3 = scheduler.runTask("retry", registry)
                assertEquals(TaskResult.Success, r3)

                // Verify via execution history
                val history = scheduler.getExecutionHistory("retry")
                assertEquals(3, history.size)
                assertEquals(1, history[0].runAttemptCount)
                assertEquals(2, history[1].runAttemptCount)
                assertEquals(3, history[2].runAttemptCount)

                val info = DesktopTaskScheduler.getTaskInfo("retry")
                assertNotNull(info)
                assertEquals(1, info.runCount)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `getEnqueuedRequest returns request`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            val request =
                TaskRequest.periodic("success", 1.hours) {
                    inputData("key", "value")
                }
            DesktopTaskScheduler.enqueue(request)

            val stored = scheduler.getEnqueuedRequest("success")
            assertNotNull(stored)
            assertEquals("value", stored.inputData["key"])
            assertNull(scheduler.getEnqueuedRequest("unknown"))
        }
    }

    @Test
    fun `KEEP policy does not replace existing task`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic("success", 1.hours) {
                    inputData("version", "1")
                },
            )
            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic("success", 2.hours) {
                    inputData("version", "2")
                },
            )

            val stored = scheduler.getEnqueuedRequest("success")
            assertNotNull(stored)
            assertEquals("1", stored.inputData["version"])
        }
    }

    @Test
    fun `REPLACE policy overwrites existing task`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic("success", 1.hours) {
                    inputData("version", "1")
                },
            )
            DesktopTaskScheduler.enqueue(
                TaskRequest.periodic("success", 2.hours) {
                    inputData("version", "2")
                    existingTaskPolicy(ExistingTaskPolicy.REPLACE)
                },
            )

            val stored = scheduler.getEnqueuedRequest("success")
            assertNotNull(stored)
            assertEquals("2", stored.inputData["version"])
        }
    }

    @Test
    fun `input data flows through to runTask`() =
        runBlocking {
            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                val task = InputEchoTask()
                val customRegistry =
                    TaskRegistry
                        .Builder()
                        .register("echo") { task }
                        .build()

                DesktopTaskScheduler.enqueue(
                    TaskRequest.periodic("echo", 1.hours) {
                        inputData("endpoint", "https://test.api")
                        inputData("token", "abc123")
                    },
                )

                scheduler.runTask("echo", customRegistry)
                assertEquals("https://test.api", task.receivedData["endpoint"])
                assertEquals("abc123", task.receivedData["token"])
            } finally {
                scheduler.uninstall()
            }
        }
}

// -- advanceTimeBy tests ------------------------------------------------------

class AdvanceTimeTest {
    @Test
    fun `advanceTimeBy triggers periodic task at correct intervals`() =
        runBlocking {
            val task = CountingTask()
            val registry =
                TaskRegistry
                    .Builder()
                    .register("counter") { task }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("counter", 2.hours))

                val results = scheduler.advanceTimeBy(6.hours, registry)
                assertEquals(3, results.size)
                assertEquals(3, task.executions)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy does not trigger before first interval`() =
        runBlocking {
            val task = CountingTask()
            val registry =
                TaskRegistry
                    .Builder()
                    .register("counter") { task }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("counter", 2.hours))

                val results = scheduler.advanceTimeBy(90.minutes, registry)
                assertEquals(0, results.size)
                assertEquals(0, task.executions)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy fires exactly on interval boundary`() =
        runBlocking {
            val task = CountingTask()
            val registry =
                TaskRegistry
                    .Builder()
                    .register("counter") { task }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("counter", 1.hours))

                // Exactly 1 hour = exactly 1 fire
                val results = scheduler.advanceTimeBy(1.hours, registry)
                assertEquals(1, results.size)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy with multiple tasks`() =
        runBlocking {
            val fastTask = CountingTask()
            val slowTask = CountingTask()
            val registry =
                TaskRegistry
                    .Builder()
                    .register("fast") { fastTask }
                    .register("slow") { slowTask }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("fast", 1.hours))
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("slow", 3.hours))

                val results = scheduler.advanceTimeBy(6.hours, registry)
                // fast: 1h, 2h, 3h, 4h, 5h, 6h = 6 fires
                // slow: 3h, 6h = 2 fires
                assertEquals(8, results.size)
                assertEquals(6, fastTask.executions)
                assertEquals(2, slowTask.executions)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy records virtual time in execution history`() =
        runBlocking {
            val registry =
                TaskRegistry
                    .Builder()
                    .register("task") { SuccessTask() }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("task", 1.hours))

                scheduler.advanceTimeBy(3.hours, registry)

                val history = scheduler.getExecutionHistory("task")
                assertEquals(3, history.size)
                assertEquals(1.hours.inWholeMilliseconds, history[0].virtualTimeMs)
                assertEquals(2.hours.inWholeMilliseconds, history[1].virtualTimeMs)
                assertEquals(3.hours.inWholeMilliseconds, history[2].virtualTimeMs)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy skips non-periodic tasks`() =
        runBlocking {
            val registry =
                TaskRegistry
                    .Builder()
                    .register("boot") { SuccessTask() }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.onBoot("boot"))

                val results = scheduler.advanceTimeBy(5.hours, registry)
                assertEquals(0, results.size)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `advanceTimeBy with retry tracks attempt count across fires`() =
        runBlocking {
            // Task retries twice then succeeds on 3rd attempt
            val registry =
                TaskRegistry
                    .Builder()
                    .register("flaky") { RetryTask() }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("flaky", 1.hours))

                // First fire at 1h — retry (attempt 1)
                scheduler.advanceTimeBy(1.hours, registry)
                var history = scheduler.getExecutionHistory("flaky")
                assertEquals(1, history.size)
                assertTrue(history[0].result is TaskResult.Retry)
                assertEquals(1, history[0].runAttemptCount)

                // Second fire at 2h — retry (attempt 2, auto-incremented)
                scheduler.advanceTimeBy(1.hours, registry)
                history = scheduler.getExecutionHistory("flaky")
                assertEquals(2, history.size)
                assertTrue(history[1].result is TaskResult.Retry)
                assertEquals(2, history[1].runAttemptCount)

                // Third fire at 3h — success (attempt 3, auto-incremented)
                scheduler.advanceTimeBy(1.hours, registry)
                history = scheduler.getExecutionHistory("flaky")
                assertEquals(3, history.size)
                assertEquals(TaskResult.Success, history[2].result)
                assertEquals(3, history[2].runAttemptCount)
            } finally {
                scheduler.uninstall()
            }
        }

    @Test
    fun `getAllExecutionHistory returns chronologically sorted records`() =
        runBlocking {
            val registry =
                TaskRegistry
                    .Builder()
                    .register("a") { SuccessTask() }
                    .register("b") { SuccessTask() }
                    .build()

            val scheduler = TestDesktopTaskScheduler()
            scheduler.install()
            try {
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("a", 1.hours))
                DesktopTaskScheduler.enqueue(TaskRequest.periodic("b", 2.hours))

                scheduler.advanceTimeBy(4.hours, registry)

                val all = scheduler.getAllExecutionHistory()
                // a: 1h, 2h, 3h, 4h = 4 fires; b: 2h, 4h = 2 fires
                assertEquals(6, all.size)
                // Verify chronological order
                for (i in 1 until all.size) {
                    assertTrue(all[i].virtualTimeMs >= all[i - 1].virtualTimeMs)
                }
            } finally {
                scheduler.uninstall()
            }
        }
}
