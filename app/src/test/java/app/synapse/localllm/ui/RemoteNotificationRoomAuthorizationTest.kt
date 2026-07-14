package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteNotificationRoomAuthorizationTest {
    @Test
    fun pendingNotificationRoomRequiresAccountScopedCachedMembership() {
        val allowedRoom = room("group_${"c".repeat(32)}")
        val unknownRoomId = RemoteRoomId("group_${"d".repeat(32)}")

        assertEquals(
            allowedRoom.roomId,
            resolveAuthorizedNotificationRoom(allowedRoom.roomId, listOf(allowedRoom)),
        )
        assertNull(resolveAuthorizedNotificationRoom(unknownRoomId, listOf(allowedRoom)))
        assertNull(resolveAuthorizedNotificationRoom(null, listOf(allowedRoom)))
    }

    private fun room(rawRoomId: String) = RemoteCachedRoom(
        accountUid = RemoteAccountUid("account-uid"),
        roomId = RemoteRoomId(rawRoomId),
        kind = RemoteRoomKind.GROUP,
        directKey = null,
        peerUid = null,
        title = "Project group",
        avatarObjectPath = null,
        unreadCount = 1,
        latestMessagePreview = null,
        latestMessageSenderUid = null,
        currentMemberRole = RemoteRoomMemberRole.MEMBER,
        notificationsEnabled = true,
        isMuted = false,
        isArchived = false,
        isPinned = false,
        joinedAt = Instant.parse("2026-07-13T07:00:00Z"),
        lastReadAt = null,
        remoteUpdatedAt = Instant.parse("2026-07-13T08:00:00Z"),
    )
}
