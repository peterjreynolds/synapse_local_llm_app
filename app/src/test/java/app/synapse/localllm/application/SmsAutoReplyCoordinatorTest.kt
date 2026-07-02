package app.synapse.localllm.application

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.chat.RoomConversationRepository
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.data.sms.RoomSmsAutoReplyRepository
import app.synapse.localllm.data.storage.RoomStorageHealthSnapshotRepository
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.diagnostics.AssistantGenerationFinishedCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationStartedCommand
import app.synapse.localllm.domain.diagnostics.GenerationDiagnosticsRepository
import app.synapse.localllm.domain.ids.AssistantGenerationTraceId
import app.synapse.localllm.domain.ids.MemoryObjectId
import app.synapse.localllm.domain.ids.ReceiptId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.ContextualMemoryCandidateResolver
import app.synapse.localllm.domain.memory.MemoryAdmissionGate
import app.synapse.localllm.domain.memory.MemoryCandidateNormalizer
import app.synapse.localllm.domain.memory.MemoryCandidateProposer
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryCommand
import app.synapse.localllm.domain.memory.MemoryCommandInterpreter
import app.synapse.localllm.domain.memory.MemoryProjector
import app.synapse.localllm.domain.memory.MemoryRepository
import app.synapse.localllm.domain.memory.MemoryReviewFilter
import app.synapse.localllm.domain.memory.MemoryWriteDecision
import app.synapse.localllm.domain.memory.MemoryWriteReceipt
import app.synapse.localllm.domain.memory.PromptContextAssembler
import app.synapse.localllm.domain.memory.RetrievalBundle
import app.synapse.localllm.domain.memory.RetrievedMemoryRef
import app.synapse.localllm.domain.memory.TraceEventRecord
import app.synapse.localllm.domain.runtime.ChatCompletionRequest
import app.synapse.localllm.domain.runtime.ChatStreamEvent
import app.synapse.localllm.domain.runtime.LocalInferenceRuntime
import app.synapse.localllm.domain.runtime.ModelChatMessage
import app.synapse.localllm.domain.runtime.RuntimeStartReceipt
import app.synapse.localllm.domain.runtime.RuntimeStartStatus
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.runtime.StartLlamaServerCommand
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.sms.InboundSmsAutoReplyCommand
import app.synapse.localllm.domain.sms.MarkSmsAutoReplyQueuedCommand
import app.synapse.localllm.domain.sms.QueueSmsReplyCommand
import app.synapse.localllm.domain.sms.QueueSmsReplyOutcome
import app.synapse.localllm.domain.sms.RecordSmsAutoReplyAcceptedCommand
import app.synapse.localllm.domain.sms.SmsAutoReplyState
import app.synapse.localllm.domain.sms.SmsInboundMessageKey
import app.synapse.localllm.domain.sms.SmsOutboundGateway
import app.synapse.localllm.domain.sms.SmsSenderAddress
import app.synapse.localllm.domain.storage.StorageHealthGovernor
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.storage.StorageThresholds
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsAutoReplyCoordinatorTest {
    private lateinit var database: SynapseDatabase
    private lateinit var conversationRepository: RoomConversationRepository
    private lateinit var smsAutoReplyRepository: RoomSmsAutoReplyRepository
    private lateinit var clock: IncrementingSynapseClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = IncrementingSynapseClock()
        val idFactory = SynapseIdFactory()
        conversationRepository = RoomConversationRepository(
            database = database,
            chatDao = database.chatDao(),
            idFactory = idFactory,
            clock = clock,
        )
        smsAutoReplyRepository = RoomSmsAutoReplyRepository(
            smsAutoReplyDao = database.smsAutoReplyDao(),
            idFactory = idFactory,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun processInboundSmsRetriesFailedDuplicateReceiptAndQueuesReply() = runTest {
        val inboundSms = inboundCommand()
        val acceptedReceipt = smsAutoReplyRepository.recordAutoReplyAccepted(acceptedCommand())!!
        smsAutoReplyRepository.markAutoReplyFailed(
            receiptId = acceptedReceipt.id,
            state = SmsAutoReplyState.GENERATION_FAILED,
            reason = "Previous generation was interrupted.",
        )
        val outboundGateway = RecordingSmsOutboundGateway(clock)
        val coordinator = createCoordinator(
            outboundGateway = outboundGateway,
            runtime = FixedReplyRuntime(replyText = "I will call you back soon."),
        )

        val receipt = coordinator.processInboundSms(
            command = inboundSms,
            settings = SynapseSettings(smsAutoReplyEnabled = true),
        )
        val persistedReceipt = smsAutoReplyRepository.findReceiptByInboundMessageKey(inboundSms.inboundMessageKey)

        assertEquals(acceptedReceipt.id, receipt.id)
        assertEquals(SmsAutoReplyState.SMS_QUEUED, receipt.state)
        assertEquals(receipt, persistedReceipt)
        assertEquals(1, outboundGateway.queuedCommands.size)
        assertEquals("I will call you back soon.", outboundGateway.queuedCommands.single().replyBody)
        assertNotNull(persistedReceipt?.assistantMessageId)
        val assistantReply = conversationRepository.findMessage(persistedReceipt!!.assistantMessageId!!)
        assertEquals("I will call you back soon.", assistantReply?.body)
    }

    @Test
    fun processInboundSmsDoesNotQueueAlreadyQueuedDuplicateReceipt() = runTest {
        val inboundSms = inboundCommand()
        val acceptedReceipt = smsAutoReplyRepository.recordAutoReplyAccepted(acceptedCommand())!!
        val queuedReceipt = smsAutoReplyRepository.markAutoReplyQueued(
            MarkSmsAutoReplyQueuedCommand(
                receiptId = acceptedReceipt.id,
                replyBody = "Already sent.",
                smsPartCount = 1,
                queuedAt = clock.now(),
            ),
        )
        val outboundGateway = RecordingSmsOutboundGateway(clock)
        val coordinator = createCoordinator(
            outboundGateway = outboundGateway,
            runtime = FixedReplyRuntime(replyText = "This should not send."),
        )

        val receipt = coordinator.processInboundSms(
            command = inboundSms,
            settings = SynapseSettings(smsAutoReplyEnabled = true),
        )

        assertEquals(queuedReceipt, receipt)
        assertTrue(outboundGateway.queuedCommands.isEmpty())
    }

    @Test
    fun processInboundSmsKeepsSeparateThreadsPerSenderAddress() = runTest {
        val outboundGateway = RecordingSmsOutboundGateway(clock)
        val coordinator = createCoordinator(
            outboundGateway = outboundGateway,
            runtime = FixedReplyRuntime(replyText = "Got it."),
        )
        val firstSenderMessage = inboundCommand(
            inboundMessageKey = SmsInboundMessageKey("1".repeat(64)),
            senderAddress = SmsSenderAddress("+15551230001"),
            messageBody = "First sender",
        )
        val secondSenderMessage = inboundCommand(
            inboundMessageKey = SmsInboundMessageKey("2".repeat(64)),
            senderAddress = SmsSenderAddress("+15551230002"),
            messageBody = "Second sender",
        )
        val firstSenderFollowUp = inboundCommand(
            inboundMessageKey = SmsInboundMessageKey("3".repeat(64)),
            senderAddress = SmsSenderAddress("+15551230001"),
            messageBody = "First sender follow-up",
        )

        val firstReceipt = coordinator.processInboundSms(
            command = firstSenderMessage,
            settings = SynapseSettings(smsAutoReplyEnabled = true),
        )
        val secondReceipt = coordinator.processInboundSms(
            command = secondSenderMessage,
            settings = SynapseSettings(smsAutoReplyEnabled = true),
        )
        val firstFollowUpReceipt = coordinator.processInboundSms(
            command = firstSenderFollowUp,
            settings = SynapseSettings(smsAutoReplyEnabled = true),
        )

        assertNotNull(firstReceipt.threadId)
        assertNotNull(secondReceipt.threadId)
        assertNotEquals(firstReceipt.threadId, secondReceipt.threadId)
        assertEquals(firstReceipt.threadId, firstFollowUpReceipt.threadId)
        assertEquals(3, outboundGateway.queuedCommands.size)
    }

    private fun createCoordinator(
        outboundGateway: RecordingSmsOutboundGateway,
        runtime: LocalInferenceRuntime,
    ): SmsAutoReplyCoordinator =
        SmsAutoReplyCoordinator(
            conversationRepository = conversationRepository,
            smsAutoReplyRepository = smsAutoReplyRepository,
            smsOutboundGateway = outboundGateway,
            turnCoordinator = SynapseTurnCoordinator(
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
                localInferenceRuntime = runtime,
                generationDiagnosticsRepository = RecordingGenerationDiagnosticsRepository,
                idFactory = SynapseIdFactory(),
                clock = clock,
            ),
        )

    private fun inboundCommand(
        inboundMessageKey: SmsInboundMessageKey = INBOUND_MESSAGE_KEY,
        senderAddress: SmsSenderAddress = SENDER_ADDRESS,
        messageBody: String = INBOUND_BODY,
    ): InboundSmsAutoReplyCommand =
        InboundSmsAutoReplyCommand(
            inboundMessageKey = inboundMessageKey,
            senderAddress = senderAddress,
            messageBody = messageBody,
            receivedAt = RECEIVED_AT,
        )

    private fun acceptedCommand(): RecordSmsAutoReplyAcceptedCommand =
        RecordSmsAutoReplyAcceptedCommand(
            inboundMessageKey = INBOUND_MESSAGE_KEY,
            senderAddress = SENDER_ADDRESS,
            receivedAt = RECEIVED_AT,
            inboundBody = INBOUND_BODY,
        )

    private class FixedReplyRuntime(
        private val replyText: String,
    ) : LocalInferenceRuntime {
        override suspend fun checkRuntimeStatus(settings: SynapseSettings): RuntimeStatus =
            RuntimeStatus.Unknown

        override suspend fun startRuntime(
            settings: SynapseSettings,
            command: StartLlamaServerCommand,
        ): RuntimeStartReceipt =
            RuntimeStartReceipt(
                id = ReceiptId("runtime-start-1"),
                status = RuntimeStartStatus.EMBEDDED_MODEL_READY,
                requestedAt = Instant.parse("2026-07-02T12:00:00Z"),
                message = "Ready",
            )

        override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatStreamEvent> =
            flow {
                emit(ChatStreamEvent.Token(replyText))
                emit(ChatStreamEvent.Completed(Instant.parse("2026-07-02T12:00:01Z")))
            }

        override fun cancelActiveGeneration() = Unit
    }

    private class RecordingSmsOutboundGateway(
        private val clock: SynapseClock,
    ) : SmsOutboundGateway {
        val queuedCommands = mutableListOf<QueueSmsReplyCommand>()

        override suspend fun queueSmsReply(command: QueueSmsReplyCommand): QueueSmsReplyOutcome {
            queuedCommands += command
            return QueueSmsReplyOutcome.Queued(
                queuedAt = clock.now(),
                smsPartCount = 1,
            )
        }
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

    private class IncrementingSynapseClock : SynapseClock {
        private var tickMillis = 0L

        override fun now(): Instant {
            val instant = Instant.parse("2026-07-02T12:00:00Z").plusMillis(tickMillis)
            tickMillis += 10
            return instant
        }
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

    private companion object {
        val INBOUND_MESSAGE_KEY = SmsInboundMessageKey("a".repeat(64))
        val SENDER_ADDRESS = SmsSenderAddress("+15551234567")
        val RECEIVED_AT: Instant = Instant.parse("2026-07-02T12:00:00Z")
        const val INBOUND_BODY = "Are you free later?"
    }
}

private fun unusedDependency(): Nothing =
    error("Memory and storage dependencies are not used when SMS auto-reply disables memory writes.")
