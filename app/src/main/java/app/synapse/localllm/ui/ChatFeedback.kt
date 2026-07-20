package app.synapse.localllm.ui

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Immutable
internal data class ChatFeedbackPreferences(
    val soundsEnabled: Boolean,
    val hapticsEnabled: Boolean,
    val reducedMotionEnabled: Boolean,
)

internal enum class ChatSoundCue(
    val tone: Int,
    val durationMillis: Int,
    val volumePercent: Int,
) {
    SENT(ToneGenerator.TONE_PROP_ACK, 42, 12),
    INCOMING(ToneGenerator.TONE_PROP_BEEP2, 55, 9),
    REACTION(ToneGenerator.TONE_PROP_BEEP, 35, 8),
    UPLOAD_COMPLETE(ToneGenerator.TONE_PROP_ACK, 40, 8),
    CALL_CONNECTED(ToneGenerator.TONE_PROP_PROMPT, 70, 12),
}

internal class AndroidChatFeedbackController(context: Context) {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
    private val releaseHandler = Handler(Looper.getMainLooper())

    fun play(cue: ChatSoundCue, soundsEnabled: Boolean) {
        if (!soundsEnabled || !systemAllowsChatSound()) return
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_SYSTEM, cue.volumePercent)
        }.getOrNull() ?: return
        if (!generator.startTone(cue.tone, cue.durationMillis)) {
            generator.release()
            return
        }
        releaseHandler.postDelayed(
            { generator.release() },
            cue.durationMillis.toLong() + RELEASE_GRACE_MILLIS,
        )
    }

    private fun systemAllowsChatSound(): Boolean = runCatching {
        val soundEffectsEnabled = Settings.System.getInt(
            applicationContext.contentResolver,
            Settings.System.SOUND_EFFECTS_ENABLED,
            1,
        ) == 1
        soundEffectsEnabled &&
            audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM) > 0 &&
            notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)

    private companion object {
        const val RELEASE_GRACE_MILLIS = 60L
    }
}

@Composable
internal fun rememberChatFeedbackController(): AndroidChatFeedbackController {
    val context = LocalContext.current
    return remember(context) { AndroidChatFeedbackController(context) }
}
