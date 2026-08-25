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

internal data class PrivateBackendProfileRecord(
    val accountId: UUID,
    val displayName: String,
    val presenceSharing: PrivatePresenceSharingState,
    val activitySharing: PrivateActivitySharingPreferences,
)

internal data class PrivateBackendRoomRecord(
    val roomId: UUID,
    val ownerAccountId: UUID,
    val creationClientMutationId: UUID?,
    val kind: PrivateRoomKind,
    val retention: PrivateMessageRetention,
    val membershipEpoch: Int,
    val metadataRevision: Int,
    val metadataUpdatedAt: Instant?,
    val createdAt: Instant,
)

internal data class PrivateBackendRoomMemberRecord(
    val roomId: UUID,
    val accountId: UUID,
    val role: PrivateRoomMemberRole,
    val joinedAt: Instant,
)

internal data class PrivateBackendRoomPreferenceRecord(
    val roomId: UUID,
    val archiveState: PrivateRoomArchiveState,
    val pinState: PrivateRoomPinState,
    val muteState: PrivateRoomMuteState,
    val updatedAt: Instant,
)

internal data class PrivateBackendDeviceRecord(
    val address: app.synapse.privatechat.crypto.SignalDeviceAddress,
    val protocolAdapterVersion: Int,
)

internal data class PrivateBackendMessageRecord(
    val messageId: UUID,
    val roomId: UUID,
    val senderAccountId: UUID,
    val senderDeviceId: UUID,
    val clientMutationId: UUID,
    val membershipEpoch: Int,
    val currentRevision: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
)

internal data class PrivateBackendMessageRevisionRecord(
    val revisionId: UUID,
    val messageId: UUID,
    val editorAccountId: UUID,
    val editorDeviceId: UUID,
    val revisionNumber: Int,
    val membershipEpoch: Int,
    val editedAt: Instant,
    val expiresAt: Instant,
)

internal data class PrivateBackendReactionRecord(
    val reactionId: UUID,
    val messageId: UUID,
    val senderAccountId: UUID,
    val senderDeviceId: UUID,
    val clientMutationId: UUID,
    val membershipEpoch: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
)

internal data class PrivateBackendReplyRecord(
    val messageId: UUID,
    val repliedToMessageId: UUID,
)

internal data class PrivateBackendMessageReceiptRecord(
    val messageId: UUID,
    val recipientDeviceId: UUID,
    val kind: PrivateBackendMessageReceiptKind,
    val createdAt: Instant,
    val expiresAt: Instant,
)

internal enum class PrivateBackendMessageReceiptKind {
    DELIVERED,
    READ,
}

internal data class PrivateBackendTypingRecord(
    val roomId: UUID,
    val deviceId: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
)

internal data class PrivateBackendPresenceRecord(
    val deviceId: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
)

internal data class PrivateBackendEnvelopeRecord(
    val parentRecordId: UUID,
    val serverRevision: Int,
    val senderAccountId: UUID,
    val senderDeviceId: UUID,
    val envelope: PrivateChatEncryptedEnvelope,
    val createdAt: Instant,
)

internal data class PrivateBackendPollingState(
    val profiles: List<PrivateBackendProfileRecord>,
    val rooms: List<PrivateBackendRoomRecord>,
    val roomMembers: List<PrivateBackendRoomMemberRecord>,
    val roomPreferences: List<PrivateBackendRoomPreferenceRecord>,
    val devices: List<PrivateBackendDeviceRecord>,
    val messages: List<PrivateBackendMessageRecord>,
    val messageEnvelopes: List<PrivateBackendEnvelopeRecord>,
    val messageRevisions: List<PrivateBackendMessageRevisionRecord>,
    val messageRevisionEnvelopes: List<PrivateBackendEnvelopeRecord>,
    val replies: List<PrivateBackendReplyRecord>,
    val reactions: List<PrivateBackendReactionRecord>,
    val reactionEnvelopes: List<PrivateBackendEnvelopeRecord>,
    val roomMetadataEnvelopes: List<PrivateBackendEnvelopeRecord>,
    val messageReceipts: List<PrivateBackendMessageReceiptRecord>,
    val typing: List<PrivateBackendTypingRecord>,
    val presence: List<PrivateBackendPresenceRecord>,
)

internal data class PrivateBackendMessageSendReceipt(
    val messageId: UUID,
    val roomId: UUID,
    val clientMutationId: UUID,
    val expiresAt: Instant,
)

internal data class PrivateBackendMessageEditReceipt(
    val messageId: UUID,
    val revisionId: UUID,
    val serverRevision: Int,
    val editedAt: Instant,
    val expiresAt: Instant,
)

internal data class PrivateBackendMessageDeleteReceipt(
    val messageId: UUID,
    val serverRevision: Int,
    val correlationId: UUID,
    val deletionState: String,
    val requestedAt: Instant,
)

internal data class PrivateBackendReactionSendReceipt(
    val reactionId: UUID,
    val messageId: UUID,
    val clientMutationId: UUID,
    val expiresAt: Instant,
)

internal data class PrivateBackendReactionRemoveReceipt(
    val reactionId: UUID,
    val removedAt: Instant,
)

internal data class PrivateBackendRoomRetentionReceipt(
    val roomId: UUID,
    val retention: PrivateMessageRetention,
    val updatedAt: Instant,
)

internal data class PrivateBackendRoomPreferenceReceipt(
    val roomId: UUID,
    val archiveState: PrivateRoomArchiveState,
    val pinState: PrivateRoomPinState,
    val muteState: PrivateRoomMuteState,
    val updatedAt: Instant,
)

internal data class PrivateBackendRoomCreationReceipt(
    val roomId: UUID,
    val clientMutationId: UUID,
    val kind: PrivateRoomKind,
    val retention: PrivateMessageRetention,
    val membershipEpoch: Int,
    val metadataRevision: Int,
    val createdAt: Instant,
    val metadataUpdatedAt: Instant,
)

internal data class PrivateBackendMemberRoleReceipt(
    val roomId: UUID,
    val memberAccountId: UUID,
    val role: PrivateRoomMemberRole,
    val membershipEpoch: Int,
)

internal data class PrivateBackendMemberRemovalReceipt(
    val roomId: UUID,
    val memberAccountId: UUID,
    val membershipEpoch: Int,
)

internal data class PrivateBackendInviteReceipt(
    val invitationId: UUID,
    val kind: PrivateBackendInviteKind,
    val roomId: UUID?,
    val code: String,
    val expiresAt: Instant,
)

internal data class PrivateBackendRoomInvitationRedemptionReceipt(
    val roomId: UUID,
    val accountId: UUID,
    val membershipEpoch: Int,
    val completedAt: Instant,
)

internal enum class PrivateBackendInviteKind {
    ACCOUNT_REGISTRATION,
    ROOM_MEMBERSHIP,
}

internal interface PrivateChatPollingBackend {
    suspend fun loadPollingState(
        session: PrivateChatAuthenticatedSession,
        now: Instant,
    ): PrivateBackendPollingState

    suspend fun listRoomRecipientDevices(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
    ): List<PrivateChatRecipientDevice>

    suspend fun listCurrentAccountRecipientDevices(session: PrivateChatAuthenticatedSession): List<PrivateChatRecipientDevice>

    suspend fun claimDevicePreKey(
        session: PrivateChatAuthenticatedSession,
        recipient: PrivateChatRecipientDevice,
    ): SignalPublicPreKeyBundle
}

internal interface PrivateChatMutationBackend {
    suspend fun sendMessage(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        replyToMessageId: UUID?,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendMessageSendReceipt

    suspend fun editMessage(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        expectedServerRevision: Int,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendMessageEditReceipt

    suspend fun deleteMessage(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        expectedServerRevision: Int,
    ): PrivateBackendMessageDeleteReceipt

    suspend fun addReaction(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendReactionSendReceipt

    suspend fun removeReaction(
        session: PrivateChatAuthenticatedSession,
        reactionId: UUID,
        clientMutationId: UUID,
    ): PrivateBackendReactionRemoveReceipt

    suspend fun updateRoomRetention(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        retention: PrivateMessageRetention,
    ): PrivateBackendRoomRetentionReceipt

    suspend fun updateRoomPreferences(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        archiveState: PrivateRoomArchiveState,
        pinState: PrivateRoomPinState,
        muteState: PrivateRoomMuteState,
    ): PrivateBackendRoomPreferenceReceipt

    suspend fun updateActivitySharing(
        session: PrivateChatAuthenticatedSession,
        preferences: PrivateActivitySharingPreferences,
    ): PrivateBackendProfileRecord

    suspend fun acknowledgeRoomRead(
        session: PrivateChatAuthenticatedSession,
        messageIds: List<UUID>,
    )

    suspend fun publishTyping(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        active: Boolean,
    ): PrivateBackendTypingRecord?

    suspend fun issueInvite(
        session: PrivateChatAuthenticatedSession,
        clientMutationId: UUID,
        kind: PrivateBackendInviteKind,
        roomId: UUID?,
    ): PrivateBackendInviteReceipt

    suspend fun redeemRoomInvite(
        session: PrivateChatAuthenticatedSession,
        inviteCode: String,
        redemptionId: UUID,
    ): PrivateBackendRoomInvitationRedemptionReceipt

    suspend fun updateProfile(
        session: PrivateChatAuthenticatedSession,
        displayName: String,
    ): PrivateBackendProfileRecord

    suspend fun createRoom(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        kind: PrivateRoomKind,
        retention: PrivateMessageRetention,
        clientMutationId: UUID,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendRoomCreationReceipt

    suspend fun updateGroupMemberRole(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        memberAccountId: UUID,
        clientMutationId: UUID,
        role: PrivateRoomMemberRole,
    ): PrivateBackendMemberRoleReceipt

    suspend fun removeGroupMember(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        memberAccountId: UUID,
        clientMutationId: UUID,
    ): PrivateBackendMemberRemovalReceipt

    suspend fun updatePresenceSharing(
        session: PrivateChatAuthenticatedSession,
        sharingState: PrivatePresenceSharingState,
    ): PrivateBackendProfileRecord

    suspend fun publishPresence(session: PrivateChatAuthenticatedSession): PrivateBackendPresenceRecord
}

internal interface PrivateChatBackend :
    PrivateChatPollingBackend,
    PrivateChatMutationBackend
