package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAiProvenance
import app.synapse.localllm.domain.remote.RemoteAiResponsePolicy
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteRoomId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseRemoteCinderParticipantGatewayTest {
    @Test
    fun exactParticipantReceiptMapsSummonAndManagementState() {
        val state = validParticipantReceipt(active = true, canManage = false)
            .toCinderParticipantState(ROOM_ID)

        assertTrue(state.active)
        assertEquals(false, state.canManage)
        assertEquals(RemoteAssistantConversationCatalog.cinder.participantId, state.participantId)
        assertEquals(RemoteAiProvenance.REMOTE_HOSTED, state.provenance)
        assertEquals(RemoteAiResponsePolicy.MENTION_ONLY, state.responsePolicy)
        assertEquals(7L, state.revision)
    }

    @Test
    fun forgedProviderReceiptIsRejected() {
        val failure = runCatching {
            (validParticipantReceipt(active = true, canManage = true) + ("provider" to "GENERIC"))
                .toCinderParticipantState(ROOM_ID)
        }.exceptionOrNull()

        assertTrue(failure is app.synapse.localllm.domain.remote.RemoteChatException)
    }

    private fun validParticipantReceipt(
        active: Boolean,
        canManage: Boolean,
    ): Map<String, Any> = mapOf(
        "active" to active,
        "canManage" to canManage,
        "displayName" to "Cinder",
        "participantId" to "participant-cinder-remote-ai",
        "provenance" to "REMOTE_HOSTED",
        "provider" to "OPENCLAW_CINDER",
        "responsePolicy" to "MENTION_ONLY",
        "revision" to 7L,
        "roomId" to ROOM_ID.raw,
    )

    private companion object {
        val ROOM_ID = RemoteRoomId("group_${"a".repeat(32)}")
    }
}
