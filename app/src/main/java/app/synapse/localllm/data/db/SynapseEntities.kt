package app.synapse.localllm.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_threads")
data class ChatThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val pinnedAtEpochMillis: Long?,
    val archivedAtEpochMillis: Long?,
    val titleEditedByUser: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("threadId"), Index("createdAtEpochMillis")],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val role: String,
    val body: String,
    val deliveryState: String,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val failureReason: String?,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val displayName: String,
    val mimeType: String?,
    val uri: String,
    val byteCount: Long?,
    val kind: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "trace_events",
    indices = [Index("sourceMessageId"), Index("observedAtEpochMillis")],
)
data class TraceEventEntity(
    @PrimaryKey val id: String,
    val sourceMessageId: String,
    val role: String,
    val text: String,
    val observedAtEpochMillis: Long,
)

@Entity(tableName = "memory_objects", indices = [Index("kind"), Index("status")])
data class MemoryObjectEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "memory_versions",
    foreignKeys = [
        ForeignKey(
            entity = MemoryObjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryObjectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("memoryObjectId"), Index("createdAtEpochMillis"), Index("surfacePolicy")],
)
data class MemoryVersionEntity(
    @PrimaryKey val id: String,
    val memoryObjectId: String,
    val text: String,
    val confidence: Double,
    val surfacePolicy: String,
    val createdAtEpochMillis: Long,
)

data class MemoryVersionWithKind(
    @Embedded val version: MemoryVersionEntity,
    @ColumnInfo(name = "objectKind") val objectKind: String,
)

@Entity(
    tableName = "memory_supports",
    primaryKeys = ["memoryVersionId", "traceEventId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryVersionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TraceEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["traceEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("traceEventId")],
)
data class MemorySupportEntity(
    val memoryVersionId: String,
    val traceEventId: String,
)

@Entity(tableName = "memory_write_receipts", indices = [Index("traceEventId"), Index("memoryObjectId")])
data class MemoryWriteReceiptEntity(
    @PrimaryKey val id: String,
    val outcome: String,
    val traceEventId: String?,
    val memoryObjectId: String?,
    val memoryVersionId: String?,
    val decidedAtEpochMillis: Long,
    val reason: String,
)

@Entity(tableName = "retrieval_receipts")
data class RetrievalReceiptEntity(
    @PrimaryKey val id: String,
    val query: String,
    val promptBlock: String,
    val retrievedAtEpochMillis: Long,
)

@Entity(
    tableName = "retrieved_memory_receipts",
    primaryKeys = ["retrievalReceiptId", "memoryVersionId"],
    foreignKeys = [
        ForeignKey(
            entity = RetrievalReceiptEntity::class,
            parentColumns = ["id"],
            childColumns = ["retrievalReceiptId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MemoryVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryVersionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("memoryVersionId")],
)
data class RetrievedMemoryReceiptEntity(
    val retrievalReceiptId: String,
    val memoryVersionId: String,
    val memoryObjectId: String,
    val reasonCodes: String,
)

@Entity(tableName = "storage_health_snapshots", indices = [Index("checkedAtEpochMillis")])
data class StorageHealthSnapshotEntity(
    @PrimaryKey val id: String,
    val state: String,
    val checkedAtEpochMillis: Long,
    val availableBytes: Long,
    val memoryDatabaseBytes: Long,
    val attachmentCacheBytes: Long,
    val reason: String,
)
