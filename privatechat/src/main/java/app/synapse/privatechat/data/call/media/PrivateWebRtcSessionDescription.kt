package app.synapse.privatechat.data.call.media

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun createPrivateCallDescription(create: (SdpObserver) -> Unit): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        create(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }

                override fun onCreateFailure(message: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Could not create encrypted call setup."),
                        )
                    }
                }

                override fun onSetSuccess() = Unit

                override fun onSetFailure(message: String?) = Unit
            },
        )
    }

internal suspend fun PeerConnection.setPrivateCallDescription(
    description: SessionDescription,
    remote: Boolean,
) {
    suspendCancellableCoroutine { continuation ->
        val observer =
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) = Unit

                override fun onCreateFailure(message: String?) = Unit

                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(message: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Could not apply encrypted call setup."),
                        )
                    }
                }
            }
        if (remote) setRemoteDescription(observer, description) else setLocalDescription(observer, description)
    }
}
