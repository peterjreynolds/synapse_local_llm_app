package app.synapse.localllm.data.remote

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidDownloadedImageExporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `accepts a validated downloaded image from private cache`() {
        val source = File(context.cacheDir, "downloaded-image.cache").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val validated = validateDownloadedImageExportSource(
            cacheDirectory = context.cacheDir,
            localUri = Uri.fromFile(source).toString(),
            displayName = "Trish photo.png",
            mimeType = "image/png",
        )

        assertEquals(source.canonicalFile, validated)
    }

    @Test
    fun `rejects non-image and outside-cache export sources`() {
        val outsideCache = File(context.filesDir, "outside.png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertThrows(IllegalArgumentException::class.java) {
            validateDownloadedImageExportSource(
                cacheDirectory = context.cacheDir,
                localUri = Uri.fromFile(outsideCache).toString(),
                displayName = "outside.png",
                mimeType = "image/png",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDownloadedImageExportSource(
                cacheDirectory = context.cacheDir,
                localUri = Uri.fromFile(outsideCache).toString(),
                displayName = "outside.txt",
                mimeType = "text/plain",
            )
        }
    }
}
