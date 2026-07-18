package app.synapse.localllm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.isValidRemoteConversationRoomId
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
            .setContentTitle(payload.senderDisplayName ?: "Synapse Chat")
            .setContentText("New message")
            .setSubText("Synapse Chat")
            .setContentIntent(openRoomPendingIntent(payload.roomId))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(REMOTE_CHAT_NOTIFICATION_GROUP)
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

    private fun openRoomPendingIntent(roomId: RemoteRoomId): PendingIntent {
        val intent = buildRemoteNotificationOpenIntent(this, roomId)
        return PendingIntent.getActivity(
            this,
            roomId.raw.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                REMOTE_CHAT_NOTIFICATION_CHANNEL_ID,
                "Synapse Chat messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts for Synapse Chat messages."
            },
        )
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
        const val REMOTE_CHAT_NOTIFICATION_ID = 4_301
        const val REMOTE_CHAT_NOTIFICATION_GROUP = "synapse_remote_chat"
    }
}

internal fun buildRemoteNotificationOpenIntent(
    context: Context,
    roomId: RemoteRoomId,
): Intent =
    Intent(context, RemoteNotificationOpenActivity::class.java)
        .putExtra(EXTRA_REMOTE_ROOM_ID, roomId.raw)

internal data class RemoteNotificationPayload(
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val senderUid: RemoteProfileUid,
    val senderDisplayName: String?,
)

internal fun parseRemoteNotificationPayload(data: Map<String, String>): RemoteNotificationPayload? {
    if (data["type"] != REMOTE_CHAT_MESSAGE_TYPE) return null
    val roomId = data["roomId"]?.takeIf(::isValidRemoteConversationRoomId) ?: return null
    val messageId = data["messageId"]?.takeIf { value ->
        value.isNotBlank() && value.length <= MAXIMUM_REMOTE_NOTIFICATION_IDENTIFIER_LENGTH
    } ?: return null
    val senderUid = data["senderUid"]?.takeIf { value ->
        value.isNotBlank() && value.length <= MAXIMUM_REMOTE_NOTIFICATION_IDENTIFIER_LENGTH
    } ?: return null
    val senderDisplayName = data["senderDisplayName"]
        ?.trim()
        ?.takeIf { value ->
            value.isNotEmpty() &&
                value.length <= MAXIMUM_REMOTE_NOTIFICATION_SENDER_NAME_LENGTH &&
                value.none(Char::isISOControl)
        }
    return RemoteNotificationPayload(
        roomId = RemoteRoomId(roomId),
        messageId = RemoteMessageId(messageId),
        senderUid = RemoteProfileUid(senderUid),
        senderDisplayName = senderDisplayName,
    )
}

const val EXTRA_REMOTE_ROOM_ID = "app.synapse.localllm.extra.REMOTE_ROOM_ID"
private const val REMOTE_CHAT_MESSAGE_TYPE = "SYNAPSE_CHAT_MESSAGE"
private const val MAXIMUM_REMOTE_NOTIFICATION_IDENTIFIER_LENGTH = 512
private const val MAXIMUM_REMOTE_NOTIFICATION_SENDER_NAME_LENGTH = 64
