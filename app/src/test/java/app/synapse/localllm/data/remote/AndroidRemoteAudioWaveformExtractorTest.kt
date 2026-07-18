package app.synapse.localllm.data.remote

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidRemoteAudioWaveformExtractorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `accepts only downloaded audio inside private cache`() {
        val cachedAudio = File(context.cacheDir, "voice-message.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outsideCache = File(context.filesDir, "outside.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertEquals(
            cachedAudio.canonicalFile,
            validateRemoteAudioWaveformSource(context.cacheDir, Uri.fromFile(cachedAudio).toString()),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateRemoteAudioWaveformSource(context.cacheDir, Uri.fromFile(outsideCache).toString())
        }
    }

    @Test
    fun `normalizes actual decoded frame peaks into stable waveform bars`() {
        val peaks = RemoteWaveformPeakAccumulator(barCount = 4, estimatedFrameCount = 8)
        listOf(0.1f, 0.2f, 0.4f, 0.3f, 0.5f, 0.1f, 0.2f, 1f).forEach(peaks::append)

        assertEquals(listOf(0.2f, 0.4f, 0.5f, 1f), peaks.normalizedPeaks())

        val silent = RemoteWaveformPeakAccumulator(barCount = 2, estimatedFrameCount = 2)
        silent.append(0f)
        silent.append(0f)
        assertTrue(silent.normalizedPeaks().isEmpty())
    }
}
