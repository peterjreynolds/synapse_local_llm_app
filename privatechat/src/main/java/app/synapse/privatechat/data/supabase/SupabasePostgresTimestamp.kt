package app.synapse.privatechat.data.supabase

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Parses the UTC timestamp strings returned by Supabase/PostgREST. Android 7's desugared
 * `Instant.parse` rejects PostgreSQL's `+00:00` suffix when a fractional second is present, so the
 * equivalent UTC offset is normalized to `Z` before it reaches the platform parser.
 */
internal fun parseSupabasePostgresInstant(rawTimestamp: String): Instant {
    if (!SUPABASE_POSTGRES_UTC_TIMESTAMP.matches(rawTimestamp)) {
        throw DateTimeParseException("Supabase timestamp is malformed", rawTimestamp, 0)
    }
    val normalizedTimestamp =
        when {
            rawTimestamp.endsWith("+00:00") ->
                rawTimestamp.dropLast(6) + "Z"

            rawTimestamp.endsWith("+00") ->
                rawTimestamp.dropLast(3) + "Z"

            else -> rawTimestamp
        }
    return Instant.parse(normalizedTimestamp)
}

private val SUPABASE_POSTGRES_UTC_TIMESTAMP =
    Regex(
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T" +
            "[0-9]{2}:[0-9]{2}:[0-9]{2}" +
            "(?:[.][0-9]{1,9})?" +
            "(?:Z|[+]00(?::00)?)$",
    )
