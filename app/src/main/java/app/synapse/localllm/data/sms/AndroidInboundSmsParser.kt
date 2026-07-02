package app.synapse.localllm.data.sms

import android.content.Intent
import android.provider.Telephony
import app.synapse.localllm.domain.sms.InboundSmsAutoReplyCommand
import app.synapse.localllm.domain.sms.buildSmsInboundMessageKey
import app.synapse.localllm.domain.sms.normalizeInboundSmsBody
import app.synapse.localllm.domain.sms.parseSmsSenderAddress
import java.time.Instant

object AndroidInboundSmsParser {
    fun parseIntent(intent: Intent): InboundSmsAutoReplyCommand? {
        if (
            intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION
        ) {
            return null
        }
        val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            .filterNotNull()
        val firstMessage = smsMessages.firstOrNull() ?: return null
        val senderAddress = parseSmsSenderAddress(firstMessage.originatingAddress) ?: return null
        val messageBody = normalizeInboundSmsBody(
            smsMessages.joinToString(separator = "") { message -> message.messageBody.orEmpty() },
        )
        if (messageBody.isBlank()) return null
        val receivedAt = Instant.ofEpochMilli(
            firstMessage.timestampMillis.takeIf { timestamp -> timestamp > 0L }
                ?: System.currentTimeMillis(),
        )
        return InboundSmsAutoReplyCommand(
            inboundMessageKey = buildSmsInboundMessageKey(
                senderAddress = senderAddress,
                receivedAt = receivedAt,
                messageBody = messageBody,
            ),
            senderAddress = senderAddress,
            messageBody = messageBody,
            receivedAt = receivedAt,
        )
    }
}
