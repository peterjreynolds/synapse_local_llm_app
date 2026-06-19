package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.memory.MemoryCandidateProposer
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryClaimDomain
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryScope
import app.synapse.localllm.domain.memory.MemorySensitivity
import app.synapse.localllm.domain.memory.MemoryWriteIntent
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.memory.TraceEventRecord

class RuleBasedMemoryCandidateProposer : MemoryCandidateProposer {
    override fun proposeMemoryCandidates(traceEvent: TraceEventRecord): List<MemoryClaimCandidate> {
        if (traceEvent.role != ConversationRole.USER) return emptyList()
        val normalizedText = traceEvent.text.trim().replace(Regex("\\s+"), " ")
        if (normalizedText.length < MINIMUM_IMPLICIT_TEXT_LENGTH) return emptyList()
        if (vaguePraisePattern.matches(normalizedText)) return emptyList()

        return listOfNotNull(
            proposeProjectPriorityCandidate(normalizedText, traceEvent),
            proposeRoutineCandidate(normalizedText, traceEvent),
        ).distinctBy { candidate ->
            listOf(candidate.domain.name, candidate.subject.orEmpty(), candidate.predicate.orEmpty(), candidate.value.orEmpty())
                .joinToString("|")
                .lowercase()
        }
    }

    private fun proposeProjectPriorityCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
    ): MemoryClaimCandidate? {
        val match = projectLeadPattern.find(normalizedText) ?: return null
        val projectSubject = cleanMemorySegment(match.groupValues[1])
        val projectClaim = cleanMemorySegment(match.groupValues[2])
        if (projectSubject.isBlank() || projectClaim.isBlank()) return null
        val predicate = when {
            priorityPattern.containsMatchIn(projectClaim) -> "priority"
            goalPattern.containsMatchIn(projectClaim) -> "goal"
            blockerPattern.containsMatchIn(projectClaim) -> "blocker"
            else -> return null
        }

        return buildImplicitCandidate(
            kind = MemoryKind.PROJECT,
            domain = MemoryClaimDomain.PROJECT,
            scope = MemoryScope.PROJECT,
            subject = projectSubject,
            predicate = predicate,
            value = projectClaim,
            text = "For $projectSubject, $projectClaim.",
            traceEvent = traceEvent,
            reasonCodes = listOf("implicit-project-$predicate"),
            keywords = extractKeywords(projectSubject) + extractKeywords(projectClaim) + listOf("project", predicate),
            claimKey = buildClaimKey("project", "project", projectSubject, predicate),
            confidence = 0.88,
            durabilityScore = 0.9,
            futureUsefulnessScore = 0.92,
        )
    }

    private fun proposeRoutineCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
    ): MemoryClaimCandidate? {
        val match = routinePattern.find(normalizedText) ?: return null
        val routineValue = cleanMemorySegment(match.groupValues[1])
        if (routineValue.isBlank()) return null

        return buildImplicitCandidate(
            kind = MemoryKind.PROCEDURE,
            domain = MemoryClaimDomain.ROUTINE,
            scope = MemoryScope.GLOBAL,
            subject = "user",
            predicate = "default",
            value = routineValue,
            text = "User usually $routineValue.",
            traceEvent = traceEvent,
            reasonCodes = listOf("implicit-user-routine"),
            keywords = extractKeywords(routineValue) + listOf("routine", "usually"),
            claimKey = buildClaimKey(listOf("user", "routine") + extractKeywords(routineValue).take(3) + "default"),
            confidence = 0.82,
            durabilityScore = 0.75,
            futureUsefulnessScore = 0.74,
        )
    }

    private fun buildImplicitCandidate(
        kind: MemoryKind,
        domain: MemoryClaimDomain,
        scope: MemoryScope,
        subject: String,
        predicate: String,
        value: String,
        text: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
        keywords: List<String>,
        claimKey: String?,
        confidence: Double,
        durabilityScore: Double,
        futureUsefulnessScore: Double,
    ): MemoryClaimCandidate =
        MemoryClaimCandidate(
            kind = kind,
            text = text.ensureSentence().take(MAXIMUM_MEMORY_TEXT_LENGTH),
            confidence = confidence,
            sourceTraceEventIds = listOf(traceEvent.id),
            surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
            reasonCodes = reasonCodes,
            scope = scope,
            domain = domain,
            subject = subject,
            predicate = predicate,
            value = value,
            sourceQuote = traceEvent.text.trim().take(MAXIMUM_SOURCE_QUOTE_LENGTH),
            writeIntent = MemoryWriteIntent.IMPLICIT_CANDIDATE,
            durabilityScore = durabilityScore,
            futureUsefulnessScore = futureUsefulnessScore,
            sensitivity = detectSensitivity(value),
            keywords = keywords
                .flatMap(::splitKeyword)
                .map { keyword -> keyword.lowercase() }
                .filter { keyword -> keyword.length >= MINIMUM_KEYWORD_LENGTH }
                .distinct()
                .take(MAXIMUM_KEYWORDS),
            claimKey = claimKey,
        )

    private fun cleanMemorySegment(rawText: String): String =
        rawText.trim().trimEnd('.', '!', '?', ',', ';', ':').trim()

    private fun String.ensureSentence(): String =
        if (lastOrNull() in sentenceTerminators) this else "$this."

    private fun extractKeywords(text: String): List<String> =
        text.split(nonWordPattern)
            .asSequence()
            .map { token -> token.trim() }
            .filter { token -> token.length >= MINIMUM_KEYWORD_LENGTH }
            .filterNot { token -> token.lowercase() in keywordStopWords }
            .take(MAXIMUM_KEYWORDS)
            .toList()

    private fun splitKeyword(keyword: String): List<String> =
        keyword.split(nonWordPattern).filter { token -> token.isNotBlank() }

    private fun buildClaimKey(vararg parts: String?): String? =
        buildClaimKey(parts.toList())

    private fun buildClaimKey(parts: List<String?>): String? {
        val normalizedParts = parts
            .mapNotNull(::normalizeClaimKeyPart)
            .take(MAXIMUM_CLAIM_KEY_PARTS)
        return normalizedParts.joinToString(".").takeIf { claimKey -> claimKey.isNotBlank() }
    }

    private fun normalizeClaimKeyPart(part: String?): String? {
        val normalizedPart = part.orEmpty()
            .split(nonWordPattern)
            .map { token -> token.lowercase().trim() }
            .filter { token -> token.length >= MINIMUM_KEYWORD_LENGTH }
            .joinToString("_")
        return normalizedPart.takeIf { claimKeyPart -> claimKeyPart.isNotBlank() }
    }

    private fun detectSensitivity(value: String): MemorySensitivity =
        if (highSensitivityPatterns.any { pattern -> pattern.containsMatchIn(value.lowercase()) }) {
            MemorySensitivity.HIGH
        } else {
            MemorySensitivity.LOW
        }

    private companion object {
        const val MINIMUM_IMPLICIT_TEXT_LENGTH = 14
        const val MINIMUM_KEYWORD_LENGTH = 3
        const val MAXIMUM_MEMORY_TEXT_LENGTH = 280
        const val MAXIMUM_SOURCE_QUOTE_LENGTH = 500
        const val MAXIMUM_KEYWORDS = 16
        const val MAXIMUM_CLAIM_KEY_PARTS = 6

        val sentenceTerminators = setOf('.', '!', '?')
        val nonWordPattern = Regex("[^a-zA-Z0-9']+")
        val projectLeadPattern = Regex(
            pattern = "^\\s*for\\s+([a-zA-Z0-9][a-zA-Z0-9 _.-]{1,60}),\\s+(.{3,180})$",
            option = RegexOption.IGNORE_CASE,
        )
        val priorityPattern = Regex("\\b(priority|main\\s+priority|focus)\\b", RegexOption.IGNORE_CASE)
        val goalPattern = Regex("\\b(goal|objective)\\b", RegexOption.IGNORE_CASE)
        val blockerPattern = Regex("\\b(blocker|blocked|risk)\\b", RegexOption.IGNORE_CASE)
        val routinePattern = Regex(
            pattern = "\\bI\\s+(?:usually|typically|normally)\\s+(.{3,150})",
            option = RegexOption.IGNORE_CASE,
        )
        val vaguePraisePattern = Regex(
            pattern = "\\s*(?:that|this|it)?\\s*(?:was|is)?\\s*(?:sick|cool|nice|wild|great|awesome)\\s*[.!?]*\\s*",
            option = RegexOption.IGNORE_CASE,
        )
        val highSensitivityPatterns = listOf(
            Regex("\\b(password|passcode|api\\s*key|secret|token|door\\s+code|security\\s+code)\\b"),
            Regex("\\b(address|phone\\s+number|email|ssn|credit\\s+card|bank\\s+account)\\b"),
        )
        val keywordStopWords = setOf(
            "the",
            "and",
            "for",
            "that",
            "this",
            "with",
            "you",
            "your",
            "main",
        )
    }
}
