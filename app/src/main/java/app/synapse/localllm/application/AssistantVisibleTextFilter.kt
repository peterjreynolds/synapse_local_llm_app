package app.synapse.localllm.application

class AssistantVisibleTextFilter {
    private var rawAssistantText = ""
    private var emittedVisibleText = ""

    val visibleCharacterCount: Int
        get() = emittedVisibleText.length

    val filteredCharacterCount: Int
        get() = (rawAssistantText.length - emittedVisibleText.length).coerceAtLeast(0)

    fun appendToken(token: String): AssistantVisibleTextFilterResult {
        rawAssistantText += token
        val nextVisibleText = sanitizeAssistantText(rawAssistantText)
        val visibleDelta = when {
            nextVisibleText == emittedVisibleText -> ""
            nextVisibleText.startsWith(emittedVisibleText) ->
                nextVisibleText.removePrefix(emittedVisibleText)
            else -> nextVisibleText
        }
        emittedVisibleText = nextVisibleText
        return AssistantVisibleTextFilterResult(
            visibleDelta = visibleDelta,
            shouldStopGeneration = containsGeneratedRoleBoundary(rawAssistantText),
        )
    }

    private fun sanitizeAssistantText(rawText: String): String {
        val withoutThinkBlocks = removeThinkBlocks(rawText)
        val withoutLeadingAssistantLabel = LEADING_ASSISTANT_LABEL.replace(withoutThinkBlocks, "")
        val withoutDiagnosticPrelude = removeDiagnosticPrelude(withoutLeadingAssistantLabel)
        val roleBoundary = GENERATED_STOP_BOUNDARY.find(withoutDiagnosticPrelude)
        val boundedText = if (roleBoundary == null) {
            withoutDiagnosticPrelude
        } else {
            withoutDiagnosticPrelude.take(roleBoundary.range.first)
        }
        return boundedText.trimStart()
    }

    private fun removeThinkBlocks(rawText: String): String {
        val cleanedText = StringBuilder()
        var cursor = 0
        while (cursor < rawText.length) {
            val thinkStart = THINK_START.find(rawText, cursor)
            if (thinkStart == null) {
                cleanedText.append(rawText.substring(cursor))
                break
            }
            cleanedText.append(rawText.substring(cursor, thinkStart.range.first))
            val thinkEnd = THINK_END.find(rawText, thinkStart.range.last + 1)
            if (thinkEnd == null) {
                break
            }
            cursor = thinkEnd.range.last + 1
        }
        return cleanedText.toString()
    }

    private fun removeDiagnosticPrelude(text: String): String {
        val diagnosticMatch = DIAGNOSTIC_PRELUDE.find(text) ?: return text
        return if (diagnosticMatch.range.first == 0) {
            ""
        } else {
            text.take(diagnosticMatch.range.first)
        }
    }

    private fun containsGeneratedRoleBoundary(rawText: String): Boolean {
        val visibleCandidate = removeThinkBlocks(rawText)
        val textWithoutLeadingAssistant = LEADING_ASSISTANT_LABEL.replace(visibleCandidate, "")
        return GENERATED_STOP_BOUNDARY.containsMatchIn(textWithoutLeadingAssistant)
    }

    private companion object {
        val THINK_START = Regex("<think>", setOf(RegexOption.IGNORE_CASE))
        val THINK_END = Regex("</think>", setOf(RegexOption.IGNORE_CASE))
        val LEADING_ASSISTANT_LABEL = Regex("^\\s*Assistant\\s*:\\s*", setOf(RegexOption.IGNORE_CASE))
        val GENERATED_STOP_BOUNDARY = Regex(
            "(<\\|im_end\\|>|(^|\\n)\\s*(User|Assistant|System)\\s*:)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        val DIAGNOSTIC_PRELUDE = Regex(
            "(?m)^\\s*(Thinking Process|Final Decision|Self-Correction|Review against constraints)\\s*:",
            setOf(RegexOption.IGNORE_CASE),
        )
    }
}

data class AssistantVisibleTextFilterResult(
    val visibleDelta: String,
    val shouldStopGeneration: Boolean,
)
