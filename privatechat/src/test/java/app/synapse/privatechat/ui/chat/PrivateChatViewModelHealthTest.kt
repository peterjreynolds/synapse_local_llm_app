package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.data.chat.PendingTransportPrivateChatGateway
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateDirectConversationReceipt
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivatePeopleGateway
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMemberSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.domain.chat.UnavailablePrivatePeopleGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateChatViewModelHealthTest {
    @Test
    fun peopleChatWaitsForConfirmedMembershipAndOnlyMessageFailuresReconnect() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val feeds = MutableSharedFlow<PrivateChatObservation<PrivateRoomFeedSnapshot>>(replay = 1)
            val conversations = MutableSharedFlow<PrivateChatObservation<PrivateConversationSnapshot>>(replay = 1)
            val chat =
                object : PrivateChatGateway by PendingTransportPrivateChatGateway {
                    override fun observeRoomFeed(accountId: PrivateAccountId) = feeds

                    override fun observeConversation(
                        accountId: PrivateAccountId,
                        roomId: PrivateRoomId,
                    ) = conversations
                }
            val people =
                object : PrivatePeopleGateway by UnavailablePrivatePeopleGateway {
                    override suspend fun openDirectConversation(
                        accountId: PrivateAccountId,
                        targetAccountId: PrivateAccountId,
                    ) = PrivateChatMutationOutcome.Confirmed(PrivateDirectConversationReceipt(accountId, targetAccountId, ROOM.roomId))
                }
            val vm =
                PrivateChatViewModel(
                    chat,
                    PendingTransportPrivateChatGateway,
                    { PrivateClientMutationId("mutation") },
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    people,
                )
            try {
                vm.activateAccount(ACTOR)
                vm.enterForeground()
                feeds.emit(
                    PrivateChatObservation.Available(PrivateRoomFeedSnapshot(ACTOR, emptyList(), PrivateActivitySharingPreferences())),
                )
                runCurrent()
                assertEquals(PrivatePeopleAvailability.UNAVAILABLE, vm.peopleState.value.availability)
                assertEquals(
                    PrivateChatConnectionUiState.CONNECTED,
                    (vm.uiState.value.roomFeed as PrivateRoomFeedUiState.Available).connectionState,
                )
                vm.openDirectChat(PEER)
                runCurrent()
                assertNull(vm.uiState.value.selectedRoomId)
                val feed = PrivateRoomFeedSnapshot(ACTOR, listOf(ROOM), PrivateActivitySharingPreferences())
                feeds.emit(PrivateChatObservation.Available(feed))
                runCurrent()
                assertEquals(ROOM.roomId, vm.uiState.value.selectedRoomId)
                val conversation =
                    PrivateConversationSnapshot(
                        ACTOR,
                        ROOM,
                        listOf(
                            PrivateRoomMemberSnapshot(ACTOR, "Me", PrivateRoomMemberRole.OWNER),
                            PrivateRoomMemberSnapshot(PEER, "Peer", PrivateRoomMemberRole.MEMBER),
                        ),
                        emptyList(),
                        emptyList(),
                    )
                conversations.emit(PrivateChatObservation.Available(conversation))
                runCurrent()
                feeds.emit(PrivateChatObservation.TransportUnavailable)
                conversations.emit(PrivateChatObservation.TransportUnavailable)
                runCurrent()
                assertEquals(
                    PrivateChatConnectionUiState.RECONNECTING,
                    (vm.uiState.value.roomFeed as PrivateRoomFeedUiState.Available).connectionState,
                )
                assertEquals(conversation, (vm.uiState.value.conversation as PrivateConversationUiState.Available).snapshot)
                assertEquals(
                    PrivateChatConnectionUiState.RECONNECTING,
                    (vm.uiState.value.conversation as PrivateConversationUiState.Available).connectionState,
                )
                feeds.emit(PrivateChatObservation.Available(feed))
                conversations.emit(PrivateChatObservation.Available(conversation))
                runCurrent()
                assertEquals(
                    PrivateChatConnectionUiState.CONNECTED,
                    (vm.uiState.value.roomFeed as PrivateRoomFeedUiState.Available).connectionState,
                )
                assertEquals(
                    PrivateChatConnectionUiState.CONNECTED,
                    (vm.uiState.value.conversation as PrivateConversationUiState.Available).connectionState,
                )
            } finally {
                vm.leaveForeground()
                vm.deactivateAccount()
                Dispatchers.resetMain()
            }
        }

    private companion object {
        val ACTOR = PrivateAccountId("actor")
        val PEER = PrivateAccountId("peer")
        val NOW = Instant.parse("2026-10-04T12:00:00Z")
        val ROOM =
            PrivateRoomSummary(
                PrivateRoomId("direct"),
                PrivateRoomKind.DIRECT,
                "Peer",
                2,
                PrivateMessageRetention.ONE_DAY,
                PrivateRoomArchiveState.ACTIVE,
                PrivateRoomPinState.UNPINNED,
                PrivateRoomMuteState.AUDIBLE,
                0,
                null,
            )
    }
}
