package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
import app.synapse.localllm.domain.remote.RemoteDirectCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseRemoteDirectCallGatewayTest {
    @Test
    fun callReceiptParserNarrowsServerResponse() {
        val session = mapOf(
            "callId" to "call_${"a".repeat(32)}",
            "calleeUid" to "trish-uid",
            "callerUid" to "peter-uid",
            "expiresAtMillis" to 12_345L,
            "mediaKind" to "VIDEO",
            "roomId" to "direct_${"b".repeat(64)}",
            "state" to "RINGING",
        ).toDirectCallSessionReceipt()

        assertEquals("peter-uid", session.callerUid.raw)
        assertEquals("trish-uid", session.calleeUid.raw)
        assertEquals(RemoteDirectCallState.RINGING, session.state)
        assertEquals(RemoteDirectCallMediaKind.VIDEO, session.mediaKind)
        assertEquals(12_345L, session.expiresAtMillis)
    }

    @Test
    fun callReceiptParserDefaultsOlderCallsToAudio() {
        val session = mapOf(
            "callId" to "call_${"a".repeat(32)}",
            "calleeUid" to "trish-uid",
            "callerUid" to "peter-uid",
            "expiresAtMillis" to 12_345L,
            "roomId" to "direct_${"b".repeat(64)}",
            "state" to "ACTIVE",
        ).toDirectCallSessionReceipt()

        assertEquals(RemoteDirectCallMediaKind.AUDIO, session.mediaKind)
    }

    @Test
    fun callReceiptParserAcceptsCallerCancellationAsTerminal() {
        val session = mapOf(
            "callId" to "call_${"a".repeat(32)}",
            "calleeUid" to "trish-uid",
            "callerUid" to "peter-uid",
            "expiresAtMillis" to 12_345L,
            "roomId" to "direct_${"b".repeat(64)}",
            "state" to "CANCELED",
        ).toDirectCallSessionReceipt()

        assertEquals(RemoteDirectCallState.CANCELED, session.state)
    }

    @Test
    fun callReceiptParserFailsClosedForMalformedIdentifiersAndStates() {
        val base = mapOf(
            "callId" to "call_${"a".repeat(32)}",
            "calleeUid" to "trish-uid",
            "callerUid" to "peter-uid",
            "expiresAtMillis" to 12_345L,
            "roomId" to "direct_${"b".repeat(64)}",
            "state" to "ACTIVE",
        )

        assertThrows(RemoteChatException::class.java) {
            (base + ("callId" to "not-a-call")).toDirectCallSessionReceipt()
        }
        assertThrows(RemoteChatException::class.java) {
            (base + ("state" to "UNKNOWN")).toDirectCallSessionReceipt()
        }
        assertThrows(RemoteChatException::class.java) {
            (base + ("mediaKind" to "SCREEN")).toDirectCallSessionReceipt()
        }
    }
}
