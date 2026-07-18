package app.synapse.localllm.ui

import org.junit.Assert.assertEquals
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
}
