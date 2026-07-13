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
    FAILED,
}

enum class RemoteOutboxState {
    PENDING,
    IN_FLIGHT,
    FAILED,
    COMPLETE,
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

data class RemoteCachedDirectRoom(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val directKey: String,
    val peerUid: RemoteProfileUid,
    val title: String,
    val unreadCount: Int,
    val latestMessagePreview: String?,
    val latestMessageSenderUid: RemoteProfileUid?,
    val remoteUpdatedAt: Instant,
)

data class RemoteCachedMembership(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val memberUid: RemoteProfileUid,
    val role: String,
    val isActive: Boolean,
    val joinedAt: Instant,
    val lastReadAt: Instant?,
)

data class RemoteCachedMessage(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val idempotencyKey: RemoteIdempotencyKey,
    val senderUid: RemoteProfileUid,
    val authorKind: String,
    val body: String,
    val deliveryState: RemoteMessageDeliveryState,
    val clientCreatedAt: Instant,
    val serverCreatedAt: Instant?,
    val failureReason: String?,
)

data class RemoteMessageOutboxOperation(
    val accountUid: RemoteAccountUid,
    val operationId: String,
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val idempotencyKey: RemoteIdempotencyKey,
    val senderUid: RemoteProfileUid,
    val body: String,
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
    val rooms: List<RemoteCachedDirectRoom>,
    val memberships: List<RemoteCachedMembership>,
)

data class CacheRemoteMessagesCommand(
    val accountUid: RemoteAccountUid,
    val messages: List<RemoteCachedMessage>,
)

data class EnqueueRemoteMessageCommand(
    val message: RemoteCachedMessage,
    val outboxOperation: RemoteMessageOutboxOperation,
)

enum class RemoteCacheMutation {
    PROFILES_CACHED,
    ROOMS_CACHED,
    MESSAGES_CACHED,
    MESSAGE_ENQUEUED,
    MESSAGE_ALREADY_ENQUEUED,
    DELIVERY_UPDATED,
    CURSOR_SAVED,
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

    fun observeDirectRooms(): Flow<List<RemoteCachedDirectRoom>>

    fun observeMessages(roomId: RemoteRoomId): Flow<List<RemoteCachedMessage>>

    fun observePendingOutbox(): Flow<List<RemoteMessageOutboxOperation>>

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

    suspend fun findSyncCursor(
        collectionName: String,
        scopeId: String,
    ): RemoteSyncCursor?
}
