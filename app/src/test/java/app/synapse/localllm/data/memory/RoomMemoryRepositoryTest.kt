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
import app.synapse.localllm.domain.memory.MemoryClaimDomain
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryReviewFilter
import app.synapse.localllm.domain.memory.MemorySensitivity
import app.synapse.localllm.domain.memory.MemoryScope
import app.synapse.localllm.domain.memory.MemoryStatus
import app.synapse.localllm.domain.memory.MemoryWriteDecision
import app.synapse.localllm.domain.memory.MemoryWriteIntent
import app.synapse.localllm.domain.memory.MemoryWriteOutcome
import app.synapse.localllm.domain.memory.MemoryWriteReceipt
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
            text = "User's full name is Jordan Taylor.",
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
        assertEquals("User's full name is Jordan Taylor.", memory.text)
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
            text = "User's full name is Jordan Taylor.",
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

    @Test
    fun sameClaimKeySupersedesOlderActiveMemory() = runTest {
        writeDurableMemory(
            text = "User's full name is Jordan Blake.",
            kind = MemoryKind.IDENTITY,
            subject = "User",
            keywords = listOf("identity", "full", "name"),
            claimKey = "user.identity.self.full_name",
        )

        val receipt = writeDurableMemory(
            text = "User's full name is Jordan Taylor.",
            kind = MemoryKind.IDENTITY,
            subject = "User",
            keywords = listOf("identity", "full", "name"),
            claimKey = "user.identity.self.full_name",
        )

        assertEquals(MemoryWriteOutcome.MEMORY_SUPERSEDED, receipt.outcome)
        val retrievalBundle = repository.retrieveMemories(
            query = "What is my full name?",
            limit = 5,
        )
        assertEquals(1, retrievalBundle.refs.size)
        assertEquals("User's full name is Jordan Taylor.", retrievalBundle.refs.single().text)
        assertEquals("user.identity.self.full_name", retrievalBundle.refs.single().claimKey)

        val inactiveMemories = repository.listMemoriesForReview(MemoryReviewFilter.INACTIVE, limit = 10)
        assertEquals(1, inactiveMemories.size)
        assertEquals(MemoryStatus.SUPERSEDED, inactiveMemories.single().status)
        assertEquals("User's full name is Jordan Blake.", inactiveMemories.single().text)
    }

    @Test
    fun exactClaimKeyRefreshReturnsUpdatedReceiptWithoutDuplicateActiveMemory() = runTest {
        writeDurableMemory(
            text = "User's favorite food is pizza.",
            kind = MemoryKind.PREFERENCE,
            keywords = listOf("favorite", "food", "pizza"),
            claimKey = "user.preference.food.favorite",
        )

        val receipt = writeDurableMemory(
            text = "User's favorite food is pizza.",
            kind = MemoryKind.PREFERENCE,
            keywords = listOf("favorite", "food", "pizza"),
            claimKey = "user.preference.food.favorite",
        )

        assertEquals(MemoryWriteOutcome.MEMORY_UPDATED, receipt.outcome)
        val activeMemories = repository.listMemoriesForReview(MemoryReviewFilter.ACTIVE, limit = 10)
        assertEquals(1, activeMemories.size)
        assertEquals("User's favorite food is pizza.", activeMemories.single().text)
    }

    @Test
    fun quarantinedMemoryIsReviewableButNotPromptVisible() = runTest {
        val receipt = writeDurableMemory(
            text = "User's address is 123 Example Street.",
            kind = MemoryKind.IDENTITY,
            domain = MemoryClaimDomain.IDENTITY,
            subject = "self",
            predicate = "address",
            value = "123 Example Street",
            keywords = listOf("identity", "address"),
            claimKey = "user.identity.self.address",
            requestedOutcome = MemoryWriteOutcome.REQUIRES_CONFIRMATION,
        )

        assertEquals(MemoryWriteOutcome.REQUIRES_CONFIRMATION, receipt.outcome)
        assertTrue(repository.retrieveMemories("What is my address?", limit = 5).refs.isEmpty())

        val reviewMemories = repository.listMemoriesForReview(MemoryReviewFilter.REVIEW_NEEDED, limit = 10)
        assertEquals(1, reviewMemories.size)
        assertEquals(MemoryStatus.QUARANTINED, reviewMemories.single().status)
        assertEquals("address", reviewMemories.single().predicate)
    }

    @Test
    fun implicitConflictGoesToReviewWithoutReplacingActiveClaim() = runTest {
        writeDurableMemory(
            text = "For Stuart, diarization is the main priority.",
            kind = MemoryKind.PROJECT,
            domain = MemoryClaimDomain.PROJECT,
            scope = MemoryScope.PROJECT,
            subject = "Stuart",
            predicate = "priority",
            value = "diarization is the main priority",
            keywords = listOf("stuart", "diarization", "priority"),
            claimKey = "project.project.stuart.priority",
        )

        val receipt = writeDurableMemory(
            text = "For Stuart, visual design is the main priority.",
            kind = MemoryKind.PROJECT,
            domain = MemoryClaimDomain.PROJECT,
            scope = MemoryScope.PROJECT,
            subject = "Stuart",
            predicate = "priority",
            value = "visual design is the main priority",
            keywords = listOf("stuart", "visual", "design", "priority"),
            claimKey = "project.project.stuart.priority",
            requestedOutcome = MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN,
            writeIntent = MemoryWriteIntent.IMPLICIT_CANDIDATE,
        )

        assertEquals(MemoryWriteOutcome.QUARANTINED, receipt.outcome)
        val activeMemories = repository.listMemoriesForReview(MemoryReviewFilter.ACTIVE, limit = 10)
        assertEquals(1, activeMemories.size)
        assertEquals("For Stuart, diarization is the main priority.", activeMemories.single().text)

        val reviewMemories = repository.listMemoriesForReview(MemoryReviewFilter.REVIEW_NEEDED, limit = 10)
        assertEquals(1, reviewMemories.size)
        assertEquals(MemoryStatus.CONFLICTED, reviewMemories.single().status)
        assertEquals("For Stuart, visual design is the main priority.", reviewMemories.single().text)
    }

    @Test
    fun repeatedQuarantinedCandidateRefreshesReviewMemoryInsteadOfDuplicating() = runTest {
        writeDurableMemory(
            text = "User's address is 123 Example Street.",
            kind = MemoryKind.IDENTITY,
            domain = MemoryClaimDomain.IDENTITY,
            subject = "self",
            predicate = "address",
            value = "123 Example Street",
            keywords = listOf("identity", "address"),
            claimKey = "user.identity.self.address",
            requestedOutcome = MemoryWriteOutcome.REQUIRES_CONFIRMATION,
        )

        val secondReceipt = writeDurableMemory(
            text = "User's address is 123 Example Street.",
            kind = MemoryKind.IDENTITY,
            domain = MemoryClaimDomain.IDENTITY,
            subject = "self",
            predicate = "address",
            value = "123 Example Street",
            keywords = listOf("identity", "address"),
            claimKey = "user.identity.self.address",
            requestedOutcome = MemoryWriteOutcome.REQUIRES_CONFIRMATION,
        )

        assertEquals(MemoryWriteOutcome.MEMORY_UPDATED, secondReceipt.outcome)
        val reviewMemories = repository.listMemoriesForReview(MemoryReviewFilter.REVIEW_NEEDED, limit = 10)
        assertEquals(1, reviewMemories.size)
        assertEquals(MemoryStatus.QUARANTINED, reviewMemories.single().status)
    }

    @Test
    fun activatingReviewMemorySupersedesActiveSameClaim() = runTest {
        writeDurableMemory(
            text = "For Stuart, diarization is the main priority.",
            kind = MemoryKind.PROJECT,
            domain = MemoryClaimDomain.PROJECT,
            scope = MemoryScope.PROJECT,
            subject = "Stuart",
            predicate = "priority",
            value = "diarization is the main priority",
            keywords = listOf("stuart", "diarization", "priority"),
            claimKey = "project.project.stuart.priority",
        )
        writeDurableMemory(
            text = "For Stuart, visual design is the main priority.",
            kind = MemoryKind.PROJECT,
            domain = MemoryClaimDomain.PROJECT,
            scope = MemoryScope.PROJECT,
            subject = "Stuart",
            predicate = "priority",
            value = "visual design is the main priority",
            keywords = listOf("stuart", "visual", "design", "priority"),
            claimKey = "project.project.stuart.priority",
            requestedOutcome = MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN,
            writeIntent = MemoryWriteIntent.IMPLICIT_CANDIDATE,
        )
        val reviewMemory = repository.listMemoriesForReview(MemoryReviewFilter.REVIEW_NEEDED, limit = 10).single()

        val activationReceipt = repository.activateMemory(
            memoryObjectId = reviewMemory.memoryObjectId,
            reason = "Approved during test.",
        )

        assertEquals(MemoryWriteOutcome.MEMORY_ACTIVATED, activationReceipt.outcome)
        val activeMemories = repository.listMemoriesForReview(MemoryReviewFilter.ACTIVE, limit = 10)
        assertEquals(1, activeMemories.size)
        assertEquals("For Stuart, visual design is the main priority.", activeMemories.single().text)

        val inactiveMemories = repository.listMemoriesForReview(MemoryReviewFilter.INACTIVE, limit = 10)
        assertEquals(1, inactiveMemories.size)
        assertEquals(MemoryStatus.SUPERSEDED, inactiveMemories.single().status)
        assertEquals("For Stuart, diarization is the main priority.", inactiveMemories.single().text)
    }

    @Test
    fun activatingActiveMemoryIsRejected() = runTest {
        writeDurableMemory(
            text = "User's favorite food is pizza.",
            kind = MemoryKind.PREFERENCE,
            keywords = listOf("favorite", "food", "pizza"),
            claimKey = "user.preference.food.favorite",
        )
        val activeMemory = repository.listMemoriesForReview(MemoryReviewFilter.ACTIVE, limit = 10).single()

        val activationReceipt = repository.activateMemory(
            memoryObjectId = activeMemory.memoryObjectId,
            reason = "Approved during test.",
        )

        assertEquals(MemoryWriteOutcome.REJECTED, activationReceipt.outcome)
        assertTrue(activationReceipt.reason.contains("not awaiting review"))
    }

    @Test
    fun rejectedHighSensitivityCandidateReceiptRedactsSourceQuote() = runTest {
        val traceEvent = TraceEventRecord(
            id = TraceEventId("trace-sensitive"),
            sourceMessageId = ChatMessageId("message-sensitive"),
            role = ConversationRole.USER,
            text = "My address is 123 Example Street.",
            observedAt = clock.now(),
        )
        repository.appendTraceEvent(traceEvent)

        val receipt = repository.persistMemoryDecision(
            traceEvent = traceEvent,
            decision = MemoryWriteDecision(
                outcome = MemoryWriteOutcome.REJECTED,
                candidate = MemoryClaimCandidate(
                    kind = MemoryKind.IDENTITY,
                    text = "User's address is 123 Example Street.",
                    confidence = 0.20,
                    sourceTraceEventIds = listOf(traceEvent.id),
                    surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
                    reasonCodes = listOf("test-rejected"),
                    domain = MemoryClaimDomain.IDENTITY,
                    subject = "self",
                    predicate = "address",
                    value = "123 Example Street",
                    sourceQuote = "My address is 123 Example Street.",
                    sensitivity = MemorySensitivity.HIGH,
                    claimKey = "user.identity.self.address",
                ),
                reason = "Rejected by test.",
                storageHealthSnapshot = null,
            ),
        )

        assertEquals(MemoryWriteOutcome.REJECTED, receipt.outcome)
        assertTrue(receipt.reason.contains("Candidate: kind=IDENTITY"))
        assertTrue(receipt.reason.contains("source=redacted-sensitive"))
        assertTrue(!receipt.reason.contains("123 Example Street"))
    }

    @Test
    fun tombstoneMatchingMemoriesRemovesMemoryFromPromptRetrieval() = runTest {
        writeDurableMemory(
            text = "User's favorite food is sushi.",
            kind = MemoryKind.PREFERENCE,
            keywords = listOf("favorite", "food", "sushi"),
            claimKey = "user.preference.food.favorite",
        )
        val traceEvent = TraceEventRecord(
            id = TraceEventId("trace-delete"),
            sourceMessageId = ChatMessageId("message-delete"),
            role = ConversationRole.USER,
            text = "Forget my favorite food.",
            observedAt = clock.now(),
        )
        repository.appendTraceEvent(traceEvent)

        val receipts = repository.tombstoneMemoriesMatching(
            traceEvent = traceEvent,
            query = "favorite food",
            reason = "User requested memory deletion.",
        )

        assertEquals(1, receipts.size)
        assertEquals(MemoryWriteOutcome.MEMORY_TOMBSTONED, receipts.single().outcome)
        assertTrue(repository.retrieveMemories("What is my favorite food?", limit = 5).refs.isEmpty())
        val inactiveMemories = repository.listMemoriesForReview(MemoryReviewFilter.INACTIVE, limit = 10)
        assertEquals(MemoryStatus.TOMBSTONED, inactiveMemories.single().status)
    }

    private suspend fun writeDurableMemory(
        text: String,
        kind: MemoryKind = MemoryKind.PREFERENCE,
        domain: MemoryClaimDomain = MemoryClaimDomain.fromKind(kind),
        scope: MemoryScope = MemoryScope.GLOBAL,
        subject: String? = null,
        predicate: String? = null,
        value: String? = null,
        keywords: List<String> = emptyList(),
        claimKey: String? = null,
        requestedOutcome: MemoryWriteOutcome = MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN,
        writeIntent: MemoryWriteIntent = MemoryWriteIntent.EXPLICIT_SAVE,
    ): MemoryWriteReceipt {
        val traceEvent = TraceEventRecord(
            id = TraceEventId("trace-${clock.tickCount}"),
            sourceMessageId = ChatMessageId("message-${clock.tickCount}"),
            role = ConversationRole.USER,
            text = text,
            observedAt = clock.now(),
        )
        repository.appendTraceEvent(traceEvent)
        return repository.persistMemoryDecision(
            traceEvent = traceEvent,
            decision = MemoryWriteDecision(
                outcome = requestedOutcome,
                candidate = MemoryClaimCandidate(
                    kind = kind,
                    text = text,
                    confidence = 0.86,
                    sourceTraceEventIds = listOf(traceEvent.id),
                    surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
                    reasonCodes = listOf("test-memory"),
                    scope = scope,
                    domain = domain,
                    subject = subject,
                    predicate = predicate,
                    value = value,
                    sourceQuote = traceEvent.text,
                    writeIntent = writeIntent,
                    keywords = keywords,
                    claimKey = claimKey,
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
