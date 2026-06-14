package app.synapse.localllm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_threads ORDER BY updatedAtEpochMillis DESC LIMIT 1")
    suspend fun findLatestThread(): ChatThreadEntity?

    @Query("SELECT * FROM chat_threads WHERE id = :threadId LIMIT 1")
    suspend fun findThread(threadId: String): ChatThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThread(thread: ChatThreadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM chat_messages WHERE id = :messageId LIMIT 1")
    suspend fun findMessage(messageId: String): ChatMessageEntity?

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE threadId = :threadId
        ORDER BY createdAtEpochMillis ASC
        """,
    )
    fun observeMessages(threadId: String): Flow<List<ChatMessageEntity>>

    @Query(
        """
        UPDATE chat_messages
        SET body = :body,
            deliveryState = :deliveryState,
            completedAtEpochMillis = :completedAtEpochMillis,
            failureReason = :failureReason
        WHERE id = :messageId
        """,
    )
    suspend fun updateMessageDelivery(
        messageId: String,
        body: String,
        deliveryState: String,
        completedAtEpochMillis: Long?,
        failureReason: String?,
    )
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTraceEvent(traceEvent: TraceEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemoryObject(memoryObject: MemoryObjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemoryVersion(memoryVersion: MemoryVersionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemorySupports(memorySupports: List<MemorySupportEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemoryWriteReceipt(receipt: MemoryWriteReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRetrievalReceipt(receipt: RetrievalReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRetrievedMemoryReceipts(receipts: List<RetrievedMemoryReceiptEntity>)

    @Query(
        """
        SELECT v.*, o.kind AS objectKind
        FROM memory_versions v
        INNER JOIN memory_objects o ON o.id = v.memoryObjectId
        WHERE o.status = :activeStatus
          AND v.surfacePolicy = :surfacePolicy
        ORDER BY v.createdAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun listPromptVisibleVersions(
        activeStatus: String,
        surfacePolicy: String,
        limit: Int,
    ): List<MemoryVersionWithKind>

    @Query(
        """
        SELECT traceEventId FROM memory_supports
        WHERE memoryVersionId = :memoryVersionId
        ORDER BY traceEventId ASC
        """,
    )
    suspend fun listSupportTraceEventIds(memoryVersionId: String): List<String>

    @Query(
        """
        UPDATE memory_objects
        SET status = :status,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :memoryObjectId
        """,
    )
    suspend fun updateMemoryStatus(
        memoryObjectId: String,
        status: String,
        updatedAtEpochMillis: Long,
    )
}

@Dao
interface StorageHealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStorageHealthSnapshot(snapshot: StorageHealthSnapshotEntity)

    @Query(
        """
        SELECT * FROM storage_health_snapshots
        ORDER BY checkedAtEpochMillis DESC
        LIMIT 1
        """,
    )
    fun observeLatestStorageHealthSnapshot(): Flow<StorageHealthSnapshotEntity?>
}
