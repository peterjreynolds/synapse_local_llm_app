package app.synapse.privatechat.domain.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PrivateChatContractsTest {
    @Test
    fun `retention contract exposes only the four approved lifetimes`() {
        assertEquals(
            listOf(300, 3_600, 86_400, 604_800),
            PrivateMessageRetention.entries.map(PrivateMessageRetention::durationSeconds),
        )
        assertEquals(listOf("5m", "1h", "24h", "7d"), PrivateMessageRetention.entries.map { it.label })
    }

    @Test
    fun `message validation normalizes line endings but rejects controls`() {
        val accepted = validatePrivateMessageText("  first\r\nsecond  ")
        val rejected = validatePrivateMessageText("hello\u0000friend")

        assertEquals(
            "first\nsecond",
            (accepted as PrivateMessageTextValidation.Accepted).message.plaintext,
        )
        assertTrue(rejected is PrivateMessageTextValidation.Rejected)
    }

    @Test
    fun `sensitive values redact their contents`() {
        val message = PrivateMessageText("do-not-render")
        val invitation = PrivateRoomInvitationCode("A".repeat(43))

        assertFalse(message.toString().contains(message.plaintext))
        assertFalse(invitation.toString().contains(invitation.secret))
    }

    @Test
    fun `room invitation accepts only the backend issued capability width`() {
        assertTrue(parsePrivateRoomInvitationCode("A".repeat(43)) != null)
        assertEquals(null, parsePrivateRoomInvitationCode("A".repeat(42)))
        assertEquals(null, parsePrivateRoomInvitationCode("A".repeat(44)))
    }

    @Test
    fun `presence publication receipt accepts a server owned short interval`() {
        val publishedAt = Instant.parse("2026-08-20T18:00:00Z")

        val receipt =
            PrivateSocialMutationReceipt.PresencePublished(
                accountId = PrivateAccountId("current"),
                mutationId = PrivateClientMutationId("mutation"),
                publishedAt = publishedAt,
                expiresAt = publishedAt.plusSeconds(60),
            )

        assertEquals(publishedAt.plusSeconds(60), receipt.expiresAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `presence publication receipt rejects server intervals longer than two minutes`() {
        val publishedAt = Instant.parse("2026-08-20T18:00:00Z")

        PrivateSocialMutationReceipt.PresencePublished(
            accountId = PrivateAccountId("current"),
            mutationId = PrivateClientMutationId("mutation"),
            publishedAt = publishedAt,
            expiresAt = publishedAt.plusSeconds(121),
        )
    }

    @Test
    fun `room title validation normalizes unicode and rejects controls`() {
        val accepted = validatePrivateRoomTitle("  Private circle  ")
        val rejected = validatePrivateRoomTitle("Private\u0000circle")

        assertEquals("Private circle", (accepted as PrivateSocialTextValidation.Accepted).normalizedText)
        assertTrue(rejected is PrivateSocialTextValidation.Rejected)
    }

    @Test
    fun `owner-only rooms remain representable until a one-use invitation is redeemed`() {
        assertEquals(1, roomSummary(PrivateRoomKind.DIRECT).participantCount)
        assertEquals(1, roomSummary(PrivateRoomKind.GROUP).participantCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `direct rooms reject more than one invited peer`() {
        roomSummary(kind = PrivateRoomKind.DIRECT, participantCount = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mutation rejections reject control characters from a transport boundary`() {
        PrivateChatMutationOutcome.Rejected("Not allowed\u0000internal detail")
    }

    private fun roomSummary(
        kind: PrivateRoomKind,
        participantCount: Int = 1,
    ): PrivateRoomSummary =
        PrivateRoomSummary(
            roomId = PrivateRoomId("room"),
            kind = kind,
            title = "Private circle",
            participantCount = participantCount,
            retention = PrivateMessageRetention.ONE_DAY,
            archiveState = PrivateRoomArchiveState.ACTIVE,
            pinState = PrivateRoomPinState.UNPINNED,
            muteState = PrivateRoomMuteState.AUDIBLE,
            unreadMessageCount = 0,
            latestMessagePreview = null,
        )
}
