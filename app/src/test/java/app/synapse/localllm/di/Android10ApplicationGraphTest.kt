package app.synapse.localllm.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Android10ApplicationGraphTest {
    @Test
    fun api29CreatesCompleteApplicationGraph() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = SynapseApplicationGraph.create(context)

        try {
            assertNotNull(graph.conversationRepository)
            assertNotNull(graph.localInferenceRuntime)
            assertNotNull(graph.remoteDeviceRegistrationCoordinator)
            assertNotNull(graph.smsAutoReplyCoordinator)
        } finally {
            graph.database.close()
            context.deleteDatabase("synapse.db")
        }
    }
}
