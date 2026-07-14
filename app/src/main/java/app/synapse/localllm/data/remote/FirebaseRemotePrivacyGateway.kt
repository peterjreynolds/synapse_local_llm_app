package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteBlockMutationReceipt
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDeletionRequestReceipt
import app.synapse.localllm.domain.remote.RemotePrivacyGateway
import app.synapse.localllm.domain.remote.RemotePrivacyState
import app.synapse.localllm.domain.remote.RemoteProfileUid
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

class FirebaseRemotePrivacyGateway(
    private val firebaseFunctions: FirebaseFunctions,
) : RemotePrivacyGateway {
    override suspend fun getOwnPrivacyState(): RemotePrivacyState =
        parseRemotePrivacyState(call("getOwnPrivacyState", emptyMap(), "load privacy settings"))

    override suspend fun setUserBlocked(
        targetUid: RemoteProfileUid,
        blocked: Boolean,
    ): RemoteBlockMutationReceipt {
        val response = call(
            functionName = "setUserBlocked",
            payload = mapOf("blocked" to blocked, "targetUid" to targetUid.raw),
            operation = if (blocked) "block this account" else "unblock this account",
        )
        response.requirePrivacyKeys("blocked", "targetUid")
        if (response.requirePrivacyString("targetUid") != targetUid.raw) malformedPrivacyResponse()
        if (response.requirePrivacyBoolean("blocked") != blocked) malformedPrivacyResponse()
        return RemoteBlockMutationReceipt(
            targetUid = targetUid,
            blocked = blocked,
        )
    }

    override suspend fun requestAccountDeletion(): RemoteDeletionRequestReceipt =
        parseDeletionMutation(
            call("requestAccountDeletion", emptyMap(), "request account deletion"),
            expectedPending = true,
        )

    override suspend fun cancelAccountDeletionRequest(): RemoteDeletionRequestReceipt =
        parseDeletionMutation(
            call("cancelAccountDeletionRequest", emptyMap(), "cancel account deletion"),
            expectedPending = false,
        )

    private suspend fun call(
        functionName: String,
        payload: Map<String, Any?>,
        operation: String,
    ): Map<*, *> = try {
        firebaseFunctions.getHttpsCallable(functionName).call(payload).await().data as? Map<*, *>
            ?: malformedPrivacyResponse()
    } catch (exception: RemoteChatException) {
        throw exception
    } catch (exception: Exception) {
        throw exception.toRemoteChatFailure(operation)
    }
}

internal fun parseRemotePrivacyState(response: Map<*, *>): RemotePrivacyState {
    response.requirePrivacyKeys("blockedUids", "deletionRequestPending")
    val blockedUids = response["blockedUids"] as? List<*> ?: malformedPrivacyResponse()
    val parsedUids = blockedUids.map { value ->
        val uid = (value as? String)?.takeIf(String::isNotBlank) ?: malformedPrivacyResponse()
        RemoteProfileUid(uid)
    }.toSet()
    if (parsedUids.size != blockedUids.size) malformedPrivacyResponse()
    return RemotePrivacyState(
        blockedProfileUids = parsedUids,
        deletionRequestPending = response.requirePrivacyBoolean("deletionRequestPending"),
    )
}

private fun parseDeletionMutation(
    response: Map<*, *>,
    expectedPending: Boolean,
): RemoteDeletionRequestReceipt {
    response.requirePrivacyKeys("deletionRequestPending")
    if (response.requirePrivacyBoolean("deletionRequestPending") != expectedPending) {
        malformedPrivacyResponse()
    }
    return RemoteDeletionRequestReceipt(deletionRequestPending = expectedPending)
}

private fun Map<*, *>.requirePrivacyString(fieldName: String): String =
    (this[fieldName] as? String)?.takeIf(String::isNotBlank) ?: malformedPrivacyResponse()

private fun Map<*, *>.requirePrivacyBoolean(fieldName: String): Boolean =
    this[fieldName] as? Boolean ?: malformedPrivacyResponse()

private fun Map<*, *>.requirePrivacyKeys(vararg expectedKeys: String) {
    if (keys != expectedKeys.toSet()) malformedPrivacyResponse()
}

private fun malformedPrivacyResponse(): Nothing =
    throw RemoteChatException("Synapse returned an invalid account privacy response.")
