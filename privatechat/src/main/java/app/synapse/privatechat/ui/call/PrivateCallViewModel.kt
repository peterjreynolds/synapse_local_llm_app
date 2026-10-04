package app.synapse.privatechat.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.synapse.privatechat.data.call.media.PrivateCallVideoRenderer
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
import app.synapse.privatechat.domain.call.PrivateCallPoll
import app.synapse.privatechat.domain.call.PrivateCallRole
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallSignal
import app.synapse.privatechat.domain.call.PrivateCallSignalingGateway
import app.synapse.privatechat.domain.call.PrivateCallState
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Instant
import java.util.UUID

class PrivateCallViewModel(
    private val signaling: PrivateCallSignalingGateway,
    media: PrivateCallMediaGateway,
    alerts: PrivateCallAlertGateway,
    foreground: PrivateCallForegroundGateway,
    private val clock: Clock,
    val videoRenderer: PrivateCallVideoRenderer? = null,
) : ViewModel() {
    private val mediaSession = PrivateCallMediaSession(media, alerts, foreground)
    private val monitor = PrivateCallTransportMonitor(signaling, clock)
    private val mutableUiState = MutableStateFlow<PrivateCallUiState>(PrivateCallUiState.Idle)
    private val mutableAvailability = MutableStateFlow(PrivateCallAvailability.CONNECTING)
    val uiState: StateFlow<PrivateCallUiState> = mutableUiState.asStateFlow()
    val availability: StateFlow<PrivateCallAvailability> = mutableAvailability.asStateFlow()
    private var accountId: PrivateAccountId? = null
    private var isForeground = false
    private var callScope: CoroutineScope? = null
    private var generation = 0L
    private var callRevision = 0L
    private var accountCleanupJob: Job? = null
    private var teardownJob: Job? = null
    private var activeCall: PrivateCallSession? = null
    private var pendingCallId: UUID? = null
    private var direction = PrivateCallDirection.OUTGOING
    private var ringDeadline: Instant? = null
    private var disconnectedDeadline: Instant? = null
    private var tickJob: Job? = null
    private val localSignalMutex = Mutex()
    private val remoteSignalMutex = Mutex()
    private val mediaControlMutex = Mutex()
    private val pendingLocalSignals = ArrayDeque<PrivateCallMediaSignal>()
    private val pendingRemoteSignals = ArrayDeque<PrivateCallMediaSignal>()
    private var localDescriptionPublished = false
    private var knownRooms: List<PrivateRoomSummary> = emptyList()

    fun activateAccount(account: PrivateAccountId) {
        if (accountId == account) return
        deactivateAccount()
        accountId = account
        mutableUiState.value = PrivateCallUiState.Idle
        viewModelScope.launch {
            accountCleanupJob?.join()
            if (accountId == account) synchronizeMonitoring()
        }
    }

    fun updateRooms(rooms: List<PrivateRoomSummary>) {
        knownRooms = rooms.toList()
    }

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
        if (!foreground && (mutableUiState.value is PrivateCallUiState.Consent || mutableUiState.value is PrivateCallUiState.Preparing)) {
            finishCall("Call dismissed", localEndReason())
        }
        synchronizeMonitoring()
    }

    fun requestCall(
        room: PrivateRoomSummary,
        kind: PrivateCallMediaKind,
    ) {
        val account = accountId ?: return
        if (!isForeground || !canStartCall() || room.kind != PrivateRoomKind.DIRECT || room.participantCount != 2) return
        beginCallScope()
        direction = PrivateCallDirection.OUTGOING
        mutableUiState.value = PrivateCallUiState.Preparing(room.title)
        val expectedGeneration = generation
        launchCallOperation {
            val preparation = signaling.prepareCall(account, room.roomId)
            requireGeneration(expectedGeneration)
            check(preparation.peerSafetyNumbers.isNotEmpty()) { "Peer encryption identity is unavailable" }
            mutableUiState.value =
                PrivateCallUiState.Consent(
                    PrivateCallConsentRequest.Outgoing(room, preparation, kind),
                    preparation.peerSafetyNumbers,
                )
        }
    }

    /** Only the foreground permission/consent UI invokes this command, never a remote signal. */
    fun startVerifiedDirectCall() {
        val account = accountId ?: return
        val consent = mutableUiState.value as? PrivateCallUiState.Consent ?: return
        if (!isForeground) return
        val request = consent.request
        val id = if (request is PrivateCallConsentRequest.Incoming) request.session.callId else UUID.randomUUID()
        pendingCallId = id
        ringDeadline = clock.instant().plusSeconds(60)
        mutableUiState.value =
            PrivateCallUiState.Ongoing(
                id,
                request.title,
                request.mediaKind,
                direction,
                PrivateCallStage.CONNECTING,
            )
        val expectedGeneration = generation
        launchCallOperation {
            if (request is PrivateCallConsentRequest.Incoming) {
                val accepted = signaling.acceptCall(account, id)
                requireGeneration(expectedGeneration)
                activeCall = accepted
                callRevision++
                mediaSession.silenceAlerts()
                pendingRemoteSignals.addFirst(request.offer)
            }
            mediaSession.start(
                id = id,
                role = if (direction == PrivateCallDirection.OUTGOING) PrivateCallRole.OFFERER else PrivateCallRole.ANSWERER,
                kind = request.mediaKind,
                onHangUp = ::hangUp,
                onSignal = { signal -> dispatchLocalSignal(expectedGeneration, request, signal) },
                onState = { state ->
                    viewModelScope.launch {
                        if (generation == expectedGeneration) applyMediaState(state)
                    }
                },
            )
            requireGeneration(expectedGeneration)
            updateOngoing { it.copy(mediaReady = true) }
            drainRemoteSignals(expectedGeneration)
        }
    }

    fun permissionDenied() = finishCall("Microphone, camera when used, and call notifications must be allowed.", localEndReason())

    fun hangUp() = finishCall("Call ended", localEndReason())

    fun dismiss() {
        when (mutableUiState.value) {
            is PrivateCallUiState.Finished -> mutableUiState.value = PrivateCallUiState.Idle
            PrivateCallUiState.Idle, is PrivateCallUiState.Stopping -> Unit
            else -> hangUp()
        }
    }

    fun toggleMicrophone() = changeMediaControl(MediaControl.MICROPHONE)

    fun toggleSpeaker() = changeMediaControl(MediaControl.SPEAKER)

    fun toggleCamera() = changeMediaControl(MediaControl.CAMERA)

    fun switchCamera() {
        val ongoing = mutableUiState.value as? PrivateCallUiState.Ongoing ?: return
        if (!ongoing.mediaReady || ongoing.mediaKind != PrivateCallMediaKind.VIDEO) return
        val expectedGeneration = generation
        launchCallOperation {
            mediaControlMutex.withLock {
                requireGeneration(expectedGeneration)
                val current = mutableUiState.value as? PrivateCallUiState.Ongoing ?: return@withLock
                if (current.mediaReady && current.mediaKind == PrivateCallMediaKind.VIDEO) mediaSession.media.switchCamera()
                requireGeneration(expectedGeneration)
            }
        }
    }

    fun deactivateAccount() {
        if (mutableUiState.value !is PrivateCallUiState.Idle && mutableUiState.value !is PrivateCallUiState.Finished) {
            finishCall("Signed out", localEndReason())
        }
        // An undispatched teardown can complete synchronously and restart monitoring.
        // Cancel monitoring after it starts, before allowing a different account to activate.
        monitor.stop()
        tickJob?.cancel()
        tickJob = null
        generation++
        callRevision++
        callScope?.cancel()
        callScope = null
        accountId = null
        knownRooms = emptyList()
        mutableAvailability.value = PrivateCallAvailability.UNAVAILABLE
        val previousCleanup = accountCleanupJob
        val pendingTeardown = teardownJob
        accountCleanupJob =
            viewModelScope.launch {
                previousCleanup?.join()
                pendingTeardown?.join()
                signaling.clearEphemeralState()
            }
    }

    private fun synchronizeMonitoring() {
        val account = accountId ?: return
        if (accountCleanupJob?.isActive == true) return
        if (!isForeground && mutableUiState.value !is PrivateCallUiState.Ongoing) {
            monitor.stop()
            return
        }
        monitor.start(
            viewModelScope,
            account,
            callRevision = { callRevision },
            heartbeatCall = { activeCall?.callId.takeIf { mutableUiState.value is PrivateCallUiState.Ongoing } },
            onHeartbeat = { receipt -> if (activeCall?.callId == receipt.callId) activeCall = receipt },
            onPoll = ::applyPoll,
            onFailure = { failure ->
                mutableAvailability.value = PrivateCallAvailability.UNAVAILABLE
                if (failure is PrivateCallException && failure.failure != PrivateCallFailure.UNAVAILABLE) {
                    finishCall("The call identity or authenticated signaling could not be verified.", localEndReason())
                }
            },
        )
        if (tickJob?.isActive != true) {
            tickJob =
                viewModelScope.launch {
                    while (isActive) {
                        delay(500)
                        val call = activeCall
                        val deadline =
                            when (call?.state) {
                                PrivateCallState.ACTIVE -> call.leaseExpiresAt
                                PrivateCallState.RINGING -> minOf(call.ringExpiresAt, call.leaseExpiresAt)
                                else -> ringDeadline
                            }
                        if (deadline != null && !clock.instant().isBefore(deadline)) finishCall("Call timed out", localEndReason())
                        if (disconnectedDeadline?.let { !clock.instant().isBefore(it) } ==
                            true
                        ) {
                            finishCall("Call connection lost", localEndReason())
                        }
                    }
                }
        }
    }

    private suspend fun applyPoll(
        poll: PrivateCallPoll,
        revisionAtPollStart: Long,
    ) {
        mutableAvailability.value = PrivateCallAvailability.AVAILABLE
        val tracked = activeCall
        if (tracked != null) {
            val current = poll.calls.firstOrNull { it.callId == tracked.callId }
            when {
                (current == null && revisionAtPollStart == callRevision) || current?.state == PrivateCallState.ENDED -> {
                    finishCall("Call ended", null)
                    return
                }
                current?.state == PrivateCallState.ACTIVE &&
                    direction == PrivateCallDirection.INCOMING &&
                    tracked.acceptedDeviceId == null &&
                    mutableUiState.value !is PrivateCallUiState.Ongoing -> {
                    finishCall("Answered on another device", null)
                    return
                }
                current != null && !(tracked.state == PrivateCallState.ACTIVE && current.state == PrivateCallState.RINGING) ->
                    activeCall =
                        current
            }
            if (current?.state == PrivateCallState.ACTIVE) mediaSession.silenceAlerts()
        } else if (canStartCall() && isForeground) {
            val account = accountId ?: return
            val incoming =
                poll.calls.firstOrNull {
                    it.recipientAccountId.toString() == account.canonical &&
                        it.state == PrivateCallState.RINGING &&
                        clock.instant().isBefore(it.ringExpiresAt) &&
                        clock.instant().isBefore(it.leaseExpiresAt)
                }
            val offer =
                poll.signals
                    .firstOrNull {
                        it.callId == incoming?.callId &&
                            it.signal is PrivateCallSignal.Media &&
                            it.signal.mediaSignal is PrivateCallMediaSignal.Offer
                    }?.signal as? PrivateCallSignal.Media
            if (incoming != null && offer?.mediaSignal is PrivateCallMediaSignal.Offer) {
                beginCallScope()
                direction = PrivateCallDirection.INCOMING
                activeCall = incoming
                pendingCallId = incoming.callId
                ringDeadline = minOf(incoming.ringExpiresAt, incoming.leaseExpiresAt)
                val title = knownRooms.firstOrNull { it.roomId == incoming.roomId }?.title ?: "Private caller"
                mutableUiState.value = PrivateCallUiState.Preparing(title)
                mediaSession.alerts.startIncoming(checkNotNull(ringDeadline))
                val expectedGeneration = generation
                launchCallOperation {
                    val safetyNumbers = signaling.safetyNumbers(account, incoming.callId)
                    requireGeneration(expectedGeneration)
                    check(safetyNumbers.isNotEmpty()) { "Caller encryption identity is unavailable" }
                    mutableUiState.value =
                        PrivateCallUiState.Consent(
                            PrivateCallConsentRequest.Incoming(incoming, offer.mediaSignal, title),
                            safetyNumbers,
                        )
                }
            }
        }
        poll.signals.filter { it.callId == activeCall?.callId }.forEach { received ->
            when (val signal = received.signal) {
                is PrivateCallSignal.End -> finishCall("Call ended", null)
                is PrivateCallSignal.Media ->
                    when (val mediaSignal = signal.mediaSignal) {
                        is PrivateCallMediaSignal.Offer -> Unit
                        else -> {
                            if (pendingRemoteSignals.size < MAXIMUM_QUEUED_SIGNALS) {
                                pendingRemoteSignals.addLast(mediaSignal)
                                val expectedGeneration = generation
                                launchCallOperation { drainRemoteSignals(expectedGeneration) }
                            } else {
                                finishCall("Call signaling was rejected", localEndReason())
                            }
                        }
                    }
            }
        }
    }

    private fun dispatchLocalSignal(
        expectedGeneration: Long,
        request: PrivateCallConsentRequest,
        signal: PrivateCallMediaSignal,
    ) {
        launchCallOperation {
            localSignalMutex.withLock {
                if (generation != expectedGeneration) return@withLock
                val account = accountId ?: return@withLock
                val id = pendingCallId ?: return@withLock
                if (signal is PrivateCallMediaSignal.Offer && request is PrivateCallConsentRequest.Outgoing) {
                    check(activeCall == null) { "An offer has already been published" }
                    val receipt =
                        signaling.createCall(
                            account,
                            id,
                            request.room.roomId,
                            request.preparation.membershipEpoch,
                            request.mediaKind,
                            signal,
                        )
                    requireGeneration(expectedGeneration)
                    activeCall = receipt
                    callRevision++
                    localDescriptionPublished = true
                    ringDeadline = receipt.ringExpiresAt
                    mediaSession.alerts.startOutgoing(receipt.ringExpiresAt)
                    updateOngoing { it.copy(stage = PrivateCallStage.RINGING) }
                    drainLocalSignals(account, id, expectedGeneration)
                } else if (signal is PrivateCallMediaSignal.Answer && activeCall != null) {
                    signaling.sendSignal(account, id, signal)
                    requireGeneration(expectedGeneration)
                    localDescriptionPublished = true
                    drainLocalSignals(account, id, expectedGeneration)
                } else if (activeCall != null && localDescriptionPublished) {
                    signaling.sendSignal(account, id, signal)
                    requireGeneration(expectedGeneration)
                } else {
                    check(pendingLocalSignals.size < MAXIMUM_QUEUED_SIGNALS) { "Call signaling overflow" }
                    pendingLocalSignals.addLast(signal)
                }
            }
        }
    }

    private fun applyMediaState(state: PrivateCallMediaState) {
        when (state) {
            PrivateCallMediaState.CONNECTED -> {
                disconnectedDeadline = null
                mediaSession.silenceAlerts()
                updateOngoing { it.copy(stage = PrivateCallStage.CONNECTED) }
            }
            PrivateCallMediaState.DISCONNECTED -> {
                disconnectedDeadline = clock.instant().plusSeconds(15)
                updateOngoing { it.copy(stage = PrivateCallStage.RECONNECTING) }
            }
            PrivateCallMediaState.FAILED, PrivateCallMediaState.CLOSED -> finishCall("Call connection ended", localEndReason())
            PrivateCallMediaState.CONNECTING -> Unit
        }
    }

    private fun finishCall(
        message: String,
        remoteReason: PrivateCallEndReason?,
    ) {
        if (mutableUiState.value is PrivateCallUiState.Stopping) return
        if (mutableUiState.value == PrivateCallUiState.Idle || mutableUiState.value is PrivateCallUiState.Finished) return
        mediaSession.silenceAlerts()
        callScope?.cancel()
        callScope = null
        val expectedGeneration = ++generation
        callRevision++
        val endingCallId = pendingCallId
        val account = accountId
        activeCall = null
        pendingCallId = null
        ringDeadline = null
        disconnectedDeadline = null
        pendingLocalSignals.clear()
        pendingRemoteSignals.clear()
        mutableUiState.value = PrivateCallUiState.Stopping(message)
        teardownJob =
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                mediaSession.stop()
                if (account != null && endingCallId != null && remoteReason != null) {
                    try {
                        withTimeout(5_000) { signaling.endCall(account, endingCallId, remoteReason) }
                    } catch (
                        _: TimeoutCancellationException,
                    ) {
                        // The peer's bounded lease remains the offline stop rule.
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Local capture is already stopped; remote state expires independently.
                    }
                }
                if (generation == expectedGeneration) mutableUiState.value = PrivateCallUiState.Finished(message)
                synchronizeMonitoring()
            }
    }

    private fun beginCallScope() {
        generation++
        callRevision++
        callScope?.cancel()
        callScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        localDescriptionPublished = false
        pendingLocalSignals.clear()
        pendingRemoteSignals.clear()
    }

    private fun launchCallOperation(operation: suspend () -> Unit) {
        val expectedGeneration = generation
        callScope?.launch {
            try {
                withTimeout(20_000) { operation() }
            } catch (_: TimeoutCancellationException) {
                if (generation ==
                    expectedGeneration
                ) {
                    finishCall("Call setup or connection timed out", localEndReason())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation ==
                    expectedGeneration
                ) {
                    finishCall("The encrypted call could not be completed.", localEndReason())
                }
            }
        }
    }

    private fun canStartCall(): Boolean =
        accountCleanupJob?.isActive != true &&
            teardownJob?.isActive != true &&
            (mutableUiState.value == PrivateCallUiState.Idle || mutableUiState.value is PrivateCallUiState.Finished)

    private fun localEndReason(): PrivateCallEndReason =
        when {
            activeCall?.state == PrivateCallState.ACTIVE -> PrivateCallEndReason.ENDED
            direction == PrivateCallDirection.INCOMING -> PrivateCallEndReason.DECLINED
            else -> PrivateCallEndReason.CANCELLED
        }

    private fun updateOngoing(update: (PrivateCallUiState.Ongoing) -> PrivateCallUiState.Ongoing) {
        val ongoing = mutableUiState.value as? PrivateCallUiState.Ongoing ?: return
        mutableUiState.value = update(ongoing)
    }

    private fun changeMediaControl(control: MediaControl) {
        val ongoing = mutableUiState.value as? PrivateCallUiState.Ongoing ?: return
        if (!ongoing.mediaReady) return
        val expectedGeneration = generation
        launchCallOperation {
            mediaControlMutex.withLock {
                requireGeneration(expectedGeneration)
                val current = mutableUiState.value as? PrivateCallUiState.Ongoing ?: return@withLock
                if (!current.mediaReady) return@withLock
                when (control) {
                    MediaControl.MICROPHONE -> {
                        val muted = !current.microphoneMuted
                        mediaSession.media.setMicrophoneMuted(muted)
                        requireGeneration(expectedGeneration)
                        updateOngoing { it.copy(microphoneMuted = muted) }
                    }
                    MediaControl.SPEAKER -> {
                        val enabled = !current.speakerEnabled
                        mediaSession.media.setSpeakerEnabled(enabled)
                        requireGeneration(expectedGeneration)
                        updateOngoing { it.copy(speakerEnabled = enabled) }
                    }
                    MediaControl.CAMERA -> {
                        if (current.mediaKind != PrivateCallMediaKind.VIDEO) return@withLock
                        val enabled = !current.cameraEnabled
                        mediaSession.media.setCameraEnabled(enabled)
                        requireGeneration(expectedGeneration)
                        updateOngoing { it.copy(cameraEnabled = enabled) }
                    }
                }
            }
        }
    }

    private suspend fun drainLocalSignals(
        account: PrivateAccountId,
        callId: UUID,
        expectedGeneration: Long,
    ) {
        while (pendingLocalSignals.isNotEmpty()) {
            requireGeneration(expectedGeneration)
            signaling.sendSignal(account, callId, pendingLocalSignals.removeFirst())
            requireGeneration(expectedGeneration)
        }
    }

    private suspend fun drainRemoteSignals(expectedGeneration: Long) =
        remoteSignalMutex.withLock {
            requireGeneration(expectedGeneration)
            if ((mutableUiState.value as? PrivateCallUiState.Ongoing)?.mediaReady != true) return@withLock
            while (pendingRemoteSignals.isNotEmpty()) {
                mediaSession.media.applyRemoteSignal(pendingRemoteSignals.removeFirst())
                requireGeneration(expectedGeneration)
            }
        }

    private fun requireGeneration(expectedGeneration: Long) {
        if (generation != expectedGeneration) throw CancellationException("Call operation was superseded")
    }

    private enum class MediaControl { MICROPHONE, SPEAKER, CAMERA }

    override fun onCleared() {
        monitor.stop()
        mediaSession.silenceAlerts()
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanupScope.launch {
            try {
                mediaSession.stop()
                signaling.clearEphemeralState()
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    private companion object {
        const val MAXIMUM_QUEUED_SIGNALS = 128
    }
}
