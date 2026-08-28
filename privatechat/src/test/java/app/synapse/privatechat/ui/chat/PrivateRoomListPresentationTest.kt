package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivateMessagePreview
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateMessageText
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PrivateRoomListPresentationTest {
    @Test
    fun `search matches normalized titles senders and confirmed message previews`() {
        val rooms =
            listOf(
                room(title = "Café Friends", id = "title-match"),
                room(
                    title = "Weekend",
                    id = "sender-match",
                    previewSender = "Álvaro",
                    previewBody = "See you soon",
                ),
                room(
                    title = "Plans",
                    id = "body-match",
                    previewSender = "Friend",
                    previewBody = "Secret meeting place",
                ),
            )

        assertEquals(
            listOf("title-match"),
            filterPrivateRooms(rooms, "CAFÉ", unreadOnly = false, archivedOnly = false)
                .map { room -> room.roomId.canonical },
        )
        assertEquals(
            listOf("sender-match"),
            filterPrivateRooms(rooms, "álvaro", unreadOnly = false, archivedOnly = false)
                .map { room -> room.roomId.canonical },
        )
        assertEquals(
            listOf("body-match"),
            filterPrivateRooms(rooms, "meeting", unreadOnly = false, archivedOnly = false)
                .map { room -> room.roomId.canonical },
        )
    }

    @Test
    fun `chat filters hide archived rooms by default and keep pinned rooms first`() {
        val rooms =
            listOf(
                room(title = "Regular", id = "regular"),
                room(title = "Pinned", id = "pinned", pinState = PrivateRoomPinState.PINNED),
                room(
                    title = "Archived unread",
                    id = "archived",
                    archiveState = PrivateRoomArchiveState.ARCHIVED,
                    unreadCount = 2,
                ),
            )

        assertEquals(
            listOf("pinned", "regular"),
            filterPrivateRooms(rooms, "", unreadOnly = false, archivedOnly = false)
                .map { room -> room.roomId.canonical },
        )
        assertEquals(
            listOf("archived"),
            filterPrivateRooms(rooms, "", unreadOnly = false, archivedOnly = true)
                .map { room -> room.roomId.canonical },
        )
        assertEquals(
            listOf("archived"),
            filterPrivateRooms(rooms, "", unreadOnly = true, archivedOnly = true)
                .map { room -> room.roomId.canonical },
        )
    }

    private fun room(
        title: String,
        id: String,
        previewSender: String? = null,
        previewBody: String? = null,
        archiveState: PrivateRoomArchiveState = PrivateRoomArchiveState.ACTIVE,
        pinState: PrivateRoomPinState = PrivateRoomPinState.UNPINNED,
        unreadCount: Int = 0,
    ): PrivateRoomSummary =
        PrivateRoomSummary(
            roomId = PrivateRoomId(id),
            kind = PrivateRoomKind.DIRECT,
            title = title,
            participantCount = 2,
            retention = PrivateMessageRetention.ONE_DAY,
            archiveState = archiveState,
            pinState = pinState,
            muteState = PrivateRoomMuteState.AUDIBLE,
            unreadMessageCount = unreadCount,
            latestMessagePreview =
                previewSender?.let { sender ->
                    PrivateMessagePreview(
                        senderDisplayName = sender,
                        body = PrivateMessageText(requireNotNull(previewBody)),
                        expiresAt = Instant.parse("2026-08-28T12:00:00Z"),
                    )
                },
        )
}
