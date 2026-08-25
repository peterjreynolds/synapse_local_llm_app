package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.supabase.SupabaseHttpMethod
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

internal class SupabasePrivateRoomMutationApi(
    private val transport: SupabasePrivateChatMutationTransport,
) {
    suspend fun updateRoomRetention(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        retention: PrivateMessageRetention,
    ): PrivateBackendRoomRetentionReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "update_room_retention",
                    body =
                        buildJsonObject {
                            put("p_room_id", roomId.toString())
                            put("p_client_mutation_id", clientMutationId.toString())
                            put("p_retention_seconds", retention.durationSeconds)
                        },
                ).requireChatMutationSuccess("room retention update")
        receipt.requireExactChatFields("room_id", "retention_seconds", "updated_at")
        val returnedRoomId = receipt.requireChatUuid("room_id")
        val returnedSeconds = receipt.requireChatInt("retention_seconds", 1..604_800)
        if (returnedRoomId != roomId || returnedSeconds != retention.durationSeconds) {
            throw SupabasePrivateChatResponseException("Supabase room retention receipt is inconsistent")
        }
        return PrivateBackendRoomRetentionReceipt(
            roomId = returnedRoomId,
            retention = retention,
            updatedAt = receipt.requireChatInstant("updated_at"),
        )
    }

    suspend fun updateRoomPreferences(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        archiveState: PrivateRoomArchiveState,
        pinState: PrivateRoomPinState,
        muteState: PrivateRoomMuteState,
    ): PrivateBackendRoomPreferenceReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "set_room_preferences",
                    body =
                        buildJsonObject {
                            put("p_room_id", roomId.toString())
                            put("p_client_mutation_id", clientMutationId.toString())
                            put("p_archive_state", archiveState.name)
                            put("p_pin_state", pinState.name)
                            put(
                                "p_mute_state",
                                when (muteState) {
                                    PrivateRoomMuteState.AUDIBLE -> "UNMUTED"
                                    PrivateRoomMuteState.MUTED -> "MUTED_FOREVER"
                                },
                            )
                            put("p_muted_until", kotlinx.serialization.json.JsonNull)
                        },
                ).requireChatMutationSuccess("room preference update")
        val parsed = receipt.parseRoomPreferenceRecord()
        if (
            parsed.roomId != roomId ||
            parsed.archiveState != archiveState ||
            parsed.pinState != pinState ||
            parsed.muteState != muteState
        ) {
            throw SupabasePrivateChatResponseException("Supabase room preference receipt is inconsistent")
        }
        return PrivateBackendRoomPreferenceReceipt(
            roomId = parsed.roomId,
            archiveState = parsed.archiveState,
            pinState = parsed.pinState,
            muteState = parsed.muteState,
            updatedAt = parsed.updatedAt,
        )
    }

    suspend fun acknowledgeRoomRead(
        session: PrivateChatAuthenticatedSession,
        messageIds: List<UUID>,
    ) {
        if (messageIds.isEmpty()) return
        if (messageIds.distinct().size != messageIds.size || messageIds.size > MAXIMUM_READ_ACKNOWLEDGEMENTS) {
            throw IllegalArgumentException("Read acknowledgement message IDs are invalid")
        }
        messageIds.chunked(READ_ACKNOWLEDGEMENT_CHUNK_SIZE).forEach { chunk ->
            val body =
                buildJsonArray {
                    chunk.forEach { messageId ->
                        add(
                            buildJsonObject {
                                put("message_id", messageId.toString())
                                put("recipient_device_id", session.localSignalAddress.transportDeviceId.toString())
                                put("receipt_kind", "READ")
                                put("membership_epoch", 1)
                                put("expires_at", MAXIMUM_SERVER_TIMESTAMP.toString())
                            },
                        )
                    }
                }
            transport
                .tableMutation(
                    session = session,
                    method = SupabaseHttpMethod.POST,
                    tableName = "message_receipts",
                    body = body,
                    queryParameters =
                        mapOf(
                            "on_conflict" to "message_id,recipient_device_id,receipt_kind",
                        ),
                    preferHeader = "resolution=merge-duplicates,return=minimal",
                ).requireAcceptedChatMutation("read acknowledgement")
            requirePersistedReadReceipts(session, chunk)
        }
    }

    suspend fun publishTyping(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        active: Boolean,
    ): PrivateBackendTypingRecord? {
        if (!active) {
            transport
                .tableMutation(
                    session = session,
                    method = SupabaseHttpMethod.DELETE,
                    tableName = "typing_state",
                    queryParameters =
                        mapOf(
                            "room_id" to "eq.$roomId",
                            "device_id" to "eq.${session.localSignalAddress.transportDeviceId}",
                        ),
                ).requireAcceptedChatMutation("typing state removal")
            val remaining = loadCurrentTypingState(session, roomId)
            if (remaining.isNotEmpty()) {
                throw SupabasePrivateChatResponseException("Supabase did not remove the current typing state")
            }
            return null
        }
        val response =
            transport
                .tableMutation(
                    session = session,
                    method = SupabaseHttpMethod.POST,
                    tableName = "typing_state",
                    body =
                        buildJsonObject {
                            put("room_id", roomId.toString())
                            put("device_id", session.localSignalAddress.transportDeviceId.toString())
                            put("membership_epoch", 1)
                            put("expires_at", MAXIMUM_SERVER_TIMESTAMP.toString())
                        },
                    queryParameters =
                        mapOf(
                            "on_conflict" to "room_id,device_id",
                            "select" to TYPING_COLUMNS,
                        ),
                    preferHeader = "resolution=merge-duplicates,return=representation",
                ).requireAcceptedChatMutation("typing state publication")
        val typingRows = response.parseTyping(now = null)
        val persisted =
            typingRows.singleOrNull()
                ?: throw SupabasePrivateChatResponseException("Supabase typing publication receipt is malformed")
        if (
            persisted.roomId != roomId ||
            persisted.deviceId != session.localSignalAddress.transportDeviceId
        ) {
            throw SupabasePrivateChatResponseException("Supabase typing publication receipt is inconsistent")
        }
        return persisted
    }

    private suspend fun requirePersistedReadReceipts(
        session: PrivateChatAuthenticatedSession,
        messageIds: List<UUID>,
    ) {
        val response =
            transport
                .tableMutation(
                    session = session,
                    method = SupabaseHttpMethod.GET,
                    tableName = "message_receipts",
                    queryParameters =
                        mapOf(
                            "select" to MESSAGE_RECEIPT_COLUMNS,
                            "message_id" to "in.(${messageIds.joinToString()})",
                            "recipient_device_id" to "eq.${session.localSignalAddress.transportDeviceId}",
                            "receipt_kind" to "eq.READ",
                        ),
                ).requireAcceptedChatMutation("read acknowledgement verification")
        val persistedIds = response.parseMessageReceipts(now = null).mapTo(HashSet()) { receipt -> receipt.messageId }
        if (!persistedIds.containsAll(messageIds)) {
            throw SupabasePrivateChatResponseException("Supabase did not persist every read acknowledgement")
        }
    }

    private suspend fun loadCurrentTypingState(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
    ): List<PrivateBackendTypingRecord> =
        transport
            .tableMutation(
                session = session,
                method = SupabaseHttpMethod.GET,
                tableName = "typing_state",
                queryParameters =
                    mapOf(
                        "select" to TYPING_COLUMNS,
                        "room_id" to "eq.$roomId",
                        "device_id" to "eq.${session.localSignalAddress.transportDeviceId}",
                    ),
            ).requireAcceptedChatMutation("typing state verification")
            .parseTyping(now = null)
}

private const val MAXIMUM_READ_ACKNOWLEDGEMENTS = 2_000
private const val READ_ACKNOWLEDGEMENT_CHUNK_SIZE = 50
private const val MESSAGE_RECEIPT_COLUMNS = "message_id,recipient_device_id,receipt_kind,created_at,expires_at"
private const val TYPING_COLUMNS = "room_id,device_id,created_at,expires_at"
private val MAXIMUM_SERVER_TIMESTAMP = Instant.ofEpochSecond(MAXIMUM_CACHE_EXPIRY_EPOCH_SECONDS)
