package app.synapse.localllm

import app.synapse.localllm.domain.notifications.NotificationPermissionState
import app.synapse.localllm.domain.notifications.notificationPermissionState
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SynapseFirebaseMessagingServiceTest {
    @Test
    fun api29RemoteNotificationIsEligibleWithoutRuntimePermission() {
        val permissionState = notificationPermissionState(
            androidApiLevel = 29,
            runtimePermissionGranted = false,
        )

        assertEquals(NotificationPermissionState.NOT_REQUIRED, permissionState)
        assertEquals(true, permissionState.allowsNotifications)
    }

    @Test
    fun notificationPayloadAcceptsTrustedMessageShapeWithoutPlaintextBody() {
        val roomId = "direct_${"a".repeat(64)}"

        val payload = parseRemoteNotificationPayload(
            mapOf(
                "messageId" to "message-1",
                "roomId" to roomId,
                "senderUid" to "peter-uid",
                "type" to "SYNAPSE_CHAT_MESSAGE",
                "unreadCount" to "2",
            ),
        )

        assertEquals(roomId, payload?.roomId?.raw)
        assertEquals("message-1", payload?.messageId?.raw)
        assertEquals("peter-uid", payload?.senderUid?.raw)
        assertEquals(2, payload?.unreadCount)
    }

    @Test
    fun notificationPayloadAcceptsServerShapedGroupRoom() {
        val roomId = "group_${"b".repeat(32)}"

        val payload = parseRemoteNotificationPayload(
            mapOf(
                "messageId" to "message-2",
                "roomId" to roomId,
                "senderUid" to "trish-uid",
                "type" to "SYNAPSE_CHAT_MESSAGE",
                "unreadCount" to "1",
            ),
        )

        assertEquals(roomId, payload?.roomId?.raw)
        assertEquals(1, payload?.unreadCount)
    }

    @Test
    fun notificationPayloadRejectsMissingOrInvalidUnreadCount() {
        val basePayload = mapOf(
            "messageId" to "message-3",
            "roomId" to "direct_${"c".repeat(64)}",
            "senderUid" to "trish-uid",
            "type" to "SYNAPSE_CHAT_MESSAGE",
        )

        assertNull(parseRemoteNotificationPayload(basePayload))
        assertNull(parseRemoteNotificationPayload(basePayload + ("unreadCount" to "0")))
        assertNull(parseRemoteNotificationPayload(basePayload + ("unreadCount" to "1000")))
        assertEquals(999, parseRemoteNotificationPayload(basePayload + ("unreadCount" to "999"))?.unreadCount)
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
                    "unreadCount" to "1",
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
                    "unreadCount" to "1",
                ),
            ),
        )
    }

    @Test
    fun directCallNotificationAcceptsPrivateRoutingDataAndRejectsMalformedCalls() {
        val callId = "call_${"a".repeat(32)}"
        val payload = parseDirectCallNotificationPayload(
            mapOf(
                "callId" to callId,
                "event" to "INCOMING",
                "expiresAtMillis" to "12345",
                "mediaKind" to "VIDEO",
                "type" to "SYNAPSE_DIRECT_CALL",
            ),
        )

        assertEquals(callId, payload?.callId?.raw)
        assertEquals(DirectCallNotificationEvent.INCOMING, payload?.event)
        assertEquals(RemoteDirectCallMediaKind.VIDEO, payload?.mediaKind)
        assertEquals(
            RemoteDirectCallMediaKind.AUDIO,
            parseDirectCallNotificationPayload(
                mapOf(
                    "callId" to callId,
                    "event" to "INCOMING",
                    "expiresAtMillis" to "12345",
                    "type" to "SYNAPSE_DIRECT_CALL",
                ),
            )?.mediaKind,
        )
        assertNull(
            parseDirectCallNotificationPayload(
                mapOf(
                    "callId" to "untrusted-call",
                    "event" to "INCOMING",
                    "expiresAtMillis" to "12345",
                    "type" to "SYNAPSE_DIRECT_CALL",
                ),
            ),
        )
        assertNull(
            parseDirectCallNotificationPayload(
                mapOf(
                    "callId" to callId,
                    "event" to "INCOMING",
                    "expiresAtMillis" to "12345",
                    "mediaKind" to "SCREEN",
                    "type" to "SYNAPSE_DIRECT_CALL",
                ),
            ),
        )
    }
}
