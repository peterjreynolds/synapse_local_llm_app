package app.synapse.localllm.domain.remote

data class RemoteVoiceNoteRecordingReceipt(
    val sourceUri: String,
    val durationMillis: Long,
)

interface RemoteVoiceNoteRecorder {
    fun startRecording()

    fun stopRecording(): RemoteVoiceNoteRecordingReceipt

    fun cancelRecording()

    fun deleteRecording(sourceUri: String)
}
