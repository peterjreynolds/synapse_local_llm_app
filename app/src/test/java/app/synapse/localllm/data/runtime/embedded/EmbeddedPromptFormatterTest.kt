package app.synapse.localllm.data.runtime.embedded

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.runtime.ModelChatMessage
import app.synapse.localllm.domain.runtime.ModelPromptProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedPromptFormatterTest {
    @Test
    fun autoQwenUsesChatMlProfile() {
        val formatter = EmbeddedPromptFormatter(NoopModelTemplateFormatter)

        val format = formatter.formatPrompt(
            modelName = "Qwen3.5-9B-Q4_K_M",
            modelPath = "/models/Qwen3.5-9B-Q4_K_M.gguf",
            requestedProfile = ModelPromptProfile.AUTO,
            messages = sampleMessages(),
        )

        assertEquals(ModelPromptProfile.QWEN_CHATML, format.resolvedProfile)
        assertTrue(format.promptText.contains("<|im_start|>system"))
        assertTrue(format.stopSequences.contains("<|im_end|>"))
    }

    @Test
    fun autoNonQwenUsesModelTemplateWhenAvailable() {
        val formatter = EmbeddedPromptFormatter(
            object : ModelTemplateFormatter {
                override fun formatWithModelTemplate(messages: List<ModelChatMessage>): String =
                    "model-template-prompt"

                override fun formatWithNamedTemplate(
                    templateName: String,
                    messages: List<ModelChatMessage>,
                ): String? = null
            },
        )

        val format = formatter.formatPrompt(
            modelName = "hardcore",
            modelPath = "/models/hardcore.gguf",
            requestedProfile = ModelPromptProfile.AUTO,
            messages = sampleMessages(),
        )

        assertEquals(ModelPromptProfile.AUTO, format.resolvedProfile)
        assertEquals("model-template-prompt", format.promptText)
    }

    private fun sampleMessages(): List<ModelChatMessage> =
        listOf(
            ModelChatMessage(ConversationRole.SYSTEM, "Be useful."),
            ModelChatMessage(ConversationRole.USER, "Hello"),
        )

    private object NoopModelTemplateFormatter : ModelTemplateFormatter {
        override fun formatWithModelTemplate(messages: List<ModelChatMessage>): String? = null

        override fun formatWithNamedTemplate(
            templateName: String,
            messages: List<ModelChatMessage>,
        ): String? = null
    }
}
