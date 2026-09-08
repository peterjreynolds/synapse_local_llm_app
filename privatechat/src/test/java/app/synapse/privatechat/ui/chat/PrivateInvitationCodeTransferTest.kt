package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateInvitationCode
import app.synapse.privatechat.domain.chat.PrivateAccountInvitationId
import app.synapse.privatechat.domain.chat.PrivateChatMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomInvitationCode
import app.synapse.privatechat.domain.chat.PrivateRoomInvitationId
import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PrivateInvitationCodeTransferTest {
    @Test
    fun `account invitation transfer exposes code only through explicit user actions`() {
        val secretCode = "A".repeat(43)
        val transferContent =
            PrivateInvitationTransferContent.forAccount(
                PrivateInvitationCode(secretCode),
            )

        assertEquals(secretCode, transferContent.exposeCodeForUserAction())
        assertTrue(transferContent.buildShareTextForUserAction().endsWith(secretCode))
        assertFalse(transferContent.toString().contains(secretCode))
        assertTrue(transferContent.toString().contains("[REDACTED]"))
    }

    @Test
    fun `conversation invitation transfer exposes code only through explicit user actions`() {
        val secretCode = "B".repeat(43)
        val transferContent =
            PrivateInvitationTransferContent.forConversation(
                PrivateRoomInvitationCode(secretCode),
            )

        assertEquals(secretCode, transferContent.exposeCodeForUserAction())
        assertTrue(transferContent.buildShareTextForUserAction().endsWith(secretCode))
        assertFalse(transferContent.toString().contains(secretCode))
        assertTrue(transferContent.toString().contains("[REDACTED]"))
    }

    @Test
    fun `confirmed invitation states keep account and conversation codes redacted`() {
        val accountSecretCode = "C".repeat(43)
        val conversationSecretCode = "D".repeat(43)
        val expiresAt = Instant.parse("2030-01-01T00:00:00Z")
        val accountState =
            PrivateAccountInvitationUiState.Confirmed(
                PrivateSocialMutationReceipt.OneUseAccountInvitationCreated(
                    accountId = PrivateAccountId("account-id"),
                    mutationId = PrivateClientMutationId("account-mutation-id"),
                    invitationId = PrivateAccountInvitationId("account-invitation-id"),
                    invitationCode = PrivateInvitationCode(accountSecretCode),
                    expiresAt = expiresAt,
                ),
            )
        val conversationState =
            PrivateRoomInvitationUiState.Confirmed(
                PrivateChatMutationReceipt.OneUseRoomInvitationCreated(
                    accountId = PrivateAccountId("account-id"),
                    roomId = PrivateRoomId("room-id"),
                    mutationId = PrivateClientMutationId("room-mutation-id"),
                    invitationId = PrivateRoomInvitationId("room-invitation-id"),
                    invitationCode = PrivateRoomInvitationCode(conversationSecretCode),
                    expiresAt = expiresAt,
                ),
            )

        assertFalse(accountState.toString().contains(accountSecretCode))
        assertFalse(conversationState.toString().contains(conversationSecretCode))
        assertTrue(accountState.toString().contains("[REDACTED]"))
        assertTrue(conversationState.toString().contains("[REDACTED]"))
    }
}
