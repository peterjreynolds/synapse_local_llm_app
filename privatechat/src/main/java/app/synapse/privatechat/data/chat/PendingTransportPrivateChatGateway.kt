package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.AcknowledgePrivateRoomReadCommand
import app.synapse.privatechat.domain.chat.ChangePrivateActivitySharingCommand
import app.synapse.privatechat.domain.chat.ChangePrivateGroupMemberRoleCommand
import app.synapse.privatechat.domain.chat.ChangePrivatePresenceSharingCommand
import app.synapse.privatechat.domain.chat.ChangePrivateReactionCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomPreferencesCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomRetentionCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseAccountInvitationCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseRoomInvitationCommand
import app.synapse.privatechat.domain.chat.CreatePrivateRoomCommand
import app.synapse.privatechat.domain.chat.DeletePrivateMessageForEveryoneCommand
import app.synapse.privatechat.domain.chat.EditPrivateMessageCommand
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateSocialGateway
import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateSocialSnapshot
import app.synapse.privatechat.domain.chat.PublishPrivatePresenceCommand
import app.synapse.privatechat.domain.chat.PublishPrivateTypingStateCommand
import app.synapse.privatechat.domain.chat.RedeemPrivateRoomInvitationCommand
import app.synapse.privatechat.domain.chat.RemovePrivateGroupMemberCommand
import app.synapse.privatechat.domain.chat.SendPrivateMessageCommand
import app.synapse.privatechat.domain.chat.UpdatePrivateProfileCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object PendingTransportPrivateChatGateway : PrivateChatGateway, PrivateSocialGateway {
    override fun observeRoomFeed(accountId: PrivateAccountId): Flow<PrivateChatObservation<PrivateRoomFeedSnapshot>> =
        flowOf(PrivateChatObservation.TransportUnavailable)

    override fun observeConversation(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
    ): Flow<PrivateChatObservation<PrivateConversationSnapshot>> = flowOf(PrivateChatObservation.TransportUnavailable)

    override suspend fun sendMessage(
        command: SendPrivateMessageCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageSent> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun editMessage(
        command: EditPrivateMessageCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageEdited> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun deleteMessageForEveryone(
        command: DeletePrivateMessageForEveryoneCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageDeletedForEveryone> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun changeReaction(
        command: ChangePrivateReactionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ReactionChanged> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun changeRoomRetention(
        command: ChangePrivateRoomRetentionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RetentionChanged> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun changeRoomPreferences(
        command: ChangePrivateRoomPreferencesCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomPreferencesChanged> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun changeActivitySharing(
        command: ChangePrivateActivitySharingCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ActivitySharingChanged> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun acknowledgeRoomRead(
        command: AcknowledgePrivateRoomReadCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomReadAcknowledged> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun publishTypingState(
        command: PublishPrivateTypingStateCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.TypingStatePublished> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun createOneUseRoomInvitation(
        command: CreatePrivateOneUseRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.OneUseRoomInvitationCreated> = PrivateChatMutationOutcome.TransportUnavailable

    override fun observeSocial(accountId: PrivateAccountId): Flow<PrivateChatObservation<PrivateSocialSnapshot>> =
        flowOf(PrivateChatObservation.TransportUnavailable)

    override suspend fun updateProfile(
        command: UpdatePrivateProfileCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.ProfileUpdated> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun createRoom(
        command: CreatePrivateRoomCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomCreated> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun changeGroupMemberRole(
        command: ChangePrivateGroupMemberRoleCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRoleChanged> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun removeGroupMember(
        command: RemovePrivateGroupMemberCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRemoved> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun createOneUseAccountInvitation(
        command: CreatePrivateOneUseAccountInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.OneUseAccountInvitationCreated> =
        PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun redeemRoomInvitation(
        command: RedeemPrivateRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomInvitationRedeemed> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun changePresenceSharing(
        command: ChangePrivatePresenceSharingCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresenceSharingChanged> = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun publishPresence(
        command: PublishPrivatePresenceCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresencePublished> = PrivateChatMutationOutcome.TransportUnavailable
}
