package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.AcknowledgePrivateRoomReadCommand
import app.synapse.privatechat.domain.chat.ChangePrivateActivitySharingCommand
import app.synapse.privatechat.domain.chat.ChangePrivateReactionCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomPreferencesCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomRetentionCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseRoomInvitationCommand
import app.synapse.privatechat.domain.chat.DeletePrivateMessageForEveryoneCommand
import app.synapse.privatechat.domain.chat.EditPrivateMessageCommand
import app.synapse.privatechat.domain.chat.PrivateChatMutationReceipt
import app.synapse.privatechat.domain.chat.PublishPrivateTypingStateCommand
import app.synapse.privatechat.domain.chat.SendPrivateMessageCommand

internal object PrivateChatReceiptValidator {
    fun matches(
        receipt: PrivateChatMutationReceipt.MessageSent,
        command: SendPrivateMessageCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.roomId == command.roomId &&
            receipt.mutationId == command.mutationId &&
            receipt.messageId != command.replyToMessageId

    fun matches(
        receipt: PrivateChatMutationReceipt.MessageEdited,
        command: EditPrivateMessageCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.roomId == command.roomId &&
            receipt.messageId == command.messageId &&
            receipt.mutationId == command.mutationId &&
            receipt.revision > command.expectedRevision

    fun matches(
        receipt: PrivateChatMutationReceipt.MessageDeletedForEveryone,
        command: DeletePrivateMessageForEveryoneCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.roomId == command.roomId &&
            receipt.messageId == command.messageId &&
            receipt.mutationId == command.mutationId

    fun matches(
        receipt: PrivateChatMutationReceipt.ReactionChanged,
        command: ChangePrivateReactionCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.roomId == command.roomId &&
            receipt.messageId == command.messageId &&
            receipt.mutationId == command.mutationId &&
            receipt.reaction == command.reaction &&
            receipt.change == command.change

    fun matches(
        receipt: PrivateChatMutationReceipt.RetentionChanged,
        command: ChangePrivateRoomRetentionCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.roomId == command.roomId &&
            receipt.mutationId == command.mutationId &&
            receipt.retention == command.retention

    fun matches(
        receipt: PrivateChatMutationReceipt.RoomPreferencesChanged,
        command: ChangePrivateRoomPreferencesCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.roomId == command.roomId &&
            receipt.mutationId == command.mutationId &&
            receipt.archiveState == command.archiveState &&
            receipt.pinState == command.pinState &&
            receipt.muteState == command.muteState

    fun matches(
        receipt: PrivateChatMutationReceipt.ActivitySharingChanged,
        command: ChangePrivateActivitySharingCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.mutationId == command.mutationId &&
            receipt.preferences == command.preferences

    fun matches(
        receipt: PrivateChatMutationReceipt.RoomReadAcknowledged,
        command: AcknowledgePrivateRoomReadCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.roomId == command.roomId &&
            receipt.mutationId == command.mutationId

    fun matches(
        receipt: PrivateChatMutationReceipt.TypingStatePublished,
        command: PublishPrivateTypingStateCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.roomId == command.roomId &&
            receipt.mutationId == command.mutationId &&
            receipt.typingState == command.typingState

    fun matches(
        receipt: PrivateChatMutationReceipt.OneUseRoomInvitationCreated,
        command: CreatePrivateOneUseRoomInvitationCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.roomId == command.roomId &&
            receipt.mutationId == command.mutationId
}
