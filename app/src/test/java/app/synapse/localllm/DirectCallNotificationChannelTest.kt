package app.synapse.localllm

import android.app.NotificationManager
import android.media.AudioAttributes
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DirectCallNotificationChannelTest {
    @Test
    fun incomingCallChannelUsesThePhoneRingtoneAndVibration() {
        val channel = createDirectCallNotificationChannel()

        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Settings.System.DEFAULT_RINGTONE_URI, channel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, channel.audioAttributes.usage)
        assertTrue(channel.shouldVibrate())
    }
}
