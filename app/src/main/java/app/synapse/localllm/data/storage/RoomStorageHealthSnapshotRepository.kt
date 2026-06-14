package app.synapse.localllm.data.storage

import app.synapse.localllm.data.db.StorageHealthDao
import app.synapse.localllm.data.db.StorageHealthSnapshotEntity
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.storage.StorageHealthState
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomStorageHealthSnapshotRepository(
    private val storageHealthDao: StorageHealthDao,
    private val idFactory: SynapseIdFactory,
) {
    fun observeLatestStorageHealth(): Flow<StorageHealthSnapshot?> =
        storageHealthDao.observeLatestStorageHealthSnapshot().map { snapshot ->
            snapshot?.toDomain()
        }

    suspend fun persistStorageHealthSnapshot(snapshot: StorageHealthSnapshot) {
        storageHealthDao.upsertStorageHealthSnapshot(snapshot.toEntity(idFactory.createReceiptId().raw))
    }

    private fun StorageHealthSnapshot.toEntity(id: String): StorageHealthSnapshotEntity =
        StorageHealthSnapshotEntity(
            id = id,
            state = state.name,
            checkedAtEpochMillis = checkedAt.toEpochMilli(),
            availableBytes = availableBytes,
            memoryDatabaseBytes = memoryDatabaseBytes,
            attachmentCacheBytes = attachmentCacheBytes,
            reason = reason,
        )

    private fun StorageHealthSnapshotEntity.toDomain(): StorageHealthSnapshot =
        StorageHealthSnapshot(
            state = StorageHealthState.valueOf(state),
            checkedAt = Instant.ofEpochMilli(checkedAtEpochMillis),
            availableBytes = availableBytes,
            memoryDatabaseBytes = memoryDatabaseBytes,
            attachmentCacheBytes = attachmentCacheBytes,
            reason = reason,
        )
}
