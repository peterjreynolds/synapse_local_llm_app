package app.synapse.localllm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.synapse.localllm.domain.notifications.NotificationPermissionState
import app.synapse.localllm.domain.notifications.notificationPermissionState
import app.synapse.localllm.domain.notifications.requiresNotificationRuntimePermission

internal fun resolveNotificationPermissionState(context: Context): NotificationPermissionState {
    val androidApiLevel = Build.VERSION.SDK_INT
    if (!requiresNotificationRuntimePermission(androidApiLevel)) {
        return NotificationPermissionState.NOT_REQUIRED
    }
    return notificationPermissionState(
        androidApiLevel = androidApiLevel,
        runtimePermissionGranted = ContextCompat.checkSelfPermission(
            context,
            POST_NOTIFICATIONS_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED,
    )
}

internal const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
