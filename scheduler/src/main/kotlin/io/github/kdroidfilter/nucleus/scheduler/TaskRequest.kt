package io.github.kdroidfilter.nucleus.scheduler

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Describes a task to be scheduled with the OS.
 *
 * Create instances via the companion factory methods: [periodic], [calendar], [onBoot].
 */
@OptIn(InternalSchedulerApi::class)
public class TaskRequest private constructor(
    /** Unique identifier — must match a [TaskRegistry] entry. */
    public val taskId: String,
    @property:InternalSchedulerApi public val type: Type,
    @property:InternalSchedulerApi public val interval: Duration?,
    @property:InternalSchedulerApi public val cronExpression: CronExpression?,
    /** Key-value pairs attached at enqueue time, retrievable via [TaskContext.inputData]. */
    public val inputData: Map<String, String>,
    internal val retryPolicy: RetryPolicy?,
    /** Policy for handling a task with the same ID already scheduled. */
    public val existingTaskPolicy: ExistingTaskPolicy,
    internal val runImmediately: Boolean,
) {
    @InternalSchedulerApi
    public enum class Type { PERIODIC, CALENDAR, ON_BOOT }

    /**
     * DSL builder for configuring a [TaskRequest].
     */
    public class Builder internal constructor() {
        internal val data = mutableMapOf<String, String>()
        internal var retryPolicy: RetryPolicy? = null
        internal var existingTaskPolicy: ExistingTaskPolicy = ExistingTaskPolicy.KEEP
        internal var runImmediately: Boolean = false

        /** Attach a key-value pair to the task, retrievable via [TaskContext.inputData]. */
        public fun inputData(
            key: String,
            value: String,
        ) {
            data[key] = value
        }

        /** Set the retry policy for this task. */
        public fun retryPolicy(policy: RetryPolicy) {
            retryPolicy = policy
        }

        /** Control behavior when a task with the same ID is already scheduled. */
        public fun existingTaskPolicy(policy: ExistingTaskPolicy) {
            existingTaskPolicy = policy
        }

        /**
         * Run the task immediately when scheduled, in addition to the periodic interval.
         * By default, the first execution waits for the full interval to elapse.
         */
        public fun runImmediately(value: Boolean = true) {
            runImmediately = value
        }
    }

    public companion object {
        private val MIN_INTERVAL = 15.minutes
        private val TASK_ID_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

        private fun validateTaskId(taskId: String) {
            require(taskId.isNotEmpty()) { "taskId must not be empty" }
            require(TASK_ID_PATTERN.matches(taskId)) {
                "taskId must match [a-zA-Z0-9_-]+, got '$taskId'"
            }
        }

        /**
         * A task that repeats at a fixed interval.
         *
         * @param taskId unique identifier matching a [TaskRegistry] entry
         * @param interval minimum 15 minutes
         * @param configure optional DSL block for inputData, retryPolicy, etc.
         */
        public fun periodic(
            taskId: String,
            interval: Duration,
            configure: Builder.() -> Unit = {},
        ): TaskRequest {
            validateTaskId(taskId)
            require(interval >= MIN_INTERVAL) {
                "Interval must be at least $MIN_INTERVAL, got $interval"
            }
            val builder = Builder().apply(configure)
            return TaskRequest(
                taskId = taskId,
                type = Type.PERIODIC,
                interval = interval,
                cronExpression = null,
                inputData = builder.data.toMap(),
                retryPolicy = builder.retryPolicy,
                existingTaskPolicy = builder.existingTaskPolicy,
                runImmediately = builder.runImmediately,
            )
        }

        /**
         * A task triggered on a calendar schedule (cron-like).
         *
         * @param taskId unique identifier matching a [TaskRegistry] entry
         * @param expression the schedule (use [CronExpression] factory methods)
         * @param configure optional DSL block
         */
        public fun calendar(
            taskId: String,
            expression: CronExpression,
            configure: Builder.() -> Unit = {},
        ): TaskRequest {
            validateTaskId(taskId)
            val builder = Builder().apply(configure)
            return TaskRequest(
                taskId = taskId,
                type = Type.CALENDAR,
                interval = null,
                cronExpression = expression,
                inputData = builder.data.toMap(),
                retryPolicy = builder.retryPolicy,
                existingTaskPolicy = builder.existingTaskPolicy,
                runImmediately = false,
            )
        }

        /**
         * A task that runs at system/user login.
         *
         * @param taskId unique identifier matching a [TaskRegistry] entry
         * @param configure optional DSL block
         */
        public fun onBoot(
            taskId: String,
            configure: Builder.() -> Unit = {},
        ): TaskRequest {
            validateTaskId(taskId)
            val builder = Builder().apply(configure)
            return TaskRequest(
                taskId = taskId,
                type = Type.ON_BOOT,
                interval = null,
                cronExpression = null,
                inputData = builder.data.toMap(),
                retryPolicy = builder.retryPolicy,
                existingTaskPolicy = builder.existingTaskPolicy,
                runImmediately = false,
            )
        }
    }
}
