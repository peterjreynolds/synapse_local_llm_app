package app.synapse.localllm

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.runtime.ModelCatalogEntry
import app.synapse.localllm.domain.runtime.ModelPromptProfile
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
import app.synapse.localllm.domain.sms.InboundSmsAutoReplyCommand
import app.synapse.localllm.domain.sms.SmsInboundMessageKey
import app.synapse.localllm.domain.sms.SmsSenderAddress
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ModelDownloadForegroundServiceTest {
    @Test
    @Config(sdk = [28])
    fun api28ForegroundServicesDoNotUseNewerTypeConstants() {
        assertEquals(0, modelDownloadForegroundServiceType())
        assertEquals(0, smsAutoReplyForegroundServiceType())
        assertEquals(0, directCallForegroundServiceTypes(RemoteDirectCallMediaKind.AUDIO))
        assertEquals(0, directCallForegroundServiceTypes(RemoteDirectCallMediaKind.VIDEO))
    }

    @Test
    fun api29ForegroundServiceTypesMatchDeclaredCompatibilityPaths() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, modelDownloadForegroundServiceType())
        assertEquals(0, smsAutoReplyForegroundServiceType())
        assertEquals(0, directCallForegroundServiceTypes(RemoteDirectCallMediaKind.AUDIO))
        assertEquals(0, directCallForegroundServiceTypes(RemoteDirectCallMediaKind.VIDEO))
    }

    @Test
    @Config(sdk = [34])
    fun api34SmsAutoReplyUsesRemoteMessagingForegroundServiceType() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            smsAutoReplyForegroundServiceType(),
        )
    }

    @Test
    @Config(sdk = [34])
    fun api34DirectCallsDeclareOnlyTheMediaCurrentlyInUse() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            directCallForegroundServiceTypes(RemoteDirectCallMediaKind.AUDIO),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            directCallForegroundServiceTypes(RemoteDirectCallMediaKind.VIDEO),
        )
    }

    @Test
    fun startIntentRoundTripsCatalogEntry() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val entry = ModelCatalogEntry(
            id = "llama-test",
            name = "Llama Test",
            fileName = "llama-test.gguf",
            sizeBytes = 1024L,
            downloadUrl = "https://example.com/llama-test.gguf",
            sha256 = "a".repeat(64),
            promptProfile = ModelPromptProfile.LLAMA_INSTRUCT,
            compatibilityNotes = "Test entry.",
            sourceLabel = "Test source",
            recommended = false,
        )

        val parsedEntry = ModelDownloadForegroundService.parseStartIntent(
            ModelDownloadForegroundService.createStartIntent(context, entry),
        )

        assertEquals(entry, parsedEntry)
    }

    @Test
    fun parserRejectsWrongAction() {
        assertNull(ModelDownloadForegroundService.parseStartIntent(Intent("wrong.action")))
    }

    @Test
    fun smsAutoReplyStartIntentRoundTripsInboundSms() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val inboundSms = InboundSmsAutoReplyCommand(
            inboundMessageKey = SmsInboundMessageKey("b".repeat(64)),
            senderAddress = SmsSenderAddress("+15551234567"),
            messageBody = "Are you free later?",
            receivedAt = Instant.parse("2026-07-02T12:00:00Z"),
        )

        val parsedInboundSms = SmsAutoReplyForegroundService.parseStartIntent(
            SmsAutoReplyForegroundService.createStartIntent(context, inboundSms),
        )

        assertEquals(inboundSms, parsedInboundSms)
    }
}
