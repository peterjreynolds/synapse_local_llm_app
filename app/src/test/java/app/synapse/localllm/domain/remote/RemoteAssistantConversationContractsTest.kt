package app.synapse.localllm.domain.remote

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAssistantConversationContractsTest {
    @Test
    fun cinderIdentityIsStableAndProjectsToAnAppOwnedAssistantRoom() {
        val endpoint = RemoteAssistantConversationCatalog.cinder
        val room = endpoint.toCachedRoom(ACCOUNT_UID)

        assertEquals("cinder", endpoint.assistantId.raw)
        assertEquals("participant-cinder-remote-ai", endpoint.participantId.raw)
        assertEquals("assistant_cinder", endpoint.roomId.raw)
        assertEquals("Cinder", endpoint.displayName)
        assertEquals(RemoteRoomKind.ASSISTANT, room.kind)
        assertEquals(endpoint.roomId, room.roomId)
        assertEquals(endpoint.displayName, room.title)
        assertFalse(room.isPinned)
        assertNull(room.directKey)
        assertNull(room.peerUid)
        assertTrue(isValidRemoteAssistantRoomId(endpoint.roomId.raw))
        assertTrue(isValidRemoteConversationRoomId(endpoint.roomId.raw))
    }

    @Test
    fun remoteAiMessagesRequireARegisteredAssistantIdentityAndHostedProvenance() {
        val endpoint = RemoteAssistantConversationCatalog.cinder
        val message = remoteAiMessage(endpoint.participantId.raw)

        assertEquals("REMOTE_AI", message.authorKind)
        assertEquals(RemoteAiProvenance.REMOTE_HOSTED, message.aiProvenance)
        assertThrows(IllegalArgumentException::class.java) {
            remoteAiMessage("participant-unregistered-remote-ai")
        }
    }

    private fun remoteAiMessage(participantId: String) = RemoteCachedMessage(
        accountUid = ACCOUNT_UID,
        roomId = RemoteAssistantConversationCatalog.cinder.roomId,
        messageId = RemoteMessageId("cinder-message"),
        idempotencyKey = RemoteIdempotencyKey("cinder-message"),
        senderUid = RemoteProfileUid(participantId),
        authorKind = "REMOTE_AI",
        body = "Remote response",
        replyToMessageId = null,
        editedAt = null,
        deletedAt = null,
        revision = 1,
        reactionCounts = emptyMap(),
        deliveredToCount = 1,
        readByCount = 0,
        deliveryState = RemoteMessageDeliveryState.DELIVERED,
        clientCreatedAt = Instant.EPOCH,
        serverCreatedAt = Instant.EPOCH,
        failureReason = null,
        aiParticipantId = participantId,
        aiProvenance = RemoteAiProvenance.REMOTE_HOSTED,
    )

    private companion object {
        val ACCOUNT_UID = RemoteAccountUid("peter-uid")
    }
}
