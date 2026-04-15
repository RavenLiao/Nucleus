package io.github.kdroidfilter.nucleus.scheduler.internal

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.scheduler.TaskContext
import java.io.File
import java.util.Properties

/**
 * Persists per-task metadata (inputData, run count, last result, timestamps)
 * as `.properties` files in `~/.local/share/nucleus/scheduler/<appId>/`.
 */
@Suppress("TooManyFunctions")
internal object TaskMetadataStore {
    private const val KEY_RUN_COUNT = "_runCount"
    private const val KEY_RUN_ATTEMPT = "_runAttemptCount"
    private const val KEY_LAST_RUN_MS = "_lastRunMs"
    private const val KEY_LAST_RESULT = "_lastResult"

    fun storeDir(appId: String): File {
        val baseDir = when (Platform.Current) {
            Platform.Windows ->
                System.getenv("LOCALAPPDATA")
                    ?: "${System.getProperty("user.home")}\\AppData\\Local"
            Platform.MacOS ->
                "${System.getProperty("user.home")}/Library/Application Support"
            else ->
                System.getenv("XDG_DATA_HOME")
                    ?: "${System.getProperty("user.home")}/.local/share"
        }
        return File(baseDir, "nucleus/scheduler/$appId")
    }

    private fun taskFile(
        appId: String,
        taskId: String,
    ): File = File(storeDir(appId), "$taskId.properties")

    fun save(
        appId: String,
        taskId: String,
        inputData: Map<String, String>,
    ) {
        val file = taskFile(appId, taskId)
        file.parentFile.mkdirs()
        val existing = load(file)
        inputData.forEach { (k, v) -> existing.setProperty(k, v) }
        file.outputStream().use { existing.store(it, null) }
    }

    fun loadContext(
        appId: String,
        taskId: String,
    ): TaskContext {
        val props = load(taskFile(appId, taskId))
        val inputData =
            props
                .stringPropertyNames()
                .filter { !it.startsWith("_") }
                .associateWith { props.getProperty(it) }
        val runAttempt = props.getProperty(KEY_RUN_ATTEMPT)?.toIntOrNull() ?: 1
        return TaskContext(
            taskId = taskId,
            inputData = inputData,
            runAttemptCount = runAttempt,
        )
    }

    fun recordSuccess(
        appId: String,
        taskId: String,
    ) {
        val file = taskFile(appId, taskId)
        val props = load(file)
        val runCount = (props.getProperty(KEY_RUN_COUNT)?.toIntOrNull() ?: 0) + 1
        props.setProperty(KEY_RUN_COUNT, runCount.toString())
        props.setProperty(KEY_RUN_ATTEMPT, "1")
        props.setProperty(KEY_LAST_RUN_MS, System.currentTimeMillis().toString())
        props.setProperty(KEY_LAST_RESULT, "Success")
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }

    fun recordFailure(
        appId: String,
        taskId: String,
        message: String?,
    ) {
        val file = taskFile(appId, taskId)
        val props = load(file)
        props.setProperty(KEY_LAST_RUN_MS, System.currentTimeMillis().toString())
        props.setProperty(KEY_LAST_RESULT, "Failure: ${message ?: "unknown"}")
        props.setProperty(KEY_RUN_ATTEMPT, "1")
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }

    fun recordRetry(
        appId: String,
        taskId: String,
        message: String?,
    ) {
        val file = taskFile(appId, taskId)
        val props = load(file)
        val attempt = (props.getProperty(KEY_RUN_ATTEMPT)?.toIntOrNull() ?: 1) + 1
        props.setProperty(KEY_RUN_ATTEMPT, attempt.toString())
        props.setProperty(KEY_LAST_RUN_MS, System.currentTimeMillis().toString())
        props.setProperty(KEY_LAST_RESULT, "Retry: ${message ?: "unknown"}")
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }

    fun getRunCount(
        appId: String,
        taskId: String,
    ): Int {
        val props = load(taskFile(appId, taskId))
        return props.getProperty(KEY_RUN_COUNT)?.toIntOrNull() ?: 0
    }

    fun getLastRunMs(
        appId: String,
        taskId: String,
    ): Long? {
        val props = load(taskFile(appId, taskId))
        return props.getProperty(KEY_LAST_RUN_MS)?.toLongOrNull()
    }

    fun getLastResult(
        appId: String,
        taskId: String,
    ): String? {
        val props = load(taskFile(appId, taskId))
        return props.getProperty(KEY_LAST_RESULT)
    }

    fun getRunAttemptCount(
        appId: String,
        taskId: String,
    ): Int {
        val props = load(taskFile(appId, taskId))
        return props.getProperty(KEY_RUN_ATTEMPT)?.toIntOrNull() ?: 1
    }

    fun delete(
        appId: String,
        taskId: String,
    ) {
        taskFile(appId, taskId).delete()
    }

    fun deleteAll(appId: String) {
        val dir = storeDir(appId)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Lists all task IDs that have metadata stored. */
    fun listTaskIds(appId: String): List<String> {
        val dir = storeDir(appId)
        if (!dir.isDirectory) return emptyList()
        return dir
            .listFiles()
            ?.filter { it.extension == "properties" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun load(file: File): Properties {
        val props = Properties()
        if (file.isFile) {
            try {
                file.inputStream().use { props.load(it) }
            } catch (_: Exception) {
                // Corrupted file — start fresh
            }
        }
        return props
    }
}
