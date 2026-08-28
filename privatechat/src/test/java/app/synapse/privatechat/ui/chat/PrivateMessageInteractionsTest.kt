package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageOwnership
import app.synapse.privatechat.domain.chat.PrivateMessageSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageText
import app.synapse.privatechat.domain.chat.PrivateReactionValidation
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.validatePrivateReaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PrivateMessageInteractionsTest {
    @Test
    fun `own message actions preserve reply edit and delete for everyone`() {
        assertEquals(
            listOf(
                PrivateMessageActionOption.REPLY,
                PrivateMessageActionOption.EDIT,
                PrivateMessageActionOption.DELETE_FOR_EVERYONE,
            ),
            privateMessageActionOptions(message(PrivateMessageOwnership.CURRENT_ACCOUNT)),
        )
    }

    @Test
    fun `other participant message actions allow reply without privileged mutations`() {
        assertEquals(
            listOf(PrivateMessageActionOption.REPLY),
            privateMessageActionOptions(message(PrivateMessageOwnership.OTHER_PARTICIPANT)),
        )
    }

    @Test
    fun `every presented emoji satisfies the reaction boundary`() {
        assertEquals(PRIVATE_MESSAGE_EMOJIS.size, PRIVATE_MESSAGE_EMOJIS.distinct().size)
        assertTrue(
            PRIVATE_MESSAGE_EMOJIS.all { emoji ->
                validatePrivateReaction(emoji) is PrivateReactionValidation.Accepted
            },
        )
    }

    private fun message(ownership: PrivateMessageOwnership): PrivateMessageSnapshot =
        PrivateMessageSnapshot(
            roomId = PrivateRoomId("room"),
            messageId = PrivateMessageId("message"),
            senderAccountId = PrivateAccountId("sender"),
            senderDisplayName = "Sender",
            ownership = ownership,
            body = PrivateMessageText("message body"),
            replyPreview = null,
            revision = 1,
            reactions = emptyList(),
            sentAt = Instant.parse("2026-08-27T12:00:00Z"),
            editedAt = null,
            expiresAt = Instant.parse("2026-08-28T12:00:00Z"),
        )
}
