package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.chat.PrivateChatObservation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/** Each observer owns its retry delay; social failures cannot back off the message feed. */
internal fun <Snapshot> observePrivateChatSnapshots(
    waitForNextPoll: suspend (Long) -> Unit,
    loadObservation: suspend () -> PrivateChatObservation<Snapshot>,
): Flow<PrivateChatObservation<Snapshot>> =
    flow {
        var retryMillis = 5_000L
        while (currentCoroutineContext().isActive) {
            val observation = loadObservation()
            emit(observation)
            if (observation is PrivateChatObservation.Available) retryMillis = 5_000L
            waitForNextPoll(retryMillis)
            if (observation is PrivateChatObservation.TransportUnavailable) retryMillis = (retryMillis * 2).coerceAtMost(30_000L)
        }
    }
