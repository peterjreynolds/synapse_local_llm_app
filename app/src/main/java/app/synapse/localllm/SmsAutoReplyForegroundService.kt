package app.synapse.localllm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.synapse.localllm.domain.sms.InboundSmsAutoReplyCommand
import app.synapse.localllm.domain.sms.SmsAutoReplyReceiptRecord
import app.synapse.localllm.domain.sms.SmsAutoReplyState
import app.synapse.localllm.domain.sms.SmsInboundMessageKey
import app.synapse.localllm.domain.sms.SmsSenderAddress
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SmsAutoReplyForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inboundSms = parseStartIntent(intent)
        if (inboundSms == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(),
            smsAutoReplyForegroundServiceType(),
        )

        serviceScope.launch {
            runSmsAutoReply(inboundSms = inboundSms, startId = startId)
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runSmsAutoReply(
        inboundSms: InboundSmsAutoReplyCommand,
        startId: Int,
    ) {
        try {
            val graph = requireSynapseApplication().graph
            val settings = graph.settingsStore.settingsFlow.first()
            val receipt = graph.smsAutoReplyCoordinator.processInboundSms(
                command = inboundSms,
                settings = settings,
            )
            finishWithReceipt(receipt)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            notifyFailed(exception.message ?: "SMS auto-reply failed.")
        } finally {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelfResult(startId)
        }
    }

    private fun finishWithReceipt(receipt: SmsAutoReplyReceiptRecord) {
        when (receipt.state) {
            SmsAutoReplyState.SMS_QUEUED ->
                notifyQueued(receipt.smsPartCount)

            SmsAutoReplyState.AUTO_REPLY_DISABLED,
            SmsAutoReplyState.GENERATING,
            SmsAutoReplyState.DUPLICATE_IGNORED,
            -> Unit

            SmsAutoReplyState.GENERATION_FAILED,
            SmsAutoReplyState.EMPTY_REPLY_REJECTED,
            SmsAutoReplyState.SMS_QUEUE_FAILED,
            SmsAutoReplyState.INVALID_INBOUND_MESSAGE,
            -> notifyFailed(receipt.failureReason ?: "SMS auto-reply did not send.")
        }
    }

    private fun buildProgressNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Synapse SMS auto-reply")
            .setContentText("Preparing automatic reply.")
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notifyQueued(partCount: Int) {
        val text = if (partCount == 1) {
            "Automatic SMS reply queued."
        } else {
            "Automatic SMS reply queued in $partCount parts."
        }
        notificationManager().notify(
            NOTIFICATION_ID,
            buildTerminalNotification(
                title = "SMS auto-reply queued",
                text = text,
                icon = android.R.drawable.stat_notify_chat,
            ),
        )
    }

    private fun notifyFailed(message: String) {
        notificationManager().notify(
            NOTIFICATION_ID,
            buildTerminalNotification(
                title = "SMS auto-reply failed",
                text = message,
                icon = android.R.drawable.stat_notify_error,
            ),
        )
    }

    private fun buildTerminalNotification(
        title: String,
        text: String,
        icon: Int,
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS auto-replies",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows Synapse automatic SMS reply processing."
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun smsAutoReplyForegroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }

    private fun requireSynapseApplication(): SynapseApplication {
        val currentApplication = application
        check(currentApplication is SynapseApplication) {
            "SynapseApplication is required for SMS auto-replies."
        }
        return currentApplication
    }

    companion object {
        private const val ACTION_PROCESS_SMS_AUTO_REPLY =
            "app.synapse.localllm.action.PROCESS_SMS_AUTO_REPLY"
        private const val CHANNEL_ID = "synapse_sms_auto_replies"
        private const val NOTIFICATION_ID = 4201

        private const val EXTRA_INBOUND_MESSAGE_KEY = "inbound_message_key"
        private const val EXTRA_SENDER_ADDRESS = "sender_address"
        private const val EXTRA_MESSAGE_BODY = "message_body"
        private const val EXTRA_RECEIVED_AT_EPOCH_MILLIS = "received_at_epoch_millis"

        fun createStartIntent(
            context: Context,
            inboundSms: InboundSmsAutoReplyCommand,
        ): Intent =
            Intent(context, SmsAutoReplyForegroundService::class.java)
                .setAction(ACTION_PROCESS_SMS_AUTO_REPLY)
                .putExtra(EXTRA_INBOUND_MESSAGE_KEY, inboundSms.inboundMessageKey.raw)
                .putExtra(EXTRA_SENDER_ADDRESS, inboundSms.senderAddress.raw)
                .putExtra(EXTRA_MESSAGE_BODY, inboundSms.messageBody)
                .putExtra(EXTRA_RECEIVED_AT_EPOCH_MILLIS, inboundSms.receivedAt.toEpochMilli())

        fun parseStartIntent(intent: Intent?): InboundSmsAutoReplyCommand? {
            if (intent?.action != ACTION_PROCESS_SMS_AUTO_REPLY) return null
            val inboundMessageKey = intent.getNonBlankStringExtra(EXTRA_INBOUND_MESSAGE_KEY)
                ?.let(::SmsInboundMessageKey)
                ?: return null
            val senderAddress = intent.getNonBlankStringExtra(EXTRA_SENDER_ADDRESS)
                ?.let(::SmsSenderAddress)
                ?: return null
            val messageBody = intent.getNonBlankStringExtra(EXTRA_MESSAGE_BODY) ?: return null
            val receivedAtEpochMillis = intent.getLongExtra(EXTRA_RECEIVED_AT_EPOCH_MILLIS, -1L)
            if (receivedAtEpochMillis <= 0L) return null

            return InboundSmsAutoReplyCommand(
                inboundMessageKey = inboundMessageKey,
                senderAddress = senderAddress,
                messageBody = messageBody,
                receivedAt = Instant.ofEpochMilli(receivedAtEpochMillis),
            )
        }

        private fun Intent.getNonBlankStringExtra(key: String): String? =
            getStringExtra(key)?.trim()?.takeIf { value -> value.isNotBlank() }
    }
}
