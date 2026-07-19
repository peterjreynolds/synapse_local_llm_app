package app.synapse.localllm.domain.calling

import java.time.Instant

enum class DirectCallRingtoneSource {
    PHONE_DEFAULT,
    PHONE_RINGTONE,
    AUDIO_FILE,
}

data class DirectCallRingtoneSelection(
    val source: DirectCallRingtoneSource = DirectCallRingtoneSource.PHONE_DEFAULT,
    val uri: String? = null,
    val displayName: String = PHONE_DEFAULT_RINGTONE_DISPLAY_NAME,
)

data class DirectCallRingtoneMutationReceipt(
    val selection: DirectCallRingtoneSelection,
    val persistedAt: Instant,
)

interface DirectCallRingtoneRepository {
    fun currentSelection(): DirectCallRingtoneSelection

    suspend fun usePhoneDefaultRingtone(): DirectCallRingtoneMutationReceipt

    suspend fun selectPhoneRingtone(uri: String): DirectCallRingtoneMutationReceipt

    suspend fun selectAudioFile(uri: String): DirectCallRingtoneMutationReceipt
}

const val PHONE_DEFAULT_RINGTONE_DISPLAY_NAME = "Phone default ringtone"
