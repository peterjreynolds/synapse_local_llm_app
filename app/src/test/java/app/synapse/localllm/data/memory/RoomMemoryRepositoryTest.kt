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
import app.synapse.localllm.domain.memory.MemoryScope
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
        assertTrue(retrievalBundle.refs.single().reasonCodes.contains("intent:preference"))
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

    @Test
    fun identityRecallQueryRetrievesIdentityMemory() = runTest {
        writeDurableMemory(
            text = "User's full name is Peter Joseph Reynolds.",
            kind = MemoryKind.IDENTITY,
            subject = "User",
            keywords = listOf("identity", "full", "name"),
        )

        val retrievalBundle = repository.retrieveMemories(
            query = "What is my full name?",
            limit = 5,
        )

        assertEquals(1, retrievalBundle.refs.size)
        val memory = retrievalBundle.refs.single()
        assertEquals(MemoryKind.IDENTITY, memory.kind)
        assertEquals("User's full name is Peter Joseph Reynolds.", memory.text)
        assertTrue(memory.reasonCodes.contains("intent:identity"))
        assertTrue(retrievalBundle.promptBlock.contains("[identity / global / User]"))
    }

    @Test
    fun projectRecallQueryRetrievesProjectScopedMemory() = runTest {
        writeDurableMemory(
            text = "All new proposals for Project Walby should be reviewed by Roberto Moreno.",
            kind = MemoryKind.PROJECT,
            scope = MemoryScope.PROJECT,
            subject = "Walby",
            keywords = listOf("project", "walby", "proposal", "review"),
        )

        val retrievalBundle = repository.retrieveMemories(
            query = "What do we know about Project Walby?",
            limit = 5,
        )

        assertEquals(1, retrievalBundle.refs.size)
        val memory = retrievalBundle.refs.single()
        assertEquals(MemoryKind.PROJECT, memory.kind)
        assertEquals(MemoryScope.PROJECT, memory.scope)
        assertEquals("Walby", memory.subject)
        assertTrue(memory.reasonCodes.contains("intent:project"))
        assertTrue(memory.reasonCodes.contains("scope:project"))
    }

    @Test
    fun appointmentRecallQueryRetrievesAppointmentMemory() = runTest {
        writeDurableMemory(
            text = "User has a dentist appointment tomorrow at 3 PM.",
            kind = MemoryKind.APPOINTMENT,
            keywords = listOf("appointment", "dentist", "tomorrow"),
        )

        val retrievalBundle = repository.retrieveMemories(
            query = "What appointments do I have?",
            limit = 5,
        )

        assertEquals(1, retrievalBundle.refs.size)
        assertEquals(MemoryKind.APPOINTMENT, retrievalBundle.refs.single().kind)
        assertTrue(retrievalBundle.refs.single().reasonCodes.contains("intent:appointment"))
    }

    @Test
    fun savedMemoryReviewRetrievesAllPromptVisibleMemory() = runTest {
        writeDurableMemory(
            text = "User's full name is Peter Joseph Reynolds.",
            kind = MemoryKind.IDENTITY,
        )
        writeDurableMemory(
            text = "User prefers concise Kotlin code.",
            kind = MemoryKind.PREFERENCE,
        )

        val retrievalBundle = repository.retrieveMemories(
            query = "What are my saved memories?",
            limit = 10,
        )

        assertEquals(2, retrievalBundle.refs.size)
        assertTrue(retrievalBundle.refs.all { memory -> memory.reasonCodes.contains("all-memory-review") })
    }

    private suspend fun writeDurableMemory(
        text: String,
        kind: MemoryKind = MemoryKind.PREFERENCE,
        scope: MemoryScope = MemoryScope.GLOBAL,
        subject: String? = null,
        keywords: List<String> = emptyList(),
    ) {
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
                    kind = kind,
                    text = text,
                    confidence = 0.86,
                    sourceTraceEventIds = listOf(traceEvent.id),
                    surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
                    reasonCodes = listOf("test-memory"),
                    scope = scope,
                    subject = subject,
                    keywords = keywords,
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
