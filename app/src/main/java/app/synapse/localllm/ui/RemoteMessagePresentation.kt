package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteMessageId
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal enum class RemoteMessageGroupPosition {
    SINGLE,
    START,
    MIDDLE,
    END,
}

internal data class RemoteMessageGroupPresentation(
    val position: RemoteMessageGroupPosition,
    val showSenderName: Boolean,
    val showAvatar: Boolean,
    val beginsNewVisualGroup: Boolean,
)

internal fun remoteMessageGroupPresentation(
    messages: List<RemoteCachedMessage>,
    index: Int,
    currentAccountUid: String?,
    showGroupIdentities: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): RemoteMessageGroupPresentation {
    val message = messages[index]
    val continuesPrevious = messages.getOrNull(index - 1)
        ?.let { previous -> remoteMessagesBelongToSameVisualGroup(previous, message, zoneId) }
        ?: false
    val continuesNext = messages.getOrNull(index + 1)
        ?.let { next -> remoteMessagesBelongToSameVisualGroup(message, next, zoneId) }
        ?: false
    val position = when {
        !continuesPrevious && !continuesNext -> RemoteMessageGroupPosition.SINGLE
        !continuesPrevious -> RemoteMessageGroupPosition.START
        !continuesNext -> RemoteMessageGroupPosition.END
        else -> RemoteMessageGroupPosition.MIDDLE
    }
    val isIncoming = message.senderUid.raw != currentAccountUid
    return RemoteMessageGroupPresentation(
        position = position,
        showSenderName = showGroupIdentities && isIncoming && !continuesPrevious,
        showAvatar = showGroupIdentities && isIncoming && !continuesNext,
        beginsNewVisualGroup = !continuesPrevious,
    )
}

internal fun remoteMessagesBelongToSameVisualGroup(
    earlier: RemoteCachedMessage,
    later: RemoteCachedMessage,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (earlier.senderUid != later.senderUid || earlier.authorKind != later.authorKind) return false
    if (earlier.deletedAt != null || later.deletedAt != null) return false
    if (earlier.replyToMessageId != null || later.replyToMessageId != null) return false
    val earlierInstant = earlier.displayInstant()
    val laterInstant = later.displayInstant()
    if (earlierInstant.atZone(zoneId).toLocalDate() != laterInstant.atZone(zoneId).toLocalDate()) return false
    val separation = Duration.between(earlierInstant, laterInstant)
    return !separation.isNegative && separation <= MAXIMUM_VISUAL_GROUP_SEPARATION
}

internal fun remoteUnreadDividerMessageId(
    messages: List<RemoteCachedMessage>,
    currentAccountUid: String?,
    lastReadAt: Instant?,
): RemoteMessageId? {
    if (lastReadAt == null) return null
    return messages.firstOrNull { message ->
        message.senderUid.raw != currentAccountUid && message.displayInstant().isAfter(lastReadAt)
    }?.messageId
}

internal fun remoteParticipantColorIndex(
    roomId: String,
    senderUid: String,
    paletteSize: Int,
): Int {
    require(paletteSize > 0) { "Participant color palette cannot be empty." }
    return Math.floorMod("$roomId:$senderUid".hashCode(), paletteSize)
}

private val MAXIMUM_VISUAL_GROUP_SEPARATION: Duration = Duration.ofMinutes(5)
