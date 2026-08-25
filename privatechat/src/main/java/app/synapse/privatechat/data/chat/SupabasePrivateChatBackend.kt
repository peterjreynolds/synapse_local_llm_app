package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import java.time.Instant
import java.util.UUID

internal class SupabasePrivateChatBackend(
    private val polling: SupabasePrivateChatPollingApi,
    private val contentMutations: SupabasePrivateContentMutationApi,
    private val roomMutations: SupabasePrivateRoomMutationApi,
    private val socialMutations: SupabasePrivateSocialMutationApi,
) : PrivateChatBackend {
    override suspend fun loadPollingState(
        session: PrivateChatAuthenticatedSession,
        now: Instant,
    ): PrivateBackendPollingState = polling.loadPollingState(session, now)

    override suspend fun listRoomRecipientDevices(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
    ): List<PrivateChatRecipientDevice> = polling.listRoomRecipientDevices(session, roomId)

    override suspend fun listCurrentAccountRecipientDevices(session: PrivateChatAuthenticatedSession): List<PrivateChatRecipientDevice> =
        polling.listCurrentAccountRecipientDevices(session)

    override suspend fun claimDevicePreKey(
        session: PrivateChatAuthenticatedSession,
        recipient: PrivateChatRecipientDevice,
    ): SignalPublicPreKeyBundle = polling.claimDevicePreKey(session, recipient)

    override suspend fun sendMessage(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        replyToMessageId: UUID?,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendMessageSendReceipt = contentMutations.sendMessage(session, roomId, clientMutationId, replyToMessageId, envelopes)

    override suspend fun editMessage(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        expectedServerRevision: Int,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendMessageEditReceipt =
        contentMutations.editMessage(session, messageId, clientMutationId, expectedServerRevision, envelopes)

    override suspend fun deleteMessage(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        expectedServerRevision: Int,
    ): PrivateBackendMessageDeleteReceipt = contentMutations.deleteMessage(session, messageId, clientMutationId, expectedServerRevision)

    override suspend fun addReaction(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendReactionSendReceipt = contentMutations.addReaction(session, messageId, clientMutationId, envelopes)

    override suspend fun removeReaction(
        session: PrivateChatAuthenticatedSession,
        reactionId: UUID,
        clientMutationId: UUID,
    ): PrivateBackendReactionRemoveReceipt = contentMutations.removeReaction(session, reactionId, clientMutationId)

    override suspend fun updateRoomRetention(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        retention: PrivateMessageRetention,
    ): PrivateBackendRoomRetentionReceipt = roomMutations.updateRoomRetention(session, roomId, clientMutationId, retention)

    override suspend fun updateRoomPreferences(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        archiveState: PrivateRoomArchiveState,
        pinState: PrivateRoomPinState,
        muteState: PrivateRoomMuteState,
    ): PrivateBackendRoomPreferenceReceipt =
        roomMutations.updateRoomPreferences(
            session,
            roomId,
            clientMutationId,
            archiveState,
            pinState,
            muteState,
        )

    override suspend fun updateActivitySharing(
        session: PrivateChatAuthenticatedSession,
        preferences: PrivateActivitySharingPreferences,
    ): PrivateBackendProfileRecord = socialMutations.updateActivitySharing(session, preferences)

    override suspend fun acknowledgeRoomRead(
        session: PrivateChatAuthenticatedSession,
        messageIds: List<UUID>,
    ) = roomMutations.acknowledgeRoomRead(session, messageIds)

    override suspend fun publishTyping(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        active: Boolean,
    ): PrivateBackendTypingRecord? = roomMutations.publishTyping(session, roomId, active)

    override suspend fun issueInvite(
        session: PrivateChatAuthenticatedSession,
        clientMutationId: UUID,
        kind: PrivateBackendInviteKind,
        roomId: UUID?,
    ): PrivateBackendInviteReceipt = socialMutations.issueInvite(session, clientMutationId, kind, roomId)

    override suspend fun redeemRoomInvite(
        session: PrivateChatAuthenticatedSession,
        inviteCode: String,
        redemptionId: UUID,
    ): PrivateBackendRoomInvitationRedemptionReceipt = socialMutations.redeemRoomInvite(session, inviteCode, redemptionId)

    override suspend fun updateProfile(
        session: PrivateChatAuthenticatedSession,
        displayName: String,
    ): PrivateBackendProfileRecord = socialMutations.updateProfile(session, displayName)

    override suspend fun createRoom(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        kind: PrivateRoomKind,
        retention: PrivateMessageRetention,
        clientMutationId: UUID,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendRoomCreationReceipt = socialMutations.createRoom(session, roomId, kind, retention, clientMutationId, envelopes)

    override suspend fun updateGroupMemberRole(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        memberAccountId: UUID,
        clientMutationId: UUID,
        role: PrivateRoomMemberRole,
    ): PrivateBackendMemberRoleReceipt = socialMutations.updateGroupMemberRole(session, roomId, memberAccountId, clientMutationId, role)

    override suspend fun removeGroupMember(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        memberAccountId: UUID,
        clientMutationId: UUID,
    ): PrivateBackendMemberRemovalReceipt = socialMutations.removeGroupMember(session, roomId, memberAccountId, clientMutationId)

    override suspend fun updatePresenceSharing(
        session: PrivateChatAuthenticatedSession,
        sharingState: PrivatePresenceSharingState,
    ): PrivateBackendProfileRecord = socialMutations.updatePresenceSharing(session, sharingState)

    override suspend fun publishPresence(session: PrivateChatAuthenticatedSession): PrivateBackendPresenceRecord =
        socialMutations.publishPresence(session)
}
