package app.synapse.localllm.data.remote

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
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
        assertLegacyPicturesWritePermission()
        val destinationValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, canonicalMimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Synapse")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                putLegacyDestinationPath(createLegacyDestinationFile(displayName))
            }
        }
        val resolver = applicationContext.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                check(
                    resolver.update(
                        destination,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null,
                    ) == 1,
                ) { "Android did not publish the saved image." }
            }
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

    private fun assertLegacyPicturesWritePermission() {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw RemoteChatException("Android 9 needs storage permission to save images to Pictures.")
        }
    }

    @Suppress("DEPRECATION")
    private fun ContentValues.putLegacyDestinationPath(destinationFile: File) {
        put(MediaStore.Images.Media.DATA, destinationFile.absolutePath)
    }

    @Suppress("DEPRECATION")
    private fun createLegacyDestinationFile(displayName: String): File {
        val destinationDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Synapse",
        )
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            throw RemoteChatException("Android could not create Pictures/Synapse.")
        }
        return (0..MAXIMUM_LEGACY_FILENAME_SUFFIX).asSequence()
            .map { suffix ->
                if (suffix == 0) {
                    File(destinationDirectory, displayName)
                } else {
                    File(
                        destinationDirectory,
                        "${displayName.substringBeforeLast('.', displayName)}-$suffix" +
                            displayName.substringAfterLast('.', "").let { extension ->
                                if (extension.isBlank()) "" else ".$extension"
                            },
                    )
                }
            }
            .firstOrNull { destinationFile -> !destinationFile.exists() }
            ?: throw RemoteChatException("Pictures/Synapse has too many files with this name.")
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
private const val MAXIMUM_LEGACY_FILENAME_SUFFIX = 9_999
