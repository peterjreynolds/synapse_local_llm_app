package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.chat.PrivateChatObservation
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateChatPollingFlowTest {
    @Test
    fun pollingBacksOffWithinABoundThenResetsAndEmitsRecovery() =
        runTest {
            val delays = mutableListOf<Long>()
            var attempt = 0
            val observations =
                observePrivateChatSnapshots(
                    waitForNextPoll = { delays += it },
                    loadObservation = {
                        attempt++
                        if (attempt <= 5) PrivateChatObservation.TransportUnavailable else PrivateChatObservation.Available("confirmed")
                    },
                ).take(7).toList()
            assertEquals(listOf(5000L, 10000L, 20000L, 30000L, 30000L, 5000L), delays)
            assertEquals(PrivateChatObservation.Available("confirmed"), observations[5])
            assertEquals(PrivateChatObservation.Available("confirmed"), observations[6])
        }
}
