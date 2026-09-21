package app.synapse.privatechat.data.call.media

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
import androidx.core.net.toUri
import app.synapse.privatechat.MainActivity
import app.synapse.privatechat.R
import app.synapse.privatechat.domain.call.PrivateCallMediaKind

class PrivateCallForegroundService : Service() {
    private var activeCallId: String? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val callId = intent?.getStringExtra(EXTRA_CALL_ID)
        if (intent?.action == ACTION_HANG_UP) {
            if (callId != null && callId == activeCallId) {
                PrivateCallForegroundRegistration.requestHangUp(callId)
                // The owner removes this service only after microphone/camera teardown completes.
            }
            return START_NOT_STICKY
        }
        val registration = callId?.let(PrivateCallForegroundRegistration::forCall)
        if (intent?.action != ACTION_START || registration == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeCallId = callId
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Visible call status and Hang up control."
                        setShowBadge(false)
                    },
                )
                check(manager.getNotificationChannel(CHANNEL_ID).importance != NotificationManager.IMPORTANCE_NONE) {
                    "Enable ongoing call notifications before starting a call."
                }
            }
            val openApp =
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val hangUp =
                PendingIntent.getService(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, PrivateCallForegroundService::class.java)
                        .setAction(ACTION_HANG_UP)
                        .setData("synapse-private-call:$callId".toUri())
                        .putExtra(EXTRA_CALL_ID, callId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(if (registration.kind == PrivateCallMediaKind.VIDEO) "Synapse video call" else "Synapse voice call")
                    .setContentText(
                        if (registration.kind ==
                            PrivateCallMediaKind.VIDEO
                        ) {
                            "Camera and microphone call · Hang up to stop"
                        } else {
                            "Microphone call · Hang up to stop"
                        },
                    ).setContentIntent(openApp)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setOngoing(true)
                    .setSilent(true)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", hangUp)
                    .build()
            val types =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        if (registration.kind == PrivateCallMediaKind.VIDEO) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
                } else {
                    0
                }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            registration.started.complete(Unit)
        } catch (_: RuntimeException) {
            registration.started.completeExceptionally(IllegalStateException("Android could not start the visible call service."))
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeCallId?.let(PrivateCallForegroundRegistration::requestHangUp)
        activeCallId = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_START = "app.synapse.privatechat.START_CALL"
        internal const val ACTION_HANG_UP = "app.synapse.privatechat.HANG_UP_CALL"
        internal const val EXTRA_CALL_ID = "private_call_id"
        private const val CHANNEL_ID = "synapse_private_active_call"
        private const val NOTIFICATION_ID = 4_403
    }
}
