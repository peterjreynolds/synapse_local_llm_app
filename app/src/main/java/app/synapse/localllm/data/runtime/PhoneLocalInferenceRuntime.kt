package app.synapse.localllm.data.runtime

import app.synapse.localllm.domain.runtime.ChatCompletionRequest
import app.synapse.localllm.domain.runtime.ChatStreamEvent
import app.synapse.localllm.domain.runtime.LocalInferenceRuntime
import app.synapse.localllm.domain.runtime.RuntimeStartReceipt
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.runtime.StartLlamaServerCommand
import kotlinx.coroutines.flow.Flow

class PhoneLocalInferenceRuntime(
    private val llamaServerGateway: LlamaServerGateway,
    private val termuxCommandGateway: TermuxCommandGateway,
) : LocalInferenceRuntime {
    override suspend fun checkRuntimeStatus(baseUrl: String): RuntimeStatus =
        llamaServerGateway.checkStatus(baseUrl)

    override suspend fun startRuntime(command: StartLlamaServerCommand): RuntimeStartReceipt =
        termuxCommandGateway.startLlamaServer(command)

    override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatStreamEvent> =
        llamaServerGateway.streamChatCompletion(request)
}
