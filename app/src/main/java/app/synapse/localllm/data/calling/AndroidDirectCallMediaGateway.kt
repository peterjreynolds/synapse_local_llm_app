package app.synapse.localllm.data.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.view.View
import app.synapse.localllm.domain.calling.DirectCallMediaConnectionState
import app.synapse.localllm.domain.calling.DirectCallMediaGateway
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
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
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

class AndroidDirectCallMediaGateway(context: Context) :
    DirectCallMediaGateway,
    DirectCallVideoRendererController {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val videoEglBase = EglBase.create()
    private val videoRendererLock = Any()
    private val videoRendererTargets = mutableMapOf<SurfaceViewRenderer, DirectCallVideoRendererTarget>()
    private var audioDeviceModule: AudioDeviceModule? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var currentAccountUid: RemoteAccountUid? = null
    private var currentMediaKind: RemoteDirectCallMediaKind = RemoteDirectCallMediaKind.AUDIO
    private var currentRole: RemoteDirectCallRole? = null
    private var localSignalConsumer: ((RemoteDirectCallSignal) -> Unit)? = null
    private var connectionStateConsumer: ((DirectCallMediaConnectionState) -> Unit)? = null
    private var remoteDescriptionApplied = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val appliedSignalIds = mutableSetOf<RemoteDirectCallSignalId>()

    override suspend fun start(
        accountUid: RemoteAccountUid,
        mediaKind: RemoteDirectCallMediaKind,
        role: RemoteDirectCallRole,
        onLocalSignal: (RemoteDirectCallSignal) -> Unit,
        onConnectionStateChanged: (DirectCallMediaConnectionState) -> Unit,
    ) {
        stop()
        initializeWebRtc(applicationContext)
        currentAccountUid = accountUid
        currentMediaKind = mediaKind
        currentRole = role
        localSignalConsumer = onLocalSignal
        connectionStateConsumer = onConnectionStateChanged
        requestCallAudioFocus()
        audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val factoryBuilder = PeerConnectionFactory.builder().setAudioDeviceModule(audioDeviceModule)
        if (mediaKind == RemoteDirectCallMediaKind.VIDEO) {
            factoryBuilder
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(videoEglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(videoEglBase.eglBaseContext))
        }
        peerConnectionFactory = factoryBuilder.createPeerConnectionFactory()
        val factory = checkNotNull(peerConnectionFactory)
        audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(true)
        }
        if (mediaKind == RemoteDirectCallMediaKind.VIDEO) initializeCameraVideo(factory)
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
            ?: error("Android could not create the call connection.")
        peerConnection?.addTrack(checkNotNull(localAudioTrack), listOf(AUDIO_STREAM_ID))
        localVideoTrack?.let { track -> peerConnection?.addTrack(track, listOf(VIDEO_STREAM_ID)) }
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

    override fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    override fun switchCamera() {
        videoCapturer?.switchCamera(null)
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
        detachVideoTracksFromRenderers()
        runCatching { videoCapturer?.stopCapture() }
            .onFailure { failure -> if (failure is InterruptedException) Thread.currentThread().interrupt() }
        videoCapturer?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        localVideoTrack?.dispose()
        videoSource?.dispose()
        surfaceTextureHelper?.dispose()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnectionFactory?.dispose()
        audioDeviceModule?.release()
        peerConnection = null
        localAudioTrack = null
        localVideoTrack = null
        remoteVideoTrack = null
        videoSource = null
        videoCapturer = null
        surfaceTextureHelper = null
        audioSource = null
        peerConnectionFactory = null
        audioDeviceModule = null
        currentAccountUid = null
        currentMediaKind = RemoteDirectCallMediaKind.AUDIO
        currentRole = null
        localSignalConsumer = null
        connectionStateConsumer = null
        remoteDescriptionApplied = false
        pendingRemoteCandidates.clear()
        appliedSignalIds.clear()
        releaseCallAudioFocus()
    }

    override fun createRendererView(
        context: Context,
        target: DirectCallVideoRendererTarget,
    ): View = SurfaceViewRenderer(context).apply {
        init(videoEglBase.eglBaseContext, null)
        setEnableHardwareScaler(true)
        setMirror(target == DirectCallVideoRendererTarget.LOCAL_PREVIEW)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        if (target == DirectCallVideoRendererTarget.LOCAL_PREVIEW) setZOrderMediaOverlay(true)
        synchronized(videoRendererLock) {
            videoRendererTargets[this] = target
            videoTrackFor(target)?.addSink(this)
        }
    }

    override fun releaseRendererView(view: View) {
        val renderer = view as? SurfaceViewRenderer ?: return
        synchronized(videoRendererLock) {
            val target = videoRendererTargets.remove(renderer)
            if (target != null) videoTrackFor(target)?.removeSink(renderer)
        }
        renderer.release()
    }

    private suspend fun publishOffer() {
        val connection = checkNotNull(peerConnection)
        val description = connection.createSessionDescription(
            create = { observer -> connection.createOffer(observer, callMediaConstraints()) },
        )
        connection.setLocalDescriptionAwait(description)
        localSignalConsumer?.invoke(
            RemoteDirectCallSignal.Offer(newSignalId(), checkNotNull(currentAccountUid), description.description),
        )
    }

    private suspend fun publishAnswer() {
        val connection = checkNotNull(peerConnection)
        val description = connection.createSessionDescription(
            create = { observer -> connection.createAnswer(observer, callMediaConstraints()) },
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

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            attachRemoteVideoTrack(receiver?.track() as? VideoTrack)
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            attachRemoteVideoTrack(transceiver?.receiver?.track() as? VideoTrack)
        }

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

    private fun callMediaConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        mandatory += MediaConstraints.KeyValuePair(
            "OfferToReceiveVideo",
            (currentMediaKind == RemoteDirectCallMediaKind.VIDEO).toString(),
        )
    }

    private fun initializeCameraVideo(factory: PeerConnectionFactory) {
        val capturer = createCameraVideoCapturer()
        val source = factory.createVideoSource(false)
        val textureHelper = checkNotNull(
            SurfaceTextureHelper.create(CAMERA_CAPTURE_THREAD_NAME, videoEglBase.eglBaseContext),
        ) { "Android could not initialize the camera renderer." }
        videoCapturer = capturer
        videoSource = source
        surfaceTextureHelper = textureHelper
        capturer.initialize(textureHelper, applicationContext, source.capturerObserver)
        capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FRAMES_PER_SECOND)
        attachLocalVideoTrack(factory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, source).apply { setEnabled(true) })
    }

    private fun createCameraVideoCapturer(): CameraVideoCapturer {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(applicationContext)) {
            Camera2Enumerator(applicationContext)
        } else {
            Camera1Enumerator(true)
        }
        val deviceNames = enumerator.deviceNames
        val deviceName = deviceNames.firstOrNull(enumerator::isFrontFacing) ?: deviceNames.firstOrNull()
            ?: error("This phone does not have an available camera.")
        return enumerator.createCapturer(deviceName, null)
            ?: error("Android could not open the selected camera.")
    }

    private fun attachLocalVideoTrack(track: VideoTrack) {
        synchronized(videoRendererLock) {
            localVideoTrack = track
            videoRendererTargets
                .filterValues { target -> target == DirectCallVideoRendererTarget.LOCAL_PREVIEW }
                .keys
                .forEach(track::addSink)
        }
    }

    private fun attachRemoteVideoTrack(track: VideoTrack?) {
        if (track == null) return
        synchronized(videoRendererLock) {
            if (remoteVideoTrack === track) return
            remoteVideoTrack?.let { previousTrack ->
                videoRendererTargets
                    .filterValues { target -> target == DirectCallVideoRendererTarget.REMOTE_PARTICIPANT }
                    .keys
                    .forEach(previousTrack::removeSink)
            }
            remoteVideoTrack = track
            videoRendererTargets
                .filterValues { target -> target == DirectCallVideoRendererTarget.REMOTE_PARTICIPANT }
                .keys
                .forEach(track::addSink)
        }
    }

    private fun detachVideoTracksFromRenderers() {
        synchronized(videoRendererLock) {
            videoRendererTargets.forEach { (renderer, target) ->
                videoTrackFor(target)?.removeSink(renderer)
            }
        }
    }

    private fun videoTrackFor(target: DirectCallVideoRendererTarget): VideoTrack? = when (target) {
        DirectCallVideoRendererTarget.LOCAL_PREVIEW -> localVideoTrack
        DirectCallVideoRendererTarget.REMOTE_PARTICIPANT -> remoteVideoTrack
    }

    private fun newSignalId(): RemoteDirectCallSignalId =
        RemoteDirectCallSignalId("signal_${UUID.randomUUID().toString().replace("-", "")}")

    private companion object {
        const val AUDIO_STREAM_ID = "synapse_direct_call"
        const val VIDEO_STREAM_ID = "synapse_direct_call_video"
        const val LOCAL_AUDIO_TRACK_ID = "synapse_direct_call_microphone"
        const val LOCAL_VIDEO_TRACK_ID = "synapse_direct_call_camera"
        const val CAMERA_CAPTURE_THREAD_NAME = "SynapseCameraCapture"
        const val VIDEO_WIDTH = 1_280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FRAMES_PER_SECOND = 30
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
