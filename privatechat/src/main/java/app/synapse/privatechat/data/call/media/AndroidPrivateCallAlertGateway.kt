package app.synapse.privatechat.data.call.media

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import app.synapse.privatechat.domain.call.PrivateCallAlertGateway
import java.time.Clock
import java.time.Instant

class AndroidPrivateCallAlertGateway(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
) : PrivateCallAlertGateway {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val notifications = applicationContext.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var mode: Mode? = null
    private var deadlineMillis = 0L
    private val stopAtDeadline = Runnable(::stopOnMain)
    private val pulse =
        object : Runnable {
            override fun run() {
                if (clock.millis() >= deadlineMillis || !canPlayAlert()) {
                    stopOnMain()
                    return
                }
                try {
                    playPulse()
                } catch (_: RuntimeException) {
                    stopOnMain()
                }
            }

            private fun playPulse() {
                when (mode) {
                    Mode.INCOMING -> {
                        // API25 lacks Ringtone.setLooping; restart only after the platform ringtone finishes.
                        if (ringtone?.isPlaying == false) ringtone?.play()
                        handler.postDelayed(this, 500)
                    }
                    Mode.OUTGOING -> {
                        ringback?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 2_000)
                        handler.postDelayed(this, 6_000)
                    }
                    null -> Unit
                }
            }
        }

    override fun startOutgoing(expiresAt: Instant) = onMain { start(Mode.OUTGOING, expiresAt) }

    override fun startIncoming(expiresAt: Instant) = onMain { start(Mode.INCOMING, expiresAt) }

    override fun stop() = onMain(::stopOnMain)

    private fun start(
        requested: Mode,
        expiresAt: Instant,
    ) {
        val remaining = (expiresAt.toEpochMilli() - clock.millis()).coerceAtMost(72_000)
        if (remaining <= 0L) {
            stopOnMain()
            return
        }
        if (mode == requested) return
        stopOnMain()
        if (!canPlayAlert()) return
        mode = requested
        deadlineMillis = clock.millis() + remaining
        try {
            when (requested) {
                Mode.INCOMING -> {
                    ringtone =
                        RingtoneManager
                            .getRingtone(
                                applicationContext,
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                            )?.apply {
                                audioAttributes =
                                    AudioAttributes
                                        .Builder()
                                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build()
                            }
                }
                Mode.OUTGOING -> ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
            }
            pulse.run()
            handler.postDelayed(stopAtDeadline, remaining)
        } catch (_: RuntimeException) {
            // An unavailable sound device never extends ringing or starts capture; call UI remains authoritative.
            stopOnMain()
        }
    }

    private fun stopOnMain() {
        handler.removeCallbacks(pulse)
        handler.removeCallbacks(stopAtDeadline)
        ringtone?.let { current -> runCatching(current::stop) }
        ringtone = null
        ringback?.let { current ->
            runCatching(current::stopTone)
            runCatching(current::release)
        }
        ringback = null
        mode = null
        deadlineMillis = 0
    }

    private fun canPlayAlert(): Boolean =
        audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    private enum class Mode { INCOMING, OUTGOING }
}
