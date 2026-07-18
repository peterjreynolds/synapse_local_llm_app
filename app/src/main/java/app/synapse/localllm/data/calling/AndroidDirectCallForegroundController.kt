package app.synapse.localllm.data.calling

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.synapse.localllm.DIRECT_CALL_NOTIFICATION_ID
import app.synapse.localllm.DirectCallForegroundService
import app.synapse.localllm.domain.calling.DirectCallForegroundController
import app.synapse.localllm.domain.remote.RemoteDirectCallId
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind

class AndroidDirectCallForegroundController(context: Context) : DirectCallForegroundController {
    private val applicationContext = context.applicationContext

    override fun start(
        callId: RemoteDirectCallId,
        mediaKind: RemoteDirectCallMediaKind,
    ) {
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, DirectCallForegroundService::class.java)
                .setAction(DirectCallForegroundService.ACTION_START)
                .putExtra(DirectCallForegroundService.EXTRA_CALL_ID, callId.raw)
                .putExtra(DirectCallForegroundService.EXTRA_MEDIA_KIND, mediaKind.name),
        )
    }

    override fun stop() {
        applicationContext.stopService(Intent(applicationContext, DirectCallForegroundService::class.java))
    }

    override fun dismissIncomingNotification(callId: RemoteDirectCallId) {
        NotificationManagerCompat.from(applicationContext).cancel(callId.raw, DIRECT_CALL_NOTIFICATION_ID)
    }
}
