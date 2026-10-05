package app.synapse.privatechat.data.call.media

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase

@RunWith(AndroidJUnit4::class)
class AndroidPrivateCallVideoLifecycleTest {
    @Test
    fun contextIsReleasedAfterBothFactoryAndRendererAndRecreatedForNextCall() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var contextsCreated = 0
            var contextsReleased = 0
            val video =
                AndroidPrivateCallVideoTracks(instrumentation.targetContext) {
                    val actual = EglBase.create()
                    contextsCreated += 1
                    object : EglBase by actual {
                        override fun release() {
                            contextsReleased += 1
                            actual.release()
                        }
                    }
                }
            assertEquals(0, contextsCreated)
            lateinit var renderer: View
            withContext(Dispatchers.IO) { video.eglContext() }
            instrumentation.runOnMainSync {
                renderer = video.createRendererView(instrumentation.targetContext, PrivateCallVideoTarget.LOCAL_PREVIEW)
            }
            withContext(Dispatchers.IO) {
                video.stop()
                video.releaseFactoryContext()
            }
            assertEquals(0, contextsReleased)
            instrumentation.runOnMainSync { video.releaseRendererView(renderer) }
            assertEquals(1, contextsReleased)
            withContext(Dispatchers.IO) {
                video.eglContext()
                video.stop()
                video.releaseFactoryContext()
            }
            assertEquals(2, contextsCreated)
            assertEquals(2, contextsReleased)
        }
}
