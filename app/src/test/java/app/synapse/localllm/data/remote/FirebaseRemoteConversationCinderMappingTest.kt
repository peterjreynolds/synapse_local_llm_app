package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAiProvenance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseRemoteConversationCinderMappingTest {
    @Test
    fun humanRoomAcceptsOnlyExactServerOwnedCinderAttribution() {
        val trusted = hasTrustedCinderRoomMessageAttribution(
            senderUid = "participant-cinder-remote-ai",
            aiParticipantId = "participant-cinder-remote-ai",
            aiProvenance = RemoteAiProvenance.REMOTE_HOSTED,
            aiProvider = "OPENCLAW_CINDER",
            assistantId = "cinder",
            serverSequence = 1_784_435_200_123_456L,
        )

        assertTrue(trusted)
        assertFalse(
            hasTrustedCinderRoomMessageAttribution(
                senderUid = "peter-uid",
                aiParticipantId = "participant-cinder-remote-ai",
                aiProvenance = RemoteAiProvenance.REMOTE_HOSTED,
                aiProvider = "OPENCLAW_CINDER",
                assistantId = "cinder",
                serverSequence = 1L,
            ),
        )
        assertFalse(
            hasTrustedCinderRoomMessageAttribution(
                senderUid = "participant-cinder-remote-ai",
                aiParticipantId = "participant-cinder-remote-ai",
                aiProvenance = RemoteAiProvenance.REMOTE_HOSTED,
                aiProvider = "GENERIC",
                assistantId = "cinder",
                serverSequence = 1L,
            ),
        )
    }
}
