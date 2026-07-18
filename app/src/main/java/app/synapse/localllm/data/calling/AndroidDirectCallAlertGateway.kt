package app.synapse.localllm.data.calling

import android.media.AudioManager
import android.media.ToneGenerator
import app.synapse.localllm.domain.calling.DirectCallAlertGateway

class AndroidDirectCallAlertGateway : DirectCallAlertGateway {
    private var ringbackGenerator: ToneGenerator? = null

    override fun startOutgoingRingback() {
        if (ringbackGenerator != null) return
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME_PERCENT)
        }.getOrNull() ?: return
        if (generator.startTone(ToneGenerator.TONE_SUP_RINGTONE)) {
            ringbackGenerator = generator
        } else {
            generator.release()
        }
    }

    override fun stop() {
        ringbackGenerator?.stopTone()
        ringbackGenerator?.release()
        ringbackGenerator = null
    }

    private companion object {
        const val RINGBACK_VOLUME_PERCENT = 70
    }
}
