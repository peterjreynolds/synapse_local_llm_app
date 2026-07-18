package app.synapse.localllm.data.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import app.synapse.localllm.domain.calling.DirectCallMediaConnectionState
import app.synapse.localllm.domain.calling.DirectCallMediaGateway
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteDirectCallRole
import app.synapse.localllm.domain.remote.RemoteDirectCallSignal
import app.synapse.localllm.domain.remote.RemoteDirectCallSignalId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

class AndroidDirectCallMediaGateway(context: Context) : DirectCallMediaGateway {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private var audioDeviceModule: AudioDeviceModule? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var currentAccountUid: RemoteAccountUid? = null
    private var currentRole: RemoteDirectCallRole? = null
    private var localSignalConsumer: ((RemoteDirectCallSignal) -> Unit)? = null
    private var connectionStateConsumer: ((DirectCallMediaConnectionState) -> Unit)? = null
    private var remoteDescriptionApplied = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val appliedSignalIds = mutableSetOf<RemoteDirectCallSignalId>()

    override suspend fun start(
        accountUid: RemoteAccountUid,
        role: RemoteDirectCallRole,
        onLocalSignal: (RemoteDirectCallSignal) -> Unit,
        onConnectionStateChanged: (DirectCallMediaConnectionState) -> Unit,
    ) {
        stop()
        initializeWebRtc(applicationContext)
        currentAccountUid = accountUid
        currentRole = role
        localSignalConsumer = onLocalSignal
        connectionStateConsumer = onConnectionStateChanged
        requestCallAudioFocus()
        audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        val factory = checkNotNull(peerConnectionFactory)
        audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(true)
        }
        val rtcConfiguration = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            ),
        ).apply {
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory.createPeerConnection(rtcConfiguration, createPeerConnectionObserver())
            ?: error("Android could not create the voice connection.")
        peerConnection?.addTrack(checkNotNull(localAudioTrack), listOf(AUDIO_STREAM_ID))
        onConnectionStateChanged(DirectCallMediaConnectionState.CONNECTING)
        if (role == RemoteDirectCallRole.CALLER) publishOffer()
    }

    override suspend fun applyRemoteSignal(signal: RemoteDirectCallSignal) {
        if (!appliedSignalIds.add(signal.signalId)) return
        when (signal) {
            is RemoteDirectCallSignal.Offer -> {
                check(currentRole == RemoteDirectCallRole.CALLEE) { "Only the called phone can accept an offer." }
                setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, signal.sessionDescription))
                publishAnswer()
            }
            is RemoteDirectCallSignal.Answer -> {
                check(currentRole == RemoteDirectCallRole.CALLER) { "Only the calling phone can accept an answer." }
                setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, signal.sessionDescription))
            }
            is RemoteDirectCallSignal.IceCandidate -> {
                val candidate = IceCandidate(
                    signal.mediaStreamIdentification,
                    signal.mediaLineIndex,
                    signal.candidate,
                )
                if (remoteDescriptionApplied) {
                    check(peerConnection?.addIceCandidate(candidate) == true) {
                        "Android rejected remote call routing data."
                    }
                } else {
                    pendingRemoteCandidates += candidate
                }
            }
        }
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    override fun setSpeakerEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferredType = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            audioManager.availableCommunicationDevices
                .firstOrNull { device -> device.type == preferredType }
                ?.let(audioManager::setCommunicationDevice)
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    override fun stop() {
        peerConnection?.close()
        peerConnection?.dispose()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnectionFactory?.dispose()
        audioDeviceModule?.release()
        peerConnection = null
        localAudioTrack = null
        audioSource = null
        peerConnectionFactory = null
        audioDeviceModule = null
        currentAccountUid = null
        currentRole = null
        localSignalConsumer = null
        connectionStateConsumer = null
        remoteDescriptionApplied = false
        pendingRemoteCandidates.clear()
        appliedSignalIds.clear()
        releaseCallAudioFocus()
    }

    private suspend fun publishOffer() {
        val connection = checkNotNull(peerConnection)
        val description = connection.createSessionDescription(
            create = { observer -> connection.createOffer(observer, audioOnlyMediaConstraints()) },
        )
        connection.setLocalDescriptionAwait(description)
        localSignalConsumer?.invoke(
            RemoteDirectCallSignal.Offer(newSignalId(), checkNotNull(currentAccountUid), description.description),
        )
    }

    private suspend fun publishAnswer() {
        val connection = checkNotNull(peerConnection)
        val description = connection.createSessionDescription(
            create = { observer -> connection.createAnswer(observer, audioOnlyMediaConstraints()) },
        )
        connection.setLocalDescriptionAwait(description)
        localSignalConsumer?.invoke(
            RemoteDirectCallSignal.Answer(newSignalId(), checkNotNull(currentAccountUid), description.description),
        )
    }

    private suspend fun setRemoteDescription(description: SessionDescription) {
        val connection = checkNotNull(peerConnection)
        connection.setRemoteDescriptionAwait(description)
        remoteDescriptionApplied = true
        pendingRemoteCandidates.forEach { candidate ->
            check(connection.addIceCandidate(candidate)) { "Android rejected queued call routing data." }
        }
        pendingRemoteCandidates.clear()
    }

    private fun createPeerConnectionObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> connectionStateConsumer?.invoke(DirectCallMediaConnectionState.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.CLOSED,
                -> connectionStateConsumer?.invoke(DirectCallMediaConnectionState.DISCONNECTED)
                PeerConnection.IceConnectionState.FAILED ->
                    connectionStateConsumer?.invoke(DirectCallMediaConnectionState.FAILED)
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            val accountUid = currentAccountUid ?: return
            localSignalConsumer?.invoke(
                RemoteDirectCallSignal.IceCandidate(
                    signalId = newSignalId(),
                    senderUid = accountUid,
                    candidate = candidate.sdp,
                    mediaStreamIdentification = candidate.sdpMid,
                    mediaLineIndex = candidate.sdpMLineIndex,
                ),
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream?) = Unit

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(dataChannel: DataChannel?) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED ->
                    connectionStateConsumer?.invoke(DirectCallMediaConnectionState.CONNECTED)
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> connectionStateConsumer?.invoke(DirectCallMediaConnectionState.DISCONNECTED)
                PeerConnection.PeerConnectionState.FAILED ->
                    connectionStateConsumer?.invoke(DirectCallMediaConnectionState.FAILED)
                else -> Unit
            }
        }
    }

    private fun requestCallAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Android audio is busy with another app or call."
        }
        audioFocusRequest = request
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerEnabled(false)
    }

    private fun releaseCallAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun audioOnlyMediaConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
    }

    private fun newSignalId(): RemoteDirectCallSignalId =
        RemoteDirectCallSignalId("signal_${UUID.randomUUID().toString().replace("-", "")}")

    private companion object {
        const val AUDIO_STREAM_ID = "synapse_direct_call"
        const val LOCAL_AUDIO_TRACK_ID = "synapse_direct_call_microphone"
        val initialized = AtomicBoolean(false)

        fun initializeWebRtc(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
                )
            }
        }
    }
}

private suspend fun PeerConnection.createSessionDescription(
    create: (SdpObserver) -> Unit,
): SessionDescription = suspendCancellableCoroutine { continuation ->
    create(
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (continuation.isActive) continuation.resume(description)
            }

            override fun onCreateFailure(message: String?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException(message ?: "Could not create call setup data."))
                }
            }

            override fun onSetSuccess() = Unit

            override fun onSetFailure(message: String?) = Unit
        },
    )
}

private suspend fun PeerConnection.setLocalDescriptionAwait(description: SessionDescription) {
    setDescriptionAwait(description, remote = false)
}

private suspend fun PeerConnection.setRemoteDescriptionAwait(description: SessionDescription) {
    setDescriptionAwait(description, remote = true)
}

private suspend fun PeerConnection.setDescriptionAwait(
    description: SessionDescription,
    remote: Boolean,
) = suspendCancellableCoroutine { continuation ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit

        override fun onCreateFailure(message: String?) = Unit

        override fun onSetSuccess() {
            if (continuation.isActive) continuation.resume(Unit)
        }

        override fun onSetFailure(message: String?) {
            if (continuation.isActive) {
                continuation.resumeWithException(IllegalStateException(message ?: "Could not apply call setup data."))
            }
        }
    }
    if (remote) setRemoteDescription(observer, description) else setLocalDescription(observer, description)
}
