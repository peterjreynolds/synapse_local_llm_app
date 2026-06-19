package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryClaimDomain
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryScope
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.memory.TraceEventRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultMemoryCandidateNormalizerTest {
    private val normalizer = DefaultMemoryCandidateNormalizer()

    @Test
    fun preservesDisplaySubjectWhileBuildingNormalizedClaimKey() {
        val traceEvent = userTrace("For Stuart Project, diarization is the main priority.")
        val candidate = MemoryClaimCandidate(
            kind = MemoryKind.PROJECT,
            text = "For Stuart Project, diarization is the main priority.",
            confidence = 0.88,
            sourceTraceEventIds = listOf(traceEvent.id),
            surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
            reasonCodes = listOf("test"),
            scope = MemoryScope.PROJECT,
            domain = MemoryClaimDomain.PROJECT,
            subject = "Stuart Project",
            predicate = "main priority",
            value = "diarization",
        )

        val normalizedCandidate = normalizer.normalizeMemoryCandidate(candidate, traceEvent)

        assertEquals("Stuart Project", normalizedCandidate.subject)
        assertEquals("main_priority", normalizedCandidate.predicate)
        assertEquals("project.project.stuart_project.main_priority", normalizedCandidate.claimKey)
        assertEquals(traceEvent.text, normalizedCandidate.sourceQuote)
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
