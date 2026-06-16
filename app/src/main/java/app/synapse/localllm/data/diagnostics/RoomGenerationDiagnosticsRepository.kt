package app.synapse.localllm.data.diagnostics

import app.synapse.localllm.data.db.AssistantGenerationTraceEntity
import app.synapse.localllm.data.db.DiagnosticsDao
import app.synapse.localllm.domain.diagnostics.AssistantGenerationFinishedCommand
import app.synapse.localllm.domain.diagnostics.AssistantGenerationStartedCommand
import app.synapse.localllm.domain.diagnostics.GenerationDiagnosticsRepository
import app.synapse.localllm.domain.ids.AssistantGenerationTraceId
import app.synapse.localllm.domain.ids.SynapseIdFactory

class RoomGenerationDiagnosticsRepository(
    private val diagnosticsDao: DiagnosticsDao,
    private val idFactory: SynapseIdFactory,
) : GenerationDiagnosticsRepository {
    override suspend fun recordAssistantGenerationStarted(
        command: AssistantGenerationStartedCommand,
    ): AssistantGenerationTraceId {
        val traceId = idFactory.createAssistantGenerationTraceId()
        diagnosticsDao.upsertAssistantGenerationTrace(
            AssistantGenerationTraceEntity(
                id = traceId.raw,
                assistantMessageId = command.assistantMessageId.raw,
                backend = command.backend.name,
                modelName = command.modelName,
                promptMessageCount = command.promptMessageCount,
                promptCharacterCount = command.promptCharacterCount,
                retrievedMemoryCount = command.retrievedMemoryCount,
                maxTokens = command.maxTokens,
                temperature = command.temperature,
                startedAtEpochMillis = command.startedAt.toEpochMilli(),
                completedAtEpochMillis = null,
                rawTokenEvents = 0,
                rawCharacterCount = 0,
                visibleCharacterCount = 0,
                filteredCharacterCount = 0,
                firstRawTokenAtEpochMillis = null,
                firstVisibleTokenAtEpochMillis = null,
                stopReason = null,
                failureReason = null,
            ),
        )
        return traceId
    }

    override suspend fun recordAssistantGenerationFinished(command: AssistantGenerationFinishedCommand) {
        diagnosticsDao.finishAssistantGenerationTrace(
            traceId = command.traceId.raw,
            completedAtEpochMillis = command.completedAt.toEpochMilli(),
            rawTokenEvents = command.rawTokenEvents,
            rawCharacterCount = command.rawCharacterCount,
            visibleCharacterCount = command.visibleCharacterCount,
            filteredCharacterCount = command.filteredCharacterCount,
            firstRawTokenAtEpochMillis = command.firstRawTokenAt?.toEpochMilli(),
            firstVisibleTokenAtEpochMillis = command.firstVisibleTokenAt?.toEpochMilli(),
            stopReason = command.stopReason.name,
            failureReason = command.failureReason,
        )
    }
}
