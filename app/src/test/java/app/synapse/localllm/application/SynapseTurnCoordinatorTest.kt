package app.synapse.localllm.application

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.data.storage.RoomStorageHealthSnapshotRepository
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ChatThreadMutationReceipt
import app.synapse.localllm.domain.chat.ChatThreadRecord
import app.synapse.localllm.domain.chat.ConversationRepository
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.ConversationTurnReceipt
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationFinishedCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationStartedCommand
import app.synapse.localllm.domain.diagnostics.GenerationDiagnosticsRepository
import app.synapse.localllm.domain.ids.AssistantGenerationTraceId
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.MemoryObjectId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.ContextualMemoryCandidateResolver
import app.synapse.localllm.domain.memory.MemoryAdmissionGate
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryCandidateNormalizer
import app.synapse.localllm.domain.memory.MemoryCandidateProposer
import app.synapse.localllm.domain.memory.MemoryCommand
import app.synapse.localllm.domain.memory.MemoryCommandInterpreter
import app.synapse.localllm.domain.memory.MemoryReviewFilter
import app.synapse.localllm.domain.memory.MemoryProjector
import app.synapse.localllm.domain.memory.MemoryRepository
import app.synapse.localllm.domain.memory.MemoryWriteDecision
import app.synapse.localllm.domain.memory.MemoryWriteReceipt
import app.synapse.localllm.domain.memory.PromptContextAssembler
import app.synapse.localllm.domain.memory.RetrievedMemoryRef
import app.synapse.localllm.domain.memory.RetrievalBundle
import app.synapse.localllm.domain.memory.TraceEventRecord
import app.synapse.localllm.domain.runtime.ChatCompletionRequest
import app.synapse.localllm.domain.runtime.ChatStreamEvent
import app.synapse.localllm.domain.runtime.LocalInferenceRuntime
import app.synapse.localllm.domain.runtime.ModelChatMessage
import app.synapse.localllm.domain.runtime.RuntimeStartReceipt
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.runtime.StartLlamaServerCommand
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.storage.StorageHealthGovernor
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.storage.StorageThresholds
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
class SynapseTurnCoordinatorTest {
    private lateinit var database: SynapseDatabase
    private lateinit var clock: FixedSynapseClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FixedSynapseClock()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sendUserTurnReportsTurnReceiptBeforeRuntimeGenerationStarts() = runTest {
        val conversationRepository = RecordingConversationRepository()
        var turnStarted = false
        val coordinator = SynapseTurnCoordinator(
            conversationRepository = conversationRepository,
            memoryRepository = UnusedMemoryRepository,
            memoryCommandInterpreter = UnusedMemoryCommandInterpreter,
            memoryProjector = UnusedMemoryProjector,
            memoryCandidateNormalizer = UnusedMemoryCandidateNormalizer,
            memoryCandidateProposer = UnusedMemoryCandidateProposer,
            contextualMemoryCandidateResolver = UnusedContextualMemoryCandidateResolver,
            memoryAdmissionGate = UnusedMemoryAdmissionGate,
            storageHealthGovernor = UnusedStorageHealthGovernor,
            storageHealthSnapshotRepository = RoomStorageHealthSnapshotRepository(
                storageHealthDao = database.storageHealthDao(),
                idFactory = SynapseIdFactory(),
            ),
            promptContextAssembler = DirectPromptContextAssembler,
            localInferenceRuntime = CallbackAssertingRuntime(
                clock = clock,
                assertTurnStarted = { assertTrue(turnStarted) },
            ),
            generationDiagnosticsRepository = RecordingGenerationDiagnosticsRepository,
            idFactory = SynapseIdFactory(),
            clock = clock,
        )

        val outcome = coordinator.sendUserTurn(
            command = SubmitUserMessageCommand(
                threadId = ChatThreadId("thread-1"),
                body = "Reply to this",
                attachments = emptyList(),
            ),
            settings = SynapseSettings(memoryWritesEnabled = false),
            onTurnStarted = { receipt ->
                turnStarted = true
                assertEquals(conversationRepository.turnReceipt, receipt)
            },
        )

        assertTrue(outcome is SynapseTurnOutcome.Completed)
        assertTrue(conversationRepository.assistantTokens.isNotEmpty())
        assertEquals(MessageDeliveryState.COMPLETE, conversationRepository.assistantDeliveryState)
    }

    private class RecordingConversationRepository : ConversationRepository {
        val turnReceipt = ConversationTurnReceipt(
            userMessageId = ChatMessageId("user-message-1"),
            assistantMessageId = ChatMessageId("assistant-message-1"),
            submittedAt = Instant.parse("2026-07-02T12:00:00Z"),
        )
        var assistantTokens = ""
            private set
        var assistantDeliveryState = MessageDeliveryState.STREAMING
            private set

        override suspend fun ensureDefaultThread(): ChatThreadRecord =
            error("Not used by this test.")

        override suspend fun createThread(): ChatThreadRecord =
            error("Not used by this test.")

        override suspend fun createThread(title: String): ChatThreadRecord =
            error("Not used by this test.")

        override fun observeThreads(): Flow<List<ChatThreadRecord>> = emptyFlow()

        override fun observeMessages(threadId: ChatThreadId): Flow<List<ChatMessageRecord>> = emptyFlow()

        override suspend fun listRecentMessages(
            threadId: ChatThreadId,
            limit: Int,
        ): List<ChatMessageRecord> = emptyList()

        override suspend fun findMessage(messageId: ChatMessageId): ChatMessageRecord? = null

        override suspend fun setThreadPinned(
            threadId: ChatThreadId,
            pinned: Boolean,
        ): ChatThreadMutationReceipt = error("Not used by this test.")

        override suspend fun renameThread(
            threadId: ChatThreadId,
            title: String,
        ): ChatThreadMutationReceipt = error("Not used by this test.")

        override suspend fun archiveThread(threadId: ChatThreadId): ChatThreadMutationReceipt =
            error("Not used by this test.")

        override suspend fun deleteThread(threadId: ChatThreadId): ChatThreadMutationReceipt =
            error("Not used by this test.")

        override suspend fun failStaleStreamingAssistantMessages(
            reason: String,
            activeSmsAutoReplyAfter: Instant,
        ): Int = error("Not used by this test.")

        override suspend fun submitUserMessage(command: SubmitUserMessageCommand): ConversationTurnReceipt =
            turnReceipt

        override suspend fun appendAssistantToken(messageId: ChatMessageId, token: String) {
            assistantTokens += token
            assistantDeliveryState = MessageDeliveryState.STREAMING
        }

        override suspend fun completeAssistantMessage(messageId: ChatMessageId) {
            assistantDeliveryState = MessageDeliveryState.COMPLETE
        }

        override suspend fun failAssistantMessage(messageId: ChatMessageId, reason: String) {
            assistantDeliveryState = MessageDeliveryState.FAILED
        }
    }

    private class CallbackAssertingRuntime(
        private val clock: SynapseClock,
        private val assertTurnStarted: () -> Unit,
    ) : LocalInferenceRuntime {
        override suspend fun checkRuntimeStatus(settings: SynapseSettings): RuntimeStatus =
            RuntimeStatus.Unknown

        override suspend fun startRuntime(
            settings: SynapseSettings,
            command: StartLlamaServerCommand,
        ): RuntimeStartReceipt = error("Not used by this test.")

        override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatStreamEvent> =
            flow {
                assertTurnStarted()
                emit(ChatStreamEvent.Token("ok"))
                emit(ChatStreamEvent.Completed(clock.now()))
            }

        override fun cancelActiveGeneration() = Unit
    }

    private object DirectPromptContextAssembler : PromptContextAssembler {
        override suspend fun assemblePromptMessages(
            userMessage: String,
            priorMessages: List<ChatMessageRecord>,
            retrievalBundle: RetrievalBundle,
            memoryWriteStatusBlock: String,
            systemPrompt: String,
        ): List<ModelChatMessage> =
            listOf(ModelChatMessage(ConversationRole.USER, userMessage))
    }

    private object RecordingGenerationDiagnosticsRepository : GenerationDiagnosticsRepository {
        override suspend fun recordAssistantGenerationStarted(
            command: AssistantGenerationStartedCommand,
        ): AssistantGenerationTraceId = AssistantGenerationTraceId("generation-1")

        override suspend fun recordAssistantGenerationFinished(command: AssistantGenerationFinishedCommand) = Unit
    }

    private class FixedSynapseClock : SynapseClock {
        override fun now(): Instant = Instant.parse("2026-07-02T12:00:00Z")
    }

    private object UnusedMemoryRepository : MemoryRepository {
        override suspend fun appendTraceEvent(traceEvent: TraceEventRecord): TraceEventId =
            unusedDependency()

        override suspend fun persistMemoryDecision(
            traceEvent: TraceEventRecord,
            decision: MemoryWriteDecision,
        ): MemoryWriteReceipt = unusedDependency()

        override suspend fun tombstoneMemory(
            memoryObjectId: MemoryObjectId,
            reason: String,
        ): MemoryWriteReceipt = unusedDependency()

        override suspend fun activateMemory(
            memoryObjectId: MemoryObjectId,
            reason: String,
        ): MemoryWriteReceipt = unusedDependency()

        override suspend fun tombstoneMemoriesMatching(
            traceEvent: TraceEventRecord,
            query: String,
            reason: String,
        ): List<MemoryWriteReceipt> = unusedDependency()

        override suspend fun listPromptVisibleMemories(limit: Int): List<RetrievedMemoryRef> =
            unusedDependency()

        override suspend fun listMemoriesForReview(
            filter: MemoryReviewFilter,
            limit: Int,
        ): List<RetrievedMemoryRef> = unusedDependency()

        override suspend fun retrieveMemories(query: String, limit: Int): RetrievalBundle =
            unusedDependency()
    }

    private object UnusedMemoryCommandInterpreter : MemoryCommandInterpreter {
        override fun interpretMemoryCommand(traceEvent: TraceEventRecord): MemoryCommand =
            unusedDependency()
    }

    private object UnusedMemoryProjector : MemoryProjector {
        override fun extractMemoryCandidates(traceEvent: TraceEventRecord): List<MemoryClaimCandidate> =
            unusedDependency()
    }

    private object UnusedMemoryCandidateNormalizer : MemoryCandidateNormalizer {
        override fun normalizeMemoryCandidate(
            candidate: MemoryClaimCandidate,
            traceEvent: TraceEventRecord,
        ): MemoryClaimCandidate = unusedDependency()
    }

    private object UnusedMemoryCandidateProposer : MemoryCandidateProposer {
        override fun proposeMemoryCandidates(traceEvent: TraceEventRecord): List<MemoryClaimCandidate> =
            unusedDependency()
    }

    private object UnusedContextualMemoryCandidateResolver : ContextualMemoryCandidateResolver {
        override fun resolveContextualMemoryCandidates(
            traceEvent: TraceEventRecord,
            priorMessages: List<ChatMessageRecord>,
        ): List<MemoryClaimCandidate> = unusedDependency()
    }

    private object UnusedMemoryAdmissionGate : MemoryAdmissionGate {
        override fun decideMemoryWrite(
            candidate: MemoryClaimCandidate,
            storageHealthSnapshot: StorageHealthSnapshot,
        ): MemoryWriteDecision = unusedDependency()
    }

    private object UnusedStorageHealthGovernor : StorageHealthGovernor {
        override suspend fun inspectStorageHealth(thresholds: StorageThresholds): StorageHealthSnapshot =
            unusedDependency()

        override suspend fun canWriteMemory(thresholds: StorageThresholds): StorageHealthSnapshot =
            unusedDependency()
    }

}

private fun unusedDependency(): Nothing =
    error("Memory and storage dependencies are not used when memory writes are disabled.")
