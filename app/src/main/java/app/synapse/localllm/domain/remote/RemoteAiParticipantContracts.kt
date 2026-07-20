package app.synapse.localllm.domain.remote

import java.time.Instant

enum class RemoteAiResponsePolicy {
    MENTION_ONLY,
    AUTOMATIC,
}

enum class RemoteCinderParticipationMode {
    SILENT,
    MENTION,
    AUTO,
}

enum class RemoteCinderWorkState {
    IDLE,
    QUEUED,
    THINKING,
}

enum class RemoteHostedAiStatus {
    DISABLED_NO_PROVIDER,
}

enum class RemoteAiProvenance {
    PHONE_LOCAL,
    REMOTE_HOSTED,
}

enum class RemoteLocalAiFailureCode {
    CANCELLED,
    GENERATION_FAILED,
    MODEL_UNAVAILABLE,
    TIMEOUT,
}

data class RemoteHostedAiExecutionPolicy(
    val dailyRoomRequestLimit: Int,
    val maximumAttempts: Int,
    val maximumMonthlyCostMicrousd: Long,
    val timeoutMillis: Long,
)

data class RemoteRoomAiConfiguration(
    val roomId: RemoteRoomId,
    val localAiParticipantId: String,
    val localAiEnabled: Boolean,
    val localAiAutoResponse: Boolean,
    val localAiHostDeviceId: RemoteDeviceId?,
    val localAiHostUid: RemoteAccountUid?,
    val localAiHostLastSeenAt: Instant?,
    val localAiHostAvailable: Boolean,
    val hostedAiEnabled: Boolean,
    val hostedAiProviderConfigured: Boolean,
    val hostedAiStatus: RemoteHostedAiStatus,
    val hostedExecutionPolicy: RemoteHostedAiExecutionPolicy,
)

data class UpdateRemoteRoomAiConfigurationCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val localAiEnabled: Boolean,
    val localAiAutoResponse: Boolean,
    val localAiHostDeviceId: RemoteDeviceId?,
    val hostedAiEnabled: Boolean = false,
)

data class RemoteCinderParticipantState(
    val roomId: RemoteRoomId,
    val participantId: RemoteAssistantParticipantId,
    val displayName: String,
    val active: Boolean,
    val canManage: Boolean,
    val mode: RemoteCinderParticipationMode,
    val provenance: RemoteAiProvenance,
    val provider: String,
    val revision: Long,
    val workState: RemoteCinderWorkState,
) {
    init {
        require(revision >= 0L) { "Cinder participant revision must not be negative." }
        require(participantId == RemoteAssistantConversationCatalog.cinder.participantId) {
            "Cinder participant state must use the registered Cinder identity."
        }
        require(displayName == RemoteAssistantConversationCatalog.cinder.displayName) {
            "Cinder participant state must use the registered Cinder display name."
        }
        require(provenance == RemoteAiProvenance.REMOTE_HOSTED) {
            "Cinder participant state must use remote-hosted provenance."
        }
        require(provider == "OPENCLAW_CINDER") { "Cinder participant provider is invalid." }
    }
}

data class UpdateRemoteCinderParticipantCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val active: Boolean,
    val mode: RemoteCinderParticipationMode,
    val expectedRevision: Long,
) {
    init {
        require(expectedRevision >= 0L) { "Expected Cinder participant revision must not be negative." }
    }
}

data class RemoteAiContextMessage(
    val messageId: RemoteMessageId,
    val authorId: String,
    val authorKind: String,
    val body: String,
)

data class RemoteLocalAiResponseClaim(
    val jobId: String,
    val leaseToken: String,
    val leaseExpiresAt: Instant,
    val roomId: RemoteRoomId,
    val roomKind: RemoteRoomKind,
    val responsePolicy: RemoteAiResponsePolicy,
    val sourceMessage: RemoteAiContextMessage,
    val recentMessages: List<RemoteAiContextMessage>,
)

data class RemoteAiMessageReceipt(
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
)

interface RemoteAiParticipantGateway {
    suspend fun getCinderParticipant(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteCinderParticipantState

    suspend fun getCinderParticipants(
        accountUid: RemoteAccountUid,
        roomIds: List<RemoteRoomId>,
    ): Map<RemoteRoomId, RemoteCinderParticipantState> = buildMap {
        roomIds.forEach { roomId -> put(roomId, getCinderParticipant(accountUid, roomId)) }
    }

    suspend fun updateCinderParticipant(
        command: UpdateRemoteCinderParticipantCommand,
    ): RemoteCinderParticipantState

    suspend fun getRoomConfiguration(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteRoomAiConfiguration

    suspend fun updateRoomConfiguration(
        command: UpdateRemoteRoomAiConfigurationCommand,
    ): RemoteRoomAiConfiguration

    suspend fun heartbeatLocalHost(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    )

    suspend fun claimNextLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ): RemoteLocalAiResponseClaim?

    suspend fun completeLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        claim: RemoteLocalAiResponseClaim,
        body: String,
    ): RemoteAiMessageReceipt

    suspend fun failLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        claim: RemoteLocalAiResponseClaim,
        failureCode: RemoteLocalAiFailureCode,
        retryable: Boolean,
    ): Boolean

    suspend fun skipLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        claim: RemoteLocalAiResponseClaim,
    )
}
