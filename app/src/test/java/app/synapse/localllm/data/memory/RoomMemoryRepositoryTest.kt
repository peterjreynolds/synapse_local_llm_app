package app.synapse.localllm.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryWriteDecision
import app.synapse.localllm.domain.memory.MemoryWriteOutcome
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.memory.TraceEventRecord
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMemoryRepositoryTest {
    private lateinit var database: SynapseDatabase
    private lateinit var repository: RoomMemoryRepository
    private lateinit var clock: IncrementingSynapseClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = IncrementingSynapseClock()
        repository = RoomMemoryRepository(
            database = database,
            memoryDao = database.memoryDao(),
            idFactory = SynapseIdFactory(),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun personalRecallQueryRetrievesPreferenceWithoutTokenOverlap() = runTest {
        writeDurableMemory("User I like pizza.")

        val retrievalBundle = repository.retrieveMemories(
            query = "What is my favorite food?",
            limit = 5,
        )

        assertEquals(1, retrievalBundle.refs.size)
        assertEquals("User I like pizza.", retrievalBundle.refs.single().text)
        assertTrue(retrievalBundle.refs.single().reasonCodes.contains("personal-memory-recall"))
        assertTrue(retrievalBundle.promptBlock.contains("User I like pizza."))
    }

    @Test
    fun lexicalQueryRetrievesFavoriteFoodMemory() = runTest {
        writeDurableMemory("User's favorite food is sushi.")

        val retrievalBundle = repository.retrieveMemories(
            query = "What is my favorite food?",
            limit = 5,
        )

        assertEquals(1, retrievalBundle.refs.size)
        assertEquals("User's favorite food is sushi.", retrievalBundle.refs.single().text)
        assertTrue(retrievalBundle.refs.single().reasonCodes.any { reason -> reason.startsWith("token:") })
    }

    private suspend fun writeDurableMemory(text: String) {
        val traceEvent = TraceEventRecord(
            id = TraceEventId("trace-${clock.tickCount}"),
            sourceMessageId = ChatMessageId("message-${clock.tickCount}"),
            role = ConversationRole.USER,
            text = text,
            observedAt = clock.now(),
        )
        repository.appendTraceEvent(traceEvent)
        repository.persistMemoryDecision(
            traceEvent = traceEvent,
            decision = MemoryWriteDecision(
                outcome = MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN,
                candidate = MemoryClaimCandidate(
                    kind = MemoryKind.PREFERENCE,
                    text = text,
                    confidence = 0.86,
                    sourceTraceEventIds = listOf(traceEvent.id),
                    surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
                    reasonCodes = listOf("test-memory"),
                ),
                reason = "Test memory accepted.",
                storageHealthSnapshot = null,
            ),
        )
    }

    private class IncrementingSynapseClock : SynapseClock {
        var tickCount = 0
            private set

        override fun now(): Instant {
            val instant = Instant.parse("2026-06-17T12:00:00Z").plusMillis(tickCount.toLong())
            tickCount += 1
            return instant
        }
    }
}
