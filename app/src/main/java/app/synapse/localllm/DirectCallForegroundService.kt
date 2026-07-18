package app.synapse.localllm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
import app.synapse.localllm.domain.remote.isValidRemoteDirectCallId

class DirectCallForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(EXTRA_CALL_ID)
        val mediaKind = intent?.getStringExtra(EXTRA_MEDIA_KIND)?.let { rawValue ->
            runCatching { RemoteDirectCallMediaKind.valueOf(rawValue) }.getOrNull()
        }
        if (
            intent?.action != ACTION_START ||
            callId == null ||
            !isValidRemoteDirectCallId(callId) ||
            mediaKind == null
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Synapse calls",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps an active Synapse call connected."
                setShowBadge(false)
            },
        )
        val openApp = PendingIntent.getActivity(
            this,
            ACTIVE_CALL_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (mediaKind == RemoteDirectCallMediaKind.VIDEO) "Synapse video call" else "Synapse voice call")
            .setContentText("Call in progress")
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val foregroundTypes = directCallForegroundServiceTypes(mediaKind)
        ServiceCompat.startForeground(this, ACTIVE_CALL_NOTIFICATION_ID, notification, foregroundTypes)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "app.synapse.localllm.action.START_DIRECT_CALL"
        const val EXTRA_CALL_ID = "app.synapse.localllm.extra.DIRECT_CALL_ID"
        const val EXTRA_MEDIA_KIND = "app.synapse.localllm.extra.DIRECT_CALL_MEDIA_KIND"
        private const val CHANNEL_ID = "synapse_active_voice_call"
        private const val ACTIVE_CALL_NOTIFICATION_ID = 4_303
    }
}

internal fun directCallForegroundServiceTypes(mediaKind: RemoteDirectCallMediaKind): Int =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        0
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            if (mediaKind == RemoteDirectCallMediaKind.VIDEO) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            }
    }
