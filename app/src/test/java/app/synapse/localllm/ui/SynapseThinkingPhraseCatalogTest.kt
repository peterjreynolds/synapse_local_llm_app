package app.synapse.localllm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SynapseThinkingPhraseCatalogTest {
    @Test
    fun thinkingPhraseCatalogContainsThreeHundredDistinctPhrases() {
        assertEquals(300, SynapseThinkingPhrases.size)
        assertEquals(SynapseThinkingPhrases.size, SynapseThinkingPhrases.distinct().size)
        assertTrue(SynapseThinkingPhrases.all { phrase -> phrase.isNotBlank() })
    }
}
