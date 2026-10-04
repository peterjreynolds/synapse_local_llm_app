package app.synapse.privatechat.data.diagnostics

import java.time.Clock

internal enum class PrivateDiagnosticOperation {
    HTTP,
    SESSION,
    OBSERVE,
    MUTATE,
}

/** Process-local bounded evidence. Never accepts payloads, identifiers, or exception messages. */
internal class PrivateConnectionDiagnostics(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val events = ArrayDeque<String>()

    @Synchronized
    fun record(
        operation: PrivateDiagnosticOperation,
        statusCode: Int? = null,
        failure: Throwable? = null,
    ) {
        val locations =
            failure
                ?.stackTrace
                ?.take(6)
                ?.joinToString(" <- ") { frame ->
                    "${frame.className.safeSymbol()}.${frame.methodName.safeSymbol()}:${frame.lineNumber}"
                }.orEmpty()
        val category = failure?.javaClass?.name?.safeSymbol() ?: "NONE"
        val status = statusCode?.takeIf { it in 100..599 }?.toString() ?: "NONE"
        events.addLast("${clock.instant()} operation=$operation http=$status failure=$category at=$locations")
        while (events.size > MAXIMUM_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun exportReport(
        versionName: String,
        versionCode: Int,
        androidApi: Int,
    ): String =
        buildString {
            appendLine("Synapse Private connection diagnostics v1")
            appendLine("version=${versionName.safeSymbol()} code=$versionCode android_api=$androidApi")
            appendLine("exported_at=${clock.instant()}")
            appendLine("Last $MAXIMUM_EVENTS events from this app process; cleared when the process exits.")
            appendLine("No message content, credentials, account IDs, request bodies, URLs, or exception messages.")
            events.forEach { appendLine(it) }
        }

    private companion object {
        const val MAXIMUM_EVENTS = 200
    }
}

private fun String.safeSymbol(): String = take(180).replace(Regex("[^A-Za-z0-9_.$<>-]"), "_")
