package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryProjector
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.memory.TraceEventRecord

class DeterministicMemoryProjector : MemoryProjector {
    override fun extractMemoryCandidates(traceEvent: TraceEventRecord): List<MemoryClaimCandidate> {
        if (traceEvent.role != ConversationRole.USER) return emptyList()

        val normalizedText = traceEvent.text.trim().replace(Regex("\\s+"), " ")
        if (normalizedText.length < MINIMUM_MEMORY_TEXT_LENGTH) return emptyList()

        return listOfNotNull(
            extractPreferenceCandidate(normalizedText, traceEvent),
            extractCommitmentCandidate(normalizedText, traceEvent),
            extractProcedureCandidate(normalizedText, traceEvent),
        ).distinctBy { candidate -> candidate.kind to candidate.text.lowercase() }
    }

    private fun extractPreferenceCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
    ): MemoryClaimCandidate? {
        val match = preferencePattern.find(normalizedText) ?: return null
        return buildCandidate(
            kind = MemoryKind.PREFERENCE,
            text = "User ${match.value.trim()}",
            traceEvent = traceEvent,
            reasonCodes = listOf("explicit-user-preference"),
        )
    }

    private fun extractCommitmentCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
    ): MemoryClaimCandidate? {
        val match = commitmentPattern.find(normalizedText) ?: return null
        return buildCandidate(
            kind = MemoryKind.COMMITMENT,
            text = match.value.trim(),
            traceEvent = traceEvent,
            reasonCodes = listOf("explicit-user-commitment"),
        )
    }

    private fun extractProcedureCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
    ): MemoryClaimCandidate? {
        val match = procedurePattern.find(normalizedText) ?: return null
        return buildCandidate(
            kind = MemoryKind.PROCEDURE,
            text = match.value.trim(),
            traceEvent = traceEvent,
            reasonCodes = listOf("explicit-user-boundary-or-procedure"),
        )
    }

    private fun buildCandidate(
        kind: MemoryKind,
        text: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate =
        MemoryClaimCandidate(
            kind = kind,
            text = text.take(MAXIMUM_MEMORY_TEXT_LENGTH),
            confidence = DEFAULT_EXPLICIT_CONFIDENCE,
            sourceTraceEventIds = listOf(traceEvent.id),
            surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
            reasonCodes = reasonCodes,
        )

    private companion object {
        const val MINIMUM_MEMORY_TEXT_LENGTH = 8
        const val MAXIMUM_MEMORY_TEXT_LENGTH = 280
        const val DEFAULT_EXPLICIT_CONFIDENCE = 0.86

        val preferencePattern =
            Regex(
                pattern = "\\bI\\s+(like|love|prefer|hate|dislike|want|need)\\b.{2,140}",
                option = RegexOption.IGNORE_CASE,
            )
        val commitmentPattern =
            Regex(
                pattern = "\\b(remind me|remember to|don't forget|we need to|I need to)\\b.{2,140}",
                option = RegexOption.IGNORE_CASE,
            )
        val procedurePattern =
            Regex(
                pattern = "\\b(always|never)\\b.{2,140}",
                option = RegexOption.IGNORE_CASE,
            )
    }
}
