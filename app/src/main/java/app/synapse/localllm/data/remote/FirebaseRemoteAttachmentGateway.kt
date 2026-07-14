package app.synapse.localllm.data.remote

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import app.synapse.localllm.domain.remote.CancelRemoteAttachmentCommand
import app.synapse.localllm.domain.remote.DownloadRemoteAttachmentCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAttachmentGateway
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteAttachmentPolicy
import app.synapse.localllm.domain.remote.RemoteAttachmentSelection
import app.synapse.localllm.domain.remote.RemoteAttachmentTransferUpdate
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDownloadedAttachment
import app.synapse.localllm.domain.remote.UploadRemoteAttachmentCommand
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FirebaseRemoteAttachmentGateway(
    context: Context,
    private val firebaseAuth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val storage: FirebaseStorage,
    private val sessionController: RemoteAccountSessionController,
) : RemoteAttachmentGateway {
    private val applicationContext = context.applicationContext
    private val cacheRoot = File(applicationContext.cacheDir, CACHE_DIRECTORY_NAME)

    override suspend fun inspectSelection(
        attachmentId: RemoteAttachmentId,
        sourceUri: String,
        audioDurationMillis: Long?,
        isVoiceNote: Boolean,
    ): RemoteAttachmentSelection {
        val uri = trustedSourceUri(sourceUri, allowVoiceNoteFile = isVoiceNote)
        val metadata = readLocalMetadata(uri)
        val mimeType = metadata.mimeType
        val measuredAudioDuration = if (mimeType.startsWith("audio/")) {
            audioDurationMillis ?: measureAudioDuration(uri)
        } else {
            require(audioDurationMillis == null && !isVoiceNote) { "Only audio can be sent as a voice note." }
            null
        }
        val decision = RemoteAttachmentPolicy.validate(
            displayName = metadata.displayName,
            mimeType = mimeType,
            byteCount = metadata.byteCount,
            audioDurationMillis = measuredAudioDuration,
            isVoiceNote = isVoiceNote,
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

    override fun uploadAttachment(command: UploadRemoteAttachmentCommand): Flow<RemoteAttachmentTransferUpdate> =
        channelFlow {
            requireAuthenticatedUid(command.accountUid)
            val selection = command.selection
            val receipt = prepareAttachment(command)
            when (receipt.status) {
                AttachmentUploadStatus.READY -> {
                    send(
                        RemoteAttachmentTransferUpdate.Uploaded(
                            attachmentId = selection.attachmentId,
                            attachment = receipt.toCachedAttachment(selection),
                        ),
                    )
                    return@channelFlow
                }

                AttachmentUploadStatus.ATTACHED ->
                    throw RemoteChatException("The attachment was already sent.")

                AttachmentUploadStatus.CANCELLED,
                AttachmentUploadStatus.CLEANED,
                -> throw RemoteChatException("The attachment upload expired. Add the file again.")

                AttachmentUploadStatus.PENDING -> Unit
            }
            val contentReference = storage.reference.child(receipt.contentObjectPath)
            var activeUpload: UploadTask? = null
            try {
                val metadata = uploadMetadata(command, "content", selection.mimeType)
                val contentTask = contentReference.putFile(
                    trustedSourceUri(
                        selection.sourceUri,
                        allowVoiceNoteFile = selection.kind == RemoteAttachmentKind.VOICE_NOTE,
                    ),
                    metadata,
                )
                activeUpload = contentTask
                contentTask.addOnProgressListener { snapshot ->
                    trySend(
                        RemoteAttachmentTransferUpdate.Progress(
                            attachmentId = selection.attachmentId,
                            transferredBytes = snapshot.bytesTransferred,
                            totalBytes = selection.byteCount,
                        ),
                    )
                }
                contentTask.await()
                if (selection.kind == RemoteAttachmentKind.IMAGE) {
                    val thumbnailBytes = createThumbnail(
                        trustedSourceUri(selection.sourceUri, allowVoiceNoteFile = false),
                    )
                    val thumbnailReference = storage.reference.child(
                        requireNotNull(receipt.thumbnailObjectPath) { "Image upload receipt has no thumbnail path." },
                    )
                    val thumbnailTask = thumbnailReference.putBytes(
                        thumbnailBytes,
                        uploadMetadata(command, "thumbnail", "image/jpeg"),
                    )
                    activeUpload = thumbnailTask
                    thumbnailTask.await()
                }
                finalizeAttachment(command)
                send(
                    RemoteAttachmentTransferUpdate.Uploaded(
                        attachmentId = selection.attachmentId,
                        attachment = receipt.toCachedAttachment(selection),
                    ),
                )
            } catch (exception: CancellationException) {
                activeUpload?.cancel()
                withContext(NonCancellable) { runCatching { cancelAttachment(command.toCancelCommand()) } }
                throw exception
            } catch (exception: Exception) {
                if (exception is RemoteChatException) throw exception
                throw exception.toRemoteChatFailure("upload the attachment")
            }
        }

    override suspend fun cancelAttachment(command: CancelRemoteAttachmentCommand) {
        requireAuthenticatedUid(command.accountUid)
        callAttachmentFunction("cancelRemoteAttachment", command)
    }

    override fun downloadAttachment(command: DownloadRemoteAttachmentCommand): Flow<RemoteAttachmentTransferUpdate> =
        callbackFlow {
            try {
                requireAuthenticatedUid(command.accountUid)
                findCachedAttachment(command)?.let { cached ->
                    trySend(
                        RemoteAttachmentTransferUpdate.Downloaded(
                            attachmentId = cached.attachmentId,
                            localUri = cached.localUri,
                            thumbnail = cached.thumbnail,
                        ),
                    )
                    close()
                    return@callbackFlow
                }
                val targetFile = cacheFile(command)
                targetFile.parentFile?.mkdirs()
                val partialFile = File(targetFile.parentFile, "${targetFile.name}.part")
                partialFile.delete()
                val objectPath = command.objectPath()
                val task = storage.reference.child(objectPath).getFile(partialFile)
                task.addOnProgressListener { snapshot ->
                    trySend(
                        RemoteAttachmentTransferUpdate.Progress(
                            attachmentId = command.attachment.attachmentId,
                            transferredBytes = snapshot.bytesTransferred,
                            totalBytes = snapshot.totalByteCount,
                        ),
                    )
                }
                task.addOnSuccessListener {
                    val validSize = if (command.thumbnail) {
                        partialFile.length() in 1..MAXIMUM_THUMBNAIL_BYTES
                    } else {
                        partialFile.length() == command.attachment.byteCount
                    }
                    if (!validSize || !partialFile.renameTo(targetFile)) {
                        partialFile.delete()
                        close(RemoteChatException("The downloaded attachment failed validation."))
                    } else {
                        trimCache()
                        trySend(
                            RemoteAttachmentTransferUpdate.Downloaded(
                                attachmentId = command.attachment.attachmentId,
                                localUri = Uri.fromFile(targetFile).toString(),
                                thumbnail = command.thumbnail,
                            ),
                        )
                        close()
                    }
                }
                task.addOnFailureListener { exception ->
                    partialFile.delete()
                    close(exception.toRemoteChatFailure("download the attachment"))
                }
                awaitClose {
                    if (!task.isComplete) task.cancel()
                }
            } catch (exception: Exception) {
                close(if (exception is RemoteChatException) exception else exception.toRemoteChatFailure("download the attachment"))
            }
        }

    override suspend fun findCachedAttachment(
        command: DownloadRemoteAttachmentCommand,
    ): RemoteDownloadedAttachment? {
        val targetFile = cacheFile(command)
        val validSize = targetFile.isFile && if (command.thumbnail) {
            targetFile.length() in 1..MAXIMUM_THUMBNAIL_BYTES
        } else {
            targetFile.length() == command.attachment.byteCount
        }
        if (!validSize) {
            targetFile.delete()
            return null
        }
        targetFile.setLastModified(System.currentTimeMillis())
        return RemoteDownloadedAttachment(
            attachmentId = command.attachment.attachmentId,
            localUri = Uri.fromFile(targetFile).toString(),
            thumbnail = command.thumbnail,
        )
    }

    override suspend fun clearAccountCache(accountUid: RemoteAccountUid) {
        cacheDirectory(accountUid).deleteRecursively()
    }

    private suspend fun prepareAttachment(command: UploadRemoteAttachmentCommand): AttachmentReceipt {
        val selection = command.selection
        val result = functions.getHttpsCallable("prepareRemoteAttachment").call(
            mapOf(
                "attachmentId" to selection.attachmentId.raw,
                "byteCount" to selection.byteCount,
                "displayName" to selection.displayName,
                "durationMillis" to selection.durationMillis,
                "kind" to selection.kind.name,
                "messageId" to command.messageId.raw,
                "mimeType" to selection.mimeType,
                "roomId" to command.roomId.raw,
            ),
        ).await().data.requireAttachmentReceipt(command)
        return result
    }

    private suspend fun finalizeAttachment(command: UploadRemoteAttachmentCommand) {
        callAttachmentFunction("finalizeRemoteAttachment", command.toCancelCommand())
    }

    private suspend fun callAttachmentFunction(
        functionName: String,
        command: CancelRemoteAttachmentCommand,
    ) {
        functions.getHttpsCallable(functionName).call(
            mapOf(
                "attachmentId" to command.attachmentId.raw,
                "messageId" to command.messageId.raw,
                "roomId" to command.roomId.raw,
            ),
        ).await()
    }

    private fun uploadMetadata(
        command: UploadRemoteAttachmentCommand,
        variant: String,
        contentType: String,
    ): StorageMetadata = StorageMetadata.Builder()
        .setContentType(contentType)
        .setCustomMetadata("attachmentId", command.selection.attachmentId.raw)
        .setCustomMetadata("messageId", command.messageId.raw)
        .setCustomMetadata("ownerUid", command.accountUid.raw)
        .setCustomMetadata("roomId", command.roomId.raw)
        .setCustomMetadata("variant", variant)
        .build()

    private fun readLocalMetadata(uri: Uri): LocalAttachmentMetadata {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            require(file.isFile) { "The recorded voice note is unavailable." }
            return LocalAttachmentMetadata(
                displayName = file.name,
                mimeType = "audio/mp4",
                byteCount = file.length(),
            )
        }
        val resolver = applicationContext.contentResolver
        val mimeType = resolver.getType(uri)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Android did not provide a safe attachment type.")
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?: throw RemoteChatException("Android could not inspect the selected attachment.")
        cursor.use {
            require(it.moveToFirst()) { "The selected attachment has no metadata." }
            val displayName = it.readString(OpenableColumns.DISPLAY_NAME)
            val byteCount = it.readLong(OpenableColumns.SIZE)
            return LocalAttachmentMetadata(displayName, mimeType, byteCount)
        }
    }

    private fun Cursor.readString(columnName: String): String {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else "attachment"
    }

    private fun Cursor.readLong(columnName: String): Long {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else -1L
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

    private fun createThumbnail(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw RemoteChatException("Android could not read the selected image.")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected image is invalid." }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > THUMBNAIL_EDGE || bounds.outHeight / sampleSize > THUMBNAIL_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw RemoteChatException("Android could not decode the selected image.")
        return bitmap.useAsThumbnailBytes()
    }

    private fun Bitmap.useAsThumbnailBytes(): ByteArray {
        return try {
            val output = ByteArrayOutputStream()
            var quality = 82
            do {
                output.reset()
                check(compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Android could not encode the thumbnail." }
                quality -= 10
            } while (output.size() > MAXIMUM_THUMBNAIL_BYTES && quality >= 32)
            require(output.size() in 1..MAXIMUM_THUMBNAIL_BYTES.toInt()) { "The image thumbnail is too large." }
            output.toByteArray()
        } finally {
            recycle()
        }
    }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        sessionController.requireActiveToken(accountUid)
        if (firebaseAuth.currentUser?.uid != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }

    private fun cacheFile(command: DownloadRemoteAttachmentCommand): File {
        val variant = if (command.thumbnail) "thumbnail" else "content"
        return File(cacheDirectory(command.accountUid), "${command.attachment.attachmentId.raw}-$variant.cache")
    }

    private fun cacheDirectory(accountUid: RemoteAccountUid): File =
        File(cacheRoot, accountUid.raw.sha256Hex())

    private fun trimCache() {
        val files = cacheRoot.walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(CACHE_FILE_SUFFIX) }
            .sortedBy(File::lastModified)
            .toList()
        var totalBytes = files.sumOf(File::length)
        files.forEach { file ->
            if (totalBytes <= MAXIMUM_CACHE_BYTES) return
            val length = file.length()
            if (file.delete()) totalBytes -= length
        }
    }

    private fun DownloadRemoteAttachmentCommand.objectPath(): String =
        if (thumbnail) requireNotNull(attachment.thumbnailObjectPath) else attachment.contentObjectPath

    private fun UploadRemoteAttachmentCommand.toCancelCommand(): CancelRemoteAttachmentCommand =
        CancelRemoteAttachmentCommand(accountUid, roomId, messageId, selection.attachmentId)

    private fun trustedSourceUri(
        sourceUri: String,
        allowVoiceNoteFile: Boolean,
    ): Uri = sourceUri.toUri().also { uri ->
        require(uri.scheme == "content" || uri.scheme == "file") {
            "Attachment source must be a local Android URI."
        }
        if (uri.scheme == "file") {
            val sourceFile = File(requireNotNull(uri.path)).canonicalFile
            val voiceNoteDirectory = File(
                applicationContext.cacheDir,
                REMOTE_VOICE_NOTE_CACHE_DIRECTORY,
            ).canonicalFile
            require(allowVoiceNoteFile && sourceFile.isFile && sourceFile.parentFile == voiceNoteDirectory) {
                "Only app-recorded voice notes may use file URIs."
            }
        }
    }

    private data class LocalAttachmentMetadata(
        val displayName: String,
        val mimeType: String,
        val byteCount: Long,
    )

    private companion object {
        const val CACHE_DIRECTORY_NAME = "remote-attachments"
        const val CACHE_FILE_SUFFIX = ".cache"
        const val MAXIMUM_CACHE_BYTES = 100L * 1024L * 1024L
        const val MAXIMUM_THUMBNAIL_BYTES = 256L * 1024L
        const val THUMBNAIL_EDGE = 512
    }
}

private data class AttachmentReceipt(
    val contentObjectPath: String,
    val status: AttachmentUploadStatus,
    val thumbnailObjectPath: String?,
) {
    fun toCachedAttachment(selection: RemoteAttachmentSelection) = RemoteCachedAttachment(
        attachmentId = selection.attachmentId,
        displayName = selection.displayName,
        mimeType = selection.mimeType,
        byteCount = selection.byteCount,
        kind = selection.kind,
        durationMillis = selection.durationMillis,
        contentObjectPath = contentObjectPath,
        thumbnailObjectPath = thumbnailObjectPath,
    )
}

private enum class AttachmentUploadStatus {
    ATTACHED,
    CANCELLED,
    CLEANED,
    PENDING,
    READY,
}

private fun Any?.requireAttachmentReceipt(command: UploadRemoteAttachmentCommand): AttachmentReceipt {
    val value = this as? Map<*, *> ?: throw RemoteChatException("Firebase returned an invalid attachment receipt.")
    val contentObjectPath = value["contentObjectPath"] as? String
        ?: throw RemoteChatException("Firebase returned an invalid attachment content path.")
    val thumbnailObjectPath = value["thumbnailObjectPath"] as? String
    val status = (value["status"] as? String)?.let { rawStatus ->
        runCatching { AttachmentUploadStatus.valueOf(rawStatus) }.getOrNull()
    } ?: throw RemoteChatException("Firebase returned an invalid attachment upload state.")
    val expectedBasePath =
        "roomAttachments/${command.roomId.raw}/${command.messageId.raw}/${command.selection.attachmentId.raw}"
    if (
        contentObjectPath != "$expectedBasePath/content" ||
        thumbnailObjectPath != if (command.selection.kind == RemoteAttachmentKind.IMAGE) {
            "$expectedBasePath/thumbnail"
        } else {
            null
        }
    ) {
        throw RemoteChatException("Firebase returned an inconsistent attachment object path.")
    }
    return AttachmentReceipt(contentObjectPath, status, thumbnailObjectPath)
}

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
