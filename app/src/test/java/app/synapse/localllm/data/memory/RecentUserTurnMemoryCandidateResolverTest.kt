package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryWriteIntent
import app.synapse.localllm.domain.memory.TraceEventRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentUserTurnMemoryCandidateResolverTest {
    private val resolver = RecentUserTurnMemoryCandidateResolver(
        memoryProjector = DeterministicMemoryProjector(),
        memoryCandidateProposer = RuleBasedMemoryCandidateProposer(),
    )

    @Test
    fun resolvesContextualSaveFromPreviousUserTurn() {
        val candidates = resolver.resolveContextualMemoryCandidates(
            traceEvent = userTrace("Add that to memory."),
            priorMessages = listOf(
                userMessage("Stuart is a meetings and formalization agent."),
            ),
        )

        val candidate = candidates.single()
        assertEquals(MemoryKind.PROJECT, candidate.kind)
        assertEquals("Stuart", candidate.subject)
        assertEquals("description", candidate.predicate)
        assertEquals("Stuart is a meetings and formalization agent.", candidate.text)
        assertEquals(MemoryWriteIntent.EXPLICIT_SAVE, candidate.writeIntent)
        assertTrue(candidate.reasonCodes.contains("contextual-memory-source-previous-user-turn"))
    }

    @Test
    fun appliesRecentUserCorrectionBeforeCreatingContextualMemory() {
        val candidates = resolver.resolveContextualMemoryCandidates(
            traceEvent = userTrace("but add what it is to your memory"),
            priorMessages = listOf(
                userMessage("Steward is a meetings and formalization agent."),
                assistantMessage("Saved."),
                userMessage("Stuart** not steward.."),
            ),
        )

        val candidate = candidates.single()
        assertEquals("Stuart", candidate.subject)
        assertEquals("Stuart is a meetings and formalization agent.", candidate.text)
        assertEquals("Stuart is a meetings and formalization agent.", candidate.sourceQuote)
    }

    @Test
    fun neverUsesAssistantTextAsContextualMemorySource() {
        val candidates = resolver.resolveContextualMemoryCandidates(
            traceEvent = userTrace("Add that to memory."),
            priorMessages = listOf(
                assistantMessage("Peter's full name is Wrong Fake Name."),
            ),
        )

        assertTrue(candidates.isEmpty())
    }

    private fun userTrace(text: String): TraceEventRecord =
        TraceEventRecord(
            id = TraceEventId("trace-1"),
            sourceMessageId = ChatMessageId("message-current"),
            role = ConversationRole.USER,
            text = text,
            observedAt = Instant.parse("2026-06-20T14:25:50Z"),
        )

    private fun userMessage(text: String): ChatMessageRecord =
        chatMessage(role = ConversationRole.USER, text = text)

    private fun assistantMessage(text: String): ChatMessageRecord =
        chatMessage(role = ConversationRole.ASSISTANT, text = text)

    private fun chatMessage(role: ConversationRole, text: String): ChatMessageRecord =
        ChatMessageRecord(
            id = ChatMessageId("message-${role.name}-$text"),
            threadId = ChatThreadId("thread-1"),
            role = role,
            body = text,
            deliveryState = MessageDeliveryState.COMPLETE,
            createdAt = Instant.parse("2026-06-20T14:25:50Z"),
            completedAt = Instant.parse("2026-06-20T14:25:50Z"),
            failureReason = null,
        )
}
