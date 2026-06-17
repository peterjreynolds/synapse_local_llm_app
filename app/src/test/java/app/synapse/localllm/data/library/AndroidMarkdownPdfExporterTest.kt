package app.synapse.localllm.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File
import java.time.Instant
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
class AndroidMarkdownPdfExporterTest {
    private lateinit var context: Context
    private lateinit var exporter: AndroidMarkdownPdfExporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "library-exports").deleteRecursively()
        exporter = AndroidMarkdownPdfExporter(
            context = context,
            clock = FixedSynapseClock(Instant.parse("2026-06-17T16:00:00Z")),
            pdfWriter = MarkdownPdfWriter { targetFile, title, body ->
                targetFile.writeText(
                    "%PDF-1.4\n% Synapse test PDF\nTitle: $title\n$body\n%%EOF\n",
                    Charsets.UTF_8,
                )
            },
        )
    }

    @After
    fun tearDown() {
        File(context.cacheDir, "library-exports").deleteRecursively()
    }

    @Test
    fun exportMarkdownAsPdfWritesCacheFileAndFileProviderUri() {
        val receipt = exporter.exportMarkdownAsPdf(
            MarkdownPdfExportCommand(
                title = "Research Summary",
                markdown = "## Finding\n\nCatalog metadata comes before chunks.",
            ),
        )

        val pdfFile = File(context.cacheDir, receipt.relativePath)
        val pdfHeader = pdfFile.inputStream().use { input ->
            ByteArray(4).also { header -> input.read(header) }
        }.toString(Charsets.US_ASCII)

        assertEquals("research-summary-2026-06-17T16-00-00Z.pdf", receipt.displayName)
        assertEquals("application/pdf", receipt.mimeType)
        assertEquals("content", receipt.uri.scheme)
        assertEquals("${BuildConfig.APPLICATION_ID}.fileprovider", receipt.uri.authority)
        assertTrue(pdfFile.isFile)
        assertTrue(receipt.byteCount > 0)
        assertEquals("%PDF", pdfHeader)
    }

    private class FixedSynapseClock(private val instant: Instant) : SynapseClock {
        override fun now(): Instant = instant
    }
}
