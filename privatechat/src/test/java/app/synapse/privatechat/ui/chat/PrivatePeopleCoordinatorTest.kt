package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateDirectConversationReceipt
import app.synapse.privatechat.domain.chat.PrivateDirectoryPerson
import app.synapse.privatechat.domain.chat.PrivatePeopleGateway
import app.synapse.privatechat.domain.chat.PrivateRoomId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PrivatePeopleCoordinatorTest {
    @Test
    fun activityStartsWithoutPresenceOptInAndStopsInBackgroundOrOnSignOut() =
        runTest {
            val gateway = RecordingPeopleGateway()
            val coordinator = PrivatePeopleCoordinator(gateway, backgroundScope, CLOCK) {}
            coordinator.activateAccount(ACTOR)
            runCurrent()
            assertEquals(0, gateway.publications)
            coordinator.enterForeground()
            runCurrent()
            assertEquals(1, gateway.publications)
            advanceTimeBy(25_001)
            assertEquals(2, gateway.publications)
            coordinator.leaveForeground()
            advanceTimeBy(90_000)
            assertEquals(2, gateway.publications)
            coordinator.enterForeground()
            runCurrent()
            assertEquals(3, gateway.publications)
            coordinator.deactivateAccount()
            advanceTimeBy(90_000)
            assertEquals(3, gateway.publications)
            assertTrue(
                coordinator.state.value.people
                    .isEmpty(),
            )
        }

    @Test
    fun directoryAndHeartbeatFailuresRecoverIndependently() =
        runTest {
            val gateway = RecordingPeopleGateway().apply { unavailable = true }
            val coordinator = PrivatePeopleCoordinator(gateway, backgroundScope, CLOCK) {}
            coordinator.activateAccount(ACTOR)
            coordinator.enterForeground()
            runCurrent()
            assertEquals(PrivatePeopleAvailability.UNAVAILABLE, coordinator.state.value.availability)
            assertEquals(PrivatePeopleAvailability.UNAVAILABLE, coordinator.state.value.activityAvailability)
            gateway.unavailable = false
            advanceTimeBy(25_001)
            assertEquals(PrivatePeopleAvailability.AVAILABLE, coordinator.state.value.availability)
            assertEquals(PrivatePeopleAvailability.AVAILABLE, coordinator.state.value.activityAvailability)
            coordinator.deactivateAccount()
        }

    @Test
    fun failedRefreshDoesNotKeepAnExpiredPersonActive() =
        runTest {
            val clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId) = this

                    override fun instant() = NOW.plusMillis(testScheduler.currentTime)
                }
            val gateway = RecordingPeopleGateway()
            val coordinator = PrivatePeopleCoordinator(gateway, backgroundScope, clock) {}
            coordinator.activateAccount(ACTOR)
            coordinator.enterForeground()
            runCurrent()
            assertTrue(
                coordinator.state.value.people
                    .single()
                    .activeUntil > coordinator.state.value.now,
            )
            gateway.unavailable = true
            advanceTimeBy(61_001)
            assertFalse(
                coordinator.state.value.people
                    .single()
                    .activeUntil > coordinator.state.value.now,
            )
            coordinator.deactivateAccount()
        }

    @Test
    fun chatNavigatesOnlyAfterAMatchingDurableReceipt() =
        runTest {
            val gateway = RecordingPeopleGateway()
            val rooms = mutableListOf<PrivateRoomId>()
            val coordinator = PrivatePeopleCoordinator(gateway, backgroundScope, CLOCK, rooms::add)
            coordinator.activateAccount(ACTOR)
            gateway.wrongTarget = true
            coordinator.openChat(PEER)
            runCurrent()
            assertTrue(rooms.isEmpty())
            assertEquals(PrivateDirectChatUiState.Unavailable, coordinator.state.value.directChat)
            gateway.wrongTarget = false
            coordinator.openChat(PEER)
            runCurrent()
            assertEquals(listOf(ROOM), rooms)
            coordinator.openChat(ACTOR)
            runCurrent()
            assertEquals(listOf(ROOM), rooms)
            coordinator.deactivateAccount()
        }

    private class RecordingPeopleGateway : PrivatePeopleGateway {
        var publications = 0
        var unavailable = false
        var wrongTarget = false

        override suspend fun loadPeople(accountId: PrivateAccountId): PrivateChatObservation<List<PrivateDirectoryPerson>> =
            if (unavailable) {
                PrivateChatObservation.TransportUnavailable
            } else {
                PrivateChatObservation.Available(listOf(PrivateDirectoryPerson(PEER, "Peer", NOW.plusSeconds(60))))
            }

        override suspend fun publishActivity(accountId: PrivateAccountId): PrivateChatMutationOutcome<Instant> {
            publications++
            return if (unavailable) {
                PrivateChatMutationOutcome.TransportUnavailable
            } else {
                PrivateChatMutationOutcome.Confirmed(
                    NOW.plusSeconds(60),
                )
            }
        }

        override suspend fun openDirectConversation(
            accountId: PrivateAccountId,
            targetAccountId: PrivateAccountId,
        ) = PrivateChatMutationOutcome.Confirmed(
            PrivateDirectConversationReceipt(accountId, if (wrongTarget) ACTOR else targetAccountId, ROOM),
        )
    }

    private companion object {
        val ACTOR = PrivateAccountId("81000000-0000-4000-8000-000000000001")
        val PEER = PrivateAccountId("81000000-0000-4000-8000-000000000002")
        val ROOM = PrivateRoomId("84000000-0000-4000-8000-000000000001")
        val NOW = Instant.parse("2026-10-04T12:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
