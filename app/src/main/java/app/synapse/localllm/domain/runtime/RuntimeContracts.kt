package app.synapse.localllm.domain.runtime

import android.annotation.SuppressLint
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.ReceiptId
import app.synapse.localllm.domain.settings.InferenceRuntimeBackend
import app.synapse.localllm.domain.settings.SynapseSettings
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@SuppressLint("SdCardPath")
data class StartLlamaServerCommand(
    // Termux RUN_COMMAND requires Termux app paths; Synapse Context paths would be wrong here.
    val commandPath: String = "/data/data/com.termux/files/usr/bin/bash",
    val workingDirectory: String = "/data/data/com.termux/files/home/llama.cpp/build",
    val arguments: List<String> = listOf("-lc", DEFAULT_LLAMA_SERVER_SCRIPT),
    val runInBackground: Boolean = true,
)

data class RuntimeStartReceipt(
    val id: ReceiptId,
    val status: RuntimeStartStatus,
    val requestedAt: Instant,
    val message: String,
)

enum class RuntimeStartStatus {
    SENT_TO_TERMUX,
    EMBEDDED_MODEL_READY,
    EMBEDDED_MODEL_MISSING,
    TERMUX_UNAVAILABLE,
    TERMUX_PERMISSION_MISSING,
    FAILED,
}

sealed interface RuntimeStatus {
    data class Ready(
        val baseUrl: String,
        val checkedAt: Instant,
    ) : RuntimeStatus

    data class Unreachable(
        val baseUrl: String,
        val checkedAt: Instant,
        val reason: String,
    ) : RuntimeStatus

    data class Starting(
        val receipt: RuntimeStartReceipt,
    ) : RuntimeStatus

    data object Unknown : RuntimeStatus
}

data class ModelChatMessage(
    val role: ConversationRole,
    val content: String,
)

data class ChatCompletionRequest(
    val backend: InferenceRuntimeBackend,
    val baseUrl: String,
    val model: String,
    val embeddedModelPath: String?,
    val messages: List<ModelChatMessage>,
    val temperature: Double,
    val maxTokens: Int,
)

sealed interface ChatStreamEvent {
    data class Token(
        val text: String,
    ) : ChatStreamEvent

    data class Completed(
        val completedAt: Instant,
    ) : ChatStreamEvent

    data class Failed(
        val reason: String,
    ) : ChatStreamEvent
}

interface LocalInferenceRuntime {
    suspend fun checkRuntimeStatus(settings: SynapseSettings): RuntimeStatus

    suspend fun startRuntime(settings: SynapseSettings, command: StartLlamaServerCommand): RuntimeStartReceipt

    fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatStreamEvent>
}

const val DEFAULT_LLAMA_SERVER_SCRIPT =
    "cd ~/llama.cpp/build || exit 1; " +
        "command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock; " +
        "if ! curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then " +
        "nohup ./bin/llama-server -m ../hardcore.gguf -c 2048 -t 6 " +
        "--host 127.0.0.1 --port 8080 " +
        "> ~/synapse-llama-server.log 2>&1 < /dev/null & " +
        "fi"
