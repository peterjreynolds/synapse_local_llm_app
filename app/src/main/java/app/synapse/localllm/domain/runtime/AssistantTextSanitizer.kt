package app.synapse.localllm.domain.runtime

data class SanitizedAssistantText(
    val visibleText: String,
    val shouldStopGeneration: Boolean,
)

object AssistantTextSanitizer {
    fun sanitizeForDisplay(rawText: String): SanitizedAssistantText {
        val withoutThinkBlocks = removeThinkBlocks(rawText)
        val withoutLeadingAssistantLabel = LEADING_ASSISTANT_LABEL.replace(withoutThinkBlocks, "")
        val withoutDiagnosticPrelude = removeDiagnosticPrelude(withoutLeadingAssistantLabel)
        val boundary = STOP_BOUNDARY.find(withoutDiagnosticPrelude)
        val boundedText = if (boundary == null) {
            withoutDiagnosticPrelude
        } else {
            withoutDiagnosticPrelude.take(boundary.range.first)
        }
        return SanitizedAssistantText(
            visibleText = boundedText.trimStart(),
            shouldStopGeneration = containsStopBoundary(rawText),
        )
    }

    fun sanitizeForPromptHistory(rawText: String): String? {
        val displayText = sanitizeForDisplay(rawText).visibleText
        val promptBoundary = PROMPT_SCAFFOLDING_BOUNDARY.find(displayText)
        val boundedText = if (promptBoundary == null) {
            displayText
        } else {
            displayText.take(promptBoundary.range.first)
        }
        return boundedText.trim().takeIf { text -> text.isNotBlank() }
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
                val unclosedThinkText = rawText.substring(thinkStart.range.last + 1)
                val finalAnswerMatch = FINAL_ANSWER_LABEL.find(unclosedThinkText)
                if (finalAnswerMatch != null) {
                    cleanedText.append(unclosedThinkText.substring(finalAnswerMatch.range.last + 1))
                }
                break
            }
            cursor = thinkEnd.range.last + 1
        }
        return cleanedText.toString()
    }

    private fun removeDiagnosticPrelude(text: String): String {
        val finalAnswerMatch = FINAL_ANSWER_LABEL.find(text)
        if (finalAnswerMatch != null) {
            return text.substring(finalAnswerMatch.range.last + 1)
        }
        val diagnosticMatch = DIAGNOSTIC_PRELUDE.find(text) ?: return text
        return if (diagnosticMatch.range.first == 0) {
            ""
        } else {
            text.take(diagnosticMatch.range.first)
        }
    }

    private fun containsStopBoundary(rawText: String): Boolean {
        val visibleCandidate = removeThinkBlocks(rawText)
        val textWithoutLeadingAssistant = LEADING_ASSISTANT_LABEL.replace(visibleCandidate, "")
        return STOP_BOUNDARY.containsMatchIn(textWithoutLeadingAssistant)
    }

    private val THINK_START = Regex("<think>", setOf(RegexOption.IGNORE_CASE))
    private val THINK_END = Regex("</think>", setOf(RegexOption.IGNORE_CASE))
    private val LEADING_ASSISTANT_LABEL = Regex("^\\s*Assistant\\s*:\\s*", setOf(RegexOption.IGNORE_CASE))
    private val STOP_BOUNDARY = Regex(
        pattern = buildString {
            append("(<\\|im_end\\|>|<\\|eot_id\\|>|<\\|endoftext\\|>|</s>|")
            append("(^|\\n)\\s*(User|Assistant|System)\\s*:)")
        },
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val DIAGNOSTIC_PRELUDE = Regex(
        "(?m)^\\s*(Thinking Process|Self-Correction|Review against constraints)\\s*:",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val FINAL_ANSWER_LABEL = Regex(
        "(?m)^\\s*(Final Answer|Final Decision|Answer)\\s*:\\s*",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val PROMPT_SCAFFOLDING_BOUNDARY = Regex(
        "(?m)^\\s*(Core behavior|Persona|Custom instructions|Verified local memory)\\s*:",
        setOf(RegexOption.IGNORE_CASE),
    )
}
