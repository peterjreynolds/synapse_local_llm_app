package app.synapse.localllm.data.runtime

import app.synapse.localllm.data.runtime.embedded.EmbeddedLlamaRuntime
import app.synapse.localllm.domain.runtime.ChatCompletionRequest
import app.synapse.localllm.domain.runtime.ChatStreamEvent
import app.synapse.localllm.domain.runtime.LocalInferenceRuntime
import app.synapse.localllm.domain.runtime.RuntimeStartReceipt
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.runtime.StartLlamaServerCommand
import app.synapse.localllm.domain.settings.InferenceRuntimeBackend
import app.synapse.localllm.domain.settings.SynapseSettings
import kotlinx.coroutines.flow.Flow

class PhoneLocalInferenceRuntime(
    private val llamaServerGateway: LlamaServerGateway,
    private val termuxCommandGateway: TermuxCommandGateway,
    private val embeddedLlamaRuntime: EmbeddedLlamaRuntime,
) : LocalInferenceRuntime {
    override suspend fun checkRuntimeStatus(settings: SynapseSettings): RuntimeStatus =
        when (settings.runtimeBackend) {
            InferenceRuntimeBackend.EMBEDDED_LLAMA -> embeddedLlamaRuntime.checkStatus(settings)
            InferenceRuntimeBackend.LLAMA_SERVER -> llamaServerGateway.checkStatus(settings.baseUrl)
        }

    override suspend fun startRuntime(
        settings: SynapseSettings,
        command: StartLlamaServerCommand,
    ): RuntimeStartReceipt =
        when (settings.runtimeBackend) {
            InferenceRuntimeBackend.EMBEDDED_LLAMA -> embeddedLlamaRuntime.start(settings)
            InferenceRuntimeBackend.LLAMA_SERVER -> termuxCommandGateway.startLlamaServer(command)
        }

    override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatStreamEvent> =
        when (request.backend) {
            InferenceRuntimeBackend.EMBEDDED_LLAMA -> embeddedLlamaRuntime.streamChatCompletion(request)
            InferenceRuntimeBackend.LLAMA_SERVER -> llamaServerGateway.streamChatCompletion(request)
        }

    override fun cancelActiveGeneration() {
        embeddedLlamaRuntime.cancelGeneration()
    }
}
