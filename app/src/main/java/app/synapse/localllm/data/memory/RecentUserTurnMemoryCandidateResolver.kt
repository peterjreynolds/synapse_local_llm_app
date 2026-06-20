package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.memory.ContextualMemoryCandidateResolver
import app.synapse.localllm.domain.memory.MemoryCandidateProposer
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryProjector
import app.synapse.localllm.domain.memory.MemoryWriteIntent
import app.synapse.localllm.domain.memory.TraceEventRecord

class RecentUserTurnMemoryCandidateResolver(
    private val memoryProjector: MemoryProjector,
    private val memoryCandidateProposer: MemoryCandidateProposer,
) : ContextualMemoryCandidateResolver {
    override fun resolveContextualMemoryCandidates(
        traceEvent: TraceEventRecord,
        priorMessages: List<ChatMessageRecord>,
    ): List<MemoryClaimCandidate> {
        if (traceEvent.role != ConversationRole.USER) return emptyList()
        val currentText = traceEvent.text.trim().replace(whitespacePattern, " ")
        if (!contextualSaveRequestPattern.containsMatchIn(currentText)) return emptyList()

        val recentCorrections = priorMessages
            .asReversed()
            .mapNotNull { message -> extractRecentCorrection(message) }
            .take(MAXIMUM_RECENT_CORRECTIONS)
            .toList()
        val sourceMessage = priorMessages
            .asReversed()
            .firstOrNull(::isContextualMemorySource)
            ?: return emptyList()
        val sourceText = applyRecentCorrections(
            sourceText = sourceMessage.body.trim().replace(whitespacePattern, " "),
            corrections = recentCorrections,
        )
        if (sourceText.length < MINIMUM_CONTEXTUAL_SOURCE_LENGTH) return emptyList()

        val sourceTrace = traceEvent.copy(text = sourceText)
        val projectedCandidates = memoryProjector.extractMemoryCandidates(sourceTrace)
        val proposedCandidates = if (projectedCandidates.isEmpty()) {
            memoryCandidateProposer.proposeMemoryCandidates(sourceTrace)
        } else {
            emptyList()
        }

        return (projectedCandidates + proposedCandidates)
            .map { candidate ->
                candidate.copy(
                    writeIntent = MemoryWriteIntent.EXPLICIT_SAVE,
                    confidence = maxOf(candidate.confidence, CONTEXTUAL_EXPLICIT_CONFIDENCE),
                    durabilityScore = maxOf(candidate.durabilityScore, CONTEXTUAL_EXPLICIT_SCORE),
                    futureUsefulnessScore = maxOf(candidate.futureUsefulnessScore, CONTEXTUAL_EXPLICIT_SCORE),
                    reasonCodes = (candidate.reasonCodes + "contextual-memory-source-previous-user-turn").distinct(),
                    sourceQuote = sourceText.take(MAXIMUM_SOURCE_QUOTE_LENGTH),
                )
            }
    }

    private fun isContextualMemorySource(message: ChatMessageRecord): Boolean {
        if (message.role != ConversationRole.USER) return false
        if (message.deliveryState != MessageDeliveryState.COMPLETE) return false
        val normalizedText = message.body.trim().replace(whitespacePattern, " ")
        if (normalizedText.length < MINIMUM_CONTEXTUAL_SOURCE_LENGTH) return false
        if (contextualSaveRequestPattern.containsMatchIn(normalizedText)) return false
        if (memoryReviewQuestionPattern.containsMatchIn(normalizedText)) return false
        if (correctionOnlyPattern.containsMatchIn(normalizedText)) return false
        return true
    }

    private fun extractRecentCorrection(message: ChatMessageRecord): TextCorrection? {
        if (message.role != ConversationRole.USER) return null
        if (message.deliveryState != MessageDeliveryState.COMPLETE) return null
        val normalizedText = message.body.trim().replace(whitespacePattern, " ")
        val match = correctionOnlyPattern.find(normalizedText) ?: return null
        val replacement = match.groupValues[1].trim('*', ' ', '.', ',', '!', '?', ':', ';')
        val mistaken = match.groupValues[2].trim('*', ' ', '.', ',', '!', '?', ':', ';')
        if (replacement.length < MINIMUM_CORRECTION_TERM_LENGTH) return null
        if (mistaken.length < MINIMUM_CORRECTION_TERM_LENGTH) return null
        return TextCorrection(mistaken = mistaken, replacement = replacement)
    }

    private fun applyRecentCorrections(
        sourceText: String,
        corrections: List<TextCorrection>,
    ): String =
        corrections.fold(sourceText) { revisedText, correction ->
            revisedText.replace(
                oldValue = correction.mistaken,
                newValue = correction.replacement,
                ignoreCase = true,
            )
        }

    private data class TextCorrection(
        val mistaken: String,
        val replacement: String,
    )

    private companion object {
        const val MINIMUM_CONTEXTUAL_SOURCE_LENGTH = 14
        const val MINIMUM_CORRECTION_TERM_LENGTH = 2
        const val MAXIMUM_RECENT_CORRECTIONS = 3
        const val MAXIMUM_SOURCE_QUOTE_LENGTH = 500
        const val CONTEXTUAL_EXPLICIT_CONFIDENCE = 0.95
        const val CONTEXTUAL_EXPLICIT_SCORE = 1.0

        val whitespacePattern = Regex("\\s+")
        val contextualSaveRequestPattern = Regex(
            "\\b(?:add|save|remember|keep)\\b.{0,80}\\b(?:that|this|it|what\\s+it\\s+is)\\b.{0,80}\\b(?:memory|remember|saved)?\\b",
            RegexOption.IGNORE_CASE,
        )
        val correctionOnlyPattern = Regex(
            "^\\s*([a-zA-Z][a-zA-Z0-9_.-]{1,60})\\W{0,8}\\s+not\\s+([a-zA-Z][a-zA-Z0-9_.-]{1,60})\\W*\\s*$",
            RegexOption.IGNORE_CASE,
        )
        val memoryReviewQuestionPattern = Regex(
            "\\b(?:what\\s+do\\s+you\\s+remember|what\\s+are\\s+my\\s+memories|what\\s+have\\s+you\\s+saved)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
