package app.synapse.privatechat.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
                PrivateMessageActionOption.COPY,
                PrivateMessageActionOption.EDIT,
                PrivateMessageActionOption.DELETE_FOR_EVERYONE,
            ),
            privateMessageActionOptions(message(PrivateMessageOwnership.CURRENT_ACCOUNT)),
        )
    }

    @Test
    fun `other participant message actions allow reply without privileged mutations`() {
        assertEquals(
            listOf(
                PrivateMessageActionOption.REPLY,
                PrivateMessageActionOption.COPY,
            ),
            privateMessageActionOptions(message(PrivateMessageOwnership.OTHER_PARTICIPANT)),
        )
    }

    @Test
    fun `emoji insertion replaces the current selection and puts the caret after the emoji`() {
        val revisedValue =
            insertPrivateComposerEmoji(
                currentValue =
                    TextFieldValue(
                        text = "before selected after",
                        selection = TextRange(start = 7, end = 15),
                    ),
                emoji = "😊",
            )

        assertEquals("before 😊 after", revisedValue.text)
        assertEquals(TextRange(9), revisedValue.selection)
    }

    @Test
    fun `authoritative draft replacement moves the caret to the end of the restored draft`() {
        val synchronizedValue =
            synchronizePrivateComposerFieldValue(
                currentValue = TextFieldValue(text = "old", selection = TextRange(1)),
                authoritativeText = "restored draft",
            )

        assertEquals("restored draft", synchronizedValue.text)
        assertEquals(TextRange("restored draft".length), synchronizedValue.selection)
    }

    @Test
    fun `every quick reaction satisfies the reaction boundary`() {
        assertEquals(PRIVATE_QUICK_REACTIONS.size, PRIVATE_QUICK_REACTIONS.distinct().size)
        assertTrue(
            PRIVATE_QUICK_REACTIONS.all { emoji ->
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
