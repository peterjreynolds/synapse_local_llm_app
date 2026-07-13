package app.synapse.localllm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteChatCacheDao {
    @Query(
        """
        SELECT * FROM remote_profile_cache
        WHERE accountUid = :accountUid
        ORDER BY usernameNormalized ASC, profileUid ASC
        """,
    )
    fun observeProfiles(accountUid: String): Flow<List<RemoteProfileCacheEntity>>

    @Upsert
    suspend fun upsertProfiles(profiles: List<RemoteProfileCacheEntity>)

    @Query(
        """
        SELECT * FROM remote_direct_room_cache
        WHERE accountUid = :accountUid
        ORDER BY remoteUpdatedAtEpochMillis DESC, remoteRoomId ASC
        """,
    )
    fun observeDirectRooms(accountUid: String): Flow<List<RemoteDirectRoomCacheEntity>>

    @Upsert
    suspend fun upsertDirectRooms(rooms: List<RemoteDirectRoomCacheEntity>)

    @Upsert
    suspend fun upsertMemberships(memberships: List<RemoteRoomMembershipCacheEntity>)

    @Query(
        """
        SELECT * FROM remote_message_cache
        WHERE accountUid = :accountUid AND remoteRoomId = :remoteRoomId
        ORDER BY COALESCE(serverCreatedAtEpochMillis, clientCreatedAtEpochMillis) ASC,
                 remoteMessageId ASC
        """,
    )
    fun observeMessages(
        accountUid: String,
        remoteRoomId: String,
    ): Flow<List<RemoteMessageCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: RemoteMessageCacheEntity): Long

    @Upsert
    suspend fun upsertMessages(messages: List<RemoteMessageCacheEntity>)

    @Query(
        """
        UPDATE remote_message_cache
        SET deliveryState = :deliveryState,
            failureReason = :failureReason,
            cachedAtEpochMillis = :cachedAtEpochMillis
        WHERE accountUid = :accountUid
          AND remoteRoomId = :remoteRoomId
          AND remoteMessageId = :remoteMessageId
        """,
    )
    suspend fun updateMessageDelivery(
        accountUid: String,
        remoteRoomId: String,
        remoteMessageId: String,
        deliveryState: String,
        failureReason: String?,
        cachedAtEpochMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutboxOperationIfAbsent(operation: RemoteMessageOutboxEntity): Long

    @Query(
        """
        SELECT * FROM remote_message_outbox
        WHERE accountUid = :accountUid AND state IN ('PENDING', 'IN_FLIGHT', 'FAILED')
        ORDER BY createdAtEpochMillis ASC, operationId ASC
        """,
    )
    fun observePendingOutbox(accountUid: String): Flow<List<RemoteMessageOutboxEntity>>

    @Query(
        """
        UPDATE remote_message_outbox
        SET state = :state,
            attemptCount = attemptCount + 1,
            lastAttemptAtEpochMillis = :lastAttemptAtEpochMillis,
            failureReason = :failureReason
        WHERE accountUid = :accountUid
          AND remoteRoomId = :remoteRoomId
          AND remoteMessageId = :remoteMessageId
        """,
    )
    suspend fun updateOutboxDelivery(
        accountUid: String,
        remoteRoomId: String,
        remoteMessageId: String,
        state: String,
        lastAttemptAtEpochMillis: Long,
        failureReason: String?,
    ): Int

    @Upsert
    suspend fun upsertSyncCursor(cursor: RemoteSyncCursorEntity)

    @Query(
        """
        SELECT * FROM remote_sync_cursors
        WHERE accountUid = :accountUid
          AND collectionName = :collectionName
          AND scopeId = :scopeId
        LIMIT 1
        """,
    )
    suspend fun findSyncCursor(
        accountUid: String,
        collectionName: String,
        scopeId: String,
    ): RemoteSyncCursorEntity?
}
