package app.synapse.localllm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteDirectCallId
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.isValidRemoteConversationRoomId
import app.synapse.localllm.domain.remote.isValidRemoteDirectCallId
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SynapseFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        requireSynapseApplication()
            .graph
            .remoteDeviceRegistrationCoordinator
            .handleRefreshedInstallation(installationId)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        // This service owns FID registration through onRegistered. The legacy token must not enter
        // the FID-only device schema; remove this override when Android lint recognizes FID mode.
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        parseDirectCallNotificationPayload(remoteMessage.data)?.let { payload ->
            handleDirectCallNotification(payload)
            return
        }
        val payload = parseRemoteNotificationPayload(remoteMessage.data) ?: return
        if (
            requireSynapseApplication()
                .graph
                .remoteRoomVisibilityTracker
                .shouldSuppressNotification(payload.roomId)
        ) {
            return
        }
        if (!resolveNotificationPermissionState(this).allowsNotifications) {
            return
        }
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(this, REMOTE_CHAT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Synapse Chat")
            .setContentText(REMOTE_CHAT_PRIVATE_NOTIFICATION_TEXT)
            .setContentIntent(openRoomPendingIntent(payload.roomId))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(REMOTE_CHAT_NOTIFICATION_GROUP)
            .setNumber(payload.unreadCount)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(
                payload.roomId.raw,
                REMOTE_CHAT_NOTIFICATION_ID,
                notification,
            )
        } catch (_: SecurityException) {
            // Permission state can change between the explicit check and this external Android call.
            return
        }
    }

    private fun handleDirectCallNotification(payload: DirectCallNotificationPayload) {
        if (payload.event == DirectCallNotificationEvent.ENDED) {
            NotificationManagerCompat.from(this).cancel(
                payload.callId.raw,
                DIRECT_CALL_NOTIFICATION_ID,
            )
            return
        }
        if (payload.expiresAtMillis <= System.currentTimeMillis()) return
        if (!resolveNotificationPermissionState(this).allowsNotifications) return
        ensureDirectCallNotificationChannel()
        val notification = NotificationCompat.Builder(this, DIRECT_CALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Synapse Chat")
            .setContentText(payload.privateNotificationText)
            .setContentIntent(openCallPendingIntent(payload.callId))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter((payload.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1L))
            .build()
        try {
            NotificationManagerCompat.from(this).notify(
                payload.callId.raw,
                DIRECT_CALL_NOTIFICATION_ID,
                notification,
            )
        } catch (_: SecurityException) {
            return
        }
    }

    private fun openRoomPendingIntent(roomId: RemoteRoomId): PendingIntent {
        val intent = buildRemoteNotificationOpenIntent(this, roomId)
        return PendingIntent.getActivity(
            this,
            roomId.raw.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openCallPendingIntent(callId: RemoteDirectCallId): PendingIntent =
        PendingIntent.getActivity(
            this,
            callId.raw.hashCode(),
            buildDirectCallNotificationOpenIntent(this, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                REMOTE_CHAT_NOTIFICATION_CHANNEL_ID,
                "Synapse Chat messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts for Synapse Chat messages."
                setShowBadge(true)
            },
        )
    }

    private fun ensureDirectCallNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(createDirectCallNotificationChannel())
    }

    private fun requireSynapseApplication(): SynapseApplication {
        val currentApplication = application
        check(currentApplication is SynapseApplication) {
            "SynapseApplication is required for Firebase Messaging."
        }
        return currentApplication
    }

    private companion object {
        const val REMOTE_CHAT_NOTIFICATION_CHANNEL_ID = "synapse_remote_chat_messages"
        const val REMOTE_CHAT_NOTIFICATION_GROUP = "synapse_remote_chat"
        const val REMOTE_CHAT_PRIVATE_NOTIFICATION_TEXT = "You received a message from Synapse."
    }
}

internal fun createDirectCallNotificationChannel(): NotificationChannel =
    NotificationChannel(
        DIRECT_CALL_NOTIFICATION_CHANNEL_ID,
        "Synapse incoming calls",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = "Private ringing alerts for incoming Synapse calls."
        enableVibration(true)
        setShowBadge(false)
        setSound(
            Settings.System.DEFAULT_RINGTONE_URI,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
    }

internal fun dismissRemoteRoomNotification(
    context: Context,
    roomId: RemoteRoomId,
) {
    NotificationManagerCompat.from(context).cancel(roomId.raw, REMOTE_CHAT_NOTIFICATION_ID)
}

internal fun buildRemoteNotificationOpenIntent(
    context: Context,
    roomId: RemoteRoomId,
): Intent =
    Intent(context, RemoteNotificationOpenActivity::class.java)
        .putExtra(EXTRA_REMOTE_ROOM_ID, roomId.raw)

internal fun buildDirectCallNotificationOpenIntent(
    context: Context,
    callId: RemoteDirectCallId,
): Intent =
    Intent(context, RemoteNotificationOpenActivity::class.java)
        .putExtra(EXTRA_DIRECT_CALL_ID, callId.raw)

internal data class RemoteNotificationPayload(
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val senderUid: RemoteProfileUid,
    val unreadCount: Int,
)

internal enum class DirectCallNotificationEvent {
    INCOMING,
    ENDED,
}

internal data class DirectCallNotificationPayload(
    val callId: RemoteDirectCallId,
    val event: DirectCallNotificationEvent,
    val expiresAtMillis: Long,
    val mediaKind: RemoteDirectCallMediaKind,
)

private val DirectCallNotificationPayload.privateNotificationText: String
    get() = if (mediaKind == RemoteDirectCallMediaKind.VIDEO) {
        "Incoming Synapse video call."
    } else {
        "Incoming Synapse voice call."
    }

internal fun parseRemoteNotificationPayload(data: Map<String, String>): RemoteNotificationPayload? {
    if (data["type"] != REMOTE_CHAT_MESSAGE_TYPE) return null
    val roomId = data["roomId"]?.takeIf(::isValidRemoteConversationRoomId) ?: return null
    val messageId = data["messageId"]?.takeIf { value ->
        value.isNotBlank() && value.length <= MAXIMUM_REMOTE_NOTIFICATION_IDENTIFIER_LENGTH
    } ?: return null
    val senderUid = data["senderUid"]?.takeIf { value ->
        value.isNotBlank() && value.length <= MAXIMUM_REMOTE_NOTIFICATION_IDENTIFIER_LENGTH
    } ?: return null
    val unreadCount = data["unreadCount"]
        ?.toIntOrNull()
        ?.takeIf { value -> value in 1..MAXIMUM_REMOTE_NOTIFICATION_UNREAD_COUNT }
        ?: return null
    return RemoteNotificationPayload(
        roomId = RemoteRoomId(roomId),
        messageId = RemoteMessageId(messageId),
        senderUid = RemoteProfileUid(senderUid),
        unreadCount = unreadCount,
    )
}

internal fun parseDirectCallNotificationPayload(data: Map<String, String>): DirectCallNotificationPayload? {
    if (data["type"] != DIRECT_CALL_NOTIFICATION_TYPE) return null
    val callId = data["callId"]?.takeIf(::isValidRemoteDirectCallId) ?: return null
    val event = data["event"]?.let { value ->
        runCatching { DirectCallNotificationEvent.valueOf(value) }.getOrNull()
    } ?: return null
    val expiresAtMillis = data["expiresAtMillis"]?.toLongOrNull()?.takeIf { value -> value >= 0 } ?: return null
    val mediaKind = data["mediaKind"]?.let { value ->
        runCatching { RemoteDirectCallMediaKind.valueOf(value) }.getOrNull()
    } ?: if ("mediaKind" in data) return null else RemoteDirectCallMediaKind.AUDIO
    return DirectCallNotificationPayload(
        callId = RemoteDirectCallId(callId),
        event = event,
        expiresAtMillis = expiresAtMillis,
        mediaKind = mediaKind,
    )
}

const val EXTRA_REMOTE_ROOM_ID = "app.synapse.localllm.extra.REMOTE_ROOM_ID"
const val EXTRA_DIRECT_CALL_ID = "app.synapse.localllm.extra.DIRECT_CALL_ID"
private const val REMOTE_CHAT_MESSAGE_TYPE = "SYNAPSE_CHAT_MESSAGE"
private const val DIRECT_CALL_NOTIFICATION_TYPE = "SYNAPSE_DIRECT_CALL"
private const val DIRECT_CALL_NOTIFICATION_CHANNEL_ID = "synapse_direct_calls_ringing_v2"
internal const val REMOTE_CHAT_NOTIFICATION_ID = 4_301
internal const val DIRECT_CALL_NOTIFICATION_ID = 4_302
private const val MAXIMUM_REMOTE_NOTIFICATION_IDENTIFIER_LENGTH = 512
private const val MAXIMUM_REMOTE_NOTIFICATION_UNREAD_COUNT = 999
