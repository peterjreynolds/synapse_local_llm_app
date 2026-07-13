package app.synapse.localllm

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.remote.RemoteRoomId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteNotificationRoutingTest {
    @Test
    @Suppress("DEPRECATION")
    fun notificationIntentTargetsNonExportedRouterWhileLauncherRemainsExported() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        val roomId = RemoteRoomId("direct_${"b".repeat(64)}")

        val intent = buildRemoteNotificationOpenIntent(context, roomId)
        val routerComponent = ComponentName(context, RemoteNotificationOpenActivity::class.java)
        val routerInfo = packageManager.getActivityInfo(routerComponent, 0)
        val launcherInfo = packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)

        assertEquals(routerComponent, intent.component)
        assertEquals(roomId.raw, intent.getStringExtra(EXTRA_REMOTE_ROOM_ID))
        assertFalse(routerInfo.exported)
        assertTrue(launcherInfo.exported)
    }
}
