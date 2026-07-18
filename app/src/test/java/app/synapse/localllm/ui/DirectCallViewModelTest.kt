package app.synapse.localllm.ui

import app.synapse.localllm.domain.calling.DirectCallAlertGateway
import app.synapse.localllm.domain.calling.DirectCallForegroundController
import app.synapse.localllm.domain.calling.DirectCallMediaConnectionState
import app.synapse.localllm.domain.calling.DirectCallMediaGateway
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteDirectCallGateway
import app.synapse.localllm.domain.remote.RemoteDirectCallId
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
    fun startsOutgoingCallAndEndsItWithForegroundCleanup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingCallGateway()
        val mediaGateway = RecordingMediaGateway()
        val alertGateway = RecordingAlertGateway()
        val foregroundController = RecordingForegroundController()
        val viewModel = DirectCallViewModel(gateway, mediaGateway, alertGateway, foregroundController)
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
        val viewModel = DirectCallViewModel(
            gateway,
            mediaGateway,
            RecordingAlertGateway(),
            RecordingForegroundController(),
        )
        viewModel.bindAccount(PETER_UID)
        runCurrent()

        assertEquals(DirectCallUiPhase.INCOMING_RINGING, viewModel.uiState.value.phase)
        assertFalse(mediaGateway.started)

        viewModel.acceptCall()
        runCurrent()

        assertEquals(DirectCallUiPhase.ACTIVE, viewModel.uiState.value.phase)
        assertTrue(mediaGateway.started)
        assertEquals(RemoteDirectCallRole.CALLEE, mediaGateway.role)
    }

    @Test
    fun deniedOutgoingMicrophonePermissionSurfacesAClosableFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = DirectCallViewModel(
            RecordingCallGateway(),
            RecordingMediaGateway(),
            RecordingAlertGateway(),
            RecordingForegroundController(),
        )

        viewModel.reportMicrophonePermissionDenied()

        assertEquals(DirectCallUiPhase.FAILED, viewModel.uiState.value.phase)
        assertEquals("Microphone permission is required for voice calls.", viewModel.uiState.value.notice)
        viewModel.dismissFailure()
        assertEquals(DirectCallUiPhase.IDLE, viewModel.uiState.value.phase)
    }

    private class RecordingCallGateway(
        initialSession: RemoteDirectCallSession = directCallSession(),
    ) : RemoteDirectCallGateway {
        val activeCallId = MutableStateFlow<RemoteDirectCallId?>(CALL_ID)
        val session = MutableStateFlow<RemoteDirectCallSession?>(initialSession)
        val signals = MutableStateFlow<List<RemoteDirectCallSignal>>(emptyList())

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
        ): RemoteDirectCallSession = directCallSession(callerUid = accountUid, calleeUid = TRISH_UID).also {
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
        ): RemoteDirectCallSession = requireNotNull(session.value).copy(state = RemoteDirectCallState.ENDED).also {
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
        var role: RemoteDirectCallRole? = null

        override suspend fun start(
            accountUid: RemoteAccountUid,
            role: RemoteDirectCallRole,
            onLocalSignal: (RemoteDirectCallSignal) -> Unit,
            onConnectionStateChanged: (DirectCallMediaConnectionState) -> Unit,
        ) {
            started = true
            this.role = role
            onConnectionStateChanged(DirectCallMediaConnectionState.CONNECTED)
        }

        override suspend fun applyRemoteSignal(signal: RemoteDirectCallSignal) = Unit

        override fun setMicrophoneMuted(muted: Boolean) = Unit

        override fun setSpeakerEnabled(enabled: Boolean) = Unit

        override fun stop() {
            started = false
            role = null
        }
    }

    private class RecordingAlertGateway : DirectCallAlertGateway {
        var ringbackPlaying = false

        override fun startOutgoingRingback() {
            ringbackPlaying = true
        }

        override fun stop() {
            ringbackPlaying = false
        }
    }

    private class RecordingForegroundController : DirectCallForegroundController {
        var started = false
        var dismissedCallId: RemoteDirectCallId? = null

        override fun start(callId: RemoteDirectCallId) {
            started = true
        }

        override fun stop() {
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
        ) = RemoteDirectCallSession(
            callId = CALL_ID,
            callerUid = callerUid,
            calleeUid = calleeUid,
            roomId = ROOM_ID,
            state = RemoteDirectCallState.RINGING,
            expiresAtMillis = Long.MAX_VALUE,
        )
    }
}
