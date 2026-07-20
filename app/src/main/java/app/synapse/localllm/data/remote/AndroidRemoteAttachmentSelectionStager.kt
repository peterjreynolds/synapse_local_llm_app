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

private data class StagedAttachmentMetadata(
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
)

internal class AndroidRemoteAttachmentSelectionStager(
    context: Context,
    private val contentMetadataReader: (Uri) -> RemoteAttachmentSourceMetadata =
        { uri -> readContentMetadata(context.applicationContext.contentResolver, uri) },
    private val contentStreamOpener: (Uri) -> InputStream? =
        { uri -> openAttachmentInputStream(context.applicationContext.contentResolver, uri) },
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
        val reportedMimeType = RemoteAttachmentPolicy.canonicalMimeType(metadata.mimeType)
        val copyLimit = RemoteAttachmentPolicy.maximumBytesFor(reportedMimeType)
            ?: RemoteAttachmentPolicy.maximumSupportedBytes()
        val stagedFile = stagedFile(attachmentId)
        val incomingFile = incomingFile(attachmentId)
        stagedFile.delete()
        incomingFile.delete()
        try {
            val copiedBytes = contentStreamOpener(uri)?.use { input ->
                incomingFile.outputStream().buffered().use { output ->
                    input.copyBoundedTo(output, copyLimit)
                }
            } ?: throw RemoteChatException("Android could not read the selected attachment.")
            val resolvedMimeType = resolveRemoteAttachmentMimeType(reportedMimeType, incomingFile)
            val maximumBytes = RemoteAttachmentPolicy.maximumBytesFor(resolvedMimeType)
                ?: throw IllegalArgumentException("Choose a supported image, video, document, or audio file.")
            require(copiedBytes <= maximumBytes) {
                "The selected attachment exceeds the ${maximumBytes / MEBIBYTE} MB limit."
            }
            val stagedMetadata = if (resolvedMimeType == JPEG_MIME_TYPE) {
                val normalizedByteCount = AndroidRemoteJpegNormalizer().normalize(incomingFile, stagedFile)
                StagedAttachmentMetadata(metadata.displayName, JPEG_MIME_TYPE, normalizedByteCount)
            } else {
                check(incomingFile.renameTo(stagedFile)) {
                    "Android could not preserve the selected attachment."
                }
                StagedAttachmentMetadata(metadata.displayName, resolvedMimeType, copiedBytes)
            }
            val measuredAudioDuration = if (stagedMetadata.mimeType.startsWith("audio/")) {
                audioDurationMillis ?: measureAudioDuration(Uri.fromFile(stagedFile))
            } else {
                require(audioDurationMillis == null) { "Only audio can include a duration." }
                null
            }
            val decision = RemoteAttachmentPolicy.validate(
                displayName = stagedMetadata.displayName,
                mimeType = stagedMetadata.mimeType,
                byteCount = stagedMetadata.byteCount,
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
        } finally {
            incomingFile.delete()
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

    private fun incomingFile(attachmentId: RemoteAttachmentId): File =
        File(stagingDirectory, "${attachmentId.raw}$INCOMING_FILE_SUFFIX")

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
        const val INCOMING_FILE_SUFFIX = ".incoming"
        const val JPEG_MIME_TYPE = "image/jpeg"
        const val STAGING_DIRECTORY_NAME = "remote-attachment-staging"
        const val STAGING_FILE_SUFFIX = ".source"
        const val VOICE_NOTE_MIME_TYPE = "audio/mp4"
    }
}

internal class AndroidRemoteJpegNormalizer {
    fun normalize(
        sourceFile: File,
        targetFile: File,
    ): Long {
        require(sourceFile.isFile) { "The private image copy is unavailable." }
        val bitmap = decodeRemoteImageBitmap(sourceFile, NORMALIZED_IMAGE_EDGE)
        return bitmap.useAsNormalizedJpeg(targetFile)
    }

    private fun Bitmap.useAsNormalizedJpeg(targetFile: File): Long {
        return try {
            var quality = 88
            do {
                targetFile.outputStream().buffered().use { output ->
                    check(compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        "Android could not prepare the selected image."
                    }
                }
                quality -= 8
            } while (targetFile.length() > TARGET_MAXIMUM_IMAGE_BYTES && quality >= 64)
            require(targetFile.length() in 1..MAXIMUM_IMAGE_BYTES) {
                "The selected image is still too large after compression."
            }
            targetFile.length()
        } finally {
            recycle()
        }
    }

    private companion object {
        const val MAXIMUM_IMAGE_BYTES = 15L * 1024L * 1024L
        const val NORMALIZED_IMAGE_EDGE = 2_560
        const val TARGET_MAXIMUM_IMAGE_BYTES = 5L * 1024L * 1024L
    }
}

internal class AndroidRemoteImageThumbnailEncoder {
    fun encode(sourceFile: File): ByteArray {
        require(sourceFile.isFile) { "The private image copy is unavailable." }
        val bitmap = decodeRemoteImageBitmap(sourceFile, THUMBNAIL_EDGE)
        return bitmap.useAsThumbnailBytes()
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

private fun decodeRemoteImageBitmap(
    sourceFile: File,
    maximumEdge: Int,
): Bitmap = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(sourceFile)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val sourceWidth = info.size.width
        val sourceHeight = info.size.height
        require(sourceWidth > 0 && sourceHeight > 0) { "The selected image has invalid dimensions." }
        val scale = minOf(
            1f,
            maximumEdge.toFloat() / maxOf(sourceWidth, sourceHeight).toFloat(),
        )
        decoder.setTargetSize(
            maxOf(1, (sourceWidth * scale).toInt()),
            maxOf(1, (sourceHeight * scale).toInt()),
        )
    }
}.getOrElse { imageDecoderFailure ->
    decodeRemoteImageWithBitmapFactory(sourceFile, maximumEdge)
        ?: throw RemoteChatException("Android could not decode the selected image.", imageDecoderFailure)
}

private fun decodeRemoteImageWithBitmapFactory(
    sourceFile: File,
    maximumEdge: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourceFile.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maximumEdge || bounds.outHeight / sampleSize > maximumEdge) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        sourceFile.path,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

private fun readContentMetadata(
    resolver: ContentResolver,
    uri: Uri,
): RemoteAttachmentSourceMetadata {
    val mimeType = runCatching { resolver.getType(uri) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: GENERIC_BINARY_MIME_TYPE
    val displayName = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.takeIf(Cursor::moveToFirst)?.readDisplayName()
        }
    }.getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "attachment"
    return RemoteAttachmentSourceMetadata(displayName, mimeType)
}

private fun Cursor.readDisplayName(): String {
    val index = getColumnIndex(OpenableColumns.DISPLAY_NAME)
    return if (index >= 0 && !isNull(index)) getString(index) else "attachment"
}

private fun openAttachmentInputStream(
    resolver: ContentResolver,
    uri: Uri,
): InputStream? = runCatching { resolver.openInputStream(uri) }
    .getOrNull()
    ?: runCatching { resolver.openAssetFileDescriptor(uri, "r")?.createInputStream() }.getOrNull()

internal fun resolveRemoteAttachmentMimeType(
    reportedMimeType: String,
    sourceFile: File,
): String {
    val canonicalReportedMimeType = RemoteAttachmentPolicy.canonicalMimeType(reportedMimeType)
    val detectedImageMimeType = detectRemoteImageMimeType(sourceFile)
    if (detectedImageMimeType != null) return detectedImageMimeType
    require(!canonicalReportedMimeType.startsWith("image/")) {
        "Android could not recognize the selected image."
    }
    return canonicalReportedMimeType
}

private fun detectRemoteImageMimeType(sourceFile: File): String? {
    val header = ByteArray(12)
    val headerSize = sourceFile.inputStream().buffered().use { input -> input.read(header) }
    if (headerSize >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }
    if (headerSize >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_FILE_SIGNATURE)) {
        return "image/png"
    }
    if (headerSize >= 6) {
        val gifSignature = header.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (gifSignature == "GIF87a" || gifSignature == "GIF89a") return "image/gif"
    }
    if (
        headerSize >= 12 &&
        header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
    ) {
        return "image/webp"
    }
    return null
}

private const val GENERIC_BINARY_MIME_TYPE = "application/octet-stream"
private val PNG_FILE_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
)
