package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.ChangePrivateActivitySharingCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomPreferencesCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomRetentionCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseRoomInvitationCommand
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateActivitySharingState
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateClientMutationIdFactory
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.domain.chat.PrivateTypingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivateRoomActionCoordinator(
    private val gateway: PrivateChatGateway,
    private val mutationIdFactory: PrivateClientMutationIdFactory,
    private val clock: Clock,
    private val coroutineScope: CoroutineScope,
    private val stateStore: PrivateChatUiStateStore,
    private val mutationCoordinator: PrivateConfirmedMutationCoordinator,
    private val activitySharingCoordinator: PrivateActivitySharingCoordinator,
    private val activeAccountId: () -> PrivateAccountId?,
) {
    private val invitationInFlight = AtomicBoolean(false)
    private var invitationJob: Job? = null

    fun changeRetention(retention: PrivateMessageRetention) {
        val accountId = activeAccountId() ?: return
        val room = selectedRoom() ?: return
        if (room.retention == retention) return
        val command =
            ChangePrivateRoomRetentionCommand(
                accountId = accountId,
                roomId = room.roomId,
                mutationId = mutationIdFactory.createMutationId(),
                retention = retention,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.CHANGE_RETENTION,
            request = { gateway.changeRoomRetention(command) },
            receiptMatches = { receipt -> PrivateChatReceiptValidator.matches(receipt, command) },
            onConfirmed = { receipt -> updatePresentedRoom { current -> current.copy(retention = receipt.retention) } },
        )
    }

    fun setRoomArchived(archiveState: PrivateRoomArchiveState) {
        changeRoomPreferences { room -> room.copy(archiveState = archiveState) }
    }

    fun setRoomPinned(pinState: PrivateRoomPinState) {
        changeRoomPreferences { room -> room.copy(pinState = pinState) }
    }

    fun setRoomMuted(muteState: PrivateRoomMuteState) {
        changeRoomPreferences { room -> room.copy(muteState = muteState) }
    }

    fun changeReadReceiptSharing(sharingState: PrivateActivitySharingState) {
        val preferences = currentActivitySharingPreferences() ?: return
        changeActivitySharing(preferences.copy(readReceipts = sharingState))
    }

    fun changeTypingIndicatorSharing(sharingState: PrivateActivitySharingState) {
        val preferences = currentActivitySharingPreferences() ?: return
        changeActivitySharing(preferences.copy(typingIndicators = sharingState))
    }

    fun createOneUseRoomInvitation() {
        val accountId = activeAccountId() ?: return
        val room = selectedRoom() ?: return
        if (room.kind == PrivateRoomKind.DIRECT && room.participantCount == 2) {
            stateStore.update { state ->
                state.copy(
                    roomInvitation =
                        PrivateRoomInvitationUiState.Rejected(
                            "This direct conversation already has its peer.",
                        ),
                )
            }
            return
        }
        if (!invitationInFlight.compareAndSet(false, true)) return
        val command =
            CreatePrivateOneUseRoomInvitationCommand(
                accountId = accountId,
                roomId = room.roomId,
                mutationId = mutationIdFactory.createMutationId(),
            )
        stateStore.update { state -> state.copy(roomInvitation = PrivateRoomInvitationUiState.Creating) }
        invitationJob =
            coroutineScope.launch {
                val invitationState = requestRoomInvitation(command)
                invitationInFlight.set(false)
                if (activeAccountId() == accountId && stateStore.current.selectedRoomId == room.roomId) {
                    stateStore.update { state -> state.copy(roomInvitation = invitationState) }
                }
            }
    }

    fun dismissRoomInvitation() {
        if (invitationInFlight.get()) return
        stateStore.update { state -> state.copy(roomInvitation = PrivateRoomInvitationUiState.Hidden) }
    }

    fun cancelPendingInvitation() {
        invitationJob?.cancel()
        invitationJob = null
        invitationInFlight.set(false)
    }

    private suspend fun requestRoomInvitation(command: CreatePrivateOneUseRoomInvitationCommand): PrivateRoomInvitationUiState =
        try {
            when (val outcome = gateway.createOneUseRoomInvitation(command)) {
                is PrivateChatMutationOutcome.Confirmed -> {
                    val receipt = outcome.receipt
                    if (
                        PrivateChatReceiptValidator.matches(receipt, command) &&
                        receipt.expiresAt.isAfter(clock.instant())
                    ) {
                        PrivateRoomInvitationUiState.Confirmed(receipt)
                    } else {
                        PrivateRoomInvitationUiState.UnexpectedFailure
                    }
                }

                is PrivateChatMutationOutcome.Rejected ->
                    PrivateRoomInvitationUiState.Rejected(outcome.userMessage)

                PrivateChatMutationOutcome.TransportUnavailable ->
                    PrivateRoomInvitationUiState.TransportUnavailable
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PrivateRoomInvitationUiState.UnexpectedFailure
        }

    private fun changeRoomPreferences(transform: (PrivateRoomSummary) -> PrivateRoomSummary) {
        val accountId = activeAccountId() ?: return
        val room = selectedRoom() ?: return
        val changedRoom = transform(room)
        if (changedRoom == room) return
        val command =
            ChangePrivateRoomPreferencesCommand(
                accountId = accountId,
                roomId = room.roomId,
                mutationId = mutationIdFactory.createMutationId(),
                archiveState = changedRoom.archiveState,
                pinState = changedRoom.pinState,
                muteState = changedRoom.muteState,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.CHANGE_ROOM_PREFERENCES,
            request = { gateway.changeRoomPreferences(command) },
            receiptMatches = { receipt -> PrivateChatReceiptValidator.matches(receipt, command) },
            onConfirmed = { receipt ->
                updatePresentedRoom { currentRoom ->
                    currentRoom.copy(
                        archiveState = receipt.archiveState,
                        pinState = receipt.pinState,
                        muteState = receipt.muteState,
                    )
                }
            },
        )
    }

    private fun changeActivitySharing(preferences: PrivateActivitySharingPreferences) {
        val accountId = activeAccountId() ?: return
        val currentPreferences = currentActivitySharingPreferences() ?: return
        if (currentPreferences == preferences) return
        val command =
            ChangePrivateActivitySharingCommand(
                accountId = accountId,
                mutationId = mutationIdFactory.createMutationId(),
                preferences = preferences,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.CHANGE_ACTIVITY_SHARING,
            request = { gateway.changeActivitySharing(command) },
            receiptMatches = { receipt -> PrivateChatReceiptValidator.matches(receipt, command) },
            onConfirmed = { receipt -> acceptActivitySharingReceipt(receipt.preferences) },
        )
    }

    private fun acceptActivitySharingReceipt(preferences: PrivateActivitySharingPreferences) {
        stateStore.update { state ->
            val roomFeed = state.roomFeed as? PrivateRoomFeedUiState.Available ?: return@update state
            state.copy(
                roomFeed =
                    PrivateRoomFeedUiState.Available(
                        roomFeed.snapshot.copy(activitySharingPreferences = preferences),
                    ),
            )
        }
        if (preferences.typingIndicators == PrivateActivitySharingState.DISABLED) {
            activitySharingCoordinator.publishTypingStateIfEnabled(PrivateTypingState.INACTIVE, force = true)
        }
        if (preferences.readReceipts == PrivateActivitySharingState.ENABLED) {
            selectedRoom()?.let(activitySharingCoordinator::acknowledgeRoomReadIfEnabled)
        }
    }

    private fun currentActivitySharingPreferences(): PrivateActivitySharingPreferences? =
        (stateStore.current.roomFeed as? PrivateRoomFeedUiState.Available)
            ?.snapshot
            ?.activitySharingPreferences

    private fun selectedRoom(): PrivateRoomSummary? = PrivateChatUiReducer.selectedRoom(stateStore.current)

    private fun updatePresentedRoom(transform: (PrivateRoomSummary) -> PrivateRoomSummary) {
        val selectedRoomId = stateStore.current.selectedRoomId ?: return
        stateStore.update { state ->
            PrivateChatUiReducer.updatePresentedRoom(state, selectedRoomId, transform)
        }
    }
}
