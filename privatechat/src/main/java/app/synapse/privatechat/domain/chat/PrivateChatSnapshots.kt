package app.synapse.privatechat.domain.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import java.time.Instant

enum class PrivateRoomKind {
    DIRECT,
    GROUP,
}

enum class PrivateMessageOwnership {
    CURRENT_ACCOUNT,
    OTHER_PARTICIPANT,
}

enum class PrivateRoomArchiveState {
    ACTIVE,
    ARCHIVED,
}

enum class PrivateRoomPinState {
    UNPINNED,
    PINNED,
}

enum class PrivateRoomMuteState {
    AUDIBLE,
    MUTED,
}

enum class PrivateRoomMetadataState {
    AVAILABLE,
    PENDING,
    UNAVAILABLE_ON_DEVICE,
}

enum class PrivateActivitySharingState {
    DISABLED,
    ENABLED,
}

enum class PrivateActivityFeedAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

enum class PrivateMessageRetention(
    val durationSeconds: Int,
    val label: String,
) {
    FIVE_MINUTES(durationSeconds = 300, label = "5m"),
    ONE_HOUR(durationSeconds = 3_600, label = "1h"),
    ONE_DAY(durationSeconds = 86_400, label = "24h"),
    SEVEN_DAYS(durationSeconds = 604_800, label = "7d"),
}

data class PrivateActivitySharingPreferences(
    val readReceipts: PrivateActivitySharingState = PrivateActivitySharingState.DISABLED,
    val typingIndicators: PrivateActivitySharingState = PrivateActivitySharingState.DISABLED,
)

data class PrivateMessagePreview(
    val senderDisplayName: String,
    val body: PrivateMessageText,
    val expiresAt: Instant,
) {
    init {
        requireValidPrivateDisplayText(senderDisplayName, "Message preview sender", PRIVATE_DISPLAY_NAME_LIMIT)
    }
}

data class PrivateRoomSummary(
    val roomId: PrivateRoomId,
    val kind: PrivateRoomKind,
    val title: String,
    val participantCount: Int,
    val retention: PrivateMessageRetention,
    val archiveState: PrivateRoomArchiveState,
    val pinState: PrivateRoomPinState,
    val muteState: PrivateRoomMuteState,
    val unreadMessageCount: Int,
    val latestMessagePreview: PrivateMessagePreview?,
    val metadataState: PrivateRoomMetadataState = PrivateRoomMetadataState.AVAILABLE,
) {
    init {
        requireValidPrivateDisplayText(title, "Room title", PRIVATE_ROOM_TITLE_LIMIT)
        require(unreadMessageCount >= 0) { "Unread message count cannot be negative." }
        when (kind) {
            PrivateRoomKind.DIRECT ->
                require(participantCount in 1..2) { "Direct rooms support one owner and at most one peer." }

            PrivateRoomKind.GROUP ->
                require(participantCount >= 1) { "Group rooms require an owner." }
        }
    }
}

enum class PrivateRoomMemberRole {
    OWNER,
    ADMIN,
    MEMBER,
}

data class PrivateRoomMemberSnapshot(
    val accountId: PrivateAccountId,
    val displayName: String,
    val role: PrivateRoomMemberRole,
) {
    init {
        requireValidPrivateDisplayText(displayName, "Room member", PRIVATE_DISPLAY_NAME_LIMIT)
    }
}

data class PrivateReplyPreview(
    val messageId: PrivateMessageId,
    val senderDisplayName: String,
    val body: PrivateMessageText,
) {
    init {
        requireValidPrivateDisplayText(senderDisplayName, "Reply sender", PRIVATE_DISPLAY_NAME_LIMIT)
    }
}

enum class PrivateReactionSelectionState {
    NOT_SELECTED,
    SELECTED,
}

data class PrivateReactionSummary(
    val reaction: PrivateReactionCode,
    val count: Int,
    val selectionState: PrivateReactionSelectionState,
) {
    init {
        require(count > 0) { "Reaction count must be positive." }
    }
}

data class PrivateMessageSnapshot(
    val roomId: PrivateRoomId,
    val messageId: PrivateMessageId,
    val senderAccountId: PrivateAccountId,
    val senderDisplayName: String,
    val ownership: PrivateMessageOwnership,
    val body: PrivateMessageText,
    val replyPreview: PrivateReplyPreview?,
    val revision: Long,
    val reactions: List<PrivateReactionSummary>,
    val sentAt: Instant,
    val editedAt: Instant?,
    val expiresAt: Instant,
) {
    init {
        requireValidPrivateDisplayText(senderDisplayName, "Message sender", PRIVATE_DISPLAY_NAME_LIMIT)
        require(revision >= 1L) { "Message revision must be positive." }
        require(expiresAt.isAfter(sentAt)) { "Message expiry must follow its send time." }
        require(reactions.distinctBy(PrivateReactionSummary::reaction).size == reactions.size) {
            "Message reactions must be unique."
        }
    }
}

data class PrivateTypingParticipant(
    val accountId: PrivateAccountId,
    val displayName: String,
    val expiresAt: Instant,
) {
    init {
        requireValidPrivateDisplayText(displayName, "Typing participant", PRIVATE_DISPLAY_NAME_LIMIT)
    }
}

data class PrivateRoomFeedSnapshot(
    val accountId: PrivateAccountId,
    val rooms: List<PrivateRoomSummary>,
    val activitySharingPreferences: PrivateActivitySharingPreferences,
) {
    init {
        require(rooms.distinctBy(PrivateRoomSummary::roomId).size == rooms.size) {
            "Room feed cannot contain duplicate rooms."
        }
    }
}

data class PrivateConversationSnapshot(
    val accountId: PrivateAccountId,
    val room: PrivateRoomSummary,
    val members: List<PrivateRoomMemberSnapshot>,
    val messages: List<PrivateMessageSnapshot>,
    val typingParticipants: List<PrivateTypingParticipant>,
    val typingAvailability: PrivateActivityFeedAvailability = PrivateActivityFeedAvailability.AVAILABLE,
) {
    init {
        require(members.size == room.participantCount) {
            "Conversation membership must match the room participant count."
        }
        require(members.distinctBy(PrivateRoomMemberSnapshot::accountId).size == members.size) {
            "Conversation cannot contain duplicate members."
        }
        require(members.any { member -> member.accountId == accountId }) {
            "Conversation membership must include the current account."
        }
        require(members.count { member -> member.role == PrivateRoomMemberRole.OWNER } == 1) {
            "Conversation membership requires exactly one owner."
        }
        require(messages.all { message -> message.roomId == room.roomId }) {
            "Conversation messages must belong to the presented room."
        }
        require(messages.distinctBy(PrivateMessageSnapshot::messageId).size == messages.size) {
            "Conversation cannot contain duplicate messages."
        }
        require(typingParticipants.distinctBy(PrivateTypingParticipant::accountId).size == typingParticipants.size) {
            "Typing participants must be unique."
        }
    }
}

private fun requireValidPrivateDisplayText(
    text: String,
    fieldName: String,
    maximumLength: Int,
) {
    require(text.isNotBlank() && text.length <= maximumLength && text.none(Char::isISOControl)) {
        "$fieldName is invalid."
    }
}

private const val PRIVATE_DISPLAY_NAME_LIMIT = 64
private const val PRIVATE_ROOM_TITLE_LIMIT = 128
