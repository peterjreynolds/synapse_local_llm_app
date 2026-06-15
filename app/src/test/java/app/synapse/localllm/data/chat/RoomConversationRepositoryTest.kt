package app.synapse.localllm.data.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomConversationRepositoryTest {
    private lateinit var database: SynapseDatabase
    private lateinit var repository: RoomConversationRepository
    private lateinit var clock: IncrementingSynapseClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = IncrementingSynapseClock()
        repository = RoomConversationRepository(
            database = database,
            chatDao = database.chatDao(),
            idFactory = SynapseIdFactory(),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun submitUserMessageKeepsMessagesAfterThreadSummaryUpdate() = runTest {
        val thread = repository.ensureDefaultThread()

        repository.submitUserMessage(
            SubmitUserMessageCommand(
                threadId = thread.id,
                body = "First message",
                attachments = emptyList(),
            ),
        )
        repository.submitUserMessage(
            SubmitUserMessageCommand(
                threadId = thread.id,
                body = "Second message",
                attachments = emptyList(),
            ),
        )

        val recentMessages = repository.listRecentMessages(thread.id, limit = 10)

        assertEquals(4, recentMessages.size)
        assertEquals(
            listOf(
                ConversationRole.USER,
                ConversationRole.ASSISTANT,
                ConversationRole.USER,
                ConversationRole.ASSISTANT,
            ),
            recentMessages.map { message -> message.role },
        )
        assertEquals(MessageDeliveryState.COMPLETE, recentMessages[0].deliveryState)
        assertEquals(MessageDeliveryState.STREAMING, recentMessages[1].deliveryState)
        assertEquals("First message", recentMessages[0].body)
        assertEquals("Second message", recentMessages[2].body)
    }

    private class IncrementingSynapseClock : SynapseClock {
        private var tickMillis = 0L

        override fun now(): Instant {
            val instant = Instant.parse("2026-06-15T14:00:00Z").plusMillis(tickMillis)
            tickMillis += 10
            return instant
        }
    }
}
