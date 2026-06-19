package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.memory.MemoryCandidateNormalizer
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryClaimDomain
import app.synapse.localllm.domain.memory.MemoryScope
import app.synapse.localllm.domain.memory.MemorySensitivity
import app.synapse.localllm.domain.memory.TraceEventRecord

class DefaultMemoryCandidateNormalizer : MemoryCandidateNormalizer {
    override fun normalizeMemoryCandidate(
        candidate: MemoryClaimCandidate,
        traceEvent: TraceEventRecord,
    ): MemoryClaimCandidate {
        val normalizedDomain = candidate.domain
        val displaySubject = candidate.subject
            ?.trim()
            ?.takeIf { subject -> subject.isNotBlank() }
        val claimKeySubject = normalizeClaimKeyPart(displaySubject)
        val normalizedPredicate = normalizePredicate(candidate.predicate)
        val normalizedValue = candidate.value
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        val normalizedSourceQuote = candidate.sourceQuote
            ?.trim()
            ?.takeIf { sourceQuote -> sourceQuote.isNotBlank() }
            ?: traceEvent.text.trim().take(MAXIMUM_SOURCE_QUOTE_LENGTH)
        val normalizedClaimKey = candidate.claimKey
            ?.trim()
            ?.takeIf { claimKey -> claimKey.isNotBlank() }
            ?: buildGeneralizedClaimKey(
                scope = candidate.scope,
                domain = normalizedDomain,
                subject = claimKeySubject,
                predicate = normalizedPredicate,
            )
        val normalizedKeywords = (candidate.keywords + listOfNotNull(
            normalizedDomain.name,
            displaySubject,
            normalizedPredicate,
            normalizedValue,
        ))
            .flatMap(::splitKeyword)
            .map { keyword -> keyword.lowercase() }
            .filter { keyword -> keyword.length >= MINIMUM_KEYWORD_LENGTH }
            .distinct()
            .take(MAXIMUM_KEYWORDS)

        return candidate.copy(
            domain = normalizedDomain,
            subject = displaySubject,
            predicate = normalizedPredicate,
            value = normalizedValue,
            sourceQuote = normalizedSourceQuote,
            sensitivity = maxSensitivity(candidate.sensitivity, detectSensitivity(candidate)),
            keywords = normalizedKeywords,
            claimKey = normalizedClaimKey,
            durabilityScore = candidate.durabilityScore.coerceIn(0.0, 1.0),
            futureUsefulnessScore = candidate.futureUsefulnessScore.coerceIn(0.0, 1.0),
            confidence = candidate.confidence.coerceIn(0.0, 1.0),
        )
    }

    private fun buildGeneralizedClaimKey(
        scope: MemoryScope,
        domain: MemoryClaimDomain,
        subject: String?,
        predicate: String?,
    ): String? {
        val scopePart = when (scope) {
            MemoryScope.GLOBAL -> "user"
            MemoryScope.PROJECT -> "project"
            MemoryScope.THREAD -> "thread"
        }
        val normalizedParts = listOfNotNull(
            scopePart,
            normalizeClaimKeyPart(domain.name),
            subject ?: defaultSubject(domain),
            predicate,
        ).mapNotNull(::normalizeClaimKeyPart)
        return normalizedParts.joinToString(".").takeIf { claimKey -> claimKey.isNotBlank() }
    }

    private fun defaultSubject(domain: MemoryClaimDomain): String? =
        when (domain) {
            MemoryClaimDomain.IDENTITY -> "self"
            MemoryClaimDomain.PREFERENCE -> "general"
            MemoryClaimDomain.ROUTINE -> "user"
            MemoryClaimDomain.GIST -> "note"
            else -> null
        }

    private fun detectSensitivity(candidate: MemoryClaimCandidate): MemorySensitivity {
        val searchableText = listOfNotNull(
            candidate.text,
            candidate.value,
            candidate.sourceQuote,
            candidate.predicate,
        ).joinToString(" ").lowercase()
        return when {
            highSensitivityPatterns.any { pattern -> pattern.containsMatchIn(searchableText) } ->
                MemorySensitivity.HIGH
            mediumSensitivityPatterns.any { pattern -> pattern.containsMatchIn(searchableText) } ->
                MemorySensitivity.MEDIUM
            else -> MemorySensitivity.LOW
        }
    }

    private fun maxSensitivity(
        declaredSensitivity: MemorySensitivity,
        detectedSensitivity: MemorySensitivity,
    ): MemorySensitivity =
        if (declaredSensitivity.ordinal >= detectedSensitivity.ordinal) {
            declaredSensitivity
        } else {
            detectedSensitivity
        }

    private fun normalizePredicate(predicate: String?): String? =
        normalizeClaimKeyPart(predicate)?.take(MAXIMUM_PREDICATE_LENGTH)

    private fun normalizeClaimKeyPart(part: String?): String? {
        val normalizedPart = part.orEmpty()
            .split(nonWordPattern)
            .map { token -> token.lowercase().trim() }
            .filter { token -> token.length >= MINIMUM_KEYWORD_LENGTH }
            .joinToString("_")
        return normalizedPart.takeIf { claimKeyPart -> claimKeyPart.isNotBlank() }
    }

    private fun splitKeyword(keyword: String): List<String> =
        keyword.split(nonWordPattern).filter { token -> token.isNotBlank() }

    private companion object {
        const val MINIMUM_KEYWORD_LENGTH = 3
        const val MAXIMUM_KEYWORDS = 20
        const val MAXIMUM_PREDICATE_LENGTH = 60
        const val MAXIMUM_SOURCE_QUOTE_LENGTH = 500

        val nonWordPattern = Regex("[^a-zA-Z0-9']+")
        val highSensitivityPatterns = listOf(
            Regex("\\b(password|passcode|api\\s*key|secret|token|door\\s+code|security\\s+code)\\b"),
            Regex("\\b(ssn|social\\s+security|credit\\s+card|bank\\s+account|routing\\s+number)\\b"),
            Regex("\\b(address|phone\\s+number|email)\\b"),
            Regex("\\b(diagnosed|diagnosis|medication|medical|health\\s+condition)\\b"),
        )
        val mediumSensitivityPatterns = listOf(
            Regex("\\b(birthday|location|salary|income|debt|family|partner|spouse|child)\\b"),
        )
    }
}
