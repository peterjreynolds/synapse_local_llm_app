package app.synapse.localllm.domain.appearance

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

enum class ChatBubblePalette {
    SYNAPSE,
    OCEAN,
    VIOLET,
    ROSE,
    AMBER,
    MONOCHROME,
}

enum class ChatBackground {
    PITCH_BLACK,
    GRAPHITE_SOLID,
    DEEP_NAVY_SOLID,
    FOREST_SOLID,
    PLUM_SOLID,
    AURORA_FLOW,
    MIDNIGHT_CONSTELLATION,
    GRAPHITE_WAVES,
    FOREST_MIST,
    OCEAN_CAUSTICS,
    VIOLET_NEBULA,
    EMBER_GEOMETRY,
    MOONLIT_TOPOGRAPHY,
    SAGE_LINEN,
    CYBER_RAIN,
}

data class ChatAppearance(
    val bubblePalette: ChatBubblePalette = ChatBubblePalette.SYNAPSE,
    val background: ChatBackground = ChatBackground.PITCH_BLACK,
    val messageScale: Float = DEFAULT_CHAT_MESSAGE_SCALE,
) {
    init {
        require(messageScale.isFinite() && messageScale in MINIMUM_CHAT_MESSAGE_SCALE..MAXIMUM_CHAT_MESSAGE_SCALE) {
            "Chat message scale is outside the supported range."
        }
    }
}

data class ChatAppearanceMutationReceipt(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val appearance: ChatAppearance,
    val persistedAt: Instant,
)

data class ChatBubblePaletteMutationReceipt(
    val accountUid: RemoteAccountUid,
    val bubblePalette: ChatBubblePalette,
    val persistedAt: Instant,
)

interface ChatAppearanceRepository {
    fun observeAccountBubblePalette(accountUid: RemoteAccountUid): Flow<ChatBubblePalette>

    fun observeAppearance(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<ChatAppearance>

    suspend fun saveAppearance(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        appearance: ChatAppearance,
    ): ChatAppearanceMutationReceipt

    suspend fun resetAppearance(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): ChatAppearanceMutationReceipt

    suspend fun saveAccountBubblePalette(
        accountUid: RemoteAccountUid,
        bubblePalette: ChatBubblePalette,
    ): ChatBubblePaletteMutationReceipt
}

fun clampChatMessageScale(messageScale: Float): Float = when {
    !messageScale.isFinite() -> DEFAULT_CHAT_MESSAGE_SCALE
    else -> messageScale.coerceIn(MINIMUM_CHAT_MESSAGE_SCALE, MAXIMUM_CHAT_MESSAGE_SCALE)
}

const val DEFAULT_CHAT_MESSAGE_SCALE = 1f
const val MAXIMUM_CHAT_MESSAGE_SCALE = 1.35f
const val MINIMUM_CHAT_MESSAGE_SCALE = 0.78f
