package app.synapse.localllm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModeStateMachineTest {
    private val stateMachine = VoiceModeStateMachine()

    @Test
    fun startListeningRequestsRecognition() {
        val state = stateMachine.startListening(VoiceModeUiState())

        assertEquals(VoiceModeStatus.LISTENING, state.status)
        assertEquals(1L, state.recognitionRequestId)
        assertTrue(state.isActive)
    }

    @Test
    fun transcriptProcessingMovesToProcessingWithoutRequestingPlayback() {
        val listeningState = stateMachine.startListening(VoiceModeUiState())

        val processingState = stateMachine.processTranscript(listeningState)

        assertEquals(VoiceModeStatus.PROCESSING, processingState.status)
        assertEquals(listeningState.recognitionRequestId, processingState.recognitionRequestId)
        assertEquals(0L, processingState.speechRequestId)
        assertTrue(processingState.isActive)
    }

    @Test
    fun assistantReplyRequestsSpeechAndFinishReturnsToListening() {
        val processingState = stateMachine.processTranscript(
            stateMachine.startListening(VoiceModeUiState()),
        )

        val speakingState = stateMachine.speakAssistantReply(
            currentState = processingState,
            assistantText = "Done.",
        )
        val listeningAgainState = stateMachine.finishSpeaking(speakingState)

        assertEquals(VoiceModeStatus.SPEAKING, speakingState.status)
        assertEquals(1L, speakingState.speechRequestId)
        assertEquals("Done.", speakingState.speechText)
        assertEquals(VoiceModeStatus.LISTENING, listeningAgainState.status)
        assertEquals(2L, listeningAgainState.recognitionRequestId)
        assertEquals("", listeningAgainState.speechText)
    }

    @Test
    fun failureStopsTheLoopWithoutSchedulingAnotherRecognition() {
        val listeningState = stateMachine.startListening(VoiceModeUiState())

        val failedState = stateMachine.fail("No speech was recognized.")

        assertEquals(VoiceModeStatus.ERROR, failedState.status)
        assertEquals("No speech was recognized.", failedState.errorMessage)
        assertEquals(0L, failedState.recognitionRequestId)
        assertFalse(failedState.isActive)
        assertEquals(1L, listeningState.recognitionRequestId)
    }

    @Test
    fun stopReturnsToOffState() {
        val speakingState = stateMachine.speakAssistantReply(
            currentState = stateMachine.processTranscript(
                stateMachine.startListening(VoiceModeUiState()),
            ),
            assistantText = "Done.",
        )

        val stoppedState = stateMachine.stop()

        assertEquals(VoiceModeUiState(), stoppedState)
        assertEquals(VoiceModeStatus.SPEAKING, speakingState.status)
    }
}
