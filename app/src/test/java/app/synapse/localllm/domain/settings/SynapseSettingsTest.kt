package app.synapse.localllm.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynapseSettingsTest {
    @Test
    fun normalizesBlankSystemPromptToDefault() {
        assertEquals(
            composeSystemPrompt(DEFAULT_PERSONA, DEFAULT_CUSTOM_INSTRUCTIONS),
            normalizeSystemPrompt("  "),
        )
    }

    @Test
    fun normalizesLegacyRawDataSystemPromptToDefault() {
        val normalizedPrompt = normalizeSystemPrompt(LEGACY_RAW_DATA_SYSTEM_PROMPT)

        assertEquals(composeSystemPrompt(DEFAULT_PERSONA, DEFAULT_CUSTOM_INSTRUCTIONS), normalizedPrompt)
        assertFalse(normalizedPrompt.contains("Raw Data"))
    }

    @Test
    fun preservesCustomSystemPrompt() {
        val customPrompt = "You are Synapse. Answer conversationally."

        assertEquals(customPrompt, normalizeSystemPrompt(customPrompt))
    }

    @Test
    fun composesPersonaAndCustomInstructionsIntoSystemPrompt() {
        val prompt = composeSystemPrompt(
            persona = "You are direct.",
            customInstructions = "Use short answers.",
        )

        assertTrue(prompt.contains("Persona:\nYou are direct."))
        assertTrue(prompt.contains("Custom instructions:\nUse short answers."))
    }
}
