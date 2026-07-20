package app.synapse.localllm.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Android9ApplicationGraphTest {
    @Test
    fun api28CreatesCompleteApplicationGraph() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = SynapseApplicationGraph.create(context)

        try {
            assertNotNull(graph.conversationRepository)
            assertNotNull(graph.localInferenceRuntime)
            assertNotNull(graph.remoteDeviceRegistrationCoordinator)
            assertNotNull(graph.smsAutoReplyCoordinator)
            val capabilities = graph.deviceRuntimeCapabilitiesReader.readDeviceRuntimeCapabilities()
            assertEquals(28, capabilities.androidApiLevel)
            assertTrue(capabilities.totalMemoryBytes > 0L)
        } finally {
            graph.database.close()
            context.deleteDatabase("synapse.db")
        }
    }
}
