package app.synapse.localllm.data.storage

import android.content.Context
import android.os.StatFs
import app.synapse.localllm.domain.storage.StorageHealthGovernor
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.storage.StorageHealthState
import app.synapse.localllm.domain.storage.StorageThresholds
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File

class AndroidStorageHealthGovernor(
    context: Context,
    private val clock: SynapseClock,
) : StorageHealthGovernor {
    private val applicationContext = context.applicationContext

    override suspend fun inspectStorageHealth(thresholds: StorageThresholds): StorageHealthSnapshot =
        buildStorageHealthSnapshot(thresholds)

    override suspend fun canWriteMemory(thresholds: StorageThresholds): StorageHealthSnapshot =
        buildStorageHealthSnapshot(thresholds)

    private fun buildStorageHealthSnapshot(thresholds: StorageThresholds): StorageHealthSnapshot {
        val filesDirectory = applicationContext.filesDir
        val statFs = StatFs(filesDirectory.absolutePath)
        val availableBytes = statFs.availableBytes
        val memoryDatabaseBytes = calculateDatabaseBytes()
        val attachmentCacheBytes = calculateDirectoryBytes(File(filesDirectory, ATTACHMENT_DIRECTORY_NAME))

        val stateAndReason = when {
            availableBytes < thresholds.minimumFreeStorageBytes ->
                StorageHealthState.PAUSED_WRITES to "Device free storage is below the memory-write floor."

            memoryDatabaseBytes > thresholds.memoryDatabaseWarningBytes ->
                StorageHealthState.WARNING to "Synapse memory database is over the warning threshold."

            attachmentCacheBytes > thresholds.attachmentCacheWarningBytes ->
                StorageHealthState.WARNING to "Synapse attachment cache is over the warning threshold."

            else -> StorageHealthState.HEALTHY to "Storage is healthy."
        }

        return StorageHealthSnapshot(
            state = stateAndReason.first,
            checkedAt = clock.now(),
            availableBytes = availableBytes,
            memoryDatabaseBytes = memoryDatabaseBytes,
            attachmentCacheBytes = attachmentCacheBytes,
            reason = stateAndReason.second,
        )
    }

    private fun calculateDatabaseBytes(): Long =
        databaseFileNames.sumOf { databaseFileName ->
            val databaseFile = applicationContext.getDatabasePath(databaseFileName)
            if (databaseFile.exists()) databaseFile.length() else 0L
        }

    private fun calculateDirectoryBytes(directory: File): Long {
        if (!directory.exists()) return 0L
        return directory.walkTopDown()
            .filter { file -> file.isFile }
            .sumOf { file -> file.length() }
    }

    private companion object {
        const val ATTACHMENT_DIRECTORY_NAME = "attachments"
        val databaseFileNames = listOf("synapse.db", "synapse.db-wal", "synapse.db-shm")
    }
}
