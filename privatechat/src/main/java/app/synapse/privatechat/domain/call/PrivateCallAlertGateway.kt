package app.synapse.privatechat.domain.call

import java.time.Instant

interface PrivateCallAlertGateway {
    fun startOutgoing(expiresAt: Instant)

    fun startIncoming(expiresAt: Instant)

    fun stop()
}
