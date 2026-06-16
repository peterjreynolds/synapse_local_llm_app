package app.synapse.localllm.domain.diagnostics

import app.synapse.localllm.domain.ids.AssistantGenerationTraceId
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.settings.InferenceRuntimeBackend
import java.time.Instant

data class AssistantGenerationStartedCommand(
    val assistantMessageId: ChatMessageId,
    val backend: InferenceRuntimeBackend,
    val modelName: String,
    val promptMessageCount: Int,
    val promptCharacterCount: Int,
    val retrievedMemoryCount: Int,
    val maxTokens: Int,
    val temperature: Double,
    val startedAt: Instant,
)

data class AssistantGenerationFinishedCommand(
    val traceId: AssistantGenerationTraceId,
    val completedAt: Instant,
    val rawTokenEvents: Int,
    val rawCharacterCount: Int,
    val visibleCharacterCount: Int,
    val filteredCharacterCount: Int,
    val firstRawTokenAt: Instant?,
    val firstVisibleTokenAt: Instant?,
    val stopReason: AssistantGenerationStopReason,
    val failureReason: String?,
)

enum class AssistantGenerationStopReason {
    COMPLETED,
    FAILED,
    CANCELLED,
    FILTER_STOPPED,
    EMPTY_VISIBLE_OUTPUT,
}

interface GenerationDiagnosticsRepository {
    suspend fun recordAssistantGenerationStarted(
        command: AssistantGenerationStartedCommand,
    ): AssistantGenerationTraceId

    suspend fun recordAssistantGenerationFinished(command: AssistantGenerationFinishedCommand)
}
