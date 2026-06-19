package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryClaimDomain
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryScope
import app.synapse.localllm.domain.memory.MemoryWriteIntent
import app.synapse.localllm.domain.memory.TraceEventRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedMemoryCandidateProposerTest {
    private val proposer = RuleBasedMemoryCandidateProposer()

    @Test
    fun proposesImplicitProjectPriorityMemory() {
        val traceEvent = userTrace("For Stuart, diarization is the main priority.")

        val candidate = proposer.proposeMemoryCandidates(traceEvent).single()

        assertEquals(MemoryKind.PROJECT, candidate.kind)
        assertEquals(MemoryClaimDomain.PROJECT, candidate.domain)
        assertEquals(MemoryScope.PROJECT, candidate.scope)
        assertEquals("Stuart", candidate.subject)
        assertEquals("priority", candidate.predicate)
        assertEquals("diarization is the main priority", candidate.value)
        assertEquals("project.project.stuart.priority", candidate.claimKey)
        assertEquals(MemoryWriteIntent.IMPLICIT_CANDIDATE, candidate.writeIntent)
        assertTrue(candidate.reasonCodes.contains("implicit-project-priority"))
    }

    @Test
    fun leavesVaguePraiseAsTraceOnly() {
        val traceEvent = userTrace("that was sick")

        val candidates = proposer.proposeMemoryCandidates(traceEvent)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun neverProposesFromAssistantText() {
        val traceEvent = userTrace("For Stuart, diarization is the main priority.")
            .copy(role = ConversationRole.ASSISTANT)

        val candidates = proposer.proposeMemoryCandidates(traceEvent)

        assertTrue(candidates.isEmpty())
    }

    private fun userTrace(text: String): TraceEventRecord =
        TraceEventRecord(
            id = TraceEventId("trace-1"),
            sourceMessageId = ChatMessageId("message-1"),
            role = ConversationRole.USER,
            text = text,
            observedAt = Instant.parse("2026-06-18T12:00:00Z"),
        )
}
