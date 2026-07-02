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
    tableName = "assistant_generation_traces",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["assistantMessageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("assistantMessageId"), Index("startedAtEpochMillis")],
)
data class AssistantGenerationTraceEntity(
    @PrimaryKey val id: String,
    val assistantMessageId: String,
    val backend: String,
    val modelName: String,
    val promptMessageCount: Int,
    val promptCharacterCount: Int,
    val retrievedMemoryCount: Int,
    val maxTokens: Int,
    val temperature: Double,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val rawTokenEvents: Int,
    val rawCharacterCount: Int,
    val visibleCharacterCount: Int,
    val filteredCharacterCount: Int,
    val firstRawTokenAtEpochMillis: Long?,
    val firstVisibleTokenAtEpochMillis: Long?,
    val stopReason: String?,
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
    tableName = "library_artifacts",
    indices = [
        Index("artifactKind"),
        Index("sourceKind"),
        Index("sha256"),
        Index("updatedAtEpochMillis"),
    ],
)
data class LibraryArtifactEntity(
    @PrimaryKey val id: String,
    val title: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val artifactKind: String,
    val sourceKind: String,
    val sha256: String,
    val byteCount: Long,
    val catalogSummary: String?,
    val tagsCsv: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "library_artifact_write_receipts",
    foreignKeys = [
        ForeignKey(
            entity = LibraryArtifactEntity::class,
            parentColumns = ["id"],
            childColumns = ["artifactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artifactId"), Index("writtenAtEpochMillis")],
)
data class LibraryArtifactWriteReceiptEntity(
    @PrimaryKey val id: String,
    val artifactId: String,
    val mutation: String,
    val writtenAtEpochMillis: Long,
    val reason: String,
    val byteCount: Long,
    val sha256: String,
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

@Entity(tableName = "memory_objects", indices = [Index("kind"), Index("status"), Index("claimKey")])
data class MemoryObjectEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val status: String,
    val claimKey: String?,
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
    indices = [
        Index("memoryObjectId"),
        Index("createdAtEpochMillis"),
        Index("surfacePolicy"),
        Index("scope"),
        Index("subject"),
        Index("domain"),
        Index("predicate"),
        Index("writeIntent"),
        Index("sensitivity"),
    ],
)
data class MemoryVersionEntity(
    @PrimaryKey val id: String,
    val memoryObjectId: String,
    val text: String,
    val confidence: Double,
    val surfacePolicy: String,
    val scope: String,
    val domain: String,
    val subject: String?,
    val predicate: String?,
    val valueText: String?,
    val sourceQuote: String?,
    val writeIntent: String,
    val durabilityScore: Double,
    val futureUsefulnessScore: Double,
    val sensitivity: String,
    val keywordsCsv: String,
    val createdAtEpochMillis: Long,
)

data class MemoryVersionWithKind(
    @Embedded val version: MemoryVersionEntity,
    @ColumnInfo(name = "objectKind") val objectKind: String,
    @ColumnInfo(name = "objectStatus") val objectStatus: String,
    @ColumnInfo(name = "objectClaimKey") val objectClaimKey: String?,
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
    val retrievalIntent: String,
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
    val rankScore: Double,
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

@Entity(
    tableName = "sms_sender_threads",
    foreignKeys = [
        ForeignKey(
            entity = ChatThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("threadId")],
)
data class SmsSenderThreadEntity(
    @PrimaryKey val senderAddress: String,
    val threadId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "sms_auto_reply_receipts",
    foreignKeys = [
        ForeignKey(
            entity = ChatThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["userMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["assistantMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["inboundMessageKey"], unique = true),
        Index("senderAddress"),
        Index("threadId"),
        Index("userMessageId"),
        Index("assistantMessageId"),
        Index("decidedAtEpochMillis"),
    ],
)
data class SmsAutoReplyReceiptEntity(
    @PrimaryKey val id: String,
    val inboundMessageKey: String,
    val senderAddress: String,
    val inboundBodySha256: String,
    val inboundCharacterCount: Int,
    val inboundReceivedAtEpochMillis: Long,
    val threadId: String?,
    val userMessageId: String?,
    val assistantMessageId: String?,
    val state: String,
    val replyBodySha256: String?,
    val replyCharacterCount: Int,
    val smsPartCount: Int,
    val queuedAtEpochMillis: Long?,
    val decidedAtEpochMillis: Long,
    val failureReason: String?,
)
