package app.synapse.localllm.domain.chat

import app.synapse.localllm.domain.ids.AttachmentId
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.ParticipantId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

typealias RoomId = ChatThreadId

object BuiltInParticipantIds {
    val LOCAL_HUMAN = ParticipantId("participant-local-human")
    val SYNAPSE_LOCAL_AI = ParticipantId("participant-synapse-local-ai")
    val SYSTEM = ParticipantId("participant-system")
}

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

enum class RoomKind {
    DIRECT,
    GROUP,
    AI_CHAT,
}

enum class ParticipantKind {
    HUMAN,
    LOCAL_AI,
    REMOTE_AI,
    SYSTEM,
}

enum class RoomMemberRole {
    OWNER,
    MEMBER,
}

enum class AiResponsePolicy {
    NEVER,
    MENTION_ONLY,
    AUTOMATIC,
}

enum class SyncState {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    SYNCED,
    FAILED,
}

data class SyncMetadata(
    val remoteId: String? = null,
    val revision: Long = 0,
    val state: SyncState = SyncState.LOCAL_ONLY,
)

data class ParticipantRecord(
    val id: ParticipantId,
    val kind: ParticipantKind,
    val displayName: String,
    val avatarUri: String?,
    val avatarColorArgb: Long?,
    val syncMetadata: SyncMetadata,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class RoomMemberRecord(
    val roomId: RoomId,
    val participant: ParticipantRecord,
    val role: RoomMemberRole,
    val canPost: Boolean,
    val joinedAt: Instant,
    val leftAt: Instant?,
    val aiResponsePolicy: AiResponsePolicy,
    val syncMetadata: SyncMetadata,
) {
    val isActive: Boolean
        get() = leftAt == null
}

data class ChatRoomRecord(
    val id: RoomId,
    val title: String,
    val kind: RoomKind,
    val isPinned: Boolean,
    val members: List<RoomMemberRecord>,
    val syncMetadata: SyncMetadata,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val activeMembers: List<RoomMemberRecord>
        get() = members.filter(RoomMemberRecord::isActive)

    val activeMemberCount: Int
        get() = activeMembers.size

    val memberSummary: String
        get() = activeMembers.joinToString(", ") { member -> member.participant.displayName }
}

typealias ChatThreadRecord = ChatRoomRecord

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
    val threadId: RoomId,
    val author: ParticipantRecord,
    val role: ConversationRole,
    val body: String,
    val deliveryState: MessageDeliveryState,
    val syncMetadata: SyncMetadata,
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

data class CreateRoomCommand(
    val title: String,
    val kind: RoomKind,
    val placeholderHumanDisplayNames: List<String>,
    val includeSynapseAi: Boolean,
    val synapseAiAutoResponseEnabled: Boolean,
)

data class AddHumanRoomMemberCommand(
    val roomId: RoomId,
    val displayName: String,
    val avatarUri: String? = null,
    val avatarColorArgb: Long? = null,
)

data class SubmitHumanMessageCommand(
    val threadId: RoomId,
    val body: String,
    val attachments: List<PendingAttachment>,
    val authorParticipantId: ParticipantId = BuiltInParticipantIds.LOCAL_HUMAN,
)

typealias SubmitUserMessageCommand = SubmitHumanMessageCommand

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

data class HumanMessageReceipt(
    val roomId: RoomId,
    val messageId: ChatMessageId,
    val authorParticipantId: ParticipantId,
    val submittedAt: Instant,
)

data class AiResponseStartReceipt(
    val roomId: RoomId,
    val messageId: ChatMessageId,
    val authorParticipantId: ParticipantId,
    val startedAt: Instant,
)

enum class RoomMembershipMutation {
    HUMAN_ADDED,
    MEMBER_REMOVED,
    SYNAPSE_AI_ADDED,
    SYNAPSE_AI_REMOVED,
    AI_RESPONSE_POLICY_UPDATED,
}

data class RoomMembershipMutationReceipt(
    val roomId: RoomId,
    val participantId: ParticipantId,
    val mutation: RoomMembershipMutation,
    val changedAt: Instant,
    val affectedRows: Int,
)

interface ConversationRepository {
    suspend fun ensureDefaultRoom(): ChatRoomRecord

    suspend fun findRoom(roomId: RoomId): ChatRoomRecord?

    suspend fun createRoom(command: CreateRoomCommand): ChatRoomRecord

    fun observeRooms(): Flow<List<ChatRoomRecord>>

    fun observeRoomMembers(roomId: RoomId): Flow<List<RoomMemberRecord>>

    suspend fun addHumanRoomMember(command: AddHumanRoomMemberCommand): RoomMembershipMutationReceipt

    suspend fun removeRoomMember(
        roomId: RoomId,
        participantId: ParticipantId,
    ): RoomMembershipMutationReceipt

    suspend fun setSynapseAiEnabled(
        roomId: RoomId,
        enabled: Boolean,
    ): RoomMembershipMutationReceipt

    suspend fun setRoomAiAutoResponse(
        roomId: RoomId,
        enabled: Boolean,
    ): RoomMembershipMutationReceipt

    suspend fun ensureDefaultThread(): ChatThreadRecord = ensureDefaultRoom()

    suspend fun createThread(): ChatThreadRecord =
        createRoom(
            CreateRoomCommand(
                title = "New chat",
                kind = RoomKind.AI_CHAT,
                placeholderHumanDisplayNames = emptyList(),
                includeSynapseAi = true,
                synapseAiAutoResponseEnabled = true,
            ),
        )

    suspend fun createThread(title: String): ChatThreadRecord =
        createRoom(
            CreateRoomCommand(
                title = title,
                kind = RoomKind.AI_CHAT,
                placeholderHumanDisplayNames = emptyList(),
                includeSynapseAi = true,
                synapseAiAutoResponseEnabled = true,
            ),
        )

    fun observeThreads(): Flow<List<ChatThreadRecord>> = observeRooms()

    fun observeMessages(threadId: ChatThreadId): Flow<List<ChatMessageRecord>>

    suspend fun listRecentMessages(threadId: ChatThreadId, limit: Int): List<ChatMessageRecord>

    suspend fun findMessage(messageId: ChatMessageId): ChatMessageRecord?

    suspend fun setRoomPinned(roomId: RoomId, pinned: Boolean): ChatThreadMutationReceipt

    suspend fun setThreadPinned(threadId: ChatThreadId, pinned: Boolean): ChatThreadMutationReceipt =
        setRoomPinned(threadId, pinned)

    suspend fun renameRoom(roomId: RoomId, title: String): ChatThreadMutationReceipt

    suspend fun renameThread(threadId: ChatThreadId, title: String): ChatThreadMutationReceipt =
        renameRoom(threadId, title)

    suspend fun archiveRoom(roomId: RoomId): ChatThreadMutationReceipt

    suspend fun archiveThread(threadId: ChatThreadId): ChatThreadMutationReceipt = archiveRoom(threadId)

    suspend fun deleteRoom(roomId: RoomId): ChatThreadMutationReceipt

    suspend fun deleteThread(threadId: ChatThreadId): ChatThreadMutationReceipt = deleteRoom(threadId)

    suspend fun failStaleStreamingAssistantMessages(
        reason: String,
        activeSmsAutoReplyAfter: Instant,
    ): Int

    suspend fun submitHumanMessage(command: SubmitHumanMessageCommand): HumanMessageReceipt

    suspend fun startAiResponse(
        roomId: RoomId,
        inReplyToHumanMessageId: ChatMessageId? = null,
        authorParticipantId: ParticipantId = BuiltInParticipantIds.SYNAPSE_LOCAL_AI,
    ): AiResponseStartReceipt

    suspend fun appendAssistantToken(messageId: ChatMessageId, token: String)

    suspend fun completeAssistantMessage(messageId: ChatMessageId)

    suspend fun failAssistantMessage(messageId: ChatMessageId, reason: String)
}
