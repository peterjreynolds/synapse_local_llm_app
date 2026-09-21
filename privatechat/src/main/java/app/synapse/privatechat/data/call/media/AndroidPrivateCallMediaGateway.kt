package app.synapse.privatechat.data.call.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import androidx.core.content.ContextCompat
import app.synapse.privatechat.domain.call.PrivateCallMediaGateway
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallMediaState
import app.synapse.privatechat.domain.call.PrivateCallRole
import app.synapse.privatechat.domain.call.PrivateCallSessionDescriptionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicLong

/** Native capture is serialized off the main thread; construction does not initialize WebRTC or EGL. */
class AndroidPrivateCallMediaGateway(
    context: Context,
) : PrivateCallMediaGateway,
    PrivateCallVideoRenderer {
    private val applicationContext = context.applicationContext
    private val audioRoute = AndroidPrivateCallAudioRoute(applicationContext)
    private val video = AndroidPrivateCallVideoTracks(applicationContext)
    private val operations = Mutex()
    private val generation = AtomicLong()
    private var factory: PeerConnectionFactory? = null
    private var audioDevice: AudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var connection: PeerConnection? = null
    private var role: PrivateCallRole? = null
    private var kind = PrivateCallMediaKind.VOICE
    private var remoteDescriptionApplied = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var signalConsumer: ((PrivateCallMediaSignal) -> Unit)? = null
    private var stateConsumer: ((PrivateCallMediaState) -> Unit)? = null

    override suspend fun start(
        role: PrivateCallRole,
        mediaKind: PrivateCallMediaKind,
        onSignal: (PrivateCallMediaSignal) -> Unit,
        onState: (PrivateCallMediaState) -> Unit,
    ) = withContext(Dispatchers.IO) {
        operations.withLock {
            stopNativeMedia()
            requirePermission(Manifest.permission.RECORD_AUDIO)
            if (mediaKind == PrivateCallMediaKind.VIDEO) requirePermission(Manifest.permission.CAMERA)
            val callGeneration = generation.incrementAndGet()
            this@AndroidPrivateCallMediaGateway.role = role
            kind = mediaKind
            signalConsumer = onSignal
            stateConsumer = onState
            try {
                initializeWebRtc(applicationContext)
                audioRoute.acquire { if (generation.get() == callGeneration) onState(PrivateCallMediaState.FAILED) }
                val device =
                    JavaAudioDeviceModule
                        .builder(applicationContext)
                        .setUseHardwareAcousticEchoCanceler(true)
                        .setUseHardwareNoiseSuppressor(true)
                        .createAudioDeviceModule()
                        .also { audioDevice = it }
                val factoryBuilder = PeerConnectionFactory.builder().setAudioDeviceModule(device)
                if (mediaKind == PrivateCallMediaKind.VIDEO) {
                    factoryBuilder
                        .setVideoEncoderFactory(DefaultVideoEncoderFactory(video.eglContext(), true, true))
                        .setVideoDecoderFactory(DefaultVideoDecoderFactory(video.eglContext()))
                }
                val peerFactory = factoryBuilder.createPeerConnectionFactory().also { factory = it }
                val configuration =
                    PeerConnection
                        .RTCConfiguration(
                            listOf(
                                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
                            ),
                        ).apply {
                            // This explicit direct mode exposes peer IPs and has no relay fallback.
                            iceTransportsType = PeerConnection.IceTransportsType.ALL
                            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                        }
                val peer =
                    checkNotNull(peerFactory.createPeerConnection(configuration, observer(callGeneration, onSignal, onState))) {
                        "Could not create an encrypted call."
                    }.also { connection = it }
                val source = peerFactory.createAudioSource(MediaConstraints()).also { audioSource = it }
                val track = peerFactory.createAudioTrack("synapse_private_microphone", source).also { audioTrack = it }
                peer.addTrack(track, listOf("synapse_private_call"))
                if (mediaKind == PrivateCallMediaKind.VIDEO) peer.addTrack(video.start(peerFactory), listOf("synapse_private_call"))
                onState(PrivateCallMediaState.CONNECTING)
                if (role == PrivateCallRole.OFFERER) withTimeout(10_000) { publishDescription(peer, offer = true) }
            } catch (failure: Throwable) {
                stopNativeMedia()
                throw failure
            }
        }
    }

    override suspend fun applyRemoteSignal(signal: PrivateCallMediaSignal): Unit =
        withContext(Dispatchers.IO) {
            operations.withLock {
                val peer = checkNotNull(connection) { "Call is no longer active." }
                withTimeout(10_000) {
                    when (signal) {
                        is PrivateCallMediaSignal.Offer -> {
                            check(role == PrivateCallRole.ANSWERER && !remoteDescriptionApplied) { "Unexpected call offer." }
                            applyDescription(peer, SessionDescription.Type.OFFER, signal.sdp)
                            publishDescription(peer, offer = false)
                        }
                        is PrivateCallMediaSignal.Answer -> {
                            check(role == PrivateCallRole.OFFERER && !remoteDescriptionApplied) { "Unexpected call answer." }
                            applyDescription(peer, SessionDescription.Type.ANSWER, signal.sdp)
                        }
                        is PrivateCallMediaSignal.IceCandidate -> {
                            PrivateCallSessionDescriptionPolicy.requireValidCandidate(signal)
                            val candidate = IceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidate)
                            if (remoteDescriptionApplied) {
                                check(peer.addIceCandidate(candidate)) { "Call route was rejected." }
                            } else {
                                check(pendingCandidates.size < 128) { "Too many pending call routes." }
                                pendingCandidates.add(candidate)
                                Unit
                            }
                        }
                    }
                }
            }
        }

    override suspend fun stop() {
        generation.incrementAndGet()
        withContext(NonCancellable + Dispatchers.IO) { operations.withLock { stopNativeMedia() } }
    }

    override suspend fun setMicrophoneMuted(muted: Boolean) =
        withContext(Dispatchers.IO) {
            operations.withLock {
                audioTrack?.setEnabled(!muted)
                Unit
            }
        }

    override suspend fun setSpeakerEnabled(enabled: Boolean) =
        withContext(Dispatchers.IO) {
            operations.withLock { audioRoute.setSpeakerEnabled(enabled) }
        }

    override suspend fun setCameraEnabled(enabled: Boolean) =
        withContext(Dispatchers.IO) {
            operations.withLock {
                check(kind == PrivateCallMediaKind.VIDEO)
                video.setCameraEnabled(enabled)
            }
        }

    override suspend fun switchCamera() =
        withContext(Dispatchers.IO) {
            operations.withLock {
                check(kind == PrivateCallMediaKind.VIDEO)
                video.switchCamera()
            }
        }

    override fun createRendererView(
        context: Context,
        target: PrivateCallVideoTarget,
    ): View = video.createRendererView(context, target)

    override fun releaseRendererView(view: View) = video.releaseRendererView(view)

    private suspend fun publishDescription(
        peer: PeerConnection,
        offer: Boolean,
    ) {
        val constraints = MediaConstraints()
        val description =
            createPrivateCallDescription { observer ->
                if (offer) peer.createOffer(observer, constraints) else peer.createAnswer(observer, constraints)
            }
        PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(description.description, kind)
        peer.setPrivateCallDescription(description, remote = false)
        signalConsumer?.invoke(
            if (offer) PrivateCallMediaSignal.Offer(description.description) else PrivateCallMediaSignal.Answer(description.description),
        )
    }

    private suspend fun applyDescription(
        peer: PeerConnection,
        type: SessionDescription.Type,
        sdp: String,
    ) {
        PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(sdp, kind)
        peer.setPrivateCallDescription(SessionDescription(type, sdp), remote = true)
        remoteDescriptionApplied = true
        pendingCandidates.forEach { check(peer.addIceCandidate(it)) { "Queued call route was rejected." } }
        pendingCandidates.clear()
    }

    private fun stopNativeMedia() {
        generation.incrementAndGet()
        val closedConsumer = stateConsumer
        signalConsumer = null
        stateConsumer = null
        // Do not touch audio routing during idle sign-in: past startup ANRs came from idle teardown.
        if (connection == null && factory == null && audioDevice == null) {
            audioRoute.release()
            return
        }
        video.stop()
        connection?.close()
        connection?.dispose()
        connection = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        factory?.dispose()
        factory = null
        video.releaseFactoryContext()
        audioDevice?.release()
        audioDevice = null
        audioRoute.release()
        role = null
        remoteDescriptionApplied = false
        pendingCandidates.clear()
        closedConsumer?.invoke(PrivateCallMediaState.CLOSED)
    }

    private fun observer(
        expectedGeneration: Long,
        onSignal: (PrivateCallMediaSignal) -> Unit,
        onState: (PrivateCallMediaState) -> Unit,
    ): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

            override fun onIceCandidate(candidate: IceCandidate) {
                if (generation.get() ==
                    expectedGeneration
                ) {
                    onSignal(PrivateCallMediaSignal.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

            override fun onAddStream(stream: MediaStream?) = Unit

            override fun onRemoveStream(stream: MediaStream?) = Unit

            override fun onDataChannel(channel: DataChannel?) {
                channel?.close()
            }

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(
                receiver: RtpReceiver?,
                streams: Array<out MediaStream>?,
            ) {
                if (generation.get() == expectedGeneration &&
                    kind == PrivateCallMediaKind.VIDEO
                ) {
                    video.attachRemote(receiver?.track() as? VideoTrack)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                if (generation.get() == expectedGeneration &&
                    kind == PrivateCallMediaKind.VIDEO
                ) {
                    video.attachRemote(transceiver?.receiver?.track() as? VideoTrack)
                }
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                if (generation.get() != expectedGeneration) return
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> onState(PrivateCallMediaState.CONNECTED)
                    PeerConnection.PeerConnectionState.DISCONNECTED -> onState(PrivateCallMediaState.DISCONNECTED)
                    PeerConnection.PeerConnectionState.FAILED -> onState(PrivateCallMediaState.FAILED)
                    PeerConnection.PeerConnectionState.CLOSED -> onState(PrivateCallMediaState.CLOSED)
                    else -> Unit
                }
            }
        }

    private fun requirePermission(permission: String) {
        check(ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED) {
            "Grant call permissions before starting media."
        }
    }

    private companion object {
        var initialized = false

        @Synchronized fun initializeWebRtc(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .setEnableInternalTracer(false)
                    .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
                    .createInitializationOptions(),
            )
            initialized = true
        }
    }
}
