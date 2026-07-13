package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCachedDirectRoom
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteNotificationRoomAuthorizationTest {
    @Test
    fun pendingNotificationRoomRequiresAccountScopedCachedMembership() {
        val allowedRoom = room("direct_${"c".repeat(64)}")
        val unknownRoomId = RemoteRoomId("direct_${"d".repeat(64)}")

        assertEquals(
            allowedRoom.roomId,
            resolveAuthorizedNotificationRoom(allowedRoom.roomId, listOf(allowedRoom)),
        )
        assertNull(resolveAuthorizedNotificationRoom(unknownRoomId, listOf(allowedRoom)))
        assertNull(resolveAuthorizedNotificationRoom(null, listOf(allowedRoom)))
    }

    private fun room(rawRoomId: String) = RemoteCachedDirectRoom(
        accountUid = RemoteAccountUid("account-uid"),
        roomId = RemoteRoomId(rawRoomId),
        directKey = "account-uid:peer-uid",
        peerUid = RemoteProfileUid("peer-uid"),
        title = "Peer",
        unreadCount = 1,
        latestMessagePreview = null,
        latestMessageSenderUid = null,
        remoteUpdatedAt = Instant.parse("2026-07-13T08:00:00Z"),
    )
}
