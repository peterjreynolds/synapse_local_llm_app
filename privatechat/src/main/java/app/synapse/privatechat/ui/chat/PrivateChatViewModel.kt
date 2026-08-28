package app.synapse.privatechat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateActivitySharingState
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateClientMutationIdFactory
import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMemberSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateSocialGateway
import app.synapse.privatechat.domain.chat.PrivateTypingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Clock

class PrivateChatViewModel(
    private val chatGateway: PrivateChatGateway,
    socialGateway: PrivateSocialGateway,
    mutationIdFactory: PrivateClientMutationIdFactory,
    private val clock: Clock,
) : ViewModel() {
    private val stateStore = PrivateChatUiStateStore()
    private val mutationCoordinator = PrivateConfirmedMutationCoordinator(viewModelScope, stateStore)
    private val expiringContentCoordinator =
        PrivateExpiringContentCoordinator(
            clock = clock,
            coroutineScope = viewModelScope,
            stateStore = stateStore,
        )
    private var activeAccountId: PrivateAccountId? = null
    private var roomFeedJob: Job? = null
    private var conversationJob: Job? = null
    private val activitySharingCoordinator =
        PrivateActivitySharingCoordinator(
            gateway = chatGateway,
            mutationIdFactory = mutationIdFactory,
            coroutineScope = viewModelScope,
            activeAccountId = { activeAccountId },
            selectedRoomId = { stateStore.current.selectedRoomId },
            preferences = ::currentActivitySharingPreferences,
            markRoomRead = ::markRoomRead,
        )
    private val presencePublisher =
        PrivatePresencePublisher(
            gateway = socialGateway,
            mutationIdFactory = mutationIdFactory,
            coroutineScope = viewModelScope,
            stateStore = stateStore,
        )
    private val messageActions =
        PrivateMessageActionCoordinator(
            gateway = chatGateway,
            mutationIdFactory = mutationIdFactory,
            stateStore = stateStore,
            mutationCoordinator = mutationCoordinator,
            activitySharingCoordinator = activitySharingCoordinator,
            activeAccountId = { activeAccountId },
        )
    private val roomActions =
        PrivateRoomActionCoordinator(
            gateway = chatGateway,
            mutationIdFactory = mutationIdFactory,
            clock = clock,
            coroutineScope = viewModelScope,
            stateStore = stateStore,
            mutationCoordinator = mutationCoordinator,
            activitySharingCoordinator = activitySharingCoordinator,
            activeAccountId = { activeAccountId },
        )
    private val socialCoordinator =
        PrivateSocialCoordinator(
            gateway = socialGateway,
            mutationIdFactory = mutationIdFactory,
            clock = clock,
            coroutineScope = viewModelScope,
            stateStore = stateStore,
            mutationCoordinator = mutationCoordinator,
            presencePublisher = presencePublisher,
        )

    val uiState: StateFlow<PrivateChatUiState> = stateStore.state

    fun activateAccount(accountId: PrivateAccountId) {
        if (activeAccountId == accountId) return
        clearSessionResources()
        activeAccountId = accountId
        stateStore.replace(
            PrivateChatUiState(
                session = PrivateChatSessionUiState.Active(accountId),
                social = PrivateSocialUiState.Loading,
                roomFeed = PrivateRoomFeedUiState.Loading,
            ),
        )
        expiringContentCoordinator.activateAccount(accountId)
        socialCoordinator.activateAccount(accountId)
        observeRoomFeed(accountId)
    }

    fun deactivateAccount() {
        if (activeAccountId == null && stateStore.current.session is PrivateChatSessionUiState.SignedOut) return
        clearSessionResources()
        activeAccountId = null
        stateStore.replace(PrivateChatUiState())
    }

    fun enterForeground() {
        expiringContentCoordinator.enterForeground()
        socialCoordinator.enterForeground()
    }

    fun leaveForeground() {
        roomActions.cancelPendingInvitation()
        socialCoordinator.leaveForeground()
        stateStore.update(PrivateChatSnapshotPolicy::clearInvitationSecretsForBackground)
        expiringContentCoordinator.leaveForeground()
    }

    fun selectRoom(roomId: PrivateRoomId) {
        val accountId = activeAccountId ?: return
        val roomFeed = (stateStore.current.roomFeed as? PrivateRoomFeedUiState.Available)?.snapshot ?: return
        if (roomFeed.rooms.none { room -> room.roomId == roomId }) {
            mutationCoordinator.rejectAction("This conversation is no longer available.")
            return
        }
        if (stateStore.current.selectedRoomId == roomId) return

        activitySharingCoordinator.publishTypingStateIfEnabled(PrivateTypingState.INACTIVE)
        conversationJob?.cancel()
        stateStore.update { state ->
            state.copy(
                selectedRoomId = roomId,
                conversation = PrivateConversationUiState.Loading,
                composerText = "",
                composerMode = PrivateComposerMode.NewMessage,
                roomInvitation = PrivateRoomInvitationUiState.Hidden,
                overlay = PrivateChatOverlay.HIDDEN,
            )
        }
        observeConversation(accountId, roomId)
    }

    fun showRoomList() {
        activitySharingCoordinator.publishTypingStateIfEnabled(PrivateTypingState.INACTIVE)
        conversationJob?.cancel()
        conversationJob = null
        stateStore.update { state ->
            state.copy(
                selectedRoomId = null,
                conversation = PrivateConversationUiState.NotSelected,
                composerText = "",
                composerMode = PrivateComposerMode.NewMessage,
                roomInvitation = PrivateRoomInvitationUiState.Hidden,
                overlay = PrivateChatOverlay.HIDDEN,
            )
        }
    }

    fun showProfile() = showOverlay(PrivateChatOverlay.PROFILE)

    fun showCreateConversation() = showOverlay(PrivateChatOverlay.CREATE_CONVERSATION)

    fun showGroupManagement() {
        val conversation = stateStore.current.conversation as? PrivateConversationUiState.Available
        if (conversation?.snapshot?.room?.kind != PrivateRoomKind.GROUP) {
            mutationCoordinator.rejectAction("Member management is available only for groups.")
            return
        }
        showOverlay(PrivateChatOverlay.MANAGE_GROUP)
    }

    fun dismissOverlay() = showOverlay(PrivateChatOverlay.HIDDEN)

    fun updateComposerText(text: String) = messageActions.updateComposerText(text)

    fun beginReply(messageId: PrivateMessageId) = messageActions.beginReply(messageId)

    fun beginEdit(messageId: PrivateMessageId) = messageActions.beginEdit(messageId)

    fun cancelComposerContext() = messageActions.cancelComposerContext()

    fun submitComposer() = messageActions.submitComposer()

    fun toggleReaction(
        messageId: PrivateMessageId,
        reactionInput: String,
    ) = messageActions.toggleReaction(messageId, reactionInput)

    fun deleteMessageForEveryone(messageId: PrivateMessageId) = messageActions.deleteMessageForEveryone(messageId)

    fun changeRetention(retention: PrivateMessageRetention) = roomActions.changeRetention(retention)

    fun setRoomArchived(archiveState: PrivateRoomArchiveState) = roomActions.setRoomArchived(archiveState)

    fun setRoomPinned(pinState: PrivateRoomPinState) = roomActions.setRoomPinned(pinState)

    fun setRoomMuted(muteState: PrivateRoomMuteState) = roomActions.setRoomMuted(muteState)

    fun changeReadReceiptSharing(sharingState: PrivateActivitySharingState) = roomActions.changeReadReceiptSharing(sharingState)

    fun changeTypingIndicatorSharing(sharingState: PrivateActivitySharingState) = roomActions.changeTypingIndicatorSharing(sharingState)

    fun createOneUseRoomInvitation() = roomActions.createOneUseRoomInvitation()

    fun dismissRoomInvitation() = roomActions.dismissRoomInvitation()

    fun saveProfile(displayNameInput: String) = socialCoordinator.saveProfile(displayNameInput)

    fun createRoom(
        kind: PrivateRoomKind,
        titleInput: String,
        retention: PrivateMessageRetention,
    ) = socialCoordinator.createRoom(kind, titleInput, retention)

    fun redeemRoomInvitation(invitationCodeInput: String) = socialCoordinator.redeemRoomInvitation(invitationCodeInput)

    fun changeGroupMemberRole(
        member: PrivateRoomMemberSnapshot,
        role: PrivateRoomMemberRole,
    ) = socialCoordinator.changeGroupMemberRole(member, role)

    fun removeGroupMember(member: PrivateRoomMemberSnapshot) = socialCoordinator.removeGroupMember(member)

    fun changePresenceSharing(sharingState: PrivatePresenceSharingState) = socialCoordinator.changePresenceSharing(sharingState)

    fun createOneUseAccountInvitation() = socialCoordinator.createOneUseAccountInvitation()

    fun dismissAccountInvitation() = socialCoordinator.dismissAccountInvitation()

    fun dismissOperationNotice() = mutationCoordinator.dismissNotice()

    override fun onCleared() {
        clearSessionResources()
        messageActions.clearComposer()
        super.onCleared()
    }

    private fun observeRoomFeed(accountId: PrivateAccountId) {
        roomFeedJob =
            viewModelScope.launch {
                try {
                    chatGateway.observeRoomFeed(accountId).collect { observation ->
                        if (activeAccountId != accountId) return@collect
                        when (observation) {
                            is PrivateChatObservation.Available ->
                                acceptRoomFeedSnapshot(accountId, observation.snapshot)

                            PrivateChatObservation.TransportUnavailable ->
                                stateStore.update(PrivateChatUiReducer::markRoomFeedTransportUnavailable)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (activeAccountId == accountId) {
                        stateStore.update { state -> state.copy(roomFeed = PrivateRoomFeedUiState.UnexpectedFailure) }
                    }
                }
            }
    }

    private fun acceptRoomFeedSnapshot(
        accountId: PrivateAccountId,
        snapshot: app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot,
    ) {
        if (snapshot.accountId != accountId) {
            stateStore.update { state -> state.copy(roomFeed = PrivateRoomFeedUiState.UnexpectedFailure) }
            return
        }
        val sanitizedSnapshot = PrivateChatSnapshotPolicy.sanitizeRoomFeed(snapshot, clock.instant())
        stateStore.update { state -> PrivateChatUiReducer.acceptRoomFeed(state, sanitizedSnapshot) }
        messageActions.acceptRecoveredMutations(sanitizedSnapshot.recoveredMutationIds)
        socialCoordinator.acceptRecoveredMutations(sanitizedSnapshot.recoveredMutationIds)
    }

    private fun observeConversation(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
    ) {
        conversationJob =
            viewModelScope.launch {
                try {
                    chatGateway.observeConversation(accountId, roomId).collect { observation ->
                        if (activeAccountId != accountId || stateStore.current.selectedRoomId != roomId) return@collect
                        when (observation) {
                            is PrivateChatObservation.Available ->
                                acceptConversationSnapshot(accountId, roomId, observation.snapshot)

                            PrivateChatObservation.TransportUnavailable ->
                                stateStore.update { state ->
                                    PrivateChatUiReducer.markConversationTransportUnavailable(state)
                                }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (activeAccountId == accountId && stateStore.current.selectedRoomId == roomId) {
                        stateStore.update { state ->
                            state.copy(conversation = PrivateConversationUiState.UnexpectedFailure)
                        }
                    }
                }
            }
    }

    private fun acceptConversationSnapshot(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
        snapshot: PrivateConversationSnapshot,
    ) {
        if (snapshot.accountId != accountId || snapshot.room.roomId != roomId) {
            stateStore.update { state -> state.copy(conversation = PrivateConversationUiState.UnexpectedFailure) }
            return
        }
        val sanitizedSnapshot = PrivateChatSnapshotPolicy.sanitizeConversation(snapshot, clock.instant())
        stateStore.update { state ->
            PrivateChatUiReducer.acceptConversation(state, sanitizedSnapshot)
        }
        messageActions.acceptRecoveredMutations(sanitizedSnapshot.recoveredMutationIds)
        socialCoordinator.acceptRecoveredMutations(sanitizedSnapshot.recoveredMutationIds)
        activitySharingCoordinator.acknowledgeRoomReadIfEnabled(sanitizedSnapshot.room)
    }

    private fun clearSessionResources() {
        roomFeedJob?.cancel()
        roomFeedJob = null
        conversationJob?.cancel()
        conversationJob = null
        mutationCoordinator.cancelPendingMutation()
        roomActions.cancelPendingInvitation()
        messageActions.resetForAccountTransition()
        activitySharingCoordinator.reset()
        expiringContentCoordinator.deactivateAccount()
        socialCoordinator.deactivateAccount()
    }

    private fun currentActivitySharingPreferences(): PrivateActivitySharingPreferences? =
        (stateStore.current.roomFeed as? PrivateRoomFeedUiState.Available)
            ?.snapshot
            ?.activitySharingPreferences

    private fun markRoomRead(roomId: PrivateRoomId) {
        stateStore.update { state ->
            PrivateChatUiReducer.updatePresentedRoom(state, roomId) { room ->
                room.copy(unreadMessageCount = 0)
            }
        }
    }

    private fun showOverlay(overlay: PrivateChatOverlay) {
        if (stateStore.current.operation is PrivateChatOperationUiState.Running) return
        stateStore.update { state -> state.copy(overlay = overlay) }
    }
}
