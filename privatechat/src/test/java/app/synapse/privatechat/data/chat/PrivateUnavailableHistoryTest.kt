package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalProtocolException
import app.synapse.privatechat.crypto.SignalProtocolFailureKind
import app.synapse.privatechat.crypto.local.DeviceLocalContentEnvelopeKeyErasedException
import app.synapse.privatechat.data.diagnostics.PrivateConnectionDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class PrivateUnavailableHistoryTest {
    @Test
    fun consumedCiphertextWithoutCacheIsCountedButHealthyPayloadsContinue() {
        val unavailable = PrivateUnavailableHistory(PrivateConnectionDiagnostics())
        val oldPayload =
            unavailable.decodeAvailable<String>(ROOM) {
                throw SignalProtocolException(SignalProtocolFailureKind.REPLAY_DETECTED, "Previously consumed")
            }
        assertNull(oldPayload)
        assertEquals("new message", unavailable.decodeAvailable(ROOM) { "new message" })
        assertEquals(mapOf(ROOM to 1), unavailable.countsByRoom())
    }

    @Test
    fun erasedDeviceLocalKeysAreUnavailableWithoutInventingPlaintext() {
        val unavailable = PrivateUnavailableHistory(PrivateConnectionDiagnostics())
        assertNull(unavailable.decodeAvailable<String>(ROOM) { throw DeviceLocalContentEnvelopeKeyErasedException() })
        assertEquals(mapOf(ROOM to 1), unavailable.countsByRoom())
    }

    @Test
    fun identityTamperingAndVaultFailuresStillFailClosed() {
        for (kind in SignalProtocolFailureKind.entries.filter { it != SignalProtocolFailureKind.REPLAY_DETECTED }) {
            val unavailable = PrivateUnavailableHistory(PrivateConnectionDiagnostics())
            val rejection = SignalProtocolException(kind, "Rejected")
            assertSame(
                rejection,
                assertThrows(SignalProtocolException::class.java) {
                    unavailable.decodeAvailable<String>(ROOM) { throw rejection }
                },
            )
            assertEquals(emptyMap<UUID, Int>(), unavailable.countsByRoom())
        }
        val unavailable = PrivateUnavailableHistory(PrivateConnectionDiagnostics())
        assertThrows(SupabasePrivateChatResponseException::class.java) {
            unavailable.decodeAvailable<String>(ROOM) { throw SupabasePrivateChatResponseException("Context mismatch") }
        }
    }

    private companion object {
        val ROOM: UUID = UUID.fromString("30000000-0000-4000-8000-000000000003")
    }
}
