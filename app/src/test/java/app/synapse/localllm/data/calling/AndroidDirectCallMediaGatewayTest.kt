package app.synapse.localllm.data.calling

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDirectCallMediaGatewayTest {
    @Test
    fun constructionDefersVideoEglInitialization() {
        val eglCreationCount = AtomicInteger()
        val context = ApplicationProvider.getApplicationContext<Context>()

        AndroidDirectCallMediaGateway(
            context = context,
            videoEglBaseFactory = DirectCallVideoEglBaseFactory {
                eglCreationCount.incrementAndGet()
                error("App startup must not initialize WebRTC graphics.")
            },
        )

        assertEquals(0, eglCreationCount.get())
    }
}
