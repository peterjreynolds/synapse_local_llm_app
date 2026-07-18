package app.synapse.localllm.data.remote

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import app.synapse.localllm.domain.remote.RemoteAttachmentPolicy
import app.synapse.localllm.domain.remote.RemoteChatException
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RemoteImageExportReceipt(
    val displayName: String,
    val destinationUri: String,
    val exportedBytes: Long,
    val exportedAt: Instant,
)

internal class AndroidDownloadedImageExporter(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun exportToPictures(
        localUri: String,
        displayName: String,
        mimeType: String,
    ): RemoteImageExportReceipt = withContext(Dispatchers.IO) {
        val source = validateDownloadedImageExportSource(
            cacheDirectory = applicationContext.cacheDir,
            localUri = localUri,
            displayName = displayName,
            mimeType = mimeType,
        )
        val canonicalMimeType = RemoteAttachmentPolicy.canonicalMimeType(mimeType)
        val destinationValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, canonicalMimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Synapse")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = applicationContext.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val destination = resolver.insert(collection, destinationValues)
            ?: throw RemoteChatException("Android could not create the saved image.")
        try {
            val exportedBytes = source.inputStream().buffered().use { input ->
                resolver.openOutputStream(destination, "w")?.buffered()?.use { output ->
                    input.copyTo(output)
                } ?: throw RemoteChatException("Android could not write the saved image.")
            }
            check(exportedBytes == source.length()) {
                "Android did not save the complete image."
            }
            check(
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1,
            ) { "Android did not publish the saved image." }
            RemoteImageExportReceipt(
                displayName = displayName,
                destinationUri = destination.toString(),
                exportedBytes = exportedBytes,
                exportedAt = Instant.now(),
            )
        } catch (exception: Exception) {
            resolver.delete(destination, null, null)
            if (exception is RemoteChatException) throw exception
            throw RemoteChatException("Android could not save the image to Pictures.", exception)
        }
    }
}

internal fun validateDownloadedImageExportSource(
    cacheDirectory: File,
    localUri: String,
    displayName: String,
    mimeType: String,
): File {
    val canonicalMimeType = RemoteAttachmentPolicy.canonicalMimeType(mimeType)
    require(canonicalMimeType in DOWNLOADABLE_IMAGE_MIME_TYPES) {
        "Only downloaded images can be saved to Pictures."
    }
    require(
        displayName.isNotBlank() &&
            displayName == displayName.substringAfterLast('/').substringAfterLast('\\') &&
            displayName.length <= MAXIMUM_EXPORTED_IMAGE_NAME_LENGTH,
    ) { "The downloaded image filename is invalid." }
    val uri = localUri.toUri()
    require(uri.scheme == "file") { "The downloaded image is not available in private storage." }
    val source = File(requireNotNull(uri.path)).canonicalFile
    val canonicalCacheDirectory = cacheDirectory.canonicalFile
    require(source.isFile && source.toPath().startsWith(canonicalCacheDirectory.toPath())) {
        "The downloaded image is not available in private storage."
    }
    return source
}

private val DOWNLOADABLE_IMAGE_MIME_TYPES = setOf(
    "image/gif",
    "image/jpeg",
    "image/png",
    "image/webp",
)
private const val MAXIMUM_EXPORTED_IMAGE_NAME_LENGTH = 120
