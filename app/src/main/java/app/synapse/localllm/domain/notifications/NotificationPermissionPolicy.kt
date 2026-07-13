package app.synapse.localllm.domain.notifications

enum class NotificationPermissionState {
    NOT_REQUIRED,
    GRANTED,
    DENIED,
    ;

    val allowsNotifications: Boolean
        get() = this != DENIED

    val canRequestRuntimePermission: Boolean
        get() = this == DENIED
}

fun notificationPermissionState(
    androidApiLevel: Int,
    runtimePermissionGranted: Boolean,
): NotificationPermissionState =
    when {
        androidApiLevel < ANDROID_NOTIFICATION_RUNTIME_PERMISSION_API_LEVEL ->
            NotificationPermissionState.NOT_REQUIRED

        runtimePermissionGranted -> NotificationPermissionState.GRANTED
        else -> NotificationPermissionState.DENIED
    }

fun requiresNotificationRuntimePermission(androidApiLevel: Int): Boolean =
    androidApiLevel >= ANDROID_NOTIFICATION_RUNTIME_PERMISSION_API_LEVEL

private const val ANDROID_NOTIFICATION_RUNTIME_PERMISSION_API_LEVEL = 33
