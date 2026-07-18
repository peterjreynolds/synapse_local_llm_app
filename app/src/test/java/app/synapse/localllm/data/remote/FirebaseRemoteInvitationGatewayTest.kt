package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteChatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseRemoteInvitationGatewayTest {
    @Test
    fun invitationCreationResponseIsNarrowedIntoAnExplicitReceipt() {
        val receipt = parseRemoteInvitationCreatedReceipt(
            mapOf(
                "expiresAtMillis" to 123_456L,
                "invitationCode" to "one-use-invitation-code",
                "invitationId" to "a".repeat(64),
                "maximumUses" to 1,
            ),
        )

        assertEquals("one-use-invitation-code", receipt.invitationCode)
        assertEquals("a".repeat(64), receipt.invitationId)
        assertEquals(123_456L, receipt.expiresAtMillis)
        assertEquals(1, receipt.maximumUses)
        assertThrows(RemoteChatException::class.java) {
            parseRemoteInvitationCreatedReceipt(
                mapOf(
                    "expiresAtMillis" to 123_456L,
                    "invitationCode" to "one-use-invitation-code",
                    "invitationId" to "a".repeat(64),
                    "maximumUses" to 1.5,
                ),
            )
        }
    }
}
