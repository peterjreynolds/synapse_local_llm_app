package app.synapse.localllm.domain.remote

import kotlinx.coroutines.flow.Flow

data class UpdateRemoteProfileCommand(
    val accountUid: RemoteAccountUid,
    val displayName: String,
    val bio: String,
)

data class UploadRemoteAvatarCommand(
    val accountUid: RemoteAccountUid,
    val sourceUri: String,
    val mimeType: String,
)

data class RemoteProfileMutationReceipt(
    val accountUid: RemoteAccountUid,
    val mutation: RemoteProfileMutation,
)

enum class RemoteProfileMutation {
    PROFILE_UPDATED,
    PRESENCE_UPDATED,
    AVATAR_UPDATED,
}

interface RemoteDirectoryGateway {
    fun observeAllowedProfiles(accountUid: RemoteAccountUid): Flow<List<RemoteCachedProfile>>

    suspend fun updateProfile(command: UpdateRemoteProfileCommand): RemoteProfileMutationReceipt

    suspend fun updatePresence(
        accountUid: RemoteAccountUid,
        online: Boolean,
    ): RemoteProfileMutationReceipt

    suspend fun uploadAvatar(command: UploadRemoteAvatarCommand): RemoteProfileMutationReceipt
}
