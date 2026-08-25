package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageOwnership
import app.synapse.privatechat.domain.chat.PrivateMessagePreview
import app.synapse.privatechat.domain.chat.PrivateMessageSnapshot
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivatePresenceSnapshot
import app.synapse.privatechat.domain.chat.PrivateProfileSnapshot
import app.synapse.privatechat.domain.chat.PrivateReactionCode
import app.synapse.privatechat.domain.chat.PrivateReactionSelectionState
import app.synapse.privatechat.domain.chat.PrivateReactionSummary
import app.synapse.privatechat.domain.chat.PrivateReplyPreview
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMemberSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.domain.chat.PrivateSocialSnapshot
import app.synapse.privatechat.domain.chat.PrivateTypingParticipant
import java.util.UUID

internal class PrivateChatSnapshotAssembler {
    fun roomFeed(state: PrivateResolvedPollingState): PrivateRoomFeedSnapshot {
        val currentAccountId = UUID.fromString(state.session.accountId.canonical)
        val profiles = state.backend.profiles.associateBy(PrivateBackendProfileRecord::accountId)
        val membersByRoom = state.backend.roomMembers.groupBy(PrivateBackendRoomMemberRecord::roomId)
        val preferencesByRoom = state.backend.roomPreferences.associateBy(PrivateBackendRoomPreferenceRecord::roomId)
        val messagesByRoom = state.messages.values.groupBy { message -> message.record.roomId }
        val readMessageIds = currentDeviceReadMessageIds(state)
        val rooms =
            state.rooms.values
                .map { room ->
                    roomSummary(
                        room = room,
                        members = membersByRoom[room.record.roomId].orEmpty(),
                        preference = preferencesByRoom[room.record.roomId],
                        messages = messagesByRoom[room.record.roomId].orEmpty(),
                        profiles = profiles,
                        currentAccountId = currentAccountId,
                        readMessageIds = readMessageIds,
                    )
                }.sortedWith(
                    compareByDescending<PrivateRoomSummary> { room -> room.pinState == PrivateRoomPinState.PINNED }
                        .thenByDescending { room -> room.latestMessagePreview != null }
                        .thenBy(PrivateRoomSummary::title)
                        .thenBy { room -> room.roomId.canonical },
                )
        return PrivateRoomFeedSnapshot(
            accountId = state.session.accountId,
            rooms = rooms,
            activitySharingPreferences = currentProfile(state).activitySharing,
        )
    }

    fun conversation(
        state: PrivateResolvedPollingState,
        roomId: UUID,
    ): PrivateConversationSnapshot? {
        val room = state.rooms[roomId] ?: return null
        val currentAccountId = UUID.fromString(state.session.accountId.canonical)
        val profiles = state.backend.profiles.associateBy(PrivateBackendProfileRecord::accountId)
        val members = state.backend.roomMembers.filter { member -> member.roomId == roomId }
        val messages =
            state.messages.values
                .filter { message -> message.record.roomId == roomId }
                .sortedWith(compareBy({ message -> message.record.createdAt }, { message -> message.record.messageId }))
        val messagesById = messages.associateBy { message -> message.record.messageId }
        val reactionsByMessage = state.reactions.values.groupBy { reaction -> reaction.record.messageId }
        val readMessageIds = currentDeviceReadMessageIds(state)
        return PrivateConversationSnapshot(
            accountId = state.session.accountId,
            room =
                roomSummary(
                    room = room,
                    members = members,
                    preference = state.backend.roomPreferences.singleOrNull { preference -> preference.roomId == roomId },
                    messages = messages,
                    profiles = profiles,
                    currentAccountId = currentAccountId,
                    readMessageIds = readMessageIds,
                ),
            members =
                members
                    .map { member ->
                        PrivateRoomMemberSnapshot(
                            accountId = member.accountId.toDomainAccountId(),
                            displayName = profiles.requireProfile(member.accountId).displayName,
                            role = member.role,
                        )
                    }.sortedWith(
                        compareBy<PrivateRoomMemberSnapshot> { member -> member.role.sortOrder() }
                            .thenBy(PrivateRoomMemberSnapshot::displayName)
                            .thenBy { member -> member.accountId.canonical },
                    ),
            messages =
                messages.map { message ->
                    messageSnapshot(
                        message = message,
                        messagesById = messagesById,
                        reactions = reactionsByMessage[message.record.messageId].orEmpty(),
                        profiles = profiles,
                        currentAccountId = currentAccountId,
                    )
                },
            typingParticipants = typingParticipants(state, roomId, profiles, currentAccountId),
        )
    }

    fun social(state: PrivateResolvedPollingState): PrivateSocialSnapshot {
        val ownProfile = currentProfile(state)
        val currentAccountId = UUID.fromString(state.session.accountId.canonical)
        val profiles = state.backend.profiles.associateBy(PrivateBackendProfileRecord::accountId)
        val devices = state.backend.devices.associateBy { device -> device.address.transportDeviceId }
        val visiblePresence =
            state.backend.presence
                .map { presence ->
                    val accountId = devices.getValue(presence.deviceId).address.accountId
                    accountId to presence
                }.filter { (accountId, _) -> accountId != currentAccountId }
                .groupBy({ (accountId, _) -> accountId }, { (_, presence) -> presence })
                .map { (accountId, accountPresence) ->
                    val profile = profiles.requireProfile(accountId)
                    if (profile.presenceSharing != PrivatePresenceSharingState.ENABLED) {
                        malformedSnapshot("Presence was returned for a profile that disabled sharing")
                    }
                    val latest = accountPresence.maxBy(PrivateBackendPresenceRecord::createdAt)
                    PrivatePresenceSnapshot(
                        accountId = accountId.toDomainAccountId(),
                        displayName = profile.displayName,
                        publishedAt = latest.createdAt,
                        expiresAt = latest.expiresAt,
                    )
                }.sortedWith(
                    compareBy(PrivatePresenceSnapshot::displayName)
                        .thenBy { presence -> presence.accountId.canonical },
                )
        return PrivateSocialSnapshot(
            accountId = state.session.accountId,
            profile =
                PrivateProfileSnapshot(
                    accountId = state.session.accountId,
                    displayName = ownProfile.displayName,
                    username = state.session.authenticationUsername,
                ),
            presenceSharing = ownProfile.presenceSharing,
            visiblePresence = visiblePresence,
        )
    }

    fun currentAccountReactionId(
        state: PrivateResolvedPollingState,
        roomId: UUID,
        messageId: UUID,
        reaction: PrivateReactionCode,
    ): UUID? {
        val message = state.messages[messageId] ?: return null
        if (message.record.roomId != roomId) return null
        return state.reactions.values
            .filter { candidate ->
                candidate.record.messageId == messageId &&
                    candidate.record.senderAccountId.toString() == state.session.accountId.canonical &&
                    candidate.reaction == reaction
            }.minByOrNull { candidate -> candidate.record.createdAt }
            ?.record
            ?.reactionId
    }

    fun unreadMessageIds(
        state: PrivateResolvedPollingState,
        roomId: UUID,
    ): List<UUID> {
        val readMessageIds = currentDeviceReadMessageIds(state)
        return state.messages.values
            .filter { message ->
                message.record.roomId == roomId &&
                    message.record.senderAccountId.toString() != state.session.accountId.canonical &&
                    message.record.messageId !in readMessageIds
            }.sortedBy { message -> message.record.createdAt }
            .map { message -> message.record.messageId }
    }

    private fun roomSummary(
        room: PrivateResolvedRoom,
        members: List<PrivateBackendRoomMemberRecord>,
        preference: PrivateBackendRoomPreferenceRecord?,
        messages: List<PrivateResolvedMessage>,
        profiles: Map<UUID, PrivateBackendProfileRecord>,
        currentAccountId: UUID,
        readMessageIds: Set<UUID>,
    ): PrivateRoomSummary {
        val latestMessage =
            messages.maxWithOrNull(
                compareBy({ message -> message.record.createdAt }, { message -> message.record.messageId }),
            )
        return PrivateRoomSummary(
            roomId = room.record.roomId.toDomainRoomId(),
            kind = room.record.kind,
            title = room.title,
            participantCount = members.size,
            retention = room.record.retention,
            archiveState = preference?.archiveState ?: PrivateRoomArchiveState.ACTIVE,
            pinState = preference?.pinState ?: PrivateRoomPinState.UNPINNED,
            muteState = preference?.muteState ?: PrivateRoomMuteState.AUDIBLE,
            unreadMessageCount =
                messages.count { message ->
                    message.record.senderAccountId != currentAccountId && message.record.messageId !in readMessageIds
                },
            latestMessagePreview =
                latestMessage?.let { message ->
                    PrivateMessagePreview(
                        senderDisplayName = profiles.requireProfile(message.record.senderAccountId).displayName,
                        body = message.body,
                        expiresAt = message.record.expiresAt,
                    )
                },
            metadataState = room.metadataState,
        )
    }

    private fun messageSnapshot(
        message: PrivateResolvedMessage,
        messagesById: Map<UUID, PrivateResolvedMessage>,
        reactions: List<PrivateResolvedReaction>,
        profiles: Map<UUID, PrivateBackendProfileRecord>,
        currentAccountId: UUID,
    ): PrivateMessageSnapshot {
        val sender = profiles.requireProfile(message.record.senderAccountId)
        val replyPreview =
            message.replyToMessageId?.let { replyId ->
                val repliedTo = messagesById[UUID.fromString(replyId.canonical)] ?: return@let null
                PrivateReplyPreview(
                    messageId = replyId,
                    senderDisplayName = profiles.requireProfile(repliedTo.record.senderAccountId).displayName,
                    body = repliedTo.body,
                )
            }
        return PrivateMessageSnapshot(
            roomId = message.record.roomId.toDomainRoomId(),
            messageId = message.record.messageId.toDomainMessageId(),
            senderAccountId = message.record.senderAccountId.toDomainAccountId(),
            senderDisplayName = sender.displayName,
            ownership =
                if (message.record.senderAccountId == currentAccountId) {
                    PrivateMessageOwnership.CURRENT_ACCOUNT
                } else {
                    PrivateMessageOwnership.OTHER_PARTICIPANT
                },
            body = message.body,
            replyPreview = replyPreview,
            revision = message.domainRevision,
            reactions = reactionSummaries(reactions, currentAccountId),
            sentAt = message.record.createdAt,
            editedAt = message.editedAt,
            expiresAt = message.record.expiresAt,
        )
    }

    private fun reactionSummaries(
        reactions: List<PrivateResolvedReaction>,
        currentAccountId: UUID,
    ): List<PrivateReactionSummary> =
        reactions
            .groupBy(PrivateResolvedReaction::reaction)
            .map { (reaction, matchingReactions) ->
                PrivateReactionSummary(
                    reaction = reaction,
                    count = matchingReactions.size,
                    selectionState =
                        if (matchingReactions.any { candidate -> candidate.record.senderAccountId == currentAccountId }) {
                            PrivateReactionSelectionState.SELECTED
                        } else {
                            PrivateReactionSelectionState.NOT_SELECTED
                        },
                )
            }.sortedBy { summary -> summary.reaction.canonical }

    private fun typingParticipants(
        state: PrivateResolvedPollingState,
        roomId: UUID,
        profiles: Map<UUID, PrivateBackendProfileRecord>,
        currentAccountId: UUID,
    ): List<PrivateTypingParticipant> {
        val devices = state.backend.devices.associateBy { device -> device.address.transportDeviceId }
        return state.backend.typing
            .filter { typing -> typing.roomId == roomId }
            .map { typing -> devices.getValue(typing.deviceId).address.accountId to typing }
            .filter { (accountId, _) -> accountId != currentAccountId }
            .groupBy({ (accountId, _) -> accountId }, { (_, typing) -> typing })
            .map { (accountId, typingRows) ->
                PrivateTypingParticipant(
                    accountId = accountId.toDomainAccountId(),
                    displayName = profiles.requireProfile(accountId).displayName,
                    expiresAt = typingRows.maxOf(PrivateBackendTypingRecord::expiresAt),
                )
            }.sortedWith(
                compareBy(PrivateTypingParticipant::displayName)
                    .thenBy { participant -> participant.accountId.canonical },
            )
    }

    private fun currentProfile(state: PrivateResolvedPollingState): PrivateBackendProfileRecord =
        state.backend.profiles.singleOrNull { profile -> profile.accountId.toString() == state.session.accountId.canonical }
            ?: malformedSnapshot("Current account profile is unavailable")

    private fun currentDeviceReadMessageIds(state: PrivateResolvedPollingState): Set<UUID> =
        state.backend.messageReceipts
            .filter { receipt ->
                receipt.recipientDeviceId == state.session.localSignalAddress.transportDeviceId &&
                    receipt.kind == PrivateBackendMessageReceiptKind.READ
            }.mapTo(HashSet(), PrivateBackendMessageReceiptRecord::messageId)
}

private fun UUID.toDomainAccountId(): PrivateAccountId = PrivateAccountId(toString())

private fun UUID.toDomainRoomId(): PrivateRoomId = PrivateRoomId(toString())

private fun UUID.toDomainMessageId(): PrivateMessageId = PrivateMessageId(toString())

private fun Map<UUID, PrivateBackendProfileRecord>.requireProfile(accountId: UUID): PrivateBackendProfileRecord =
    this[accountId] ?: malformedSnapshot("Profile required by the chat snapshot is unavailable")

private fun PrivateRoomMemberRole.sortOrder(): Int =
    when (this) {
        PrivateRoomMemberRole.OWNER -> 0
        PrivateRoomMemberRole.ADMIN -> 1
        PrivateRoomMemberRole.MEMBER -> 2
    }

private fun malformedSnapshot(message: String): Nothing = throw SupabasePrivateChatResponseException(message)
