package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateProfilePresentationTest {
    @Test
    fun `disabled presence is labelled off regardless of publication state`() {
        assertEquals(
            "Off",
            privatePresencePublicationLabel(
                sharingState = PrivatePresenceSharingState.DISABLED,
                publicationState = PrivatePresencePublicationUiState.Publishing,
            ),
        )
    }

    @Test
    fun `enabled presence does not contradict its switch before publication starts`() {
        assertEquals(
            "On while Synapse is open",
            privatePresencePublicationLabel(
                sharingState = PrivatePresenceSharingState.ENABLED,
                publicationState = PrivatePresencePublicationUiState.NotSharing,
            ),
        )
    }
}
