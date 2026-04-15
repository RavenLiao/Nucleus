# Scheduler

The `scheduler` module registers background tasks with the OS so they run even when the application is closed. It provides a unified API across platforms, delegating to the native scheduler on each OS.

!!! warning "Not compatible with Mac App Store (.pkg)"
    On macOS, the scheduler writes plist files to `~/Library/LaunchAgents/` at runtime. This is **not allowed** in sandboxed App Store apps. If you distribute via `.pkg` / Mac App Store, use the [Service Management (macOS)](service-management-macos.md) module instead — it embeds agents in the app bundle and registers them via `SMAppService`, which is sandbox-compatible.

### Platform support

| Feature | Windows | macOS | Linux |
|---------|---------|-------|-------|
| Periodic tasks | Task Scheduler | launchd `StartInterval` | systemd user timers |
| Calendar tasks | Task Scheduler triggers | launchd `StartCalendarInterval` | systemd `OnCalendar=` |
| On-boot / login tasks | Task Scheduler logon trigger | launchd `RunAtLoad` | systemd `default.target` |
| Retry scheduling | One-shot task | One-shot launchd agent | One-shot systemd timer |
| Minimum interval | 15 minutes (enforced by `require`) | 15 minutes (enforced by `require`) | 15 minutes (enforced by `require`) |

## Installation

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.scheduler:<version>")
}
```

## Quick Start

### 1. Define a task

Implement the `DesktopTask` interface:

```kotlin
class SyncTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult {
        // Your background work here
        return TaskResult.Success
    }
}
```

### 2. Build a task registry

Map task IDs to their factories:

```kotlin
val registry = TaskRegistry.Builder()
    .register("sync") { SyncTask() }
    .register("backup") { BackupTask() }
    .build()
```

### 3. Handle scheduler invocations in `main()`

When the OS triggers a task, it re-launches your application with special arguments. You must detect this at the very top of `main()`:

```kotlin
fun main(args: Array<String>) {
    if (DesktopBootReceiver.isSchedulerInvocation(args)) {
        DesktopBootReceiver.handle(args = args, registry = registry)
        return // Don't open the UI
    }

    // Normal app startup...
}
```

!!! warning "This check must be at the top of `main()`"
    If you initialize the UI before checking for scheduler invocations, the app will open a window every time a background task fires.

### 4. Schedule tasks

```kotlin
val scheduler = DesktopTaskScheduler

// Periodic task — every hour
scheduler.enqueue(TaskRequest.periodic("sync", 1.hours))

// Calendar task — every day at 9:00
scheduler.enqueue(TaskRequest.calendar("report", CronExpression.everyDayAt(9)))

// Run at login
scheduler.enqueue(TaskRequest.onBoot("startup-check"))
```

## Usage

### Periodic tasks

Repeat at a fixed interval (minimum 15 minutes):

```kotlin
scheduler.enqueue(
    TaskRequest.periodic("backup", 30.minutes) {
        inputData("target", "/tmp/backup")
        retryPolicy(RetryPolicy.ExponentialBackoff())
        existingTaskPolicy(ExistingTaskPolicy.REPLACE)
    }
)
```

### Calendar tasks

Fire on a calendar schedule using `CronExpression`:

```kotlin
// Every day at 9:00
scheduler.enqueue(TaskRequest.calendar("report", CronExpression.everyDayAt(9)))

// Weekdays at 18:00
scheduler.enqueue(TaskRequest.calendar("cleanup", CronExpression.everyWeekdayAt(18)))

// Every Monday at 8:30
scheduler.enqueue(
    TaskRequest.calendar("weekly", CronExpression.everyWeekdayAt(DayOfWeek.MONDAY, 8, 30))
)

// Every hour
scheduler.enqueue(TaskRequest.calendar("heartbeat", CronExpression.everyHour()))
```

### On-boot tasks

Run once at user login:

```kotlin
scheduler.enqueue(TaskRequest.onBoot("login-sync"))
```

### Input data

Pass key-value pairs to tasks at enqueue time, then read them in `doWork()`:

```kotlin
// Enqueue
scheduler.enqueue(
    TaskRequest.periodic("sync", 1.hours) {
        inputData("endpoint", "https://api.example.com")
        inputData("token", "abc123")
    }
)

// In the task
class SyncTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult {
        val endpoint = context.inputData["endpoint"]
        val token = context.inputData["token"]
        // ...
        return TaskResult.Success
    }
}
```

### Task results and retry

Return `TaskResult.Success`, `TaskResult.Failure`, or `TaskResult.Retry` from `doWork()`:

```kotlin
class SyncTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult {
        return try {
            performSync()
            TaskResult.Success
        } catch (e: IOException) {
            if (context.runAttemptCount < 3) {
                TaskResult.Retry("Network error: ${e.message}")
            } else {
                TaskResult.Failure("Gave up after ${context.runAttemptCount} attempts")
            }
        }
    }
}
```

Configure retry behavior at enqueue time:

```kotlin
scheduler.enqueue(
    TaskRequest.periodic("sync", 1.hours) {
        retryPolicy(RetryPolicy.ExponentialBackoff(
            initialDelay = 30.minutes,
            maxAttempts = 3
        ))
        // Or fixed delay:
        // retryPolicy(RetryPolicy.Linear(delay = 15.minutes, maxAttempts = 5))
    }
)
```

### Managing tasks

```kotlin
// Check if available on this platform
scheduler.isAvailable()

// Check if a specific task is scheduled
scheduler.isScheduled("sync")

// Get task info
val info: TaskInfo? = scheduler.getTaskInfo("sync")
info?.let {
    println("State: ${it.state}")       // SCHEDULED, RUNNING, or INACTIVE
    println("Run count: ${it.runCount}")
    println("Last result: ${it.lastResult}")
}

// List all tasks
val all: List<TaskInfo> = scheduler.getAllTasks()

// Cancel a task
scheduler.cancel("sync")

// Cancel all tasks
scheduler.cancelAll()
```

### Existing task policy

Control what happens when you enqueue a task ID that's already scheduled:

```kotlin
// Default: keep the existing schedule, just update input data
scheduler.enqueue(TaskRequest.periodic("sync", 1.hours))

// Replace: unload the existing task and re-register with new settings
scheduler.enqueue(
    TaskRequest.periodic("sync", 2.hours) {
        existingTaskPolicy(ExistingTaskPolicy.REPLACE)
    }
)
```

## API Reference

### `DesktopTaskScheduler`

| Method | Returns | Description |
|--------|---------|-------------|
| `isAvailable()` | `Boolean` | `true` if the platform has a supported scheduler backend. |
| `enqueue(request)` | `Boolean` | Registers a task with the OS. Returns `true` on success. |
| `cancel(taskId)` | `Boolean` | Removes a scheduled task. Returns `true` if found. |
| `cancelAll()` | `Unit` | Removes all tasks for this application. |
| `isScheduled(taskId)` | `Boolean` | `true` if the task is currently registered with the OS. |
| `getTaskInfo(taskId)` | `TaskInfo?` | Runtime info about a task, or `null` if not found. |
| `getAllTasks()` | `List<TaskInfo>` | All tasks registered by this application. |

### `TaskRequest`

Created via factory methods:

| Factory | Parameters | Description |
|---------|-----------|-------------|
| `periodic(taskId, interval, configure)` | interval: `Duration` (min 15 min) | Repeats at a fixed interval. |
| `calendar(taskId, expression, configure)` | expression: `CronExpression` | Fires on a calendar schedule. |
| `onBoot(taskId, configure)` | — | Runs at user login. |

Builder DSL:

| Method | Description |
|--------|-------------|
| `inputData(key, value)` | Attach a key-value pair, retrievable via `TaskContext.inputData`. |
| `retryPolicy(policy)` | Set the retry strategy (`ExponentialBackoff` or `Linear`). |
| `existingTaskPolicy(policy)` | `KEEP` (default) or `REPLACE` if same task ID exists. |
| `runImmediately(enabled)` | Run the task immediately when scheduled (periodic tasks only). Default: `false`. |

### `CronExpression`

| Factory | Expression | Description |
|---------|-----------|-------------|
| `everyDayAt(hour, minute)` | `*-*-* HH:MM:00` | Every day at the given time. |
| `everyWeekdayAt(hour, minute)` | `Mon..Fri *-*-* HH:MM:00` | Monday through Friday. |
| `everyWeekdayAt(day, hour, minute)` | `Mon *-*-* HH:MM:00` | Specific day of the week. |
| `everyMondayAt(hour, minute)` | `Mon *-*-* HH:MM:00` | Shorthand for every Monday. |
| `everyHour()` | `*-*-* *:00:00` | Every hour at minute 0. |

### `DesktopTask`

| Method | Returns | Description |
|--------|---------|-------------|
| `doWork(context)` | `TaskResult` | Suspend function performing the background work. |

### `TaskContext`

| Property | Type | Description |
|----------|------|-------------|
| `taskId` | `String` | The unique task identifier. |
| `inputData` | `Map<String, String>` | Key-value pairs from enqueue time. |
| `runAttemptCount` | `Int` | 1-based attempt counter (increments on retry). |

### `TaskResult`

| Variant | Description |
|---------|-------------|
| `Success` | Task completed successfully. |
| `Failure(message?)` | Permanent failure — will not be retried. |
| `Retry(message?)` | Temporary failure — retried per `RetryPolicy`. |

### `TaskInfo`

| Property | Type | Description |
|----------|------|-------------|
| `taskId` | `String` | The unique task identifier. |
| `state` | `TaskState` | `SCHEDULED`, `RUNNING`, or `INACTIVE`. |
| `lastRunMs` | `Long?` | Epoch millis of the last execution. |
| `nextRunMs` | `Long?` | Epoch millis of the next execution (if known). |
| `runCount` | `Int` | Total number of completed executions. |
| `lastResult` | `String?` | Description of the last result. |

### `RetryPolicy`

| Variant | Parameters | Default |
|---------|-----------|---------|
| `ExponentialBackoff` | `initialDelay: Duration`, `maxAttempts: Int` | 30 min, 3 attempts |
| `Linear` | `delay: Duration`, `maxAttempts: Int` | 15 min, 3 attempts |

## How It Works

### Execution flow

When a task fires, the OS re-launches your application binary with arguments:

```
/path/to/MyApp --nucleus-scheduler-run <taskId>
```

`DesktopBootReceiver.isSchedulerInvocation()` detects these arguments and `handle()` resolves the task from the registry, loads its context from the metadata store, and calls `doWork()`.

### Metadata storage

Task input data and run history are persisted per-platform:

| Platform | Location |
|----------|----------|
| macOS | `~/Library/Application Support/nucleus/scheduler/<appId>/` |
| Linux | `~/.local/share/nucleus/scheduler/<appId>/` (or `$XDG_DATA_HOME`) |
| Windows | `%LOCALAPPDATA%\nucleus\scheduler\<appId>\` |

### Platform details

#### macOS (launchd)

Generates plist files in `~/Library/LaunchAgents/` with label `io.github.kdroidfilter.nucleus.<appId>.<taskId>`. Managed via a JNI bridge (`MacOSLaunchdSchedulerJni`) that uses Foundation and ServiceManagement APIs for plist writing, job status queries, and next-fire-time computation. Falls back to `launchctl` shell commands when the native library is unavailable.

#### Linux (systemd)

Creates systemd user service and timer units in `~/.config/systemd/user/` (respects `$XDG_CONFIG_HOME`). Managed via a JNI D-Bus bridge (`LinuxSystemdSchedulerJni`) that talks directly to `org.freedesktop.systemd1.Manager` through GLib/GIO — no subprocess invocation. Calendar tasks map directly to `OnCalendar=` expressions. Unit names follow the pattern `nucleus-<appId>-<taskId>.service` / `.timer`.

#### Windows (Task Scheduler)

Registers tasks under `\Nucleus\<appId>\` via a JNI bridge (`WindowsTaskSchedulerJni`) that calls the Task Scheduler 2.0 COM API (`ITaskService`, `ITaskFolder`, `ITaskDefinition`) — no `schtasks.exe` subprocess. Supports periodic, daily, weekly, logon, and one-shot triggers natively.

## Testing

The `scheduler-testing` module provides two levels of test support, inspired by Android's `work-testing`.

### Installation

```kotlin
dependencies {
    testImplementation("io.github.kdroidfilter:nucleus.scheduler-testing:<version>")
}
```

### Level 1 — Unit-test a task in isolation

`TestTaskRunner` executes a `DesktopTask.doWork()` with a fabricated `TaskContext`, no scheduler involved:

```kotlin
val result = TestTaskRunner.runTask(
    task = SyncTask(),
    taskId = "sync",
    inputData = mapOf("endpoint" to "https://test.api"),
    runAttemptCount = 1,
)
assertEquals(TaskResult.Success, result)
```

### Level 2 — In-memory scheduler for integration tests

`TestDesktopTaskScheduler` replaces the real platform backend so you can enqueue, query, and execute tasks entirely in memory. It supports **virtual time** and **execution history**.

```kotlin
val registry = TaskRegistry.Builder()
    .register("sync") { SyncTask() }
    .build()

TestDesktopTaskScheduler().use { testScheduler ->
    testScheduler.install()

    // Enqueue through the real API — routed to in-memory backend
    DesktopTaskScheduler.enqueue(TaskRequest.periodic("sync", 2.hours))
    assertTrue(DesktopTaskScheduler.isScheduled("sync"))

    // Advance virtual time — automatically triggers periodic tasks
    val results = testScheduler.advanceTimeBy(6.hours, registry)
    assertEquals(3, results.size) // fired at 2h, 4h, 6h

    // Inspect execution history
    val history = testScheduler.getExecutionHistory("sync")
    assertEquals(3, history.size)
    assertEquals(TaskResult.Success, history.last().result)
} // .close() restores the platform-default backend
```

#### Retry tracking

When `doWork()` returns `TaskResult.Retry`, the `runAttemptCount` is automatically incremented for the next execution. On `Success` or `Failure`, it resets to 1:

```kotlin
TestDesktopTaskScheduler().use { testScheduler ->
    testScheduler.install()
    DesktopTaskScheduler.enqueue(TaskRequest.periodic("flaky", 1.hours))

    // advanceTimeBy triggers the task each hour
    testScheduler.advanceTimeBy(3.hours, registry)

    val history = testScheduler.getExecutionHistory("flaky")
    assertEquals(1, history[0].runAttemptCount) // attempt 1 → Retry
    assertEquals(2, history[1].runAttemptCount) // attempt 2 → Retry
    assertEquals(3, history[2].runAttemptCount) // attempt 3 → Success
}
```

### `TestTaskRunner`

| Method | Returns | Description |
|--------|---------|-------------|
| `runTask(task, taskId?, inputData?, runAttemptCount?)` | `TaskResult` | Calls `doWork()` with a controlled `TaskContext`. |

### `TestDesktopTaskScheduler`

| Method | Returns | Description |
|--------|---------|-------------|
| `install()` | `Unit` | Swaps the `DesktopTaskScheduler` backend with this in-memory implementation. |
| `uninstall()` | `Unit` | Restores the platform-default backend. Also called by `close()`. |
| `runTask(taskId, registry)` | `TaskResult` | Executes the task immediately, updates run count and attempt tracking. |
| `advanceTimeBy(duration, registry)` | `List<ExecutionRecord>` | Advances virtual time and triggers all periodic tasks whose interval has elapsed. |
| `getExecutionHistory(taskId)` | `List<ExecutionRecord>` | Full execution history for a task (result, attempt count, virtual time). |
| `getAllExecutionHistory()` | `List<ExecutionRecord>` | Execution history across all tasks, sorted chronologically. |
| `getEnqueuedRequest(taskId)` | `TaskRequest?` | Returns the enqueued request for assertions. |
| `getEnqueuedRequests()` | `List<TaskRequest>` | Returns all enqueued requests. |
| `currentVirtualTimeMs` | `Long` | The current virtual time in milliseconds. |

All standard `DesktopTaskScheduler` methods (`enqueue`, `cancel`, `isScheduled`, `getTaskInfo`, `getAllTasks`) work as expected after `install()`.
