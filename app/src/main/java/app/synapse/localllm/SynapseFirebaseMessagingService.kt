package app.synapse.localllm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
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
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(this, REMOTE_CHAT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Synapse Chat")
            .setContentText("New private message")
            .setContentIntent(openRoomPendingIntent(payload.roomId))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(this).notify(
            payload.roomId.raw,
            REMOTE_CHAT_NOTIFICATION_ID,
            notification,
        )
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
                description = "Alerts for private Synapse Chat messages."
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
)

internal fun parseRemoteNotificationPayload(data: Map<String, String>): RemoteNotificationPayload? {
    if (data["type"] != REMOTE_CHAT_MESSAGE_TYPE) return null
    val roomId = data["roomId"]?.takeIf(REMOTE_DIRECT_ROOM_PATTERN::matches) ?: return null
    val messageId = data["messageId"]?.takeIf { value ->
        value.isNotBlank() && value.length <= MAXIMUM_REMOTE_NOTIFICATION_IDENTIFIER_LENGTH
    } ?: return null
    val senderUid = data["senderUid"]?.takeIf { value ->
        value.isNotBlank() && value.length <= MAXIMUM_REMOTE_NOTIFICATION_IDENTIFIER_LENGTH
    } ?: return null
    return RemoteNotificationPayload(
        roomId = RemoteRoomId(roomId),
        messageId = RemoteMessageId(messageId),
        senderUid = RemoteProfileUid(senderUid),
    )
}

const val EXTRA_REMOTE_ROOM_ID = "app.synapse.localllm.extra.REMOTE_ROOM_ID"
private const val REMOTE_CHAT_MESSAGE_TYPE = "SYNAPSE_CHAT_MESSAGE"
private const val MAXIMUM_REMOTE_NOTIFICATION_IDENTIFIER_LENGTH = 512
private val REMOTE_DIRECT_ROOM_PATTERN = Regex("^direct_[a-f0-9]{64}$")
