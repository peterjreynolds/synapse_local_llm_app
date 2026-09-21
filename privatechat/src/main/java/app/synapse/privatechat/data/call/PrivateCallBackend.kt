package app.synapse.privatechat.data.call

import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.data.chat.PrivateChatAuthenticatedSession
import app.synapse.privatechat.domain.call.PrivateCallEndReason
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallSignalReceipt
import java.time.Instant
import java.util.UUID

internal data class PrivateCallEncryptedSignalRecord(
    val callId: UUID,
    val roomId: UUID,
    val membershipEpoch: Int,
    val clientMutationId: UUID,
    val sequence: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
    val envelope: SignalEnvelope,
)

internal data class PrivateCallBackendPoll(
    val calls: List<PrivateCallSession>,
    val signals: List<PrivateCallEncryptedSignalRecord>,
)

internal interface PrivateCallBackend {
    suspend fun loadDirectRoomEpoch(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
    ): Int

    suspend fun createCall(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        roomId: UUID,
        membershipEpoch: Int,
        mediaKind: PrivateCallMediaKind,
        mutationId: UUID,
        expiresAt: Instant,
        envelopes: List<SignalEnvelope>,
    ): PrivateCallSession

    suspend fun poll(session: PrivateChatAuthenticatedSession): PrivateCallBackendPoll

    suspend fun acceptCall(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        mutationId: UUID,
    ): PrivateCallSession

    suspend fun sendSignal(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        mutationId: UUID,
        sequence: Int,
        expiresAt: Instant,
        envelopes: List<SignalEnvelope>,
    ): PrivateCallSignalReceipt

    suspend fun heartbeat(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
    ): PrivateCallSession

    suspend fun endCall(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        mutationId: UUID,
        reason: PrivateCallEndReason,
    ): PrivateCallSession
}
