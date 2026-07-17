package app.synapse.localllm.domain.remote

data class CreateRemoteInvitationCommand(
    val intendedLabel: String?,
    val lifetimeHours: Int,
    val maximumUses: Int,
)

data class RemoteInvitationCreatedReceipt(
    val invitationId: String,
    val invitationCode: String,
    val expiresAtMillis: Long,
    val maximumUses: Int,
)

interface RemoteInvitationGateway {
    suspend fun createInvitation(command: CreateRemoteInvitationCommand): RemoteInvitationCreatedReceipt
}
