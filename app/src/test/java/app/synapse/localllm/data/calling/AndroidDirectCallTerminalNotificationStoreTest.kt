package app.synapse.localllm.data.calling

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.remote.RemoteDirectCallId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidDirectCallTerminalNotificationStoreTest {
    @Test
    fun terminalCallSurvivesStoreRecreationAndRetentionStaysBounded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstStore = AndroidDirectCallTerminalNotificationStore(context)
        val originalCallId = callId(0)

        firstStore.record(originalCallId)
        assertTrue(AndroidDirectCallTerminalNotificationStore(context).contains(originalCallId))

        (1..65).forEach { index -> firstStore.record(callId(index)) }

        assertFalse(AndroidDirectCallTerminalNotificationStore(context).contains(originalCallId))
        assertTrue(AndroidDirectCallTerminalNotificationStore(context).contains(callId(65)))
    }

    private fun callId(index: Int): RemoteDirectCallId =
        RemoteDirectCallId("call_${index.toString(16).padStart(32, '0')}")
}
