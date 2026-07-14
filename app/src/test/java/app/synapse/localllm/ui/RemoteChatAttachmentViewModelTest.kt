package app.synapse.localllm.ui

import androidx.lifecycle.viewModelScope
import app.synapse.localllm.application.RemoteChatSessionSynchronizer
import app.synapse.localllm.application.RemoteRoomVisibilityTracker
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.remote.CancelRemoteAttachmentCommand
import app.synapse.localllm.domain.remote.DownloadRemoteAttachmentCommand
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAttachmentGateway
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteAttachmentSelection
import app.synapse.localllm.domain.remote.RemoteAttachmentTransferUpdate
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDownloadedAttachment
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteVoiceNoteRecorder
import app.synapse.localllm.domain.remote.RemoteVoiceNoteRecordingReceipt
import app.synapse.localllm.domain.remote.UploadRemoteAttachmentCommand
import app.synapse.localllm.domain.time.SynapseClock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatAttachmentViewModelTest {
    @Test
    fun `upload failure can retry and account switching clears pending attachment state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            RemoteAuthenticationState.SignedIn(activeAccount("peter-uid", "peter")),
        )
        val attachmentGateway = RecordingAttachmentGateway()
        val viewModel = createViewModel(authenticationState, attachmentGateway)
        try {
            runCurrent()
            viewModel.selectRoom(ROOM_ID)
            viewModel.addAttachment("content://synapse/report.pdf")
            runCurrent()

            val failed = viewModel.uiState.value.pendingAttachments.single()
            assertEquals(RemoteAttachmentTransferState.FAILED, failed.state)
            assertEquals(1, attachmentGateway.uploadAttempts)

            viewModel.retryAttachment(failed.selection.attachmentId)
            runCurrent()
            assertEquals(RemoteAttachmentTransferState.READY, viewModel.uiState.value.pendingAttachments.single().state)
            assertEquals(2, attachmentGateway.uploadAttempts)

            authenticationState.value = RemoteAuthenticationState.SignedIn(activeAccount("trish-uid", "trish"))
            runCurrent()
            assertTrue(viewModel.uiState.value.pendingAttachments.isEmpty())
            assertTrue(RemoteAccountUid("peter-uid") in attachmentGateway.clearedAccounts)
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `microphone denial leaves an actionable notice without starting recording`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(RemoteAuthenticationState.SignedOut)
        val viewModel = createViewModel(authenticationState, RecordingAttachmentGateway())
        try {
            runCurrent()
            viewModel.reportVoiceNotePermissionDenied()

            assertEquals(
                "Microphone permission was denied. Grant it to record voice notes.",
                viewModel.uiState.value.notice,
            )
            assertEquals(false, viewModel.uiState.value.isRecordingVoiceNote)
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed voice note stays available for retry and is deleted after upload`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            RemoteAuthenticationState.SignedIn(activeAccount("peter-uid", "peter")),
        )
        val attachmentGateway = RecordingAttachmentGateway()
        val voiceNoteRecorder = RecordingVoiceNoteRecorder()
        val viewModel = createViewModel(authenticationState, attachmentGateway, voiceNoteRecorder)
        try {
            runCurrent()
            viewModel.selectRoom(ROOM_ID)
            viewModel.addAttachment(VOICE_NOTE_URI, audioDurationMillis = 2_000, isVoiceNote = true)
            runCurrent()

            val failed = viewModel.uiState.value.pendingAttachments.single()
            assertEquals(RemoteAttachmentTransferState.FAILED, failed.state)
            assertTrue(voiceNoteRecorder.deletedSourceUris.isEmpty())

            viewModel.retryAttachment(failed.selection.attachmentId)
            runCurrent()

            assertEquals(RemoteAttachmentTransferState.READY, viewModel.uiState.value.pendingAttachments.single().state)
            assertEquals(listOf(VOICE_NOTE_URI), voiceNoteRecorder.deletedSourceUris)
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `active attachment download can be cancelled and retried`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            RemoteAuthenticationState.SignedIn(activeAccount("peter-uid", "peter")),
        )
        val attachmentGateway = RecordingAttachmentGateway()
        val viewModel = createViewModel(authenticationState, attachmentGateway)
        val attachmentId = RemoteAttachmentId("attachment-123e4567-e89b-42d3-a456-426614174000")
        val attachment = RemoteCachedAttachment(
            attachmentId = attachmentId,
            displayName = "report.pdf",
            mimeType = "application/pdf",
            byteCount = 1_024,
            kind = RemoteAttachmentKind.DOCUMENT,
            durationMillis = null,
            contentObjectPath = "roomAttachments/${ROOM_ID.raw}/message-1/${attachmentId.raw}/content",
            thumbnailObjectPath = null,
        )
        val message = mockk<RemoteCachedMessage>()
        every { message.attachments } returns listOf(attachment)
        every { message.roomId } returns ROOM_ID
        try {
            runCurrent()
            viewModel.selectRoom(ROOM_ID)
            viewModel.downloadAttachment(message, attachmentId, thumbnail = false)
            runCurrent()

            assertEquals(null, viewModel.uiState.value.attachmentDownloads.values.single().failureReason)

            viewModel.cancelAttachmentDownload(attachmentId, thumbnail = false)
            runCurrent()

            assertEquals("Download cancelled.", viewModel.uiState.value.attachmentDownloads.values.single().failureReason)
            assertEquals(1, attachmentGateway.cancelledDownloads)
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        authenticationState: MutableStateFlow<RemoteAuthenticationState>,
        attachmentGateway: RemoteAttachmentGateway,
        voiceNoteRecorder: RemoteVoiceNoteRecorder = mockk(relaxed = true),
    ): RemoteChatViewModel {
        val authenticationGateway = mockk<RemoteAuthenticationGateway>(relaxed = true) {
            every { this@mockk.authenticationState } returns authenticationState
        }
        val cacheRepository = mockk<RemoteChatCacheRepository>(relaxed = true) {
            every { observeProfiles() } returns emptyFlow()
            every { observeRooms() } returns emptyFlow()
            every { observeMessages(any()) } returns emptyFlow()
            every { observePendingOutbox() } returns emptyFlow()
            every { observeDraft(any()) } returns flowOf(null)
        }
        val sessionSynchronizer = mockk<RemoteChatSessionSynchronizer>(relaxed = true)
        coEvery { sessionSynchronizer.synchronize(any(), any(), any()) } coAnswers { awaitCancellation() }
        return RemoteChatViewModel(
            authenticationGateway = authenticationGateway,
            attachmentGateway = attachmentGateway,
            directoryGateway = mockk(relaxed = true),
            conversationGateway = mockk(relaxed = true),
            remoteAiParticipantGateway = NoOpRemoteAiParticipantGateway,
            deviceRegistrationGateway = mockk(relaxed = true),
            cacheRepository = cacheRepository,
            sessionSynchronizer = sessionSynchronizer,
            roomVisibilityTracker = RemoteRoomVisibilityTracker(),
            remoteLocalAiResponseHost = IdleRemoteLocalAiResponseHost,
            voiceNoteRecorder = voiceNoteRecorder,
            idFactory = SynapseIdFactory(),
            clock = object : SynapseClock {
                override fun now(): Instant = Instant.EPOCH
            },
        )
    }

    private fun activeAccount(
        uid: String,
        username: String,
    ) = RemoteAuthenticatedAccount(
        accountUid = RemoteAccountUid(uid),
        usernameNormalized = username,
        role = RemoteAccountRole.USER,
        state = RemoteAccountState.ACTIVE,
        mustChangePassword = false,
    )

    private class RecordingAttachmentGateway : RemoteAttachmentGateway {
        var uploadAttempts = 0
        var cancelledDownloads = 0
        val clearedAccounts = mutableListOf<RemoteAccountUid>()

        override suspend fun inspectSelection(
            attachmentId: RemoteAttachmentId,
            sourceUri: String,
            audioDurationMillis: Long?,
            isVoiceNote: Boolean,
        ) = if (isVoiceNote) {
            RemoteAttachmentSelection(
                attachmentId = attachmentId,
                sourceUri = sourceUri,
                displayName = "voice-note.m4a",
                mimeType = "audio/mp4",
                byteCount = 1_024,
                kind = RemoteAttachmentKind.VOICE_NOTE,
                durationMillis = audioDurationMillis,
            )
        } else {
            RemoteAttachmentSelection(
                attachmentId = attachmentId,
                sourceUri = sourceUri,
                displayName = "report.pdf",
                mimeType = "application/pdf",
                byteCount = 1_024,
                kind = RemoteAttachmentKind.DOCUMENT,
                durationMillis = null,
            )
        }

        override fun uploadAttachment(
            command: UploadRemoteAttachmentCommand,
        ): Flow<RemoteAttachmentTransferUpdate> = flow {
            uploadAttempts += 1
            if (uploadAttempts == 1) throw RemoteChatException("Upload interrupted.")
            val attachment = RemoteCachedAttachment(
                attachmentId = command.selection.attachmentId,
                displayName = command.selection.displayName,
                mimeType = command.selection.mimeType,
                byteCount = command.selection.byteCount,
                kind = command.selection.kind,
                durationMillis = command.selection.durationMillis,
                contentObjectPath =
                    "roomAttachments/${command.roomId.raw}/${command.messageId.raw}/${command.selection.attachmentId.raw}/content",
                thumbnailObjectPath = null,
            )
            emit(RemoteAttachmentTransferUpdate.Uploaded(command.selection.attachmentId, attachment))
        }

        override suspend fun cancelAttachment(command: CancelRemoteAttachmentCommand) = Unit

        override fun downloadAttachment(
            command: DownloadRemoteAttachmentCommand,
        ): Flow<RemoteAttachmentTransferUpdate> = flow {
            emit(
                RemoteAttachmentTransferUpdate.Progress(
                    attachmentId = command.attachment.attachmentId,
                    transferredBytes = 512,
                    totalBytes = command.attachment.byteCount,
                ),
            )
            try {
                awaitCancellation()
            } finally {
                cancelledDownloads += 1
            }
        }

        override suspend fun findCachedAttachment(
            command: DownloadRemoteAttachmentCommand,
        ): RemoteDownloadedAttachment? = null

        override suspend fun clearAccountCache(accountUid: RemoteAccountUid) {
            clearedAccounts += accountUid
        }
    }

    private class RecordingVoiceNoteRecorder : RemoteVoiceNoteRecorder {
        val deletedSourceUris = mutableListOf<String>()

        override fun startRecording() = Unit

        override fun stopRecording() = RemoteVoiceNoteRecordingReceipt(VOICE_NOTE_URI, 2_000)

        override fun cancelRecording() = Unit

        override fun deleteRecording(sourceUri: String) {
            deletedSourceUris += sourceUri
        }
    }

    private companion object {
        val ROOM_ID = RemoteRoomId("group_${"a".repeat(32)}")
        const val VOICE_NOTE_URI = "file:///private/voice-note.m4a"
    }
}
