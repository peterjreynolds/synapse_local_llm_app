package app.synapse.localllm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiChatControlsPresentationTest {
    @Test
    fun `compact top bar retains every advanced destination`() {
        assertEquals(
            listOf(
                LocalAiTopBarAction.START_LOCAL_AI,
                LocalAiTopBarAction.LIBRARY,
                LocalAiTopBarAction.MEMORY,
                LocalAiTopBarAction.SETTINGS,
            ),
            localAiTopBarActions(hasRuntimeIssue = false),
        )
        assertEquals(
            listOf(
                LocalAiTopBarAction.START_LOCAL_AI,
                LocalAiTopBarAction.RUNTIME_DIAGNOSTICS,
                LocalAiTopBarAction.LIBRARY,
                LocalAiTopBarAction.MEMORY,
                LocalAiTopBarAction.SETTINGS,
            ),
            localAiTopBarActions(hasRuntimeIssue = true),
        )
    }

    @Test
    fun `composer menu keeps secondary controls out of the default row`() {
        assertEquals(
            listOf(
                LocalAiComposerMenuAction.ATTACH,
                LocalAiComposerMenuAction.TOGGLE_HANDS_FREE_VOICE,
            ),
            localAiComposerMenuActions(synapseIsActive = false),
        )
        assertEquals(
            listOf(
                LocalAiComposerMenuAction.ATTACH,
                LocalAiComposerMenuAction.MENTION_SYNAPSE,
                LocalAiComposerMenuAction.TOGGLE_HANDS_FREE_VOICE,
            ),
            localAiComposerMenuActions(synapseIsActive = true),
        )
    }

    @Test
    fun `voice mode status uses short conversational labels`() {
        assertEquals("Hands-free voice off", VoiceModeUiState().toDisplayLabel())
        assertEquals(
            "Listening for you",
            VoiceModeUiState(status = VoiceModeStatus.LISTENING).toDisplayLabel(),
        )
        assertEquals(
            "Microphone unavailable",
            VoiceModeUiState(
                status = VoiceModeStatus.ERROR,
                errorMessage = "Microphone unavailable",
            ).toDisplayLabel(),
        )
        assertEquals("Stop", VoiceModeUiState(status = VoiceModeStatus.SPEAKING).toActionLabel())
    }

    @Test
    fun `composer send action needs content unless it is stopping a response`() {
        assertFalse(localAiComposerCanSend("", attachmentCount = 0, isSending = false))
        assertTrue(localAiComposerCanSend("Hello", attachmentCount = 0, isSending = false))
        assertTrue(localAiComposerCanSend("", attachmentCount = 1, isSending = false))
        assertTrue(localAiComposerCanSend("", attachmentCount = 0, isSending = true))
    }
}
