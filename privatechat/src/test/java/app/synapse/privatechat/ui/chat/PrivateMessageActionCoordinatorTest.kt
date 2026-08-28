package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.AcknowledgePrivateRoomReadCommand
import app.synapse.privatechat.domain.chat.ChangePrivateActivitySharingCommand
import app.synapse.privatechat.domain.chat.ChangePrivateReactionCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomPreferencesCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomRetentionCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseRoomInvitationCommand
import app.synapse.privatechat.domain.chat.DeletePrivateMessageForEveryoneCommand
import app.synapse.privatechat.domain.chat.EditPrivateMessageCommand
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateClientMutationIdFactory
import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMemberSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.domain.chat.PublishPrivateTypingStateCommand
import app.synapse.privatechat.domain.chat.SendPrivateMessageCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMessageActionCoordinatorTest {
    @Test
    fun recoveredAmbiguousSendClearsUnchangedDraftBeforeAnotherSendCanStart() =
        runTest {
            val gateway = RecordingUnavailableSendGateway()
            val stateStore = PrivateChatUiStateStore()
            val snapshot = conversationSnapshot()
            stateStore.replace(
                PrivateChatUiState(
                    session = PrivateChatSessionUiState.Active(CURRENT_ACCOUNT_ID),
                    selectedRoomId = ROOM_ID,
                    conversation = PrivateConversationUiState.Available(snapshot),
                    composerText = "Send exactly once",
                ),
            )
            val mutationIdFactory =
                PrivateClientMutationIdFactory {
                    PrivateClientMutationId("00000000-0000-4000-8000-000000000001")
                }
            val mutationCoordinator = PrivateConfirmedMutationCoordinator(this, stateStore)
            val activityCoordinator =
                PrivateActivitySharingCoordinator(
                    gateway = gateway,
                    mutationIdFactory = mutationIdFactory,
                    coroutineScope = this,
                    activeAccountId = { CURRENT_ACCOUNT_ID },
                    selectedRoomId = { ROOM_ID },
                    preferences = { PrivateActivitySharingPreferences() },
                    markRoomRead = {},
                )
            val coordinator =
                PrivateMessageActionCoordinator(
                    gateway = gateway,
                    mutationIdFactory = mutationIdFactory,
                    stateStore = stateStore,
                    mutationCoordinator = mutationCoordinator,
                    activitySharingCoordinator = activityCoordinator,
                    activeAccountId = { CURRENT_ACCOUNT_ID },
                )

            coordinator.submitComposer()
            runCurrent()

            val sentCommand = checkNotNull(gateway.sentCommand)
            assertEquals("Send exactly once", stateStore.current.composerText)
            assertEquals(
                PrivateChatConnectionUiState.RECONNECTING,
                (stateStore.current.conversation as PrivateConversationUiState.Available).connectionState,
            )

            stateStore.update { state ->
                PrivateChatUiReducer.acceptConversation(
                    state,
                    snapshot.copy(recoveredMutationIds = setOf(sentCommand.mutationId)),
                )
            }
            coordinator.acceptRecoveredMutations(setOf(sentCommand.mutationId))

            assertEquals("", stateStore.current.composerText)
            val recovery = stateStore.current.operation as PrivateChatOperationUiState.Recovered
            assertEquals(PrivateChatOperationKind.SEND_MESSAGE, recovery.kind)
            assertEquals(1, gateway.sendCount)
            assertTrue(
                PrivateChatMutationAvailability.connectedConversationSnapshot(stateStore.current) != null,
            )
        }

    @Test
    fun accountTransitionDropsPendingComposerPlaintextAndRecoveryCorrelation() =
        runTest {
            val gateway = RecordingUnavailableSendGateway()
            val stateStore = PrivateChatUiStateStore()
            stateStore.replace(
                PrivateChatUiState(
                    session = PrivateChatSessionUiState.Active(CURRENT_ACCOUNT_ID),
                    selectedRoomId = ROOM_ID,
                    conversation = PrivateConversationUiState.Available(conversationSnapshot()),
                    composerText = "Previous account secret",
                ),
            )
            val mutationId = PrivateClientMutationId("00000000-0000-4000-8000-000000000001")
            val mutationIdFactory = PrivateClientMutationIdFactory { mutationId }
            val coordinator =
                PrivateMessageActionCoordinator(
                    gateway = gateway,
                    mutationIdFactory = mutationIdFactory,
                    stateStore = stateStore,
                    mutationCoordinator = PrivateConfirmedMutationCoordinator(this, stateStore),
                    activitySharingCoordinator =
                        PrivateActivitySharingCoordinator(
                            gateway = gateway,
                            mutationIdFactory = mutationIdFactory,
                            coroutineScope = this,
                            activeAccountId = { CURRENT_ACCOUNT_ID },
                            selectedRoomId = { ROOM_ID },
                            preferences = { PrivateActivitySharingPreferences() },
                            markRoomRead = {},
                        ),
                    activeAccountId = { CURRENT_ACCOUNT_ID },
                )

            coordinator.submitComposer()
            runCurrent()
            coordinator.resetForAccountTransition()
            coordinator.acceptRecoveredMutations(setOf(mutationId))

            assertEquals("", stateStore.current.composerText)
            assertEquals(PrivateComposerMode.NewMessage, stateStore.current.composerMode)
            assertTrue(stateStore.current.operation is PrivateChatOperationUiState.TransportUnavailable)
        }

    private fun conversationSnapshot(): PrivateConversationSnapshot {
        val room =
            PrivateRoomSummary(
                roomId = ROOM_ID,
                kind = PrivateRoomKind.DIRECT,
                title = "Friend",
                participantCount = 2,
                retention = PrivateMessageRetention.ONE_DAY,
                archiveState = PrivateRoomArchiveState.ACTIVE,
                pinState = PrivateRoomPinState.UNPINNED,
                muteState = PrivateRoomMuteState.AUDIBLE,
                unreadMessageCount = 0,
                latestMessagePreview = null,
            )
        return PrivateConversationSnapshot(
            accountId = CURRENT_ACCOUNT_ID,
            room = room,
            members =
                listOf(
                    PrivateRoomMemberSnapshot(CURRENT_ACCOUNT_ID, "Current", PrivateRoomMemberRole.OWNER),
                    PrivateRoomMemberSnapshot(PrivateAccountId("friend"), "Friend", PrivateRoomMemberRole.MEMBER),
                ),
            messages = emptyList(),
            typingParticipants = emptyList(),
        )
    }

    private companion object {
        val CURRENT_ACCOUNT_ID = PrivateAccountId("current")
        val ROOM_ID = PrivateRoomId("room")
    }
}

private class RecordingUnavailableSendGateway : PrivateChatGateway {
    var sendCount: Int = 0
        private set
    var sentCommand: SendPrivateMessageCommand? = null
        private set

    override fun observeRoomFeed(
        accountId: PrivateAccountId,
    ): Flow<PrivateChatObservation<app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot>> = emptyFlow()

    override fun observeConversation(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
    ): Flow<PrivateChatObservation<PrivateConversationSnapshot>> = emptyFlow()

    override suspend fun sendMessage(
        command: SendPrivateMessageCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageSent> {
        sendCount += 1
        sentCommand = command
        return PrivateChatMutationOutcome.TransportUnavailable
    }

    override suspend fun editMessage(
        command: EditPrivateMessageCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageEdited> = error("Unexpected message edit")

    override suspend fun deleteMessageForEveryone(
        command: DeletePrivateMessageForEveryoneCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageDeletedForEveryone> = error("Unexpected message delete")

    override suspend fun changeReaction(
        command: ChangePrivateReactionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ReactionChanged> = error("Unexpected reaction mutation")

    override suspend fun changeRoomRetention(
        command: ChangePrivateRoomRetentionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RetentionChanged> = error("Unexpected retention mutation")

    override suspend fun changeRoomPreferences(
        command: ChangePrivateRoomPreferencesCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomPreferencesChanged> = error("Unexpected preference mutation")

    override suspend fun changeActivitySharing(
        command: ChangePrivateActivitySharingCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ActivitySharingChanged> = error("Unexpected activity mutation")

    override suspend fun acknowledgeRoomRead(
        command: AcknowledgePrivateRoomReadCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomReadAcknowledged> = error("Unexpected read receipt")

    override suspend fun publishTypingState(
        command: PublishPrivateTypingStateCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.TypingStatePublished> = error("Unexpected typing mutation")

    override suspend fun createOneUseRoomInvitation(
        command: CreatePrivateOneUseRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.OneUseRoomInvitationCreated> = error("Unexpected room invitation")
}
