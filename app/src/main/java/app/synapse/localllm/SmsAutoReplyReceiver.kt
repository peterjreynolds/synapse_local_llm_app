package app.synapse.localllm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.synapse.localllm.data.sms.AndroidInboundSmsParser

class SmsAutoReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            intent?.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION
        ) {
            return
        }
        val inboundSms = intent?.let(AndroidInboundSmsParser::parseIntent) ?: return
        ContextCompat.startForegroundService(
            context,
            SmsAutoReplyForegroundService.createStartIntent(context, inboundSms),
        )
    }
}
