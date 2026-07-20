package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSharedContentPresentationTest {
    @Test
    fun sharedContentClassifiesAttachmentsLinksAndNewestItemsFirst() {
        val older = message(
            id = "older",
            senderUid = "trish",
            body = "Reference https://example.com/guide).",
            sentAt = NOW.minusSeconds(60),
            attachments = listOf(attachment(IMAGE_ID, RemoteAttachmentKind.IMAGE)),
        )
        val newer = message(
            id = "newer",
            senderUid = "peter",
            body = "Watch https://example.com/watch?v=1",
            sentAt = NOW,
            attachments = listOf(
                attachment(VIDEO_ID, RemoteAttachmentKind.VIDEO),
                attachment(AUDIO_ID, RemoteAttachmentKind.AUDIO),
                attachment(FILE_ID, RemoteAttachmentKind.DOCUMENT),
            ),
        )

        val content = buildRemoteSharedContent(
            messages = listOf(older, newer),
            profiles = listOf(profile("trish", "Trish")),
            currentAccountUid = "peter",
        )

        assertEquals(6, content.size)
        assertTrue(content.take(4).all { item -> item.messageId.raw == "newer" })
        assertEquals(
            setOf(
                RemoteSharedContentCategory.PHOTOS,
                RemoteSharedContentCategory.VIDEOS,
                RemoteSharedContentCategory.AUDIO,
                RemoteSharedContentCategory.FILES,
                RemoteSharedContentCategory.LINKS,
            ),
            content.mapTo(mutableSetOf(), RemoteSharedContentItem::category),
        )
        assertEquals("You", content.first().senderDisplayName)
        assertTrue(content.any { item -> item.senderDisplayName == "Trish" })
        assertEquals(
            setOf("https://example.com/guide", "https://example.com/watch?v=1"),
            content.filterIsInstance<RemoteSharedContentItem.Link>().mapTo(mutableSetOf()) { item -> item.url },
        )
    }

    @Test
    fun deletedMessagesAndUnsafeLinkSchemesNeverReachSharedContent() {
        val deleted = message(
            id = "deleted",
            senderUid = "peter",
            body = "https://example.com/private",
            sentAt = NOW,
            attachments = listOf(attachment(FILE_ID, RemoteAttachmentKind.DOCUMENT)),
        ).copy(deletedAt = NOW.plusSeconds(1))
        val active = message(
            id = "active",
            senderUid = "peter",
            body = "javascript:alert(1) file:///secret ftp://example.com/file https://safe.example/path!",
            sentAt = NOW,
        )

        val content = buildRemoteSharedContent(listOf(deleted, active), emptyList(), "peter")

        assertEquals(listOf("https://safe.example/path"), content.map { item -> (item as RemoteSharedContentItem.Link).url })
        assertFalse(content.any { item -> item.messageId.raw == "deleted" })
    }

    private fun attachment(
        attachmentId: RemoteAttachmentId,
        kind: RemoteAttachmentKind,
    ) = RemoteCachedAttachment(
        attachmentId = attachmentId,
        displayName = "${kind.name.lowercase()}.bin",
        mimeType = when (kind) {
            RemoteAttachmentKind.IMAGE -> "image/jpeg"
            RemoteAttachmentKind.VIDEO -> "video/mp4"
            RemoteAttachmentKind.AUDIO -> "audio/mpeg"
            RemoteAttachmentKind.VOICE_NOTE -> "audio/mp4"
            RemoteAttachmentKind.DOCUMENT -> "application/pdf"
        },
        byteCount = 128,
        kind = kind,
        durationMillis = if (kind == RemoteAttachmentKind.AUDIO || kind == RemoteAttachmentKind.VOICE_NOTE) {
            1_000
        } else {
            null
        },
        contentObjectPath = "roomAttachments/room/message/${attachmentId.raw}/content",
        thumbnailObjectPath = if (kind == RemoteAttachmentKind.IMAGE) {
            "roomAttachments/room/message/${attachmentId.raw}/thumbnail"
        } else {
            null
        },
    )

    private fun message(
        id: String,
        senderUid: String,
        body: String,
        sentAt: Instant,
        attachments: List<RemoteCachedAttachment> = emptyList(),
    ) = RemoteCachedMessage(
        accountUid = RemoteAccountUid("peter"),
        roomId = RemoteRoomId("direct_${"a".repeat(64)}"),
        messageId = RemoteMessageId(id),
        idempotencyKey = RemoteIdempotencyKey(id),
        senderUid = RemoteProfileUid(senderUid),
        authorKind = "HUMAN",
        body = body,
        attachments = attachments,
        replyToMessageId = null,
        editedAt = null,
        deletedAt = null,
        revision = 1,
        reactionCounts = emptyMap(),
        deliveredToCount = 0,
        readByCount = 0,
        deliveryState = RemoteMessageDeliveryState.DELIVERED,
        clientCreatedAt = sentAt,
        serverCreatedAt = sentAt,
        failureReason = null,
    )

    private fun profile(uid: String, name: String) = RemoteCachedProfile(
        accountUid = RemoteAccountUid("peter"),
        profileUid = RemoteProfileUid(uid),
        username = uid,
        displayName = name,
        bio = "",
        avatarUrl = null,
        isAllowed = true,
        isOnline = false,
        lastSeenAt = null,
        remoteUpdatedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-20T12:00:00Z")
        val IMAGE_ID = RemoteAttachmentId("attachment-10000000-0000-4000-8000-000000000001")
        val VIDEO_ID = RemoteAttachmentId("attachment-20000000-0000-4000-8000-000000000002")
        val AUDIO_ID = RemoteAttachmentId("attachment-30000000-0000-4000-8000-000000000003")
        val FILE_ID = RemoteAttachmentId("attachment-40000000-0000-4000-8000-000000000004")
    }
}
