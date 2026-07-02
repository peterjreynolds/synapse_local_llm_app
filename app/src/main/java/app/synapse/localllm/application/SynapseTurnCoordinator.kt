package app.synapse.localllm.application

import app.synapse.localllm.data.storage.RoomStorageHealthSnapshotRepository
import app.synapse.localllm.domain.chat.AttachmentKind
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ConversationRepository
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.ConversationTurnReceipt
import app.synapse.localllm.domain.chat.PendingAttachment
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationFinishedCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationStartedCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationStopReason
import app.synapse.localllm.domain.diagnostics.GenerationDiagnosticsRepository
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.memory.ContextualMemoryCandidateResolver
import app.synapse.localllm.domain.memory.MemoryAdmissionGate
import app.synapse.localllm.domain.memory.MemoryCandidateNormalizer
import app.synapse.localllm.domain.memory.MemoryCandidateProposer
import app.synapse.localllm.domain.memory.MemoryCommand
import app.synapse.localllm.domain.memory.MemoryCommandInterpreter
import app.synapse.localllm.domain.memory.MemoryProjector
import app.synapse.localllm.domain.memory.MemoryRepository
import app.synapse.localllm.domain.memory.MemoryWriteDecision
import app.synapse.localllm.domain.memory.MemoryWriteOutcome
import app.synapse.localllm.domain.memory.PromptContextAssembler
import app.synapse.localllm.domain.memory.RetrievalBundle
import app.synapse.localllm.domain.memory.TraceEventRecord
import app.synapse.localllm.domain.runtime.ChatCompletionRequest
import app.synapse.localllm.domain.runtime.ChatStreamEvent
import app.synapse.localllm.domain.runtime.LocalInferenceRuntime
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.storage.StorageHealthGovernor
import app.synapse.localllm.domain.storage.StorageThresholds
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

class SynapseTurnCoordinator(
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryCommandInterpreter: MemoryCommandInterpreter,
    private val memoryProjector: MemoryProjector,
    private val memoryCandidateNormalizer: MemoryCandidateNormalizer,
    private val memoryCandidateProposer: MemoryCandidateProposer,
    private val contextualMemoryCandidateResolver: ContextualMemoryCandidateResolver,
    private val memoryAdmissionGate: MemoryAdmissionGate,
    private val storageHealthGovernor: StorageHealthGovernor,
    private val storageHealthSnapshotRepository: RoomStorageHealthSnapshotRepository,
    private val promptContextAssembler: PromptContextAssembler,
    private val localInferenceRuntime: LocalInferenceRuntime,
    private val generationDiagnosticsRepository: GenerationDiagnosticsRepository,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
) {
    suspend fun sendUserTurn(
        command: SubmitUserMessageCommand,
        settings: SynapseSettings,
        onTurnStarted: suspend (ConversationTurnReceipt) -> Unit = {},
    ): SynapseTurnOutcome {
        val priorMessages = conversationRepository.listRecentMessages(
            threadId = command.threadId,
            limit = RECENT_THREAD_MESSAGE_LIMIT,
        )
        val turnReceipt = conversationRepository.submitUserMessage(command)
        onTurnStarted(turnReceipt)
        val promptText = buildPromptText(command.body, command.attachments)
        val memoryPreparation = prepareMemoryForTurn(
            userMessageId = turnReceipt.userMessageId,
            userText = command.body,
            promptText = promptText,
            priorMessages = priorMessages,
            settings = settings,
        )
        val promptMessages = promptContextAssembler.assemblePromptMessages(
            userMessage = promptText,
            priorMessages = priorMessages,
            retrievalBundle = memoryPreparation.retrievalBundle,
            memoryWriteStatusBlock = memoryPreparation.writeStatusPromptBlock,
            systemPrompt = settings.systemPrompt,
        )
        val completionRequest = ChatCompletionRequest(
            backend = settings.runtimeBackend,
            baseUrl = settings.baseUrl,
            model = settings.modelName,
            embeddedModelPath = settings.embeddedModelPath,
            modelPromptProfile = settings.modelPromptProfile,
            messages = promptMessages,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
        )
        val generationTraceId = generationDiagnosticsRepository.recordAssistantGenerationStarted(
            AssistantGenerationStartedCommand(
                assistantMessageId = turnReceipt.assistantMessageId,
                backend = settings.runtimeBackend,
                modelName = settings.modelName,
                promptMessageCount = promptMessages.size,
                promptCharacterCount = promptMessages.sumOf { message -> message.content.length },
                retrievedMemoryCount = memoryPreparation.retrievalBundle.refs.size,
                maxTokens = settings.maxTokens,
                temperature = settings.temperature,
                startedAt = clock.now(),
            ),
        )

        var rawTokenEvents = 0
        var rawCharacterCount = 0
        var firstRawTokenAt: Instant? = null
        var firstVisibleTokenAt: Instant? = null
        var stopReason: AssistantGenerationStopReason? = null
        var finalFailureReason: String? = null
        var generationFinishedRecorded = false
        val visibleTextFilter = AssistantVisibleTextFilter()

        suspend fun recordGenerationFinishedOnce() {
            if (generationFinishedRecorded) return
            generationFinishedRecorded = true
            generationDiagnosticsRepository.recordAssistantGenerationFinished(
                AssistantGenerationFinishedCommand(
                    traceId = generationTraceId,
                    completedAt = clock.now(),
                    rawTokenEvents = rawTokenEvents,
                    rawCharacterCount = rawCharacterCount,
                    visibleCharacterCount = visibleTextFilter.visibleCharacterCount,
                    filteredCharacterCount = visibleTextFilter.filteredCharacterCount,
                    firstRawTokenAt = firstRawTokenAt,
                    firstVisibleTokenAt = firstVisibleTokenAt,
                    stopReason = stopReason ?: if (finalFailureReason == null) {
                        AssistantGenerationStopReason.COMPLETED
                    } else {
                        AssistantGenerationStopReason.FAILED
                    },
                    failureReason = finalFailureReason,
                ),
            )
        }

        val failureReason = try {
            var streamFailureReason: String? = null
            localInferenceRuntime.streamChatCompletion(completionRequest).collect { streamEvent ->
                when (streamEvent) {
                    is ChatStreamEvent.Token -> {
                        rawTokenEvents += 1
                        rawCharacterCount += streamEvent.text.length
                        if (firstRawTokenAt == null) {
                            firstRawTokenAt = clock.now()
                        }
                        val filteredToken = visibleTextFilter.appendToken(streamEvent.text)
                        if (filteredToken.visibleDelta.isNotEmpty()) {
                            if (firstVisibleTokenAt == null) {
                                firstVisibleTokenAt = clock.now()
                            }
                            conversationRepository.appendAssistantToken(
                                messageId = turnReceipt.assistantMessageId,
                                token = filteredToken.visibleDelta,
                            )
                        }
                        if (filteredToken.shouldStopGeneration) {
                            stopReason = AssistantGenerationStopReason.FILTER_STOPPED
                            localInferenceRuntime.cancelActiveGeneration()
                            throw AssistantOutputCompletedEarly()
                        }
                    }

                    is ChatStreamEvent.Completed -> {
                        if (visibleTextFilter.visibleCharacterCount == 0) {
                            streamFailureReason = EMPTY_VISIBLE_OUTPUT_REASON
                            finalFailureReason = EMPTY_VISIBLE_OUTPUT_REASON
                            stopReason = AssistantGenerationStopReason.EMPTY_VISIBLE_OUTPUT
                            conversationRepository.failAssistantMessage(
                                messageId = turnReceipt.assistantMessageId,
                                reason = EMPTY_VISIBLE_OUTPUT_REASON,
                            )
                        } else {
                            stopReason = AssistantGenerationStopReason.COMPLETED
                            conversationRepository.completeAssistantMessage(turnReceipt.assistantMessageId)
                        }
                    }

                    is ChatStreamEvent.Failed -> {
                        streamFailureReason = streamEvent.reason
                        finalFailureReason = streamEvent.reason
                        stopReason = AssistantGenerationStopReason.FAILED
                        conversationRepository.failAssistantMessage(
                            messageId = turnReceipt.assistantMessageId,
                            reason = streamEvent.reason,
                        )
                    }
                }
            }
            streamFailureReason
        } catch (_: AssistantOutputCompletedEarly) {
            if (visibleTextFilter.visibleCharacterCount == 0) {
                finalFailureReason = EMPTY_VISIBLE_OUTPUT_REASON
                stopReason = AssistantGenerationStopReason.EMPTY_VISIBLE_OUTPUT
                conversationRepository.failAssistantMessage(
                    messageId = turnReceipt.assistantMessageId,
                    reason = EMPTY_VISIBLE_OUTPUT_REASON,
                )
                EMPTY_VISIBLE_OUTPUT_REASON
            } else {
                conversationRepository.completeAssistantMessage(turnReceipt.assistantMessageId)
                null
            }
        } catch (exception: CancellationException) {
            finalFailureReason = STOPPED_BY_USER_REASON
            stopReason = AssistantGenerationStopReason.CANCELLED
            conversationRepository.failAssistantMessage(
                messageId = turnReceipt.assistantMessageId,
                reason = STOPPED_BY_USER_REASON,
            )
            recordGenerationFinishedOnce()
            throw exception
        }
        if (failureReason != null && finalFailureReason == null) {
            finalFailureReason = failureReason
        }
        recordGenerationFinishedOnce()

        return if (failureReason == null) {
            SynapseTurnOutcome.Completed(
                userMessageId = turnReceipt.userMessageId,
                assistantMessageId = turnReceipt.assistantMessageId,
            )
        } else {
            SynapseTurnOutcome.Failed(
                userMessageId = turnReceipt.userMessageId,
                assistantMessageId = turnReceipt.assistantMessageId,
                reason = failureReason,
            )
        }
    }

    private suspend fun prepareMemoryForTurn(
        userMessageId: ChatMessageId,
        userText: String,
        promptText: String,
        priorMessages: List<ChatMessageRecord>,
        settings: SynapseSettings,
    ): MemoryTurnPreparation {
        if (!settings.memoryWritesEnabled) {
            return MemoryTurnPreparation(
                retrievalBundle = RetrievalBundle(
                    retrievedAt = clock.now(),
                    refs = emptyList(),
                    promptBlock = "",
                ),
                writeStatusPromptBlock = "",
            )
        }

        val storageThresholds = StorageThresholds(
            memoryDatabaseWarningBytes = settings.memoryDatabaseWarningBytes,
            attachmentCacheWarningBytes = settings.attachmentCacheWarningBytes,
            minimumFreeStorageBytes = settings.minimumFreeStorageBytes,
        )
        val storageHealthSnapshot = storageHealthGovernor.canWriteMemory(storageThresholds)
        storageHealthSnapshotRepository.persistStorageHealthSnapshot(storageHealthSnapshot)

        val traceEvent = TraceEventRecord(
            id = idFactory.createTraceEventId(),
            sourceMessageId = userMessageId,
            role = ConversationRole.USER,
            text = userText,
            observedAt = clock.now(),
        )
        memoryRepository.appendTraceEvent(traceEvent)
        val hasExplicitMemoryIntent = hasExplicitMemoryWriteIntent(userText)

        when (val memoryCommand = memoryCommandInterpreter.interpretMemoryCommand(traceEvent)) {
            MemoryCommand.ContinueExtraction -> Unit
            is MemoryCommand.TombstoneMatchingMemories -> {
                val tombstoneReceipts = memoryRepository.tombstoneMemoriesMatching(
                    traceEvent = traceEvent,
                    query = memoryCommand.query,
                    reason = memoryCommand.reason,
                )
                val retrievalBundle = memoryRepository.retrieveMemories(query = promptText, limit = MEMORY_RETRIEVAL_LIMIT)
                return MemoryTurnPreparation(
                    retrievalBundle = retrievalBundle,
                    writeStatusPromptBlock = buildMemoryWriteStatusPromptBlock(
                        hasExplicitMemoryIntent = true,
                        summaries = tombstoneReceipts.map { receipt ->
                            MemoryWriteAttemptSummary(
                                outcome = receipt.outcome,
                                memoryText = null,
                                reason = receipt.reason,
                            )
                        },
                    ),
                )
            }
        }

        val deterministicCandidates = memoryProjector.extractMemoryCandidates(traceEvent)
        val proposedCandidates = if (deterministicCandidates.isEmpty()) {
            memoryCandidateProposer.proposeMemoryCandidates(traceEvent)
        } else {
            emptyList()
        }
        val contextualCandidates = if (deterministicCandidates.isEmpty() && proposedCandidates.isEmpty()) {
            contextualMemoryCandidateResolver.resolveContextualMemoryCandidates(
                traceEvent = traceEvent,
                priorMessages = priorMessages,
            )
        } else {
            emptyList()
        }
        val memoryCandidates = (deterministicCandidates + proposedCandidates + contextualCandidates).map { memoryCandidate ->
            memoryCandidateNormalizer.normalizeMemoryCandidate(
                candidate = memoryCandidate,
                traceEvent = traceEvent,
            )
        }
        val writeAttemptSummaries = mutableListOf<MemoryWriteAttemptSummary>()
        if (memoryCandidates.isEmpty()) {
            val receipt = memoryRepository.persistMemoryDecision(
                traceEvent = traceEvent,
                decision = MemoryWriteDecision(
                    outcome = MemoryWriteOutcome.TRACE_ONLY,
                    candidate = null,
                    reason = "No durable memory candidate was extracted.",
                    storageHealthSnapshot = storageHealthSnapshot,
                ),
            )
            writeAttemptSummaries += MemoryWriteAttemptSummary(
                outcome = receipt.outcome,
                memoryText = null,
                reason = receipt.reason,
            )
        } else {
            memoryCandidates.forEach { memoryCandidate ->
                val receipt = memoryRepository.persistMemoryDecision(
                    traceEvent = traceEvent,
                    decision = memoryAdmissionGate.decideMemoryWrite(
                        candidate = memoryCandidate,
                        storageHealthSnapshot = storageHealthSnapshot,
                    ),
                )
                writeAttemptSummaries += MemoryWriteAttemptSummary(
                    outcome = receipt.outcome,
                    memoryText = memoryCandidate.text,
                    reason = receipt.reason,
                )
            }
        }

        val retrievalBundle = memoryRepository.retrieveMemories(query = promptText, limit = MEMORY_RETRIEVAL_LIMIT)
        return MemoryTurnPreparation(
            retrievalBundle = retrievalBundle,
            writeStatusPromptBlock = buildMemoryWriteStatusPromptBlock(
                hasExplicitMemoryIntent = hasExplicitMemoryIntent,
                summaries = writeAttemptSummaries,
            ),
        )
    }

    private fun hasExplicitMemoryWriteIntent(userText: String): Boolean {
        val normalizedText = userText.trim().replace(Regex("\\s+"), " ")
        return explicitMemoryWritePatterns.any { pattern -> pattern.containsMatchIn(normalizedText) }
    }

    private fun buildMemoryWriteStatusPromptBlock(
        hasExplicitMemoryIntent: Boolean,
        summaries: List<MemoryWriteAttemptSummary>,
    ): String {
        if (!hasExplicitMemoryIntent) return ""
        val confirmingSummaries = summaries.filter { summary ->
            summary.outcome in promptConfirmableMemoryOutcomes
        }
        val reviewSummaries = summaries.filter { summary ->
            summary.outcome in promptReviewMemoryOutcomes
        }
        return buildString {
            append("Current turn memory write receipts:\n")
            append("- Source: app-local memory ledger, not model inference.\n")
            if (confirmingSummaries.isEmpty() && reviewSummaries.isEmpty()) {
                append("- No active memory was saved, updated, corrected, or deleted from this user turn.\n")
                append("- Do not claim memory was saved, corrected, updated, or deleted. ")
                append("Ask for the exact fact to remember if the request was ambiguous.")
                return@buildString
            }
            confirmingSummaries.forEach { summary ->
                append("- ${summary.outcome.name}: ")
                append(summary.memoryText?.take(MAX_MEMORY_STATUS_TEXT_CHARS) ?: summary.reason.take(MAX_MEMORY_STATUS_TEXT_CHARS))
                append("\n")
            }
            reviewSummaries.forEach { summary ->
                append("- ${summary.outcome.name}: memory is stored for review/confirmation, not active prompt memory. ")
                append(summary.memoryText?.take(MAX_MEMORY_STATUS_TEXT_CHARS) ?: summary.reason.take(MAX_MEMORY_STATUS_TEXT_CHARS))
                append("\n")
            }
            append("- Only confirm the receipt outcomes listed above. Do not invent additional saved facts.")
        }
    }

    private fun buildPromptText(body: String, attachments: List<PendingAttachment>): String {
        if (attachments.isEmpty()) return body
        val attachmentPrompt = attachments.joinToString(separator = "\n") { attachment ->
            when (attachment.kind) {
                AttachmentKind.TEXT ->
                    buildTextAttachmentPrompt(attachment)

                AttachmentKind.IMAGE ->
                    "- ${attachment.displayName}: image attached. " +
                        "Current model input is text-only unless multimodal is enabled."

                AttachmentKind.FILE ->
                    "- ${attachment.displayName}: file attached (${attachment.mimeType ?: "unknown type"})."
            }
        }
        return body.trim() + "\n\nAttached context:\n" + attachmentPrompt
    }

    private fun buildTextAttachmentPrompt(attachment: PendingAttachment): String {
        val extractedText = attachment.extractedText?.take(MAX_TEXT_ATTACHMENT_PROMPT_CHARS)
        return if (extractedText.isNullOrBlank()) {
            "- ${attachment.displayName}: text file attached, but no readable text was extracted."
        } else {
            "- ${attachment.displayName}:\n$extractedText"
        }
    }

    private companion object {
        const val RECENT_THREAD_MESSAGE_LIMIT = 8
        const val MEMORY_RETRIEVAL_LIMIT = 6
        const val MAX_TEXT_ATTACHMENT_PROMPT_CHARS = 12_000
        const val MAX_MEMORY_STATUS_TEXT_CHARS = 360
        const val STOPPED_BY_USER_REASON = "Stopped by user."
        const val EMPTY_VISIBLE_OUTPUT_REASON =
            "Model returned no visible answer text after hidden reasoning/output filtering."

        val explicitMemoryWritePatterns = listOf(
            Regex("\\b(?:remember|save|keep\\s+in\\s+memory|don't\\s+forget)\\b", RegexOption.IGNORE_CASE),
            Regex("\\badd\\b.{0,80}\\b(?:memory|remember|saved)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:forget|delete|remove)\\b.{0,80}\\bmemor(?:y|ies)\\b", RegexOption.IGNORE_CASE),
        )
        val promptConfirmableMemoryOutcomes = setOf(
            MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN,
            MemoryWriteOutcome.MEMORY_UPDATED,
            MemoryWriteOutcome.MEMORY_SUPERSEDED,
            MemoryWriteOutcome.MEMORY_TOMBSTONED,
        )
        val promptReviewMemoryOutcomes = setOf(
            MemoryWriteOutcome.QUARANTINED,
            MemoryWriteOutcome.REQUIRES_CONFIRMATION,
        )
    }
}

private class AssistantOutputCompletedEarly : RuntimeException()

private data class MemoryTurnPreparation(
    val retrievalBundle: RetrievalBundle,
    val writeStatusPromptBlock: String,
)

private data class MemoryWriteAttemptSummary(
    val outcome: MemoryWriteOutcome,
    val memoryText: String?,
    val reason: String,
)

sealed interface SynapseTurnOutcome {
    data class Completed(
        val userMessageId: ChatMessageId,
        val assistantMessageId: ChatMessageId,
    ) : SynapseTurnOutcome

    data class Failed(
        val userMessageId: ChatMessageId,
        val assistantMessageId: ChatMessageId,
        val reason: String,
    ) : SynapseTurnOutcome
}
