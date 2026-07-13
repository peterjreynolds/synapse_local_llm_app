package app.synapse.localllm.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "remote_profile_cache",
    primaryKeys = ["accountUid", "profileUid"],
    indices = [
        Index("accountUid"),
        Index(value = ["accountUid", "usernameNormalized"], unique = true),
    ],
)
data class RemoteProfileCacheEntity(
    val accountUid: String,
    val profileUid: String,
    val username: String,
    val usernameNormalized: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String?,
    val isAllowed: Boolean,
    val isOnline: Boolean,
    val lastSeenAtEpochMillis: Long?,
    val remoteUpdatedAtEpochMillis: Long,
    val cachedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_direct_room_cache",
    primaryKeys = ["accountUid", "remoteRoomId"],
    indices = [
        Index("accountUid"),
        Index(value = ["accountUid", "peerUid"]),
        Index(value = ["accountUid", "remoteUpdatedAtEpochMillis"]),
    ],
)
data class RemoteDirectRoomCacheEntity(
    val accountUid: String,
    val remoteRoomId: String,
    val directKey: String,
    val peerUid: String,
    val title: String,
    val unreadCount: Int,
    val latestMessagePreview: String?,
    val latestMessageSenderUid: String?,
    val remoteUpdatedAtEpochMillis: Long,
    val cachedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_room_membership_cache",
    primaryKeys = ["accountUid", "remoteRoomId", "memberUid"],
    foreignKeys = [
        ForeignKey(
            entity = RemoteDirectRoomCacheEntity::class,
            parentColumns = ["accountUid", "remoteRoomId"],
            childColumns = ["accountUid", "remoteRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountUid", "remoteRoomId"]),
        Index(value = ["accountUid", "memberUid"]),
    ],
)
data class RemoteRoomMembershipCacheEntity(
    val accountUid: String,
    val remoteRoomId: String,
    val memberUid: String,
    val role: String,
    val isActive: Boolean,
    val joinedAtEpochMillis: Long,
    val lastReadAtEpochMillis: Long?,
    val cachedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_message_cache",
    primaryKeys = ["accountUid", "remoteRoomId", "remoteMessageId"],
    foreignKeys = [
        ForeignKey(
            entity = RemoteDirectRoomCacheEntity::class,
            parentColumns = ["accountUid", "remoteRoomId"],
            childColumns = ["accountUid", "remoteRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountUid", "remoteRoomId"]),
        Index(value = ["accountUid", "remoteRoomId", "idempotencyKey"], unique = true),
        Index(value = ["accountUid", "remoteRoomId", "serverCreatedAtEpochMillis"]),
    ],
)
data class RemoteMessageCacheEntity(
    val accountUid: String,
    val remoteRoomId: String,
    val remoteMessageId: String,
    val idempotencyKey: String,
    val senderUid: String,
    val authorKind: String,
    val body: String,
    val deliveryState: String,
    val clientCreatedAtEpochMillis: Long,
    val serverCreatedAtEpochMillis: Long?,
    val failureReason: String?,
    val cachedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_message_outbox",
    primaryKeys = ["accountUid", "operationId"],
    foreignKeys = [
        ForeignKey(
            entity = RemoteDirectRoomCacheEntity::class,
            parentColumns = ["accountUid", "remoteRoomId"],
            childColumns = ["accountUid", "remoteRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountUid", "remoteRoomId"]),
        Index(value = ["accountUid", "remoteRoomId", "idempotencyKey"], unique = true),
        Index(value = ["accountUid", "state"]),
    ],
)
data class RemoteMessageOutboxEntity(
    val accountUid: String,
    val operationId: String,
    val remoteRoomId: String,
    val remoteMessageId: String,
    val idempotencyKey: String,
    val senderUid: String,
    val body: String,
    val state: String,
    val attemptCount: Int,
    val createdAtEpochMillis: Long,
    val lastAttemptAtEpochMillis: Long?,
    val failureReason: String?,
)

@Entity(
    tableName = "remote_sync_cursors",
    primaryKeys = ["accountUid", "collectionName", "scopeId"],
    indices = [Index("accountUid")],
)
data class RemoteSyncCursorEntity(
    val accountUid: String,
    val collectionName: String,
    val scopeId: String,
    val serverTimestampEpochMillis: Long,
    val documentId: String,
    val updatedAtEpochMillis: Long,
)
