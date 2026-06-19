package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryClaimDomain
import app.synapse.localllm.domain.memory.MemoryWriteIntent
import app.synapse.localllm.domain.memory.TraceEventRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonMemoryCandidateParserTest {
    private val parser = JsonMemoryCandidateParser()

    @Test
    fun parsesStrictJsonCandidateWithSourceQuote() {
        val traceEvent = userTrace("For Stuart, diarization is the main priority.")
        val receipt = parser.parseCandidateProposal(
            rawJson = """
                {
                  "candidates": [
                    {
                      "scope": "PROJECT",
                      "domain": "PROJECT",
                      "subject": "Stuart",
                      "predicate": "priority",
                      "value": "diarization is the main priority",
                      "source_quote": "For Stuart, diarization is the main priority.",
                      "write_intent": "IMPLICIT_CANDIDATE",
                      "confidence": 0.88,
                      "durability_score": 0.9,
                      "future_usefulness_score": 0.92,
                      "sensitivity": "LOW",
                      "reason_codes": ["model-proposed-project-priority"],
                      "keywords": ["stuart", "diarization", "priority"]
                    }
                  ]
                }
            """.trimIndent(),
            traceEvent = traceEvent,
        )

        assertEquals(null, receipt.rejectionReason)
        val candidate = receipt.candidates.single()
        assertEquals(MemoryClaimDomain.PROJECT, candidate.domain)
        assertEquals("Stuart", candidate.subject)
        assertEquals("priority", candidate.predicate)
        assertEquals(MemoryWriteIntent.IMPLICIT_CANDIDATE, candidate.writeIntent)
    }

    @Test
    fun rejectsBadJson() {
        val receipt = parser.parseCandidateProposal(
            rawJson = "{ not json",
            traceEvent = userTrace("For Stuart, diarization is the main priority."),
        )

        assertTrue(receipt.candidates.isEmpty())
        assertNotNull(receipt.rejectionReason)
    }

    @Test
    fun rejectsMissingSourceQuote() {
        val receipt = parser.parseCandidateProposal(
            rawJson = """
                {
                  "candidates": [
                    {
                      "scope": "PROJECT",
                      "domain": "PROJECT",
                      "subject": "Stuart",
                      "predicate": "priority",
                      "value": "diarization is the main priority"
                    }
                  ]
                }
            """.trimIndent(),
            traceEvent = userTrace("For Stuart, diarization is the main priority."),
        )

        assertTrue(receipt.candidates.isEmpty())
        assertNotNull(receipt.rejectionReason)
    }

    @Test
    fun rejectsSourceQuoteNotPresentInUserText() {
        val receipt = parser.parseCandidateProposal(
            rawJson = """
                {
                  "candidates": [
                    {
                      "scope": "PROJECT",
                      "domain": "PROJECT",
                      "subject": "Stuart",
                      "predicate": "priority",
                      "value": "diarization is the main priority",
                      "source_quote": "This text was not said.",
                      "write_intent": "IMPLICIT_CANDIDATE",
                      "confidence": 0.88,
                      "durability_score": 0.9,
                      "future_usefulness_score": 0.92,
                      "sensitivity": "LOW"
                    }
                  ]
                }
            """.trimIndent(),
            traceEvent = userTrace("For Stuart, diarization is the main priority."),
        )

        assertTrue(receipt.candidates.isEmpty())
        assertNotNull(receipt.rejectionReason)
    }

    @Test
    fun rejectsAssistantOriginText() {
        val receipt = parser.parseCandidateProposal(
            rawJson = "[]",
            traceEvent = userTrace("For Stuart, diarization is the main priority.")
                .copy(role = ConversationRole.ASSISTANT),
        )

        assertTrue(receipt.candidates.isEmpty())
        assertNotNull(receipt.rejectionReason)
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
