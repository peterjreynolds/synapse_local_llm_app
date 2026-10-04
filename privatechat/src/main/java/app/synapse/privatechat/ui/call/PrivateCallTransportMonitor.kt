package app.synapse.privatechat.ui.call

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.call.PrivateCallPoll
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallSignalingGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Polling is a signaling lease, not a claim that background push delivery exists. */
internal class PrivateCallTransportMonitor(
    private val signaling: PrivateCallSignalingGateway,
    private val clock: Clock,
) {
    private var job: Job? = null

    fun start(
        scope: CoroutineScope,
        accountId: PrivateAccountId,
        callRevision: () -> Long,
        heartbeatCall: () -> UUID?,
        onHeartbeat: (PrivateCallSession) -> Unit,
        onPoll: suspend (PrivateCallPoll, Long) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                var nextHeartbeat = Instant.MIN
                while (isActive) {
                    try {
                        val activeCallId = heartbeatCall()
                        if (activeCallId != null && !clock.instant().isBefore(nextHeartbeat)) {
                            val receipt = withTimeout(10_000) { signaling.heartbeat(accountId, activeCallId) }
                            if (!isActive) return@launch
                            onHeartbeat(receipt)
                            nextHeartbeat = clock.instant().plusSeconds(10)
                        }
                        val revisionAtPollStart = callRevision()
                        val poll = withTimeout(10_000) { signaling.poll(accountId) }
                        if (!isActive) return@launch
                        onPoll(poll, revisionAtPollStart)
                    } catch (timeout: TimeoutCancellationException) {
                        if (isActive) onFailure(timeout)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (isActive) onFailure(failure)
                    }
                    delay(2_000)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
