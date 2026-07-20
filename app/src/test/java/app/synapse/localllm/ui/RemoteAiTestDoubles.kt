package app.synapse.localllm.ui

import app.synapse.localllm.application.RemoteLocalAiHostStatus
import app.synapse.localllm.application.RemoteLocalAiResponseHost
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAiMessageReceipt
import app.synapse.localllm.domain.remote.RemoteAiParticipantGateway
import app.synapse.localllm.domain.remote.RemoteAiProvenance
import app.synapse.localllm.domain.remote.RemoteAiResponsePolicy
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteCinderParticipantState
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteHostedAiExecutionPolicy
import app.synapse.localllm.domain.remote.RemoteHostedAiStatus
import app.synapse.localllm.domain.remote.RemoteLocalAiFailureCode
import app.synapse.localllm.domain.remote.RemoteLocalAiResponseClaim
import app.synapse.localllm.domain.remote.RemoteRoomAiConfiguration
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.UpdateRemoteRoomAiConfigurationCommand
import app.synapse.localllm.domain.remote.UpdateRemoteCinderParticipantCommand
import kotlinx.coroutines.awaitCancellation

internal object NoOpRemoteAiParticipantGateway : RemoteAiParticipantGateway {
    override suspend fun getCinderParticipant(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteCinderParticipantState = inactiveCinderParticipant(roomId)

    override suspend fun updateCinderParticipant(
        command: UpdateRemoteCinderParticipantCommand,
    ): RemoteCinderParticipantState = inactiveCinderParticipant(command.roomId).copy(
        active = command.active,
        revision = 1,
    )

    override suspend fun getRoomConfiguration(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteRoomAiConfiguration = humanOnlyRemoteAiConfiguration(roomId)

    override suspend fun updateRoomConfiguration(
        command: UpdateRemoteRoomAiConfigurationCommand,
    ): RemoteRoomAiConfiguration = humanOnlyRemoteAiConfiguration(command.roomId)

    override suspend fun heartbeatLocalHost(accountUid: RemoteAccountUid, deviceId: RemoteDeviceId) = Unit

    override suspend fun claimNextLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ): RemoteLocalAiResponseClaim? = null

    override suspend fun completeLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        claim: RemoteLocalAiResponseClaim,
        body: String,
    ): RemoteAiMessageReceipt = error("No local AI claim was expected in this test.")

    override suspend fun failLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        claim: RemoteLocalAiResponseClaim,
        failureCode: RemoteLocalAiFailureCode,
        retryable: Boolean,
    ): Boolean = false

    override suspend fun skipLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        claim: RemoteLocalAiResponseClaim,
    ) = Unit
}

private fun inactiveCinderParticipant(roomId: RemoteRoomId): RemoteCinderParticipantState =
    RemoteCinderParticipantState(
        roomId = roomId,
        participantId = RemoteAssistantConversationCatalog.cinder.participantId,
        displayName = "Cinder",
        active = false,
        canManage = true,
        provenance = RemoteAiProvenance.REMOTE_HOSTED,
        provider = "OPENCLAW_CINDER",
        responsePolicy = RemoteAiResponsePolicy.MENTION_ONLY,
        revision = 0,
    )

internal object IdleRemoteLocalAiResponseHost : RemoteLocalAiResponseHost {
    override suspend fun synchronize(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        reportStatus: (RemoteLocalAiHostStatus) -> Unit,
    ): Nothing {
        reportStatus(RemoteLocalAiHostStatus.Idle)
        awaitCancellation()
    }
}

private fun humanOnlyRemoteAiConfiguration(roomId: RemoteRoomId): RemoteRoomAiConfiguration =
    RemoteRoomAiConfiguration(
        roomId = roomId,
        localAiParticipantId = "participant-synapse-local-ai",
        localAiEnabled = false,
        localAiAutoResponse = false,
        localAiHostDeviceId = null,
        localAiHostUid = null,
        localAiHostLastSeenAt = null,
        localAiHostAvailable = false,
        hostedAiEnabled = false,
        hostedAiProviderConfigured = false,
        hostedAiStatus = RemoteHostedAiStatus.DISABLED_NO_PROVIDER,
        hostedExecutionPolicy = RemoteHostedAiExecutionPolicy(
            dailyRoomRequestLimit = 0,
            maximumAttempts = 0,
            maximumMonthlyCostMicrousd = 0,
            timeoutMillis = 30_000,
        ),
    )
