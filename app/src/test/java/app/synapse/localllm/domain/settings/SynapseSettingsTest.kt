package app.synapse.localllm.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SynapseSettingsTest {
    @Test
    fun normalizesBlankSystemPromptToDefault() {
        assertEquals(DEFAULT_SYSTEM_PROMPT, normalizeSystemPrompt("  "))
    }

    @Test
    fun normalizesLegacyRawDataSystemPromptToDefault() {
        val normalizedPrompt = normalizeSystemPrompt(LEGACY_RAW_DATA_SYSTEM_PROMPT)

        assertEquals(DEFAULT_SYSTEM_PROMPT, normalizedPrompt)
        assertFalse(normalizedPrompt.contains("Raw Data"))
    }

    @Test
    fun preservesCustomSystemPrompt() {
        val customPrompt = "You are Synapse. Answer conversationally."

        assertEquals(customPrompt, normalizeSystemPrompt(customPrompt))
    }
}
