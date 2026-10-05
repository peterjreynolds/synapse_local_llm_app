package app.synapse.privatechat.data.call.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

internal class AndroidPrivateCallAudioRoute(
    context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var modernRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerEnabled = false
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onFocusLost?.invoke()
        }

    @Volatile private var onFocusLost: (() -> Unit)? = null

    fun acquire(onFocusLost: () -> Unit) {
        this.onFocusLost = onFocusLost
        val outcome =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request =
                    AudioFocusRequest
                        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        ).setOnAudioFocusChangeListener(focusListener)
                        .build()
                modernRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        check(outcome == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio is busy with another call." }
        focusHeld = true
        previousMode = audioManager.mode
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            previousSpeakerEnabled = audioManager.isSpeakerphoneOn
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerEnabled(false)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferred = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == preferred }
            if (device != null) check(audioManager.setCommunicationDevice(device)) { "Audio route is unavailable." }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    fun release() {
        onFocusLost = null
        if (!focusHeld) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            modernRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        modernRequest = null
        focusHeld = false
        audioManager.mode = previousMode
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousSpeakerEnabled
        }
    }
}
