package app.synapse.localllm.data.library

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File
import java.time.Instant

private const val PDF_MIME_TYPE = "application/pdf"

class AndroidMarkdownPdfExporter {
    private val applicationContext: Context
    private val clock: SynapseClock
    private val pdfWriter: MarkdownPdfWriter
    private val workspacePaths: LibraryWorkspacePaths

    constructor(
        context: Context,
        clock: SynapseClock,
    ) : this(context, clock, AndroidPdfDocumentWriter())

    internal constructor(
        context: Context,
        clock: SynapseClock,
        pdfWriter: MarkdownPdfWriter,
    ) {
        this.applicationContext = context.applicationContext
        this.clock = clock
        this.pdfWriter = pdfWriter
        this.workspacePaths = LibraryWorkspacePaths(
            filesDirectory = applicationContext.filesDir,
            cacheDirectory = applicationContext.cacheDir,
        )
    }

    fun exportMarkdownAsPdf(command: MarkdownPdfExportCommand): MarkdownPdfExportReceipt {
        val title = LibraryWorkspacePaths.normalizeArtifactTitle(command.title)
        val body = command.markdown.trim()
        require(body.isNotBlank()) { "PDF export body cannot be blank." }

        val createdAt = clock.now()
        val filePlan = workspacePaths.planPdfExport(title, createdAt)
        filePlan.file.parentFile?.mkdirs()
        pdfWriter.writePdf(filePlan.file, title, body)

        val fileUri = FileProvider.getUriForFile(
            applicationContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            filePlan.file,
        )
        return MarkdownPdfExportReceipt(
            uri = fileUri,
            displayName = filePlan.displayName,
            relativePath = filePlan.relativePath,
            mimeType = PDF_MIME_TYPE,
            byteCount = filePlan.file.length(),
            createdAt = createdAt,
        )
    }
}

internal fun interface MarkdownPdfWriter {
    fun writePdf(
        targetFile: File,
        title: String,
        body: String,
    )
}

private class AndroidPdfDocumentWriter : MarkdownPdfWriter {
    override fun writePdf(
        targetFile: File,
        title: String,
        body: String,
    ) {
        val document = PdfDocument()
        try {
            var pageNumber = 1
            var page = document.startPage(newPageInfo(pageNumber))
            var canvas = page.canvas
            var y = TOP_MARGIN
            val titlePaint = createTitlePaint()
            val bodyPaint = createBodyPaint()

            canvas.drawText(title, LEFT_MARGIN.toFloat(), y.toFloat(), titlePaint)
            y += TITLE_BLOCK_HEIGHT

            buildWrappedLines(body, bodyPaint, PAGE_WIDTH - LEFT_MARGIN - RIGHT_MARGIN)
                .forEach { line ->
                    if (y > PAGE_HEIGHT - BOTTOM_MARGIN) {
                        document.finishPage(page)
                        pageNumber += 1
                        page = document.startPage(newPageInfo(pageNumber))
                        canvas = page.canvas
                        y = TOP_MARGIN
                    }
                    canvas.drawText(line, LEFT_MARGIN.toFloat(), y.toFloat(), bodyPaint)
                    y += BODY_LINE_HEIGHT
                }

            document.finishPage(page)
            targetFile.outputStream().buffered().use { output ->
                document.writeTo(output)
            }
        } finally {
            document.close()
        }
    }

    private fun buildWrappedLines(
        markdown: String,
        paint: Paint,
        maxWidth: Int,
    ): List<String> =
        markdown
            .lineSequence()
            .flatMap { paragraph ->
                if (paragraph.isBlank()) {
                    sequenceOf("")
                } else {
                    wrapParagraph(paragraph, paint, maxWidth).asSequence()
                }
            }
            .toList()

    private fun wrapParagraph(
        paragraph: String,
        paint: Paint,
        maxWidth: Int,
    ): List<String> {
        val words = paragraph.trim().split(Regex("\\s+"))
        val wrappedLines = mutableListOf<String>()
        var currentLine = ""

        words.forEach { word ->
            val candidateLine = if (currentLine.isBlank()) word else "$currentLine $word"
            if (paint.measureText(candidateLine) <= maxWidth) {
                currentLine = candidateLine
            } else {
                if (currentLine.isNotBlank()) {
                    wrappedLines += currentLine
                }
                currentLine = word
            }
        }
        if (currentLine.isNotBlank()) {
            wrappedLines += currentLine
        }
        return wrappedLines
    }

    private fun newPageInfo(pageNumber: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()

    private fun createTitlePaint(): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TITLE_TEXT_SIZE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun createBodyPaint(): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BODY_TEXT_SIZE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val LEFT_MARGIN = 42
        const val RIGHT_MARGIN = 42
        const val TOP_MARGIN = 48
        const val BOTTOM_MARGIN = 48
        const val TITLE_TEXT_SIZE = 18f
        const val BODY_TEXT_SIZE = 11f
        const val TITLE_BLOCK_HEIGHT = 34
        const val BODY_LINE_HEIGHT = 16
    }
}

data class MarkdownPdfExportCommand(
    val title: String,
    val markdown: String,
)

data class MarkdownPdfExportReceipt(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val byteCount: Long,
    val createdAt: Instant,
)
