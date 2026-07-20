package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMessagePresentationTest {
    @Test
    fun consecutiveMessagesGroupBySenderAndOnlyEndWithAvatar() {
        val messages = listOf(
            message("one", "trish", NOW),
            message("two", "trish", NOW.plusSeconds(30)),
            message("three", "trish", NOW.plusSeconds(60)),
        )

        assertEquals(
            RemoteMessageGroupPosition.START,
            presentation(messages, 0).position,
        )
        assertTrue(presentation(messages, 0).showSenderName)
        assertFalse(presentation(messages, 0).showAvatar)
        assertEquals(RemoteMessageGroupPosition.MIDDLE, presentation(messages, 1).position)
        assertEquals(RemoteMessageGroupPosition.END, presentation(messages, 2).position)
        assertTrue(presentation(messages, 2).showAvatar)
    }

    @Test
    fun repliesDateChangesAndLongPausesBreakVisualGroups() {
        val replied = message("reply", "trish", NOW.plusSeconds(30)).copy(
            replyToMessageId = RemoteMessageId("source"),
        )
        assertFalse(remoteMessagesBelongToSameVisualGroup(message("one", "trish", NOW), replied, UTC))
        assertFalse(
            remoteMessagesBelongToSameVisualGroup(
                message("one", "trish", NOW),
                message("late", "trish", NOW.plusSeconds(301)),
                UTC,
            ),
        )
        assertFalse(
            remoteMessagesBelongToSameVisualGroup(
                message("one", "trish", Instant.parse("2026-07-20T23:59:59Z")),
                message("next-day", "trish", Instant.parse("2026-07-21T00:00:01Z")),
                UTC,
            ),
        )
    }

    @Test
    fun ownMessagesDoNotExposeGroupIdentityFurniture() {
        val messages = listOf(message("mine", "peter", NOW))

        val presentation = remoteMessageGroupPresentation(
            messages = messages,
            index = 0,
            currentAccountUid = "peter",
            showGroupIdentities = true,
            zoneId = UTC,
        )

        assertFalse(presentation.showSenderName)
        assertFalse(presentation.showAvatar)
    }

    @Test
    fun unreadDividerAnchorsAtFirstIncomingMessageAfterReceipt() {
        val messages = listOf(
            message("old", "trish", NOW),
            message("mine", "peter", NOW.plusSeconds(10)),
            message("new", "trish", NOW.plusSeconds(20)),
        )

        assertEquals(
            RemoteMessageId("new"),
            remoteUnreadDividerMessageId(messages, "peter", NOW.plusSeconds(5)),
        )
    }

    @Test
    fun participantColorIsStablePerRoomAndSender() {
        val first = remoteParticipantColorIndex("room-a", "trish", 8)

        assertEquals(first, remoteParticipantColorIndex("room-a", "trish", 8))
        assertNotEquals(first, remoteParticipantColorIndex("room-b", "trish", 8))
    }

    private fun presentation(messages: List<RemoteCachedMessage>, index: Int) =
        remoteMessageGroupPresentation(
            messages = messages,
            index = index,
            currentAccountUid = "peter",
            showGroupIdentities = true,
            zoneId = UTC,
        )

    private fun message(id: String, senderUid: String, createdAt: Instant) = RemoteCachedMessage(
        accountUid = RemoteAccountUid("peter"),
        roomId = RemoteRoomId("group_${"a".repeat(32)}"),
        messageId = RemoteMessageId(id),
        idempotencyKey = RemoteIdempotencyKey(id),
        senderUid = RemoteProfileUid(senderUid),
        authorKind = "HUMAN",
        body = "Message $id",
        replyToMessageId = null,
        editedAt = null,
        deletedAt = null,
        revision = 1,
        reactionCounts = emptyMap(),
        deliveredToCount = 0,
        readByCount = 0,
        deliveryState = RemoteMessageDeliveryState.SENT,
        clientCreatedAt = createdAt,
        serverCreatedAt = createdAt,
        failureReason = null,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-20T16:00:00Z")
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}
