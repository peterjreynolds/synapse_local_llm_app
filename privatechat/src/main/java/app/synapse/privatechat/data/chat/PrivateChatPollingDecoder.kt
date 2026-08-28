package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.local.DeviceLocalContentEnvelopeUnavailableException
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageText
import app.synapse.privatechat.domain.chat.PrivateReactionCode
import app.synapse.privatechat.domain.chat.PrivateRoomMetadataState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class PrivateResolvedRoom(
    val record: PrivateBackendRoomRecord,
    val title: String,
    val metadataState: app.synapse.privatechat.domain.chat.PrivateRoomMetadataState,
)

internal data class PrivateResolvedMessage(
    val record: PrivateBackendMessageRecord,
    val body: PrivateMessageText,
    val replyToMessageId: PrivateMessageId?,
    val domainRevision: Long,
    val editedAt: Instant?,
)

internal data class PrivateResolvedReaction(
    val record: PrivateBackendReactionRecord,
    val reaction: PrivateReactionCode,
)

internal data class PrivateResolvedPollingState(
    val session: PrivateChatAuthenticatedSession,
    val backend: PrivateBackendPollingState,
    val rooms: Map<UUID, PrivateResolvedRoom>,
    val messages: Map<UUID, PrivateResolvedMessage>,
    val reactions: Map<UUID, PrivateResolvedReaction>,
    val loadedAt: Instant,
    val recoveredMutationIds: Set<UUID> = emptySet(),
)

/** Serializes Signal envelope consumption while allowing every observer to reuse the durable cache. */
internal class PrivateChatPollingRepository(
    private val backend: PrivateChatPollingBackend,
    private val envelopeCipher: PrivateChatEnvelopeCipher,
    private val payloadCache: PrivateDecryptedPayloadCacheRepository,
    private val pendingMutationRecovery: PrivatePendingOutboundMutationRecovery,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val pollingMutex = Mutex()
    private var recentState: PrivateResolvedPollingState? = null

    suspend fun load(session: PrivateChatAuthenticatedSession): PrivateResolvedPollingState =
        pollingMutex.withLock {
            val now = clock.instant()
            if (!session.isUsableAt(now)) {
                recentState = null
                pendingMutationRecovery.clearRecoveredMutationIds()
                payloadCache.clearForSessionInvalidation()
                throw SupabasePrivateChatResponseException("Authenticated chat session is unavailable")
            }
            val newlyRecoveredMutationIds = pendingMutationRecovery.recoverPendingMutations(session)
            val recoveredMutationIds =
                newlyRecoveredMutationIds + pendingMutationRecovery.retainedRecoveredMutationIds(session)
            if (newlyRecoveredMutationIds.isNotEmpty()) recentState = null
            recentState?.let { cached ->
                if (
                    cached.session.hasSameAuthenticatedDeviceAs(session) &&
                    !now.isBefore(cached.loadedAt) &&
                    now.isBefore(cached.loadedAt.plusMillis(MAXIMUM_RESOLVED_STATE_REUSE_MILLIS))
                ) {
                    return@withLock cached.copy(recoveredMutationIds = recoveredMutationIds)
                }
            }
            val backendState = backend.loadPollingState(session, now)
            PrivateChatPollingDecoder(envelopeCipher, payloadCache)
                .decode(session, backendState, now)
                .copy(recoveredMutationIds = recoveredMutationIds)
                .also { resolved -> recentState = resolved }
        }

    suspend fun clearForSessionInvalidation() {
        pollingMutex.withLock {
            recentState = null
            pendingMutationRecovery.clearRecoveredMutationIds()
            payloadCache.clearForSessionInvalidation()
        }
    }

    suspend fun invalidateRecentState() {
        pollingMutex.withLock { recentState = null }
    }
}

internal class PrivateChatPollingDecoder(
    private val envelopeCipher: PrivateChatEnvelopeCipher,
    private val payloadCache: PrivateDecryptedPayloadCacheRepository,
) {
    fun decode(
        session: PrivateChatAuthenticatedSession,
        state: PrivateBackendPollingState,
        now: Instant,
    ): PrivateResolvedPollingState {
        val graph = PrivatePollingGraph.validate(session, state)
        val authoritativePayloads = ArrayList<PrivateAuthoritativeEncryptedPayload>()
        val rooms =
            state.rooms.associate { room ->
                if (room.metadataRevision == 1 && room.creationClientMutationId == null) {
                    return@associate room.roomId to
                        PrivateResolvedRoom(
                            record = room,
                            title = PENDING_ROOM_METADATA_TITLE,
                            metadataState = app.synapse.privatechat.domain.chat.PrivateRoomMetadataState.PENDING,
                        )
                }
                val envelope = graph.roomMetadataEnvelopeByRoom[room.roomId]
                if (envelope == null) {
                    return@associate room.roomId to
                        PrivateResolvedRoom(
                            record = room,
                            title = PENDING_ROOM_METADATA_TITLE,
                            metadataState = app.synapse.privatechat.domain.chat.PrivateRoomMetadataState.PENDING,
                        )
                }
                val descriptor =
                    envelope.authoritativeDescriptor(
                        kind = PrivateCachedPayloadKind.ROOM_METADATA,
                        recordId = room.roomId,
                        revision = room.metadataRevision,
                        roomId = room.roomId,
                        parentMessageId = null,
                        expiresAt = MAXIMUM_ROOM_METADATA_CACHE_EXPIRY,
                    )
                val payload =
                    try {
                        resolvePayload(session, graph, envelope, descriptor, now) { decoded ->
                            validateRoomMetadata(decoded, room, envelope)
                        }
                    } catch (_: DeviceLocalContentEnvelopeUnavailableException) {
                        return@associate room.roomId to
                            PrivateResolvedRoom(
                                record = room,
                                title = PENDING_ROOM_METADATA_TITLE,
                                metadataState = PrivateRoomMetadataState.UNAVAILABLE_ON_DEVICE,
                            )
                    }
                authoritativePayloads += descriptor
                room.roomId to
                    PrivateResolvedRoom(
                        record = room,
                        title = payload.requireRoomTitle(),
                        metadataState = app.synapse.privatechat.domain.chat.PrivateRoomMetadataState.AVAILABLE,
                    )
            }
        val messages =
            state.messages.associate { message ->
                val reply = graph.replyByMessage[message.messageId]
                val resolved =
                    if (message.currentRevision == 0) {
                        decodeInitialMessage(session, graph, message, reply, authoritativePayloads, now)
                    } else {
                        decodeMessageRevision(session, graph, message, reply, authoritativePayloads, now)
                    }
                message.messageId to resolved
            }
        val reactions =
            state.reactions.associate { reaction ->
                val parentMessage =
                    messages[reaction.messageId]
                        ?: malformedPollingGraph("Reaction parent message is unavailable")
                val envelope = graph.reactionEnvelopeByReaction.getValue(reaction.reactionId)
                val descriptor =
                    envelope.authoritativeDescriptor(
                        kind = PrivateCachedPayloadKind.REACTION,
                        recordId = reaction.reactionId,
                        revision = 0,
                        roomId = parentMessage.record.roomId,
                        parentMessageId = reaction.messageId,
                        expiresAt = reaction.expiresAt,
                    )
                val payload =
                    resolvePayload(session, graph, envelope, descriptor, now) { decoded ->
                        val reactionPayload =
                            decoded as? PrivateChatPlaintextPayload.Reaction
                                ?: malformedPollingGraph("Reaction envelope contains the wrong payload kind")
                        if (
                            reactionPayload.accountId.canonical != reaction.senderAccountId.toString() ||
                            reactionPayload.roomId.canonical != parentMessage.record.roomId.toString() ||
                            reactionPayload.mutationId.canonical != reaction.clientMutationId.toString() ||
                            reactionPayload.messageId.canonical != reaction.messageId.toString()
                        ) {
                            malformedPollingGraph("Encrypted reaction context does not match its server record")
                        }
                    } as PrivateChatPlaintextPayload.Reaction
                authoritativePayloads += descriptor
                reaction.reactionId to PrivateResolvedReaction(reaction, payload.reaction)
            }
        payloadCache.reconcileAuthoritativePayloads(session, authoritativePayloads, now)
        val authoritativeEnvelopes =
            state.roomMetadataEnvelopes +
                state.messageEnvelopes +
                state.messageRevisionEnvelopes +
                state.reactionEnvelopes
        val pendingEnvelopes =
            envelopeCipher.listPendingOutboundMutations().flatMap { pending ->
                PrivateEncryptedMutationCodec.decode(pending.opaqueRequest).envelopes
            }
        envelopeCipher.reconcileLocalEnvelopeKeys(
            authoritativeEnvelopes = authoritativeEnvelopes.map(PrivateBackendEnvelopeRecord::envelope),
            pendingEnvelopes = pendingEnvelopes,
            observedAt = now,
        )
        return PrivateResolvedPollingState(
            session = session,
            backend = state,
            rooms = rooms,
            messages = messages,
            reactions = reactions,
            loadedAt = now,
        )
    }

    private fun decodeInitialMessage(
        session: PrivateChatAuthenticatedSession,
        graph: PrivatePollingGraph,
        message: PrivateBackendMessageRecord,
        reply: PrivateBackendReplyRecord?,
        authoritativePayloads: MutableCollection<PrivateAuthoritativeEncryptedPayload>,
        now: Instant,
    ): PrivateResolvedMessage {
        val envelope = graph.messageEnvelopeByMessage.getValue(message.messageId)
        val descriptor =
            envelope.authoritativeDescriptor(
                kind = PrivateCachedPayloadKind.MESSAGE,
                recordId = message.messageId,
                revision = 0,
                roomId = message.roomId,
                parentMessageId = message.messageId,
                expiresAt = message.expiresAt,
            )
        val payload =
            resolvePayload(session, graph, envelope, descriptor, now) { decoded ->
                val messagePayload =
                    decoded as? PrivateChatPlaintextPayload.Message
                        ?: malformedPollingGraph("Message envelope contains the wrong payload kind")
                if (
                    messagePayload.accountId.canonical != message.senderAccountId.toString() ||
                    messagePayload.roomId.canonical != message.roomId.toString() ||
                    messagePayload.mutationId.canonical != message.clientMutationId.toString() ||
                    messagePayload.replyToMessageId?.canonical != reply?.repliedToMessageId?.toString()
                ) {
                    malformedPollingGraph("Encrypted message context does not match its server record")
                }
            } as PrivateChatPlaintextPayload.Message
        authoritativePayloads += descriptor
        return PrivateResolvedMessage(
            record = message,
            body = payload.body,
            replyToMessageId = payload.replyToMessageId,
            domainRevision = 1L,
            editedAt = null,
        )
    }

    private fun decodeMessageRevision(
        session: PrivateChatAuthenticatedSession,
        graph: PrivatePollingGraph,
        message: PrivateBackendMessageRecord,
        reply: PrivateBackendReplyRecord?,
        authoritativePayloads: MutableCollection<PrivateAuthoritativeEncryptedPayload>,
        now: Instant,
    ): PrivateResolvedMessage {
        val revision = graph.currentRevisionByMessage.getValue(message.messageId)
        val envelope = graph.revisionEnvelopeByRevision.getValue(revision.revisionId)
        val descriptor =
            envelope.authoritativeDescriptor(
                kind = PrivateCachedPayloadKind.MESSAGE_REVISION,
                recordId = revision.revisionId,
                revision = revision.revisionNumber,
                roomId = message.roomId,
                parentMessageId = message.messageId,
                expiresAt = revision.expiresAt,
            )
        val payload =
            resolvePayload(session, graph, envelope, descriptor, now) { decoded ->
                val revisionPayload =
                    decoded as? PrivateChatPlaintextPayload.MessageRevision
                        ?: malformedPollingGraph("Message revision envelope contains the wrong payload kind")
                if (
                    revision.editorAccountId != message.senderAccountId ||
                    revisionPayload.accountId.canonical != revision.editorAccountId.toString() ||
                    revisionPayload.roomId.canonical != message.roomId.toString() ||
                    revisionPayload.messageId.canonical != message.messageId.toString() ||
                    revisionPayload.revision != revision.revisionNumber + 1
                ) {
                    malformedPollingGraph("Encrypted message revision context does not match its server record")
                }
            } as PrivateChatPlaintextPayload.MessageRevision
        authoritativePayloads += descriptor
        return PrivateResolvedMessage(
            record = message,
            body = payload.body,
            replyToMessageId = reply?.repliedToMessageId?.let { id -> PrivateMessageId(id.toString()) },
            domainRevision = payload.revision.toLong(),
            editedAt = revision.editedAt,
        )
    }

    private fun resolvePayload(
        session: PrivateChatAuthenticatedSession,
        graph: PrivatePollingGraph,
        envelopeRecord: PrivateBackendEnvelopeRecord,
        descriptor: PrivateAuthoritativeEncryptedPayload,
        now: Instant,
        validate: (PrivateChatPlaintextPayload) -> Unit,
    ): PrivateChatPlaintextPayload {
        payloadCache.loadPlaintext(session, descriptor, now)?.let { cachedPlaintext ->
            try {
                return PrivateChatPayloadCodec.decode(cachedPlaintext).also(validate)
            } finally {
                cachedPlaintext.fill(0)
            }
        }
        val senderAddress = graph.deviceById.getValue(envelopeRecord.senderDeviceId).address
        return envelopeCipher.decryptForCurrentDeviceWithDurableCommit(
            session = session,
            senderAddress = senderAddress,
            envelope = envelopeRecord.envelope,
        ) { plaintext ->
            val decoded = PrivateChatPayloadCodec.decode(plaintext).also(validate)
            payloadCache.persistPlaintext(session, descriptor, plaintext, now)
            decoded
        }
    }
}

private class PrivatePollingGraph private constructor(
    val deviceById: Map<UUID, PrivateBackendDeviceRecord>,
    val replyByMessage: Map<UUID, PrivateBackendReplyRecord>,
    val currentRevisionByMessage: Map<UUID, PrivateBackendMessageRevisionRecord>,
    val roomMetadataEnvelopeByRoom: Map<UUID, PrivateBackendEnvelopeRecord>,
    val messageEnvelopeByMessage: Map<UUID, PrivateBackendEnvelopeRecord>,
    val revisionEnvelopeByRevision: Map<UUID, PrivateBackendEnvelopeRecord>,
    val reactionEnvelopeByReaction: Map<UUID, PrivateBackendEnvelopeRecord>,
) {
    companion object {
        fun validate(
            session: PrivateChatAuthenticatedSession,
            state: PrivateBackendPollingState,
        ): PrivatePollingGraph {
            val profiles = state.profiles.requireUnique("profile", PrivateBackendProfileRecord::accountId)
            val rooms = state.rooms.requireUnique("room", PrivateBackendRoomRecord::roomId)
            val devices = state.devices.requireUnique("device") { device -> device.address.transportDeviceId }
            if (profiles[UUID.fromString(session.accountId.canonical)] == null) {
                malformedPollingGraph("Current account profile is unavailable")
            }
            if (devices[session.localSignalAddress.transportDeviceId]?.address != session.localSignalAddress) {
                malformedPollingGraph("Current authenticated device is unavailable")
            }
            val membershipKeys = HashSet<Pair<UUID, UUID>>()
            val membersByRoom = state.roomMembers.groupBy(PrivateBackendRoomMemberRecord::roomId)
            state.roomMembers.forEach { member ->
                if (!membershipKeys.add(member.roomId to member.accountId)) {
                    malformedPollingGraph("Room membership rows are duplicated")
                }
                if (rooms[member.roomId] == null || profiles[member.accountId] == null) {
                    malformedPollingGraph("Room membership references an unavailable record")
                }
            }
            rooms.values.forEach { room ->
                val members = membersByRoom[room.roomId].orEmpty()
                if (
                    members.none { member -> member.accountId.toString() == session.accountId.canonical } ||
                    members.count { member -> member.role.name == "OWNER" } != 1 ||
                    members.singleOrNull { member -> member.role.name == "OWNER" }?.accountId != room.ownerAccountId
                ) {
                    malformedPollingGraph("Room membership is incomplete")
                }
            }
            state.roomPreferences
                .requireUnique("room preference", PrivateBackendRoomPreferenceRecord::roomId)
                .keys
                .forEach { roomId ->
                    if (rooms[roomId] == null) malformedPollingGraph("Room preference references an unavailable room")
                }
            devices.values.forEach { device ->
                if (profiles[device.address.accountId] == null) {
                    malformedPollingGraph("Device references an unavailable profile")
                }
            }
            val messages = state.messages.requireUnique("message", PrivateBackendMessageRecord::messageId)
            messages.values.forEach { message ->
                if (
                    rooms[message.roomId] == null ||
                    profiles[message.senderAccountId] == null ||
                    devices[message.senderDeviceId]?.address?.accountId != message.senderAccountId
                ) {
                    malformedPollingGraph("Message references an unavailable server record")
                }
            }
            val replies = state.replies.requireUnique("reply", PrivateBackendReplyRecord::messageId)
            replies.values.forEach { reply ->
                val message = messages[reply.messageId]
                val target = messages[reply.repliedToMessageId]
                if (message == null || target == null || message.roomId != target.roomId) {
                    malformedPollingGraph("Reply relation is invalid")
                }
            }
            val revisions = state.messageRevisions.requireUnique("message revision", PrivateBackendMessageRevisionRecord::revisionId)
            val currentRevisionByMessage = LinkedHashMap<UUID, PrivateBackendMessageRevisionRecord>()
            revisions.values.forEach { revision ->
                val message =
                    messages[revision.messageId]
                        ?: malformedPollingGraph("Message revision parent is unavailable")
                if (
                    revision.revisionNumber != message.currentRevision ||
                    revision.expiresAt != message.expiresAt ||
                    revision.editedAt.isBefore(message.createdAt) ||
                    devices[revision.editorDeviceId]?.address?.accountId != revision.editorAccountId ||
                    currentRevisionByMessage.put(revision.messageId, revision) != null
                ) {
                    malformedPollingGraph("Current message revision is inconsistent")
                }
            }
            messages.values.forEach { message ->
                if ((message.currentRevision > 0) != (currentRevisionByMessage[message.messageId] != null)) {
                    malformedPollingGraph("Message revision relation is incomplete")
                }
            }
            val reactions = state.reactions.requireUnique("reaction", PrivateBackendReactionRecord::reactionId)
            reactions.values.forEach { reaction ->
                if (
                    messages[reaction.messageId] == null ||
                    devices[reaction.senderDeviceId]?.address?.accountId != reaction.senderAccountId
                ) {
                    malformedPollingGraph("Reaction references an unavailable server record")
                }
            }
            state.messageReceipts.forEach { receipt ->
                if (messages[receipt.messageId] == null || devices[receipt.recipientDeviceId] == null) {
                    malformedPollingGraph("Message receipt references an unavailable server record")
                }
            }
            state.typing.records.forEach { typing ->
                if (rooms[typing.roomId] == null || devices[typing.deviceId] == null) {
                    malformedPollingGraph("Typing state references an unavailable server record")
                }
            }
            state.presence.records.forEach { presence ->
                if (devices[presence.deviceId] == null) {
                    malformedPollingGraph("Presence state references an unavailable device")
                }
            }
            val localDeviceId = session.localSignalAddress.transportDeviceId
            return PrivatePollingGraph(
                deviceById = devices,
                replyByMessage = replies,
                currentRevisionByMessage = currentRevisionByMessage,
                roomMetadataEnvelopeByRoom =
                    state.roomMetadataEnvelopes.requireLocalEnvelopeMap(
                        "room metadata",
                        localDeviceId,
                        PrivateBackendEnvelopeRecord::parentRecordId,
                    ),
                messageEnvelopeByMessage =
                    state.messageEnvelopes.requireLocalEnvelopeMap(
                        "message",
                        localDeviceId,
                        PrivateBackendEnvelopeRecord::parentRecordId,
                    ),
                revisionEnvelopeByRevision =
                    state.messageRevisionEnvelopes.requireLocalEnvelopeMap(
                        "message revision",
                        localDeviceId,
                        PrivateBackendEnvelopeRecord::parentRecordId,
                    ),
                reactionEnvelopeByReaction =
                    state.reactionEnvelopes.requireLocalEnvelopeMap(
                        "reaction",
                        localDeviceId,
                        PrivateBackendEnvelopeRecord::parentRecordId,
                    ),
            )
        }
    }
}

private fun PrivateBackendEnvelopeRecord.authoritativeDescriptor(
    kind: PrivateCachedPayloadKind,
    recordId: UUID,
    revision: Int,
    roomId: UUID,
    parentMessageId: UUID?,
    expiresAt: Instant,
): PrivateAuthoritativeEncryptedPayload {
    val ciphertext = envelope.ciphertextCopy()
    return try {
        PrivateAuthoritativeEncryptedPayload(
            key = PrivateCachedPayloadKey(kind, recordId, revision),
            roomId = roomId,
            parentMessageId = parentMessageId,
            fingerprint = PrivateEncryptedPayloadFingerprint.fromCiphertext(ciphertext),
            expiresAt = expiresAt,
        )
    } finally {
        ciphertext.fill(0)
    }
}

internal fun validateRoomMetadata(
    payload: PrivateChatPlaintextPayload,
    room: PrivateBackendRoomRecord,
    envelope: PrivateBackendEnvelopeRecord,
) {
    when (payload) {
        is PrivateChatPlaintextPayload.CreatedRoomMetadata ->
            if (
                room.metadataRevision != 1 ||
                payload.accountId.canonical != room.ownerAccountId.toString() ||
                payload.accountId.canonical != envelope.senderAccountId.toString() ||
                payload.roomId.canonical != room.roomId.toString() ||
                room.creationClientMutationId == null ||
                payload.mutationId.canonical != room.creationClientMutationId.toString() ||
                payload.roomKind != room.kind ||
                payload.retention != room.retention
            ) {
                malformedPollingGraph("Encrypted room creation metadata does not match its server record")
            }

        is PrivateChatPlaintextPayload.UpdatedRoomMetadata ->
            if (
                room.metadataRevision <= 1 ||
                payload.accountId.canonical != envelope.senderAccountId.toString() ||
                payload.roomId.canonical != room.roomId.toString() ||
                payload.expectedMetadataRevision != room.metadataRevision - 1
            ) {
                malformedPollingGraph("Encrypted room metadata revision does not match its server record")
            }

        else -> malformedPollingGraph("Room metadata envelope contains the wrong payload kind")
    }
}

private fun PrivateChatPlaintextPayload.requireRoomTitle(): String =
    when (this) {
        is PrivateChatPlaintextPayload.CreatedRoomMetadata -> title
        is PrivateChatPlaintextPayload.UpdatedRoomMetadata -> title
        else -> malformedPollingGraph("Room metadata envelope contains the wrong payload kind")
    }

private fun <Record, Key> List<Record>.requireUnique(
    recordName: String,
    key: (Record) -> Key,
): Map<Key, Record> {
    val indexed = associateBy(key)
    if (indexed.size != size) malformedPollingGraph("$recordName rows are duplicated")
    return indexed
}

private fun <Key> List<PrivateBackendEnvelopeRecord>.requireLocalEnvelopeMap(
    recordName: String,
    localDeviceId: UUID,
    key: (PrivateBackendEnvelopeRecord) -> Key,
): Map<Key, PrivateBackendEnvelopeRecord> {
    if (any { envelope -> envelope.envelope.recipientDeviceId != localDeviceId }) {
        malformedPollingGraph("$recordName polling returned another device's envelope")
    }
    return requireUnique("$recordName envelope", key)
}

private fun malformedPollingGraph(message: String): Nothing = throw SupabasePrivateChatResponseException(message)

private val MAXIMUM_ROOM_METADATA_CACHE_EXPIRY: Instant =
    Instant.ofEpochSecond(MAXIMUM_CACHE_EXPIRY_EPOCH_SECONDS)
private const val MAXIMUM_RESOLVED_STATE_REUSE_MILLIS = 1_000L
private const val PENDING_ROOM_METADATA_TITLE = "Encrypted conversation"
