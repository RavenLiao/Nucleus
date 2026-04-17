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

`kotlinx-serialization-json` is exposed transitively (via `api`) so you can annotate your input payloads with `@Serializable` without adding it manually. You still need the Kotlin serialization plugin in your module:

```kotlin
plugins {
    kotlin("plugin.serialization") version "<kotlin version>"
}
```

## Quick Start

### 1. Declare task IDs and a registry

Task identifiers are wrapped in the `TaskId` value class — declare them once as top-level constants and reuse them everywhere (enqueue site, registry, queries):

```kotlin
val SyncId = TaskId("sync")
val BackupId = TaskId("backup")
```

`TaskId` validates the underlying string against `[a-zA-Z0-9_-]+` in its `init` block; invalid IDs fail fast at construction.

### 2. Define a task

Implement the `DesktopTask` interface:

```kotlin
class SyncTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult {
        // Your background work here
        return TaskResult.Success
    }
}
```

### 3. Build a task registry

Map task IDs to their factories:

```kotlin
val registry = TaskRegistry.Builder()
    .register(SyncId) { SyncTask() }
    .register(BackupId) { BackupTask() }
    .build()
```

### 4. Handle scheduler invocations in `main()`

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

### 5. Schedule tasks

```kotlin
val scheduler = DesktopTaskScheduler

// Periodic task — every hour
scheduler.enqueue(TaskRequest.periodic(SyncId, 1.hours))

// Calendar task — every day at 9:00
scheduler.enqueue(TaskRequest.calendar(ReportId, CronExpression.everyDayAt(LocalTime.of(9, 0))))

// Run at login
scheduler.enqueue(TaskRequest.onBoot(StartupCheckId))
```

## Usage

### Periodic tasks

Repeat at a fixed interval (minimum 15 minutes):

```kotlin
@Serializable
data class BackupInput(val target: String)

scheduler.enqueue(
    TaskRequest.periodic(BackupId, 30.minutes) {
        inputData(BackupInput(target = "/tmp/backup"))
        retryPolicy(RetryPolicy.ExponentialBackoff())
        existingTaskPolicy(ExistingTaskPolicy.REPLACE)
    }
)
```

### Calendar tasks

Fire on a calendar schedule using `CronExpression`. Times are expressed with `java.time.LocalTime` to keep validation free and avoid raw `Int` arguments:

```kotlin
import java.time.DayOfWeek
import java.time.LocalTime

// Every day at 9:00
scheduler.enqueue(TaskRequest.calendar(ReportId, CronExpression.everyDayAt(LocalTime.of(9, 0))))

// Weekdays at 18:00
scheduler.enqueue(TaskRequest.calendar(CleanupId, CronExpression.everyWeekdayAt(LocalTime.of(18, 0))))

// Every Monday at 8:30
scheduler.enqueue(
    TaskRequest.calendar(WeeklyId, CronExpression.everyWeekdayAt(DayOfWeek.MONDAY, LocalTime.of(8, 30)))
)

// Every hour
scheduler.enqueue(TaskRequest.calendar(HeartbeatId, CronExpression.everyHour()))
```

### On-boot tasks

Run once at user login:

```kotlin
scheduler.enqueue(TaskRequest.onBoot(LoginSyncId))
```

### Input data

Attach a `@Serializable` payload at enqueue time and decode it inside `doWork()`. The scheduler stores the payload as JSON in the per-task metadata file, so a fresh process spawned by the OS can re-read it.

```kotlin
@Serializable
data class SyncInput(
    val endpoint: String,
    val token: String,
    val retries: Int = 3,
    val verbose: Boolean = false,
)

// Enqueue
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 1.hours) {
        inputData(
            SyncInput(
                endpoint = "https://api.example.com",
                token = "abc123",
            )
        )
    }
)

// In the task
class SyncTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult {
        val input = context.inputData<SyncInput>()
            ?: return TaskResult.Failure("missing input")
        // input.endpoint, input.token, input.retries, input.verbose
        return TaskResult.Success
    }
}
```

If you cannot rely on the reified inline overload (e.g. you only have a `KSerializer<T>` at hand), pass it explicitly:

```kotlin
TaskRequest.periodic(SyncId, 1.hours) {
    inputData(SyncInput(...), SyncInput.serializer())
}

// Reading:
val input = context.inputData(SyncInput.serializer())
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
    TaskRequest.periodic(SyncId, 1.hours) {
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
scheduler.isScheduled(SyncId)

// Get task info
val info: TaskInfo? = scheduler.getTaskInfo(SyncId)
info?.let {
    println("State: ${it.state}")       // SCHEDULED, RUNNING, or INACTIVE
    println("Run count: ${it.runCount}")
    println("Last result: ${it.lastResult}")
}

// List all tasks
val all: List<TaskInfo> = scheduler.getAllTasks()

// Cancel a task
scheduler.cancel(SyncId)

// Cancel all tasks
scheduler.cancelAll()
```

### Existing task policy

Control what happens when you enqueue a task ID that's already scheduled:

```kotlin
// Default: keep the existing schedule, just update input data
scheduler.enqueue(TaskRequest.periodic(SyncId, 1.hours))

// Replace: unload the existing task and re-register with new settings
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 2.hours) {
        existingTaskPolicy(ExistingTaskPolicy.REPLACE)
    }
)
```

### Constraints

Constraints let you declare conditions that must be met before a task executes — similar to Android's WorkManager constraints. Constraints are checked **at execution time**: the OS still triggers the process on schedule, but `doWork()` is only called when all constraints are satisfied.

The [`system-info`](system-info.md) module is included as a transitive dependency — no extra configuration needed.

#### Basic usage

```kotlin
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 1.hours) {
        constraints {
            requiredNetworkType = NetworkType.CONNECTED
            requiresBatteryNotLow = true
        }
    }
)
```

#### Available constraints

| Constraint | Type | Default | Description |
|------------|------|---------|-------------|
| `requiredNetworkType` | `NetworkType` | `NOT_REQUIRED` | Network connectivity requirement. |
| `requiresBatteryNotLow` | `Boolean` | `false` | Battery must be above 15 %. Devices without a battery satisfy this. |
| `requiresCharging` | `Boolean` | `false` | Device must be plugged in (charging or full). |
| `requiresDeviceIdle` | `Boolean` | `false` | User must be idle for at least 5 minutes. |
| `minimumStorageBytes` | `Long?` | `null` | Minimum available disk space (in bytes) on the app partition, or `null` for no requirement. |

#### Network types

| Value | Description |
|-------|-------------|
| `NOT_REQUIRED` | No network requirement (default). |
| `CONNECTED` | Any active network connection. |
| `UNMETERED` | Unmetered (non-cellular / non-tethered) connection only. |

#### Behavior when constraints are not met

| Task type | Behavior |
|-----------|----------|
| **Periodic** | Silently skipped — the next trigger re-checks constraints. |
| **Calendar / On-boot** | A retry is scheduled with backoff (5 minutes). |

In both cases, the metadata store records the skip with a `ConstraintsNotMet` result for observability.

#### Examples

```kotlin
// Only sync when connected to Wi-Fi and charging
scheduler.enqueue(
    TaskRequest.periodic(CloudSyncId, 2.hours) {
        constraints {
            requiredNetworkType = NetworkType.UNMETERED
            requiresCharging = true
        }
    }
)

// Heavy backup only when idle with at least 1 GB free
scheduler.enqueue(
    TaskRequest.calendar(NightlyBackupId, CronExpression.everyDayAt(LocalTime.of(3, 0))) {
        constraints {
            requiresDeviceIdle = true
            minimumStorageBytes = 1_073_741_824 // 1 GB
            requiresBatteryNotLow = true
        }
    }
)

// Pre-built Constraints object
val syncConstraints = Constraints(
    requiredNetworkType = NetworkType.CONNECTED,
    requiresBatteryNotLow = true,
)
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 1.hours) {
        constraints(syncConstraints)
    }
)
```

## API Reference

### `TaskId`

`@JvmInline value class TaskId(val value: String)` — a strongly-typed identifier validated against `[a-zA-Z0-9_-]+` in its `init` block. Used as a filename / systemd unit / launchd label / Windows Task Scheduler task name, so the character set is intentionally narrow.

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

Created via factory methods (all take a `TaskId`):

| Factory | Parameters | Description |
|---------|-----------|-------------|
| `periodic(taskId, interval, configure)` | interval: `Duration` (min 15 min) | Repeats at a fixed interval. |
| `calendar(taskId, expression, configure)` | expression: `CronExpression` | Fires on a calendar schedule. |
| `onBoot(taskId, configure)` | — | Runs at user login. |

Builder DSL:

| Method | Description |
|--------|-------------|
| `inputData(value)` | Attach a `@Serializable` payload (reified inline — type resolved at the call site). |
| `inputData(value, serializer)` | Attach a `@Serializable` payload with an explicit `KSerializer<T>`. |
| `retryPolicy(policy)` | Set the retry strategy (`ExponentialBackoff` or `Linear`). |
| `existingTaskPolicy(policy)` | `KEEP` (default) or `REPLACE` if same task ID exists. |
| `runImmediately(enabled)` | Run the task immediately when scheduled (periodic tasks only). Default: `false`. |
| `constraints { ... }` | Set execution constraints via DSL (see [Constraints](#constraints)). |
| `constraints(constraints)` | Set a pre-built `Constraints` object. |

### `CronExpression`

All factory methods take a `java.time.LocalTime` for the time-of-day component.

| Factory | Expression | Description |
|---------|-----------|-------------|
| `everyDayAt(time)` | `*-*-* HH:MM:00` | Every day at the given time. |
| `everyWeekdayAt(time)` | `Mon..Fri *-*-* HH:MM:00` | Monday through Friday. |
| `everyWeekdayAt(day, time)` | `Mon *-*-* HH:MM:00` | Specific day of the week. |
| `everyMondayAt(time)` | `Mon *-*-* HH:MM:00` | Shorthand for every Monday. |
| `everyHour()` | `*-*-* *:00:00` | Every hour at minute 0. |

### `DesktopTask`

| Method | Returns | Description |
|--------|---------|-------------|
| `doWork(context)` | `TaskResult` | Suspend function performing the background work. |

### `TaskContext`

| Property / Method | Type | Description |
|-------------------|------|-------------|
| `taskId` | `TaskId` | The unique task identifier. |
| `inputData` | `TaskData` | Opaque container holding the serialized payload. |
| `runAttemptCount` | `Int` | 1-based attempt counter (increments on retry). |
| `inputData<T>()` (extension) | `T?` | Decodes the payload using the contextually-resolved serializer. Returns `null` when no payload was attached. |
| `inputData(serializer)` (extension) | `T?` | Decodes the payload using an explicit `KSerializer<T>`. |

### `TaskData`

Opaque container for a `@Serializable` payload — wraps a JSON `String?`.

| Method | Returns | Description |
|--------|---------|-------------|
| `isEmpty()` / `isNotEmpty()` | `Boolean` | Whether a payload was attached. |
| `decode<T>()` (extension) | `T?` | Decodes the payload using the contextually-resolved serializer. |
| `decode(serializer)` | `T?` | Decodes with an explicit `KSerializer<T>`. |
| `TaskData.EMPTY` (companion) | `TaskData` | A `TaskData` with no payload. |
| `TaskData.of(value)` (companion, reified) | `TaskData` | Encodes `value` using the contextually-resolved serializer. |
| `TaskData.of(value, serializer)` (companion) | `TaskData` | Encodes `value` with an explicit `KSerializer<T>`. |

### `TaskResult`

| Variant | Description |
|---------|-------------|
| `Success` | Task completed successfully. |
| `Failure(message?)` | Permanent failure — will not be retried. |
| `Retry(message?)` | Temporary failure — retried per `RetryPolicy`. |

### `TaskInfo`

| Property | Type | Description |
|----------|------|-------------|
| `taskId` | `TaskId` | The unique task identifier. |
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

### `Constraints`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `requiredNetworkType` | `NetworkType` | `NOT_REQUIRED` | Required network connectivity. |
| `requiresBatteryNotLow` | `Boolean` | `false` | Battery above 15 % (no battery = satisfied). |
| `requiresCharging` | `Boolean` | `false` | Device must be plugged in. |
| `requiresDeviceIdle` | `Boolean` | `false` | User idle ≥ 5 minutes. |
| `requiresStorageNotLow` | `Boolean` | `false` | Disk space ≥ 256 MB on app partition. |

| Constant / Method | Description |
|--------------------|-------------|
| `Constraints.NONE` | No constraints — the task always executes. |
| `hasConstraints()` | Returns `true` if at least one constraint is set. |

### `NetworkType`

| Value | Description |
|-------|-------------|
| `NOT_REQUIRED` | No network requirement. |
| `CONNECTED` | Any active network connection. |
| `UNMETERED` | Unmetered connection only. |

## How It Works

### Execution flow

When a task fires, the OS re-launches your application binary with arguments:

```
/path/to/MyApp --nucleus-scheduler-run <taskId>
```

`DesktopBootReceiver.isSchedulerInvocation()` detects these arguments and `handle()` resolves the task from the registry, loads its context from the metadata store, checks any [constraints](#constraints) against the current system state, and — if all constraints are satisfied — calls `doWork()`. If the raw string from the command line does not match the `TaskId` regex, the invocation is rejected before any task lookup.

### Metadata storage

Task input data and run history are persisted per-platform:

| Platform | Location |
|----------|----------|
| macOS | `~/Library/Application Support/nucleus/scheduler/<appId>/` |
| Linux | `~/.local/share/nucleus/scheduler/<appId>/` (or `$XDG_DATA_HOME`) |
| Windows | `%LOCALAPPDATA%\nucleus\scheduler\<appId>\` |

Each task gets a single `<taskId>.properties` file. The serialized input payload is stored as a JSON string under the reserved `_inputDataJson` key alongside run-count, last-result and other internal bookkeeping keys (all prefixed with `_`).

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
@Serializable
data class SyncInput(val endpoint: String)

val result = TestTaskRunner.runTask(
    task = SyncTask(),
    taskId = TaskId("sync"),
    inputData = TaskData.of(SyncInput(endpoint = "https://test.api")),
    runAttemptCount = 1,
)
assertEquals(TaskResult.Success, result)
```

### Level 2 — In-memory scheduler for integration tests

`TestDesktopTaskScheduler` replaces the real platform backend so you can enqueue, query, and execute tasks entirely in memory. It supports **virtual time** and **execution history**.

```kotlin
val SyncId = TaskId("sync")

val registry = TaskRegistry.Builder()
    .register(SyncId) { SyncTask() }
    .build()

TestDesktopTaskScheduler().use { testScheduler ->
    testScheduler.install()

    // Enqueue through the real API — routed to in-memory backend
    DesktopTaskScheduler.enqueue(TaskRequest.periodic(SyncId, 2.hours))
    assertTrue(DesktopTaskScheduler.isScheduled(SyncId))

    // Advance virtual time — automatically triggers periodic tasks
    val results = testScheduler.advanceTimeBy(6.hours, registry)
    assertEquals(3, results.size) // fired at 2h, 4h, 6h

    // Inspect execution history
    val history = testScheduler.getExecutionHistory(SyncId)
    assertEquals(3, history.size)
    assertEquals(TaskResult.Success, history.last().result)
} // .close() restores the platform-default backend
```

#### Testing calendar and on-boot tasks

`advanceTimeBy` only triggers **periodic** tasks — calendar and on-boot tasks depend on absolute time or OS events, not intervals. Use `runTask()` directly for those:

```kotlin
TestDesktopTaskScheduler().use { testScheduler ->
    testScheduler.install()

    // Calendar task
    DesktopTaskScheduler.enqueue(
        TaskRequest.calendar(ReportId, CronExpression.everyDayAt(LocalTime.of(9, 0)))
    )
    val result = testScheduler.runTask(ReportId, registry)
    assertEquals(TaskResult.Success, result)

    // On-boot task
    DesktopTaskScheduler.enqueue(TaskRequest.onBoot(StartupCheckId))
    val bootResult = testScheduler.runTask(StartupCheckId, registry)
    assertEquals(TaskResult.Success, bootResult)
}
```

#### Retry tracking

When `doWork()` returns `TaskResult.Retry`, the `runAttemptCount` is automatically incremented for the next execution. On `Success` or `Failure`, it resets to 1:

```kotlin
TestDesktopTaskScheduler().use { testScheduler ->
    testScheduler.install()
    DesktopTaskScheduler.enqueue(TaskRequest.periodic(FlakyId, 1.hours))

    // advanceTimeBy triggers the task each hour
    testScheduler.advanceTimeBy(3.hours, registry)

    val history = testScheduler.getExecutionHistory(FlakyId)
    assertEquals(1, history[0].runAttemptCount) // attempt 1 → Retry
    assertEquals(2, history[1].runAttemptCount) // attempt 2 → Retry
    assertEquals(3, history[2].runAttemptCount) // attempt 3 → Success
}
```

#### Testing constraints

Use `TestConstraintChecker` to simulate system state and verify that tasks respect their constraints:

```kotlin
val constraintChecker = TestConstraintChecker()
constraintChecker.install()

TestDesktopTaskScheduler().use { testScheduler ->
    testScheduler.install()
    testScheduler.constraintChecker = constraintChecker

    DesktopTaskScheduler.enqueue(
        TaskRequest.periodic(SyncId, 1.hours) {
            constraints {
                requiredNetworkType = NetworkType.CONNECTED
            }
        }
    )

    // Network is down — task should be skipped
    constraintChecker.networkConnected = false
    val results = testScheduler.advanceTimeBy(2.hours, registry)
    assertEquals(0, results.size)

    // Network is back — task executes
    constraintChecker.networkConnected = true
    val results2 = testScheduler.advanceTimeBy(1.hours, registry)
    assertEquals(1, results2.size)
}

constraintChecker.uninstall()
```

`TestConstraintChecker` exposes mutable properties matching each constraint:

| Property | Type | Default | Maps to |
|----------|------|---------|---------|
| `networkConnected` | `Boolean` | `true` | `NetworkType.CONNECTED` |
| `networkUnmetered` | `Boolean` | `true` | `NetworkType.UNMETERED` |
| `batteryLevel` | `Float?` | `1.0f` | `requiresBatteryNotLow` (threshold: 15 %) |
| `isCharging` | `Boolean` | `false` | `requiresCharging` |
| `idleTimeSeconds` | `Long` | `0` | `requiresDeviceIdle` (threshold: 300 s) |
| `availableStorageBytes` | `Long` | `MAX_VALUE` | `minimumStorageBytes` |

### `TestTaskRunner`

| Method | Returns | Description |
|--------|---------|-------------|
| `runTask(task, taskId?, inputData?, runAttemptCount?)` | `TaskResult` | Calls `doWork()` with a controlled `TaskContext`. `taskId` defaults to `TaskId("test-task")`; `inputData` defaults to `TaskData.EMPTY`. |

### `TestDesktopTaskScheduler`

| Method / Property | Returns | Description |
|-------------------|---------|-------------|
| `install()` | `Unit` | Swaps the `DesktopTaskScheduler` backend with this in-memory implementation. |
| `uninstall()` | `Unit` | Restores the platform-default backend. Also called by `close()`. |
| `constraintChecker` | `ConstraintChecker` | Constraint checker used before execution. Defaults to all-satisfied. Set a `TestConstraintChecker` to simulate failures. |
| `runTask(taskId, registry)` | `TaskResult?` | Executes the task immediately. Returns `null` if a periodic task is skipped due to unsatisfied constraints. |
| `advanceTimeBy(duration, registry)` | `List<ExecutionRecord>` | Advances virtual time and triggers all periodic tasks whose interval has elapsed. Tasks with unsatisfied constraints are skipped. |
| `getExecutionHistory(taskId)` | `List<ExecutionRecord>` | Full execution history for a task. |
| `getAllExecutionHistory()` | `List<ExecutionRecord>` | Execution history across all tasks, sorted chronologically. |
| `getEnqueuedRequest(taskId)` | `TaskRequest?` | Returns the enqueued request for assertions. |
| `getEnqueuedRequests()` | `List<TaskRequest>` | Returns all enqueued requests. |
| `currentVirtualTimeMs` | `Long` | The current virtual time in milliseconds. |

### `ExecutionRecord`

Returned by `advanceTimeBy()`, `getExecutionHistory()`, and `getAllExecutionHistory()`.

| Property | Type | Description |
|----------|------|-------------|
| `taskId` | `TaskId` | The task that was executed. |
| `result` | `TaskResult` | The outcome of `doWork()` (`Success`, `Failure`, or `Retry`). |
| `runAttemptCount` | `Int` | The 1-based attempt number at the time of execution. |
| `virtualTimeMs` | `Long` | The virtual time (in milliseconds) at which the execution occurred. |

### `TestConstraintChecker`

| Method / Property | Type | Description |
|-------------------|------|-------------|
| `networkConnected` | `Boolean` | Simulated network connectivity (default: `true`). |
| `networkUnmetered` | `Boolean` | Simulated unmetered status (default: `true`). |
| `batteryLevel` | `Float?` | Simulated battery level 0.0–1.0, `null` = no battery (default: `1.0f`). |
| `isCharging` | `Boolean` | Simulated charging state (default: `false`). |
| `idleTimeSeconds` | `Long` | Simulated idle time in seconds (default: `0`). |
| `availableStorageBytes` | `Long` | Simulated disk space in bytes (default: `MAX_VALUE`). |
| `install()` | `Unit` | Sets this checker as the active constraint checker in `DesktopBootReceiver`. |
| `uninstall()` | `Unit` | Restores the production constraint checker. Also called by `close()`. |

All standard `DesktopTaskScheduler` methods (`enqueue`, `cancel`, `isScheduled`, `getTaskInfo`, `getAllTasks`) work as expected after `install()`.
