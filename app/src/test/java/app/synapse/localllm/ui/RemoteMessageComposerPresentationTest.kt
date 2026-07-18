package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteAttachmentSelection
import app.synapse.localllm.domain.remote.RemoteMessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMessageComposerPresentationTest {
    @Test
    fun compactComposerSwapsVoiceAndSendActionsByState() {
        assertEquals(
            RemoteComposerPrimaryAction.RECORD_VOICE,
            remoteComposerPrimaryAction(canSend = false, isRecordingVoiceNote = false),
        )
        assertEquals(
            RemoteComposerPrimaryAction.SEND,
            remoteComposerPrimaryAction(canSend = true, isRecordingVoiceNote = false),
        )
        assertEquals(
            RemoteComposerPrimaryAction.STOP_RECORDING,
            remoteComposerPrimaryAction(canSend = false, isRecordingVoiceNote = true),
        )
    }

    @Test
    fun voiceMessagesHideGeneratedFilenamesAndClampProgress() {
        val pending = RemotePendingAttachmentUi(
            messageId = RemoteMessageId("message-123e4567-e89b-42d3-a456-426614174000"),
            selection = RemoteAttachmentSelection(
                attachmentId = RemoteAttachmentId("attachment-123e4567-e89b-42d3-a456-426614174000"),
                sourceUri = "file:///private/voice-123.m4a",
                displayName = "voice-123.m4a",
                mimeType = "audio/mp4",
                byteCount = 1_024,
                kind = RemoteAttachmentKind.VOICE_NOTE,
                durationMillis = 2_000,
            ),
            state = RemoteAttachmentTransferState.READY,
            transferredBytes = 0,
            uploadedAttachment = null,
            failureReason = null,
        )

        assertEquals("Voice message", remotePendingAttachmentTitle(pending))
        assertEquals(0.5f, remoteVoiceProgressFraction(1_000, 2_000))
        assertEquals(1f, remoteVoiceProgressFraction(3_000, 2_000))
        assertNull(remoteVoiceProgressFraction(1_000, 0))
    }
}
