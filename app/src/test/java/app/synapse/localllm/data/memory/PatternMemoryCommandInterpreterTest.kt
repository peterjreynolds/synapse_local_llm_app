package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryCommand
import app.synapse.localllm.domain.memory.TraceEventRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternMemoryCommandInterpreterTest {
    private val interpreter = PatternMemoryCommandInterpreter()

    @Test
    fun interpretsForgetRequestAsTombstoneCommand() {
        val command = interpreter.interpretMemoryCommand(
            userTrace("Forget my favorite food memory."),
        )

        assertTrue(command is MemoryCommand.TombstoneMatchingMemories)
        assertEquals("favorite food", (command as MemoryCommand.TombstoneMatchingMemories).query)
    }

    @Test
    fun saveLikeDontForgetRequestContinuesToExtraction() {
        val command = interpreter.interpretMemoryCommand(
            userTrace("Don't forget that my favorite food is pizza."),
        )

        assertEquals(MemoryCommand.ContinueExtraction, command)
    }

    @Test
    fun assistantTextNeverCreatesMemoryCommand() {
        val command = interpreter.interpretMemoryCommand(
            userTrace("Forget my favorite food.").copy(role = ConversationRole.ASSISTANT),
        )

        assertEquals(MemoryCommand.ContinueExtraction, command)
    }

    private fun userTrace(text: String): TraceEventRecord =
        TraceEventRecord(
            id = TraceEventId("trace-1"),
            sourceMessageId = ChatMessageId("message-1"),
            role = ConversationRole.USER,
            text = text,
            observedAt = Instant.parse("2026-06-18T16:00:00Z"),
        )
}
