package app.synapse.localllm.ui

import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.remote.CancelRemoteAttachmentCommand
import app.synapse.localllm.domain.remote.DownloadRemoteAttachmentCommand
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAttachmentGateway
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteAttachmentTransferUpdate
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteVoiceNoteRecorder
import app.synapse.localllm.domain.remote.UploadRemoteAttachmentCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class RemoteAttachmentTransferUiState(
    val pendingAttachments: List<RemotePendingAttachmentUi> = emptyList(),
    val downloads: Map<String, RemoteAttachmentDownloadUi> = emptyMap(),
    val isRecordingVoiceNote: Boolean = false,
)

internal class RemoteAttachmentTransferController(
    private val coroutineScope: CoroutineScope,
    private val attachmentGateway: RemoteAttachmentGateway,
    private val voiceNoteRecorder: RemoteVoiceNoteRecorder,
    private val idFactory: SynapseIdFactory,
    private val clearNotice: () -> Unit,
    private val publishFailureMessage: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(RemoteAttachmentTransferUiState())
    val state: StateFlow<RemoteAttachmentTransferUiState> = mutableState.asStateFlow()

    private val uploadJobs = mutableMapOf<RemoteAttachmentId, Job>()
    private val downloadJobs = mutableMapOf<String, Job>()
    private var draftMessageId: RemoteMessageId? = null

    fun addAttachment(
        accountUid: RemoteAccountUid?,
        roomId: RemoteRoomId?,
        sourceUri: String,
        audioDurationMillis: Long? = null,
        isVoiceNote: Boolean = false,
    ) {
        if (state.value.pendingAttachments.size >= MAXIMUM_MESSAGE_ATTACHMENTS) {
            publishFailureMessage("A message can include at most $MAXIMUM_MESSAGE_ATTACHMENTS attachments.")
            return
        }
        if (accountUid == null) {
            publishFailureMessage("Sign in before adding an attachment.")
            return
        }
        if (roomId == null) {
            publishFailureMessage("Open a conversation before adding an attachment.")
            return
        }
        val messageId = draftMessageId ?: RemoteMessageId(idFactory.createChatMessageId().raw).also {
            draftMessageId = it
        }
        val attachmentId = RemoteAttachmentId(idFactory.createAttachmentId().raw)
        uploadJobs[attachmentId] = coroutineScope.launch {
            try {
                val selection = attachmentGateway.inspectSelection(
                    attachmentId = attachmentId,
                    sourceUri = sourceUri,
                    audioDurationMillis = audioDurationMillis,
                    isVoiceNote = isVoiceNote,
                )
                val pending = RemotePendingAttachmentUi(
                    messageId = messageId,
                    selection = selection,
                    state = RemoteAttachmentTransferState.UPLOADING,
                    transferredBytes = 0,
                    uploadedAttachment = null,
                    failureReason = null,
                )
                mutableState.update { current ->
                    current.copy(pendingAttachments = current.pendingAttachments + pending)
                }
                clearNotice()
                collectUpload(accountUid, roomId, pending)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val hasPendingAttachment = state.value.pendingAttachments.any { pending ->
                    pending.selection.attachmentId == attachmentId
                }
                if (!hasPendingAttachment && isVoiceNote) {
                    voiceNoteRecorder.deleteRecording(sourceUri)
                }
                if (hasPendingAttachment) {
                    mutableState.update { current ->
                        current.copy(
                            pendingAttachments = current.pendingAttachments.map { pending ->
                                if (pending.selection.attachmentId == attachmentId) {
                                    pending.copy(
                                        state = RemoteAttachmentTransferState.FAILED,
                                        failureReason = attachmentFailureMessage(exception),
                                    )
                                } else {
                                    pending
                                }
                            },
                        )
                    }
                } else {
                    publishFailureMessage(attachmentFailureMessage(exception))
                }
            } finally {
                uploadJobs.remove(attachmentId)
            }
        }
    }

    fun retryAttachment(
        accountUid: RemoteAccountUid?,
        roomId: RemoteRoomId?,
        attachmentId: RemoteAttachmentId,
    ) {
        val pending = state.value.pendingAttachments.singleOrNull { attachment ->
            attachment.selection.attachmentId == attachmentId
        } ?: return
        if (
            pending.state != RemoteAttachmentTransferState.FAILED ||
            uploadJobs[attachmentId]?.isActive == true ||
            accountUid == null ||
            roomId == null
        ) {
            return
        }
        uploadJobs[attachmentId] = coroutineScope.launch {
            try {
                mutableState.update { current ->
                    current.copy(
                        pendingAttachments = current.pendingAttachments.map { attachment ->
                            if (attachment.selection.attachmentId == attachmentId) {
                                attachment.copy(
                                    state = RemoteAttachmentTransferState.UPLOADING,
                                    transferredBytes = 0,
                                    failureReason = null,
                                )
                            } else {
                                attachment
                            }
                        },
                    )
                }
                collectUpload(accountUid, roomId, pending)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update { current ->
                    current.copy(
                        pendingAttachments = current.pendingAttachments.map { attachment ->
                            if (attachment.selection.attachmentId == attachmentId) {
                                attachment.copy(
                                    state = RemoteAttachmentTransferState.FAILED,
                                    failureReason = attachmentFailureMessage(exception),
                                )
                            } else {
                                attachment
                            }
                        },
                    )
                }
            } finally {
                uploadJobs.remove(attachmentId)
            }
        }
    }

    fun cancelAttachment(
        accountUid: RemoteAccountUid?,
        roomId: RemoteRoomId?,
        attachmentId: RemoteAttachmentId,
    ) {
        val uploadJob = uploadJobs.remove(attachmentId)
        uploadJob?.cancel()
        val pending = state.value.pendingAttachments.singleOrNull { attachment ->
            attachment.selection.attachmentId == attachmentId
        } ?: return
        mutableState.update { current ->
            current.copy(
                pendingAttachments = current.pendingAttachments.filterNot { attachment ->
                    attachment.selection.attachmentId == attachmentId
                },
            )
        }
        coroutineScope.launch {
            uploadJob?.join()
            if (pending.selection.kind == RemoteAttachmentKind.VOICE_NOTE) {
                voiceNoteRecorder.deleteRecording(pending.selection.sourceUri)
            }
            if (accountUid != null && roomId != null) {
                runCatching {
                    attachmentGateway.cancelAttachment(
                        CancelRemoteAttachmentCommand(accountUid, roomId, pending.messageId, attachmentId),
                    )
                }
            }
        }
    }

    fun downloadAttachment(
        accountUid: RemoteAccountUid?,
        message: RemoteCachedMessage,
        attachmentId: RemoteAttachmentId,
        thumbnail: Boolean,
    ) {
        val attachment = message.attachments.singleOrNull { candidate -> candidate.attachmentId == attachmentId }
            ?: return
        if (accountUid == null) return
        val key = remoteAttachmentDownloadKey(attachmentId, thumbnail)
        if (state.value.downloads[key]?.localUri != null || downloadJobs[key]?.isActive == true) return
        mutableState.update { current ->
            current.copy(
                downloads = current.downloads +
                    (key to RemoteAttachmentDownloadUi(
                        attachmentId = attachmentId,
                        thumbnail = thumbnail,
                        transferredBytes = 0,
                        totalBytes = attachment.byteCount,
                        localUri = null,
                        failureReason = null,
                    )),
            )
        }
        downloadJobs[key] = coroutineScope.launch {
            val command = DownloadRemoteAttachmentCommand(accountUid, message.roomId, attachment, thumbnail)
            try {
                attachmentGateway.downloadAttachment(command).collect { update ->
                    mutableState.update { current ->
                        val currentDownload = current.downloads[key]
                        val next = when (update) {
                            is RemoteAttachmentTransferUpdate.Progress -> RemoteAttachmentDownloadUi(
                                attachmentId = attachmentId,
                                thumbnail = thumbnail,
                                transferredBytes = update.transferredBytes,
                                totalBytes = update.totalBytes,
                                localUri = currentDownload?.localUri,
                                failureReason = null,
                            )

                            is RemoteAttachmentTransferUpdate.Downloaded -> RemoteAttachmentDownloadUi(
                                attachmentId = attachmentId,
                                thumbnail = thumbnail,
                                transferredBytes = attachment.byteCount,
                                totalBytes = attachment.byteCount,
                                localUri = update.localUri,
                                failureReason = null,
                            )

                            is RemoteAttachmentTransferUpdate.Uploaded -> currentDownload ?: return@update current
                        }
                        current.copy(downloads = current.downloads + (key to next))
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update { current ->
                    current.copy(
                        downloads = current.downloads +
                            (key to RemoteAttachmentDownloadUi(
                                attachmentId = attachmentId,
                                thumbnail = thumbnail,
                                transferredBytes = 0,
                                totalBytes = attachment.byteCount,
                                localUri = null,
                                failureReason = attachmentFailureMessage(exception),
                            )),
                    )
                }
            } finally {
                downloadJobs.remove(key)
            }
        }
    }

    fun cancelDownload(
        attachmentId: RemoteAttachmentId,
        thumbnail: Boolean,
    ) {
        val key = remoteAttachmentDownloadKey(attachmentId, thumbnail)
        downloadJobs.remove(key)?.cancel()
        mutableState.update { current ->
            val download = current.downloads[key] ?: return@update current
            current.copy(
                downloads = current.downloads +
                    (key to download.copy(
                        transferredBytes = 0,
                        localUri = null,
                        failureReason = "Download cancelled.",
                    )),
            )
        }
    }

    fun startVoiceNoteRecording(roomId: RemoteRoomId?) {
        if (state.value.isRecordingVoiceNote) return
        if (roomId == null) {
            publishFailureMessage("Open a conversation before recording a voice note.")
            return
        }
        try {
            voiceNoteRecorder.startRecording()
            mutableState.update { current -> current.copy(isRecordingVoiceNote = true) }
            clearNotice()
        } catch (exception: Exception) {
            publishFailureMessage(exception.message ?: "Android could not start voice-note recording.")
        }
    }

    fun finishVoiceNoteRecording(
        accountUid: RemoteAccountUid?,
        roomId: RemoteRoomId?,
    ) {
        if (!state.value.isRecordingVoiceNote) return
        try {
            val recording = voiceNoteRecorder.stopRecording()
            mutableState.update { current -> current.copy(isRecordingVoiceNote = false) }
            addAttachment(
                accountUid = accountUid,
                roomId = roomId,
                sourceUri = recording.sourceUri,
                audioDurationMillis = recording.durationMillis,
                isVoiceNote = true,
            )
        } catch (exception: Exception) {
            mutableState.update { current -> current.copy(isRecordingVoiceNote = false) }
            publishFailureMessage(exception.message ?: "Android could not finish the voice note.")
        }
    }

    fun cancelVoiceNoteRecording() {
        voiceNoteRecorder.cancelRecording()
        mutableState.update { current -> current.copy(isRecordingVoiceNote = false) }
    }

    fun readyAttachments(): List<RemoteCachedAttachment> =
        state.value.pendingAttachments.mapNotNull(RemotePendingAttachmentUi::uploadedAttachment)

    fun messageIdForSend(fallback: () -> RemoteMessageId): RemoteMessageId = draftMessageId ?: fallback()

    fun completeSend() {
        draftMessageId = null
        uploadJobs.clear()
        mutableState.update { current -> current.copy(pendingAttachments = emptyList()) }
    }

    fun reset(
        accountUid: RemoteAccountUid?,
        roomId: RemoteRoomId?,
    ) {
        voiceNoteRecorder.cancelRecording()
        val pendingAttachments = state.value.pendingAttachments
        val uploadsToCancel = uploadJobs.toMap()
        uploadJobs.values.forEach(Job::cancel)
        uploadJobs.clear()
        downloadJobs.values.forEach(Job::cancel)
        downloadJobs.clear()
        draftMessageId = null
        mutableState.value = RemoteAttachmentTransferUiState()
        pendingAttachments.forEach { pending ->
            coroutineScope.launch {
                uploadsToCancel[pending.selection.attachmentId]?.join()
                if (pending.selection.kind == RemoteAttachmentKind.VOICE_NOTE) {
                    voiceNoteRecorder.deleteRecording(pending.selection.sourceUri)
                }
                if (accountUid != null && roomId != null) {
                    runCatching {
                        attachmentGateway.cancelAttachment(
                            CancelRemoteAttachmentCommand(
                                accountUid = accountUid,
                                roomId = roomId,
                                messageId = pending.messageId,
                                attachmentId = pending.selection.attachmentId,
                            ),
                        )
                    }
                }
            }
        }
    }

    suspend fun clearAccountCache(accountUid: RemoteAccountUid) {
        attachmentGateway.clearAccountCache(accountUid)
    }

    private suspend fun collectUpload(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        pending: RemotePendingAttachmentUi,
    ) {
        attachmentGateway.uploadAttachment(
            UploadRemoteAttachmentCommand(
                accountUid = accountUid,
                roomId = roomId,
                messageId = pending.messageId,
                selection = pending.selection,
            ),
        ).collect { update ->
            mutableState.update { current ->
                current.copy(
                    pendingAttachments = current.pendingAttachments.map { attachment ->
                        if (attachment.selection.attachmentId != pending.selection.attachmentId) {
                            attachment
                        } else {
                            when (update) {
                                is RemoteAttachmentTransferUpdate.Progress -> attachment.copy(
                                    state = RemoteAttachmentTransferState.UPLOADING,
                                    transferredBytes = update.transferredBytes,
                                    failureReason = null,
                                )

                                is RemoteAttachmentTransferUpdate.Uploaded -> attachment.copy(
                                    state = RemoteAttachmentTransferState.READY,
                                    transferredBytes = attachment.selection.byteCount,
                                    uploadedAttachment = update.attachment,
                                    failureReason = null,
                                )

                                is RemoteAttachmentTransferUpdate.Downloaded -> attachment
                            }
                        }
                    },
                )
            }
            if (
                update is RemoteAttachmentTransferUpdate.Uploaded &&
                pending.selection.kind == RemoteAttachmentKind.VOICE_NOTE
            ) {
                voiceNoteRecorder.deleteRecording(pending.selection.sourceUri)
            }
        }
    }

    private fun attachmentFailureMessage(exception: Exception): String =
        (exception as? RemoteChatException)?.userMessage
            ?: exception.message
            ?: "Attachment transfer failed."

    private companion object {
        const val MAXIMUM_MESSAGE_ATTACHMENTS = 8
    }
}
