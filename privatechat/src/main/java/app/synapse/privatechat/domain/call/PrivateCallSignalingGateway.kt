package app.synapse.privatechat.domain.call

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateRoomId
import java.time.Instant
import java.util.UUID

enum class PrivateCallState { RINGING, ACTIVE, ENDED }

enum class PrivateCallEndReason { CANCELLED, DECLINED, ENDED }

data class PrivateCallSession(
    val callId: UUID,
    val roomId: PrivateRoomId,
    val membershipEpoch: Int,
    val callerAccountId: UUID,
    val callerDeviceId: UUID,
    val recipientAccountId: UUID,
    val acceptedDeviceId: UUID?,
    val mediaKind: PrivateCallMediaKind,
    val state: PrivateCallState,
    val terminalReason: String?,
    val createdAt: Instant,
    val ringExpiresAt: Instant,
    val leaseExpiresAt: Instant,
)

data class PrivateCallPeerSafetyNumber(
    val accountId: UUID,
    val deviceId: UUID,
    val groupedDigits: String,
) {
    override fun toString(): String = "PrivateCallPeerSafetyNumber([REDACTED])"
}

data class PrivateCallPreparation(
    val roomId: PrivateRoomId,
    val membershipEpoch: Int,
    val peerSafetyNumbers: List<PrivateCallPeerSafetyNumber>,
)

sealed interface PrivateCallSignal {
    data class Media(
        val mediaSignal: PrivateCallMediaSignal,
    ) : PrivateCallSignal {
        override fun toString(): String = "PrivateCallSignal.Media([REDACTED])"
    }

    data class End(
        val reason: PrivateCallEndReason,
    ) : PrivateCallSignal
}

data class PrivateReceivedCallSignal(
    val callId: UUID,
    val senderDeviceId: UUID,
    val sequence: Int,
    val expiresAt: Instant,
    val signal: PrivateCallSignal,
)

data class PrivateCallSignalReceipt(
    val callId: UUID,
    val clientMutationId: UUID,
    val sequence: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class PrivateCallPoll(
    val calls: List<PrivateCallSession>,
    val signals: List<PrivateReceivedCallSignal>,
)

interface PrivateCallSignalingGateway {
    suspend fun prepareCall(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
    ): PrivateCallPreparation

    suspend fun createCall(
        accountId: PrivateAccountId,
        callId: UUID,
        roomId: PrivateRoomId,
        membershipEpoch: Int,
        mediaKind: PrivateCallMediaKind,
        offer: PrivateCallMediaSignal.Offer,
    ): PrivateCallSession

    suspend fun poll(accountId: PrivateAccountId): PrivateCallPoll

    suspend fun acceptCall(
        accountId: PrivateAccountId,
        callId: UUID,
    ): PrivateCallSession

    suspend fun sendSignal(
        accountId: PrivateAccountId,
        callId: UUID,
        signal: PrivateCallMediaSignal,
    ): PrivateCallSignalReceipt

    suspend fun heartbeat(
        accountId: PrivateAccountId,
        callId: UUID,
    ): PrivateCallSession

    suspend fun endCall(
        accountId: PrivateAccountId,
        callId: UUID,
        reason: PrivateCallEndReason,
    ): PrivateCallSession

    suspend fun safetyNumbers(
        accountId: PrivateAccountId,
        callId: UUID,
    ): List<PrivateCallPeerSafetyNumber>

    suspend fun clearEphemeralState()
}

enum class PrivateCallFailure {
    AUTHENTICATION_REQUIRED,
    UNAVAILABLE,
    INVALID_STATE,
    IDENTITY_CHANGED,
    INVALID_SIGNAL,
    EXPIRED,
}

class PrivateCallException(
    val failure: PrivateCallFailure,
) : Exception("Encrypted call failed: $failure")
