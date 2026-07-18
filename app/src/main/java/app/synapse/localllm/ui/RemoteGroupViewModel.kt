package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.remote.CreateRemoteGroupRoomCommand
import app.synapse.localllm.domain.remote.DeleteRemoteGroupRoomCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteGroupGateway
import app.synapse.localllm.domain.remote.RemoteGroupRoomDetails
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.remote.RenameRemoteGroupRoomCommand
import app.synapse.localllm.domain.remote.SetRemoteGroupAvatarCommand
import app.synapse.localllm.domain.remote.SetRemoteGroupMemberRoleCommand
import app.synapse.localllm.domain.remote.UpdateRemoteGroupMemberCommand
import app.synapse.localllm.domain.remote.UpdateRemoteGroupMembersCommand
import app.synapse.localllm.domain.remote.UpdateRemoteGroupPreferencesCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteGroupUiState(
    val accountUid: RemoteAccountUid? = null,
    val activeRoomId: RemoteRoomId? = null,
    val details: RemoteGroupRoomDetails? = null,
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val roomToOpen: RemoteRoomId? = null,
    val roomToClose: RemoteRoomId? = null,
    val notice: String? = null,
)

class RemoteGroupViewModel(
    private val authenticationGateway: RemoteAuthenticationGateway,
    private val groupGateway: RemoteGroupGateway,
    private val sessionController: RemoteAccountSessionController,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RemoteGroupUiState())
    private var groupActionJob: Job? = null
    private var groupLoadJob: Job? = null
    val uiState: StateFlow<RemoteGroupUiState> = mutableUiState

    init {
        observeAuthentication()
    }

    fun createGroup(
        title: String,
        memberUids: Set<RemoteProfileUid>,
    ) = launchGroupAction("Group created.") { accountUid ->
        val receipt = groupGateway.createGroupRoom(
            CreateRemoteGroupRoomCommand(accountUid, title, memberUids),
        )
        val details = groupGateway.getGroupRoomDetails(accountUid, receipt.roomId)
        updateForAccount(accountUid) { state ->
            state.copy(
                activeRoomId = receipt.roomId,
                details = details,
                roomToOpen = receipt.roomId,
            )
        }
    }

    fun loadGroupDetails(roomId: RemoteRoomId) {
        val accountUid = mutableUiState.value.accountUid ?: return
        groupLoadJob?.cancel()
        groupLoadJob = viewModelScope.launch {
            updateForAccount(accountUid) { state ->
                state.copy(activeRoomId = roomId, details = null, isLoading = true, notice = null)
            }
            try {
                awaitActiveSession(accountUid)
                val details = groupGateway.getGroupRoomDetails(accountUid, roomId)
                updateForAccount(accountUid) { state ->
                    state.copy(activeRoomId = roomId, details = details, isLoading = false)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateForAccount(accountUid) { state ->
                    state.copy(
                        activeRoomId = roomId,
                        details = null,
                        isLoading = false,
                        notice = exception.toGroupNotice("Could not load the group details."),
                    )
                }
            }
        }
    }

    fun addMembers(
        roomId: RemoteRoomId,
        memberUids: Set<RemoteProfileUid>,
    ) = mutateGroup(roomId, "Group members added.") { accountUid ->
        groupGateway.addGroupMembers(UpdateRemoteGroupMembersCommand(accountUid, roomId, memberUids))
    }

    fun removeMember(
        roomId: RemoteRoomId,
        targetUid: RemoteProfileUid,
    ) = mutateGroup(roomId, "Group member removed.") { accountUid ->
        groupGateway.removeGroupMember(UpdateRemoteGroupMemberCommand(accountUid, roomId, targetUid))
    }

    fun setMemberRole(
        roomId: RemoteRoomId,
        targetUid: RemoteProfileUid,
        role: RemoteRoomMemberRole,
    ) = mutateGroup(roomId, "Group member role updated.") { accountUid ->
        groupGateway.setGroupMemberRole(
            SetRemoteGroupMemberRoleCommand(accountUid, roomId, targetUid, role),
        )
    }

    fun transferOwnership(
        roomId: RemoteRoomId,
        targetUid: RemoteProfileUid,
    ) = mutateGroup(roomId, "Group ownership transferred.") { accountUid ->
        groupGateway.transferGroupOwnership(UpdateRemoteGroupMemberCommand(accountUid, roomId, targetUid))
    }

    fun renameGroup(
        roomId: RemoteRoomId,
        title: String,
    ) = mutateGroup(roomId, "Group renamed.") { accountUid ->
        groupGateway.renameGroupRoom(RenameRemoteGroupRoomCommand(accountUid, roomId, title))
    }

    fun setGroupAvatar(
        roomId: RemoteRoomId,
        sourceUri: String,
        mimeType: String,
    ) = mutateGroup(roomId, "Group photo updated.") { accountUid ->
        groupGateway.setGroupAvatar(
            SetRemoteGroupAvatarCommand(
                accountUid = accountUid,
                roomId = roomId,
                sourceUri = sourceUri,
                mimeType = mimeType,
                previousAvatarObjectPath = requireDetails(roomId).avatarObjectPath,
            ),
        )
    }

    fun clearGroupAvatar(roomId: RemoteRoomId) =
        mutateGroup(roomId, "Group photo removed.") { accountUid ->
            groupGateway.clearGroupAvatar(
                accountUid = accountUid,
                roomId = roomId,
                previousAvatarObjectPath = requireDetails(roomId).avatarObjectPath,
            )
        }

    fun updatePreferences(
        roomId: RemoteRoomId,
        isArchived: Boolean,
        isMuted: Boolean,
        isPinned: Boolean,
    ) = mutateGroup(roomId, "Group preferences saved.") { accountUid ->
        groupGateway.updateGroupPreferences(
            UpdateRemoteGroupPreferencesCommand(
                accountUid = accountUid,
                roomId = roomId,
                isArchived = isArchived,
                isMuted = isMuted,
                isPinned = isPinned,
            ),
        )
    }

    fun leaveGroup(roomId: RemoteRoomId) = launchGroupAction("You left the group.") { accountUid ->
        groupGateway.leaveGroupRoom(accountUid, roomId)
        updateForAccount(accountUid) { state ->
            state.copy(activeRoomId = null, details = null, roomToClose = roomId)
        }
    }

    fun deleteGroup(
        roomId: RemoteRoomId,
        currentPassword: String,
        confirmTitle: String,
    ) = launchGroupAction("Group deleted.") { accountUid ->
        val details = requireDetails(roomId)
        require(details.currentMemberRole == RemoteRoomMemberRole.OWNER) {
            "Only the group owner can delete this group."
        }
        require(confirmTitle.trim() == details.title) { "Type the group name exactly to confirm deletion." }
        require(currentPassword.isNotEmpty()) { "Current password cannot be empty." }
        authenticationGateway.reauthenticate(currentPassword)
        groupGateway.deleteGroupRoom(
            DeleteRemoteGroupRoomCommand(accountUid, roomId, confirmTitle),
        )
        updateForAccount(accountUid) { state ->
            state.copy(activeRoomId = null, details = null, roomToClose = roomId)
        }
    }

    fun consumeNavigation() {
        mutableUiState.update { state -> state.copy(roomToOpen = null, roomToClose = null) }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun mutateGroup(
        roomId: RemoteRoomId,
        successNotice: String,
        mutation: suspend (RemoteAccountUid) -> Unit,
    ) = launchGroupAction(successNotice) { accountUid ->
        mutation(accountUid)
        val details = groupGateway.getGroupRoomDetails(accountUid, roomId)
        updateForAccount(accountUid) { state ->
            state.copy(activeRoomId = roomId, details = details)
        }
    }

    private fun launchGroupAction(
        successNotice: String,
        action: suspend (RemoteAccountUid) -> Unit,
    ) {
        val accountUid = mutableUiState.value.accountUid ?: return
        if (mutableUiState.value.isActionRunning) return
        val launchedJob = viewModelScope.launch {
            updateForAccount(accountUid) { state -> state.copy(isActionRunning = true, notice = null) }
            try {
                awaitActiveSession(accountUid)
                action(accountUid)
                updateForAccount(accountUid) { state ->
                    state.copy(isActionRunning = false, notice = successNotice)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateForAccount(accountUid) { state ->
                    state.copy(
                        isActionRunning = false,
                        notice = exception.toGroupNotice("Group operation failed."),
                    )
                }
            }
        }
        groupActionJob = launchedJob
        launchedJob.invokeOnCompletion {
            if (groupActionJob === launchedJob) groupActionJob = null
        }
    }

    private fun observeAuthentication() {
        viewModelScope.launch {
            authenticationGateway.authenticationState.collectLatest { authenticationState ->
                groupActionJob?.cancel()
                groupActionJob = null
                groupLoadJob?.cancel()
                groupLoadJob = null
                val account = (authenticationState as? RemoteAuthenticationState.SignedIn)?.account
                val activeAccount = account?.takeIf { candidate ->
                    candidate.state == RemoteAccountState.ACTIVE && !candidate.mustChangePassword
                }
                mutableUiState.value = RemoteGroupUiState(accountUid = activeAccount?.accountUid)
            }
        }
    }

    private suspend fun awaitActiveSession(accountUid: RemoteAccountUid) {
        sessionController.activeSession
            .filter { token -> token?.accountUid == accountUid }
            .first()
    }

    private fun requireDetails(roomId: RemoteRoomId): RemoteGroupRoomDetails =
        mutableUiState.value.details?.takeIf { details -> details.roomId == roomId }
            ?: throw RemoteChatException("Reload the group details before trying this action.")

    private fun updateForAccount(
        accountUid: RemoteAccountUid,
        transform: (RemoteGroupUiState) -> RemoteGroupUiState,
    ) {
        mutableUiState.update { state ->
            if (state.accountUid == accountUid) transform(state) else state
        }
    }
}

private fun Exception.toGroupNotice(fallback: String): String =
    (this as? RemoteChatException)?.userMessage ?: message ?: fallback

class RemoteGroupViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RemoteGroupViewModel::class.java)) {
            return modelClass.cast(
                RemoteGroupViewModel(
                    authenticationGateway = graph.remoteAuthenticationGateway,
                    groupGateway = graph.remoteGroupGateway,
                    sessionController = graph.remoteAccountSessionController,
                ),
            ) ?: throw IllegalArgumentException("Unable to create RemoteGroupViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
