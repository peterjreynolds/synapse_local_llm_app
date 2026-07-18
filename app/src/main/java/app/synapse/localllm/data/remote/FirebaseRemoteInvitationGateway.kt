package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.CreateRemoteInvitationCommand
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteInvitationCreatedReceipt
import app.synapse.localllm.domain.remote.RemoteInvitationGateway
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

class FirebaseRemoteInvitationGateway(
    private val firebaseFunctions: FirebaseFunctions,
) : RemoteInvitationGateway {
    override suspend fun createInvitation(
        command: CreateRemoteInvitationCommand,
    ): RemoteInvitationCreatedReceipt = try {
        val response = firebaseFunctions.getHttpsCallable("createInvitation").call(
            mapOf(
                "intendedLabel" to command.intendedLabel,
                "lifetimeHours" to command.lifetimeHours,
                "maximumUses" to command.maximumUses,
            ),
        ).await().data as? Map<*, *> ?: malformedInvitationResponse()
        parseRemoteInvitationCreatedReceipt(response)
    } catch (exception: RemoteChatException) {
        throw exception
    } catch (exception: Exception) {
        throw exception.toRemoteChatFailure("create an invitation")
    }
}

internal fun parseRemoteInvitationCreatedReceipt(response: Map<*, *>): RemoteInvitationCreatedReceipt =
    RemoteInvitationCreatedReceipt(
        invitationId = response.requireInvitationString("invitationId"),
        invitationCode = response.requireInvitationString("invitationCode"),
        expiresAtMillis = response.requireInvitationLong("expiresAtMillis"),
        maximumUses = response.requireInvitationInt("maximumUses"),
    )

private fun Map<*, *>.requireInvitationString(fieldName: String): String =
    (this[fieldName] as? String)?.takeIf(String::isNotBlank) ?: malformedInvitationResponse()

private fun Map<*, *>.requireInvitationLong(fieldName: String): Long {
    val value = this[fieldName] as? Number ?: malformedInvitationResponse()
    val serialized = value.toDouble()
    val narrowed = value.toLong()
    if (!serialized.isFinite() || serialized != narrowed.toDouble()) malformedInvitationResponse()
    return narrowed
}

private fun Map<*, *>.requireInvitationInt(fieldName: String): Int {
    val value = requireInvitationLong(fieldName)
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) malformedInvitationResponse()
    return value.toInt()
}

private fun malformedInvitationResponse(): Nothing =
    throw RemoteChatException("Synapse returned an invalid invitation response.")
