package app.synapse.localllm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.notifications.NotificationPermissionState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class NotificationPermissionResolverTest {
    @Test
    @Config(sdk = [29])
    fun api29DoesNotRequirePostNotificationsPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(NotificationPermissionState.NOT_REQUIRED, resolveNotificationPermissionState(context))
    }

    @Test
    @Config(sdk = [36])
    fun currentApiDenialAndGrantRemainVisibleToPermissionPolicy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as android.app.Application
        shadowOf(application).denyPermissions(POST_NOTIFICATIONS_PERMISSION)

        assertEquals(NotificationPermissionState.DENIED, resolveNotificationPermissionState(context))

        shadowOf(application).grantPermissions(POST_NOTIFICATIONS_PERMISSION)

        assertEquals(NotificationPermissionState.GRANTED, resolveNotificationPermissionState(context))
    }
}
