package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.memory.PromptContextAssembler
import app.synapse.localllm.domain.memory.RetrievalBundle
import app.synapse.localllm.domain.runtime.AssistantTextSanitizer
import app.synapse.localllm.domain.runtime.ModelChatMessage

class VerifiedPromptContextAssembler : PromptContextAssembler {
    override suspend fun assemblePromptMessages(
        userMessage: String,
        priorMessages: List<ChatMessageRecord>,
        retrievalBundle: RetrievalBundle,
        systemPrompt: String,
    ): List<ModelChatMessage> {
        val systemContent = buildString {
            append(systemPrompt.trim())
            append("\n\n")
            append(MEMORY_USE_POLICY)
            if (retrievalBundle.promptBlock.isNotBlank()) {
                append("\n\nVerified local memory:\n")
                append(retrievalBundle.promptBlock)
            }
        }
        val recentMessages = priorMessages
            .filter { message -> message.deliveryState == MessageDeliveryState.COMPLETE }
            .filter { message -> message.role == ConversationRole.USER || message.role == ConversationRole.ASSISTANT }
            .takeLast(RECENT_MESSAGE_LIMIT)
            .mapNotNull { message ->
                val promptHistoryText = when (message.role) {
                    ConversationRole.ASSISTANT ->
                        AssistantTextSanitizer.sanitizeForPromptHistory(message.body)

                    else -> message.body.trim().takeIf { text -> text.isNotBlank() }
                }
                promptHistoryText?.let { content ->
                    ModelChatMessage(
                        role = message.role,
                        content = content,
                    )
                }
            }

        return listOf(
            ModelChatMessage(
                role = ConversationRole.SYSTEM,
                content = systemContent,
            ),
        ) + recentMessages + ModelChatMessage(
                role = ConversationRole.USER,
                content = userMessage,
            )
    }

    private companion object {
        const val RECENT_MESSAGE_LIMIT = 8

        const val MEMORY_USE_POLICY =
            "Use verified local memory only when directly relevant. " +
                "Do not expose memory IDs unless the user asks for diagnostics. " +
                "If verified local memory does not contain a personal, project, appointment, " +
                "or preference fact needed to answer, say you do not know instead of guessing. " +
                "Only say a memory was saved when verified local memory contains the matching saved fact."
    }
}
