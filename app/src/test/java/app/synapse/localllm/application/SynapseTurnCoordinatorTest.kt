package app.synapse.localllm.application

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.data.storage.RoomStorageHealthSnapshotRepository
import app.synapse.localllm.domain.chat.AddHumanRoomMemberCommand
import app.synapse.localllm.domain.chat.AiResponsePolicy
import app.synapse.localllm.domain.chat.AiResponseStartReceipt
import app.synapse.localllm.domain.chat.BuiltInParticipantIds
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ChatRoomRecord
import app.synapse.localllm.domain.chat.ChatThreadMutationReceipt
import app.synapse.localllm.domain.chat.ConversationRepository
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.ConversationTurnReceipt
import app.synapse.localllm.domain.chat.CreateRoomCommand
import app.synapse.localllm.domain.chat.HumanMessageReceipt
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.chat.ParticipantKind
import app.synapse.localllm.domain.chat.ParticipantRecord
import app.synapse.localllm.domain.chat.RoomKind
import app.synapse.localllm.domain.chat.RoomMemberRecord
import app.synapse.localllm.domain.chat.RoomMemberRole
import app.synapse.localllm.domain.chat.RoomMembershipMutationReceipt
import app.synapse.localllm.domain.chat.SubmitHumanMessageCommand
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.chat.SyncMetadata
import app.synapse.localllm.domain.diagnostics.AssistantGenerationFinishedCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationStartedCommand
import app.synapse.localllm.domain.diagnostics.GenerationDiagnosticsRepository
import app.synapse.localllm.domain.ids.AssistantGenerationTraceId
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.MemoryObjectId
import app.synapse.localllm.domain.ids.ParticipantId
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
        val conversationRepository = RecordingConversationRepository(aiChatRoom())
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

    @Test
    fun sendUserTurnPersistsHumanMessageWithoutStartingRuntimeForHumanOnlyRoom() = runTest {
        val conversationRepository = RecordingConversationRepository(humanOnlyGroupRoom())
        val coordinator = createCoordinator(
            conversationRepository = conversationRepository,
            runtime = CallbackAssertingRuntime(
                clock = clock,
                assertTurnStarted = { error("Human-only room must not start local inference.") },
            ),
        )

        val outcome = coordinator.sendUserTurn(
            command = SubmitUserMessageCommand(
                threadId = ChatThreadId("thread-1"),
                body = "Human room message",
                attachments = emptyList(),
            ),
            settings = SynapseSettings(memoryWritesEnabled = false),
        )

        assertTrue(outcome is SynapseTurnOutcome.HumanMessageOnly)
        assertEquals(1, conversationRepository.humanMessageCount)
        assertEquals(0, conversationRepository.aiResponseCount)
    }

    @Test
    fun cancellationDurablyFailsTheStreamingAiMessage() = runTest {
        val conversationRepository = RecordingConversationRepository(aiChatRoom())
        val generationStarted = CompletableDeferred<Unit>()
        val coordinator = createCoordinator(
            conversationRepository = conversationRepository,
            runtime = AwaitCancellationRuntime(generationStarted),
        )

        val turnJob = launch {
            coordinator.sendUserTurn(
                command = SubmitUserMessageCommand(
                    threadId = ChatThreadId("thread-1"),
                    body = "Cancel this response",
                    attachments = emptyList(),
                ),
                settings = SynapseSettings(memoryWritesEnabled = false),
            )
        }
        generationStarted.await()
        turnJob.cancel()
        turnJob.join()

        assertEquals(MessageDeliveryState.FAILED, conversationRepository.assistantDeliveryState)
    }

    @Test
    fun turnStartCallbackFailureDurablyFailsTheExplicitAiMessage() = runTest {
        val conversationRepository = RecordingConversationRepository(aiChatRoom())
        val coordinator = createCoordinator(
            conversationRepository = conversationRepository,
            runtime = CallbackAssertingRuntime(
                clock = clock,
                assertTurnStarted = { error("Runtime must not start after callback failure.") },
            ),
        )

        val outcome = coordinator.sendUserTurn(
            command = SubmitUserMessageCommand(
                threadId = ChatThreadId("thread-1"),
                body = "Prepare this response",
                attachments = emptyList(),
            ),
            settings = SynapseSettings(memoryWritesEnabled = false),
            onTurnStarted = { error("Receipt linkage failed.") },
        )

        assertTrue(outcome is SynapseTurnOutcome.Failed)
        assertEquals("Receipt linkage failed.", (outcome as SynapseTurnOutcome.Failed).reason)
        assertEquals(MessageDeliveryState.FAILED, conversationRepository.assistantDeliveryState)
    }

    private fun createCoordinator(
        conversationRepository: ConversationRepository,
        runtime: LocalInferenceRuntime,
    ): SynapseTurnCoordinator =
        SynapseTurnCoordinator(
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
        )

    private fun aiChatRoom(): ChatRoomRecord = room(RoomKind.AI_CHAT, includeSynapse = true)

    private fun humanOnlyGroupRoom(): ChatRoomRecord = room(RoomKind.GROUP, includeSynapse = false)

    private fun room(
        kind: RoomKind,
        includeSynapse: Boolean,
    ): ChatRoomRecord =
        ChatRoomRecord(
            id = ChatThreadId("thread-1"),
            title = "Test room",
            kind = kind,
            isPinned = false,
            members = buildList {
                add(
                    roomMember(
                        participant = participant(
                            id = BuiltInParticipantIds.LOCAL_HUMAN,
                            kind = ParticipantKind.HUMAN,
                            displayName = "You",
                        ),
                        role = RoomMemberRole.OWNER,
                        aiResponsePolicy = AiResponsePolicy.NEVER,
                    ),
                )
                if (includeSynapse) {
                    add(
                        roomMember(
                            participant = participant(
                                id = BuiltInParticipantIds.SYNAPSE_LOCAL_AI,
                                kind = ParticipantKind.LOCAL_AI,
                                displayName = "Synapse",
                            ),
                            role = RoomMemberRole.MEMBER,
                            aiResponsePolicy = AiResponsePolicy.AUTOMATIC,
                        ),
                    )
                }
            },
            syncMetadata = SyncMetadata(),
            createdAt = TEST_INSTANT,
            updatedAt = TEST_INSTANT,
        )

    private fun roomMember(
        participant: ParticipantRecord,
        role: RoomMemberRole,
        aiResponsePolicy: AiResponsePolicy,
    ): RoomMemberRecord =
        RoomMemberRecord(
            roomId = ChatThreadId("thread-1"),
            participant = participant,
            role = role,
            canPost = true,
            joinedAt = TEST_INSTANT,
            leftAt = null,
            aiResponsePolicy = aiResponsePolicy,
            syncMetadata = SyncMetadata(),
        )

    private fun participant(
        id: ParticipantId,
        kind: ParticipantKind,
        displayName: String,
    ): ParticipantRecord =
        ParticipantRecord(
            id = id,
            kind = kind,
            displayName = displayName,
            avatarUri = null,
            avatarColorArgb = null,
            syncMetadata = SyncMetadata(),
            createdAt = TEST_INSTANT,
            updatedAt = TEST_INSTANT,
        )

    private class RecordingConversationRepository(
        private val room: ChatRoomRecord,
    ) : ConversationRepository {
        val humanMessageReceipt = HumanMessageReceipt(
            roomId = ChatThreadId("thread-1"),
            messageId = ChatMessageId("user-message-1"),
            authorParticipantId = BuiltInParticipantIds.LOCAL_HUMAN,
            submittedAt = Instant.parse("2026-07-02T12:00:00Z"),
        )
        val aiResponseReceipt = AiResponseStartReceipt(
            roomId = ChatThreadId("thread-1"),
            messageId = ChatMessageId("assistant-message-1"),
            authorParticipantId = BuiltInParticipantIds.SYNAPSE_LOCAL_AI,
            startedAt = Instant.parse("2026-07-02T12:00:00Z"),
        )
        val turnReceipt = ConversationTurnReceipt(
            userMessageId = humanMessageReceipt.messageId,
            assistantMessageId = aiResponseReceipt.messageId,
            submittedAt = humanMessageReceipt.submittedAt,
        )
        var humanMessageCount = 0
            private set
        var aiResponseCount = 0
            private set
        var assistantTokens = ""
            private set
        var assistantDeliveryState = MessageDeliveryState.STREAMING
            private set

        override suspend fun ensureDefaultRoom(): ChatRoomRecord = room

        override suspend fun findRoom(roomId: ChatThreadId): ChatRoomRecord? = room

        override suspend fun createRoom(command: CreateRoomCommand): ChatRoomRecord =
            error("Not used by this test.")

        override fun observeRooms(): Flow<List<ChatRoomRecord>> = emptyFlow()

        override fun observeRoomMembers(roomId: ChatThreadId): Flow<List<RoomMemberRecord>> = emptyFlow()

        override suspend fun addHumanRoomMember(
            command: AddHumanRoomMemberCommand,
        ): RoomMembershipMutationReceipt = error("Not used by this test.")

        override suspend fun removeRoomMember(
            roomId: ChatThreadId,
            participantId: ParticipantId,
        ): RoomMembershipMutationReceipt = error("Not used by this test.")

        override suspend fun setSynapseAiEnabled(
            roomId: ChatThreadId,
            enabled: Boolean,
        ): RoomMembershipMutationReceipt = error("Not used by this test.")

        override suspend fun setRoomAiAutoResponse(
            roomId: ChatThreadId,
            enabled: Boolean,
        ): RoomMembershipMutationReceipt = error("Not used by this test.")

        override fun observeMessages(threadId: ChatThreadId): Flow<List<ChatMessageRecord>> = emptyFlow()

        override suspend fun listRecentMessages(
            threadId: ChatThreadId,
            limit: Int,
        ): List<ChatMessageRecord> = emptyList()

        override suspend fun findMessage(messageId: ChatMessageId): ChatMessageRecord? = null

        override suspend fun setRoomPinned(
            roomId: ChatThreadId,
            pinned: Boolean,
        ): ChatThreadMutationReceipt = error("Not used by this test.")

        override suspend fun renameRoom(
            roomId: ChatThreadId,
            title: String,
        ): ChatThreadMutationReceipt = error("Not used by this test.")

        override suspend fun archiveRoom(roomId: ChatThreadId): ChatThreadMutationReceipt =
            error("Not used by this test.")

        override suspend fun deleteRoom(roomId: ChatThreadId): ChatThreadMutationReceipt =
            error("Not used by this test.")

        override suspend fun failStaleStreamingAssistantMessages(
            reason: String,
            activeSmsAutoReplyAfter: Instant,
        ): Int = error("Not used by this test.")

        override suspend fun submitHumanMessage(command: SubmitHumanMessageCommand): HumanMessageReceipt {
            humanMessageCount += 1
            return humanMessageReceipt
        }

        override suspend fun startAiResponse(
            roomId: ChatThreadId,
            inReplyToHumanMessageId: ChatMessageId?,
            authorParticipantId: ParticipantId,
        ): AiResponseStartReceipt {
            aiResponseCount += 1
            return aiResponseReceipt
        }

        override suspend fun appendAssistantToken(messageId: ChatMessageId, token: String) {
            assistantTokens += token
            assistantDeliveryState = MessageDeliveryState.STREAMING
        }

        override suspend fun completeAssistantMessage(messageId: ChatMessageId) {
            assistantDeliveryState = MessageDeliveryState.COMPLETE
        }

        override suspend fun failAssistantMessage(messageId: ChatMessageId, reason: String) {
            yield()
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

    private class AwaitCancellationRuntime(
        private val generationStarted: CompletableDeferred<Unit>,
    ) : LocalInferenceRuntime {
        override suspend fun checkRuntimeStatus(settings: SynapseSettings): RuntimeStatus = RuntimeStatus.Unknown

        override suspend fun startRuntime(
            settings: SynapseSettings,
            command: StartLlamaServerCommand,
        ): RuntimeStartReceipt = error("Not used by this test.")

        override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatStreamEvent> =
            flow {
                generationStarted.complete(Unit)
                awaitCancellation()
            }

        override fun cancelActiveGeneration() = Unit
    }

    private object DirectPromptContextAssembler : PromptContextAssembler {
        override suspend fun assemblePromptMessages(
            userMessage: String,
            currentAuthor: ParticipantRecord,
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

    private companion object {
        val TEST_INSTANT: Instant = Instant.parse("2026-07-02T12:00:00Z")
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
