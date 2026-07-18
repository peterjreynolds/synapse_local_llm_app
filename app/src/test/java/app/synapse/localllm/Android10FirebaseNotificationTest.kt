package app.synapse.localllm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Android10FirebaseNotificationTest {
    @Test
    fun api29FcmMessageCreatesChannelAndPostsNotificationWithoutRuntimePermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val shadowNotificationManager = shadowOf(notificationManager)
        val roomId = "direct_${"c".repeat(64)}"
        val serviceController = Robolectric.buildService(SynapseFirebaseMessagingService::class.java).create()

        try {
            serviceController.get().onMessageReceived(
                RemoteMessage.Builder("test-sender")
                    .setData(
                        mapOf(
                            "messageId" to "message-api29",
                            "roomId" to roomId,
                            "senderUid" to "trish-uid",
                            "type" to "SYNAPSE_CHAT_MESSAGE",
                            "unreadCount" to "3",
                        ),
                    )
                    .build(),
            )

            assertEquals(1, shadowNotificationManager.size())
            val postedNotification = shadowNotificationManager.allNotifications.single()
            assertEquals("Synapse Chat", postedNotification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(
                "You received a message from Synapse.",
                postedNotification.extras.getString(Notification.EXTRA_TEXT),
            )
            assertEquals(3, postedNotification.number)
            assertEquals(Notification.BADGE_ICON_SMALL, postedNotification.badgeIconType)
            assertEquals(0, postedNotification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertTrue(
                shadowNotificationManager.notificationChannels.any { channel ->
                    channel.name == "Synapse Chat messages" && channel.canShowBadge()
                },
            )

            dismissRemoteRoomNotification(context, app.synapse.localllm.domain.remote.RemoteRoomId(roomId))
            assertEquals(0, shadowNotificationManager.size())
        } finally {
            serviceController.destroy()
        }
    }
}
