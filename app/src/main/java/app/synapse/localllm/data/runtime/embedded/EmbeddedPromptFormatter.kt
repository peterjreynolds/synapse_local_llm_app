package app.synapse.localllm.data.runtime.embedded

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.runtime.EmbeddedPromptFormat
import app.synapse.localllm.domain.runtime.ModelChatMessage
import app.synapse.localllm.domain.runtime.ModelPromptProfile

internal class EmbeddedPromptFormatter(
    private val modelTemplateFormatter: ModelTemplateFormatter,
) {
    fun formatPrompt(
        modelName: String,
        modelPath: String?,
        requestedProfile: ModelPromptProfile,
        messages: List<ModelChatMessage>,
    ): EmbeddedPromptFormat {
        val resolvedProfile = resolveProfile(modelName, modelPath, requestedProfile)
        if (resolvedProfile == ModelPromptProfile.AUTO) {
            val modelTemplatePrompt = modelTemplateFormatter.formatWithModelTemplate(messages)
            if (modelTemplatePrompt != null) {
                return EmbeddedPromptFormat(
                    promptText = modelTemplatePrompt,
                    stopSequences = UNIVERSAL_STOP_SEQUENCES,
                    resolvedProfile = ModelPromptProfile.AUTO,
                )
            }
        }

        return when (val concreteProfile = resolvedProfile.toConcreteProfile()) {
            ModelPromptProfile.QWEN_CHATML ->
                EmbeddedPromptFormat(
                    promptText = formatQwenChatMl(messages),
                    stopSequences = QWEN_STOP_SEQUENCES,
                    resolvedProfile = concreteProfile,
                )

            ModelPromptProfile.LLAMA_INSTRUCT -> {
                val llamaPrompt = modelTemplateFormatter.formatWithNamedTemplate(
                    templateName = LLAMA_3_TEMPLATE_NAME,
                    messages = messages,
                )
                EmbeddedPromptFormat(
                    promptText = llamaPrompt ?: formatPlainTranscript(messages),
                    stopSequences = LLAMA_STOP_SEQUENCES,
                    resolvedProfile = concreteProfile,
                )
            }

            ModelPromptProfile.PLAIN_COMPLETION ->
                EmbeddedPromptFormat(
                    promptText = formatPlainTranscript(messages),
                    stopSequences = PLAIN_STOP_SEQUENCES,
                    resolvedProfile = concreteProfile,
                )

            ModelPromptProfile.AUTO ->
                EmbeddedPromptFormat(
                    promptText = formatPlainTranscript(messages),
                    stopSequences = PLAIN_STOP_SEQUENCES,
                    resolvedProfile = ModelPromptProfile.PLAIN_COMPLETION,
                )
        }
    }

    private fun resolveProfile(
        modelName: String,
        modelPath: String?,
        requestedProfile: ModelPromptProfile,
    ): ModelPromptProfile {
        if (requestedProfile != ModelPromptProfile.AUTO) return requestedProfile
        val normalizedModelName = "$modelName ${modelPath.orEmpty()}".lowercase()
        return when {
            "qwen" in normalizedModelName -> ModelPromptProfile.QWEN_CHATML
            else -> ModelPromptProfile.AUTO
        }
    }

    private fun ModelPromptProfile.toConcreteProfile(): ModelPromptProfile =
        if (this == ModelPromptProfile.AUTO) ModelPromptProfile.PLAIN_COMPLETION else this

    private fun formatQwenChatMl(messages: List<ModelChatMessage>): String =
        messages.joinToString(separator = "") { message ->
            val role = when (message.role) {
                ConversationRole.SYSTEM -> "system"
                ConversationRole.USER -> "user"
                ConversationRole.ASSISTANT -> "assistant"
            }
            "<|im_start|>$role\n${message.content}<|im_end|>\n"
        } + "<|im_start|>assistant\n"

    private fun formatPlainTranscript(messages: List<ModelChatMessage>): String {
        val systemMessage = messages.firstOrNull { message -> message.role == ConversationRole.SYSTEM }
        val conversationMessages = messages.filterNot { message -> message.role == ConversationRole.SYSTEM }
        return buildString {
            if (systemMessage != null) {
                append(systemMessage.content.trim())
                append("\n\n")
            }
            conversationMessages.forEach { message ->
                append(message.role.toPlainTranscriptLabel())
                append(": ")
                append(message.content.trim())
                append("\n\n")
            }
            append("Assistant: ")
        }
    }

    private fun ConversationRole.toPlainTranscriptLabel(): String =
        when (this) {
            ConversationRole.SYSTEM -> "System"
            ConversationRole.USER -> "User"
            ConversationRole.ASSISTANT -> "Assistant"
        }

    private companion object {
        const val LLAMA_3_TEMPLATE_NAME = "llama3"
        val UNIVERSAL_STOP_SEQUENCES = listOf(
            "<|im_end|>",
            "<|eot_id|>",
            "<|endoftext|>",
            "</s>",
        )
        val QWEN_STOP_SEQUENCES = UNIVERSAL_STOP_SEQUENCES
        val LLAMA_STOP_SEQUENCES = UNIVERSAL_STOP_SEQUENCES
        val PLAIN_STOP_SEQUENCES = UNIVERSAL_STOP_SEQUENCES + listOf(
            "\nUser:",
            "\nSystem:",
            "\nAssistant:",
        )
    }
}

internal interface ModelTemplateFormatter {
    fun formatWithModelTemplate(messages: List<ModelChatMessage>): String?

    fun formatWithNamedTemplate(templateName: String, messages: List<ModelChatMessage>): String?
}
