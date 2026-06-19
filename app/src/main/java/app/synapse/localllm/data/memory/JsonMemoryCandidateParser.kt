package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.memory.MemoryCandidateParseReceipt
import app.synapse.localllm.domain.memory.MemoryCandidateProposalParser
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryClaimDomain
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryScope
import app.synapse.localllm.domain.memory.MemorySensitivity
import app.synapse.localllm.domain.memory.MemoryWriteIntent
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.memory.TraceEventRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

class JsonMemoryCandidateParser : MemoryCandidateProposalParser {
    private val json = Json {
        ignoreUnknownKeys = false
    }

    override fun parseCandidateProposal(
        rawJson: String,
        traceEvent: TraceEventRecord,
    ): MemoryCandidateParseReceipt {
        if (traceEvent.role != ConversationRole.USER) {
            return MemoryCandidateParseReceipt(
                candidates = emptyList(),
                rejectionReason = "Assistant-origin text cannot propose memory.",
            )
        }

        val decodedCandidates = try {
            decodeCandidateEnvelope(rawJson)
        } catch (exception: IllegalArgumentException) {
            return rejected(exception.message ?: "Unsupported memory candidate JSON.")
        } catch (exception: SerializationException) {
            return rejected("Invalid memory candidate JSON: ${exception.message.orEmpty()}")
        }

        val candidates = mutableListOf<MemoryClaimCandidate>()
        decodedCandidates.forEachIndexed { index, jsonCandidate ->
            val candidate = jsonCandidate.toDomainCandidate(traceEvent)
                ?: return rejected("Candidate $index failed schema validation.")
            if (!traceEvent.text.contains(candidate.sourceQuote.orEmpty())) {
                return rejected("Candidate $index source_quote is not an exact user-text span.")
            }
            candidates += candidate
        }

        return MemoryCandidateParseReceipt(
            candidates = candidates,
            rejectionReason = null,
        )
    }

    private fun decodeCandidateEnvelope(rawJson: String): List<JsonMemoryCandidate> {
        val root = json.parseToJsonElement(rawJson)
        return when (root) {
            is JsonArray -> json.decodeFromJsonElement<List<JsonMemoryCandidate>>(root)
            else -> {
                val envelope = root.jsonObject["candidates"]
                    ?: throw IllegalArgumentException("Memory candidate JSON must contain candidates.")
                json.decodeFromJsonElement(envelope)
            }
        }
    }

    private fun JsonMemoryCandidate.toDomainCandidate(traceEvent: TraceEventRecord): MemoryClaimCandidate? {
        val domain = parseDomain(domain) ?: return null
        val scope = parseScope(scope) ?: return null
        val writeIntent = parseWriteIntent(writeIntent) ?: return null
        val sensitivity = parseSensitivity(sensitivity) ?: return null
        val normalizedSubject = subject.trim().takeIf { value -> value.isNotBlank() } ?: return null
        val normalizedPredicate = predicate.trim().takeIf { value -> value.isNotBlank() } ?: return null
        val normalizedValue = value.trim().takeIf { value -> value.isNotBlank() } ?: return null
        val normalizedSourceQuote = sourceQuote.trim().takeIf { value -> value.isNotBlank() } ?: return null
        val candidateText = buildCandidateText(
            domain = domain,
            subject = normalizedSubject,
            predicate = normalizedPredicate,
            value = normalizedValue,
        )
        return MemoryClaimCandidate(
            kind = domain.toMemoryKind(),
            text = candidateText,
            confidence = confidence.coerceIn(0.0, 1.0),
            sourceTraceEventIds = listOf(traceEvent.id),
            surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
            reasonCodes = (reasonCodes + "json-candidate-proposer").distinct(),
            scope = scope,
            domain = domain,
            subject = normalizedSubject,
            predicate = normalizedPredicate,
            value = normalizedValue,
            sourceQuote = normalizedSourceQuote,
            writeIntent = writeIntent,
            durabilityScore = durabilityScore.coerceIn(0.0, 1.0),
            futureUsefulnessScore = futureUsefulnessScore.coerceIn(0.0, 1.0),
            sensitivity = sensitivity,
            keywords = keywords.distinct().take(MAXIMUM_KEYWORDS),
        )
    }

    private fun buildCandidateText(
        domain: MemoryClaimDomain,
        subject: String,
        predicate: String,
        value: String,
    ): String {
        val readablePredicate = predicate.replace('_', ' ')
        val text = when (domain) {
            MemoryClaimDomain.IDENTITY -> "User's $readablePredicate is $value"
            MemoryClaimDomain.PROJECT -> "For $subject, $value"
            MemoryClaimDomain.PREFERENCE -> "User preference for $subject: $value"
            MemoryClaimDomain.ROUTINE -> "User routine for $subject: $value"
            else -> "$subject $readablePredicate: $value"
        }
        return text.ensureSentence()
    }

    private fun parseScope(rawScope: String): MemoryScope? =
        when (rawScope.trim().uppercase()) {
            "USER", "GLOBAL" -> MemoryScope.GLOBAL
            "PROJECT" -> MemoryScope.PROJECT
            "THREAD" -> MemoryScope.THREAD
            else -> null
        }

    private fun parseDomain(rawDomain: String): MemoryClaimDomain? =
        MemoryClaimDomain.entries.firstOrNull { domain ->
            domain.name == rawDomain.trim().uppercase()
        }

    private fun parseWriteIntent(rawWriteIntent: String): MemoryWriteIntent? =
        MemoryWriteIntent.entries.firstOrNull { intent ->
            intent.name == rawWriteIntent.trim().uppercase()
        }

    private fun parseSensitivity(rawSensitivity: String): MemorySensitivity? =
        MemorySensitivity.entries.firstOrNull { sensitivity ->
            sensitivity.name == rawSensitivity.trim().uppercase()
        }

    private fun MemoryClaimDomain.toMemoryKind(): MemoryKind =
        when (this) {
            MemoryClaimDomain.IDENTITY -> MemoryKind.IDENTITY
            MemoryClaimDomain.PREFERENCE -> MemoryKind.PREFERENCE
            MemoryClaimDomain.RELATIONSHIP -> MemoryKind.RELATIONSHIP
            MemoryClaimDomain.PROJECT -> MemoryKind.PROJECT
            MemoryClaimDomain.TASK -> MemoryKind.COMMITMENT
            MemoryClaimDomain.APPOINTMENT -> MemoryKind.APPOINTMENT
            MemoryClaimDomain.ROUTINE -> MemoryKind.PROCEDURE
            MemoryClaimDomain.INSTRUCTION -> MemoryKind.INSTRUCTION
            MemoryClaimDomain.CORRECTION -> MemoryKind.CORRECTION
            MemoryClaimDomain.SUMMARY -> MemoryKind.SUMMARY
            MemoryClaimDomain.ALIAS -> MemoryKind.IDENTITY
            MemoryClaimDomain.CONSTRAINT -> MemoryKind.INSTRUCTION
            MemoryClaimDomain.WORKSPACE -> MemoryKind.PROJECT
            MemoryClaimDomain.GIST -> MemoryKind.GIST
            MemoryClaimDomain.TRACE -> MemoryKind.TRACE
            MemoryClaimDomain.ARCHIVE -> MemoryKind.ARCHIVE
        }

    private fun String.ensureSentence(): String =
        if (lastOrNull() in sentenceTerminators) this else "$this."

    private fun rejected(reason: String): MemoryCandidateParseReceipt =
        MemoryCandidateParseReceipt(
            candidates = emptyList(),
            rejectionReason = reason,
        )

    @Serializable
    private data class JsonMemoryCandidate(
        val scope: String,
        val domain: String,
        val subject: String,
        val predicate: String,
        val value: String,
        @SerialName("source_quote") val sourceQuote: String,
        @SerialName("write_intent") val writeIntent: String = MemoryWriteIntent.IMPLICIT_CANDIDATE.name,
        val confidence: Double = 0.0,
        @SerialName("durability_score") val durabilityScore: Double = 0.0,
        @SerialName("future_usefulness_score") val futureUsefulnessScore: Double = 0.0,
        val sensitivity: String = MemorySensitivity.LOW.name,
        @SerialName("reason_codes") val reasonCodes: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
    )

    private companion object {
        const val MAXIMUM_KEYWORDS = 20

        val sentenceTerminators = setOf('.', '!', '?')
    }
}
