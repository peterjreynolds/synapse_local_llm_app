package app.synapse.privatechat.data.call.media

import android.content.Context
import android.view.View
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

internal class AndroidPrivateCallVideoTracks(
    private val context: Context,
    private val createEgl: () -> EglBase = EglBase::create,
) : PrivateCallVideoRenderer {
    private var egl: EglBase? = null
    private var source: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var texture: SurfaceTextureHelper? = null
    private var localTrack: VideoTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var captureEnabled = false
    private var factoryOwnsContext = false
    private val rendererLock = Any()
    private val renderers = mutableMapOf<SurfaceViewRenderer, PrivateCallVideoTarget>()

    fun eglContext(): EglBase.Context =
        synchronized(rendererLock) {
            factoryOwnsContext = true
            checkNotNull(egl ?: createEgl().also { egl = it }).eglBaseContext
        }

    fun start(factory: PeerConnectionFactory): VideoTrack {
        val camera2Supported = Camera2Enumerator.isSupported(context)
        val enumerator: CameraEnumerator =
            if (camera2Supported) {
                Camera2Enumerator(context)
            } else {
                Camera1Enumerator(true)
            }
        val names = enumerator.deviceNames
        val selected =
            names.firstOrNull(enumerator::isFrontFacing) ?: names.firstOrNull()
                ?: error("No camera is available.")
        val camera = checkNotNull(enumerator.createCapturer(selected, null)) { "Could not open the camera." }
        capturer = camera
        val videoSource = factory.createVideoSource(false).also { source = it }
        val surfaceTexture = checkNotNull(SurfaceTextureHelper.create("SynapsePrivateCamera", eglContext())).also { texture = it }
        camera.initialize(surfaceTexture, context, videoSource.capturerObserver)
        camera.startCapture(640, 480, 24)
        captureEnabled = true
        val track = factory.createVideoTrack("synapse_private_camera", videoSource)
        synchronized(rendererLock) {
            localTrack = track
            renderers.filterValues { it == PrivateCallVideoTarget.LOCAL_PREVIEW }.keys.forEach(track::addSink)
        }
        return track
    }

    fun attachRemote(track: VideoTrack?) {
        if (track == null) return
        synchronized(rendererLock) {
            if (track === remoteTrack) return
            renderers.filterValues { it == PrivateCallVideoTarget.REMOTE_PARTICIPANT }.keys.forEach { renderer ->
                remoteTrack?.removeSink(renderer)
                track.addSink(renderer)
            }
            remoteTrack = track
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        if (enabled == captureEnabled) return
        val camera = checkNotNull(capturer) { "Camera is not active." }
        if (enabled) {
            camera.startCapture(640, 480, 24)
            localTrack?.setEnabled(true)
        } else {
            localTrack?.setEnabled(false)
            camera.stopCapture()
        }
        captureEnabled = enabled
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    fun stop() {
        synchronized(rendererLock) {
            renderers.forEach { (renderer, target) -> trackFor(target)?.removeSink(renderer) }
            remoteTrack = null
        }
        try {
            capturer?.stopCapture()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        capturer?.dispose()
        capturer = null
        captureEnabled = false
        localTrack?.dispose()
        localTrack = null
        source?.dispose()
        source = null
        texture?.dispose()
        texture = null
        // The encoder factory and remaining Compose renderers must release their ownership before EGL.
    }

    fun releaseFactoryContext() =
        synchronized(rendererLock) {
            factoryOwnsContext = false
            releaseUnusedContext()
        }

    override fun createRendererView(
        context: Context,
        target: PrivateCallVideoTarget,
    ): View =
        SurfaceViewRenderer(context).apply {
            synchronized(rendererLock) {
                init(checkNotNull(egl) { "Start a video call before creating renderers." }.eglBaseContext, null)
                setMirror(target == PrivateCallVideoTarget.LOCAL_PREVIEW)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                if (target == PrivateCallVideoTarget.LOCAL_PREVIEW) setZOrderMediaOverlay(true)
                renderers[this] = target
                trackFor(target)?.addSink(this)
            }
        }

    override fun releaseRendererView(view: View) {
        val renderer = view as? SurfaceViewRenderer ?: return
        synchronized(rendererLock) {
            renderers.remove(renderer)?.let { trackFor(it)?.removeSink(renderer) }
            renderer.release()
            releaseUnusedContext()
        }
    }

    private fun releaseUnusedContext() {
        if (factoryOwnsContext || localTrack != null || renderers.isNotEmpty()) return
        egl?.release()
        egl = null
    }

    private fun trackFor(target: PrivateCallVideoTarget): VideoTrack? =
        if (target == PrivateCallVideoTarget.LOCAL_PREVIEW) localTrack else remoteTrack
}
