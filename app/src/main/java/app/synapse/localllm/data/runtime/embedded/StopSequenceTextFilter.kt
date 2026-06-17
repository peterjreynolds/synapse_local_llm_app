package app.synapse.localllm.data.runtime.embedded

internal class StopSequenceTextFilter(stopSequences: List<String>) {
    private val normalizedStopSequences = stopSequences
        .map { sequence -> sequence.trimEnd() }
        .filter { sequence -> sequence.isNotBlank() }
        .distinct()
    private val pendingText = StringBuilder()
    private var stopped = false

    fun append(text: String): StopSequenceFilterResult {
        if (stopped || text.isEmpty()) {
            return StopSequenceFilterResult(visibleText = "", shouldStop = stopped)
        }

        pendingText.append(text)
        val bufferedText = pendingText.toString()
        val stopIndex = normalizedStopSequences
            .mapNotNull { sequence ->
                bufferedText.indexOf(sequence)
                    .takeIf { index -> index >= 0 }
            }
            .minOrNull()

        if (stopIndex != null) {
            val visibleText = bufferedText.take(stopIndex)
            pendingText.clear()
            stopped = true
            return StopSequenceFilterResult(visibleText = visibleText, shouldStop = true)
        }

        val retainedSuffixLength = longestPotentialStopSuffixLength(bufferedText)
        val emitLength = (bufferedText.length - retainedSuffixLength).coerceAtLeast(0)
        if (emitLength == 0) {
            return StopSequenceFilterResult(visibleText = "", shouldStop = false)
        }

        val visibleText = bufferedText.take(emitLength)
        pendingText.delete(0, emitLength)
        return StopSequenceFilterResult(visibleText = visibleText, shouldStop = false)
    }

    fun flush(): String {
        if (stopped) return ""
        val visibleText = pendingText.toString()
        pendingText.clear()
        return visibleText
    }

    private fun longestPotentialStopSuffixLength(bufferedText: String): Int {
        if (normalizedStopSequences.isEmpty()) return 0
        val maximumSuffixLength = normalizedStopSequences.maxOf { sequence -> sequence.length } - 1
        return (1..maximumSuffixLength.coerceAtMost(bufferedText.length))
            .lastOrNull { length ->
                val suffix = bufferedText.takeLast(length)
                normalizedStopSequences.any { sequence -> sequence.startsWith(suffix) }
            }
            ?: 0
    }
}

internal data class StopSequenceFilterResult(
    val visibleText: String,
    val shouldStop: Boolean,
)
