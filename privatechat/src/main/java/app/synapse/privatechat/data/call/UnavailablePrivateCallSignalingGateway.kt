package app.synapse.privatechat.data.call

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.call.PrivateCallEndReason
import app.synapse.privatechat.domain.call.PrivateCallException
import app.synapse.privatechat.domain.call.PrivateCallFailure
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallSignalingGateway
import app.synapse.privatechat.domain.chat.PrivateRoomId
import java.util.UUID

internal object UnavailablePrivateCallSignalingGateway : PrivateCallSignalingGateway {
    override suspend fun prepareCall(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
    ): Nothing = unavailable()

    override suspend fun createCall(
        accountId: PrivateAccountId,
        callId: UUID,
        roomId: PrivateRoomId,
        membershipEpoch: Int,
        mediaKind: PrivateCallMediaKind,
        offer: PrivateCallMediaSignal.Offer,
    ): Nothing = unavailable()

    override suspend fun poll(accountId: PrivateAccountId): Nothing = unavailable()

    override suspend fun acceptCall(
        accountId: PrivateAccountId,
        callId: UUID,
    ): Nothing = unavailable()

    override suspend fun sendSignal(
        accountId: PrivateAccountId,
        callId: UUID,
        signal: PrivateCallMediaSignal,
    ): Nothing = unavailable()

    override suspend fun heartbeat(
        accountId: PrivateAccountId,
        callId: UUID,
    ): Nothing = unavailable()

    override suspend fun endCall(
        accountId: PrivateAccountId,
        callId: UUID,
        reason: PrivateCallEndReason,
    ): Nothing = unavailable()

    override suspend fun safetyNumbers(
        accountId: PrivateAccountId,
        callId: UUID,
    ): Nothing = unavailable()

    override suspend fun clearEphemeralState() = Unit

    private fun unavailable(): Nothing = throw PrivateCallException(PrivateCallFailure.UNAVAILABLE)
}
