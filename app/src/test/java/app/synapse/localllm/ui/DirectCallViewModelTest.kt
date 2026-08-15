package app.synapse.localllm.ui

import app.synapse.localllm.domain.calling.DirectCallAlertGateway
import app.synapse.localllm.domain.calling.DirectCallForegroundController
import app.synapse.localllm.domain.calling.DirectCallMediaConnectionState
import app.synapse.localllm.domain.calling.DirectCallMediaGateway
import app.synapse.localllm.domain.calling.DirectCallTerminalNotificationStore
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteDirectCallGateway
import app.synapse.localllm.domain.remote.RemoteDirectCallId
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
import app.synapse.localllm.domain.remote.RemoteDirectCallResponse
import app.synapse.localllm.domain.remote.RemoteDirectCallRole
import app.synapse.localllm.domain.remote.RemoteDirectCallSession
import app.synapse.localllm.domain.remote.RemoteDirectCallSignal
import app.synapse.localllm.domain.remote.RemoteDirectCallState
import app.synapse.localllm.domain.remote.RemoteRoomId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class DirectCallViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun bindingAnIdleAccountDoesNotInvokeAndroidCallTeardownBoundaries() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val mediaGateway = RecordingMediaGateway()
        val alertGateway = RecordingAlertGateway()
        val foregroundController = RecordingForegroundController()
        val viewModel = DirectCallViewModel(
            RecordingCallGateway(),
            mediaGateway,
            alertGateway,
            foregroundController,
            RecordingTerminalNotificationStore(),
        )

        viewModel.bindAccount(PETER_UID)
        runCurrent()

        assertEquals(0, mediaGateway.stopCount)
        assertEquals(0, alertGateway.stopCount)
        assertEquals(0, foregroundController.stopCount)
        assertEquals(DirectCallUiPhase.IDLE, viewModel.uiState.value.phase)
    }

    @Test
    fun startsOutgoingCallAndEndsItWithForegroundCleanup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingCallGateway()
        val mediaGateway = RecordingMediaGateway()
        val alertGateway = RecordingAlertGateway()
        val foregroundController = RecordingForegroundController()
        val viewModel = DirectCallViewModel(
            gateway,
            mediaGateway,
            alertGateway,
            foregroundController,
            RecordingTerminalNotificationStore(),
        )
        viewModel.bindAccount(PETER_UID)
        runCurrent()

        viewModel.startCall(ROOM_ID)
        runCurrent()

        assertEquals(DirectCallUiPhase.OUTGOING_RINGING, viewModel.uiState.value.phase)
        assertEquals(CALL_ID, viewModel.uiState.value.session?.callId)
        assertTrue(foregroundController.started)
        assertTrue(alertGateway.ringbackPlaying)

        gateway.session.value = gateway.session.value?.copy(state = RemoteDirectCallState.ACTIVE)
        runCurrent()

        assertEquals(DirectCallUiPhase.ACTIVE, viewModel.uiState.value.phase)
        assertTrue(mediaGateway.started)
        assertTrue(foregroundController.started)
        assertFalse(alertGateway.ringbackPlaying)

        viewModel.endCall()
        runCurrent()

        assertEquals(DirectCallUiPhase.IDLE, viewModel.uiState.value.phase)
        assertFalse(mediaGateway.started)
        assertFalse(foregroundController.started)
        assertEquals(CALL_ID, foregroundController.dismissedCallId)
    }

    @Test
    fun incomingCallRequiresExplicitAcceptBeforeStartingMicrophone() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingCallGateway(
            initialSession = directCallSession(callerUid = TRISH_UID, calleeUid = PETER_UID),
        )
        val mediaGateway = RecordingMediaGateway()
        val alertGateway = RecordingAlertGateway()
        val viewModel = DirectCallViewModel(
            gateway,
            mediaGateway,
            alertGateway,
            RecordingForegroundController(),
            RecordingTerminalNotificationStore(),
        )
        viewModel.bindAccount(PETER_UID)
        runCurrent()

        assertEquals(DirectCallUiPhase.INCOMING_RINGING, viewModel.uiState.value.phase)
        assertFalse(mediaGateway.started)
        assertTrue(alertGateway.incomingRingtonePlaying)

        viewModel.acceptCall()
        runCurrent()

        assertEquals(DirectCallUiPhase.ACTIVE, viewModel.uiState.value.phase)
        assertTrue(mediaGateway.started)
        assertEquals(RemoteDirectCallRole.CALLEE, mediaGateway.role)
        assertFalse(alertGateway.incomingRingtonePlaying)
    }

    @Test
    fun videoCallStartsCameraMediaOnSpeakerAndExposesCameraControls() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingCallGateway()
        val mediaGateway = RecordingMediaGateway()
        val foregroundController = RecordingForegroundController()
        val viewModel = DirectCallViewModel(
            gateway,
            mediaGateway,
            RecordingAlertGateway(),
            foregroundController,
            RecordingTerminalNotificationStore(),
        )
        viewModel.bindAccount(PETER_UID)
        runCurrent()

        viewModel.startCall(ROOM_ID, RemoteDirectCallMediaKind.VIDEO)
        runCurrent()

        assertEquals(DirectCallUiPhase.OUTGOING_RINGING, viewModel.uiState.value.phase)
        assertEquals(
            DirectCallVideoStageMode.OUTGOING_LOCAL_PREVIEW,
            directCallVideoStageMode(viewModel.uiState.value),
        )
        assertTrue(mediaGateway.localVideoPreviewStarted)
        assertEquals(1, mediaGateway.localVideoPreviewStartCount)
        assertFalse(mediaGateway.started)
        assertTrue(viewModel.uiState.value.isCameraEnabled)

        gateway.session.value = gateway.session.value?.copy(state = RemoteDirectCallState.ACTIVE)
        runCurrent()

        assertEquals(DirectCallVideoStageMode.ACCEPTED_CALL_MEDIA, directCallVideoStageMode(viewModel.uiState.value))
        assertFalse(mediaGateway.localVideoPreviewStarted)
        assertEquals(RemoteDirectCallMediaKind.VIDEO, mediaGateway.mediaKind)
        assertEquals(RemoteDirectCallMediaKind.VIDEO, foregroundController.mediaKind)
        assertTrue(viewModel.uiState.value.isCameraEnabled)
        assertTrue(viewModel.uiState.value.isSpeakerEnabled)
        assertTrue(mediaGateway.speakerEnabledValue)

        viewModel.toggleCamera()
        viewModel.switchCamera()

        assertFalse(viewModel.uiState.value.isCameraEnabled)
        assertFalse(mediaGateway.cameraEnabledValue)
        assertEquals(1, mediaGateway.cameraSwitchCount)
    }

    @Test
    fun incomingVideoCallDoesNotOpenTheCameraBeforeAccept() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingCallGateway(
            initialSession = directCallSession(
                callerUid = TRISH_UID,
                calleeUid = PETER_UID,
                mediaKind = RemoteDirectCallMediaKind.VIDEO,
            ),
        )
        val mediaGateway = RecordingMediaGateway()
        val viewModel = DirectCallViewModel(
            gateway,
            mediaGateway,
            RecordingAlertGateway(),
            RecordingForegroundController(),
            RecordingTerminalNotificationStore(),
        )

        viewModel.bindAccount(PETER_UID)
        runCurrent()

        assertEquals(DirectCallUiPhase.INCOMING_RINGING, viewModel.uiState.value.phase)
        assertEquals(DirectCallVideoStageMode.HIDDEN, directCallVideoStageMode(viewModel.uiState.value))
        assertEquals(0, mediaGateway.localVideoPreviewStartCount)
        assertFalse(mediaGateway.started)

        viewModel.acceptCall()
        runCurrent()

        assertTrue(mediaGateway.started)
        assertEquals(RemoteDirectCallRole.CALLEE, mediaGateway.role)
        assertEquals(0, mediaGateway.localVideoPreviewStartCount)
    }

    @Test
    fun deniedOutgoingMicrophonePermissionSurfacesAClosableFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = DirectCallViewModel(
            RecordingCallGateway(),
            RecordingMediaGateway(),
            RecordingAlertGateway(),
            RecordingForegroundController(),
            RecordingTerminalNotificationStore(),
        )

        viewModel.reportMicrophonePermissionDenied()

        assertEquals(DirectCallUiPhase.FAILED, viewModel.uiState.value.phase)
        assertEquals("Microphone permission is required for voice calls.", viewModel.uiState.value.notice)
        viewModel.dismissFailure()
        assertEquals(DirectCallUiPhase.IDLE, viewModel.uiState.value.phase)
    }

    @Test
    fun callerHangupStopsRingbackBeforePublishingCanceledState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingCallGateway()
        val alertGateway = RecordingAlertGateway()
        val terminalStore = RecordingTerminalNotificationStore()
        val viewModel = DirectCallViewModel(
            gateway,
            RecordingMediaGateway(),
            alertGateway,
            RecordingForegroundController(),
            terminalStore,
        )
        viewModel.bindAccount(PETER_UID)
        runCurrent()
        viewModel.startCall(ROOM_ID)
        runCurrent()
        assertTrue(alertGateway.ringbackPlaying)

        viewModel.endCall()
        runCurrent()

        assertFalse(alertGateway.ringbackPlaying)
        assertEquals(RemoteDirectCallState.CANCELED, gateway.session.value?.state)
        assertEquals(DirectCallUiPhase.IDLE, viewModel.uiState.value.phase)
        assertTrue(terminalStore.contains(CALL_ID))
    }

    @Test
    fun authoritativeDeadlineExpiresUnansweredCallAfterTwelveRingCycles() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingCallGateway(
            initialSession = directCallSession(expiresAtMillis = TWELVE_RING_CYCLES_MILLIS),
        )
        val alertGateway = RecordingAlertGateway()
        val viewModel = DirectCallViewModel(
            gateway,
            RecordingMediaGateway(),
            alertGateway,
            RecordingForegroundController(),
            RecordingTerminalNotificationStore(),
            nowEpochMillis = { testScheduler.currentTime },
        )
        viewModel.bindAccount(PETER_UID)
        runCurrent()

        advanceTimeBy(TWELVE_RING_CYCLES_MILLIS)
        runCurrent()

        assertEquals(1, gateway.expirationCount)
        assertEquals(RemoteDirectCallState.MISSED, gateway.session.value?.state)
        assertEquals(DirectCallUiPhase.IDLE, viewModel.uiState.value.phase)
        assertFalse(alertGateway.ringbackPlaying)
    }

    @Test
    fun remoteCancellationStopsIncomingRingtoneAndCannotBeAnswered() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingCallGateway(
            initialSession = directCallSession(callerUid = TRISH_UID, calleeUid = PETER_UID),
        )
        val alertGateway = RecordingAlertGateway()
        val terminalStore = RecordingTerminalNotificationStore()
        val viewModel = DirectCallViewModel(
            gateway,
            RecordingMediaGateway(),
            alertGateway,
            RecordingForegroundController(),
            terminalStore,
        )
        viewModel.bindAccount(PETER_UID)
        runCurrent()
        assertTrue(alertGateway.incomingRingtonePlaying)

        gateway.session.value = gateway.session.value?.copy(state = RemoteDirectCallState.CANCELED)
        runCurrent()

        assertFalse(alertGateway.incomingRingtonePlaying)
        assertEquals(DirectCallUiPhase.IDLE, viewModel.uiState.value.phase)
        assertTrue(terminalStore.contains(CALL_ID))
    }

    @Test
    fun relaunchRecoveryKeepsTerminalCallSilent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingCallGateway(
            initialSession = directCallSession(
                callerUid = TRISH_UID,
                calleeUid = PETER_UID,
                state = RemoteDirectCallState.MISSED,
            ),
        )
        val alertGateway = RecordingAlertGateway()
        val terminalStore = RecordingTerminalNotificationStore()
        val viewModel = DirectCallViewModel(
            gateway,
            RecordingMediaGateway(),
            alertGateway,
            RecordingForegroundController(),
            terminalStore,
        )

        viewModel.bindAccount(PETER_UID)
        runCurrent()

        assertEquals(DirectCallUiPhase.IDLE, viewModel.uiState.value.phase)
        assertFalse(alertGateway.incomingRingtonePlaying)
        assertTrue(terminalStore.contains(CALL_ID))
    }

    private class RecordingCallGateway(
        initialSession: RemoteDirectCallSession? = null,
    ) : RemoteDirectCallGateway {
        val activeCallId = MutableStateFlow(initialSession?.callId)
        val session = MutableStateFlow<RemoteDirectCallSession?>(initialSession)
        val signals = MutableStateFlow<List<RemoteDirectCallSignal>>(emptyList())
        var expirationCount = 0

        override fun observeActiveCallId(accountUid: RemoteAccountUid): Flow<RemoteDirectCallId?> = activeCallId

        override fun observeCall(
            accountUid: RemoteAccountUid,
            callId: RemoteDirectCallId,
        ): Flow<RemoteDirectCallSession?> = session

        override fun observeSignals(
            accountUid: RemoteAccountUid,
            callId: RemoteDirectCallId,
        ): Flow<List<RemoteDirectCallSignal>> = signals

        override suspend fun startCall(
            accountUid: RemoteAccountUid,
            roomId: RemoteRoomId,
            mediaKind: RemoteDirectCallMediaKind,
        ): RemoteDirectCallSession = directCallSession(
            callerUid = accountUid,
            calleeUid = TRISH_UID,
            mediaKind = mediaKind,
        ).also {
            activeCallId.value = it.callId
            session.value = it
        }

        override suspend fun respondToCall(
            accountUid: RemoteAccountUid,
            callId: RemoteDirectCallId,
            response: RemoteDirectCallResponse,
        ): RemoteDirectCallSession = requireNotNull(session.value).copy(
            state = if (response == RemoteDirectCallResponse.ACCEPT) {
                RemoteDirectCallState.ACTIVE
            } else {
                RemoteDirectCallState.DECLINED
            },
        ).also { session.value = it }

        override suspend fun endCall(
            accountUid: RemoteAccountUid,
            callId: RemoteDirectCallId,
        ): RemoteDirectCallSession = requireNotNull(session.value).let { current ->
            current.copy(
                state = if (current.state == RemoteDirectCallState.RINGING) {
                    RemoteDirectCallState.CANCELED
                } else {
                    RemoteDirectCallState.ENDED
                },
            )
        }.also {
            session.value = it
            activeCallId.value = null
        }

        override suspend fun expireCall(
            accountUid: RemoteAccountUid,
            callId: RemoteDirectCallId,
        ): RemoteDirectCallSession = requireNotNull(session.value).copy(
            state = RemoteDirectCallState.MISSED,
        ).also {
            expirationCount += 1
            session.value = it
            activeCallId.value = null
        }

        override suspend fun publishSignal(
            accountUid: RemoteAccountUid,
            callId: RemoteDirectCallId,
            signal: RemoteDirectCallSignal,
        ) = Unit
    }

    private class RecordingMediaGateway : DirectCallMediaGateway {
        var started = false
        var localVideoPreviewStarted = false
        var localVideoPreviewStartCount = 0
        var cameraEnabledValue = true
        var cameraSwitchCount = 0
        var mediaKind: RemoteDirectCallMediaKind? = null
        var role: RemoteDirectCallRole? = null
        var speakerEnabledValue = false
        var stopCount = 0

        override suspend fun startLocalVideoPreview() {
            localVideoPreviewStarted = true
            localVideoPreviewStartCount += 1
        }

        override suspend fun start(
            accountUid: RemoteAccountUid,
            mediaKind: RemoteDirectCallMediaKind,
            role: RemoteDirectCallRole,
            onLocalSignal: (RemoteDirectCallSignal) -> Unit,
            onConnectionStateChanged: (DirectCallMediaConnectionState) -> Unit,
        ) {
            localVideoPreviewStarted = false
            started = true
            this.mediaKind = mediaKind
            this.role = role
            onConnectionStateChanged(DirectCallMediaConnectionState.CONNECTED)
        }

        override suspend fun applyRemoteSignal(signal: RemoteDirectCallSignal) = Unit

        override fun setMicrophoneMuted(muted: Boolean) = Unit

        override fun setCameraEnabled(enabled: Boolean) {
            cameraEnabledValue = enabled
        }

        override fun switchCamera() {
            cameraSwitchCount += 1
        }

        override fun setSpeakerEnabled(enabled: Boolean) {
            speakerEnabledValue = enabled
        }

        override fun stop() {
            stopCount += 1
            started = false
            localVideoPreviewStarted = false
            mediaKind = null
            role = null
        }
    }

    private class RecordingAlertGateway : DirectCallAlertGateway {
        var ringbackPlaying = false
        var incomingRingtonePlaying = false
        var stopCount = 0

        override fun startOutgoingRingback(expiresAtMillis: Long) {
            ringbackPlaying = true
            incomingRingtonePlaying = false
        }

        override fun startIncomingRingtone(expiresAtMillis: Long) {
            ringbackPlaying = false
            incomingRingtonePlaying = true
        }

        override fun stop() {
            stopCount += 1
            ringbackPlaying = false
            incomingRingtonePlaying = false
        }
    }

    private class RecordingTerminalNotificationStore : DirectCallTerminalNotificationStore {
        private val callIds = mutableSetOf<RemoteDirectCallId>()

        override fun contains(callId: RemoteDirectCallId): Boolean = callId in callIds

        override fun record(callId: RemoteDirectCallId) {
            callIds += callId
        }
    }

    private class RecordingForegroundController : DirectCallForegroundController {
        var started = false
        var dismissedCallId: RemoteDirectCallId? = null
        var mediaKind: RemoteDirectCallMediaKind? = null
        var stopCount = 0

        override fun start(
            callId: RemoteDirectCallId,
            mediaKind: RemoteDirectCallMediaKind,
        ) {
            started = true
            this.mediaKind = mediaKind
        }

        override fun stop() {
            stopCount += 1
            started = false
        }

        override fun dismissIncomingNotification(callId: RemoteDirectCallId) {
            dismissedCallId = callId
        }
    }

    private companion object {
        val PETER_UID = RemoteAccountUid("peter-uid")
        val TRISH_UID = RemoteAccountUid("trish-uid")
        val CALL_ID = RemoteDirectCallId("call_${"a".repeat(32)}")
        val ROOM_ID = RemoteRoomId("direct_${"b".repeat(64)}")

        fun directCallSession(
            callerUid: RemoteAccountUid = PETER_UID,
            calleeUid: RemoteAccountUid = TRISH_UID,
            mediaKind: RemoteDirectCallMediaKind = RemoteDirectCallMediaKind.AUDIO,
            state: RemoteDirectCallState = RemoteDirectCallState.RINGING,
            expiresAtMillis: Long = Long.MAX_VALUE,
        ) = RemoteDirectCallSession(
            callId = CALL_ID,
            callerUid = callerUid,
            calleeUid = calleeUid,
            roomId = ROOM_ID,
            mediaKind = mediaKind,
            state = state,
            expiresAtMillis = expiresAtMillis,
        )

        const val TWELVE_RING_CYCLES_MILLIS = 72_000L
    }
}
