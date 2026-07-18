package app.synapse.localllm.domain.remote

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class RemoteAccountUid(val raw: String) {
    init {
        require(raw.isNotBlank()) { "Remote account UID cannot be blank." }
    }
}

@JvmInline
value class RemoteProfileUid(val raw: String) {
    init {
        require(raw.isNotBlank()) { "Remote profile UID cannot be blank." }
    }
}

@JvmInline
value class RemoteRoomId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "Remote room ID cannot be blank." }
    }
}

fun isValidRemoteDirectRoomId(rawRoomId: String): Boolean =
    REMOTE_DIRECT_ROOM_ID_PATTERN.matches(rawRoomId)

fun isValidRemoteGroupRoomId(rawRoomId: String): Boolean =
    REMOTE_GROUP_ROOM_ID_PATTERN.matches(rawRoomId)

fun isValidRemoteConversationRoomId(rawRoomId: String): Boolean =
    isValidRemoteDirectRoomId(rawRoomId) || isValidRemoteGroupRoomId(rawRoomId)

@JvmInline
value class RemoteMessageId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "Remote message ID cannot be blank." }
    }
}

@JvmInline
value class RemoteIdempotencyKey(val raw: String) {
    init {
        require(raw.isNotBlank()) { "Remote idempotency key cannot be blank." }
    }
}

enum class RemoteMessageDeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}

enum class RemoteOutboxState {
    PENDING,
    IN_FLIGHT,
    FAILED,
    COMPLETE,
}

enum class RemoteRoomKind {
    DIRECT,
    GROUP,
}

enum class RemoteRoomMemberRole {
    OWNER,
    ADMIN,
    MEMBER,
}

enum class RemoteRoomMuteDuration {
    OFF,
    ONE_HOUR,
    EIGHT_HOURS,
    ONE_WEEK,
    FOREVER,
}

data class RemoteCachedProfile(
    val accountUid: RemoteAccountUid,
    val profileUid: RemoteProfileUid,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String?,
    val isAllowed: Boolean,
    val isOnline: Boolean,
    val lastSeenAt: Instant?,
    val remoteUpdatedAt: Instant,
)

data class RemoteCachedRoom(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val kind: RemoteRoomKind,
    val directKey: String?,
    val peerUid: RemoteProfileUid?,
    val title: String,
    val avatarObjectPath: String?,
    val unreadCount: Int,
    val latestMessagePreview: String?,
    val latestMessageSenderUid: RemoteProfileUid?,
    val currentMemberRole: RemoteRoomMemberRole,
    val notificationsEnabled: Boolean,
    val isMuted: Boolean,
    val isArchived: Boolean,
    val isPinned: Boolean,
    val joinedAt: Instant,
    val lastReadAt: Instant?,
    val remoteUpdatedAt: Instant,
    val mutedUntil: Instant? = null,
) {
    init {
        require(unreadCount >= 0) { "Remote room unread count cannot be negative." }
        when (kind) {
            RemoteRoomKind.DIRECT -> {
                require(!directKey.isNullOrBlank()) { "Direct rooms require a direct key." }
                require(peerUid != null) { "Direct rooms require a peer UID." }
                require(avatarObjectPath == null) { "Direct rooms cannot own a group avatar." }
            }

            RemoteRoomKind.GROUP -> {
                require(directKey == null) { "Group rooms cannot have a direct key." }
                require(peerUid == null) { "Group rooms cannot have a direct peer." }
            }
        }
    }
}

data class RemoteCachedMessage(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val idempotencyKey: RemoteIdempotencyKey,
    val senderUid: RemoteProfileUid,
    val authorKind: String,
    val body: String,
    val attachments: List<RemoteCachedAttachment> = emptyList(),
    val replyToMessageId: RemoteMessageId?,
    val editedAt: Instant?,
    val deletedAt: Instant?,
    val revision: Long,
    val reactionCounts: Map<String, Int>,
    val deliveredToCount: Int,
    val readByCount: Int,
    val deliveryState: RemoteMessageDeliveryState,
    val clientCreatedAt: Instant,
    val serverCreatedAt: Instant?,
    val failureReason: String?,
    val aiParticipantId: String? = null,
    val aiProvenance: RemoteAiProvenance? = null,
) {
    init {
        require(revision >= 1L) { "Remote message revision must be positive." }
        require(deliveredToCount >= 0 && readByCount >= 0) { "Remote message receipt counts cannot be negative." }
        require(reactionCounts.keys.all { emoji -> emoji.isNotBlank() && emoji.length <= 16 }) {
            "Remote reaction identifiers are invalid."
        }
        require(reactionCounts.values.all { count -> count > 0 }) { "Remote reaction counts must be positive." }
        require(deletedAt != null || body.isNotBlank() || attachments.isNotEmpty()) {
            "Active remote messages require text or an attachment."
        }
        require(attachments.size <= 8 && attachments.distinctBy(RemoteCachedAttachment::attachmentId).size == attachments.size) {
            "Remote message attachments must be unique and bounded."
        }
        when (authorKind) {
            "HUMAN" -> require(aiParticipantId == null && aiProvenance == null) {
                "Human messages cannot claim AI provenance."
            }

            "SYNAPSE_AI" -> require(
                aiParticipantId == "participant-synapse-local-ai" && aiProvenance != null,
            ) {
                "Synapse AI messages require an explicit participant and provenance."
            }

            else -> error("Remote message author kind is unsupported.")
        }
    }
}

data class RemoteMessageOutboxOperation(
    val accountUid: RemoteAccountUid,
    val operationId: String,
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val idempotencyKey: RemoteIdempotencyKey,
    val senderUid: RemoteProfileUid,
    val body: String,
    val attachments: List<RemoteCachedAttachment> = emptyList(),
    val replyToMessageId: RemoteMessageId?,
    val state: RemoteOutboxState,
    val attemptCount: Int,
    val createdAt: Instant,
    val lastAttemptAt: Instant?,
    val failureReason: String?,
)

data class RemoteSyncCursor(
    val accountUid: RemoteAccountUid,
    val collectionName: String,
    val scopeId: String,
    val serverTimestamp: Instant,
    val documentId: String,
    val updatedAt: Instant,
)

data class CacheRemoteProfilesCommand(
    val accountUid: RemoteAccountUid,
    val profiles: List<RemoteCachedProfile>,
)

data class CacheRemoteRoomsCommand(
    val accountUid: RemoteAccountUid,
    val rooms: List<RemoteCachedRoom>,
)

data class CacheRemoteMessagesCommand(
    val accountUid: RemoteAccountUid,
    val messages: List<RemoteCachedMessage>,
)

data class EnqueueRemoteMessageCommand(
    val message: RemoteCachedMessage,
    val outboxOperation: RemoteMessageOutboxOperation,
)

data class RemoteMessageDraft(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val body: String,
    val updatedAt: Instant,
)

data class SearchRemoteMessagesCommand(
    val accountUid: RemoteAccountUid,
    val query: String,
    val roomId: RemoteRoomId? = null,
    val limit: Int = 25,
)

data class RemoteMessageSearchResult(
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val excerpt: String,
)

data class RemoteNotificationPreferences(
    val directMessages: Boolean = true,
    val groupMessages: Boolean = true,
    val mentions: Boolean = true,
    val mutedRooms: Boolean = false,
)

enum class RemoteCacheMutation {
    PROFILES_CACHED,
    ROOMS_CACHED,
    MESSAGES_CACHED,
    MESSAGE_ENQUEUED,
    MESSAGE_ALREADY_ENQUEUED,
    DELIVERY_UPDATED,
    CURSOR_SAVED,
    DRAFT_SAVED,
    DRAFT_CLEARED,
    MESSAGE_HIDDEN_LOCALLY,
    CONVERSATION_HIDDEN_LOCALLY,
    CONVERSATION_SHOWN_LOCALLY,
}

data class RemoteCacheMutationReceipt(
    val accountUid: RemoteAccountUid,
    val mutation: RemoteCacheMutation,
    val affectedRows: Int,
)

interface RemoteChatCacheRepository {
    val activeAccountUid: StateFlow<RemoteAccountUid?>

    suspend fun activateAccount(accountUid: RemoteAccountUid)

    suspend fun clearActiveAccount()

    fun observeProfiles(): Flow<List<RemoteCachedProfile>>

    fun observeRooms(): Flow<List<RemoteCachedRoom>>

    fun observeMessages(roomId: RemoteRoomId): Flow<List<RemoteCachedMessage>>

    fun observePendingOutbox(): Flow<List<RemoteMessageOutboxOperation>>

    fun observeDraft(roomId: RemoteRoomId): Flow<RemoteMessageDraft?>

    suspend fun cacheProfiles(command: CacheRemoteProfilesCommand): RemoteCacheMutationReceipt

    suspend fun cacheRooms(command: CacheRemoteRoomsCommand): RemoteCacheMutationReceipt

    suspend fun cacheMessages(command: CacheRemoteMessagesCommand): RemoteCacheMutationReceipt

    suspend fun enqueueMessage(command: EnqueueRemoteMessageCommand): RemoteCacheMutationReceipt

    suspend fun updateMessageDelivery(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        messageId: RemoteMessageId,
        deliveryState: RemoteMessageDeliveryState,
        outboxState: RemoteOutboxState,
        attemptedAt: Instant,
        failureReason: String?,
    ): RemoteCacheMutationReceipt

    suspend fun saveSyncCursor(cursor: RemoteSyncCursor): RemoteCacheMutationReceipt

    suspend fun saveDraft(draft: RemoteMessageDraft): RemoteCacheMutationReceipt

    suspend fun clearDraft(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteCacheMutationReceipt

    suspend fun hideMessageLocally(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        messageId: RemoteMessageId,
    ): RemoteCacheMutationReceipt

    suspend fun hideConversationLocally(
        accountUid: RemoteAccountUid,
        room: RemoteCachedRoom,
    ): RemoteCacheMutationReceipt

    suspend fun showConversationLocally(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteCacheMutationReceipt

    suspend fun searchMessages(command: SearchRemoteMessagesCommand): List<RemoteMessageSearchResult>

    suspend fun findSyncCursor(
        collectionName: String,
        scopeId: String,
    ): RemoteSyncCursor?
}

private val REMOTE_DIRECT_ROOM_ID_PATTERN = Regex("^direct_[a-f0-9]{64}$")
private val REMOTE_GROUP_ROOM_ID_PATTERN = Regex("^group_[a-f0-9]{32}$")
