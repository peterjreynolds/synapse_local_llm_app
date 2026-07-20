package app.synapse.localllm

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
@Config(sdk = [28])
class Android9ManifestCompatibilityTest {
    @Test
    @Suppress("DEPRECATION")
    fun manifestKeepsApi28InstallFloorAndSecureNotificationRouting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
        val applicationInfo = requireNotNull(packageInfo.applicationInfo)
        val remoteRouterInfo = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, RemoteNotificationOpenActivity::class.java),
            0,
        )

        assertEquals(28, applicationInfo.minSdkVersion)
        assertEquals(36, applicationInfo.targetSdkVersion)
        assertNotNull(packageInfo.activities)
        assertFalse(remoteRouterInfo.exported)
        assertTrue(resolveNotificationPermissionState(context).allowsNotifications)
    }

    @Test
    @Suppress("DEPRECATION")
    fun api28ManifestKeepsVideoCallingOptional() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_CONFIGURATIONS or PackageManager.GET_PERMISSIONS,
        )
        val cameraFeature = packageInfo.reqFeatures.orEmpty().firstOrNull { feature ->
            feature.name == PackageManager.FEATURE_CAMERA_ANY
        }

        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(android.Manifest.permission.CAMERA))
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(android.Manifest.permission.RECORD_AUDIO))
        assertTrue(
            packageInfo.requestedPermissions.orEmpty()
                .contains(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
        )
        assertNotNull(cameraFeature)
        assertEquals(0, requireNotNull(cameraFeature).flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
    }

    @Test
    @Config(sdk = [29])
    fun api29ManifestDeclaresScopedForegroundMedia() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directCallService = context.packageManager.getServiceInfo(
            ComponentName(context, DirectCallForegroundService::class.java),
            0,
        )

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            directCallService.foregroundServiceType,
        )
    }
}
