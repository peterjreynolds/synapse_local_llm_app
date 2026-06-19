package app.synapse.localllm.data.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.runtime.DownloadModelCommand
import app.synapse.localllm.domain.runtime.ModelCatalogEntry
import app.synapse.localllm.domain.runtime.ModelDownloadEvent
import app.synapse.localllm.domain.runtime.ModelPromptProfile
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidModelDownloaderTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EmbeddedModelFiles.modelDirectory(context).deleteRecursively()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        EmbeddedModelFiles.modelDirectory(context).deleteRecursively()
    }

    @Test
    fun downloadsVerifiesAndStoresGgufModel() = runTest {
        val modelBytes = fakeGgufBytes()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", modelBytes.size)
                .setBody(Buffer().write(modelBytes)),
        )
        val entry = testEntry(
            downloadUrl = server.url("/Qwen3.5-9B-Q4_K_M.gguf?download=true").toString(),
            sizeBytes = modelBytes.size.toLong(),
            sha256 = sha256Hex(modelBytes),
        )

        val events = AndroidModelDownloader(context, OkHttpClient()).downloadModel(
            DownloadModelCommand(entry),
        ).toList()

        assertTrue(events.first() is ModelDownloadEvent.Progress)
        assertTrue(events.any { event -> event is ModelDownloadEvent.Verifying })
        val completed = events.last() as ModelDownloadEvent.Completed
        assertEquals(entry, completed.entry)
        assertEquals("Qwen3.5-9B-Q4_K_M.gguf", completed.receipt.displayName)
        assertEquals(modelBytes.size.toLong(), completed.receipt.byteCount)
        val storedModel = java.io.File(completed.receipt.modelPath)
        assertTrue(storedModel.isFile)
        assertEquals(modelBytes.size.toLong(), storedModel.length())
        assertTrue(EmbeddedModelFiles.hasGgufMagic(storedModel))
    }

    @Test
    fun rejectsDownloadedModelWhenChecksumDoesNotMatch() = runTest {
        val modelBytes = fakeGgufBytes()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", modelBytes.size)
                .setBody(Buffer().write(modelBytes)),
        )
        val entry = testEntry(
            downloadUrl = server.url("/model.gguf").toString(),
            sizeBytes = modelBytes.size.toLong(),
            sha256 = "0".repeat(64),
        )

        val failure = runCatching {
            AndroidModelDownloader(context, OkHttpClient()).downloadModel(
                DownloadModelCommand(entry),
            ).toList()
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("checksum mismatch") == true)
        assertTrue(EmbeddedModelFiles.modelDirectory(context).listFiles().orEmpty().none { file ->
            file.name.endsWith(".gguf")
        })
    }

    private fun testEntry(
        downloadUrl: String,
        sizeBytes: Long,
        sha256: String,
    ): ModelCatalogEntry =
        ModelCatalogEntry(
            id = "qwen-test",
            name = "Qwen Test",
            fileName = "Qwen3.5-9B-Q4_K_M.gguf",
            sizeBytes = sizeBytes,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            promptProfile = ModelPromptProfile.QWEN_CHATML,
            compatibilityNotes = "Test model.",
            sourceLabel = "Mock server",
            recommended = true,
        )

    private fun fakeGgufBytes(): ByteArray =
        EmbeddedModelFiles.GGUF_MAGIC + ByteArray(8192) { index -> (index % 251).toByte() }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
