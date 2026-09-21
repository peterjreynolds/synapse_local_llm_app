package app.synapse.privatechat.data.call.media

import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCallForegroundRegistrationTest {
    @Test fun `stale notification cannot terminate a different call`() {
        var hangUps = 0
        PrivateCallForegroundRegistration.register("current", PrivateCallMediaKind.VOICE) { hangUps += 1 }
        try {
            PrivateCallForegroundRegistration.requestHangUp("previous")
            assertEquals(0, hangUps)
            assertNotNull(PrivateCallForegroundRegistration.forCall("current"))
            assertFalse(PrivateCallForegroundRegistration.clear("previous"))
        } finally {
            PrivateCallForegroundRegistration.clear("current")
        }
    }

    @Test fun `notification hang up invokes owner exactly once`() {
        var hangUps = 0
        val startReceipt = PrivateCallForegroundRegistration.register("current", PrivateCallMediaKind.VOICE) { hangUps += 1 }
        PrivateCallForegroundRegistration.requestHangUp("current")
        PrivateCallForegroundRegistration.requestHangUp("current")
        assertEquals(1, hangUps)
        assertTrue(startReceipt.isCancelled)
        assertNotNull(PrivateCallForegroundRegistration.forCall("current"))
        assertTrue(PrivateCallForegroundRegistration.clear("current"))
    }

    @Test fun `intentional stop unregisters owner before service destruction`() {
        var hangUps = 0
        PrivateCallForegroundRegistration.register("current", PrivateCallMediaKind.VOICE) { hangUps += 1 }
        assertTrue(PrivateCallForegroundRegistration.clear("current"))
        PrivateCallForegroundRegistration.requestHangUp("current")
        assertEquals(0, hangUps)
    }

    @Test fun `second foreground registration cannot replace a live call`() {
        PrivateCallForegroundRegistration.register("current", PrivateCallMediaKind.VOICE) { }
        try {
            assertThrows(IllegalStateException::class.java) {
                PrivateCallForegroundRegistration.register("other", PrivateCallMediaKind.VOICE) { }
            }
        } finally {
            PrivateCallForegroundRegistration.clear("current")
        }
    }
}
