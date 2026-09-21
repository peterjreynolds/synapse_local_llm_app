package app.synapse.privatechat.domain.call

import java.util.UUID

interface PrivateCallForegroundGateway {
    suspend fun start(
        callId: UUID,
        mediaKind: PrivateCallMediaKind,
        onHangUp: () -> Unit,
    )

    fun stop(callId: UUID)
}
