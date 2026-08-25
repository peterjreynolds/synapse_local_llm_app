package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.ChangePrivateGroupMemberRoleCommand
import app.synapse.privatechat.domain.chat.ChangePrivatePresenceSharingCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseAccountInvitationCommand
import app.synapse.privatechat.domain.chat.CreatePrivateRoomCommand
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateClientMutationIdFactory
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMemberSnapshot
import app.synapse.privatechat.domain.chat.PrivateSocialGateway
import app.synapse.privatechat.domain.chat.PrivateSocialTextValidation
import app.synapse.privatechat.domain.chat.RedeemPrivateRoomInvitationCommand
import app.synapse.privatechat.domain.chat.RemovePrivateGroupMemberCommand
import app.synapse.privatechat.domain.chat.UpdatePrivateProfileCommand
import app.synapse.privatechat.domain.chat.parsePrivateRoomInvitationCode
import app.synapse.privatechat.domain.chat.validatePrivateProfileDisplayName
import app.synapse.privatechat.domain.chat.validatePrivateRoomTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivateSocialCoordinator(
    private val gateway: PrivateSocialGateway,
    private val mutationIdFactory: PrivateClientMutationIdFactory,
    private val clock: Clock,
    private val coroutineScope: CoroutineScope,
    private val stateStore: PrivateChatUiStateStore,
    private val mutationCoordinator: PrivateConfirmedMutationCoordinator,
    private val presencePublisher: PrivatePresencePublisher,
) {
    private var activeAccountId: PrivateAccountId? = null
    private var observationJob: Job? = null
    private val accountInvitationInFlight = AtomicBoolean(false)
    private var accountInvitationJob: Job? = null

    fun activateAccount(accountId: PrivateAccountId) {
        if (activeAccountId == accountId && observationJob?.isActive == true) return
        deactivateAccount()
        activeAccountId = accountId
        presencePublisher.activateAccount(accountId)
        stateStore.update { state -> state.copy(social = PrivateSocialUiState.Loading) }
        observationJob =
            coroutineScope.launch {
                try {
                    gateway.observeSocial(accountId).collect { observation ->
                        if (activeAccountId != accountId) return@collect
                        when (observation) {
                            is PrivateChatObservation.Available -> acceptSocialSnapshot(accountId, observation.snapshot)
                            PrivateChatObservation.TransportUnavailable ->
                                stateStore.update { state ->
                                    state.copy(social = PrivateSocialUiState.TransportUnavailable)
                                }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (activeAccountId == accountId) {
                        stateStore.update { state -> state.copy(social = PrivateSocialUiState.UnexpectedFailure) }
                    }
                }
            }
    }

    fun deactivateAccount() {
        observationJob?.cancel()
        observationJob = null
        accountInvitationJob?.cancel()
        accountInvitationJob = null
        accountInvitationInFlight.set(false)
        activeAccountId = null
        presencePublisher.deactivateAccount()
    }

    fun enterForeground() {
        presencePublisher.enterForeground()
    }

    fun leaveForeground() {
        accountInvitationJob?.cancel()
        accountInvitationJob = null
        accountInvitationInFlight.set(false)
        mutationCoordinator.cancelPendingMutation(PrivateChatOperationKind.REDEEM_ROOM_INVITATION)
        presencePublisher.leaveForeground()
    }

    fun saveProfile(displayNameInput: String) {
        val accountId = activeAccountId ?: return
        val acceptedName =
            when (val validation = validatePrivateProfileDisplayName(displayNameInput)) {
                is PrivateSocialTextValidation.Accepted -> validation.normalizedText
                is PrivateSocialTextValidation.Rejected -> {
                    mutationCoordinator.rejectSocialInput(validation.field, validation.userMessage)
                    return
                }
            }
        val command =
            UpdatePrivateProfileCommand(
                accountId = accountId,
                mutationId = mutationIdFactory.createMutationId(),
                displayName = acceptedName,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.UPDATE_PROFILE,
            request = { gateway.updateProfile(command) },
            receiptMatches = { receipt -> PrivateSocialReceiptValidator.matches(receipt, command) },
            onConfirmed = { receipt ->
                stateStore.update { state ->
                    val social = state.social as? PrivateSocialUiState.Available ?: return@update state
                    state.copy(
                        social = PrivateSocialUiState.Available(social.snapshot.copy(profile = receipt.profile)),
                        overlay = PrivateChatOverlay.HIDDEN,
                    )
                }
            },
        )
    }

    fun createRoom(
        kind: PrivateRoomKind,
        titleInput: String,
        retention: PrivateMessageRetention,
    ) {
        val accountId = activeAccountId ?: return
        val acceptedTitle =
            when (val validation = validatePrivateRoomTitle(titleInput)) {
                is PrivateSocialTextValidation.Accepted -> validation.normalizedText
                is PrivateSocialTextValidation.Rejected -> {
                    mutationCoordinator.rejectSocialInput(validation.field, validation.userMessage)
                    return
                }
            }
        val command =
            CreatePrivateRoomCommand(
                accountId = accountId,
                mutationId = mutationIdFactory.createMutationId(),
                kind = kind,
                title = acceptedTitle,
                retention = retention,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.CREATE_ROOM,
            request = { gateway.createRoom(command) },
            receiptMatches = { receipt -> PrivateSocialReceiptValidator.matches(receipt, command) },
            onConfirmed = {
                stateStore.update { state -> state.copy(overlay = PrivateChatOverlay.HIDDEN) }
            },
        )
    }

    fun redeemRoomInvitation(invitationCodeInput: String) {
        val accountId = activeAccountId ?: return
        val invitationCode = parsePrivateRoomInvitationCode(invitationCodeInput)
        if (invitationCode == null) {
            mutationCoordinator.rejectAction("Enter a valid one-use conversation invitation code.")
            return
        }
        val command =
            RedeemPrivateRoomInvitationCommand(
                accountId = accountId,
                mutationId = mutationIdFactory.createMutationId(),
                invitationCode = invitationCode,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.REDEEM_ROOM_INVITATION,
            request = { gateway.redeemRoomInvitation(command) },
            receiptMatches = { receipt -> PrivateSocialReceiptValidator.matches(receipt, command) },
            onConfirmed = {
                stateStore.update { state -> state.copy(overlay = PrivateChatOverlay.HIDDEN) }
            },
        )
    }

    fun changeGroupMemberRole(
        member: PrivateRoomMemberSnapshot,
        role: PrivateRoomMemberRole,
    ) {
        val accountId = activeAccountId ?: return
        val conversation = selectedGroupConversation() ?: return
        if (!currentAccountCanChangeRoles(conversation.snapshot.members, accountId, member)) {
            mutationCoordinator.rejectAction("Only the group owner can change member roles.")
            return
        }
        val command =
            ChangePrivateGroupMemberRoleCommand(
                accountId = accountId,
                roomId = conversation.snapshot.room.roomId,
                mutationId = mutationIdFactory.createMutationId(),
                memberAccountId = member.accountId,
                role = role,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.CHANGE_GROUP_MEMBER_ROLE,
            request = { gateway.changeGroupMemberRole(command) },
            receiptMatches = { receipt -> PrivateSocialReceiptValidator.matches(receipt, command) },
        )
    }

    fun removeGroupMember(member: PrivateRoomMemberSnapshot) {
        val accountId = activeAccountId ?: return
        val conversation = selectedGroupConversation() ?: return
        if (!currentAccountCanRemoveMember(conversation.snapshot.members, accountId, member)) {
            mutationCoordinator.rejectAction("Your group role cannot remove this member.")
            return
        }
        val command =
            RemovePrivateGroupMemberCommand(
                accountId = accountId,
                roomId = conversation.snapshot.room.roomId,
                mutationId = mutationIdFactory.createMutationId(),
                memberAccountId = member.accountId,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.REMOVE_GROUP_MEMBER,
            request = { gateway.removeGroupMember(command) },
            receiptMatches = { receipt -> PrivateSocialReceiptValidator.matches(receipt, command) },
        )
    }

    fun changePresenceSharing(sharingState: PrivatePresenceSharingState) {
        val accountId = activeAccountId ?: return
        val social = stateStore.current.social as? PrivateSocialUiState.Available ?: return
        if (social.snapshot.presenceSharing == sharingState) return
        val command =
            ChangePrivatePresenceSharingCommand(
                accountId = accountId,
                mutationId = mutationIdFactory.createMutationId(),
                sharingState = sharingState,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.CHANGE_PRESENCE_SHARING,
            request = { gateway.changePresenceSharing(command) },
            receiptMatches = { receipt -> PrivateSocialReceiptValidator.matches(receipt, command) },
            onConfirmed = { receipt ->
                stateStore.update { state ->
                    val currentSocial = state.social as? PrivateSocialUiState.Available ?: return@update state
                    state.copy(
                        social =
                            PrivateSocialUiState.Available(
                                currentSocial.snapshot.copy(presenceSharing = receipt.sharingState),
                            ),
                    )
                }
                presencePublisher.updateSharingState(receipt.sharingState)
            },
        )
    }

    fun createOneUseAccountInvitation() {
        val accountId = activeAccountId ?: return
        if (!accountInvitationInFlight.compareAndSet(false, true)) return
        val command =
            CreatePrivateOneUseAccountInvitationCommand(
                accountId = accountId,
                mutationId = mutationIdFactory.createMutationId(),
            )
        stateStore.update { state ->
            state.copy(accountInvitation = PrivateAccountInvitationUiState.Creating)
        }
        accountInvitationJob =
            coroutineScope.launch {
                val invitationState = requestAccountInvitation(command)
                accountInvitationInFlight.set(false)
                if (activeAccountId == accountId) {
                    stateStore.update { state -> state.copy(accountInvitation = invitationState) }
                }
            }
    }

    fun dismissAccountInvitation() {
        if (accountInvitationInFlight.get()) return
        stateStore.update { state -> state.copy(accountInvitation = PrivateAccountInvitationUiState.Hidden) }
    }

    private fun acceptSocialSnapshot(
        accountId: PrivateAccountId,
        snapshot: app.synapse.privatechat.domain.chat.PrivateSocialSnapshot,
    ) {
        if (snapshot.accountId != accountId) {
            stateStore.update { state -> state.copy(social = PrivateSocialUiState.UnexpectedFailure) }
            return
        }
        val sanitizedSnapshot = PrivateChatSnapshotPolicy.sanitizeSocial(snapshot, clock.instant())
        stateStore.update { state -> state.copy(social = PrivateSocialUiState.Available(sanitizedSnapshot)) }
        presencePublisher.updateSharingState(sanitizedSnapshot.presenceSharing)
    }

    private suspend fun requestAccountInvitation(command: CreatePrivateOneUseAccountInvitationCommand): PrivateAccountInvitationUiState =
        try {
            when (val outcome = gateway.createOneUseAccountInvitation(command)) {
                is PrivateChatMutationOutcome.Confirmed -> {
                    val receipt = outcome.receipt
                    if (
                        PrivateSocialReceiptValidator.matches(receipt, command) &&
                        receipt.expiresAt.isAfter(clock.instant())
                    ) {
                        PrivateAccountInvitationUiState.Confirmed(receipt)
                    } else {
                        PrivateAccountInvitationUiState.UnexpectedFailure
                    }
                }

                is PrivateChatMutationOutcome.Rejected ->
                    PrivateAccountInvitationUiState.Rejected(outcome.userMessage)

                PrivateChatMutationOutcome.TransportUnavailable ->
                    PrivateAccountInvitationUiState.TransportUnavailable
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PrivateAccountInvitationUiState.UnexpectedFailure
        }

    private fun selectedGroupConversation(): PrivateConversationUiState.Available? {
        val conversation = stateStore.current.conversation as? PrivateConversationUiState.Available
        if (conversation?.snapshot?.room?.kind != PrivateRoomKind.GROUP) {
            mutationCoordinator.rejectAction("Member management is available only for groups.")
            return null
        }
        return conversation
    }

    private fun currentAccountCanChangeRoles(
        members: List<PrivateRoomMemberSnapshot>,
        accountId: PrivateAccountId,
        target: PrivateRoomMemberSnapshot,
    ): Boolean =
        target.accountId != accountId &&
            target.role != PrivateRoomMemberRole.OWNER &&
            members.firstOrNull { member -> member.accountId == accountId }?.role == PrivateRoomMemberRole.OWNER

    private fun currentAccountCanRemoveMember(
        members: List<PrivateRoomMemberSnapshot>,
        accountId: PrivateAccountId,
        target: PrivateRoomMemberSnapshot,
    ): Boolean {
        if (target.accountId == accountId || target.role == PrivateRoomMemberRole.OWNER) return false
        return when (members.firstOrNull { member -> member.accountId == accountId }?.role) {
            PrivateRoomMemberRole.OWNER -> true
            PrivateRoomMemberRole.ADMIN -> target.role == PrivateRoomMemberRole.MEMBER
            PrivateRoomMemberRole.MEMBER,
            null,
            -> false
        }
    }
}
