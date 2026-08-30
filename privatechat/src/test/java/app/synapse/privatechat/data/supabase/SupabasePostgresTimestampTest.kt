package app.synapse.privatechat.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeParseException

class SupabasePostgresTimestampTest {
    @Test
    fun normalizesPostgresUtcOffsetsWithoutLosingFractionalPrecision() {
        val expected = Instant.parse("2026-08-30T06:48:02.261968Z")

        assertEquals(expected, parseSupabasePostgresInstant("2026-08-30T06:48:02.261968+00:00"))
        assertEquals(expected, parseSupabasePostgresInstant("2026-08-30T06:48:02.261968+00"))
        assertEquals(expected, parseSupabasePostgresInstant("2026-08-30T06:48:02.261968Z"))
    }

    @Test
    fun rejectsNonUtcOrNonCanonicalTimestamps() {
        listOf(
            "2026-08-30T06:48:02.261968-04:00",
            "2026-08-30T06:48:02.261968-00:00",
            "2026-08-30 06:48:02.261968+00:00",
            "2026-08-30T06:48:02.261968z",
            "2026-08-30T06:48:02.1234567890Z",
        ).forEach { malformedTimestamp ->
            assertThrows(DateTimeParseException::class.java) {
                parseSupabasePostgresInstant(malformedTimestamp)
            }
        }
    }
}
