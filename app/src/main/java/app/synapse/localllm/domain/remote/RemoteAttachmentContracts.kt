package app.synapse.localllm.domain.remote

import kotlinx.coroutines.flow.Flow

@JvmInline
value class RemoteAttachmentId(val raw: String) {
    init {
        require(REMOTE_ATTACHMENT_ID_PATTERN.matches(raw)) { "Remote attachment ID must be an opaque random identifier." }
    }
}

enum class RemoteAttachmentKind {
    IMAGE,
    DOCUMENT,
    AUDIO,
    VOICE_NOTE,
}

data class RemoteAttachmentSelection(
    val attachmentId: RemoteAttachmentId,
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val kind: RemoteAttachmentKind,
    val durationMillis: Long?,
)

data class RemoteCachedAttachment(
    val attachmentId: RemoteAttachmentId,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val kind: RemoteAttachmentKind,
    val durationMillis: Long?,
    val contentObjectPath: String,
    val thumbnailObjectPath: String?,
) {
    init {
        require(byteCount > 0L) { "Remote attachment size must be positive." }
        require(displayName.isNotBlank()) { "Remote attachment display name cannot be blank." }
        require(contentObjectPath.endsWith("/${attachmentId.raw}/content")) {
            "Remote attachment content path is inconsistent."
        }
        require(
            (kind == RemoteAttachmentKind.IMAGE) == (thumbnailObjectPath != null),
        ) { "Only image attachments require a thumbnail path." }
        thumbnailObjectPath?.let { path ->
            require(path.endsWith("/${attachmentId.raw}/thumbnail")) {
                "Remote attachment thumbnail path is inconsistent."
            }
        }
        require(
            (kind == RemoteAttachmentKind.AUDIO || kind == RemoteAttachmentKind.VOICE_NOTE) ==
                (durationMillis != null),
        ) { "Audio attachments require a duration." }
    }
}

data class UploadRemoteAttachmentCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val selection: RemoteAttachmentSelection,
)

data class CancelRemoteAttachmentCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val attachmentId: RemoteAttachmentId,
)

data class DownloadRemoteAttachmentCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val attachment: RemoteCachedAttachment,
    val thumbnail: Boolean,
)

sealed interface RemoteAttachmentTransferUpdate {
    val attachmentId: RemoteAttachmentId

    data class Progress(
        override val attachmentId: RemoteAttachmentId,
        val transferredBytes: Long,
        val totalBytes: Long,
    ) : RemoteAttachmentTransferUpdate

    data class Uploaded(
        override val attachmentId: RemoteAttachmentId,
        val attachment: RemoteCachedAttachment,
    ) : RemoteAttachmentTransferUpdate

    data class Downloaded(
        override val attachmentId: RemoteAttachmentId,
        val localUri: String,
        val thumbnail: Boolean,
    ) : RemoteAttachmentTransferUpdate
}

data class RemoteDownloadedAttachment(
    val attachmentId: RemoteAttachmentId,
    val localUri: String,
    val thumbnail: Boolean,
)

interface RemoteAttachmentGateway {
    suspend fun inspectSelection(
        attachmentId: RemoteAttachmentId,
        sourceUri: String,
        audioDurationMillis: Long? = null,
        isVoiceNote: Boolean = false,
    ): RemoteAttachmentSelection

    fun uploadAttachment(command: UploadRemoteAttachmentCommand): Flow<RemoteAttachmentTransferUpdate>

    suspend fun cancelAttachment(command: CancelRemoteAttachmentCommand)

    fun downloadAttachment(command: DownloadRemoteAttachmentCommand): Flow<RemoteAttachmentTransferUpdate>

    suspend fun findCachedAttachment(command: DownloadRemoteAttachmentCommand): RemoteDownloadedAttachment?

    suspend fun clearAccountCache(accountUid: RemoteAccountUid)
}

private val REMOTE_ATTACHMENT_ID_PATTERN =
    Regex("^attachment-[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$")
