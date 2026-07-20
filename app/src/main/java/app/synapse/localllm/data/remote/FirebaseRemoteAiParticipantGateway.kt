package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAiContextMessage
import app.synapse.localllm.domain.remote.RemoteAiMessageReceipt
import app.synapse.localllm.domain.remote.RemoteAiParticipantGateway
import app.synapse.localllm.domain.remote.RemoteAiProvenance
import app.synapse.localllm.domain.remote.RemoteAiResponsePolicy
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteCinderParticipantState
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteHostedAiExecutionPolicy
import app.synapse.localllm.domain.remote.RemoteHostedAiStatus
import app.synapse.localllm.domain.remote.RemoteLocalAiFailureCode
import app.synapse.localllm.domain.remote.RemoteLocalAiResponseClaim
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteRoomAiConfiguration
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.UpdateRemoteRoomAiConfigurationCommand
import app.synapse.localllm.domain.remote.UpdateRemoteCinderParticipantCommand
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.time.Instant
import kotlinx.coroutines.tasks.await

class FirebaseRemoteAiParticipantGateway(
    private val firebaseAuth: FirebaseAuth,
    private val firebaseFunctions: FirebaseFunctions,
    private val sessionController: RemoteAccountSessionController,
) : RemoteAiParticipantGateway {
    override suspend fun getCinderParticipant(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteCinderParticipantState {
        requireAuthenticatedUid(accountUid)
        return call("getCinderParticipant", mapOf("roomId" to roomId.raw))
            .toCinderParticipantState(roomId)
    }

    override suspend fun updateCinderParticipant(
        command: UpdateRemoteCinderParticipantCommand,
    ): RemoteCinderParticipantState {
        requireAuthenticatedUid(command.accountUid)
        return call(
            "setCinderParticipant",
            mapOf(
                "active" to command.active,
                "roomId" to command.roomId.raw,
            ),
        ).toCinderParticipantState(command.roomId)
    }

    override suspend fun getRoomConfiguration(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteRoomAiConfiguration {
        requireAuthenticatedUid(accountUid)
        return call("getRoomAiConfiguration", mapOf("roomId" to roomId.raw))
            .toRoomAiConfiguration()
    }

    override suspend fun updateRoomConfiguration(
        command: UpdateRemoteRoomAiConfigurationCommand,
    ): RemoteRoomAiConfiguration {
        requireAuthenticatedUid(command.accountUid)
        return call(
            "updateRoomAiConfiguration",
            mapOf(
                "hostedAiEnabled" to command.hostedAiEnabled,
                "localAiAutoResponse" to command.localAiAutoResponse,
                "localAiEnabled" to command.localAiEnabled,
                "localAiHostDeviceId" to command.localAiHostDeviceId?.raw,
                "roomId" to command.roomId.raw,
            ),
        ).toRoomAiConfiguration()
    }

    override suspend fun heartbeatLocalHost(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ) {
        requireAuthenticatedUid(accountUid)
        call("heartbeatLocalAiHost", mapOf("deviceId" to deviceId.raw))
    }

    override suspend fun claimNextLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ): RemoteLocalAiResponseClaim? {
        requireAuthenticatedUid(accountUid)
        val response = call("claimNextLocalAiResponse", mapOf("deviceId" to deviceId.raw))
        val claim = response["claim"] ?: return null
        return claim.requireAiMap("local AI response claim").toLocalAiResponseClaim()
    }

    override suspend fun completeLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        claim: RemoteLocalAiResponseClaim,
        body: String,
    ): RemoteAiMessageReceipt {
        requireAuthenticatedUid(accountUid)
        val response = call(
            "completeLocalAiResponse",
            mapOf(
                "body" to body,
                "deviceId" to deviceId.raw,
                "jobId" to claim.jobId,
                "leaseToken" to claim.leaseToken,
            ),
        )
        return RemoteAiMessageReceipt(
            roomId = RemoteRoomId(response.requireAiString("roomId")),
            messageId = RemoteMessageId(response.requireAiString("messageId")),
        )
    }

    override suspend fun failLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        claim: RemoteLocalAiResponseClaim,
        failureCode: RemoteLocalAiFailureCode,
        retryable: Boolean,
    ): Boolean {
        requireAuthenticatedUid(accountUid)
        return call(
            "failLocalAiResponse",
            mapOf(
                "deviceId" to deviceId.raw,
                "failureCode" to failureCode.name,
                "jobId" to claim.jobId,
                "leaseToken" to claim.leaseToken,
                "retryable" to retryable,
            ),
        ).requireAiBoolean("retryScheduled")
    }

    override suspend fun skipLocalResponse(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
        claim: RemoteLocalAiResponseClaim,
    ) {
        requireAuthenticatedUid(accountUid)
        call(
            "skipLocalAiResponse",
            mapOf(
                "deviceId" to deviceId.raw,
                "jobId" to claim.jobId,
                "leaseToken" to claim.leaseToken,
                "reason" to "MENTION_REQUIRED",
            ),
        )
    }

    private suspend fun call(name: String, payload: Map<String, Any?>): Map<*, *> =
        try {
            firebaseFunctions.getHttpsCallable(name).call(payload).await().data
                .requireAiMap(name)
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("use the remote AI participant")
        }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        sessionController.requireActiveToken(accountUid)
        if (firebaseAuth.currentUser?.uid != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }
}

internal fun Map<*, *>.toCinderParticipantState(expectedRoomId: RemoteRoomId): RemoteCinderParticipantState {
    val endpoint = RemoteAssistantConversationCatalog.cinder
    val roomId = RemoteRoomId(requireAiString("roomId"))
    val participantId = requireAiString("participantId")
    val displayName = requireAiString("displayName")
    val provenance = requireAiString("provenance")
    val provider = requireAiString("provider")
    val responsePolicy = requireAiString("responsePolicy")
    if (
        participantId != endpoint.participantId.raw ||
        displayName != endpoint.displayName ||
        roomId != expectedRoomId ||
        provenance != RemoteAiProvenance.REMOTE_HOSTED.name ||
        provider != CINDER_PROVIDER ||
        responsePolicy != RemoteAiResponsePolicy.MENTION_ONLY.name
    ) {
        malformedAiResponse()
    }
    return RemoteCinderParticipantState(
        roomId = roomId,
        participantId = endpoint.participantId,
        displayName = displayName,
        active = requireAiBoolean("active"),
        canManage = requireAiBoolean("canManage"),
        provenance = RemoteAiProvenance.REMOTE_HOSTED,
        provider = provider,
        responsePolicy = RemoteAiResponsePolicy.MENTION_ONLY,
        revision = requireAiLong("revision"),
    )
}

private fun Map<*, *>.toRoomAiConfiguration(): RemoteRoomAiConfiguration {
    val hostedPolicy = this["hostedExecutionPolicy"].requireAiMap("hosted AI execution policy")
    return RemoteRoomAiConfiguration(
        roomId = RemoteRoomId(requireAiString("roomId")),
        localAiParticipantId = requireAiString("localAiParticipantId"),
        localAiEnabled = requireAiBoolean("localAiEnabled"),
        localAiAutoResponse = requireAiBoolean("localAiAutoResponse"),
        localAiHostDeviceId = optionalAiString("localAiHostDeviceId")?.let(::RemoteDeviceId),
        localAiHostUid = optionalAiString("localAiHostUid")?.let(::RemoteAccountUid),
        localAiHostLastSeenAt = optionalAiLong("localAiHostLastSeenAtMillis")?.let(Instant::ofEpochMilli),
        localAiHostAvailable = requireAiBoolean("localAiHostAvailable"),
        hostedAiEnabled = requireAiBoolean("hostedAiEnabled"),
        hostedAiProviderConfigured = requireAiBoolean("hostedAiProviderConfigured"),
        hostedAiStatus = RemoteHostedAiStatus.valueOf(requireAiString("hostedAiStatus")),
        hostedExecutionPolicy = RemoteHostedAiExecutionPolicy(
            dailyRoomRequestLimit = hostedPolicy.requireAiInt("dailyRoomRequestLimit"),
            maximumAttempts = hostedPolicy.requireAiInt("maximumAttempts"),
            maximumMonthlyCostMicrousd = hostedPolicy.requireAiLong("maximumMonthlyCostMicrousd"),
            timeoutMillis = hostedPolicy.requireAiLong("timeoutMillis"),
        ),
    )
}

private fun Map<*, *>.toLocalAiResponseClaim(): RemoteLocalAiResponseClaim =
    RemoteLocalAiResponseClaim(
        jobId = requireAiString("jobId"),
        leaseToken = requireAiString("leaseToken"),
        leaseExpiresAt = Instant.ofEpochMilli(requireAiLong("leaseExpiresAtMillis")),
        roomId = RemoteRoomId(requireAiString("roomId")),
        roomKind = RemoteRoomKind.valueOf(requireAiString("roomKind")),
        responsePolicy = RemoteAiResponsePolicy.valueOf(requireAiString("responsePolicy")),
        sourceMessage = this["sourceMessage"].requireAiMap("AI source message").toAiContextMessage(),
        recentMessages = (this["recentMessages"] as? List<*>)
            ?.map { message -> message.requireAiMap("AI context message").toAiContextMessage() }
            ?: malformedAiResponse(),
    )

private fun Map<*, *>.toAiContextMessage(): RemoteAiContextMessage =
    RemoteAiContextMessage(
        messageId = RemoteMessageId(requireAiString("messageId")),
        authorId = requireAiString("authorId"),
        authorKind = requireAiString("authorKind").takeIf { kind -> kind == "HUMAN" || kind == "SYNAPSE_AI" }
            ?: malformedAiResponse(),
        body = requireAiString("body"),
    )

private fun Any?.requireAiMap(owner: String): Map<*, *> =
    this as? Map<*, *> ?: throw RemoteChatException("Synapse returned a malformed $owner.")

private fun Map<*, *>.requireAiString(fieldName: String): String =
    (this[fieldName] as? String)?.takeIf(String::isNotBlank) ?: malformedAiResponse()

private fun Map<*, *>.optionalAiString(fieldName: String): String? =
    when (val value = this[fieldName]) {
        null -> null
        is String -> value.takeIf(String::isNotBlank) ?: malformedAiResponse()
        else -> malformedAiResponse()
    }

private fun Map<*, *>.requireAiBoolean(fieldName: String): Boolean =
    this[fieldName] as? Boolean ?: malformedAiResponse()

private fun Map<*, *>.requireAiInt(fieldName: String): Int =
    requireAiLong(fieldName).takeIf { value -> value <= Int.MAX_VALUE }?.toInt() ?: malformedAiResponse()

private fun Map<*, *>.requireAiLong(fieldName: String): Long =
    (this[fieldName] as? Number)?.toDouble()?.let { number ->
        number.takeIf(Double::isFinite)?.toLong()?.takeIf { integer ->
            integer >= 0L && integer.toDouble() == number
        }
    } ?: malformedAiResponse()

private fun Map<*, *>.optionalAiLong(fieldName: String): Long? =
    if (this[fieldName] == null) null else requireAiLong(fieldName)

private fun malformedAiResponse(): Nothing =
    throw RemoteChatException("Synapse returned malformed remote AI participant state.")

private const val CINDER_PROVIDER = "OPENCLAW_CINDER"
