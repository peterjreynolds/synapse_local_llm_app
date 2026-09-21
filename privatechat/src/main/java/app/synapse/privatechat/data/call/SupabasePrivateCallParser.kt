package app.synapse.privatechat.data.call

import app.synapse.privatechat.crypto.SignalCiphertextType
import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.data.chat.requireChatInstant
import app.synapse.privatechat.data.chat.requireChatInt
import app.synapse.privatechat.data.chat.requireChatString
import app.synapse.privatechat.data.chat.requireChatUuid
import app.synapse.privatechat.data.chat.requireExactChatFields
import app.synapse.privatechat.data.chat.requireNullableChatUuid
import app.synapse.privatechat.data.chat.requireSingleChatRow
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallSignalReceipt
import app.synapse.privatechat.domain.call.PrivateCallState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

internal fun SupabaseHttpResponse.parseCallReceipt(
    callId: UUID,
    mutationId: UUID?,
): PrivateCallSession {
    val row = requireSingleChatRow("call mutation")
    row.requireExactChatFields(*CALL_FIELDS, "client_mutation_id")
    require(row.requireNullableChatUuid("client_mutation_id") == mutationId) { "Call receipt mutation mismatched" }
    return row.parseCallSession().also { require(it.callId == callId) { "Call receipt target mismatched" } }
}

internal fun SupabaseHttpResponse.parseCallSignalReceipt(
    callId: UUID,
    mutationId: UUID,
    sequence: Int,
    expiresAt: Instant,
): PrivateCallSignalReceipt {
    val row = requireSingleChatRow("call signal")
    row.requireExactChatFields("call_id", "client_mutation_id", "sequence", "created_at", "expires_at")
    return PrivateCallSignalReceipt(
        row.requireChatUuid("call_id"),
        row.requireChatUuid("client_mutation_id"),
        row.requireChatInt("sequence", 0..MAXIMUM_CALL_SEQUENCE),
        row.requireChatInstant("created_at"),
        row.requireChatInstant("expires_at"),
    ).also {
        require(it.callId == callId && it.clientMutationId == mutationId && it.sequence == sequence && it.expiresAt == expiresAt)
        require(it.expiresAt.isAfter(it.createdAt) && !it.expiresAt.isAfter(it.createdAt.plusSeconds(65)))
    }
}

internal fun SupabaseHttpResponse.parseCallPoll(localAddress: SignalDeviceAddress): PrivateCallBackendPoll {
    val root = jsonBody as? JsonObject ?: error("Call poll is malformed")
    root.requireExactChatFields("calls", "signals")
    val calls =
        root.requireCallObjects("calls", 20).map { row ->
            row.requireExactChatFields(*CALL_FIELDS)
            row.parseCallSession()
        }
    require(calls.distinctBy(PrivateCallSession::callId).size == calls.size)
    val signals = root.requireCallObjects("signals", 512).map { row -> row.parseEncryptedSignal(localAddress) }
    require(signals.distinctBy { Triple(it.callId, it.envelope.sender.transportDeviceId, it.sequence) }.size == signals.size)
    return PrivateCallBackendPoll(calls, signals)
}

private fun JsonObject.parseCallSession(): PrivateCallSession {
    val terminalReason = if (this["terminal_reason"] == JsonNull) null else requireChatString("terminal_reason")
    require(terminalReason == null || terminalReason in TERMINAL_REASONS)
    return PrivateCallSession(
        callId = requireChatUuid("call_id"),
        roomId = PrivateRoomId(requireChatUuid("room_id").toString()),
        membershipEpoch = requireChatInt("membership_epoch", 1..Int.MAX_VALUE),
        callerAccountId = requireChatUuid("caller_user_id"),
        callerDeviceId = requireChatUuid("caller_device_id"),
        recipientAccountId = requireChatUuid("recipient_user_id"),
        acceptedDeviceId = requireNullableChatUuid("accepted_device_id"),
        mediaKind = PrivateCallMediaKind.valueOf(requireChatString("media_kind")),
        state = PrivateCallState.valueOf(requireChatString("state")),
        terminalReason = terminalReason,
        createdAt = requireChatInstant("created_at"),
        ringExpiresAt = requireChatInstant("ring_expires_at"),
        leaseExpiresAt = requireChatInstant("lease_expires_at"),
    ).also { call ->
        require(call.callerAccountId != call.recipientAccountId && call.callerDeviceId != call.acceptedDeviceId)
        require(call.ringExpiresAt.isAfter(call.createdAt) && !call.ringExpiresAt.isAfter(call.createdAt.plusSeconds(65)))
        require(call.leaseExpiresAt.isAfter(call.createdAt))
        require((call.state == PrivateCallState.ENDED) == (terminalReason != null))
        require(call.state != PrivateCallState.ACTIVE || call.acceptedDeviceId != null)
        require(call.state != PrivateCallState.RINGING || call.acceptedDeviceId == null)
    }
}

private fun JsonObject.parseEncryptedSignal(localAddress: SignalDeviceAddress): PrivateCallEncryptedSignalRecord {
    requireExactChatFields(
        "call_id",
        "room_id",
        "membership_epoch",
        "sender_user_id",
        "sender_device_id",
        "sender_signal_device_id",
        "client_mutation_id",
        "sequence",
        "recipient_device_id",
        "protocol_adapter_version",
        "signal_message_type",
        "ciphertext_hex",
        "created_at",
        "expires_at",
    )
    require(requireChatUuid("recipient_device_id") == localAddress.transportDeviceId)
    val encodedCiphertext = requireChatString("ciphertext_hex")
    require(encodedCiphertext.length in 2..131_072 && encodedCiphertext.length % 2 == 0 && LOWER_HEX.matches(encodedCiphertext))
    val ciphertext =
        ByteArray(encodedCiphertext.length / 2) { index ->
            encodedCiphertext.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    try {
        return PrivateCallEncryptedSignalRecord(
            callId = requireChatUuid("call_id"),
            roomId = requireChatUuid("room_id"),
            membershipEpoch = requireChatInt("membership_epoch", 1..Int.MAX_VALUE),
            clientMutationId = requireChatUuid("client_mutation_id"),
            sequence = requireChatInt("sequence", 0..MAXIMUM_CALL_SEQUENCE),
            createdAt = requireChatInstant("created_at"),
            expiresAt = requireChatInstant("expires_at"),
            envelope =
                SignalEnvelope.fromWire(
                    protocolVersion = requireChatInt("protocol_adapter_version", 1..1),
                    sender =
                        SignalDeviceAddress(
                            requireChatUuid("sender_user_id"),
                            requireChatUuid("sender_device_id"),
                            SignalDeviceId.fromWire(requireChatInt("sender_signal_device_id", 1..127)),
                        ),
                    recipient = localAddress,
                    ciphertextTypeCode = SignalCiphertextType.fromWire(requireChatString("signal_message_type")).wireCode,
                    serializedCiphertext = ciphertext,
                ),
        ).also {
            require(it.expiresAt.isAfter(it.createdAt) && !it.expiresAt.isAfter(it.createdAt.plusSeconds(65)))
        }
    } finally {
        ciphertext.fill(0)
    }
}

private fun JsonObject.requireCallObjects(
    field: String,
    limit: Int,
): List<JsonObject> {
    val rows = this[field] as? JsonArray ?: error("Call poll list is malformed")
    require(rows.size <= limit)
    return rows.map { it as? JsonObject ?: error("Call poll row is malformed") }
}

private val CALL_FIELDS =
    arrayOf(
        "call_id",
        "room_id",
        "membership_epoch",
        "caller_user_id",
        "caller_device_id",
        "recipient_user_id",
        "accepted_device_id",
        "media_kind",
        "state",
        "terminal_reason",
        "created_at",
        "ring_expires_at",
        "lease_expires_at",
    )
private val TERMINAL_REASONS = setOf("CANCELLED", "DECLINED", "ENDED", "TIMEOUT", "ACCESS_REVOKED")
private val LOWER_HEX = Regex("^[0-9a-f]+$")
