package app.synapse.privatechat.domain.call

enum class PrivateCallRole { OFFERER, ANSWERER }

enum class PrivateCallMediaKind { VOICE, VIDEO }

enum class PrivateCallMediaState { CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

sealed interface PrivateCallMediaSignal {
    data class Offer(
        val sdp: String,
    ) : PrivateCallMediaSignal {
        override fun toString(): String = "Offer(sdp=[REDACTED])"
    }

    data class Answer(
        val sdp: String,
    ) : PrivateCallMediaSignal {
        override fun toString(): String = "Answer(sdp=[REDACTED])"
    }

    data class IceCandidate(
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String,
    ) : PrivateCallMediaSignal {
        override fun toString(): String = "IceCandidate(route=[REDACTED])"
    }
}

/** Direct calls reveal network addresses to the peer; the caller must explicitly consent. */
interface PrivateCallMediaGateway {
    suspend fun start(
        role: PrivateCallRole,
        mediaKind: PrivateCallMediaKind,
        onSignal: (PrivateCallMediaSignal) -> Unit,
        onState: (PrivateCallMediaState) -> Unit,
    )

    suspend fun applyRemoteSignal(signal: PrivateCallMediaSignal)

    suspend fun stop()

    suspend fun setMicrophoneMuted(muted: Boolean)

    suspend fun setSpeakerEnabled(enabled: Boolean)

    suspend fun setCameraEnabled(enabled: Boolean)

    suspend fun switchCamera()
}
