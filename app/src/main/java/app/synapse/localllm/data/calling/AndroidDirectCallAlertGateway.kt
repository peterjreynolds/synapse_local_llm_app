package app.synapse.localllm.data.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.synapse.localllm.domain.calling.DirectCallAlertGateway
import app.synapse.localllm.domain.calling.DirectCallRingtoneRepository

class AndroidDirectCallAlertGateway(
    context: Context,
    private val ringtoneRepository: DirectCallRingtoneRepository,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : DirectCallAlertGateway {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ringbackGenerator: ToneGenerator? = null
    private var incomingRingtone: Ringtone? = null
    private val ringbackPulse = object : Runnable {
        override fun run() {
            val generator = ringbackGenerator ?: return
            if (!generator.startTone(ToneGenerator.TONE_SUP_RINGTONE, RINGBACK_BURST_MILLIS)) {
                stop()
                return
            }
            mainHandler.postDelayed(this, RINGBACK_CADENCE_MILLIS)
        }
    }
    private val incomingRingtoneTimeout = Runnable(::stop)

    override fun startOutgoingRingback() {
        if (ringbackGenerator != null) return
        stop()
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, RINGBACK_VOLUME_PERCENT)
        }.getOrNull() ?: return
        ringbackGenerator = generator
        ringbackPulse.run()
    }

    override fun startIncomingRingtone(expiresAtMillis: Long) {
        val remainingMillis = (expiresAtMillis - nowEpochMillis())
            .coerceAtMost(MAXIMUM_INCOMING_RING_MILLIS)
        if (remainingMillis <= 0L) return
        mainHandler.removeCallbacks(incomingRingtoneTimeout)
        if (incomingRingtone?.isPlaying == true) {
            mainHandler.postDelayed(incomingRingtoneTimeout, remainingMillis)
            return
        }
        stop()
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtone = listOfNotNull(
            ringtoneRepository.currentSelection().uri?.let(Uri::parse),
            defaultUri,
        ).distinct().firstNotNullOfOrNull(::loadAndStartRingtone) ?: return
        incomingRingtone = ringtone
        mainHandler.postDelayed(incomingRingtoneTimeout, remainingMillis)
    }

    override fun stop() {
        mainHandler.removeCallbacks(ringbackPulse)
        mainHandler.removeCallbacks(incomingRingtoneTimeout)
        ringbackGenerator?.stopTone()
        ringbackGenerator?.release()
        ringbackGenerator = null
        incomingRingtone?.let { ringtone -> runCatching(ringtone::stop) }
        incomingRingtone = null
    }

    private fun loadAndStartRingtone(uri: Uri): Ringtone? {
        val ringtone = runCatching { RingtoneManager.getRingtone(applicationContext, uri) }.getOrNull() ?: return null
        val started = runCatching {
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.isLooping = true
            ringtone.volume = MAXIMUM_RINGTONE_VOLUME
            ringtone.play()
        }.isSuccess
        if (started) return ringtone
        runCatching(ringtone::stop)
        return null
    }

    private companion object {
        const val RINGBACK_VOLUME_PERCENT = 100
        const val RINGBACK_BURST_MILLIS = 2_000
        const val RINGBACK_CADENCE_MILLIS = 6_000L
        const val MAXIMUM_INCOMING_RING_MILLIS = 60_000L
        const val MAXIMUM_RINGTONE_VOLUME = 1.0f
    }
}
