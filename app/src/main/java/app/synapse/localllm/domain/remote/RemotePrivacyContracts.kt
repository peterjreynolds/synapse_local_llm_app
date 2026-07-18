package app.synapse.localllm.domain.remote

data class RemotePrivacyState(
    val blockedProfileUids: Set<RemoteProfileUid>,
    val deletionRequestPending: Boolean,
)

data class RemoteBlockMutationReceipt(
    val targetUid: RemoteProfileUid,
    val blocked: Boolean,
)

data class RemoteDeletionRequestReceipt(
    val deletionRequestPending: Boolean,
)

interface RemotePrivacyGateway {
    suspend fun getOwnPrivacyState(): RemotePrivacyState

    suspend fun setUserBlocked(
        targetUid: RemoteProfileUid,
        blocked: Boolean,
    ): RemoteBlockMutationReceipt

    suspend fun requestAccountDeletion(): RemoteDeletionRequestReceipt

    suspend fun cancelAccountDeletionRequest(): RemoteDeletionRequestReceipt
}
