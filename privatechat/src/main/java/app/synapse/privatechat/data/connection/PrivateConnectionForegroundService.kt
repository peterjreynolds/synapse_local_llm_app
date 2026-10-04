package app.synapse.privatechat.data.connection

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
import app.synapse.privatechat.MainActivity
import app.synapse.privatechat.PrivateChatCompositionRoot
import app.synapse.privatechat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PrivateConnectionForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stopObserver: Job? = null
    private val connection by lazy { PrivateChatCompositionRoot.create(applicationContext).backgroundConnection }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action != ACTION_START) {
            connection.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            check(manager.areNotificationsEnabled())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Background connection", NotificationManager.IMPORTANCE_LOW),
                )
                check(manager.getNotificationChannel(CHANNEL_ID).importance != NotificationManager.IMPORTANCE_NONE)
            }
            val open =
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val stop =
                PendingIntent.getService(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, PrivateConnectionForegroundService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Synapse Private · Background mode")
                    .setContentText("Receiving chats and updating activity · Stop to disconnect in background")
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setSilent(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
                    .build()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
            )
            connection.setEnabled(true)
            stopObserver?.cancel()
            stopObserver = scope.launch { connection.enabled.collect { if (!it) stopSelf() } }
        } catch (failure: RuntimeException) {
            connection.reportStartFailure(failure)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        connection.setEnabled(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "app.synapse.privatechat.START_CONNECTION"
        const val ACTION_STOP = "app.synapse.privatechat.STOP_CONNECTION"
        private const val CHANNEL_ID = "synapse_private_background_connection"
        private const val NOTIFICATION_ID = 4404
    }
}
