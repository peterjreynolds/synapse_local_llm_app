package app.synapse.localllm.domain.sms

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAutoReplyContractsTest {
    @Test
    fun parsesNonBlankSenderAddress() {
        assertEquals(
            SmsSenderAddress("+15551234567"),
            parseSmsSenderAddress(" +15551234567 "),
        )
        assertNull(parseSmsSenderAddress("   "))
    }

    @Test
    fun normalizesInboundBodyWithoutNullCharacters() {
        assertEquals("hello there", normalizeInboundSmsBody("\u0000 hello there \u0000"))
    }

    @Test
    fun inboundMessageKeyChangesWithMessageBody() {
        val senderAddress = SmsSenderAddress("+15551234567")
        val receivedAt = Instant.parse("2026-07-02T12:00:00Z")

        val firstKey = buildSmsInboundMessageKey(senderAddress, receivedAt, "First")
        val secondKey = buildSmsInboundMessageKey(senderAddress, receivedAt, "Second")

        assertNotEquals(firstKey, secondKey)
        assertTrue(firstKey.raw.matches(Regex("[0-9a-f]{64}")))
    }
}
