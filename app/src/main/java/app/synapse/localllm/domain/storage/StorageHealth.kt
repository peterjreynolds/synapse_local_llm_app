package app.synapse.localllm.domain.storage

import java.time.Instant

enum class StorageHealthState {
    HEALTHY,
    WARNING,
    PAUSED_WRITES,
    READ_ONLY_RECOVERY,
    CORRUPT_SUSPECTED,
}

data class StorageThresholds(
    val memoryDatabaseWarningBytes: Long,
    val attachmentCacheWarningBytes: Long,
    val minimumFreeStorageBytes: Long,
)

data class StorageHealthSnapshot(
    val state: StorageHealthState,
    val checkedAt: Instant,
    val availableBytes: Long,
    val memoryDatabaseBytes: Long,
    val attachmentCacheBytes: Long,
    val reason: String,
)

interface StorageHealthGovernor {
    suspend fun inspectStorageHealth(thresholds: StorageThresholds): StorageHealthSnapshot

    suspend fun canWriteMemory(thresholds: StorageThresholds): StorageHealthSnapshot
}
