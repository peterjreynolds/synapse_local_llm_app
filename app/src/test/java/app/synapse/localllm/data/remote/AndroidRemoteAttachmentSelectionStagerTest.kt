package app.synapse.localllm.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
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
        val jpegBytes = createJpegBytes(width = 32, height = 24)
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
        assertEquals("image/jpeg", selection.mimeType)
        val stagedFile = File(requireNotNull(Uri.parse(selection.sourceUri).path))
        assertEquals(stagedFile.length(), selection.byteCount)
        assertTrue(BitmapFactory.decodeFile(stagedFile.path) != null)
        assertEquals(Uri.parse(selection.sourceUri), stager.requireUploadSource(selection))

        stager.release(attachmentId)
        assertFalse(File(requireNotNull(Uri.parse(selection.sourceUri).path)).exists())
    }

    @Test
    fun `large one-shot JPEG screenshot is resized and compressed before upload`() = runTest {
        val jpegBytes = createJpegBytes(width = 1_440, height = 3_200, quality = 100)
        var openCount = 0
        val stager = AndroidRemoteAttachmentSelectionStager(
            context = context,
            contentMetadataReader = {
                RemoteAttachmentSourceMetadata("Screenshot_20260718_012942.jpg", "image/jpg")
            },
            contentStreamOpener = {
                openCount += 1
                object : ByteArrayInputStream(jpegBytes) {
                    override fun available(): Int = 0
                }
            },
        )

        val selection = stager.stageSelection(
            attachmentId = attachmentId,
            sourceUri = "content://media/picker/screenshots/42",
            audioDurationMillis = null,
            isVoiceNote = false,
        )

        val stagedFile = File(requireNotNull(Uri.parse(selection.sourceUri).path))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(stagedFile.path, bounds)
        assertEquals(1, openCount)
        assertEquals("image/jpeg", selection.mimeType)
        assertEquals("Screenshot_20260718_012942.jpg", selection.displayName)
        assertEquals(stagedFile.length(), selection.byteCount)
        assertTrue(selection.byteCount < jpegBytes.size)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 2_560)
    }

    @Test
    fun `generic provider MIME is replaced with the detected PNG type`() = runTest {
        val pngBytes = createPngBytes(width = 96, height = 180)
        val stager = AndroidRemoteAttachmentSelectionStager(
            context = context,
            contentMetadataReader = {
                RemoteAttachmentSourceMetadata("Screenshot_20260718_014802.bin", "application/octet-stream")
            },
            contentStreamOpener = { ByteArrayInputStream(pngBytes) },
        )

        val selection = stager.stageSelection(
            attachmentId = attachmentId,
            sourceUri = "content://media/picker/screenshots/84",
            audioDurationMillis = null,
            isVoiceNote = false,
        )

        val stagedFile = File(requireNotNull(Uri.parse(selection.sourceUri).path))
        assertEquals("image/png", selection.mimeType)
        assertEquals("Screenshot_20260718_014802.png", selection.displayName)
        assertTrue(BitmapFactory.decodeFile(stagedFile.path) != null)
    }

    @Test
    fun `reported image MIME fails closed when file signature is not an image`() = runTest {
        val stager = AndroidRemoteAttachmentSelectionStager(
            context = context,
            contentMetadataReader = {
                RemoteAttachmentSourceMetadata("broken.jpg", "image/jpeg")
            },
            contentStreamOpener = { ByteArrayInputStream("not-an-image".toByteArray()) },
        )

        val failure = runCatching {
            stager.stageSelection(
                attachmentId = attachmentId,
                sourceUri = "content://media/picker/screenshots/broken",
                audioDurationMillis = null,
                isVoiceNote = false,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("recognize the selected image"))
        assertFalse(stager.stagedFile(attachmentId).exists())
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

        val thumbnail = AndroidRemoteVisualThumbnailEncoder().encode(source, RemoteAttachmentKind.IMAGE)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size, bounds)

        assertTrue(thumbnail.isNotEmpty())
        assertTrue(thumbnail.size <= 256 * 1024)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 512)
        source.delete()
    }

    @Test
    fun `video thumbnail encoder uses one bounded poster frame`() {
        val source = File(context.cacheDir, "thumbnail-source.mp4").apply { writeBytes(byteArrayOf(1)) }
        var decodedFiles = emptyList<File>()
        val encoder = AndroidRemoteVisualThumbnailEncoder { videoFile ->
            decodedFiles = decodedFiles + videoFile
            Bitmap.createBitmap(1_200, 1_600, Bitmap.Config.ARGB_8888)
        }

        val thumbnail = encoder.encode(source, RemoteAttachmentKind.VIDEO)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size, bounds)

        assertEquals(listOf(source), decodedFiles)
        assertTrue(thumbnail.isNotEmpty())
        assertTrue(thumbnail.size <= 256 * 1024)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 512)
        source.delete()
    }
}

private fun createJpegBytes(
    width: Int,
    height: Int,
    quality: Int = 92,
): ByteArray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).use { bitmap ->
    java.io.ByteArrayOutputStream().use { output ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
        output.toByteArray()
    }
}

private fun createPngBytes(
    width: Int,
    height: Int,
): ByteArray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).use { bitmap ->
    java.io.ByteArrayOutputStream().use { output ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
    }
}

private inline fun <R> Bitmap.use(block: (Bitmap) -> R): R =
    try {
        block(this)
    } finally {
        recycle()
    }
