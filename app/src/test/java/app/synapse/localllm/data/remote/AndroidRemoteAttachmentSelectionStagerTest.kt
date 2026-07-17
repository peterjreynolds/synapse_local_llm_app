package app.synapse.localllm.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidRemoteAttachmentSelectionStagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val attachmentId = RemoteAttachmentId("attachment-123e4567-e89b-42d3-a456-426614174000")

    @Test
    fun `selected provider content is copied once into a durable private upload source`() = runTest {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        var openCount = 0
        val stager = AndroidRemoteAttachmentSelectionStager(
            context = context,
            contentMetadataReader = {
                RemoteAttachmentSourceMetadata("Summer photo.jpeg", "image/jpeg")
            },
            contentStreamOpener = {
                openCount += 1
                ByteArrayInputStream(jpegBytes)
            },
        )

        val selection = stager.stageSelection(
            attachmentId = attachmentId,
            sourceUri = "content://test.attachments/summer-photo",
            audioDurationMillis = null,
            isVoiceNote = false,
        )

        assertEquals(1, openCount)
        assertEquals("file", Uri.parse(selection.sourceUri).scheme)
        assertEquals("Summer photo.jpg", selection.displayName)
        assertEquals(jpegBytes.size.toLong(), selection.byteCount)
        assertArrayEquals(jpegBytes, File(requireNotNull(Uri.parse(selection.sourceUri).path)).readBytes())
        assertEquals(Uri.parse(selection.sourceUri), stager.requireUploadSource(selection))

        stager.release(attachmentId)
        assertFalse(File(requireNotNull(Uri.parse(selection.sourceUri).path)).exists())
    }

    @Test
    fun `provider size claims cannot bypass bounded private staging`() = runTest {
        val stager = AndroidRemoteAttachmentSelectionStager(
            context = context,
            contentMetadataReader = {
                RemoteAttachmentSourceMetadata("oversized.txt", "text/plain")
            },
            contentStreamOpener = {
                object : java.io.InputStream() {
                    private var remaining = 10 * 1024 * 1024 + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(remaining, length)
                        buffer.fill(0, offset, offset + count)
                        remaining -= count
                        return count
                    }
                }
            },
        )

        val failure = runCatching {
            stager.stageSelection(
                attachmentId = attachmentId,
                sourceUri = "content://test.attachments/oversized",
                audioDurationMillis = null,
                isVoiceNote = false,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("10 MB limit"))
        assertFalse(stager.stagedFile(attachmentId).exists())
    }

    @Test
    fun `thumbnail encoder bounds a staged image without rereading its provider uri`() {
        val source = File(context.cacheDir, "thumbnail-source.png")
        Bitmap.createBitmap(1_600, 1_200, Bitmap.Config.ARGB_8888).use { bitmap ->
            source.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }

        val thumbnail = AndroidRemoteImageThumbnailEncoder().encode(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size, bounds)

        assertTrue(thumbnail.isNotEmpty())
        assertTrue(thumbnail.size <= 256 * 1024)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 512)
        source.delete()
    }
}

private inline fun <R> Bitmap.use(block: (Bitmap) -> R): R =
    try {
        block(this)
    } finally {
        recycle()
    }
