package app.synapse.localllm.data.remote

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteAttachmentPolicy
import app.synapse.localllm.domain.remote.RemoteAttachmentSelection
import app.synapse.localllm.domain.remote.RemoteChatException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RemoteAttachmentSourceMetadata(
    val displayName: String,
    val mimeType: String,
)

internal class AndroidRemoteAttachmentSelectionStager(
    context: Context,
    private val contentMetadataReader: (Uri) -> RemoteAttachmentSourceMetadata =
        { uri -> readContentMetadata(context.applicationContext.contentResolver, uri) },
    private val contentStreamOpener: (Uri) -> InputStream? =
        { uri -> context.applicationContext.contentResolver.openInputStream(uri) },
) {
    private val applicationContext = context.applicationContext
    private val stagingDirectory = File(applicationContext.cacheDir, STAGING_DIRECTORY_NAME)

    init {
        stagingDirectory.mkdirs()
        stagingDirectory.listFiles()?.forEach(File::delete)
    }

    suspend fun stageSelection(
        attachmentId: RemoteAttachmentId,
        sourceUri: String,
        audioDurationMillis: Long?,
        isVoiceNote: Boolean,
    ): RemoteAttachmentSelection = withContext(Dispatchers.IO) {
        val uri = sourceUri.toUri()
        if (isVoiceNote) {
            return@withContext inspectVoiceNote(
                attachmentId = attachmentId,
                uri = uri,
                audioDurationMillis = audioDurationMillis,
            )
        }
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
            "Choose the attachment with Android's file or photo picker."
        }
        val metadata = contentMetadataReader(uri)
        val maximumBytes = RemoteAttachmentPolicy.maximumBytesFor(metadata.mimeType)
            ?: throw IllegalArgumentException("Choose a supported image, document, or audio file.")
        val stagedFile = stagedFile(attachmentId)
        stagedFile.delete()
        try {
            val copiedBytes = contentStreamOpener(uri)?.use { input ->
                stagedFile.outputStream().buffered().use { output ->
                    input.copyBoundedTo(output, maximumBytes)
                }
            } ?: throw RemoteChatException("Android could not read the selected attachment.")
            val measuredAudioDuration = if (metadata.mimeType.startsWith("audio/")) {
                audioDurationMillis ?: measureAudioDuration(Uri.fromFile(stagedFile))
            } else {
                require(audioDurationMillis == null) { "Only audio can include a duration." }
                null
            }
            val decision = RemoteAttachmentPolicy.validate(
                displayName = metadata.displayName,
                mimeType = metadata.mimeType,
                byteCount = copiedBytes,
                audioDurationMillis = measuredAudioDuration,
                isVoiceNote = false,
            )
            RemoteAttachmentSelection(
                attachmentId = attachmentId,
                sourceUri = Uri.fromFile(stagedFile).toString(),
                displayName = decision.displayName,
                mimeType = decision.mimeType,
                byteCount = decision.byteCount,
                kind = decision.kind,
                durationMillis = decision.durationMillis,
            )
        } catch (exception: Exception) {
            stagedFile.delete()
            throw exception
        }
    }

    fun requireUploadSource(selection: RemoteAttachmentSelection): Uri {
        val uri = selection.sourceUri.toUri()
        val sourceFile = File(requireNotNull(uri.path)).canonicalFile
        val expectedFile = if (selection.kind == RemoteAttachmentKind.VOICE_NOTE) {
            File(applicationContext.cacheDir, REMOTE_VOICE_NOTE_CACHE_DIRECTORY)
                .resolve(sourceFile.name)
                .canonicalFile
        } else {
            stagedFile(selection.attachmentId).canonicalFile
        }
        require(uri.scheme == ContentResolver.SCHEME_FILE && sourceFile == expectedFile && sourceFile.isFile) {
            "The private attachment copy is unavailable. Add the file again."
        }
        return uri
    }

    fun release(attachmentId: RemoteAttachmentId) {
        stagedFile(attachmentId).delete()
    }

    internal fun stagedFile(attachmentId: RemoteAttachmentId): File =
        File(stagingDirectory, "${attachmentId.raw}$STAGING_FILE_SUFFIX")

    private fun inspectVoiceNote(
        attachmentId: RemoteAttachmentId,
        uri: Uri,
        audioDurationMillis: Long?,
    ): RemoteAttachmentSelection {
        require(uri.scheme == ContentResolver.SCHEME_FILE) {
            "The recorded voice note is unavailable."
        }
        val sourceFile = File(requireNotNull(uri.path)).canonicalFile
        val voiceNoteDirectory = File(
            applicationContext.cacheDir,
            REMOTE_VOICE_NOTE_CACHE_DIRECTORY,
        ).canonicalFile
        require(sourceFile.isFile && sourceFile.parentFile == voiceNoteDirectory) {
            "The recorded voice note is unavailable."
        }
        val decision = RemoteAttachmentPolicy.validate(
            displayName = sourceFile.name,
            mimeType = VOICE_NOTE_MIME_TYPE,
            byteCount = sourceFile.length(),
            audioDurationMillis = audioDurationMillis ?: measureAudioDuration(uri),
            isVoiceNote = true,
        )
        return RemoteAttachmentSelection(
            attachmentId = attachmentId,
            sourceUri = uri.toString(),
            displayName = decision.displayName,
            mimeType = decision.mimeType,
            byteCount = decision.byteCount,
            kind = decision.kind,
            durationMillis = decision.durationMillis,
        )
    }

    private fun measureAudioDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalArgumentException("Android could not measure the selected audio file.")
        } finally {
            retriever.release()
        }
    }

    private fun InputStream.copyBoundedTo(
        output: java.io.OutputStream,
        maximumBytes: Long,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copiedBytes = 0L
        while (true) {
            val readBytes = read(buffer)
            if (readBytes < 0) break
            copiedBytes += readBytes
            require(copiedBytes <= maximumBytes) {
                "The selected attachment exceeds the ${maximumBytes / MEBIBYTE} MB limit."
            }
            output.write(buffer, 0, readBytes)
        }
        require(copiedBytes > 0L) { "The selected attachment is empty." }
        return copiedBytes
    }

    private companion object {
        const val MEBIBYTE = 1024L * 1024L
        const val STAGING_DIRECTORY_NAME = "remote-attachment-staging"
        const val STAGING_FILE_SUFFIX = ".source"
        const val VOICE_NOTE_MIME_TYPE = "audio/mp4"
    }
}

internal class AndroidRemoteImageThumbnailEncoder {
    fun encode(sourceFile: File): ByteArray {
        require(sourceFile.isFile) { "The private image copy is unavailable." }
        val bitmap = runCatching { decodeWithImageDecoder(sourceFile) }
            .getOrElse { imageDecoderFailure ->
                decodeWithBitmapFactory(sourceFile)
                    ?: throw RemoteChatException("Android could not decode the selected image.", imageDecoderFailure)
            }
        return bitmap.useAsThumbnailBytes()
    }

    private fun decodeWithImageDecoder(sourceFile: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(sourceFile)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sourceWidth = info.size.width
            val sourceHeight = info.size.height
            val scale = minOf(
                1f,
                THUMBNAIL_EDGE.toFloat() / maxOf(sourceWidth, sourceHeight).toFloat(),
            )
            decoder.setTargetSize(
                maxOf(1, (sourceWidth * scale).toInt()),
                maxOf(1, (sourceHeight * scale).toInt()),
            )
        }

    private fun decodeWithBitmapFactory(sourceFile: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > THUMBNAIL_EDGE || bounds.outHeight / sampleSize > THUMBNAIL_EDGE) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            sourceFile.path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    private fun Bitmap.useAsThumbnailBytes(): ByteArray {
        return try {
            val output = ByteArrayOutputStream()
            var quality = 82
            do {
                output.reset()
                check(compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    "Android could not encode the image thumbnail."
                }
                quality -= 10
            } while (output.size() > MAXIMUM_THUMBNAIL_BYTES && quality >= 32)
            require(output.size() in 1..MAXIMUM_THUMBNAIL_BYTES) {
                "The image thumbnail is too large."
            }
            output.toByteArray()
        } finally {
            recycle()
        }
    }

    private companion object {
        const val MAXIMUM_THUMBNAIL_BYTES = 256 * 1024
        const val THUMBNAIL_EDGE = 512
    }
}

private fun readContentMetadata(
    resolver: ContentResolver,
    uri: Uri,
): RemoteAttachmentSourceMetadata {
    val mimeType = resolver.getType(uri)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Android did not provide a safe attachment type.")
    val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?: throw RemoteChatException("Android could not inspect the selected attachment.")
    cursor.use {
        require(it.moveToFirst()) { "The selected attachment has no metadata." }
        return RemoteAttachmentSourceMetadata(
            displayName = it.readDisplayName(),
            mimeType = mimeType,
        )
    }
}

private fun Cursor.readDisplayName(): String {
    val index = getColumnIndex(OpenableColumns.DISPLAY_NAME)
    return if (index >= 0 && !isNull(index)) getString(index) else "attachment"
}
