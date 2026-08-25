package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateInvitationCode
import app.synapse.privatechat.domain.chat.ChangePrivateGroupMemberRoleCommand
import app.synapse.privatechat.domain.chat.ChangePrivatePresenceSharingCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseAccountInvitationCommand
import app.synapse.privatechat.domain.chat.CreatePrivateRoomCommand
import app.synapse.privatechat.domain.chat.PrivateAccountInvitationId
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateProfileSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt
import app.synapse.privatechat.domain.chat.PublishPrivatePresenceCommand
import app.synapse.privatechat.domain.chat.RedeemPrivateRoomInvitationCommand
import app.synapse.privatechat.domain.chat.RemovePrivateGroupMemberCommand
import app.synapse.privatechat.domain.chat.UpdatePrivateProfileCommand
import java.util.UUID

internal class PrivateSocialMutationCoordinator(
    private val execution: PrivateChatGatewayExecution,
    private val backend: PrivateChatBackend,
    private val encryptedMutationOutbox: PrivateEncryptedMutationOutbox,
    private val pollingRepository: PrivateChatPollingRepository,
    private val roomIdFactory: () -> UUID = UUID::randomUUID,
) {
    suspend fun updateProfile(
        command: UpdatePrivateProfileCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.ProfileUpdated> =
        execution.mutate(command.accountId) { session ->
            command.mutationId.canonical.requireUuid()
            val profile = backend.updateProfile(session, command.displayName)
            PrivateSocialMutationReceipt.ProfileUpdated(
                accountId = command.accountId,
                mutationId = command.mutationId,
                profile =
                    PrivateProfileSnapshot(
                        accountId = command.accountId,
                        displayName = profile.displayName,
                        username = session.authenticationUsername,
                    ),
            )
        }

    suspend fun createRoom(command: CreatePrivateRoomCommand): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomCreated> =
        execution.mutate(command.accountId) { session ->
            val mutationId = command.mutationId.canonical.requireUuid()
            val roomId = roomIdFactory()
            val plaintext =
                PrivateChatPayloadCodec.encodeCreatedRoomMetadata(
                    PrivateChatPlaintextPayload.CreatedRoomMetadata(
                        accountId = command.accountId,
                        roomId = PrivateRoomId(roomId.toString()),
                        mutationId = command.mutationId,
                        roomKind = command.kind,
                        retention = command.retention,
                        title = command.title,
                    ),
                )
            try {
                val recipients = backend.listCurrentAccountRecipientDevices(session)
                val receipt =
                    when (
                        val outcome =
                            encryptedMutationOutbox.execute(
                                session = session,
                                intent =
                                    PrivateEncryptedMutationIntent.CreateRoom(
                                        roomId = roomId,
                                        clientMutationId = mutationId,
                                        kind = command.kind,
                                        retention = command.retention,
                                    ),
                                plaintext = plaintext,
                                recipients = recipients,
                            )
                    ) {
                        is PrivateEncryptedMutationBackendReceipt.RoomCreated -> outcome.receipt
                        else ->
                            throw PrivateEncryptedMutationOutboxException(
                                "Encrypted room creation returned another receipt kind",
                            )
                    }
                PrivateSocialMutationReceipt.RoomCreated(
                    accountId = command.accountId,
                    mutationId = command.mutationId,
                    roomId =
                        app.synapse.privatechat.domain.chat
                            .PrivateRoomId(receipt.roomId.toString()),
                    kind = command.kind,
                )
            } finally {
                plaintext.fill(0)
            }
        }

    suspend fun changeGroupMemberRole(
        command: ChangePrivateGroupMemberRoleCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRoleChanged> =
        execution.mutate(command.accountId) { session ->
            val receipt =
                backend.updateGroupMemberRole(
                    session = session,
                    roomId = command.roomId.canonical.requireUuid(),
                    memberAccountId = command.memberAccountId.canonical.requireUuid(),
                    clientMutationId = command.mutationId.canonical.requireUuid(),
                    role = command.role,
                )
            PrivateSocialMutationReceipt.GroupMemberRoleChanged(
                accountId = command.accountId,
                mutationId = command.mutationId,
                roomId = command.roomId,
                memberAccountId = command.memberAccountId,
                role = receipt.role,
            )
        }

    suspend fun removeGroupMember(
        command: RemovePrivateGroupMemberCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRemoved> =
        execution.mutate(command.accountId) { session ->
            backend.removeGroupMember(
                session = session,
                roomId = command.roomId.canonical.requireUuid(),
                memberAccountId = command.memberAccountId.canonical.requireUuid(),
                clientMutationId = command.mutationId.canonical.requireUuid(),
            )
            PrivateSocialMutationReceipt.GroupMemberRemoved(
                accountId = command.accountId,
                mutationId = command.mutationId,
                roomId = command.roomId,
                memberAccountId = command.memberAccountId,
            )
        }

    suspend fun createOneUseAccountInvitation(
        command: CreatePrivateOneUseAccountInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.OneUseAccountInvitationCreated> =
        execution.mutate(command.accountId) { session ->
            val receipt =
                backend.issueInvite(
                    session = session,
                    clientMutationId = command.mutationId.canonical.requireUuid(),
                    kind = PrivateBackendInviteKind.ACCOUNT_REGISTRATION,
                    roomId = null,
                )
            PrivateSocialMutationReceipt.OneUseAccountInvitationCreated(
                accountId = command.accountId,
                mutationId = command.mutationId,
                invitationId = PrivateAccountInvitationId(receipt.invitationId.toString()),
                invitationCode = PrivateInvitationCode(receipt.code),
                expiresAt = receipt.expiresAt,
            )
        }

    suspend fun redeemRoomInvitation(
        command: RedeemPrivateRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomInvitationRedeemed> =
        execution.mutate(command.accountId) { session ->
            val receipt =
                backend.redeemRoomInvite(
                    session = session,
                    inviteCode = command.invitationCode.secret,
                    redemptionId = command.mutationId.canonical.requireUuid(),
                )
            pollingRepository.invalidateRecentState()
            PrivateSocialMutationReceipt.RoomInvitationRedeemed(
                accountId = command.accountId,
                mutationId = command.mutationId,
                roomId =
                    app.synapse.privatechat.domain.chat
                        .PrivateRoomId(receipt.roomId.toString()),
                membershipEpoch = receipt.membershipEpoch,
                completedAt = receipt.completedAt,
            )
        }

    suspend fun changePresenceSharing(
        command: ChangePrivatePresenceSharingCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresenceSharingChanged> =
        execution.mutate(command.accountId) { session ->
            command.mutationId.canonical.requireUuid()
            val profile = backend.updatePresenceSharing(session, command.sharingState)
            PrivateSocialMutationReceipt.PresenceSharingChanged(
                accountId = command.accountId,
                mutationId = command.mutationId,
                sharingState = profile.presenceSharing,
            )
        }

    suspend fun publishPresence(
        command: PublishPrivatePresenceCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresencePublished> =
        execution.mutate(command.accountId) { session ->
            command.mutationId.canonical.requireUuid()
            val receipt = backend.publishPresence(session)
            PrivateSocialMutationReceipt.PresencePublished(
                accountId = command.accountId,
                mutationId = command.mutationId,
                publishedAt = receipt.createdAt,
                expiresAt = receipt.expiresAt,
            )
        }
}
