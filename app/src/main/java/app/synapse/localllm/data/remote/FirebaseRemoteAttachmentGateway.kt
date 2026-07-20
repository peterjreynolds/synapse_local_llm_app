package app.synapse.localllm.data.remote

import android.content.Context
import android.net.Uri
import app.synapse.localllm.domain.remote.CancelRemoteAttachmentCommand
import app.synapse.localllm.domain.remote.DownloadRemoteAttachmentCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAttachmentGateway
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
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
    private val selectionStager = AndroidRemoteAttachmentSelectionStager(applicationContext)
    private val thumbnailEncoder = AndroidRemoteVisualThumbnailEncoder()

    override suspend fun inspectSelection(
        attachmentId: RemoteAttachmentId,
        sourceUri: String,
        audioDurationMillis: Long?,
        isVoiceNote: Boolean,
    ): RemoteAttachmentSelection = selectionStager.stageSelection(
        attachmentId = attachmentId,
        sourceUri = sourceUri,
        audioDurationMillis = audioDurationMillis,
        isVoiceNote = isVoiceNote,
    )

    override fun uploadAttachment(command: UploadRemoteAttachmentCommand): Flow<RemoteAttachmentTransferUpdate> =
        channelFlow {
            requireAuthenticatedUid(command.accountUid)
            val selection = command.selection
            val receipt = prepareAttachment(command)
            when (receipt.status) {
                AttachmentUploadStatus.READY -> {
                    selectionStager.release(selection.attachmentId)
                    send(
                        RemoteAttachmentTransferUpdate.Uploaded(
                            attachmentId = selection.attachmentId,
                            attachment = receipt.toCachedAttachment(selection),
                        ),
                    )
                    return@channelFlow
                }

                AttachmentUploadStatus.ATTACHED -> {
                    selectionStager.release(selection.attachmentId)
                    throw RemoteChatException("The attachment was already sent.")
                }

                AttachmentUploadStatus.CANCELLED,
                AttachmentUploadStatus.CLEANED,
                -> {
                    selectionStager.release(selection.attachmentId)
                    throw RemoteChatException("The attachment upload expired. Add the file again.")
                }

                AttachmentUploadStatus.PENDING -> Unit
            }
            val contentReference = storage.reference.child(receipt.contentObjectPath)
            var activeUpload: UploadTask? = null
            try {
                val sourceUri = selectionStager.requireUploadSource(selection)
                val thumbnailBytes = if (selection.kind.hasVisualThumbnail()) {
                    thumbnailEncoder.encode(File(requireNotNull(sourceUri.path)), selection.kind)
                } else {
                    null
                }
                val metadata = uploadMetadata(command, "content", selection.mimeType)
                val contentTask = contentReference.putFile(
                    sourceUri,
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
                if (thumbnailBytes != null) {
                    val thumbnailReference = storage.reference.child(
                        requireNotNull(receipt.thumbnailObjectPath) { "Visual upload receipt has no thumbnail path." },
                    )
                    val thumbnailTask = thumbnailReference.putBytes(
                        thumbnailBytes,
                        uploadMetadata(command, "thumbnail", "image/jpeg"),
                    )
                    activeUpload = thumbnailTask
                    thumbnailTask.await()
                }
                finalizeAttachment(command)
                selectionStager.release(selection.attachmentId)
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
        try {
            callAttachmentFunction("cancelRemoteAttachment", command)
        } finally {
            selectionStager.release(command.attachmentId)
        }
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

    private companion object {
        const val CACHE_DIRECTORY_NAME = "remote-attachments"
        const val CACHE_FILE_SUFFIX = ".cache"
        const val MAXIMUM_CACHE_BYTES = 100L * 1024L * 1024L
        const val MAXIMUM_THUMBNAIL_BYTES = 256L * 1024L
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
        thumbnailObjectPath != if (command.selection.kind.hasVisualThumbnail()) {
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

private fun RemoteAttachmentKind.hasVisualThumbnail(): Boolean =
    this == RemoteAttachmentKind.IMAGE || this == RemoteAttachmentKind.VIDEO
