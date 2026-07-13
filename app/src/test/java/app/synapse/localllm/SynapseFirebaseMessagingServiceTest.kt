package app.synapse.localllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SynapseFirebaseMessagingServiceTest {
    @Test
    fun notificationPayloadAcceptsTrustedMessageShapeWithoutPlaintextBody() {
        val roomId = "direct_${"a".repeat(64)}"

        val payload = parseRemoteNotificationPayload(
            mapOf(
                "messageId" to "message-1",
                "roomId" to roomId,
                "senderUid" to "peter-uid",
                "type" to "SYNAPSE_CHAT_MESSAGE",
            ),
        )

        assertEquals(roomId, payload?.roomId?.raw)
        assertEquals("message-1", payload?.messageId?.raw)
        assertEquals("peter-uid", payload?.senderUid?.raw)
    }

    @Test
    fun notificationPayloadRejectsWrongTypeOrInvalidRoom() {
        assertNull(
            parseRemoteNotificationPayload(
                mapOf(
                    "messageId" to "message-1",
                    "roomId" to "not-a-room",
                    "senderUid" to "peter-uid",
                    "type" to "SYNAPSE_CHAT_MESSAGE",
                ),
            ),
        )
        assertNull(
            parseRemoteNotificationPayload(
                mapOf(
                    "messageId" to "message-1",
                    "roomId" to "direct_${"b".repeat(64)}",
                    "senderUid" to "peter-uid",
                    "type" to "UNTRUSTED_TYPE",
                ),
            ),
        )
    }
}
