package app.synapse.localllm.domain.calling

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteDirectCallId
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
import app.synapse.localllm.domain.remote.RemoteDirectCallRole
import app.synapse.localllm.domain.remote.RemoteDirectCallSignal

enum class DirectCallMediaConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

interface DirectCallAlertGateway {
    fun startOutgoingRingback()

    fun startIncomingRingtone(expiresAtMillis: Long)

    fun stop()
}

interface DirectCallMediaGateway {
    suspend fun start(
        accountUid: RemoteAccountUid,
        mediaKind: RemoteDirectCallMediaKind,
        role: RemoteDirectCallRole,
        onLocalSignal: (RemoteDirectCallSignal) -> Unit,
        onConnectionStateChanged: (DirectCallMediaConnectionState) -> Unit,
    )

    suspend fun applyRemoteSignal(signal: RemoteDirectCallSignal)

    fun setMicrophoneMuted(muted: Boolean)

    fun setCameraEnabled(enabled: Boolean)

    fun switchCamera()

    fun setSpeakerEnabled(enabled: Boolean)

    fun stop()
}

interface DirectCallForegroundController {
    fun start(
        callId: RemoteDirectCallId,
        mediaKind: RemoteDirectCallMediaKind,
    )

    fun stop()

    fun dismissIncomingNotification(callId: RemoteDirectCallId)
}
