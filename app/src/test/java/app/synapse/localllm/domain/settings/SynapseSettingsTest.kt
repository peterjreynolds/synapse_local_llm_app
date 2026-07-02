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
    fun migratesOldDefaultPersonaAndInstructionsToCurrentDefaults() {
        assertEquals(DEFAULT_PERSONA, normalizePersona(LEGACY_DEFAULT_PERSONA))
        assertEquals(
            DEFAULT_CUSTOM_INSTRUCTIONS,
            normalizeCustomInstructions(LEGACY_DEFAULT_CUSTOM_INSTRUCTIONS),
        )
        assertEquals(
            DEFAULT_CUSTOM_INSTRUCTIONS,
            normalizeCustomInstructions(LEGACY_CORE_LEAKING_CUSTOM_INSTRUCTIONS),
        )
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

        assertTrue(prompt.contains("You are direct."))
        assertTrue(prompt.contains("User preferences: Use short answers."))
        assertFalse(prompt.contains("Core behavior:"))
        assertFalse(prompt.contains("Custom instructions:"))
    }

    @Test
    fun currentDefaultPromptBlocksHiddenThinking() {
        val prompt = composeSystemPrompt(DEFAULT_PERSONA, DEFAULT_CUSTOM_INSTRUCTIONS)

        assertTrue(prompt.contains("Never expose hidden reasoning"))
        assertTrue(prompt.contains("Write visible assistant answer text immediately."))
        assertFalse(DEFAULT_CUSTOM_INSTRUCTIONS.contains("hidden reasoning"))
        assertFalse(DEFAULT_CUSTOM_INSTRUCTIONS.contains("<think>"))
    }

    @Test
    fun smsAutoReplyDefaultsOff() {
        assertFalse(SynapseSettings().smsAutoReplyEnabled)
    }

    @Test
    fun extractsEditableInstructionsFromLegacyComposedPrompt() {
        val legacyPrompt = buildString {
            append("You are Synapse, a private phone-local assistant inside an Android chat app. ")
            append("Never expose hidden reasoning. ")
            append(LEGACY_USER_INSTRUCTIONS_MARKER)
            append(" Use terse answers.")
        }

        assertEquals("Use terse answers.", extractEditableCustomInstructions(legacyPrompt))
    }
}
