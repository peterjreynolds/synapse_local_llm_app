package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.supabase.SupabaseHttpMethod
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

internal class SupabasePrivateChatPollingApi(
    private val requestExecutor: SupabasePrivateChatRequestExecutor,
) : PrivateChatPollingBackend {
    override suspend fun loadPollingState(
        session: PrivateChatAuthenticatedSession,
        now: Instant,
    ): PrivateBackendPollingState =
        coroutineScope {
            val profiles = async { getTable(session, "profiles", PROFILE_COLUMNS).parseProfiles() }
            val rooms = async { getTable(session, "rooms", ROOM_COLUMNS).parseRooms() }
            val roomMembers = async { getTable(session, "room_members", ROOM_MEMBER_COLUMNS).parseRoomMembers() }
            val roomPreferences =
                async { getTable(session, "room_member_preferences", ROOM_PREFERENCE_COLUMNS).parseRoomPreferences() }
            val devices = async { getTable(session, "devices", DEVICE_COLUMNS).parseDevices() }
            val messages =
                async {
                    getExpiringTable(session, "messages", MESSAGE_COLUMNS, now).parseMessages(now)
                }
            val messageEnvelopeRows =
                async {
                    getTable(session, "message_envelopes", MESSAGE_ENVELOPE_COLUMNS).parseEnvelopeRows(
                        operation = "message envelope polling",
                        parentField = "message_id",
                        maximumCiphertextBytes = MAXIMUM_MESSAGE_CIPHERTEXT_BYTES,
                    )
                }
            val messageRevisions =
                async {
                    getExpiringTable(session, "message_revisions", MESSAGE_REVISION_COLUMNS, now)
                        .parseMessageRevisions(now)
                }
            val messageRevisionEnvelopeRows =
                async {
                    getTable(session, "message_revision_envelopes", MESSAGE_REVISION_ENVELOPE_COLUMNS).parseEnvelopeRows(
                        operation = "message revision envelope polling",
                        parentField = "revision_id",
                        maximumCiphertextBytes = MAXIMUM_MESSAGE_CIPHERTEXT_BYTES,
                    )
                }
            val replies = async { getTable(session, "message_reply_links", REPLY_COLUMNS).parseReplies() }
            val reactions =
                async {
                    getExpiringTable(session, "reactions", REACTION_COLUMNS, now).parseReactions(now)
                }
            val reactionEnvelopeRows =
                async {
                    getTable(session, "reaction_envelopes", REACTION_ENVELOPE_COLUMNS).parseEnvelopeRows(
                        operation = "reaction envelope polling",
                        parentField = "reaction_id",
                        maximumCiphertextBytes = MAXIMUM_REACTION_CIPHERTEXT_BYTES,
                    )
                }
            val roomMetadataEnvelopes =
                async {
                    getTable(session, "room_metadata_envelopes", ROOM_METADATA_ENVELOPE_COLUMNS)
                        .parseRoomMetadataEnvelopeRows()
                }
            val messageReceipts =
                async {
                    getExpiringTable(session, "message_receipts", MESSAGE_RECEIPT_COLUMNS, now)
                        .parseMessageReceipts(now)
                }
            val typing =
                async {
                    loadActivityFeed {
                        getExpiringTable(session, "typing_state", TYPING_COLUMNS, now).parseTyping(now)
                    }
                }
            val presence =
                async {
                    loadActivityFeed {
                        getExpiringTable(session, "presence_state", PRESENCE_COLUMNS, now).parsePresence(now)
                    }
                }

            val messageRecords = messages.await()
            val revisionRecords = messageRevisions.await()
            val reactionRecords = reactions.await()
            PrivateBackendPollingState(
                profiles = profiles.await(),
                rooms = rooms.await(),
                roomMembers = roomMembers.await(),
                roomPreferences = roomPreferences.await(),
                devices = devices.await(),
                messages = messageRecords,
                messageEnvelopes = joinMessageEnvelopes(messageRecords, messageEnvelopeRows.await()),
                messageRevisions = revisionRecords,
                messageRevisionEnvelopes =
                    joinMessageRevisionEnvelopes(revisionRecords, messageRevisionEnvelopeRows.await()),
                replies = replies.await(),
                reactions = reactionRecords,
                reactionEnvelopes = joinReactionEnvelopes(reactionRecords, reactionEnvelopeRows.await()),
                roomMetadataEnvelopes = roomMetadataEnvelopes.await(),
                messageReceipts = messageReceipts.await(),
                typing = typing.await(),
                presence = presence.await(),
            ).also { pollingState ->
                requireCompleteCurrentDeviceEnvelopeSet(pollingState, session)
            }
        }

    override suspend fun listRoomRecipientDevices(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
    ): List<PrivateChatRecipientDevice> =
        rpc(
            session = session,
            functionName = "list_room_recipient_devices",
            body = buildJsonObject { put("p_room_id", roomId.toString()) },
            repeatability = PrivateChatRequestRepeatability.IDEMPOTENT,
        ).requireSuccessfulRead("room recipient device listing").parseRecipientDevices()

    override suspend fun listCurrentAccountRecipientDevices(session: PrivateChatAuthenticatedSession): List<PrivateChatRecipientDevice> =
        rpc(
            session = session,
            functionName = "list_current_account_recipient_devices",
            body = buildJsonObject {},
            repeatability = PrivateChatRequestRepeatability.IDEMPOTENT,
        ).requireSuccessfulRead("current account recipient device listing").parseRecipientDevices()

    override suspend fun claimDevicePreKey(
        session: PrivateChatAuthenticatedSession,
        recipient: PrivateChatRecipientDevice,
    ) = rpc(
        session = session,
        functionName = "claim_device_prekey",
        body =
            buildJsonObject {
                put("p_target_device_id", recipient.address.transportDeviceId.toString())
            },
        repeatability = PrivateChatRequestRepeatability.NON_IDEMPOTENT,
    ).requireSuccessfulRead("device pre-key claim").parseClaimedPreKeyBundle(recipient)

    private suspend fun getExpiringTable(
        session: PrivateChatAuthenticatedSession,
        tableName: String,
        selectedColumns: String,
        now: Instant,
    ): SupabaseHttpResponse =
        getTable(
            session = session,
            tableName = tableName,
            selectedColumns = selectedColumns,
            filters = mapOf("expires_at" to "gt.$now"),
        )

    private suspend fun <Record> loadActivityFeed(loadRecords: suspend () -> List<Record>): PrivateBackendActivityFeed<Record> =
        try {
            PrivateBackendActivityFeed.Available(loadRecords())
        } catch (rejection: SupabasePrivateChatRequestRejectedException) {
            if (rejection.statusCode != HTTP_FORBIDDEN) throw rejection
            PrivateBackendActivityFeed.AccessDenied
        }

    private suspend fun getTable(
        session: PrivateChatAuthenticatedSession,
        tableName: String,
        selectedColumns: String,
        filters: Map<String, String> = emptyMap(),
    ): SupabaseHttpResponse =
        requestExecutor
            .execute(
                request =
                    SupabaseHttpRequest(
                        method = SupabaseHttpMethod.GET,
                        pathSegments = listOf("rest", "v1", tableName),
                        queryParameters =
                            filters +
                                mapOf(
                                    "select" to selectedColumns,
                                    "limit" to POLLING_QUERY_ROW_LIMIT.toString(),
                                ),
                        accessToken = session.accessTokenForRequest(),
                    ),
                repeatability = PrivateChatRequestRepeatability.IDEMPOTENT,
            ).requireSuccessfulRead("$tableName polling")

    private suspend fun rpc(
        session: PrivateChatAuthenticatedSession,
        functionName: String,
        body: kotlinx.serialization.json.JsonObject,
        repeatability: PrivateChatRequestRepeatability,
    ): SupabaseHttpResponse =
        requestExecutor.execute(
            request =
                SupabaseHttpRequest(
                    method = SupabaseHttpMethod.POST,
                    pathSegments = listOf("rest", "v1", "rpc", functionName),
                    accessToken = session.accessTokenForRequest(),
                    jsonBody = body,
                ),
            repeatability = repeatability,
        )
}

private fun SupabaseHttpResponse.requireSuccessfulRead(operation: String): SupabaseHttpResponse {
    if (statusCode !in 200..299) throw requireChatMutationRejection()
    if (jsonBody == null) throw SupabasePrivateChatResponseException("Supabase $operation response is empty")
    return this
}

private fun joinMessageEnvelopes(
    messages: List<PrivateBackendMessageRecord>,
    envelopeRows: List<SupabaseEncryptedEnvelopeRow>,
): List<PrivateBackendEnvelopeRecord> {
    val messagesById = messages.associateBy(PrivateBackendMessageRecord::messageId)
    return envelopeRows.map { row ->
        val message = messagesById[row.parentRecordId] ?: malformedPollingRelation("Message envelope parent is missing")
        PrivateBackendEnvelopeRecord(
            parentRecordId = message.messageId,
            serverRevision = 0,
            senderAccountId = message.senderAccountId,
            senderDeviceId = message.senderDeviceId,
            envelope = row.toEncryptedEnvelope(),
            createdAt = row.createdAt,
        )
    }
}

private fun joinMessageRevisionEnvelopes(
    revisions: List<PrivateBackendMessageRevisionRecord>,
    envelopeRows: List<SupabaseEncryptedEnvelopeRow>,
): List<PrivateBackendEnvelopeRecord> {
    val revisionsById = revisions.associateBy(PrivateBackendMessageRevisionRecord::revisionId)
    return envelopeRows.map { row ->
        val revision = revisionsById[row.parentRecordId] ?: malformedPollingRelation("Message revision envelope parent is missing")
        PrivateBackendEnvelopeRecord(
            parentRecordId = revision.revisionId,
            serverRevision = revision.revisionNumber,
            senderAccountId = revision.editorAccountId,
            senderDeviceId = revision.editorDeviceId,
            envelope = row.toEncryptedEnvelope(),
            createdAt = row.createdAt,
        )
    }
}

private fun joinReactionEnvelopes(
    reactions: List<PrivateBackendReactionRecord>,
    envelopeRows: List<SupabaseEncryptedEnvelopeRow>,
): List<PrivateBackendEnvelopeRecord> {
    val reactionsById = reactions.associateBy(PrivateBackendReactionRecord::reactionId)
    return envelopeRows.map { row ->
        val reaction = reactionsById[row.parentRecordId] ?: malformedPollingRelation("Reaction envelope parent is missing")
        PrivateBackendEnvelopeRecord(
            parentRecordId = reaction.reactionId,
            serverRevision = 0,
            senderAccountId = reaction.senderAccountId,
            senderDeviceId = reaction.senderDeviceId,
            envelope = row.toEncryptedEnvelope(),
            createdAt = row.createdAt,
        )
    }
}

private fun SupabaseEncryptedEnvelopeRow.toEncryptedEnvelope(): PrivateChatEncryptedEnvelope =
    try {
        PrivateChatEncryptedEnvelope(
            recipientDeviceId = recipientDeviceId,
            protocolAdapterVersion = protocolAdapterVersion,
            kind = kind,
            ciphertext = ciphertext,
        )
    } finally {
        ciphertext.fill(0)
    }

private fun requireCompleteCurrentDeviceEnvelopeSet(
    state: PrivateBackendPollingState,
    session: PrivateChatAuthenticatedSession,
) {
    val localDeviceId = session.localSignalAddress.transportDeviceId

    fun requireOneEnvelope(
        recordName: String,
        recordId: UUID,
        envelopes: List<PrivateBackendEnvelopeRecord>,
    ) {
        if (envelopes.count { record -> record.parentRecordId == recordId && record.envelope.recipientDeviceId == localDeviceId } != 1) {
            malformedPollingRelation("$recordName does not have exactly one current-device envelope")
        }
    }
    val roomIds = state.rooms.mapTo(HashSet(), PrivateBackendRoomRecord::roomId)
    if (state.roomMetadataEnvelopes.any { envelope -> envelope.parentRecordId !in roomIds }) {
        malformedPollingRelation("Room metadata envelope parent is missing")
    }
    state.rooms.forEach { room ->
        val matching =
            state.roomMetadataEnvelopes.filter { envelope ->
                envelope.parentRecordId == room.roomId && envelope.envelope.recipientDeviceId == localDeviceId
            }
        if (matching.size > 1) {
            malformedPollingRelation("Room metadata has more than one current-device envelope")
        }
        matching.singleOrNull()?.let { envelope ->
            if (envelope.serverRevision != room.metadataRevision) {
                malformedPollingRelation("Room metadata envelope revision is stale")
            }
        }
    }
    state.messages.forEach { message ->
        if (message.currentRevision == 0) {
            requireOneEnvelope("Message", message.messageId, state.messageEnvelopes)
        } else {
            val revision =
                state.messageRevisions.singleOrNull { candidate ->
                    candidate.messageId == message.messageId && candidate.revisionNumber == message.currentRevision
                } ?: malformedPollingRelation("Current message revision is missing")
            requireOneEnvelope("Message revision", revision.revisionId, state.messageRevisionEnvelopes)
        }
    }
    state.reactions.forEach { reaction ->
        requireOneEnvelope("Reaction", reaction.reactionId, state.reactionEnvelopes)
    }
}

private fun malformedPollingRelation(message: String): Nothing = throw SupabasePrivateChatResponseException(message)

private const val POLLING_QUERY_ROW_LIMIT = 2_001
private const val HTTP_FORBIDDEN = 403
private const val MAXIMUM_MESSAGE_CIPHERTEXT_BYTES = 262_144
private const val MAXIMUM_REACTION_CIPHERTEXT_BYTES = 16_384
private const val PROFILE_COLUMNS =
    "user_id,display_name,presence_sharing_enabled,typing_indicators_enabled,read_receipts_enabled"
private const val ROOM_COLUMNS =
    "id,owner_user_id,creation_client_mutation_id,room_kind,retention_seconds,membership_epoch," +
        "metadata_revision,metadata_updated_at,created_at"
private const val ROOM_MEMBER_COLUMNS = "room_id,user_id,member_role,joined_at"
private const val ROOM_PREFERENCE_COLUMNS = "room_id,archive_state,pin_state,mute_state,muted_until,updated_at"
private const val DEVICE_COLUMNS = "id,user_id,protocol_adapter_version,signal_device_id"
private const val MESSAGE_COLUMNS =
    "id,room_id,sender_user_id,sender_device_id,client_message_id,membership_epoch,current_revision,created_at,expires_at"
private const val MESSAGE_ENVELOPE_COLUMNS =
    "message_id,recipient_device_id,protocol_adapter_version,signal_message_type,ciphertext,created_at"
private const val MESSAGE_REVISION_COLUMNS =
    "id,message_id,editor_user_id,editor_device_id,revision_number,membership_epoch,created_at,expires_at"
private const val MESSAGE_REVISION_ENVELOPE_COLUMNS =
    "revision_id,recipient_device_id,protocol_adapter_version,signal_message_type,ciphertext,created_at"
private const val REPLY_COLUMNS = "message_id,replied_to_message_id"
private const val REACTION_COLUMNS =
    "id,message_id,sender_user_id,sender_device_id,client_reaction_id,membership_epoch,created_at,expires_at"
private const val REACTION_ENVELOPE_COLUMNS =
    "reaction_id,recipient_device_id,protocol_adapter_version,signal_message_type,ciphertext,created_at"
private const val ROOM_METADATA_ENVELOPE_COLUMNS =
    "room_id,metadata_revision,sender_user_id,sender_device_id,recipient_device_id," +
        "protocol_adapter_version,signal_message_type,ciphertext,created_at"
private const val MESSAGE_RECEIPT_COLUMNS = "message_id,recipient_device_id,receipt_kind,created_at,expires_at"
private const val TYPING_COLUMNS = "room_id,device_id,created_at,expires_at"
private const val PRESENCE_COLUMNS = "device_id,created_at,expires_at"
