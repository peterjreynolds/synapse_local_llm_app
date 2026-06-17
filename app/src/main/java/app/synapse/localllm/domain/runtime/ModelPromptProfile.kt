package app.synapse.localllm.domain.runtime

enum class ModelPromptProfile {
    AUTO,
    QWEN_CHATML,
    LLAMA_INSTRUCT,
    PLAIN_COMPLETION,
}

data class EmbeddedPromptFormat(
    val promptText: String,
    val stopSequences: List<String>,
    val resolvedProfile: ModelPromptProfile,
)
