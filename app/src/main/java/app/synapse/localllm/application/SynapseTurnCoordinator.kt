package app.synapse.localllm.application

import app.synapse.localllm.data.storage.RoomStorageHealthSnapshotRepository
import app.synapse.localllm.domain.chat.AttachmentKind
import app.synapse.localllm.domain.chat.ConversationRepository
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.PendingAttachment
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationFinishedCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationStartedCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationStopReason
import app.synapse.localllm.domain.diagnostics.GenerationDiagnosticsRepository
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.memory.MemoryAdmissionGate
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
    private val memoryProjector: MemoryProjector,
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
    ): SynapseTurnOutcome {
        val priorMessages = conversationRepository.listRecentMessages(
            threadId = command.threadId,
            limit = RECENT_THREAD_MESSAGE_LIMIT,
        )
        val turnReceipt = conversationRepository.submitUserMessage(command)
        val promptText = buildPromptText(command.body, command.attachments)
        val retrievalBundle = prepareMemoryForTurn(
            userMessageId = turnReceipt.userMessageId,
            userText = command.body,
            promptText = promptText,
            settings = settings,
        )
        val promptMessages = promptContextAssembler.assemblePromptMessages(
            userMessage = promptText,
            priorMessages = priorMessages,
            retrievalBundle = retrievalBundle,
            systemPrompt = settings.systemPrompt,
        )
        val completionRequest = ChatCompletionRequest(
            backend = settings.runtimeBackend,
            baseUrl = settings.baseUrl,
            model = settings.modelName,
            embeddedModelPath = settings.embeddedModelPath,
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
                retrievedMemoryCount = retrievalBundle.refs.size,
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
            SynapseTurnOutcome.Completed(turnReceipt.assistantMessageId)
        } else {
            SynapseTurnOutcome.Failed(
                assistantMessageId = turnReceipt.assistantMessageId,
                reason = failureReason,
            )
        }
    }

    private suspend fun prepareMemoryForTurn(
        userMessageId: ChatMessageId,
        userText: String,
        promptText: String,
        settings: SynapseSettings,
    ): RetrievalBundle {
        if (!settings.memoryWritesEnabled) {
            return RetrievalBundle(
                retrievedAt = clock.now(),
                refs = emptyList(),
                promptBlock = "",
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

        val memoryCandidates = memoryProjector.extractMemoryCandidates(traceEvent)
        if (memoryCandidates.isEmpty()) {
            memoryRepository.persistMemoryDecision(
                traceEvent = traceEvent,
                decision = MemoryWriteDecision(
                    outcome = MemoryWriteOutcome.TRACE_ONLY,
                    candidate = null,
                    reason = "No durable memory candidate was extracted.",
                    storageHealthSnapshot = storageHealthSnapshot,
                ),
            )
        } else {
            memoryCandidates.forEach { memoryCandidate ->
                memoryRepository.persistMemoryDecision(
                    traceEvent = traceEvent,
                    decision = memoryAdmissionGate.decideMemoryWrite(
                        candidate = memoryCandidate,
                        storageHealthSnapshot = storageHealthSnapshot,
                    ),
                )
            }
        }

        return memoryRepository.retrieveMemories(query = promptText, limit = MEMORY_RETRIEVAL_LIMIT)
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
        const val STOPPED_BY_USER_REASON = "Stopped by user."
        const val EMPTY_VISIBLE_OUTPUT_REASON =
            "Model returned no visible answer text after hidden reasoning/output filtering."
    }
}

private class AssistantOutputCompletedEarly : RuntimeException()

sealed interface SynapseTurnOutcome {
    data class Completed(
        val assistantMessageId: ChatMessageId,
    ) : SynapseTurnOutcome

    data class Failed(
        val assistantMessageId: ChatMessageId,
        val reason: String,
    ) : SynapseTurnOutcome
}
