package app.synapse.privatechat.data.call

import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.data.chat.PrivateChatAuthenticatedSession
import app.synapse.privatechat.data.chat.PrivateChatRequestRepeatability
import app.synapse.privatechat.data.chat.SupabasePrivateChatRequestExecutor
import app.synapse.privatechat.data.chat.requireChatInt
import app.synapse.privatechat.data.chat.requireChatString
import app.synapse.privatechat.data.chat.requireChatUuid
import app.synapse.privatechat.data.chat.requireExactChatFields
import app.synapse.privatechat.data.chat.requireSingleChatRow
import app.synapse.privatechat.data.chat.toLowerHex
import app.synapse.privatechat.data.supabase.SupabaseHttpMethod
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.domain.call.PrivateCallEndReason
import app.synapse.privatechat.domain.call.PrivateCallException
import app.synapse.privatechat.domain.call.PrivateCallFailure
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallSignalReceipt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

internal class SupabasePrivateCallBackend(
    private val requests: SupabasePrivateChatRequestExecutor,
) : PrivateCallBackend {
    override suspend fun loadDirectRoomEpoch(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
    ): Int {
        val response =
            requests
                .execute(
                    SupabaseHttpRequest(
                        method = SupabaseHttpMethod.GET,
                        pathSegments = listOf("rest", "v1", "rooms"),
                        queryParameters = mapOf("id" to "eq.$roomId", "select" to "id,room_kind,membership_epoch", "limit" to "1"),
                        accessToken = session.accessTokenForRequest(),
                    ),
                    PrivateChatRequestRepeatability.IDEMPOTENT,
                ).requireCallSuccess()
                .requireSingleChatRow("call room preparation")
        response.requireExactChatFields("id", "room_kind", "membership_epoch")
        require(response.requireChatUuid("id") == roomId && response.requireChatString("room_kind") == "DIRECT")
        return response.requireChatInt("membership_epoch", 1..Int.MAX_VALUE)
    }

    override suspend fun createCall(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        roomId: UUID,
        membershipEpoch: Int,
        mediaKind: PrivateCallMediaKind,
        mutationId: UUID,
        expiresAt: Instant,
        envelopes: List<SignalEnvelope>,
    ): PrivateCallSession =
        rpc(
            session,
            "create_private_call",
            buildJsonObject {
                put("p_call_id", callId.toString())
                put("p_room_id", roomId.toString())
                put("p_membership_epoch", membershipEpoch)
                put("p_media_kind", mediaKind.name)
                put("p_client_mutation_id", mutationId.toString())
                put("p_expires_at", expiresAt.toString())
                put("p_envelopes", envelopes.toCallJson())
            },
        ).parseCallReceipt(callId, mutationId)

    override suspend fun poll(session: PrivateChatAuthenticatedSession): PrivateCallBackendPoll =
        rpc(session, "poll_private_calls", buildJsonObject {}).parseCallPoll(session.localSignalAddress)

    override suspend fun acceptCall(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        mutationId: UUID,
    ): PrivateCallSession =
        rpc(
            session,
            "accept_private_call",
            buildJsonObject {
                put("p_call_id", callId.toString())
                put("p_client_mutation_id", mutationId.toString())
            },
        ).parseCallReceipt(callId, mutationId)

    override suspend fun sendSignal(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        mutationId: UUID,
        sequence: Int,
        expiresAt: Instant,
        envelopes: List<SignalEnvelope>,
    ): PrivateCallSignalReceipt =
        rpc(
            session,
            "send_private_call_signal",
            buildJsonObject {
                put("p_call_id", callId.toString())
                put("p_client_mutation_id", mutationId.toString())
                put("p_sequence", sequence)
                put("p_expires_at", expiresAt.toString())
                put("p_envelopes", envelopes.toCallJson())
            },
        ).parseCallSignalReceipt(callId, mutationId, sequence, expiresAt)

    override suspend fun heartbeat(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
    ): PrivateCallSession =
        rpc(session, "heartbeat_private_call", buildJsonObject { put("p_call_id", callId.toString()) })
            .parseCallReceipt(callId, null)

    override suspend fun endCall(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        mutationId: UUID,
        reason: PrivateCallEndReason,
    ): PrivateCallSession =
        rpc(
            session,
            "end_private_call",
            buildJsonObject {
                put("p_call_id", callId.toString())
                put("p_client_mutation_id", mutationId.toString())
                put("p_reason", reason.name)
            },
        ).parseCallReceipt(callId, mutationId)

    private suspend fun rpc(
        session: PrivateChatAuthenticatedSession,
        operation: String,
        body: JsonObject,
    ): SupabaseHttpResponse =
        requests
            .execute(
                SupabaseHttpRequest(
                    method = SupabaseHttpMethod.POST,
                    pathSegments = listOf("rest", "v1", "rpc", operation),
                    accessToken = session.accessTokenForRequest(),
                    jsonBody = body,
                ),
                PrivateChatRequestRepeatability.IDEMPOTENT,
            ).requireCallSuccess()
}

private fun List<SignalEnvelope>.toCallJson(): JsonArray =
    JsonArray(
        map { envelope ->
            val ciphertext = envelope.serializedCiphertext
            try {
                buildJsonObject {
                    put("recipient_device_id", envelope.recipient.transportDeviceId.toString())
                    put("protocol_adapter_version", envelope.protocolVersion)
                    put("signal_message_type", envelope.ciphertextType.wireName)
                    put("ciphertext_hex", ciphertext.toLowerHex())
                }
            } finally {
                ciphertext.fill(0)
            }
        },
    )

private fun SupabaseHttpResponse.requireCallSuccess(): SupabaseHttpResponse {
    if (statusCode !in 200..299) {
        throw PrivateCallException(
            when (statusCode) {
                401, 403 -> PrivateCallFailure.AUTHENTICATION_REQUIRED
                409 -> PrivateCallFailure.INVALID_STATE
                else -> PrivateCallFailure.UNAVAILABLE
            },
        )
    }
    return this
}
