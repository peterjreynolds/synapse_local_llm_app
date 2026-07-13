package app.synapse.localllm.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun api29AndApi32DoNotRequireRuntimeNotificationPermission() {
        listOf(29, 32).forEach { androidApiLevel ->
            val permissionState = notificationPermissionState(
                androidApiLevel = androidApiLevel,
                runtimePermissionGranted = false,
            )

            assertEquals(NotificationPermissionState.NOT_REQUIRED, permissionState)
            assertTrue(permissionState.allowsNotifications)
            assertFalse(permissionState.canRequestRuntimePermission)
            assertFalse(requiresNotificationRuntimePermission(androidApiLevel))
        }
    }

    @Test
    fun api33AndCurrentApiReflectRuntimePermissionDecision() {
        listOf(33, 36).forEach { androidApiLevel ->
            val deniedState = notificationPermissionState(
                androidApiLevel = androidApiLevel,
                runtimePermissionGranted = false,
            )
            val grantedState = notificationPermissionState(
                androidApiLevel = androidApiLevel,
                runtimePermissionGranted = true,
            )

            assertEquals(NotificationPermissionState.DENIED, deniedState)
            assertFalse(deniedState.allowsNotifications)
            assertTrue(deniedState.canRequestRuntimePermission)
            assertEquals(NotificationPermissionState.GRANTED, grantedState)
            assertTrue(grantedState.allowsNotifications)
            assertFalse(grantedState.canRequestRuntimePermission)
            assertTrue(requiresNotificationRuntimePermission(androidApiLevel))
        }
    }
}
