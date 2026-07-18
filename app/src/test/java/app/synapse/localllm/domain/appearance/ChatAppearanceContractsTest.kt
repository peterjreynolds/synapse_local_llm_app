package app.synapse.localllm.domain.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAppearanceContractsTest {
    @Test
    fun chatMessageScaleClampsPinchGesturesToSupportedBounds() {
        assertEquals(MINIMUM_CHAT_MESSAGE_SCALE, clampChatMessageScale(0.2f))
        assertEquals(1.1f, clampChatMessageScale(1.1f))
        assertEquals(MAXIMUM_CHAT_MESSAGE_SCALE, clampChatMessageScale(2f))
        assertEquals(DEFAULT_CHAT_MESSAGE_SCALE, clampChatMessageScale(Float.NaN))
    }
}
