package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.CreateOwnerAccountCommand
import app.synapse.localllm.domain.remote.CreateOwnerInvitationCommand
import app.synapse.localllm.domain.remote.OwnerAccountMutationReceipt
import app.synapse.localllm.domain.remote.OwnerAccountSummary
import app.synapse.localllm.domain.remote.OwnerAdminGateway
import app.synapse.localllm.domain.remote.OwnerAuditEventSummary
import app.synapse.localllm.domain.remote.OwnerDeviceSummary
import app.synapse.localllm.domain.remote.OwnerInvitationCreatedReceipt
import app.synapse.localllm.domain.remote.OwnerInvitationSummary
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.ResetOwnerAccountPasswordCommand
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

class FirebaseOwnerAdminGateway(
    private val firebaseFunctions: FirebaseFunctions,
) : OwnerAdminGateway {
    override suspend fun listAccounts(searchPrefix: String?): List<OwnerAccountSummary> {
        val response = call(
            functionName = "listOwnerAccounts",
            payload = searchPrefix?.let { mapOf("searchPrefix" to it) } ?: emptyMap(),
            operation = "load accounts",
        )
        return parseOwnerAccountSummaries(response)
    }

    override suspend fun createAccount(command: CreateOwnerAccountCommand): OwnerAccountMutationReceipt {
        val response = call(
            functionName = "createAccountForUser",
            payload = mapOf(
                "displayName" to command.displayName,
                "password" to command.temporaryPassword,
                "requirePasswordChange" to command.requirePasswordChange,
                "username" to command.username,
            ),
            operation = "create the account",
        )
        return parseAccountMutationReceipt(response)
    }

    override suspend fun reviewRegistration(
        targetUid: RemoteAccountUid,
        approve: Boolean,
    ): OwnerAccountMutationReceipt {
        val response = call(
            functionName = "reviewRegistration",
            payload = mapOf(
                "decision" to if (approve) "APPROVE" else "REJECT",
                "targetUid" to targetUid.raw,
            ),
            operation = "review the registration",
        )
        return parseAccountMutationReceipt(response)
    }

    override suspend fun setAccountEnabled(
        targetUid: RemoteAccountUid,
        enabled: Boolean,
    ): OwnerAccountMutationReceipt {
        val response = call(
            functionName = "setOwnerAccountEnabled",
            payload = mapOf("enabled" to enabled, "targetUid" to targetUid.raw),
            operation = "change account access",
        )
        return parseAccountMutationReceipt(response)
    }

    override suspend fun revokeAccountSessions(
        targetUid: RemoteAccountUid,
    ): OwnerAccountMutationReceipt {
        val response = call(
            functionName = "revokeOwnerAccountSessions",
            payload = mapOf("targetUid" to targetUid.raw),
            operation = "revoke account sessions",
        )
        return parseAccountMutationReceipt(response)
    }

    override suspend fun deleteAccount(
        targetUid: RemoteAccountUid,
        confirmUsername: String,
    ): OwnerAccountMutationReceipt {
        val response = call(
            functionName = "deleteOwnerAccount",
            payload = mapOf(
                "confirmUsername" to confirmUsername,
                "targetUid" to targetUid.raw,
            ),
            operation = "delete the account",
        )
        return parseAccountMutationReceipt(response)
    }

    override suspend fun resetAccountPassword(
        command: ResetOwnerAccountPasswordCommand,
    ): OwnerAccountMutationReceipt {
        val response = call(
            functionName = "resetOwnerAccountPassword",
            payload = mapOf(
                "password" to command.temporaryPassword,
                "requirePasswordChange" to command.requirePasswordChange,
                "targetUid" to command.targetUid.raw,
            ),
            operation = "reset the account password",
        )
        return parseAccountMutationReceipt(response)
    }

    override suspend fun listInvitations(): List<OwnerInvitationSummary> =
        parseOwnerInvitationSummaries(
            call("listOwnerInvitations", emptyMap(), "load invitations"),
        )

    override suspend fun createInvitation(
        command: CreateOwnerInvitationCommand,
    ): OwnerInvitationCreatedReceipt {
        val response = call(
            functionName = "createInvitation",
            payload = mapOf(
                "intendedLabel" to command.intendedLabel,
                "lifetimeHours" to command.lifetimeHours,
                "maximumUses" to command.maximumUses,
            ),
            operation = "create the invitation",
        )
        return OwnerInvitationCreatedReceipt(
            invitationId = response.requireString("invitationId"),
            invitationCode = response.requireString("invitationCode"),
            expiresAtMillis = response.requireLong("expiresAtMillis"),
            maximumUses = response.requireInt("maximumUses"),
        )
    }

    override suspend fun revokeInvitation(invitationId: String) {
        val response = call(
            functionName = "revokeInvitation",
            payload = mapOf("invitationId" to invitationId),
            operation = "revoke the invitation",
        )
        if (response.requireString("invitationId") != invitationId) malformedResponse()
    }

    override suspend fun setRegistrationApprovalRequired(required: Boolean) {
        val response = call(
            functionName = "setRegistrationApprovalRequired",
            payload = mapOf("approvalRequired" to required),
            operation = "change registration approval",
        )
        if (response["approvalRequired"] != required) malformedResponse()
    }

    override suspend fun getRegistrationApprovalRequired(): Boolean =
        call(
            functionName = "getOwnerRegistrationConfiguration",
            payload = emptyMap(),
            operation = "load registration configuration",
        ).requireBoolean("approvalRequired")

    override suspend fun listDevices(targetUid: RemoteAccountUid): List<OwnerDeviceSummary> =
        parseOwnerDeviceSummaries(
            call(
                functionName = "listOwnerDevices",
                payload = mapOf("targetUid" to targetUid.raw),
                operation = "load account devices",
            ),
        )

    override suspend fun removeDevice(
        targetUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ) {
        val response = call(
            functionName = "removeOwnerDevice",
            payload = mapOf("deviceId" to deviceId.raw, "targetUid" to targetUid.raw),
            operation = "remove the device",
        )
        verifyDeviceReceipt(response, targetUid, deviceId)
    }

    override suspend fun sendTestPush(
        targetUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ) {
        val response = call(
            functionName = "sendOwnerTestPush",
            payload = mapOf("deviceId" to deviceId.raw, "targetUid" to targetUid.raw),
            operation = "send the test notification",
        )
        verifyDeviceReceipt(response, targetUid, deviceId)
        response.requireString("messageId")
    }

    override suspend fun listAuditEvents(limit: Int): List<OwnerAuditEventSummary> =
        parseOwnerAuditEventSummaries(
            call(
                functionName = "listOwnerAuditEvents",
                payload = mapOf("limit" to limit),
                operation = "load security history",
            ),
        )

    private suspend fun call(
        functionName: String,
        payload: Map<String, Any?>,
        operation: String,
    ): Map<*, *> = try {
        firebaseFunctions.getHttpsCallable(functionName).call(payload).await().data as? Map<*, *>
            ?: malformedResponse()
    } catch (exception: RemoteChatException) {
        throw exception
    } catch (exception: Exception) {
        throw exception.toOwnerAdminFailure(operation)
    }
}

internal fun parseOwnerAccountSummaries(response: Map<*, *>): List<OwnerAccountSummary> =
    response.requireMapList("accounts").map { account ->
        OwnerAccountSummary(
            accountUid = RemoteAccountUid(account.requireString("uid")),
            usernameNormalized = account.requireString("usernameNormalized"),
            displayName = account.requireString("displayName"),
            role = account.requireEnum("role"),
            state = account.requireEnum("accountState"),
            mustChangePassword = account.requireBoolean("mustChangePassword"),
            createdAtMillis = account.optionalLong("createdAtMillis"),
            lastSeenAtMillis = account.optionalLong("lastSeenAtMillis"),
        )
    }

internal fun parseOwnerInvitationSummaries(response: Map<*, *>): List<OwnerInvitationSummary> =
    response.requireMapList("invitations").map { invitation ->
        OwnerInvitationSummary(
            invitationId = invitation.requireString("invitationId"),
            intendedLabel = invitation.optionalString("intendedLabel"),
            state = invitation.requireString("state"),
            maximumUses = invitation.requireInt("maximumUses"),
            remainingUses = invitation.requireInt("remainingUses"),
            expiresAtMillis = invitation.requireLong("expiresAtMillis"),
        )
    }

internal fun parseOwnerDeviceSummaries(response: Map<*, *>): List<OwnerDeviceSummary> =
    response.requireMapList("devices").map { device ->
        if (device.requireString("platform") != "ANDROID") malformedResponse()
        OwnerDeviceSummary(
            deviceId = RemoteDeviceId(device.requireString("deviceId")),
            active = device.requireBoolean("active"),
            updatedAtMillis = device.optionalLong("updatedAtMillis"),
        )
    }

internal fun parseOwnerAuditEventSummaries(response: Map<*, *>): List<OwnerAuditEventSummary> =
    response.requireMapList("events").map { event ->
        OwnerAuditEventSummary(
            eventId = event.requireString("eventId"),
            eventType = event.requireString("eventType"),
            actorUid = RemoteAccountUid(event.requireString("actorUid")),
            targetUid = event.optionalString("targetUid")?.let(::RemoteAccountUid),
            createdAtMillis = event.requireLong("createdAtMillis"),
        )
    }

private fun parseAccountMutationReceipt(response: Map<*, *>): OwnerAccountMutationReceipt =
    OwnerAccountMutationReceipt(RemoteAccountUid(response.requireString("targetUid")))

private fun verifyDeviceReceipt(
    response: Map<*, *>,
    targetUid: RemoteAccountUid,
    deviceId: RemoteDeviceId,
) {
    if (
        response.requireString("targetUid") != targetUid.raw ||
        response.requireString("deviceId") != deviceId.raw
    ) {
        malformedResponse()
    }
}

private inline fun <reified T : Enum<T>> Map<*, *>.requireEnum(fieldName: String): T =
    requireString(fieldName).let { serialized ->
        enumValues<T>().firstOrNull { candidate -> candidate.name == serialized }
            ?: malformedResponse()
    }

private fun Map<*, *>.requireMapList(fieldName: String): List<Map<*, *>> =
    (this[fieldName] as? List<*>)?.map { entry -> entry as? Map<*, *> ?: malformedResponse() }
        ?: malformedResponse()

private fun Map<*, *>.requireString(fieldName: String): String =
    (this[fieldName] as? String)?.takeIf(String::isNotBlank) ?: malformedResponse()

private fun Map<*, *>.optionalString(fieldName: String): String? = when (val value = this[fieldName]) {
    null -> null
    is String -> value
    else -> malformedResponse()
}

private fun Map<*, *>.requireBoolean(fieldName: String): Boolean =
    this[fieldName] as? Boolean ?: malformedResponse()

private fun Map<*, *>.requireLong(fieldName: String): Long =
    (this[fieldName] as? Number)?.toExactLong() ?: malformedResponse()

private fun Map<*, *>.optionalLong(fieldName: String): Long? = when (val value = this[fieldName]) {
    null -> null
    is Number -> value.toExactLong()
    else -> malformedResponse()
}

private fun Map<*, *>.requireInt(fieldName: String): Int {
    val value = requireLong(fieldName)
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) malformedResponse()
    return value.toInt()
}

private fun Number.toExactLong(): Long {
    val serialized = toDouble()
    val narrowed = toLong()
    if (!serialized.isFinite() || serialized != narrowed.toDouble()) malformedResponse()
    return narrowed
}

private fun malformedResponse(): Nothing =
    throw RemoteChatException("Synapse returned an invalid owner administration response.")

private fun Exception.toOwnerAdminFailure(operation: String): RemoteChatException {
    val message = when ((this as? FirebaseFunctionsException)?.code) {
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> "Owner access is required to $operation."
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Sign in again before trying to $operation."
        FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
            "Confirm owner access again before trying to $operation."
        FirebaseFunctionsException.Code.ALREADY_EXISTS -> "That account or invitation already exists."
        FirebaseFunctionsException.Code.NOT_FOUND -> "The selected owner administration record no longer exists."
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Too many requests. Wait before trying again."
        else -> return toRemoteChatFailure(operation)
    }
    return RemoteChatException(message, this)
}
