package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.di.SynapseApplicationGraph
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
import app.synapse.localllm.domain.remote.RemoteDirectCallSignalId
import app.synapse.localllm.domain.remote.RemoteDirectCallState
import app.synapse.localllm.domain.remote.RemoteRoomId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DirectCallUiPhase {
    IDLE,
    STARTING,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    ACTIVE,
    ENDING,
    FAILED,
}

data class DirectCallUiState(
    val phase: DirectCallUiPhase = DirectCallUiPhase.IDLE,
    val session: RemoteDirectCallSession? = null,
    val isActionRunning: Boolean = false,
    val isMicrophoneMuted: Boolean = false,
    val isSpeakerEnabled: Boolean = false,
    val notice: String? = null,
)

class DirectCallViewModel(
    private val callGateway: RemoteDirectCallGateway,
    private val mediaGateway: DirectCallMediaGateway,
    private val alertGateway: DirectCallAlertGateway,
    private val foregroundController: DirectCallForegroundController,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(DirectCallUiState())
    val uiState: StateFlow<DirectCallUiState> = mutableUiState

    private var accountUid: RemoteAccountUid? = null
    private var accountCallJob: Job? = null
    private var callSessionJob: Job? = null
    private var callSignalJob: Job? = null
    private var callExpiryJob: Job? = null
    private var mediaCallId: RemoteDirectCallId? = null
    private var foregroundCallId: RemoteDirectCallId? = null
    private var pendingNotificationCallId: RemoteDirectCallId? = null
    private val processedSignalIds = mutableSetOf<RemoteDirectCallSignalId>()

    fun bindAccount(updatedAccountUid: RemoteAccountUid?) {
        if (accountUid == updatedAccountUid) return
        accountUid = updatedAccountUid
        accountCallJob?.cancel()
        callSessionJob?.cancel()
        callExpiryJob?.cancel()
        stopMedia()
        mutableUiState.value = DirectCallUiState()
        if (updatedAccountUid == null) return
        pendingNotificationCallId?.let { callId -> observeCall(updatedAccountUid, callId) }
        accountCallJob = viewModelScope.launch {
            try {
                callGateway.observeActiveCallId(updatedAccountUid).collectLatest { callId ->
                    if (callId == null) {
                        if (mutableUiState.value.phase !in setOf(DirectCallUiPhase.STARTING, DirectCallUiPhase.ENDING)) {
                            callSessionJob?.cancel()
                            stopMedia()
                            mutableUiState.value = DirectCallUiState()
                        }
                    } else {
                        observeCall(updatedAccountUid, callId)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failCall(exception.message ?: "Could not monitor Synapse calls.")
            }
        }
    }

    fun openNotificationCall(callId: RemoteDirectCallId) {
        pendingNotificationCallId = callId
        accountUid?.let { activeAccountUid -> observeCall(activeAccountUid, callId) }
    }

    fun startCall(roomId: RemoteRoomId) = launchAction {
        val activeAccountUid = requireNotNull(accountUid) { "Sign in before starting a call." }
        mutableUiState.update { state -> state.copy(phase = DirectCallUiPhase.STARTING, notice = null) }
        val session = callGateway.startCall(activeAccountUid, roomId)
        observeCall(activeAccountUid, session.callId)
        presentSession(activeAccountUid, session)
    }

    fun acceptCall() = launchAction {
        val activeAccountUid = requireNotNull(accountUid) { "Sign in before answering a call." }
        val session = requireNotNull(mutableUiState.value.session) { "The call is no longer available." }
        val updated = callGateway.respondToCall(
            activeAccountUid,
            session.callId,
            RemoteDirectCallResponse.ACCEPT,
        )
        presentSession(activeAccountUid, updated)
    }

    fun declineCall() = launchAction {
        val activeAccountUid = requireNotNull(accountUid) { "Sign in before declining a call." }
        val session = requireNotNull(mutableUiState.value.session) { "The call is no longer available." }
        callGateway.respondToCall(activeAccountUid, session.callId, RemoteDirectCallResponse.DECLINE)
        finishCallLocally("Call declined.")
    }

    fun endCall() = launchAction {
        val activeAccountUid = requireNotNull(accountUid) { "Sign in before ending a call." }
        val session = requireNotNull(mutableUiState.value.session) { "The call is no longer available." }
        mutableUiState.update { state -> state.copy(phase = DirectCallUiPhase.ENDING) }
        callGateway.endCall(activeAccountUid, session.callId)
        finishCallLocally("Call ended.")
    }

    fun toggleMicrophone() {
        val muted = !mutableUiState.value.isMicrophoneMuted
        mediaGateway.setMicrophoneMuted(muted)
        mutableUiState.update { state -> state.copy(isMicrophoneMuted = muted) }
    }

    fun toggleSpeaker() {
        val enabled = !mutableUiState.value.isSpeakerEnabled
        mediaGateway.setSpeakerEnabled(enabled)
        mutableUiState.update { state -> state.copy(isSpeakerEnabled = enabled) }
    }

    fun reportMicrophonePermissionDenied() {
        mutableUiState.update { state ->
            state.copy(
                phase = if (state.phase == DirectCallUiPhase.IDLE) DirectCallUiPhase.FAILED else state.phase,
                notice = "Microphone permission is required for voice calls.",
            )
        }
    }

    fun dismissFailure() {
        if (mutableUiState.value.phase == DirectCallUiPhase.FAILED) {
            mutableUiState.value = DirectCallUiState()
        }
    }

    private fun observeCall(
        activeAccountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
    ) {
        if (callSessionJob?.isActive == true && mutableUiState.value.session?.callId == callId) return
        callSessionJob?.cancel()
        callSessionJob = viewModelScope.launch {
            try {
                callGateway.observeCall(activeAccountUid, callId).collectLatest { session ->
                    if (session == null) {
                        if (pendingNotificationCallId == callId) pendingNotificationCallId = null
                        finishCallLocally(null)
                    } else {
                        presentSession(activeAccountUid, session)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failCall(exception.message ?: "Could not update the call.")
            }
        }
    }

    private fun presentSession(
        activeAccountUid: RemoteAccountUid,
        session: RemoteDirectCallSession,
    ) {
        pendingNotificationCallId = null
        if (activeAccountUid != session.callerUid && activeAccountUid != session.calleeUid) {
            failCall("This call does not belong to the signed-in account.")
            return
        }
        when (session.state) {
            RemoteDirectCallState.RINGING -> {
                val phase = if (activeAccountUid == session.callerUid) {
                    DirectCallUiPhase.OUTGOING_RINGING
                } else {
                    DirectCallUiPhase.INCOMING_RINGING
                }
                mutableUiState.update { state -> state.copy(phase = phase, session = session, notice = null) }
                if (phase == DirectCallUiPhase.OUTGOING_RINGING) {
                    alertGateway.startOutgoingRingback()
                    runCatching { ensureCallForeground(session.callId) }
                        .onFailure { failure ->
                            failAndEndCall(failure.message ?: "Android could not keep the outgoing call active.")
                            return
                        }
                }
                scheduleCallExpiry(activeAccountUid, session)
            }
            RemoteDirectCallState.ACTIVE -> {
                callExpiryJob?.cancel()
                alertGateway.stop()
                mutableUiState.update { state ->
                    state.copy(phase = DirectCallUiPhase.CONNECTING, session = session, notice = null)
                }
                if (mediaCallId != session.callId) startMedia(activeAccountUid, session)
            }
            RemoteDirectCallState.DECLINED -> finishCallLocally("Call declined.")
            RemoteDirectCallState.ENDED -> finishCallLocally("Call ended.")
            RemoteDirectCallState.MISSED -> finishCallLocally("Missed call.")
        }
    }

    private fun startMedia(
        activeAccountUid: RemoteAccountUid,
        session: RemoteDirectCallSession,
    ) {
        mediaCallId = session.callId
        processedSignalIds.clear()
        viewModelScope.launch {
            try {
                ensureCallForeground(session.callId)
                val role = if (activeAccountUid == session.callerUid) {
                    RemoteDirectCallRole.CALLER
                } else {
                    RemoteDirectCallRole.CALLEE
                }
                mediaGateway.start(
                    accountUid = activeAccountUid,
                    role = role,
                    onLocalSignal = { signal -> publishLocalSignal(activeAccountUid, session.callId, signal) },
                    onConnectionStateChanged = ::onMediaConnectionStateChanged,
                )
                callSignalJob?.cancel()
                callSignalJob = viewModelScope.launch {
                    callGateway.observeSignals(activeAccountUid, session.callId).collectLatest { signals ->
                        signals
                            .asSequence()
                            .filter { signal -> signal.senderUid != activeAccountUid }
                            .filter { signal -> processedSignalIds.add(signal.signalId) }
                            .forEach { signal -> mediaGateway.applyRemoteSignal(signal) }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failAndEndCall(exception.message ?: "Could not connect the voice call.")
            }
        }
    }

    private fun publishLocalSignal(
        activeAccountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
        signal: RemoteDirectCallSignal,
    ) {
        viewModelScope.launch {
            try {
                callGateway.publishSignal(activeAccountUid, callId, signal)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failAndEndCall(exception.message ?: "Could not exchange voice-call setup data.")
            }
        }
    }

    private fun onMediaConnectionStateChanged(connectionState: DirectCallMediaConnectionState) {
        when (connectionState) {
            DirectCallMediaConnectionState.CONNECTING -> Unit
            DirectCallMediaConnectionState.CONNECTED -> mutableUiState.update { state ->
                state.copy(phase = DirectCallUiPhase.ACTIVE, notice = null)
            }
            DirectCallMediaConnectionState.DISCONNECTED ->
                mutableUiState.update { state -> state.copy(notice = "Voice connection interrupted. Reconnecting…") }
            DirectCallMediaConnectionState.FAILED ->
                failAndEndCall("The voice connection failed on this network. Try Wi-Fi or another network.")
        }
    }

    private fun scheduleCallExpiry(
        activeAccountUid: RemoteAccountUid,
        session: RemoteDirectCallSession,
    ) {
        callExpiryJob?.cancel()
        callExpiryJob = viewModelScope.launch {
            delay((session.expiresAtMillis - nowEpochMillis()).coerceAtLeast(0L))
            if (mutableUiState.value.session?.callId != session.callId) return@launch
            runCatching { callGateway.endCall(activeAccountUid, session.callId) }
            finishCallLocally(
                if (activeAccountUid == session.callerUid) "No answer." else "Missed call.",
            )
        }
    }

    private fun failAndEndCall(message: String) {
        val activeAccountUid = accountUid
        val session = mutableUiState.value.session
        failCall(message)
        if (activeAccountUid != null && session != null) {
            viewModelScope.launch { runCatching { callGateway.endCall(activeAccountUid, session.callId) } }
        }
    }

    private fun failCall(message: String) {
        stopMedia()
        mutableUiState.update { state ->
            state.copy(phase = DirectCallUiPhase.FAILED, isActionRunning = false, notice = message)
        }
    }

    private fun finishCallLocally(notice: String?) {
        callExpiryJob?.cancel()
        mutableUiState.value.session?.callId?.let(foregroundController::dismissIncomingNotification)
        stopMedia()
        mutableUiState.value = DirectCallUiState(notice = notice)
    }

    private fun stopMedia() {
        callSignalJob?.cancel()
        callSignalJob = null
        mediaGateway.stop()
        alertGateway.stop()
        foregroundController.stop()
        foregroundCallId = null
        mediaCallId = null
        processedSignalIds.clear()
    }

    private fun ensureCallForeground(callId: RemoteDirectCallId) {
        if (foregroundCallId == callId) return
        try {
            foregroundController.start(callId)
            foregroundCallId = callId
        } catch (exception: RuntimeException) {
            throw IllegalStateException("Android could not keep the voice call active.", exception)
        }
    }

    private fun launchAction(action: suspend () -> Unit) {
        if (mutableUiState.value.isActionRunning) return
        viewModelScope.launch {
            mutableUiState.update { state -> state.copy(isActionRunning = true, notice = null) }
            try {
                action()
                mutableUiState.update { state -> state.copy(isActionRunning = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(
                        phase = if (state.session == null) DirectCallUiPhase.FAILED else state.phase,
                        isActionRunning = false,
                        notice = exception.message ?: "The call action failed.",
                    )
                }
            }
        }
    }

    override fun onCleared() {
        stopMedia()
        super.onCleared()
    }
}

class DirectCallViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DirectCallViewModel::class.java)) {
            return modelClass.cast(
                DirectCallViewModel(
                    callGateway = graph.remoteDirectCallGateway,
                    mediaGateway = graph.directCallMediaGateway,
                    alertGateway = graph.directCallAlertGateway,
                    foregroundController = graph.directCallForegroundController,
                ),
            ) ?: throw IllegalArgumentException("Unable to create DirectCallViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
