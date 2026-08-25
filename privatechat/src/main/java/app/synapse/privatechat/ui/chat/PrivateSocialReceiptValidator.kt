package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.ChangePrivateGroupMemberRoleCommand
import app.synapse.privatechat.domain.chat.ChangePrivatePresenceSharingCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseAccountInvitationCommand
import app.synapse.privatechat.domain.chat.CreatePrivateRoomCommand
import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt
import app.synapse.privatechat.domain.chat.PublishPrivatePresenceCommand
import app.synapse.privatechat.domain.chat.RemovePrivateGroupMemberCommand
import app.synapse.privatechat.domain.chat.UpdatePrivateProfileCommand

internal object PrivateSocialReceiptValidator {
    fun matches(
        receipt: PrivateSocialMutationReceipt.ProfileUpdated,
        command: UpdatePrivateProfileCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.mutationId == command.mutationId &&
            receipt.profile.accountId == command.accountId &&
            receipt.profile.displayName == command.displayName

    fun matches(
        receipt: PrivateSocialMutationReceipt.RoomCreated,
        command: CreatePrivateRoomCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.mutationId == command.mutationId &&
            receipt.kind == command.kind

    fun matches(
        receipt: PrivateSocialMutationReceipt.GroupMemberRoleChanged,
        command: ChangePrivateGroupMemberRoleCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.mutationId == command.mutationId &&
            receipt.roomId == command.roomId &&
            receipt.memberAccountId == command.memberAccountId &&
            receipt.role == command.role

    fun matches(
        receipt: PrivateSocialMutationReceipt.GroupMemberRemoved,
        command: RemovePrivateGroupMemberCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.mutationId == command.mutationId &&
            receipt.roomId == command.roomId &&
            receipt.memberAccountId == command.memberAccountId

    fun matches(
        receipt: PrivateSocialMutationReceipt.OneUseAccountInvitationCreated,
        command: CreatePrivateOneUseAccountInvitationCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.mutationId == command.mutationId

    fun matches(
        receipt: PrivateSocialMutationReceipt.PresenceSharingChanged,
        command: ChangePrivatePresenceSharingCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.mutationId == command.mutationId &&
            receipt.sharingState == command.sharingState

    fun matches(
        receipt: PrivateSocialMutationReceipt.PresencePublished,
        command: PublishPrivatePresenceCommand,
    ): Boolean =
        receipt.accountId == command.accountId &&
            receipt.mutationId == command.mutationId &&
            receipt.expiresAt == command.expiresAt
}
