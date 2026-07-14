package app.synapse.localllm.domain.remote

data class OwnerAccountSummary(
    val accountUid: RemoteAccountUid,
    val usernameNormalized: String,
    val displayName: String,
    val role: RemoteAccountRole,
    val state: RemoteAccountState,
    val mustChangePassword: Boolean,
    val createdAtMillis: Long?,
    val lastSeenAtMillis: Long?,
)

data class OwnerInvitationSummary(
    val invitationId: String,
    val intendedLabel: String?,
    val state: String,
    val maximumUses: Int,
    val remainingUses: Int,
    val expiresAtMillis: Long,
)

data class OwnerDeviceSummary(
    val deviceId: RemoteDeviceId,
    val active: Boolean,
    val updatedAtMillis: Long?,
)

data class OwnerAuditEventSummary(
    val eventId: String,
    val eventType: String,
    val actorUid: RemoteAccountUid,
    val targetUid: RemoteAccountUid?,
    val createdAtMillis: Long,
)

data class CreateOwnerAccountCommand(
    val username: String,
    val displayName: String,
    val temporaryPassword: String,
    val requirePasswordChange: Boolean,
)

data class CreateOwnerInvitationCommand(
    val intendedLabel: String?,
    val lifetimeHours: Int,
    val maximumUses: Int,
)

data class ResetOwnerAccountPasswordCommand(
    val targetUid: RemoteAccountUid,
    val temporaryPassword: String,
    val requirePasswordChange: Boolean,
)

data class OwnerAccountMutationReceipt(
    val targetUid: RemoteAccountUid,
)

data class OwnerInvitationCreatedReceipt(
    val invitationId: String,
    val invitationCode: String,
    val expiresAtMillis: Long,
    val maximumUses: Int,
)

interface OwnerAdminGateway {
    suspend fun listAccounts(searchPrefix: String? = null): List<OwnerAccountSummary>

    suspend fun createAccount(command: CreateOwnerAccountCommand): OwnerAccountMutationReceipt

    suspend fun reviewRegistration(
        targetUid: RemoteAccountUid,
        approve: Boolean,
    ): OwnerAccountMutationReceipt

    suspend fun setAccountEnabled(
        targetUid: RemoteAccountUid,
        enabled: Boolean,
    ): OwnerAccountMutationReceipt

    suspend fun revokeAccountSessions(targetUid: RemoteAccountUid): OwnerAccountMutationReceipt

    suspend fun deleteAccount(
        targetUid: RemoteAccountUid,
        confirmUsername: String,
    ): OwnerAccountMutationReceipt

    suspend fun resetAccountPassword(command: ResetOwnerAccountPasswordCommand): OwnerAccountMutationReceipt

    suspend fun listInvitations(): List<OwnerInvitationSummary>

    suspend fun createInvitation(command: CreateOwnerInvitationCommand): OwnerInvitationCreatedReceipt

    suspend fun revokeInvitation(invitationId: String)

    suspend fun setRegistrationApprovalRequired(required: Boolean)

    suspend fun getRegistrationApprovalRequired(): Boolean

    suspend fun listDevices(targetUid: RemoteAccountUid): List<OwnerDeviceSummary>

    suspend fun removeDevice(
        targetUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    )

    suspend fun sendTestPush(
        targetUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    )

    suspend fun listAuditEvents(limit: Int = 50): List<OwnerAuditEventSummary>
}
