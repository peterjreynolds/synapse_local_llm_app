package app.synapse.localllm

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DirectCallNotificationChannelTest {
    @Test
    fun incomingCallChannelLeavesLoopingAudioToTheAlertGatewayAndKeepsVibration() {
        val channel = createDirectCallNotificationChannel()

        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
        assertTrue(channel.shouldVibrate())
    }
}
