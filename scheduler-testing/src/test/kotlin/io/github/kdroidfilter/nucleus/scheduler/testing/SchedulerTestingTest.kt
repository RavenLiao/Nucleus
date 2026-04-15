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

// -- Sample tasks for testing -------------------------------------------------

class SuccessTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Success
}

class FailingTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult =
        TaskResult.Failure("something went wrong")
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

// -- Level 1: TestTaskRunner --------------------------------------------------

class TestTaskRunnerTest {

    @Test
    fun `runs a successful task`() = runBlocking {
        val result = TestTaskRunner.runTask(task = SuccessTask())
        assertEquals(TaskResult.Success, result)
    }

    @Test
    fun `runs a failing task`() = runBlocking {
        val result = TestTaskRunner.runTask(task = FailingTask())
        assertTrue(result is TaskResult.Failure)
        assertEquals("something went wrong", result.message)
    }

    @Test
    fun `passes input data to task context`() = runBlocking {
        val task = InputEchoTask()
        TestTaskRunner.runTask(
            task = task,
            inputData = mapOf("key" to "value"),
        )
        assertEquals(mapOf("key" to "value"), task.receivedData)
    }

    @Test
    fun `passes run attempt count to task context`() = runBlocking {
        val retryResult = TestTaskRunner.runTask(task = RetryTask(), runAttemptCount = 1)
        assertTrue(retryResult is TaskResult.Retry)

        val successResult = TestTaskRunner.runTask(task = RetryTask(), runAttemptCount = 3)
        assertEquals(TaskResult.Success, successResult)
    }
}

// -- Level 2: TestDesktopTaskScheduler ----------------------------------------

class TestDesktopTaskSchedulerTest {

    private val registry = TaskRegistry.Builder()
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
    fun `runTask executes task and updates info`() = runBlocking {
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
    fun `runTask tracks retry attempts`() = runBlocking {
        val scheduler = TestDesktopTaskScheduler()
        scheduler.install()
        try {
            DesktopTaskScheduler.enqueue(TaskRequest.periodic("retry", 1.hours))

            // First attempt — retry
            val r1 = scheduler.runTask("retry", registry)
            assertTrue(r1 is TaskResult.Retry)

            // Second attempt — still retry (attempt count = 2)
            val r2 = scheduler.runTask("retry", registry)
            assertTrue(r2 is TaskResult.Retry)

            // Third attempt — success (attempt count = 3)
            val r3 = scheduler.runTask("retry", registry)
            assertEquals(TaskResult.Success, r3)

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

            val request = TaskRequest.periodic("success", 1.hours) {
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

            DesktopTaskScheduler.enqueue(TaskRequest.periodic("success", 1.hours) {
                inputData("version", "1")
            })
            DesktopTaskScheduler.enqueue(TaskRequest.periodic("success", 2.hours) {
                inputData("version", "2")
            })

            val stored = scheduler.getEnqueuedRequest("success")
            assertNotNull(stored)
            assertEquals("1", stored.inputData["version"])
        }
    }

    @Test
    fun `REPLACE policy overwrites existing task`() {
        TestDesktopTaskScheduler().use { scheduler ->
            scheduler.install()

            DesktopTaskScheduler.enqueue(TaskRequest.periodic("success", 1.hours) {
                inputData("version", "1")
            })
            DesktopTaskScheduler.enqueue(TaskRequest.periodic("success", 2.hours) {
                inputData("version", "2")
                existingTaskPolicy(ExistingTaskPolicy.REPLACE)
            })

            val stored = scheduler.getEnqueuedRequest("success")
            assertNotNull(stored)
            assertEquals("2", stored.inputData["version"])
        }
    }

    @Test
    fun `input data flows through to runTask`() = runBlocking {
        val scheduler = TestDesktopTaskScheduler()
        scheduler.install()
        try {
            val task = InputEchoTask()
            val customRegistry = TaskRegistry.Builder()
                .register("echo") { task }
                .build()

            DesktopTaskScheduler.enqueue(TaskRequest.periodic("echo", 1.hours) {
                inputData("endpoint", "https://test.api")
                inputData("token", "abc123")
            })

            scheduler.runTask("echo", customRegistry)
            assertEquals("https://test.api", task.receivedData["endpoint"])
            assertEquals("abc123", task.receivedData["token"])
        } finally {
            scheduler.uninstall()
        }
    }
}
