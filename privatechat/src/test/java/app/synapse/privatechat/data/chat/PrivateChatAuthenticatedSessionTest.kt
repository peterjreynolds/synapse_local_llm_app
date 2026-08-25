package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PrivateChatAuthenticatedSessionTest {
    @Test
    fun exposesOnlyExplicitRequestTokenAndRedactsDiagnosticText() {
        val session = authenticatedSession()

        assertTrue(session.isUsableAt(NOW))
        assertFalse(session.isUsableAt(EXPIRES_AT))
        assertTrue(session.toString().contains("[REDACTED]"))
        assertFalse(session.toString().contains(ACCESS_TOKEN))
        assertTrue(session.accessTokenForRequest() == ACCESS_TOKEN)
    }

    @Test
    fun rejectsMalformedLocalUsernameAndBearerTokenBeforeTransport() {
        assertThrows(IllegalArgumentException::class.java) {
            authenticatedSession(authenticationUsername = "Display Name")
        }
        assertThrows(IllegalArgumentException::class.java) {
            authenticatedSession(accessToken = "not-a-jwt")
        }
    }

    private fun authenticatedSession(
        authenticationUsername: String = "peter_01",
        accessToken: String = ACCESS_TOKEN,
    ): PrivateChatAuthenticatedSession =
        PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
            accountId = UUID.fromString("10000000-0000-4000-8000-000000000001"),
            transportDeviceId = UUID.fromString("20000000-0000-4000-8000-000000000002"),
            signalDeviceId = SignalDeviceId.fromWire(7),
            authenticationUsername = authenticationUsername,
            accessToken = accessToken,
            expiresAt = EXPIRES_AT,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-25T12:00:00Z")
        val EXPIRES_AT: Instant = Instant.parse("2026-08-25T13:00:00Z")
        const val ACCESS_TOKEN = "header.payload.signature-material"
    }
}
