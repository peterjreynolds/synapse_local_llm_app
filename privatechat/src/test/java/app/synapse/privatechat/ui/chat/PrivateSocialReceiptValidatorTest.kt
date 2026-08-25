package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.CreatePrivateRoomCommand
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSocialReceiptValidatorTest {
    @Test
    fun `room creation receipt matches the requesting account mutation and room kind`() {
        val command =
            CreatePrivateRoomCommand(
                accountId = PrivateAccountId("current"),
                mutationId = PrivateClientMutationId("mutation"),
                kind = PrivateRoomKind.GROUP,
                title = "Private circle",
                retention = PrivateMessageRetention.ONE_DAY,
            )
        val matchingReceipt =
            PrivateSocialMutationReceipt.RoomCreated(
                accountId = command.accountId,
                mutationId = command.mutationId,
                roomId = PrivateRoomId("room"),
                kind = command.kind,
            )

        assertTrue(PrivateSocialReceiptValidator.matches(matchingReceipt, command))
        assertFalse(
            PrivateSocialReceiptValidator.matches(
                matchingReceipt.copy(accountId = PrivateAccountId("different-account")),
                command,
            ),
        )
        assertFalse(
            PrivateSocialReceiptValidator.matches(
                matchingReceipt.copy(mutationId = PrivateClientMutationId("different-mutation")),
                command,
            ),
        )
        assertFalse(
            PrivateSocialReceiptValidator.matches(
                matchingReceipt.copy(kind = PrivateRoomKind.DIRECT),
                command,
            ),
        )
    }
}
