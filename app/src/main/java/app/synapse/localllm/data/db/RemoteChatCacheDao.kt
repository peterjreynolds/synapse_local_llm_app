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
        SELECT room.*
        FROM remote_room_cache AS room
        LEFT JOIN remote_room_local_state AS localState
          ON localState.accountUid = room.accountUid
         AND localState.remoteRoomId = room.remoteRoomId
        WHERE room.accountUid = :accountUid
          AND (
              localState.hiddenThroughRemoteUpdatedAtEpochMillis IS NULL
              OR room.remoteUpdatedAtEpochMillis > localState.hiddenThroughRemoteUpdatedAtEpochMillis
          )
        ORDER BY room.remoteUpdatedAtEpochMillis DESC, room.remoteRoomId ASC
        """,
    )
    fun observeRooms(accountUid: String): Flow<List<RemoteRoomCacheEntity>>

    @Upsert
    suspend fun upsertRooms(rooms: List<RemoteRoomCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRoomIfAbsent(room: RemoteRoomCacheEntity): Long

    @Query(
        """
        UPDATE remote_room_cache
        SET latestMessagePreview = :latestMessagePreview,
            latestMessageSenderUid = :latestMessageSenderUid,
            remoteUpdatedAtEpochMillis = :remoteUpdatedAtEpochMillis,
            cachedAtEpochMillis = :cachedAtEpochMillis
        WHERE accountUid = :accountUid AND remoteRoomId = :remoteRoomId
        """,
    )
    suspend fun updateAssistantConversationSummary(
        accountUid: String,
        remoteRoomId: String,
        latestMessagePreview: String,
        latestMessageSenderUid: String,
        remoteUpdatedAtEpochMillis: Long,
        cachedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM remote_room_cache WHERE accountUid = :accountUid")
    suspend fun deleteAllRooms(accountUid: String): Int

    @Query(
        """
        DELETE FROM remote_room_cache
        WHERE accountUid = :accountUid AND remoteRoomId NOT IN (:authorizedRoomIds)
        """,
    )
    suspend fun deleteRoomsNotIn(
        accountUid: String,
        authorizedRoomIds: List<String>,
    ): Int

    @Query(
        """
        SELECT message.*
        FROM remote_message_cache AS message
        LEFT JOIN remote_room_local_state AS roomLocalState
          ON roomLocalState.accountUid = message.accountUid
         AND roomLocalState.remoteRoomId = message.remoteRoomId
        LEFT JOIN remote_message_local_state AS messageLocalState
          ON messageLocalState.accountUid = message.accountUid
         AND messageLocalState.remoteRoomId = message.remoteRoomId
         AND messageLocalState.remoteMessageId = message.remoteMessageId
        WHERE message.accountUid = :accountUid
          AND message.remoteRoomId = :remoteRoomId
          AND messageLocalState.remoteMessageId IS NULL
          AND (
              roomLocalState.messagesHiddenThroughEpochMillis IS NULL
              OR COALESCE(message.serverCreatedAtEpochMillis, message.clientCreatedAtEpochMillis) >
                  roomLocalState.messagesHiddenThroughEpochMillis
          )
        ORDER BY CASE
                     WHEN message.remoteRoomId = 'assistant_cinder' THEN message.serverSequence
                     ELSE NULL
                 END ASC,
                 COALESCE(message.serverCreatedAtEpochMillis, message.clientCreatedAtEpochMillis) ASC,
                 message.remoteMessageId ASC
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessageSearchEntries(entries: List<RemoteMessageSearchEntity>)

    @Query(
        """
        DELETE FROM remote_message_search
        WHERE accountUid = :accountUid
          AND remoteRoomId = :remoteRoomId
          AND remoteMessageId IN (:remoteMessageIds)
        """,
    )
    suspend fun deleteMessageSearchEntries(
        accountUid: String,
        remoteRoomId: String,
        remoteMessageIds: List<String>,
    ): Int

    @Query(
        """
        DELETE FROM remote_message_search
        WHERE accountUid = :accountUid AND remoteRoomId = :remoteRoomId
        """,
    )
    suspend fun deleteRoomMessageSearchEntries(
        accountUid: String,
        remoteRoomId: String,
    ): Int

    @Query(
        """
        DELETE FROM remote_message_search
        WHERE accountUid = :accountUid
          AND EXISTS (
              SELECT 1
              FROM remote_message_cache AS message
              LEFT JOIN remote_room_local_state AS roomLocalState
                ON roomLocalState.accountUid = message.accountUid
               AND roomLocalState.remoteRoomId = message.remoteRoomId
              LEFT JOIN remote_message_local_state AS messageLocalState
                ON messageLocalState.accountUid = message.accountUid
               AND messageLocalState.remoteRoomId = message.remoteRoomId
               AND messageLocalState.remoteMessageId = message.remoteMessageId
              WHERE message.accountUid = remote_message_search.accountUid
                AND message.remoteRoomId = remote_message_search.remoteRoomId
                AND message.remoteMessageId = remote_message_search.remoteMessageId
                AND (
                    messageLocalState.remoteMessageId IS NOT NULL
                    OR (
                        roomLocalState.messagesHiddenThroughEpochMillis IS NOT NULL
                        AND COALESCE(message.serverCreatedAtEpochMillis, message.clientCreatedAtEpochMillis) <=
                            roomLocalState.messagesHiddenThroughEpochMillis
                    )
                )
          )
        """,
    )
    suspend fun deleteHiddenMessageSearchEntries(accountUid: String): Int

    @Query("DELETE FROM remote_message_search WHERE accountUid = :accountUid")
    suspend fun deleteAllMessageSearchEntries(accountUid: String): Int

    @Query(
        """
        DELETE FROM remote_message_search
        WHERE accountUid = :accountUid AND remoteRoomId NOT IN (:authorizedRoomIds)
        """,
    )
    suspend fun deleteUnauthorizedRoomSearchEntries(
        accountUid: String,
        authorizedRoomIds: List<String>,
    ): Int

    @Query(
        """
        SELECT remoteRoomId, remoteMessageId, body
        FROM remote_message_search
        WHERE accountUid = :accountUid
          AND remote_message_search MATCH :matchQuery
        ORDER BY rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun searchAllMessages(
        accountUid: String,
        matchQuery: String,
        limit: Int,
    ): List<RemoteMessageSearchRow>

    @Query(
        """
        SELECT remoteRoomId, remoteMessageId, body
        FROM remote_message_search
        WHERE accountUid = :accountUid
          AND remoteRoomId = :remoteRoomId
          AND remote_message_search MATCH :matchQuery
        ORDER BY rowid DESC
        LIMIT :limit
        """,
    )
    suspend fun searchRoomMessages(
        accountUid: String,
        remoteRoomId: String,
        matchQuery: String,
        limit: Int,
    ): List<RemoteMessageSearchRow>

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
        SELECT * FROM remote_message_drafts
        WHERE accountUid = :accountUid AND remoteRoomId = :remoteRoomId
        LIMIT 1
        """,
    )
    fun observeDraft(
        accountUid: String,
        remoteRoomId: String,
    ): Flow<RemoteMessageDraftEntity?>

    @Upsert
    suspend fun upsertDraft(draft: RemoteMessageDraftEntity)

    @Query(
        """
        DELETE FROM remote_message_drafts
        WHERE accountUid = :accountUid AND remoteRoomId = :remoteRoomId
        """,
    )
    suspend fun deleteDraft(
        accountUid: String,
        remoteRoomId: String,
    ): Int

    @Upsert
    suspend fun upsertRoomLocalState(localState: RemoteRoomLocalStateEntity)

    @Query(
        """
        UPDATE remote_room_local_state
        SET hiddenThroughRemoteUpdatedAtEpochMillis = NULL,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE accountUid = :accountUid AND remoteRoomId = :remoteRoomId
        """,
    )
    suspend fun showRoomLocally(
        accountUid: String,
        remoteRoomId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Upsert
    suspend fun upsertMessageLocalState(localState: RemoteMessageLocalStateEntity)

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

data class RemoteMessageSearchRow(
    val remoteRoomId: String,
    val remoteMessageId: String,
    val body: String,
)
