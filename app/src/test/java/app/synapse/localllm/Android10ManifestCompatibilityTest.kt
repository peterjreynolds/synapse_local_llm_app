package app.synapse.localllm

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Android10ManifestCompatibilityTest {
    @Test
    @Suppress("DEPRECATION")
    fun manifestKeepsApi29InstallFloorAndSecureNotificationRouting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
        val applicationInfo = requireNotNull(packageInfo.applicationInfo)
        val remoteRouterInfo = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, RemoteNotificationOpenActivity::class.java),
            0,
        )

        assertEquals(29, applicationInfo.minSdkVersion)
        assertEquals(36, applicationInfo.targetSdkVersion)
        assertNotNull(packageInfo.activities)
        assertFalse(remoteRouterInfo.exported)
        assertTrue(resolveNotificationPermissionState(context).allowsNotifications)
    }
}
