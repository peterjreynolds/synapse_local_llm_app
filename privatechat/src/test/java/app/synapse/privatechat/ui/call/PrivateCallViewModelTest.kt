package app.synapse.privatechat.ui.call

import androidx.lifecycle.viewModelScope
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.call.PrivateCallAlertGateway
import app.synapse.privatechat.domain.call.PrivateCallEndReason
import app.synapse.privatechat.domain.call.PrivateCallException
import app.synapse.privatechat.domain.call.PrivateCallFailure
import app.synapse.privatechat.domain.call.PrivateCallForegroundGateway
import app.synapse.privatechat.domain.call.PrivateCallMediaGateway
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallMediaState
import app.synapse.privatechat.domain.call.PrivateCallPeerSafetyNumber
import app.synapse.privatechat.domain.call.PrivateCallPoll
import app.synapse.privatechat.domain.call.PrivateCallPreparation
import app.synapse.privatechat.domain.call.PrivateCallRole
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallSignal
import app.synapse.privatechat.domain.call.PrivateCallSignalReceipt
import app.synapse.privatechat.domain.call.PrivateCallSignalingGateway
import app.synapse.privatechat.domain.call.PrivateCallState
import app.synapse.privatechat.domain.call.PrivateReceivedCallSignal
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateCallViewModelTest {
    private val fixtures = mutableListOf<CallUiFixture>()

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun neitherPreparingNorReceivingAnOfferStartsCaptureWithoutConsent() =
        runCallTest {
            val outgoing = fixture()
            outgoing.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VOICE)
            runCurrent()
            assertTrue(outgoing.viewModel.uiState.value is PrivateCallUiState.Consent)
            assertFalse(outgoing.events.contains("media.start"))
            outgoing.viewModel.deactivateAccount()
            runCurrent()

            val incoming = fixture(activate = false)
            incoming.signaling.enqueueIncoming()
            incoming.viewModel.activateAccount(ACCOUNT)
            incoming.viewModel.setForeground(true)
            runCurrent()
            assertTrue(incoming.viewModel.uiState.value is PrivateCallUiState.Consent)
            assertFalse(incoming.events.contains("media.start"))
            assertFalse(incoming.events.contains("accept"))
            incoming.viewModel.deactivateAccount()
            runCurrent()
        }

    @Test
    fun callerPublishesOfferBeforeIceAndQueuesRemoteAnswerUntilMediaReady() =
        runCallTest {
            val fixture = fixture()
            fixture.media.startGate = CompletableDeferred()
            fixture.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VOICE)
            runCurrent()
            fixture.viewModel.startVerifiedDirectCall()
            runCurrent()
            assertEquals(listOf("offer", "ice"), fixture.signaling.published)
            val call = checkNotNull(fixture.signaling.call)
            fixture.signaling.call = call.copy(state = PrivateCallState.ACTIVE, acceptedDeviceId = REMOTE_DEVICE)
            fixture.signaling.signals += received(call, ANSWER, 0)
            fixture.signaling.signals += received(call, ICE, 1)
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(fixture.media.appliedSignals.isEmpty())
            fixture.media.startGate?.complete(Unit)
            runCurrent()
            assertEquals(listOf(ANSWER, ICE), fixture.media.appliedSignals)
            fixture.viewModel.deactivateAccount()
            runCurrent()
        }

    @Test
    fun calleePublishesAnswerBeforeIce() =
        runCallTest {
            val fixture = fixture(activate = false)
            fixture.signaling.enqueueIncoming()
            fixture.viewModel.activateAccount(ACCOUNT)
            fixture.viewModel.setForeground(true)
            runCurrent()
            fixture.viewModel.startVerifiedDirectCall()
            runCurrent()
            assertEquals(listOf("answer", "ice"), fixture.signaling.published)
            assertEquals(OFFER, fixture.media.appliedSignals.first())
            fixture.viewModel.deactivateAccount()
            runCurrent()
        }

    @Test
    fun aPollStartedBeforeCreateCannotEndTheNewCall() =
        runCallTest {
            val fixture = fixture()
            val oldPoll = CompletableDeferred<PrivateCallPoll>()
            fixture.signaling.nextPoll = oldPoll
            advanceTimeBy(2_000)
            runCurrent()
            fixture.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VOICE)
            runCurrent()
            fixture.viewModel.startVerifiedDirectCall()
            runCurrent()
            oldPoll.complete(PrivateCallPoll(emptyList(), emptyList()))
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value is PrivateCallUiState.Ongoing)
            assertFalse(fixture.events.contains("media.stop"))
            fixture.viewModel.deactivateAccount()
            runCurrent()
        }

    @Test
    fun stoppingCapturePrecedesAFailedRemoteEnd() =
        runCallTest {
            val fixture = fixture()
            fixture.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VOICE)
            runCurrent()
            fixture.viewModel.startVerifiedDirectCall()
            runCurrent()
            fixture.signaling.failEnd = true
            fixture.viewModel.hangUp()
            runCurrent()
            assertTrue(fixture.events.indexOf("media.stop") < fixture.events.indexOf("end"))
            assertTrue(fixture.viewModel.uiState.value is PrivateCallUiState.Finished)
            fixture.viewModel.deactivateAccount()
            runCurrent()
        }

    @Test
    fun switchingRequiresMediaReadyAndControlsPreserveConcurrentConnectionState() =
        runCallTest {
            val fixture = fixture()
            fixture.media.startGate = CompletableDeferred()
            fixture.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VIDEO)
            runCurrent()
            fixture.viewModel.startVerifiedDirectCall()
            runCurrent()
            fixture.viewModel.switchCamera()
            runCurrent()
            assertFalse(fixture.events.contains("camera.switch"))
            fixture.media.startGate?.complete(Unit)
            runCurrent()
            fixture.media.microphoneGate = CompletableDeferred()
            fixture.viewModel.toggleMicrophone()
            runCurrent()
            fixture.media.onState?.invoke(PrivateCallMediaState.CONNECTED)
            runCurrent()
            fixture.media.microphoneGate?.complete(Unit)
            runCurrent()
            val current = fixture.viewModel.uiState.value as PrivateCallUiState.Ongoing
            assertTrue(current.microphoneMuted)
            assertEquals(PrivateCallStage.CONNECTED, current.stage)
            fixture.viewModel.deactivateAccount()
            runCurrent()
        }

    @Test
    fun accountCleanupFinishesBeforeTheNextAccountCanPrepareCalls() =
        runCallTest {
            val fixture = fixture()
            fixture.signaling.clearGate = CompletableDeferred()
            fixture.viewModel.activateAccount(PrivateAccountId(REMOTE_ACCOUNT.toString()))
            fixture.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VOICE)
            runCurrent()
            assertEquals(0, fixture.signaling.preparations)
            fixture.signaling.clearGate?.complete(Unit)
            runCurrent()
            fixture.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VOICE)
            runCurrent()
            assertEquals(1, fixture.signaling.preparations)
            fixture.viewModel.deactivateAccount()
            runCurrent()
        }

    @Test
    fun timeoutAndAnswerOnAnotherDeviceStopRinging() =
        runCallTest {
            val timeout = fixture()
            timeout.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VOICE)
            runCurrent()
            timeout.viewModel.startVerifiedDirectCall()
            runCurrent()
            advanceTimeBy(60_001)
            runCurrent()
            assertTrue(timeout.viewModel.uiState.value is PrivateCallUiState.Finished)
            assertTrue(timeout.events.contains("media.stop"))
            timeout.viewModel.deactivateAccount()
            runCurrent()

            val incoming = fixture(activate = false)
            incoming.signaling.enqueueIncoming()
            incoming.viewModel.activateAccount(ACCOUNT)
            incoming.viewModel.setForeground(true)
            runCurrent()
            incoming.signaling.call =
                checkNotNull(incoming.signaling.call).copy(state = PrivateCallState.ACTIVE, acceptedDeviceId = UUID.randomUUID())
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(incoming.viewModel.uiState.value is PrivateCallUiState.Finished)
            assertFalse(incoming.events.contains("media.start"))
            incoming.viewModel.deactivateAccount()
            runCurrent()
        }

    @Test
    fun aTerminalPollStopsRingingWithoutStartingCapture() =
        runCallTest {
            val fixture = fixture(activate = false)
            fixture.signaling.enqueueIncoming()
            fixture.viewModel.activateAccount(ACCOUNT)
            fixture.viewModel.setForeground(true)
            runCurrent()
            assertTrue(fixture.events.contains("ring.incoming"))
            fixture.signaling.call = checkNotNull(fixture.signaling.call).copy(state = PrivateCallState.ENDED, terminalReason = "CANCELLED")
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value is PrivateCallUiState.Finished)
            assertEquals("ring.stop", fixture.events.last { it.startsWith("ring.") })
            assertFalse(fixture.events.contains("media.start"))
        }

    @Test
    fun synchronousSignOutTeardownCannotRestartAnOldAccountsPolling() =
        runCallTest {
            val fixture = fixture()
            fixture.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VOICE)
            runCurrent()
            fixture.viewModel.startVerifiedDirectCall()
            runCurrent()
            fixture.viewModel.deactivateAccount()
            runCurrent()
            val pollsAtSignOut = fixture.signaling.polls
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(pollsAtSignOut, fixture.signaling.polls)
        }

    @Test
    fun rejectedAuthenticatedSignalingStopsCaptureButTransientNetworkLossUsesTheLease() =
        runCallTest {
            val fixture = fixture()
            fixture.viewModel.requestCall(TEST_ROOM, PrivateCallMediaKind.VOICE)
            runCurrent()
            fixture.viewModel.startVerifiedDirectCall()
            runCurrent()
            fixture.signaling.pollFailure = PrivateCallFailure.UNAVAILABLE
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value is PrivateCallUiState.Ongoing)
            assertFalse(fixture.events.contains("media.stop"))
            fixture.signaling.pollFailure = PrivateCallFailure.INVALID_SIGNAL
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value is PrivateCallUiState.Finished)
            assertTrue(fixture.events.contains("media.stop"))
        }

    private fun runCallTest(block: suspend TestScope.() -> Unit) =
        runTest {
            try {
                block()
            } finally {
                fixtures.forEach { it.viewModel.deactivateAccount() }
                runCurrent()
                fixtures.forEach { it.viewModel.viewModelScope.cancel() }
                runCurrent()
                fixtures.clear()
            }
        }

    private fun TestScope.fixture(activate: Boolean = true): CallUiFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock =
            object : Clock() {
                override fun instant(): Instant = BASE_TIME.plusMillis(testScheduler.currentTime)

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this
            }
        return CallUiFixture(clock).also {
            fixtures += it
            it.viewModel.updateRooms(listOf(TEST_ROOM))
            if (activate) {
                it.viewModel.activateAccount(ACCOUNT)
                it.viewModel.setForeground(true)
                runCurrent()
            }
        }
    }
}

private class CallUiFixture(
    clock: Clock,
) {
    val events = mutableListOf<String>()
    val signaling = CallUiSignaling(clock, events)
    val media = CallUiMedia(events)
    val viewModel =
        PrivateCallViewModel(
            signaling,
            media,
            object : PrivateCallAlertGateway {
                override fun startOutgoing(expiresAt: Instant) {
                    events += "ring.outgoing"
                }

                override fun startIncoming(expiresAt: Instant) {
                    events += "ring.incoming"
                }

                override fun stop() {
                    events += "ring.stop"
                }
            },
            object : PrivateCallForegroundGateway {
                override suspend fun start(
                    callId: UUID,
                    mediaKind: PrivateCallMediaKind,
                    onHangUp: () -> Unit,
                ) {
                    events += "foreground.start"
                }

                override fun stop(callId: UUID) {
                    events += "foreground.stop"
                }
            },
            clock,
        )
}

private class CallUiMedia(
    private val events: MutableList<String>,
) : PrivateCallMediaGateway {
    var startGate: CompletableDeferred<Unit>? = null
    var microphoneGate: CompletableDeferred<Unit>? = null
    var onState: ((PrivateCallMediaState) -> Unit)? = null
    private var onSignal: ((PrivateCallMediaSignal) -> Unit)? = null
    val appliedSignals = mutableListOf<PrivateCallMediaSignal>()

    override suspend fun start(
        role: PrivateCallRole,
        mediaKind: PrivateCallMediaKind,
        onSignal: (PrivateCallMediaSignal) -> Unit,
        onState: (PrivateCallMediaState) -> Unit,
    ) {
        events += "media.start"
        this.onSignal = onSignal
        this.onState = onState
        if (role == PrivateCallRole.OFFERER) {
            onSignal(ICE)
            onSignal(OFFER)
        }
        startGate?.await()
    }

    override suspend fun applyRemoteSignal(signal: PrivateCallMediaSignal) {
        appliedSignals += signal
        if (signal is PrivateCallMediaSignal.Offer) {
            onSignal?.invoke(ICE)
            onSignal?.invoke(ANSWER)
        }
    }

    override suspend fun stop() {
        events += "media.stop"
    }

    override suspend fun setMicrophoneMuted(muted: Boolean) {
        microphoneGate?.await()
    }

    override suspend fun setSpeakerEnabled(enabled: Boolean) = Unit

    override suspend fun setCameraEnabled(enabled: Boolean) = Unit

    override suspend fun switchCamera() {
        events += "camera.switch"
    }
}

private class CallUiSignaling(
    private val clock: Clock,
    private val events: MutableList<String>,
) : PrivateCallSignalingGateway {
    var call: PrivateCallSession? = null
    var nextPoll: CompletableDeferred<PrivateCallPoll>? = null
    var clearGate: CompletableDeferred<Unit>? = null
    var failEnd = false
    var pollFailure: PrivateCallFailure? = null
    var preparations = 0
    var polls = 0
    val signals = mutableListOf<PrivateReceivedCallSignal>()
    val published = mutableListOf<String>()

    override suspend fun prepareCall(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
    ): PrivateCallPreparation {
        preparations++
        return PrivateCallPreparation(roomId, 1, SAFETY_NUMBERS)
    }

    override suspend fun createCall(
        accountId: PrivateAccountId,
        callId: UUID,
        roomId: PrivateRoomId,
        membershipEpoch: Int,
        mediaKind: PrivateCallMediaKind,
        offer: PrivateCallMediaSignal.Offer,
    ): PrivateCallSession {
        published += "offer"
        return newSession(callId, mediaKind, incoming = false).also { call = it }
    }

    override suspend fun poll(accountId: PrivateAccountId): PrivateCallPoll {
        polls++
        pollFailure?.let { throw PrivateCallException(it) }
        nextPoll?.let { pending ->
            nextPoll = null
            return pending.await()
        }
        return PrivateCallPoll(listOfNotNull(call), signals.toList()).also { signals.clear() }
    }

    override suspend fun acceptCall(
        accountId: PrivateAccountId,
        callId: UUID,
    ): PrivateCallSession {
        events += "accept"
        return checkNotNull(call).copy(state = PrivateCallState.ACTIVE, acceptedDeviceId = LOCAL_DEVICE).also { call = it }
    }

    override suspend fun sendSignal(
        accountId: PrivateAccountId,
        callId: UUID,
        signal: PrivateCallMediaSignal,
    ): PrivateCallSignalReceipt {
        published +=
            when (signal) {
                is PrivateCallMediaSignal.Answer -> "answer"
                else -> "ice"
            }
        return PrivateCallSignalReceipt(callId, UUID.randomUUID(), published.lastIndex, clock.instant(), clock.instant().plusSeconds(60))
    }

    override suspend fun heartbeat(
        accountId: PrivateAccountId,
        callId: UUID,
    ): PrivateCallSession = checkNotNull(call)

    override suspend fun endCall(
        accountId: PrivateAccountId,
        callId: UUID,
        reason: PrivateCallEndReason,
    ): PrivateCallSession {
        events += "end"
        if (failEnd) error("Backend unavailable")
        return checkNotNull(call).copy(state = PrivateCallState.ENDED, terminalReason = reason.name).also { call = it }
    }

    override suspend fun safetyNumbers(
        accountId: PrivateAccountId,
        callId: UUID,
    ) = SAFETY_NUMBERS

    override suspend fun clearEphemeralState() {
        clearGate?.await()
        events += "clear"
    }

    fun enqueueIncoming() {
        val incoming = newSession(UUID.randomUUID(), PrivateCallMediaKind.VOICE, incoming = true)
        call = incoming
        signals += received(incoming, OFFER, 0)
    }

    private fun newSession(
        id: UUID,
        mediaKind: PrivateCallMediaKind,
        incoming: Boolean,
    ) = PrivateCallSession(
        id,
        TEST_ROOM.roomId,
        1,
        if (incoming) REMOTE_ACCOUNT else LOCAL_ACCOUNT,
        if (incoming) REMOTE_DEVICE else LOCAL_DEVICE,
        if (incoming) LOCAL_ACCOUNT else REMOTE_ACCOUNT,
        null,
        mediaKind,
        PrivateCallState.RINGING,
        null,
        clock.instant(),
        clock.instant().plusSeconds(60),
        clock.instant().plusSeconds(60),
    )
}

private fun received(
    call: PrivateCallSession,
    signal: PrivateCallMediaSignal,
    sequence: Int,
) = PrivateReceivedCallSignal(
    call.callId,
    REMOTE_DEVICE,
    sequence,
    call.ringExpiresAt,
    PrivateCallSignal.Media(signal),
)

private val LOCAL_ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111")
private val LOCAL_DEVICE = UUID.fromString("22222222-2222-4222-8222-222222222222")
private val REMOTE_ACCOUNT = UUID.fromString("33333333-3333-4333-8333-333333333333")
private val REMOTE_DEVICE = UUID.fromString("44444444-4444-4444-8444-444444444444")
private val ACCOUNT = PrivateAccountId(LOCAL_ACCOUNT.toString())
private val BASE_TIME = Instant.parse("2026-09-20T07:00:00Z")
private val SAFETY_NUMBERS = listOf(PrivateCallPeerSafetyNumber(REMOTE_ACCOUNT, REMOTE_DEVICE, "12345 ".repeat(12).trim()))
private val OFFER = PrivateCallMediaSignal.Offer("authenticated test offer")
private val ANSWER = PrivateCallMediaSignal.Answer("authenticated test answer")
private val ICE = PrivateCallMediaSignal.IceCandidate("audio", 0, "candidate:1 1 udp 123 192.0.2.1 5000 typ host")
private val TEST_ROOM =
    PrivateRoomSummary(
        PrivateRoomId("55555555-5555-4555-8555-555555555555"),
        PrivateRoomKind.DIRECT,
        "Partner",
        2,
        PrivateMessageRetention.ONE_HOUR,
        PrivateRoomArchiveState.ACTIVE,
        PrivateRoomPinState.UNPINNED,
        PrivateRoomMuteState.AUDIBLE,
        0,
        null,
    )
