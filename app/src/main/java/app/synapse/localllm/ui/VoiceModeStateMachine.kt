package app.synapse.localllm.ui

class VoiceModeStateMachine {
    fun startListening(currentState: VoiceModeUiState): VoiceModeUiState =
        currentState.copy(
            status = VoiceModeStatus.LISTENING,
            recognitionRequestId = currentState.recognitionRequestId + 1,
            speechText = "",
            errorMessage = null,
        )

    fun stop(): VoiceModeUiState = VoiceModeUiState()

    fun processTranscript(currentState: VoiceModeUiState): VoiceModeUiState =
        currentState.copy(
            status = VoiceModeStatus.PROCESSING,
            speechText = "",
            errorMessage = null,
        )

    fun speakAssistantReply(
        currentState: VoiceModeUiState,
        assistantText: String,
    ): VoiceModeUiState =
        currentState.copy(
            status = VoiceModeStatus.SPEAKING,
            speechRequestId = currentState.speechRequestId + 1,
            speechText = assistantText,
            errorMessage = null,
        )

    fun finishSpeaking(currentState: VoiceModeUiState): VoiceModeUiState =
        currentState.copy(
            status = VoiceModeStatus.LISTENING,
            recognitionRequestId = currentState.recognitionRequestId + 1,
            speechText = "",
            errorMessage = null,
        )

    fun fail(reason: String): VoiceModeUiState =
        VoiceModeUiState(
            status = VoiceModeStatus.ERROR,
            errorMessage = reason,
        )
}
