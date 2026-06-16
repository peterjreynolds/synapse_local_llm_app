package app.synapse.localllm.data.runtime.embedded

import android.content.Context
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.runtime.ChatCompletionRequest
import app.synapse.localllm.domain.runtime.ChatStreamEvent
import app.synapse.localllm.domain.runtime.RuntimeStartReceipt
import app.synapse.localllm.domain.runtime.RuntimeStartStatus
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform

class EmbeddedLlamaRuntime(
    context: Context,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
) {
    private val applicationContext = context.applicationContext
    private val engine: EmbeddedLlamaEngine by lazy {
        EmbeddedLlamaEngine.getInstance(applicationContext)
    }

    suspend fun checkStatus(settings: SynapseSettings): RuntimeStatus {
        val modelPath = settings.embeddedModelPath
            ?: return RuntimeStatus.Unreachable(
                baseUrl = EMBEDDED_BASE_URL,
                checkedAt = clock.now(),
                reason = "No embedded GGUF model is selected.",
            )
        val modelFile = File(modelPath)
        if (!modelFile.isFile) {
            return RuntimeStatus.Unreachable(
                baseUrl = EMBEDDED_BASE_URL,
                checkedAt = clock.now(),
                reason = "Embedded model file is missing.",
            )
        }

        return when (val state = engine.state.value) {
            EmbeddedLlamaEngineState.ModelReady ->
                RuntimeStatus.Ready(EMBEDDED_BASE_URL, clock.now())

            EmbeddedLlamaEngineState.Generating,
            EmbeddedLlamaEngineState.LoadingModel,
            EmbeddedLlamaEngineState.ProcessingPrompt,
            -> RuntimeStatus.Starting(
                RuntimeStartReceipt(
                    id = idFactory.createReceiptId(),
                    status = RuntimeStartStatus.EMBEDDED_MODEL_READY,
                    requestedAt = clock.now(),
                    message = "Embedded llama.cpp is busy.",
                ),
            )

            is EmbeddedLlamaEngineState.Error ->
                RuntimeStatus.Unreachable(
                    baseUrl = EMBEDDED_BASE_URL,
                    checkedAt = clock.now(),
                    reason = state.exception.message ?: "Embedded llama.cpp failed.",
                )

            else -> RuntimeStatus.Unreachable(
                baseUrl = EMBEDDED_BASE_URL,
                checkedAt = clock.now(),
                reason = "Embedded llama.cpp is not loaded.",
            )
        }
    }

    suspend fun start(settings: SynapseSettings): RuntimeStartReceipt {
        val requestedAt = clock.now()
        val modelPath = settings.embeddedModelPath
            ?: return RuntimeStartReceipt(
                id = idFactory.createReceiptId(),
                status = RuntimeStartStatus.EMBEDDED_MODEL_MISSING,
                requestedAt = requestedAt,
                message = "Pick a GGUF model in Settings before starting embedded llama.cpp.",
            )

        return try {
            engine.loadModel(modelPath)
            RuntimeStartReceipt(
                id = idFactory.createReceiptId(),
                status = RuntimeStartStatus.EMBEDDED_MODEL_READY,
                requestedAt = requestedAt,
                message = "Embedded llama.cpp loaded ${settings.embeddedModelDisplayName ?: "model"}.",
            )
        } catch (exception: Exception) {
            RuntimeStartReceipt(
                id = idFactory.createReceiptId(),
                status = RuntimeStartStatus.FAILED,
                requestedAt = requestedAt,
                message = exception.message ?: "Embedded llama.cpp failed to start.",
            )
        }
    }

    fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatStreamEvent> =
        flow {
            val modelPath = request.embeddedModelPath
            if (modelPath == null) {
                emit(ChatStreamEvent.Failed("No embedded GGUF model is selected."))
                return@flow
            }
            engine.loadModel(modelPath)
            engine.streamResponse(
                systemPrompt = extractSystemPrompt(request),
                userPrompt = buildEmbeddedPrompt(request),
                maxTokens = request.maxTokens,
                temperature = request.temperature,
            ).transform { token ->
                emit(ChatStreamEvent.Token(token))
            }.onCompletion { failure ->
                if (failure == null) {
                    emit(ChatStreamEvent.Completed(clock.now()))
                }
            }.catch { exception ->
                emit(ChatStreamEvent.Failed(exception.message ?: "Embedded llama.cpp generation failed."))
            }.collect { event ->
                emit(event)
            }
        }

    fun cancelGeneration() {
        engine.cancelGeneration()
    }

    private fun extractSystemPrompt(request: ChatCompletionRequest): String =
        request.messages
            .firstOrNull { message -> message.role == ConversationRole.SYSTEM }
            ?.content
            ?.let { systemPrompt -> "<|im_start|>system\n$systemPrompt<|im_end|>\n" }
            .orEmpty()

    private fun buildEmbeddedPrompt(request: ChatCompletionRequest): String =
        request.messages
            .filterNot { message -> message.role == ConversationRole.SYSTEM }
            .joinToString(separator = "") { message ->
                when (message.role) {
                    ConversationRole.USER -> "<|im_start|>user\n${message.content}<|im_end|>\n"
                    ConversationRole.ASSISTANT -> "<|im_start|>assistant\n${message.content}<|im_end|>\n"
                    ConversationRole.SYSTEM -> message.content
                }
            } + "<|im_start|>assistant\n"

    private companion object {
        const val EMBEDDED_BASE_URL = "embedded://llama.cpp"
    }
}
