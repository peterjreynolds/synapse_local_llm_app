package app.synapse.localllm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAiStatusPresentationTest {
    @Test
    fun humanOnlyRoomsDoNotSurfaceBackgroundAiRefreshFailures() {
        assertFalse(shouldPublishRoomAiRefreshFailure(localAiEnabled = null))
        assertFalse(shouldPublishRoomAiRefreshFailure(localAiEnabled = false))
        assertTrue(shouldPublishRoomAiRefreshFailure(localAiEnabled = true))
    }
}
