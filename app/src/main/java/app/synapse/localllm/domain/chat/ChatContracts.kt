package app.synapse.localllm.domain.chat

import app.synapse.localllm.domain.ids.AttachmentId
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

enum class ConversationRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

enum class AttachmentKind {
    TEXT,
    IMAGE,
    FILE,
}

enum class MessageDeliveryState {
    DRAFT,
    SUBMITTED,
    STREAMING,
    COMPLETE,
    FAILED,
}

data class ChatThreadRecord(
    val id: ChatThreadId,
    val title: String,
    val isPinned: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class ChatThreadMutation {
    PINNED,
    UNPINNED,
    RENAMED,
    ARCHIVED,
    DELETED,
}

data class ChatThreadMutationReceipt(
    val threadId: ChatThreadId,
    val mutation: ChatThreadMutation,
    val changedAt: Instant,
    val affectedRows: Int,
)

data class ChatMessageRecord(
    val id: ChatMessageId,
    val threadId: ChatThreadId,
    val role: ConversationRole,
    val body: String,
    val deliveryState: MessageDeliveryState,
    val createdAt: Instant,
    val completedAt: Instant?,
    val failureReason: String?,
)

data class AttachmentRecord(
    val id: AttachmentId,
    val messageId: ChatMessageId,
    val displayName: String,
    val mimeType: String?,
    val uri: String,
    val byteCount: Long?,
    val kind: AttachmentKind,
    val createdAt: Instant,
)

data class SubmitUserMessageCommand(
    val threadId: ChatThreadId,
    val body: String,
    val attachments: List<PendingAttachment>,
)

data class PendingAttachment(
    val displayName: String,
    val mimeType: String?,
    val uri: String,
    val byteCount: Long?,
    val kind: AttachmentKind,
    val extractedText: String?,
)

data class ConversationTurnReceipt(
    val userMessageId: ChatMessageId,
    val assistantMessageId: ChatMessageId,
    val submittedAt: Instant,
)

interface ConversationRepository {
    suspend fun ensureDefaultThread(): ChatThreadRecord

    suspend fun createThread(): ChatThreadRecord

    suspend fun createThread(title: String): ChatThreadRecord

    fun observeThreads(): Flow<List<ChatThreadRecord>>

    fun observeMessages(threadId: ChatThreadId): Flow<List<ChatMessageRecord>>

    suspend fun listRecentMessages(threadId: ChatThreadId, limit: Int): List<ChatMessageRecord>

    suspend fun findMessage(messageId: ChatMessageId): ChatMessageRecord?

    suspend fun setThreadPinned(threadId: ChatThreadId, pinned: Boolean): ChatThreadMutationReceipt

    suspend fun renameThread(threadId: ChatThreadId, title: String): ChatThreadMutationReceipt

    suspend fun archiveThread(threadId: ChatThreadId): ChatThreadMutationReceipt

    suspend fun deleteThread(threadId: ChatThreadId): ChatThreadMutationReceipt

    suspend fun failStaleStreamingAssistantMessages(reason: String): Int

    suspend fun submitUserMessage(command: SubmitUserMessageCommand): ConversationTurnReceipt

    suspend fun appendAssistantToken(messageId: ChatMessageId, token: String)

    suspend fun completeAssistantMessage(messageId: ChatMessageId)

    suspend fun failAssistantMessage(messageId: ChatMessageId, reason: String)
}
