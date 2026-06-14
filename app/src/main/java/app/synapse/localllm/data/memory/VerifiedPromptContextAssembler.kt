package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.memory.PromptContextAssembler
import app.synapse.localllm.domain.memory.RetrievalBundle
import app.synapse.localllm.domain.runtime.ModelChatMessage

class VerifiedPromptContextAssembler : PromptContextAssembler {
    override suspend fun assemblePromptMessages(
        userMessage: String,
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
        return listOf(
            ModelChatMessage(
                role = ConversationRole.SYSTEM,
                content = systemContent,
            ),
            ModelChatMessage(
                role = ConversationRole.USER,
                content = userMessage,
            ),
        )
    }

    private companion object {
        const val MEMORY_USE_POLICY =
            "Use verified local memory only when directly relevant. " +
                "Do not expose memory IDs unless the user asks for diagnostics."
    }
}
