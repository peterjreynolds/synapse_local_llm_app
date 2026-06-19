package app.synapse.localllm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query(
        """
        SELECT * FROM chat_threads
        WHERE archivedAtEpochMillis IS NULL
        ORDER BY
            CASE WHEN pinnedAtEpochMillis IS NULL THEN 1 ELSE 0 END ASC,
            pinnedAtEpochMillis DESC,
            updatedAtEpochMillis DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestThread(): ChatThreadEntity?

    @Query("SELECT * FROM chat_threads WHERE id = :threadId LIMIT 1")
    suspend fun findThread(threadId: String): ChatThreadEntity?

    @Query(
        """
        SELECT * FROM chat_threads
        WHERE archivedAtEpochMillis IS NULL
        ORDER BY
            CASE WHEN pinnedAtEpochMillis IS NULL THEN 1 ELSE 0 END ASC,
            pinnedAtEpochMillis DESC,
            updatedAtEpochMillis DESC
        """,
    )
    fun observeThreads(): Flow<List<ChatThreadEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertThreadIfAbsent(thread: ChatThreadEntity): Long

    @Query(
        """
        UPDATE chat_threads
        SET title = CASE
                WHEN titleEditedByUser THEN title
                ELSE :title
            END,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :threadId
          AND archivedAtEpochMillis IS NULL
        """,
    )
    suspend fun updateThreadSummary(
        threadId: String,
        title: String,
        updatedAtEpochMillis: Long,
    )

    @Query(
        """
        UPDATE chat_threads
        SET pinnedAtEpochMillis = :pinnedAtEpochMillis
        WHERE id = :threadId
          AND archivedAtEpochMillis IS NULL
        """,
    )
    suspend fun updateThreadPin(
        threadId: String,
        pinnedAtEpochMillis: Long?,
    ): Int

    @Query(
        """
        UPDATE chat_threads
        SET title = :title,
            titleEditedByUser = 1
        WHERE id = :threadId
          AND archivedAtEpochMillis IS NULL
        """,
    )
    suspend fun renameThread(
        threadId: String,
        title: String,
    ): Int

    @Query(
        """
        UPDATE chat_threads
        SET archivedAtEpochMillis = :archivedAtEpochMillis,
            pinnedAtEpochMillis = NULL
        WHERE id = :threadId
          AND archivedAtEpochMillis IS NULL
        """,
    )
    suspend fun archiveThread(
        threadId: String,
        archivedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM chat_threads WHERE id = :threadId")
    suspend fun deleteThread(threadId: String): Int

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
        SELECT * FROM chat_messages
        WHERE threadId = :threadId
        ORDER BY createdAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecentMessages(threadId: String, limit: Int): List<ChatMessageEntity>

    @Query(
        """
        UPDATE chat_messages
        SET deliveryState = :failedState,
            completedAtEpochMillis = :completedAtEpochMillis,
            failureReason = :failureReason
        WHERE role = :assistantRole
          AND deliveryState = :streamingState
        """,
    )
    suspend fun failStreamingAssistantMessages(
        assistantRole: String,
        streamingState: String,
        failedState: String,
        completedAtEpochMillis: Long,
        failureReason: String,
    ): Int

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
interface DiagnosticsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssistantGenerationTrace(trace: AssistantGenerationTraceEntity)

    @Query(
        """
        UPDATE assistant_generation_traces
        SET completedAtEpochMillis = :completedAtEpochMillis,
            rawTokenEvents = :rawTokenEvents,
            rawCharacterCount = :rawCharacterCount,
            visibleCharacterCount = :visibleCharacterCount,
            filteredCharacterCount = :filteredCharacterCount,
            firstRawTokenAtEpochMillis = :firstRawTokenAtEpochMillis,
            firstVisibleTokenAtEpochMillis = :firstVisibleTokenAtEpochMillis,
            stopReason = :stopReason,
            failureReason = :failureReason
        WHERE id = :traceId
        """,
    )
    suspend fun finishAssistantGenerationTrace(
        traceId: String,
        completedAtEpochMillis: Long,
        rawTokenEvents: Int,
        rawCharacterCount: Int,
        visibleCharacterCount: Int,
        filteredCharacterCount: Int,
        firstRawTokenAtEpochMillis: Long?,
        firstVisibleTokenAtEpochMillis: Long?,
        stopReason: String,
        failureReason: String?,
    )
}

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibraryArtifact(artifact: LibraryArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibraryArtifactWriteReceipt(receipt: LibraryArtifactWriteReceiptEntity)

    @Query("SELECT * FROM library_artifacts WHERE id = :artifactId LIMIT 1")
    suspend fun findLibraryArtifact(artifactId: String): LibraryArtifactEntity?

    @Query(
        """
        SELECT * FROM library_artifacts
        ORDER BY updatedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun listLibraryArtifacts(limit: Int): List<LibraryArtifactEntity>
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
        SELECT v.*, o.kind AS objectKind, o.status AS objectStatus, o.claimKey AS objectClaimKey
        FROM memory_versions v
        INNER JOIN memory_objects o ON o.id = v.memoryObjectId
        WHERE o.status = :activeStatus
          AND v.surfacePolicy = :surfacePolicy
          AND v.id = (
              SELECT v2.id
              FROM memory_versions v2
              WHERE v2.memoryObjectId = o.id
              ORDER BY v2.createdAtEpochMillis DESC, v2.id DESC
              LIMIT 1
          )
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
        SELECT v.*, o.kind AS objectKind, o.status AS objectStatus, o.claimKey AS objectClaimKey
        FROM memory_versions v
        INNER JOIN memory_objects o ON o.id = v.memoryObjectId
        WHERE o.status IN (:statuses)
          AND v.id = (
              SELECT v2.id
              FROM memory_versions v2
              WHERE v2.memoryObjectId = o.id
              ORDER BY v2.createdAtEpochMillis DESC, v2.id DESC
              LIMIT 1
          )
        ORDER BY v.createdAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun listLatestVersionsByStatuses(
        statuses: List<String>,
        limit: Int,
    ): List<MemoryVersionWithKind>

    @Query(
        """
        SELECT v.*, o.kind AS objectKind, o.status AS objectStatus, o.claimKey AS objectClaimKey
        FROM memory_versions v
        INNER JOIN memory_objects o ON o.id = v.memoryObjectId
        WHERE o.status = :activeStatus
          AND o.claimKey = :claimKey
          AND v.id = (
              SELECT v2.id
              FROM memory_versions v2
              WHERE v2.memoryObjectId = o.id
              ORDER BY v2.createdAtEpochMillis DESC, v2.id DESC
              LIMIT 1
          )
        ORDER BY v.createdAtEpochMillis DESC
        """,
    )
    suspend fun listActiveVersionsByClaimKey(
        activeStatus: String,
        claimKey: String,
    ): List<MemoryVersionWithKind>

    @Query(
        """
        SELECT v.*, o.kind AS objectKind, o.status AS objectStatus, o.claimKey AS objectClaimKey
        FROM memory_versions v
        INNER JOIN memory_objects o ON o.id = v.memoryObjectId
        WHERE o.claimKey = :claimKey
          AND v.id = (
              SELECT v2.id
              FROM memory_versions v2
              WHERE v2.memoryObjectId = o.id
              ORDER BY v2.createdAtEpochMillis DESC, v2.id DESC
              LIMIT 1
          )
        ORDER BY v.createdAtEpochMillis DESC
        """,
    )
    suspend fun listLatestVersionsByClaimKey(claimKey: String): List<MemoryVersionWithKind>

    @Query(
        """
        SELECT v.*, o.kind AS objectKind, o.status AS objectStatus, o.claimKey AS objectClaimKey
        FROM memory_versions v
        INNER JOIN memory_objects o ON o.id = v.memoryObjectId
        WHERE o.id = :memoryObjectId
          AND v.id = (
              SELECT v2.id
              FROM memory_versions v2
              WHERE v2.memoryObjectId = o.id
              ORDER BY v2.createdAtEpochMillis DESC, v2.id DESC
              LIMIT 1
          )
        LIMIT 1
        """,
    )
    suspend fun findLatestVersionByMemoryObjectId(memoryObjectId: String): MemoryVersionWithKind?

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

    @Query(
        """
        UPDATE memory_objects
        SET status = :status,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id IN (:memoryObjectIds)
        """,
    )
    suspend fun updateMemoryStatuses(
        memoryObjectIds: List<String>,
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
