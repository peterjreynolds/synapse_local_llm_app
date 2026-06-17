package app.synapse.localllm.data.runtime.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopSequenceTextFilterTest {
    @Test
    fun stopsBeforeCompleteStopSequence() {
        val filter = StopSequenceTextFilter(listOf("<|im_end|>"))

        val first = filter.append("Hello")
        val second = filter.append("<|im_end|>Hello again")

        assertEquals("Hello", first.visibleText)
        assertFalse(first.shouldStop)
        assertEquals("", second.visibleText)
        assertTrue(second.shouldStop)
        assertEquals("", filter.flush())
    }

    @Test
    fun withholdsPartialStopSequenceAcrossTokens() {
        val filter = StopSequenceTextFilter(listOf("<|im_end|>"))

        val first = filter.append("Hello<|im")
        val second = filter.append("_end|>leaked")

        assertEquals("Hello", first.visibleText)
        assertEquals("", second.visibleText)
        assertTrue(second.shouldStop)
    }
}
