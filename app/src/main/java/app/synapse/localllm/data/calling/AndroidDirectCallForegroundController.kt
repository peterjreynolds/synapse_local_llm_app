package app.synapse.localllm.data.calling

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import app.synapse.localllm.DIRECT_CALL_NOTIFICATION_ID
import app.synapse.localllm.DirectCallForegroundService
import app.synapse.localllm.domain.calling.DirectCallForegroundController
import app.synapse.localllm.domain.remote.RemoteDirectCallId

class AndroidDirectCallForegroundController(context: Context) : DirectCallForegroundController {
    private val applicationContext = context.applicationContext

    override fun start(callId: RemoteDirectCallId) {
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, DirectCallForegroundService::class.java)
                .setAction(DirectCallForegroundService.ACTION_START)
                .putExtra(DirectCallForegroundService.EXTRA_CALL_ID, callId.raw),
        )
    }

    override fun stop() {
        applicationContext.stopService(Intent(applicationContext, DirectCallForegroundService::class.java))
    }

    override fun dismissIncomingNotification(callId: RemoteDirectCallId) {
        NotificationManagerCompat.from(applicationContext).cancel(callId.raw, DIRECT_CALL_NOTIFICATION_ID)
    }
}
