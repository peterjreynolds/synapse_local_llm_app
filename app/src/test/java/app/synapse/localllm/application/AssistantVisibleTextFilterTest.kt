package app.synapse.localllm.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantVisibleTextFilterTest {
    @Test
    fun removesThinkBlocksFromVisibleText() {
        val filter = AssistantVisibleTextFilter()

        val first = filter.appendToken("<think>private reasoning")
        val second = filter.appendToken("</think>Visible answer.")

        assertEquals("", first.visibleDelta)
        assertEquals("Visible answer.", second.visibleDelta)
    }

    @Test
    fun stopsBeforeGeneratedUserTurn() {
        val filter = AssistantVisibleTextFilter()

        val first = filter.appendToken("Sure.")
        val second = filter.appendToken("\n\nUser:\nnew fake turn")

        assertEquals("Sure.", first.visibleDelta)
        assertEquals("", second.visibleDelta)
        assertTrue(second.shouldStopGeneration)
    }

    @Test
    fun removesLeadingAssistantLabel() {
        val filter = AssistantVisibleTextFilter()

        val filtered = filter.appendToken("Assistant: Got it.")

        assertEquals("Got it.", filtered.visibleDelta)
    }

    @Test
    fun reportsVisibleAndFilteredCharacterCounts() {
        val filter = AssistantVisibleTextFilter()

        filter.appendToken("<think>private</think>Visible answer.")

        assertEquals("Visible answer.".length, filter.visibleCharacterCount)
        assertTrue(filter.filteredCharacterCount > 0)
    }
}
