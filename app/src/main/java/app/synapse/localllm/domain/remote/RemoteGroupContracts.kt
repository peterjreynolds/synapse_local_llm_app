package app.synapse.localllm.domain.remote

import java.time.Instant

data class CreateRemoteGroupRoomCommand(
    val accountUid: RemoteAccountUid,
    val title: String,
    val memberUids: Set<RemoteProfileUid>,
)

data class UpdateRemoteGroupMembersCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val memberUids: Set<RemoteProfileUid>,
)

data class UpdateRemoteGroupMemberCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val targetUid: RemoteProfileUid,
)

data class SetRemoteGroupMemberRoleCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val targetUid: RemoteProfileUid,
    val role: RemoteRoomMemberRole,
)

data class RenameRemoteGroupRoomCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val title: String,
)

data class SetRemoteGroupAvatarCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val sourceUri: String,
    val mimeType: String,
    val previousAvatarObjectPath: String?,
)

data class UpdateRemoteGroupPreferencesCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val isArchived: Boolean,
    val isMuted: Boolean,
    val isPinned: Boolean,
)

data class DeleteRemoteGroupRoomCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val confirmTitle: String,
)

data class RemoteGroupMember(
    val profileUid: RemoteProfileUid,
    val role: RemoteRoomMemberRole,
    val joinedAt: Instant,
)

data class RemoteGroupRoomDetails(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val title: String,
    val avatarObjectPath: String?,
    val avatarUrl: String?,
    val ownerUid: RemoteProfileUid,
    val revision: Long,
    val currentMemberRole: RemoteRoomMemberRole,
    val isArchived: Boolean,
    val isMuted: Boolean,
    val isPinned: Boolean,
    val members: List<RemoteGroupMember>,
)

enum class RemoteGroupMutation {
    CREATED,
    MEMBERS_ADDED,
    MEMBER_REMOVED,
    MEMBER_ROLE_CHANGED,
    OWNERSHIP_TRANSFERRED,
    LEFT,
    RENAMED,
    AVATAR_CHANGED,
    PREFERENCES_UPDATED,
    DELETED,
}

data class RemoteGroupMutationReceipt(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val mutation: RemoteGroupMutation,
    val revision: Long?,
)

interface RemoteGroupGateway {
    suspend fun createGroupRoom(command: CreateRemoteGroupRoomCommand): RemoteGroupMutationReceipt

    suspend fun getGroupRoomDetails(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteGroupRoomDetails

    suspend fun addGroupMembers(command: UpdateRemoteGroupMembersCommand): RemoteGroupMutationReceipt

    suspend fun removeGroupMember(command: UpdateRemoteGroupMemberCommand): RemoteGroupMutationReceipt

    suspend fun setGroupMemberRole(command: SetRemoteGroupMemberRoleCommand): RemoteGroupMutationReceipt

    suspend fun transferGroupOwnership(command: UpdateRemoteGroupMemberCommand): RemoteGroupMutationReceipt

    suspend fun leaveGroupRoom(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteGroupMutationReceipt

    suspend fun renameGroupRoom(command: RenameRemoteGroupRoomCommand): RemoteGroupMutationReceipt

    suspend fun setGroupAvatar(command: SetRemoteGroupAvatarCommand): RemoteGroupMutationReceipt

    suspend fun clearGroupAvatar(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        previousAvatarObjectPath: String?,
    ): RemoteGroupMutationReceipt

    suspend fun updateGroupPreferences(
        command: UpdateRemoteGroupPreferencesCommand,
    ): RemoteGroupMutationReceipt

    suspend fun deleteGroupRoom(command: DeleteRemoteGroupRoomCommand): RemoteGroupMutationReceipt
}
