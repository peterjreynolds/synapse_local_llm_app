package app.synapse.localllm.data.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.synapse.localllm.domain.remote.RemoteVoiceNoteRecorder
import app.synapse.localllm.domain.remote.RemoteVoiceNoteRecordingReceipt
import java.io.File
import java.util.UUID

class AndroidRemoteVoiceNoteRecorder(context: Context) : RemoteVoiceNoteRecorder {
    private val applicationContext = context.applicationContext
    private val recordingDirectory = File(applicationContext.cacheDir, REMOTE_VOICE_NOTE_CACHE_DIRECTORY)
    private var activeRecording: ActiveRecording? = null

    override fun startRecording() {
        check(activeRecording == null) { "A voice note is already recording." }
        check(
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "Microphone permission is required to record a voice note." }
        recordingDirectory.mkdirs()
        val outputFile = File(recordingDirectory, "voice-${UUID.randomUUID()}.m4a")
        val recorder = createRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.prepare()
            recorder.start()
            activeRecording = ActiveRecording(recorder, outputFile, System.nanoTime())
        } catch (exception: Exception) {
            recorder.release()
            outputFile.delete()
            throw IllegalStateException("Android could not start voice-note recording.", exception)
        }
    }

    override fun stopRecording(): RemoteVoiceNoteRecordingReceipt {
        val recording = checkNotNull(activeRecording) { "No voice note is recording." }
        activeRecording = null
        try {
            recording.recorder.stop()
        } catch (exception: RuntimeException) {
            recording.outputFile.delete()
            throw IllegalStateException("The voice note was too short or could not be finalized.", exception)
        } finally {
            recording.recorder.release()
        }
        val durationMillis = (System.nanoTime() - recording.startedAtNanos) / 1_000_000L
        check(durationMillis in MINIMUM_DURATION_MILLIS..MAXIMUM_DURATION_MILLIS) {
            recording.outputFile.delete()
            "Voice notes must be between 1 second and 60 minutes."
        }
        check(recording.outputFile.isFile && recording.outputFile.length() > 0L) {
            recording.outputFile.delete()
            "Android did not create the voice-note audio file."
        }
        return RemoteVoiceNoteRecordingReceipt(
            sourceUri = Uri.fromFile(recording.outputFile).toString(),
            durationMillis = durationMillis,
        )
    }

    override fun cancelRecording() {
        val recording = activeRecording ?: return
        activeRecording = null
        runCatching { recording.recorder.stop() }
        recording.recorder.release()
        recording.outputFile.delete()
    }

    override fun deleteRecording(sourceUri: String) {
        val uri = sourceUri.toUri()
        val file = uri.path?.let(::File) ?: return
        if (uri.scheme == "file" && file.parentFile?.canonicalFile == recordingDirectory.canonicalFile) {
            file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(applicationContext)
        } else {
            // Scope: API 29-30 recorder construction. Owner: this Android adapter. Remove with minSdk 31.
            MediaRecorder()
        }

    private data class ActiveRecording(
        val recorder: MediaRecorder,
        val outputFile: File,
        val startedAtNanos: Long,
    )

    private companion object {
        const val AUDIO_BIT_RATE = 96_000
        const val AUDIO_SAMPLE_RATE = 44_100
        const val MAXIMUM_DURATION_MILLIS = 60 * 60 * 1_000L
        const val MINIMUM_DURATION_MILLIS = 1_000L
    }
}

internal const val REMOTE_VOICE_NOTE_CACHE_DIRECTORY = "remote-voice-notes"
