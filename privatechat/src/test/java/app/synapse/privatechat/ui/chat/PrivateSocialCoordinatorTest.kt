package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.ChangePrivateGroupMemberRoleCommand
import app.synapse.privatechat.domain.chat.ChangePrivatePresenceSharingCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseAccountInvitationCommand
import app.synapse.privatechat.domain.chat.CreatePrivateRoomCommand
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateClientMutationIdFactory
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateSocialGateway
import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateSocialSnapshot
import app.synapse.privatechat.domain.chat.PublishPrivatePresenceCommand
import app.synapse.privatechat.domain.chat.RedeemPrivateRoomInvitationCommand
import app.synapse.privatechat.domain.chat.RemovePrivateGroupMemberCommand
import app.synapse.privatechat.domain.chat.UpdatePrivateProfileCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateSocialCoordinatorTest {
    @Test
    fun reconnectingRoomFeedRejectsRoomCreationBeforeCallingGateway() =
        runTest {
            val gateway = RecordingRoomCreationGateway()
            val stateStore =
                activeStateStore(PrivateChatConnectionUiState.RECONNECTING)
            val coordinator = createCoordinator(gateway, stateStore)

            coordinator.createRoom(PrivateRoomKind.DIRECT, "Friend", PrivateMessageRetention.ONE_DAY)

            assertEquals(0, gateway.roomCreationCount)
            val rejection = stateStore.current.operation as PrivateChatOperationUiState.Rejected
            assertEquals(PRIVATE_RECONNECT_BEFORE_MUTATION_MESSAGE, rejection.userMessage)
            coordinator.deactivateAccount()
        }

    @Test
    fun ambiguousRoomCreationMarksFeedReconnectingBeforeRetryCanStart() =
        runTest {
            val gateway = RecordingRoomCreationGateway()
            val stateStore = activeStateStore(PrivateChatConnectionUiState.CONNECTED)
            val coordinator = createCoordinator(gateway, stateStore)

            coordinator.createRoom(PrivateRoomKind.GROUP, "Friends", PrivateMessageRetention.ONE_DAY)
            runCurrent()

            assertEquals(1, gateway.roomCreationCount)
            val roomFeed = stateStore.current.roomFeed as PrivateRoomFeedUiState.Available
            assertEquals(PrivateChatConnectionUiState.RECONNECTING, roomFeed.connectionState)
            assertTrue(stateStore.current.operation is PrivateChatOperationUiState.TransportUnavailable)

            val createdCommand = checkNotNull(gateway.roomCreationCommand)
            val recoveredSnapshot =
                roomFeed.snapshot.copy(recoveredMutationIds = setOf(createdCommand.mutationId))
            stateStore.update { state -> PrivateChatUiReducer.acceptRoomFeed(state, recoveredSnapshot) }
            coordinator.acceptRecoveredMutations(recoveredSnapshot.recoveredMutationIds)

            assertEquals(PrivateChatOverlay.HIDDEN, stateStore.current.overlay)
            val recovery = stateStore.current.operation as PrivateChatOperationUiState.Recovered
            assertEquals(PrivateChatOperationKind.CREATE_ROOM, recovery.kind)
            coordinator.deactivateAccount()
        }

    @Test
    fun accountTransitionDropsPendingRoomCreationRecovery() =
        runTest {
            val gateway = RecordingRoomCreationGateway()
            val stateStore = activeStateStore(PrivateChatConnectionUiState.CONNECTED)
            val coordinator = createCoordinator(gateway, stateStore)

            coordinator.createRoom(PrivateRoomKind.GROUP, "Previous account room", PrivateMessageRetention.ONE_DAY)
            runCurrent()
            val mutationId = checkNotNull(gateway.roomCreationCommand).mutationId

            coordinator.deactivateAccount()
            coordinator.acceptRecoveredMutations(setOf(mutationId))

            assertEquals(PrivateChatOverlay.CREATE_CONVERSATION, stateStore.current.overlay)
            assertTrue(stateStore.current.operation is PrivateChatOperationUiState.TransportUnavailable)
        }

    private fun kotlinx.coroutines.test.TestScope.createCoordinator(
        gateway: PrivateSocialGateway,
        stateStore: PrivateChatUiStateStore,
    ): PrivateSocialCoordinator {
        val mutationIdFactory =
            PrivateClientMutationIdFactory {
                PrivateClientMutationId("00000000-0000-4000-8000-000000000001")
            }
        val mutationCoordinator = PrivateConfirmedMutationCoordinator(this, stateStore)
        val presencePublisher =
            PrivatePresencePublisher(
                gateway = gateway,
                mutationIdFactory = mutationIdFactory,
                coroutineScope = this,
                stateStore = stateStore,
            )
        return PrivateSocialCoordinator(
            gateway = gateway,
            mutationIdFactory = mutationIdFactory,
            clock = Clock.systemUTC(),
            coroutineScope = this,
            stateStore = stateStore,
            mutationCoordinator = mutationCoordinator,
            presencePublisher = presencePublisher,
        ).also { coordinator -> coordinator.activateAccount(CURRENT_ACCOUNT_ID) }
    }

    private fun activeStateStore(connectionState: PrivateChatConnectionUiState): PrivateChatUiStateStore =
        PrivateChatUiStateStore().apply {
            replace(
                PrivateChatUiState(
                    overlay = PrivateChatOverlay.CREATE_CONVERSATION,
                    roomFeed =
                        PrivateRoomFeedUiState.Available(
                            snapshot =
                                PrivateRoomFeedSnapshot(
                                    accountId = CURRENT_ACCOUNT_ID,
                                    rooms = emptyList(),
                                    activitySharingPreferences = PrivateActivitySharingPreferences(),
                                ),
                            connectionState = connectionState,
                        ),
                ),
            )
        }

    private companion object {
        val CURRENT_ACCOUNT_ID = PrivateAccountId("current")
    }
}

private class RecordingRoomCreationGateway : PrivateSocialGateway {
    var roomCreationCount: Int = 0
        private set
    var roomCreationCommand: CreatePrivateRoomCommand? = null
        private set

    override fun observeSocial(accountId: PrivateAccountId): Flow<PrivateChatObservation<PrivateSocialSnapshot>> = emptyFlow()

    override suspend fun createRoom(
        command: CreatePrivateRoomCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomCreated> {
        roomCreationCount += 1
        roomCreationCommand = command
        return PrivateChatMutationOutcome.TransportUnavailable
    }

    override suspend fun updateProfile(
        command: UpdatePrivateProfileCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.ProfileUpdated> = error("Unexpected profile mutation")

    override suspend fun changeGroupMemberRole(
        command: ChangePrivateGroupMemberRoleCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRoleChanged> = error("Unexpected role mutation")

    override suspend fun removeGroupMember(
        command: RemovePrivateGroupMemberCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRemoved> = error("Unexpected member mutation")

    override suspend fun createOneUseAccountInvitation(
        command: CreatePrivateOneUseAccountInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.OneUseAccountInvitationCreated> = error("Unexpected account invitation")

    override suspend fun redeemRoomInvitation(
        command: RedeemPrivateRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomInvitationRedeemed> = error("Unexpected room invitation redemption")

    override suspend fun changePresenceSharing(
        command: ChangePrivatePresenceSharingCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresenceSharingChanged> = error("Unexpected presence mutation")

    override suspend fun publishPresence(
        command: PublishPrivatePresenceCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresencePublished> = error("Unexpected presence publication")
}
