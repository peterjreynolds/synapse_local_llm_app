package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.memory.MemoryCommand
import app.synapse.localllm.domain.memory.MemoryCommandInterpreter
import app.synapse.localllm.domain.memory.TraceEventRecord

class PatternMemoryCommandInterpreter : MemoryCommandInterpreter {
    override fun interpretMemoryCommand(traceEvent: TraceEventRecord): MemoryCommand {
        if (traceEvent.role != ConversationRole.USER) return MemoryCommand.ContinueExtraction

        val normalizedText = traceEvent.text.trim().replace(Regex("\\s+"), " ")
        if (normalizedText.isBlank()) return MemoryCommand.ContinueExtraction
        if (saveLikePatterns.any { pattern -> pattern.containsMatchIn(normalizedText) }) {
            return MemoryCommand.ContinueExtraction
        }

        val tombstoneQuery = tombstonePatterns
            .asSequence()
            .mapNotNull { pattern -> pattern.find(normalizedText) }
            .mapNotNull { match -> extractTombstoneQuery(match.groupValues.lastOrNull(), normalizedText) }
            .firstOrNull()
            ?: return MemoryCommand.ContinueExtraction

        return MemoryCommand.TombstoneMatchingMemories(
            query = tombstoneQuery,
            reason = "User requested memory deletion: $tombstoneQuery",
        )
    }

    private fun extractTombstoneQuery(
        rawQuery: String?,
        fallbackText: String,
    ): String? {
        val query = rawQuery.orEmpty()
            .replace(prefixNoisePattern, " ")
            .trim()
            .trimEnd('.', '!', '?', ',', ';', ':')
            .trim()
            .takeIf { candidate -> candidate.length >= MINIMUM_QUERY_LENGTH }
            ?: fallbackText
                .replace(deleteVerbPattern, " ")
                .replace(prefixNoisePattern, " ")
                .trim()
                .trimEnd('.', '!', '?', ',', ';', ':')
                .trim()
                .takeIf { candidate -> candidate.length >= MINIMUM_QUERY_LENGTH }
        return query
    }

    private companion object {
        const val MINIMUM_QUERY_LENGTH = 3

        val saveLikePatterns = listOf(
            Regex("\\b(?:don't|do\\s+not)\\s+forget\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:remember|save|keep\\s+in\\s+memory)\\b", RegexOption.IGNORE_CASE),
        )
        val tombstonePatterns = listOf(
            Regex(
                "\\b(?:forget|delete|remove)\\b\\s+(?:from\\s+memory\\s+)?(.{0,180})",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "\\b(?:delete|remove)\\s+(?:that\\s+)?memor(?:y|ies)\\b\\s*(?:about|of|for|that)?\\s*(.{0,180})",
                RegexOption.IGNORE_CASE,
            ),
        )
        val deleteVerbPattern = Regex("\\b(?:forget|delete|remove)\\b", RegexOption.IGNORE_CASE)
        val prefixNoisePattern =
            Regex("\\b(?:my|the|that|this|a|an|memory|memories|about|of|for|that)\\b", RegexOption.IGNORE_CASE)
    }
}
