package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryProjector
import app.synapse.localllm.domain.memory.MemoryScope
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.memory.TraceEventRecord

class DeterministicMemoryProjector : MemoryProjector {
    override fun extractMemoryCandidates(traceEvent: TraceEventRecord): List<MemoryClaimCandidate> {
        if (traceEvent.role != ConversationRole.USER) return emptyList()

        val normalizedText = traceEvent.text.trim().replace(Regex("\\s+"), " ")
        if (normalizedText.length < MINIMUM_MEMORY_TEXT_LENGTH) return emptyList()

        if (forgetPattern.containsMatchIn(normalizedText)) return emptyList()

        val explicitMemoryClaim = extractExplicitMemoryClaim(normalizedText)
        val textForClassification = explicitMemoryClaim ?: normalizedText
        val explicitReasonCodes = if (explicitMemoryClaim != null) {
            listOf("explicit-user-memory-command")
        } else {
            emptyList()
        }

        val specificCandidates = listOfNotNull(
            extractFavoriteCandidate(textForClassification, traceEvent, explicitReasonCodes),
            extractIdentityCandidate(textForClassification, traceEvent, explicitReasonCodes),
            extractAppointmentCandidate(textForClassification, traceEvent, explicitReasonCodes),
            extractProjectCandidate(textForClassification, traceEvent, explicitReasonCodes),
            extractRelationshipCandidate(textForClassification, traceEvent, explicitReasonCodes),
            extractPreferenceCandidate(textForClassification, traceEvent, explicitReasonCodes),
            extractCommitmentCandidate(textForClassification, traceEvent, explicitReasonCodes),
            extractProcedureCandidate(textForClassification, traceEvent, explicitReasonCodes),
        )
        val candidates = specificCandidates.ifEmpty {
            listOfNotNull(
                extractGenericExplicitMemoryCandidate(explicitMemoryClaim, traceEvent, explicitReasonCodes),
            )
        }

        return candidates.distinctBy { candidate ->
            listOf(
                candidate.kind.name,
                candidate.scope.name,
                candidate.subject.orEmpty().lowercase(),
                candidate.text.lowercase(),
            ).joinToString("|")
        }
    }

    private fun extractFavoriteCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate? {
        val match = favoritePattern.find(normalizedText) ?: return null
        val category = cleanMemorySegment(match.groupValues[1])
        val favorite = cleanMemorySegment(match.groupValues[2])
        if (category.isBlank() || favorite.isBlank()) return null

        return buildCandidate(
            kind = MemoryKind.PREFERENCE,
            text = "User's favorite $category is $favorite.",
            traceEvent = traceEvent,
            reasonCodes = reasonCodes + "explicit-user-favorite",
            keywords = listOf("favorite", category, favorite),
            claimKey = buildClaimKey("user", "favorite", category),
        )
    }

    private fun extractIdentityCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate? {
        val match = identityPattern.find(normalizedText) ?: return null
        val identityField = cleanMemorySegment(match.groupValues[1].lowercase())
        val identityValue = cleanMemorySegment(match.groupValues[2])
        if (identityField.isBlank() || identityValue.isBlank()) return null

        return buildCandidate(
            kind = MemoryKind.IDENTITY,
            text = "User's $identityField is $identityValue.",
            traceEvent = traceEvent,
            reasonCodes = reasonCodes + "explicit-user-identity",
            subject = "User",
            keywords = listOf("identity", "name", identityField, identityValue),
            claimKey = buildClaimKey("user", identityField),
        )
    }

    private fun extractAppointmentCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate? {
        val match = appointmentPattern.find(normalizedText) ?: return null
        val appointmentText = cleanMemorySegment(normalizedText)
        if (appointmentText.isBlank()) return null

        return buildCandidate(
            kind = MemoryKind.APPOINTMENT,
            text = appointmentText.ensureSentence(),
            traceEvent = traceEvent,
            reasonCodes = reasonCodes + "explicit-user-appointment",
            keywords = extractKeywords(appointmentText) + listOf("appointment", "schedule"),
            claimKey = buildClaimKey("appointment", extractKeywords(appointmentText).take(4)),
        )
    }

    private fun extractProjectCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate? {
        if (preferencePattern.find(normalizedText)?.range?.first == 0) return null
        val directProjectMatch = directProjectPattern.find(normalizedText)
        val workingProjectMatch = workingProjectPattern.find(normalizedText)
        val projectSubject = when {
            directProjectMatch != null -> cleanProjectSubject(directProjectMatch.groupValues[1])
            workingProjectMatch != null -> cleanProjectSubject(workingProjectMatch.groupValues[1])
            else -> null
        } ?: return null

        val projectText = cleanMemorySegment(normalizedText)
        return buildCandidate(
            kind = MemoryKind.PROJECT,
            text = projectText.ensureSentence(),
            traceEvent = traceEvent,
            reasonCodes = reasonCodes + "explicit-user-project-context",
            scope = MemoryScope.PROJECT,
            subject = projectSubject,
            keywords = extractKeywords(projectText) + listOf(projectSubject, "project"),
            claimKey = buildClaimKey("project", projectSubject, "context"),
        )
    }

    private fun extractRelationshipCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate? {
        val match = relationshipPattern.find(normalizedText) ?: return null
        val relationship = cleanMemorySegment(match.groupValues[1].lowercase())
        val relationshipValue = cleanMemorySegment(match.groupValues[2])
        if (relationship.isBlank() || relationshipValue.isBlank()) return null
        if (relationship in relationshipFieldExclusions) return null

        return buildCandidate(
            kind = MemoryKind.RELATIONSHIP,
            text = "User's $relationship is $relationshipValue.",
            traceEvent = traceEvent,
            reasonCodes = reasonCodes + "explicit-user-relationship",
            subject = relationship,
            keywords = listOf("relationship", relationship, relationshipValue),
            claimKey = buildClaimKey("user", "relationship", relationship),
        )
    }

    private fun extractPreferenceCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate? {
        val match = preferencePattern.find(normalizedText) ?: return null
        val preferenceVerb = match.groupValues[1].lowercase()
        val preferenceText = cleanMemorySegment(match.groupValues[2])
        if (preferenceText.isBlank()) return null

        return buildCandidate(
            kind = MemoryKind.PREFERENCE,
            text = buildPreferenceText(preferenceVerb, preferenceText),
            traceEvent = traceEvent,
            reasonCodes = reasonCodes + "explicit-user-preference",
            keywords = extractKeywords(preferenceText) + preferenceVerb,
            claimKey = buildClaimKey(listOf("user", "preference") + extractKeywords(preferenceText).take(3)),
        )
    }

    private fun extractCommitmentCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate? {
        val match = commitmentPattern.find(normalizedText) ?: return null
        return buildCandidate(
            kind = MemoryKind.COMMITMENT,
            text = cleanMemorySegment(match.value).ensureSentence(),
            traceEvent = traceEvent,
            reasonCodes = reasonCodes + "explicit-user-commitment",
            keywords = extractKeywords(match.value),
            claimKey = buildClaimKey("commitment", extractKeywords(match.value).take(4)),
        )
    }

    private fun extractProcedureCandidate(
        normalizedText: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate? {
        val match = procedurePattern.find(normalizedText) ?: return null
        return buildCandidate(
            kind = MemoryKind.PROCEDURE,
            text = cleanMemorySegment(match.value).ensureSentence(),
            traceEvent = traceEvent,
            reasonCodes = reasonCodes + "explicit-user-boundary-or-procedure",
            keywords = extractKeywords(match.value),
            claimKey = buildClaimKey(listOf("user", "procedure") + extractKeywords(match.value).take(3)),
        )
    }

    private fun extractGenericExplicitMemoryCandidate(
        explicitMemoryClaim: String?,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
    ): MemoryClaimCandidate? {
        val memoryClaim = explicitMemoryClaim?.let(::cleanMemorySegment) ?: return null
        if (memoryClaim.isBlank()) return null
        return buildCandidate(
            kind = MemoryKind.GIST,
            text = memoryClaim.ensureSentence(),
            traceEvent = traceEvent,
            reasonCodes = reasonCodes + "explicit-user-general-memory",
            keywords = extractKeywords(memoryClaim),
        )
    }

    private fun buildCandidate(
        kind: MemoryKind,
        text: String,
        traceEvent: TraceEventRecord,
        reasonCodes: List<String>,
        scope: MemoryScope = MemoryScope.GLOBAL,
        subject: String? = null,
        keywords: List<String> = emptyList(),
        claimKey: String? = null,
    ): MemoryClaimCandidate =
        MemoryClaimCandidate(
            kind = kind,
            text = text.take(MAXIMUM_MEMORY_TEXT_LENGTH),
            confidence = DEFAULT_EXPLICIT_CONFIDENCE,
            sourceTraceEventIds = listOf(traceEvent.id),
            surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
            reasonCodes = reasonCodes.distinct(),
            scope = scope,
            subject = subject?.trim()?.takeIf { normalizedSubject -> normalizedSubject.isNotBlank() },
            keywords = keywords
                .flatMap(::splitKeyword)
                .map { keyword -> keyword.lowercase() }
                .filter { keyword -> keyword.length >= MINIMUM_KEYWORD_LENGTH }
                .distinct()
                .take(MAXIMUM_KEYWORDS),
            claimKey = claimKey,
        )

    private fun extractExplicitMemoryClaim(normalizedText: String): String? {
        val match = explicitMemoryPattern.find(normalizedText) ?: return null
        return cleanMemorySegment(match.groupValues[1]).takeIf { memoryClaim ->
            memoryClaim.isNotBlank() && !forgetPattern.containsMatchIn(memoryClaim)
        }
    }

    private fun cleanMemorySegment(rawText: String): String =
        rawText
            .trim()
            .trimEnd('.', '!', '?', ',', ';', ':')
            .trim()

    private fun cleanProjectSubject(rawProjectSubject: String): String? {
        val subject = cleanMemorySegment(rawProjectSubject)
            .replace(Regex("\\b(repo|project|app|website|workspace)\\b", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd('.', ',', ';', ':')
            .trim()
        return subject.takeIf { normalizedSubject -> normalizedSubject.length >= MINIMUM_KEYWORD_LENGTH }
    }

    private fun buildPreferenceText(
        preferenceVerb: String,
        preferenceText: String,
    ): String =
        when (preferenceVerb) {
            "like" -> "User likes $preferenceText."
            "love" -> "User loves $preferenceText."
            "prefer" -> "User prefers $preferenceText."
            "hate", "dislike" -> "User dislikes $preferenceText."
            "want" -> "User wants $preferenceText."
            "need" -> "User needs $preferenceText."
            else -> "User $preferenceVerb $preferenceText."
        }

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

    private fun buildClaimKey(prefix: String, parts: List<String>): String? =
        buildClaimKey(listOf(prefix) + parts)

    private fun buildClaimKey(parts: List<String?>): String? {
        val normalizedParts = parts
            .mapNotNull(::normalizeClaimKeyPart)
            .take(MAXIMUM_CLAIM_KEY_PARTS)
        return normalizedParts
            .joinToString(".")
            .takeIf { claimKey -> claimKey.isNotBlank() }
    }

    private fun normalizeClaimKeyPart(part: String?): String? {
        val normalizedPart = part.orEmpty()
            .split(nonWordPattern)
            .map { token -> token.lowercase().trim() }
            .filter { token -> token.length >= MINIMUM_KEYWORD_LENGTH }
            .joinToString("_")
        return normalizedPart.takeIf { claimKeyPart -> claimKeyPart.isNotBlank() }
    }

    private companion object {
        const val MINIMUM_MEMORY_TEXT_LENGTH = 8
        const val MINIMUM_KEYWORD_LENGTH = 3
        const val MAXIMUM_MEMORY_TEXT_LENGTH = 280
        const val MAXIMUM_KEYWORDS = 16
        const val DEFAULT_EXPLICIT_CONFIDENCE = 0.95
        const val MAXIMUM_CLAIM_KEY_PARTS = 6

        val sentenceTerminators = setOf('.', '!', '?')
        val nonWordPattern = Regex("[^a-zA-Z0-9']+")

        val explicitMemoryPattern =
            Regex(
                pattern = "\\b(?:remember|save|keep\\s+in\\s+memory|don't\\s+forget)\\s+" +
                    "(?:that\\s+)?(.{3,220})",
                option = RegexOption.IGNORE_CASE,
            )
        val forgetPattern =
            Regex(
                pattern = "\\b(forget|remove\\s+from\\s+memory|delete\\s+(?:that\\s+)?memory|" +
                    "do\\s+not\\s+save|don't\\s+save|do\\s+not\\s+remember|don't\\s+remember|" +
                    "never\\s+save|never\\s+remember)\\b",
                option = RegexOption.IGNORE_CASE,
            )
        val favoritePattern =
            Regex(
                pattern = "\\bmy\\s+favou?rite\\s+([a-z0-9][a-z0-9' -]{0,48})\\s+is\\s+(.{1,120})",
                option = RegexOption.IGNORE_CASE,
            )
        val identityPattern =
            Regex(
                pattern = "\\bmy\\s+" +
                    "(full\\s+name|first\\s+name|middle\\s+name|last\\s+name|preferred\\s+name|" +
                    "nickname|birthday|email|phone\\s+number|address|location|job\\s+title|role)" +
                    "\\s+is\\s+(.{1,120})",
                option = RegexOption.IGNORE_CASE,
            )
        val appointmentPattern =
            Regex(
                pattern = "\\b(?:(?:I|we)\\s+(?:have|have\\s+got|got)|my|our)?\\s*" +
                    "(appointment|meeting|call|deadline|due\\s+date)\\b.{2,180}",
                option = RegexOption.IGNORE_CASE,
            )
        val directProjectPattern =
            Regex(
                pattern = "\\b(?i:project|repo|app|website|workspace)\\s+" +
                    "([a-zA-Z0-9][a-zA-Z0-9_.-]*(?:\\s+[A-Z][a-zA-Z0-9_.-]*){0,3})\\b",
            )
        val workingProjectPattern =
            Regex(
                pattern = "\\b(?:we(?:'re|\\s+are)?|I(?:'m|\\s+am)?)\\s+" +
                    "(?:currently\\s+)?(?:working\\s+on|building|planning|researching)\\s+" +
                    "(?:the\\s+)?([a-zA-Z0-9][a-zA-Z0-9 _.-]{1,60}?)" +
                    "(?:\\s+(?:project|repo|app|website|workspace))?\\b",
                option = RegexOption.IGNORE_CASE,
            )
        val relationshipPattern =
            Regex(
                pattern = "\\bmy\\s+([a-z][a-z -]{1,40})\\s+(?:name\\s+)?is\\s+(.{1,120})",
                option = RegexOption.IGNORE_CASE,
            )
        val preferencePattern =
            Regex(
                pattern = "\\bI\\s+(like|love|prefer|hate|dislike|want|need)\\b\\s+(.{2,140})",
                option = RegexOption.IGNORE_CASE,
            )
        val commitmentPattern =
            Regex(
                pattern = "\\b(remind me|remember to|don't forget|we need to|I need to)\\b.{2,140}",
                option = RegexOption.IGNORE_CASE,
            )
        val procedurePattern =
            Regex(
                pattern = "\\b(always|never)\\s+" +
                    "(?:use|save|remember|respond|reply|call|write|format|include|exclude|do)\\b.{2,140}",
                option = RegexOption.IGNORE_CASE,
            )
        val relationshipFieldExclusions = setOf(
            "favorite food",
            "favorite color",
            "full name",
            "first name",
            "middle name",
            "last name",
            "preferred name",
            "nickname",
            "birthday",
            "email",
            "phone number",
            "address",
            "location",
            "job title",
            "role",
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
            "our",
            "are",
            "have",
            "has",
            "remember",
            "save",
        )
    }
}
