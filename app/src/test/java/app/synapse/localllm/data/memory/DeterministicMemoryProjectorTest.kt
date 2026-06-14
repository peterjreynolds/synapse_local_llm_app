package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ChatMessageId
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.memory.TraceEventRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicMemoryProjectorTest {
    private val projector = DeterministicMemoryProjector()

    @Test
    fun extractsExplicitPreferenceWithSourceEvidence() {
        val traceEvent = userTrace("I prefer concise Kotlin code when we are building Android apps.")

        val candidates = projector.extractMemoryCandidates(traceEvent)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(MemoryKind.PREFERENCE, candidate.kind)
        assertEquals(SurfacePolicy.PROMPT_VISIBLE, candidate.surfacePolicy)
        assertEquals(listOf(traceEvent.id), candidate.sourceTraceEventIds)
        assertTrue(candidate.reasonCodes.contains("explicit-user-preference"))
    }

    @Test
    fun ignoresAssistantTextForDurableMemoryExtraction() {
        val traceEvent = TraceEventRecord(
            id = TraceEventId("trace-1"),
            sourceMessageId = ChatMessageId("message-1"),
            role = ConversationRole.ASSISTANT,
            text = "I prefer concise Kotlin code.",
            observedAt = Instant.parse("2026-06-14T16:00:00Z"),
        )

        val candidates = projector.extractMemoryCandidates(traceEvent)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun extractsExplicitCommitment() {
        val traceEvent = userTrace("Remind me to test the APK on my S25 Ultra tonight.")

        val candidates = projector.extractMemoryCandidates(traceEvent)

        assertEquals(MemoryKind.COMMITMENT, candidates.single().kind)
        assertTrue(candidates.single().reasonCodes.contains("explicit-user-commitment"))
    }

    private fun userTrace(text: String): TraceEventRecord =
        TraceEventRecord(
            id = TraceEventId("trace-1"),
            sourceMessageId = ChatMessageId("message-1"),
            role = ConversationRole.USER,
            text = text,
            observedAt = Instant.parse("2026-06-14T16:00:00Z"),
        )
}
