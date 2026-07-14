package app.synapse.localllm.data.remote

import androidx.room.withTransaction
import app.synapse.localllm.data.db.RemoteChatCacheDao
import app.synapse.localllm.data.db.RemoteMessageCacheEntity
import app.synapse.localllm.data.db.RemoteMessageDraftEntity
import app.synapse.localllm.data.db.RemoteMessageOutboxEntity
import app.synapse.localllm.data.db.RemoteMessageSearchEntity
import app.synapse.localllm.data.db.RemoteMessageSearchRow
import app.synapse.localllm.data.db.RemoteProfileCacheEntity
import app.synapse.localllm.data.db.RemoteRoomCacheEntity
import app.synapse.localllm.data.db.RemoteSyncCursorEntity
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.remote.CacheRemoteMessagesCommand
import app.synapse.localllm.domain.remote.CacheRemoteProfilesCommand
import app.synapse.localllm.domain.remote.CacheRemoteRoomsCommand
import app.synapse.localllm.domain.remote.EnqueueRemoteMessageCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteCacheMutation
import app.synapse.localllm.domain.remote.RemoteCacheMutationReceipt
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageDraft
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageOutboxOperation
import app.synapse.localllm.domain.remote.RemoteMessageSearchResult
import app.synapse.localllm.domain.remote.RemoteOutboxState
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.remote.RemoteSyncCursor
import app.synapse.localllm.domain.remote.SearchRemoteMessagesCommand
import app.synapse.localllm.domain.time.SynapseClock
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class RoomRemoteChatCacheRepository(
    private val database: SynapseDatabase,
    private val remoteChatCacheDao: RemoteChatCacheDao,
    private val sessionController: RemoteAccountSessionController,
    private val clock: SynapseClock,
    applicationScope: CoroutineScope,
) : RemoteChatCacheRepository {
    override val activeAccountUid: StateFlow<RemoteAccountUid?> =
        sessionController.activeSession
            .map { session -> session?.accountUid }
            .stateIn(applicationScope, SharingStarted.Eagerly, sessionController.activeSession.value?.accountUid)

    override suspend fun activateAccount(accountUid: RemoteAccountUid) {
        sessionController.beginSession(accountUid)
    }

    override suspend fun clearActiveAccount() {
        sessionController.endSession()
    }

    override fun observeProfiles(): Flow<List<RemoteCachedProfile>> =
        sessionController.activeSession.flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                remoteChatCacheDao.observeProfiles(session.accountUid.raw).map { profiles ->
                    profiles.map(RemoteProfileCacheEntity::toDomain)
                }
            }
        }

    override fun observeRooms(): Flow<List<RemoteCachedRoom>> =
        sessionController.activeSession.flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                remoteChatCacheDao.observeRooms(session.accountUid.raw).map { rooms ->
                    rooms.map(RemoteRoomCacheEntity::toDomain)
                }
            }
        }

    override fun observeMessages(roomId: RemoteRoomId): Flow<List<RemoteCachedMessage>> =
        sessionController.activeSession.flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                remoteChatCacheDao.observeMessages(session.accountUid.raw, roomId.raw).map { messages ->
                    messages.map(RemoteMessageCacheEntity::toDomain)
                }
            }
        }

    override fun observePendingOutbox(): Flow<List<RemoteMessageOutboxOperation>> =
        sessionController.activeSession.flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                remoteChatCacheDao.observePendingOutbox(session.accountUid.raw).map { operations ->
                    operations.map(RemoteMessageOutboxEntity::toDomain)
                }
            }
        }

    override fun observeDraft(roomId: RemoteRoomId): Flow<RemoteMessageDraft?> =
        sessionController.activeSession.flatMapLatest { session ->
            if (session == null) {
                flowOf(null)
            } else {
                remoteChatCacheDao.observeDraft(session.accountUid.raw, roomId.raw).map { draft ->
                    draft?.toDomain()
                }
            }
        }

    override suspend fun cacheProfiles(command: CacheRemoteProfilesCommand): RemoteCacheMutationReceipt {
        requireActiveAccount(command.accountUid)
        require(command.profiles.all { profile -> profile.accountUid == command.accountUid }) {
            "Every cached profile must belong to the active account scope."
        }
        remoteChatCacheDao.upsertProfiles(command.profiles.map { profile -> profile.toEntity(clock.now()) })
        return receipt(command.accountUid, RemoteCacheMutation.PROFILES_CACHED, command.profiles.size)
    }

    override suspend fun cacheRooms(command: CacheRemoteRoomsCommand): RemoteCacheMutationReceipt {
        requireActiveAccount(command.accountUid)
        require(command.rooms.all { room -> room.accountUid == command.accountUid }) {
            "Every cached room must belong to the active account scope."
        }
        val cachedAt = clock.now()
        val removedRooms = database.withTransaction {
            remoteChatCacheDao.upsertRooms(command.rooms.map { room -> room.toEntity(cachedAt) })
            val authorizedRoomIds = command.rooms.map { room -> room.roomId.raw }
            if (authorizedRoomIds.isEmpty()) {
                remoteChatCacheDao.deleteAllMessageSearchEntries(command.accountUid.raw)
                remoteChatCacheDao.deleteAllRooms(command.accountUid.raw)
            } else {
                remoteChatCacheDao.deleteUnauthorizedRoomSearchEntries(command.accountUid.raw, authorizedRoomIds)
                remoteChatCacheDao.deleteRoomsNotIn(command.accountUid.raw, authorizedRoomIds)
            }
        }
        return receipt(
            command.accountUid,
            RemoteCacheMutation.ROOMS_CACHED,
            command.rooms.size + removedRooms,
        )
    }

    override suspend fun cacheMessages(command: CacheRemoteMessagesCommand): RemoteCacheMutationReceipt {
        requireActiveAccount(command.accountUid)
        require(command.messages.all { message -> message.accountUid == command.accountUid }) {
            "Every cached message must belong to the active account scope."
        }
        val cachedAt = clock.now()
        database.withTransaction {
            val messageEntities = command.messages.map { message -> message.toEntity(cachedAt) }
            if (messageEntities.isNotEmpty()) {
                messageEntities.groupBy(RemoteMessageCacheEntity::remoteRoomId).forEach { (roomId, roomMessages) ->
                    remoteChatCacheDao.deleteMessageSearchEntries(
                        accountUid = command.accountUid.raw,
                        remoteRoomId = roomId,
                        remoteMessageIds = roomMessages.map(RemoteMessageCacheEntity::remoteMessageId),
                    )
                }
                remoteChatCacheDao.upsertMessages(messageEntities)
                remoteChatCacheDao.upsertMessageSearchEntries(
                    command.messages.mapNotNull(RemoteCachedMessage::toSearchEntity),
                )
            }
        }
        return receipt(command.accountUid, RemoteCacheMutation.MESSAGES_CACHED, command.messages.size)
    }

    override suspend fun enqueueMessage(command: EnqueueRemoteMessageCommand): RemoteCacheMutationReceipt {
        val message = command.message
        val operation = command.outboxOperation
        requireActiveAccount(message.accountUid)
        require(message.accountUid == operation.accountUid) { "Message and outbox account scopes must match." }
        require(message.roomId == operation.roomId) { "Message and outbox rooms must match." }
        require(message.messageId == operation.messageId) { "Message and outbox message IDs must match." }
        require(message.idempotencyKey == operation.idempotencyKey) {
            "Message and outbox idempotency keys must match."
        }
        val cachedAt = clock.now()
        val inserted = database.withTransaction {
            val messageInsert = remoteChatCacheDao.insertMessageIfAbsent(message.toEntity(cachedAt))
            val outboxInsert = remoteChatCacheDao.insertOutboxOperationIfAbsent(operation.toEntity())
            check((messageInsert == INSERT_IGNORED) == (outboxInsert == INSERT_IGNORED)) {
                "Message and outbox idempotency state diverged."
            }
            val wasInserted = messageInsert != INSERT_IGNORED
            if (wasInserted) {
                message.toSearchEntity()?.let { searchEntry ->
                    remoteChatCacheDao.upsertMessageSearchEntries(listOf(searchEntry))
                }
            }
            wasInserted
        }
        return receipt(
            message.accountUid,
            if (inserted) RemoteCacheMutation.MESSAGE_ENQUEUED else RemoteCacheMutation.MESSAGE_ALREADY_ENQUEUED,
            if (inserted) 2 else 0,
        )
    }

    override suspend fun updateMessageDelivery(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        messageId: RemoteMessageId,
        deliveryState: RemoteMessageDeliveryState,
        outboxState: RemoteOutboxState,
        attemptedAt: Instant,
        failureReason: String?,
    ): RemoteCacheMutationReceipt {
        requireActiveAccount(accountUid)
        val affectedRows = database.withTransaction {
            remoteChatCacheDao.updateMessageDelivery(
                accountUid = accountUid.raw,
                remoteRoomId = roomId.raw,
                remoteMessageId = messageId.raw,
                deliveryState = deliveryState.name,
                failureReason = failureReason,
                cachedAtEpochMillis = clock.now().toEpochMilli(),
            ) + remoteChatCacheDao.updateOutboxDelivery(
                accountUid = accountUid.raw,
                remoteRoomId = roomId.raw,
                remoteMessageId = messageId.raw,
                state = outboxState.name,
                lastAttemptAtEpochMillis = attemptedAt.toEpochMilli(),
                failureReason = failureReason,
            )
        }
        return receipt(accountUid, RemoteCacheMutation.DELIVERY_UPDATED, affectedRows)
    }

    override suspend fun saveSyncCursor(cursor: RemoteSyncCursor): RemoteCacheMutationReceipt {
        requireActiveAccount(cursor.accountUid)
        remoteChatCacheDao.upsertSyncCursor(cursor.toEntity())
        return receipt(cursor.accountUid, RemoteCacheMutation.CURSOR_SAVED, 1)
    }

    override suspend fun saveDraft(draft: RemoteMessageDraft): RemoteCacheMutationReceipt {
        requireActiveAccount(draft.accountUid)
        require(draft.body.isNotEmpty() && draft.body.length <= MAXIMUM_MESSAGE_BODY_LENGTH) {
            "Drafts must contain 1-$MAXIMUM_MESSAGE_BODY_LENGTH characters."
        }
        remoteChatCacheDao.upsertDraft(draft.toEntity())
        return receipt(draft.accountUid, RemoteCacheMutation.DRAFT_SAVED, 1)
    }

    override suspend fun clearDraft(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteCacheMutationReceipt {
        requireActiveAccount(accountUid)
        val affectedRows = remoteChatCacheDao.deleteDraft(accountUid.raw, roomId.raw)
        return receipt(accountUid, RemoteCacheMutation.DRAFT_CLEARED, affectedRows)
    }

    override suspend fun searchMessages(command: SearchRemoteMessagesCommand): List<RemoteMessageSearchResult> {
        requireActiveAccount(command.accountUid)
        require(command.limit in 1..MAXIMUM_MESSAGE_SEARCH_RESULTS) {
            "Remote message search limits must be bounded."
        }
        val searchTerms = normalizeSearchTerms(command.query)
        if (searchTerms.isEmpty()) return emptyList()
        val matchQuery = searchTerms.joinToString(" AND ") { term -> "$term*" }
        val rows = command.roomId?.let { roomId ->
            remoteChatCacheDao.searchRoomMessages(
                accountUid = command.accountUid.raw,
                remoteRoomId = roomId.raw,
                matchQuery = matchQuery,
                limit = command.limit,
            )
        } ?: remoteChatCacheDao.searchAllMessages(
            accountUid = command.accountUid.raw,
            matchQuery = matchQuery,
            limit = command.limit,
        )
        return rows.map { row -> row.toDomainSearchResult(searchTerms) }
    }

    override suspend fun findSyncCursor(
        collectionName: String,
        scopeId: String,
    ): RemoteSyncCursor? {
        val accountUid = requireActiveAccount()
        return remoteChatCacheDao.findSyncCursor(accountUid.raw, collectionName, scopeId)?.toDomain()
    }

    private fun requireActiveAccount(expectedAccountUid: RemoteAccountUid? = null): RemoteAccountUid {
        val activeAccountUid = sessionController.activeSession.value?.accountUid
            ?: error("No remote account session is active.")
        check(expectedAccountUid == null || expectedAccountUid == activeAccountUid) {
            "Remote cache mutation scope does not match the active account."
        }
        return activeAccountUid
    }

    private fun receipt(
        accountUid: RemoteAccountUid,
        mutation: RemoteCacheMutation,
        affectedRows: Int,
    ): RemoteCacheMutationReceipt =
        RemoteCacheMutationReceipt(
            accountUid = accountUid,
            mutation = mutation,
            affectedRows = affectedRows,
        )

    private companion object {
        const val INSERT_IGNORED = -1L
        const val MAXIMUM_MESSAGE_BODY_LENGTH = 4_000
        const val MAXIMUM_MESSAGE_SEARCH_RESULTS = 50
    }
}

private fun RemoteCachedProfile.toEntity(cachedAt: Instant): RemoteProfileCacheEntity =
    RemoteProfileCacheEntity(
        accountUid = accountUid.raw,
        profileUid = profileUid.raw,
        username = username,
        usernameNormalized = username.lowercase(),
        displayName = displayName,
        bio = bio,
        avatarUrl = avatarUrl,
        isAllowed = isAllowed,
        isOnline = isOnline,
        lastSeenAtEpochMillis = lastSeenAt?.toEpochMilli(),
        remoteUpdatedAtEpochMillis = remoteUpdatedAt.toEpochMilli(),
        cachedAtEpochMillis = cachedAt.toEpochMilli(),
    )

private fun RemoteProfileCacheEntity.toDomain(): RemoteCachedProfile =
    RemoteCachedProfile(
        accountUid = RemoteAccountUid(accountUid),
        profileUid = RemoteProfileUid(profileUid),
        username = username,
        displayName = displayName,
        bio = bio,
        avatarUrl = avatarUrl,
        isAllowed = isAllowed,
        isOnline = isOnline,
        lastSeenAt = lastSeenAtEpochMillis?.let(Instant::ofEpochMilli),
        remoteUpdatedAt = Instant.ofEpochMilli(remoteUpdatedAtEpochMillis),
    )

private fun RemoteCachedRoom.toEntity(cachedAt: Instant): RemoteRoomCacheEntity =
    RemoteRoomCacheEntity(
        accountUid = accountUid.raw,
        remoteRoomId = roomId.raw,
        roomKind = kind.name,
        directKey = directKey,
        peerUid = peerUid?.raw,
        title = title,
        avatarObjectPath = avatarObjectPath,
        unreadCount = unreadCount,
        latestMessagePreview = latestMessagePreview,
        latestMessageSenderUid = latestMessageSenderUid?.raw,
        currentMemberRole = currentMemberRole.name,
        notificationsEnabled = notificationsEnabled,
        isMuted = isMuted,
        isArchived = isArchived,
        isPinned = isPinned,
        joinedAtEpochMillis = joinedAt.toEpochMilli(),
        lastReadAtEpochMillis = lastReadAt?.toEpochMilli(),
        remoteUpdatedAtEpochMillis = remoteUpdatedAt.toEpochMilli(),
        cachedAtEpochMillis = cachedAt.toEpochMilli(),
        mutedUntilEpochMillis = mutedUntil?.toEpochMilli(),
    )

private fun RemoteRoomCacheEntity.toDomain(): RemoteCachedRoom =
    RemoteCachedRoom(
        accountUid = RemoteAccountUid(accountUid),
        roomId = RemoteRoomId(remoteRoomId),
        kind = RemoteRoomKind.valueOf(roomKind),
        directKey = directKey,
        peerUid = peerUid?.let(::RemoteProfileUid),
        title = title,
        avatarObjectPath = avatarObjectPath,
        unreadCount = unreadCount,
        latestMessagePreview = latestMessagePreview,
        latestMessageSenderUid = latestMessageSenderUid?.let(::RemoteProfileUid),
        currentMemberRole = RemoteRoomMemberRole.valueOf(currentMemberRole),
        notificationsEnabled = notificationsEnabled,
        isMuted = isMuted,
        isArchived = isArchived,
        isPinned = isPinned,
        joinedAt = Instant.ofEpochMilli(joinedAtEpochMillis),
        lastReadAt = lastReadAtEpochMillis?.let(Instant::ofEpochMilli),
        remoteUpdatedAt = Instant.ofEpochMilli(remoteUpdatedAtEpochMillis),
        mutedUntil = mutedUntilEpochMillis?.let(Instant::ofEpochMilli),
    )

private fun RemoteCachedMessage.toEntity(cachedAt: Instant): RemoteMessageCacheEntity =
    RemoteMessageCacheEntity(
        accountUid = accountUid.raw,
        remoteRoomId = roomId.raw,
        remoteMessageId = messageId.raw,
        idempotencyKey = idempotencyKey.raw,
        senderUid = senderUid.raw,
        authorKind = authorKind,
        body = body,
        attachmentsJson = encodeAttachments(attachments),
        replyToMessageId = replyToMessageId?.raw,
        editedAtEpochMillis = editedAt?.toEpochMilli(),
        deletedAtEpochMillis = deletedAt?.toEpochMilli(),
        revision = revision,
        reactionCountsJson = encodeReactionCounts(reactionCounts),
        deliveredToCount = deliveredToCount,
        readByCount = readByCount,
        deliveryState = deliveryState.name,
        clientCreatedAtEpochMillis = clientCreatedAt.toEpochMilli(),
        serverCreatedAtEpochMillis = serverCreatedAt?.toEpochMilli(),
        failureReason = failureReason,
        cachedAtEpochMillis = cachedAt.toEpochMilli(),
    )

private fun RemoteCachedMessage.toSearchEntity(): RemoteMessageSearchEntity? {
    if (deletedAt != null || body.isBlank()) return null
    return RemoteMessageSearchEntity(
        rowId = stableSearchRowId(accountUid.raw, roomId.raw, messageId.raw),
        accountUid = accountUid.raw,
        remoteRoomId = roomId.raw,
        remoteMessageId = messageId.raw,
        body = body,
    )
}

private fun RemoteMessageCacheEntity.toDomain(): RemoteCachedMessage =
    RemoteCachedMessage(
        accountUid = RemoteAccountUid(accountUid),
        roomId = RemoteRoomId(remoteRoomId),
        messageId = RemoteMessageId(remoteMessageId),
        idempotencyKey = RemoteIdempotencyKey(idempotencyKey),
        senderUid = RemoteProfileUid(senderUid),
        authorKind = authorKind,
        body = body,
        attachments = decodeAttachments(attachmentsJson),
        replyToMessageId = replyToMessageId?.let(::RemoteMessageId),
        editedAt = editedAtEpochMillis?.let(Instant::ofEpochMilli),
        deletedAt = deletedAtEpochMillis?.let(Instant::ofEpochMilli),
        revision = revision,
        reactionCounts = decodeReactionCounts(reactionCountsJson),
        deliveredToCount = deliveredToCount,
        readByCount = readByCount,
        deliveryState = RemoteMessageDeliveryState.valueOf(deliveryState),
        clientCreatedAt = Instant.ofEpochMilli(clientCreatedAtEpochMillis),
        serverCreatedAt = serverCreatedAtEpochMillis?.let(Instant::ofEpochMilli),
        failureReason = failureReason,
    )

private fun RemoteMessageOutboxOperation.toEntity(): RemoteMessageOutboxEntity =
    RemoteMessageOutboxEntity(
        accountUid = accountUid.raw,
        operationId = operationId,
        remoteRoomId = roomId.raw,
        remoteMessageId = messageId.raw,
        idempotencyKey = idempotencyKey.raw,
        senderUid = senderUid.raw,
        body = body,
        attachmentsJson = encodeAttachments(attachments),
        replyToMessageId = replyToMessageId?.raw,
        state = state.name,
        attemptCount = attemptCount,
        createdAtEpochMillis = createdAt.toEpochMilli(),
        lastAttemptAtEpochMillis = lastAttemptAt?.toEpochMilli(),
        failureReason = failureReason,
    )

private fun RemoteMessageDraft.toEntity(): RemoteMessageDraftEntity =
    RemoteMessageDraftEntity(
        accountUid = accountUid.raw,
        remoteRoomId = roomId.raw,
        body = body,
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
    )

private fun RemoteMessageDraftEntity.toDomain(): RemoteMessageDraft =
    RemoteMessageDraft(
        accountUid = RemoteAccountUid(accountUid),
        roomId = RemoteRoomId(remoteRoomId),
        body = body,
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )

private fun encodeReactionCounts(reactionCounts: Map<String, Int>): String =
    JSONObject(reactionCounts.toSortedMap()).toString()

private fun encodeAttachments(attachments: List<RemoteCachedAttachment>): String =
    JSONArray().apply {
        attachments.forEach { attachment ->
            put(
                JSONObject()
                    .put("attachmentId", attachment.attachmentId.raw)
                    .put("byteCount", attachment.byteCount)
                    .put("contentObjectPath", attachment.contentObjectPath)
                    .put("displayName", attachment.displayName)
                    .put("durationMillis", attachment.durationMillis ?: JSONObject.NULL)
                    .put("kind", attachment.kind.name)
                    .put("mimeType", attachment.mimeType)
                    .put("thumbnailObjectPath", attachment.thumbnailObjectPath ?: JSONObject.NULL),
            )
        }
    }.toString()

private fun decodeAttachments(encodedAttachments: String): List<RemoteCachedAttachment> {
    val json = JSONArray(encodedAttachments)
    require(json.length() <= 8) { "Cached remote attachments are not bounded." }
    return buildList {
        repeat(json.length()) { index ->
            val attachment = json.getJSONObject(index)
            add(
                RemoteCachedAttachment(
                    attachmentId = RemoteAttachmentId(attachment.getString("attachmentId")),
                    displayName = attachment.getString("displayName"),
                    mimeType = attachment.getString("mimeType"),
                    byteCount = attachment.getLong("byteCount"),
                    kind = RemoteAttachmentKind.valueOf(attachment.getString("kind")),
                    durationMillis = attachment.optionalLong("durationMillis"),
                    contentObjectPath = attachment.getString("contentObjectPath"),
                    thumbnailObjectPath = attachment.optionalString("thumbnailObjectPath"),
                ),
            )
        }
    }.also { attachments ->
        require(attachments.distinctBy(RemoteCachedAttachment::attachmentId).size == attachments.size) {
            "Cached remote attachments contain duplicate identifiers."
        }
    }
}

private fun JSONObject.optionalLong(fieldName: String): Long? =
    if (isNull(fieldName)) null else getLong(fieldName)

private fun JSONObject.optionalString(fieldName: String): String? =
    if (isNull(fieldName)) null else getString(fieldName)

private fun normalizeSearchTerms(query: String): List<String> =
    Normalizer.normalize(query, Normalizer.Form.NFKC)
        .lowercase()
        .take(MAXIMUM_MESSAGE_SEARCH_QUERY_LENGTH)
        .split(NON_SEARCH_TERM_CHARACTERS)
        .filter(String::isNotBlank)
        .distinct()
        .take(MAXIMUM_MESSAGE_SEARCH_TERMS)

private fun RemoteMessageSearchRow.toDomainSearchResult(searchTerms: List<String>): RemoteMessageSearchResult =
    RemoteMessageSearchResult(
        roomId = RemoteRoomId(remoteRoomId),
        messageId = RemoteMessageId(remoteMessageId),
        excerpt = buildSearchExcerpt(body, searchTerms),
    )

private fun buildSearchExcerpt(
    body: String,
    searchTerms: List<String>,
): String {
    val normalizedBody = body.lowercase()
    val firstMatch = searchTerms.map(normalizedBody::indexOf).filter { index -> index >= 0 }.minOrNull() ?: 0
    val start = (firstMatch - SEARCH_EXCERPT_CONTEXT).coerceAtLeast(0)
    val end = (start + MAXIMUM_SEARCH_EXCERPT_LENGTH).coerceAtMost(body.length)
    return buildString {
        if (start > 0) append('…')
        append(body.substring(start, end))
        if (end < body.length) append('…')
    }
}

private fun stableSearchRowId(
    accountUid: String,
    roomId: String,
    messageId: String,
): Long {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$accountUid\u0000$roomId\u0000$messageId".toByteArray())
    return (ByteBuffer.wrap(digest).long and Long.MAX_VALUE).coerceAtLeast(1L)
}

private fun decodeReactionCounts(encodedCounts: String): Map<String, Int> {
    val json = JSONObject(encodedCounts)
    return buildMap {
        json.keys().forEach { emoji ->
            val count = json.optInt(emoji, 0)
            require(emoji.isNotBlank() && emoji.length <= 16 && count > 0) {
                "Cached remote reaction state is malformed."
            }
            put(emoji, count)
        }
    }
}

private fun RemoteMessageOutboxEntity.toDomain(): RemoteMessageOutboxOperation =
    RemoteMessageOutboxOperation(
        accountUid = RemoteAccountUid(accountUid),
        operationId = operationId,
        roomId = RemoteRoomId(remoteRoomId),
        messageId = RemoteMessageId(remoteMessageId),
        idempotencyKey = RemoteIdempotencyKey(idempotencyKey),
        senderUid = RemoteProfileUid(senderUid),
        body = body,
        attachments = decodeAttachments(attachmentsJson),
        replyToMessageId = replyToMessageId?.let(::RemoteMessageId),
        state = RemoteOutboxState.valueOf(state),
        attemptCount = attemptCount,
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        lastAttemptAt = lastAttemptAtEpochMillis?.let(Instant::ofEpochMilli),
        failureReason = failureReason,
    )

private fun RemoteSyncCursor.toEntity(): RemoteSyncCursorEntity =
    RemoteSyncCursorEntity(
        accountUid = accountUid.raw,
        collectionName = collectionName,
        scopeId = scopeId,
        serverTimestampEpochMillis = serverTimestamp.toEpochMilli(),
        documentId = documentId,
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
    )

private fun RemoteSyncCursorEntity.toDomain(): RemoteSyncCursor =
    RemoteSyncCursor(
        accountUid = RemoteAccountUid(accountUid),
        collectionName = collectionName,
        scopeId = scopeId,
        serverTimestamp = Instant.ofEpochMilli(serverTimestampEpochMillis),
        documentId = documentId,
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )

private const val MAXIMUM_MESSAGE_SEARCH_QUERY_LENGTH = 100
private const val MAXIMUM_MESSAGE_SEARCH_TERMS = 6
private const val MAXIMUM_SEARCH_EXCERPT_LENGTH = 180
private const val SEARCH_EXCERPT_CONTEXT = 60
private val NON_SEARCH_TERM_CHARACTERS = Regex("[^\\p{L}\\p{N}]+")
