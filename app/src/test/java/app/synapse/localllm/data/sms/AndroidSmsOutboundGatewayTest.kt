package app.synapse.localllm.data.sms

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.ids.ReceiptId
import app.synapse.localllm.domain.sms.QueueSmsReplyCommand
import app.synapse.localllm.domain.sms.QueueSmsReplyOutcome
import app.synapse.localllm.domain.sms.SmsSenderAddress
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidSmsOutboundGatewayTest {
    @Test
    fun unavailableSmsServiceReturnsFailureWithoutBreakingApi29Startup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.applicationContext as android.app.Application).grantPermissions(Manifest.permission.SEND_SMS)
        val gateway = AndroidSmsOutboundGateway(context, FixedClock)

        val outcome = gateway.queueSmsReply(
            QueueSmsReplyCommand(
                recipientAddress = SmsSenderAddress("+15551234567"),
                replyBody = "Hello from Synapse",
                receiptId = ReceiptId("sms-receipt-api29"),
            ),
        )

        assertTrue(outcome is QueueSmsReplyOutcome.Failed)
        assertEquals(
            "Android SMS service is unavailable on this device.",
            (outcome as QueueSmsReplyOutcome.Failed).reason,
        )
    }

    private object FixedClock : SynapseClock {
        override fun now(): Instant = Instant.parse("2026-07-13T12:00:00Z")
    }
}
