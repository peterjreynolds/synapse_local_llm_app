package app.synapse.privatechat.ui.call

import app.synapse.privatechat.domain.call.PrivateCallAlertGateway
import app.synapse.privatechat.domain.call.PrivateCallForegroundGateway
import app.synapse.privatechat.domain.call.PrivateCallMediaGateway
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallMediaState
import app.synapse.privatechat.domain.call.PrivateCallRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Sensor teardown is independent of signaling availability and precedes remote hang-up. */
internal class PrivateCallMediaSession(
    val media: PrivateCallMediaGateway,
    val alerts: PrivateCallAlertGateway,
    private val foreground: PrivateCallForegroundGateway,
) {
    private var callId: UUID? = null
    private val lifecycleMutex = Mutex()

    @Volatile private var generation = 0L

    suspend fun start(
        id: UUID,
        role: PrivateCallRole,
        kind: PrivateCallMediaKind,
        onHangUp: () -> Unit,
        onSignal: (PrivateCallMediaSignal) -> Unit,
        onState: (PrivateCallMediaState) -> Unit,
    ) = lifecycleMutex.withLock {
        check(callId == null) { "Another local call owns capture" }
        callId = id
        val expectedGeneration = ++generation
        try {
            withTimeout(15_000) {
                foreground.start(id, kind, onHangUp)
                currentCoroutineContext().ensureActive()
                if (generation != expectedGeneration) throw CancellationException("Call start was superseded")
                media.start(
                    role,
                    kind,
                    onSignal = { signal -> if (generation == expectedGeneration) onSignal(signal) },
                    onState = { state -> if (generation == expectedGeneration) onState(state) },
                )
                currentCoroutineContext().ensureActive()
                if (generation != expectedGeneration) throw CancellationException("Call start was superseded")
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) { stopLocked() }
            throw failure
        }
    }

    fun silenceAlerts() = alerts.stop()

    suspend fun stop() {
        generation++
        alerts.stop()
        withContext(NonCancellable) { lifecycleMutex.withLock { stopLocked() } }
    }

    private suspend fun stopLocked() {
        val stoppingCallId = callId
        callId = null
        try {
            withContext(NonCancellable) { media.stop() }
        } finally {
            stoppingCallId?.let(foreground::stop)
        }
    }
}
