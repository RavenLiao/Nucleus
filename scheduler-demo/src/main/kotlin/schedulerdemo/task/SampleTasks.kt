package schedulerdemo.task

import io.github.kdroidfilter.nucleus.notification.common.notification
import io.github.kdroidfilter.nucleus.scheduler.DesktopTask
import io.github.kdroidfilter.nucleus.scheduler.TaskContext
import io.github.kdroidfilter.nucleus.scheduler.TaskResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

class SyncTask : DesktopTask {
    private val logger = Logger.getLogger(SyncTask::class.java.name)

    override suspend fun doWork(context: TaskContext): TaskResult {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        logger.info("SyncTask executed at $timestamp (attempt ${context.runAttemptCount})")
        return TaskResult.Success
    }
}

class BackupTask : DesktopTask {
    private val logger = Logger.getLogger(BackupTask::class.java.name)

    override suspend fun doWork(context: TaskContext): TaskResult {
        val target = context.inputData["target"] ?: "default"
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        logger.info("BackupTask to '$target' executed at $timestamp")
        return TaskResult.Success
    }
}

class NotificationTask : DesktopTask {
    private val logger = Logger.getLogger(NotificationTask::class.java.name)

    override suspend fun doWork(context: TaskContext): TaskResult {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val title = context.inputData["title"] ?: "Scheduler Notification"
        val message = context.inputData["message"] ?: "This is a scheduled notification"

        logger.info("NotificationTask: '$title' at $timestamp")

        return try {
            notification(title = title, message = message).send()
            TaskResult.Success
        } catch (e: Exception) {
            logger.warning("Failed to show notification: ${e.message}")
            TaskResult.Failure("Notification failed: ${e.message}")
        }
    }
}
