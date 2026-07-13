package app.synapse.localllm.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.chat.ParticipantRecord
import app.synapse.localllm.domain.chat.RoomKind

internal fun RoomKind.toDisplayLabel(): String =
    when (this) {
        RoomKind.DIRECT -> "Direct"
        RoomKind.GROUP -> "Group"
        RoomKind.AI_CHAT -> "AI chat"
    }

@Composable
internal fun ParticipantAvatar(
    participant: ParticipantRecord,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val avatarColor = Color(participant.avatarColorArgb ?: participant.id.raw.toStableAvatarColor())
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = avatarColor,
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = participant.displayName
                    .trim()
                    .firstOrNull()
                    ?.uppercaseChar()
                    ?.toString()
                    ?: "?",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun String.toStableAvatarColor(): Long {
    val palette = longArrayOf(
        0xFF2E7D32,
        0xFF1565C0,
        0xFF6A1B9A,
        0xFFAD4E00,
        0xFF00695C,
        0xFF455A64,
    )
    return palette[(hashCode() and Int.MAX_VALUE) % palette.size]
}
