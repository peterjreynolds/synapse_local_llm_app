package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryImplicitScore
import app.synapse.localllm.domain.memory.MemoryImplicitScorer
import app.synapse.localllm.domain.memory.MemorySensitivity
import app.synapse.localllm.domain.memory.MemoryWriteOutcome

class ConservativeMemoryImplicitScorer : MemoryImplicitScorer {
    override fun scoreImplicitMemoryCandidate(candidate: MemoryClaimCandidate): MemoryImplicitScore {
        if (candidate.sourceQuote.isNullOrBlank()) {
            return MemoryImplicitScore(
                score = 0.0,
                outcome = MemoryWriteOutcome.REJECTED,
                reason = "Implicit memory candidate has no source quote.",
            )
        }

        if (candidate.sensitivity == MemorySensitivity.HIGH) {
            return MemoryImplicitScore(
                score = calculateAdmissionScore(candidate),
                outcome = MemoryWriteOutcome.REQUIRES_CONFIRMATION,
                reason = "Sensitive implicit memory requires user review before activation.",
            )
        }

        val admissionScore = calculateAdmissionScore(candidate)
        val outcome = when {
            admissionScore >= AUTO_SAVE_THRESHOLD && candidate.sensitivity == MemorySensitivity.LOW ->
                MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN
            admissionScore >= REVIEW_THRESHOLD -> MemoryWriteOutcome.QUARANTINED
            else -> MemoryWriteOutcome.TRACE_ONLY
        }
        val reason = when (outcome) {
            MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN ->
                "Implicit memory candidate passed conservative admission score."
            MemoryWriteOutcome.QUARANTINED ->
                "Implicit memory candidate needs review before prompt use."
            else -> "Implicit memory candidate is too weak for durable memory."
        }

        return MemoryImplicitScore(
            score = admissionScore,
            outcome = outcome,
            reason = reason,
        )
    }

    private fun calculateAdmissionScore(candidate: MemoryClaimCandidate): Double {
        val confidence = candidate.confidence.coerceIn(0.0, 1.0)
        val durability = candidate.durabilityScore.coerceIn(0.0, 1.0)
        val futureUsefulness = candidate.futureUsefulnessScore.coerceIn(0.0, 1.0)
        val sensitivityPenalty = when (candidate.sensitivity) {
            MemorySensitivity.LOW -> 0.0
            MemorySensitivity.MEDIUM -> 0.12
            MemorySensitivity.HIGH -> 0.35
        }
        return ((confidence * 0.4) + (durability * 0.3) + (futureUsefulness * 0.3) - sensitivityPenalty)
            .coerceIn(0.0, 1.0)
    }

    private companion object {
        const val AUTO_SAVE_THRESHOLD = 0.85
        const val REVIEW_THRESHOLD = 0.55
    }
}
