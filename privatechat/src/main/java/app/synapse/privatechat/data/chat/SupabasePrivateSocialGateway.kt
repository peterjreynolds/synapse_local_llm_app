package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.ChangePrivateGroupMemberRoleCommand
import app.synapse.privatechat.domain.chat.ChangePrivatePresenceSharingCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseAccountInvitationCommand
import app.synapse.privatechat.domain.chat.CreatePrivateRoomCommand
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateSocialGateway
import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateSocialSnapshot
import app.synapse.privatechat.domain.chat.PublishPrivatePresenceCommand
import app.synapse.privatechat.domain.chat.RedeemPrivateRoomInvitationCommand
import app.synapse.privatechat.domain.chat.RemovePrivateGroupMemberCommand
import app.synapse.privatechat.domain.chat.UpdatePrivateProfileCommand
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

internal class SupabasePrivateSocialGateway(
    private val execution: PrivateChatGatewayExecution,
    private val pollingRepository: PrivateChatPollingRepository,
    private val snapshotAssembler: PrivateChatSnapshotAssembler,
    private val mutations: PrivateSocialMutationCoordinator,
    private val waitForNextPoll: suspend () -> Unit = { delay(DEFAULT_SOCIAL_POLL_INTERVAL_MILLIS) },
) : PrivateSocialGateway {
    override fun observeSocial(accountId: PrivateAccountId): Flow<PrivateChatObservation<PrivateSocialSnapshot>> =
        flow {
            while (currentCoroutineContext().isActive) {
                emit(
                    execution.observe(accountId) { session ->
                        snapshotAssembler.social(pollingRepository.load(session))
                    },
                )
                waitForNextPoll()
            }
        }

    override suspend fun updateProfile(
        command: UpdatePrivateProfileCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.ProfileUpdated> = mutations.updateProfile(command)

    override suspend fun createRoom(
        command: CreatePrivateRoomCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomCreated> = mutations.createRoom(command)

    override suspend fun changeGroupMemberRole(
        command: ChangePrivateGroupMemberRoleCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRoleChanged> = mutations.changeGroupMemberRole(command)

    override suspend fun removeGroupMember(
        command: RemovePrivateGroupMemberCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRemoved> = mutations.removeGroupMember(command)

    override suspend fun createOneUseAccountInvitation(
        command: CreatePrivateOneUseAccountInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.OneUseAccountInvitationCreated> =
        mutations.createOneUseAccountInvitation(command)

    override suspend fun redeemRoomInvitation(
        command: RedeemPrivateRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomInvitationRedeemed> = mutations.redeemRoomInvitation(command)

    override suspend fun changePresenceSharing(
        command: ChangePrivatePresenceSharingCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresenceSharingChanged> = mutations.changePresenceSharing(command)

    override suspend fun publishPresence(
        command: PublishPrivatePresenceCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresencePublished> = mutations.publishPresence(command)
}

private const val DEFAULT_SOCIAL_POLL_INTERVAL_MILLIS = 5_000L
