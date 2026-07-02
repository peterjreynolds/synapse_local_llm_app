package app.synapse.localllm.data.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import app.synapse.localllm.domain.sms.QueueSmsReplyCommand
import app.synapse.localllm.domain.sms.QueueSmsReplyOutcome
import app.synapse.localllm.domain.sms.SmsOutboundGateway
import app.synapse.localllm.domain.time.SynapseClock

class AndroidSmsOutboundGateway(
    context: Context,
    private val clock: SynapseClock,
) : SmsOutboundGateway {
    private val applicationContext = context.applicationContext
    private val smsManager: SmsManager = applicationContext.getSystemService(SmsManager::class.java)

    override suspend fun queueSmsReply(command: QueueSmsReplyCommand): QueueSmsReplyOutcome {
        if (
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return QueueSmsReplyOutcome.Failed("SEND_SMS permission is not granted.")
        }
        val replyBody = command.replyBody.trim()
        if (replyBody.isBlank()) {
            return QueueSmsReplyOutcome.Failed("SMS reply body was blank.")
        }

        return runCatching {
            val messageParts = smsManager.divideMessage(replyBody)
                .takeIf { parts -> parts.isNotEmpty() }
                ?: listOf(replyBody)
            if (messageParts.size == 1) {
                smsManager.sendTextMessage(
                    command.recipientAddress.raw,
                    null,
                    messageParts.single(),
                    null,
                    null,
                )
            } else {
                smsManager.sendMultipartTextMessage(
                    command.recipientAddress.raw,
                    null,
                    ArrayList(messageParts),
                    null,
                    null,
                )
            }
            QueueSmsReplyOutcome.Queued(
                queuedAt = clock.now(),
                smsPartCount = messageParts.size,
            )
        }.getOrElse { exception ->
            QueueSmsReplyOutcome.Failed(exception.message ?: "Android SMS manager rejected the send request.")
        }
    }
}
