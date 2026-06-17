package app.synapse.localllm.application

import app.synapse.localllm.domain.runtime.AssistantTextSanitizer

class AssistantVisibleTextFilter {
    private var rawAssistantText = ""
    private var emittedVisibleText = ""

    val visibleCharacterCount: Int
        get() = emittedVisibleText.length

    val filteredCharacterCount: Int
        get() = (rawAssistantText.length - emittedVisibleText.length).coerceAtLeast(0)

    fun appendToken(token: String): AssistantVisibleTextFilterResult {
        rawAssistantText += token
        val sanitizedText = AssistantTextSanitizer.sanitizeForDisplay(rawAssistantText)
        val nextVisibleText = sanitizedText.visibleText
        val visibleDelta = when {
            nextVisibleText == emittedVisibleText -> ""
            nextVisibleText.startsWith(emittedVisibleText) ->
                nextVisibleText.removePrefix(emittedVisibleText)
            else -> nextVisibleText
        }
        emittedVisibleText = nextVisibleText
        return AssistantVisibleTextFilterResult(
            visibleDelta = visibleDelta,
            shouldStopGeneration = sanitizedText.shouldStopGeneration,
        )
    }
}

data class AssistantVisibleTextFilterResult(
    val visibleDelta: String,
    val shouldStopGeneration: Boolean,
)
