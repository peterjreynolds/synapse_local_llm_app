package app.synapse.privatechat.domain.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import java.time.Instant

enum class PrivateChatInputField {
    MESSAGE,
    REACTION,
}

sealed interface PrivateMessageTextValidation {
    data class Accepted(
        val message: PrivateMessageText,
    ) : PrivateMessageTextValidation

    data class Rejected(
        val field: PrivateChatInputField = PrivateChatInputField.MESSAGE,
        val userMessage: String,
    ) : PrivateMessageTextValidation
}

sealed interface PrivateReactionValidation {
    data class Accepted(
        val reaction: PrivateReactionCode,
    ) : PrivateReactionValidation

    data class Rejected(
        val field: PrivateChatInputField = PrivateChatInputField.REACTION,
        val userMessage: String,
    ) : PrivateReactionValidation
}

fun validatePrivateMessageText(input: String): PrivateMessageTextValidation {
    val normalizedText = input.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (
        normalizedText.isEmpty() ||
        normalizedText.length > PRIVATE_MESSAGE_TEXT_LIMIT ||
        normalizedText.any { character ->
            character.isISOControl() && character != '\n' && character != '\t'
        }
    ) {
        return PrivateMessageTextValidation.Rejected(
            userMessage = "Enter a message with 1–$PRIVATE_MESSAGE_TEXT_LIMIT supported characters.",
        )
    }
    return PrivateMessageTextValidation.Accepted(PrivateMessageText(normalizedText))
}

fun validatePrivateReaction(input: String): PrivateReactionValidation {
    val normalizedReaction = input.trim()
    val codePointCount = normalizedReaction.codePointCount(0, normalizedReaction.length)
    if (
        codePointCount !in 1..PRIVATE_REACTION_CODE_POINT_LIMIT ||
        normalizedReaction.any { character -> character.isISOControl() || character.isWhitespace() }
    ) {
        return PrivateReactionValidation.Rejected(
            userMessage = "Choose one supported reaction.",
        )
    }
    return PrivateReactionValidation.Accepted(PrivateReactionCode(normalizedReaction))
}

data class SendPrivateMessageCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val mutationId: PrivateClientMutationId,
    val body: PrivateMessageText,
    val replyToMessageId: PrivateMessageId?,
)

data class EditPrivateMessageCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val messageId: PrivateMessageId,
    val mutationId: PrivateClientMutationId,
    val expectedRevision: Long,
    val revisedBody: PrivateMessageText,
) {
    init {
        require(expectedRevision >= 1L) { "Expected message revision must be positive." }
    }
}

data class DeletePrivateMessageForEveryoneCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val messageId: PrivateMessageId,
    val mutationId: PrivateClientMutationId,
    val expectedRevision: Long,
) {
    init {
        require(expectedRevision >= 1L) { "Expected message revision must be positive." }
    }
}

enum class PrivateReactionChange {
    ADD,
    REMOVE,
}

data class ChangePrivateReactionCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val messageId: PrivateMessageId,
    val mutationId: PrivateClientMutationId,
    val reaction: PrivateReactionCode,
    val change: PrivateReactionChange,
)

data class ChangePrivateRoomRetentionCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val mutationId: PrivateClientMutationId,
    val retention: PrivateMessageRetention,
)

data class ChangePrivateRoomPreferencesCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val mutationId: PrivateClientMutationId,
    val archiveState: PrivateRoomArchiveState,
    val pinState: PrivateRoomPinState,
    val muteState: PrivateRoomMuteState,
)

data class ChangePrivateActivitySharingCommand(
    val accountId: PrivateAccountId,
    val mutationId: PrivateClientMutationId,
    val preferences: PrivateActivitySharingPreferences,
)

data class AcknowledgePrivateRoomReadCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val mutationId: PrivateClientMutationId,
)

enum class PrivateTypingState {
    INACTIVE,
    ACTIVE,
}

data class PublishPrivateTypingStateCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val mutationId: PrivateClientMutationId,
    val typingState: PrivateTypingState,
)

data class CreatePrivateOneUseRoomInvitationCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val mutationId: PrivateClientMutationId,
)

sealed interface PrivateMutationReceipt {
    val accountId: PrivateAccountId
    val mutationId: PrivateClientMutationId
}

sealed interface PrivateChatMutationReceipt : PrivateMutationReceipt {
    val roomId: PrivateRoomId?

    data class MessageSent(
        override val accountId: PrivateAccountId,
        override val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val messageId: PrivateMessageId,
    ) : PrivateChatMutationReceipt

    data class MessageEdited(
        override val accountId: PrivateAccountId,
        override val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val messageId: PrivateMessageId,
        val revision: Long,
    ) : PrivateChatMutationReceipt {
        init {
            require(revision >= 2L) { "Edited message revision must be at least two." }
        }
    }

    data class MessageDeletedForEveryone(
        override val accountId: PrivateAccountId,
        override val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val messageId: PrivateMessageId,
    ) : PrivateChatMutationReceipt

    data class ReactionChanged(
        override val accountId: PrivateAccountId,
        override val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val messageId: PrivateMessageId,
        val reaction: PrivateReactionCode,
        val change: PrivateReactionChange,
    ) : PrivateChatMutationReceipt

    data class RetentionChanged(
        override val accountId: PrivateAccountId,
        override val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val retention: PrivateMessageRetention,
    ) : PrivateChatMutationReceipt

    data class RoomPreferencesChanged(
        override val accountId: PrivateAccountId,
        override val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val archiveState: PrivateRoomArchiveState,
        val pinState: PrivateRoomPinState,
        val muteState: PrivateRoomMuteState,
    ) : PrivateChatMutationReceipt

    data class ActivitySharingChanged(
        override val accountId: PrivateAccountId,
        override val mutationId: PrivateClientMutationId,
        val preferences: PrivateActivitySharingPreferences,
    ) : PrivateChatMutationReceipt {
        override val roomId: PrivateRoomId? = null
    }

    data class RoomReadAcknowledged(
        override val accountId: PrivateAccountId,
        override val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
    ) : PrivateChatMutationReceipt

    data class TypingStatePublished(
        override val accountId: PrivateAccountId,
        override val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val typingState: PrivateTypingState,
    ) : PrivateChatMutationReceipt

    data class OneUseRoomInvitationCreated(
        override val accountId: PrivateAccountId,
        override val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val invitationId: PrivateRoomInvitationId,
        val invitationCode: PrivateRoomInvitationCode,
        val expiresAt: Instant,
    ) : PrivateChatMutationReceipt
}

fun interface PrivateClientMutationIdFactory {
    fun createMutationId(): PrivateClientMutationId
}

private const val PRIVATE_MESSAGE_TEXT_LIMIT = 4_096
private const val PRIVATE_REACTION_CODE_POINT_LIMIT = 8
