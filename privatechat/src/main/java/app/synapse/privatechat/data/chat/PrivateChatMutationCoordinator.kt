package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.chat.AcknowledgePrivateRoomReadCommand
import app.synapse.privatechat.domain.chat.ChangePrivateActivitySharingCommand
import app.synapse.privatechat.domain.chat.ChangePrivateReactionCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomPreferencesCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomRetentionCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseRoomInvitationCommand
import app.synapse.privatechat.domain.chat.DeletePrivateMessageForEveryoneCommand
import app.synapse.privatechat.domain.chat.EditPrivateMessageCommand
import app.synapse.privatechat.domain.chat.PrivateActivitySharingState
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateReactionChange
import app.synapse.privatechat.domain.chat.PrivateRoomInvitationCode
import app.synapse.privatechat.domain.chat.PrivateRoomInvitationId
import app.synapse.privatechat.domain.chat.PrivateTypingState
import app.synapse.privatechat.domain.chat.PublishPrivateTypingStateCommand
import app.synapse.privatechat.domain.chat.SendPrivateMessageCommand
import java.time.Clock
import java.util.Locale
import java.util.UUID

internal class PrivateChatMutationCoordinator(
    private val execution: PrivateChatGatewayExecution,
    private val backend: PrivateChatBackend,
    private val pollingRepository: PrivateChatPollingRepository,
    private val snapshotAssembler: PrivateChatSnapshotAssembler,
    private val encryptedMutationOutbox: PrivateEncryptedMutationOutbox,
    private val payloadCache: PrivateDecryptedPayloadCacheRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun sendMessage(command: SendPrivateMessageCommand): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageSent> =
        execution.mutate(command.accountId) { session ->
            val roomId = command.roomId.canonical.requireUuid()
            val mutationId = command.mutationId.canonical.requireUuid()
            val replyToMessageId = command.replyToMessageId?.canonical?.requireUuid()
            val plaintext =
                PrivateChatPayloadCodec.encodeMessage(
                    PrivateChatPlaintextPayload.Message(
                        accountId = command.accountId,
                        roomId = command.roomId,
                        mutationId = command.mutationId,
                        body = command.body,
                        replyToMessageId = command.replyToMessageId,
                    ),
                )
            try {
                val receipt =
                    when (
                        val outcome =
                            encryptedMutationOutbox.execute(
                                session = session,
                                intent =
                                    PrivateEncryptedMutationIntent.SendMessage(
                                        roomId,
                                        mutationId,
                                        replyToMessageId,
                                    ),
                                plaintext = plaintext,
                                recipients = backend.listRoomRecipientDevices(session, roomId),
                            )
                    ) {
                        is PrivateEncryptedMutationBackendReceipt.MessageSent -> outcome.receipt
                        else -> throw PrivateEncryptedMutationOutboxException("Encrypted send returned another receipt kind")
                    }
                PrivateChatMutationReceipt.MessageSent(
                    accountId = command.accountId,
                    roomId = command.roomId,
                    mutationId = command.mutationId,
                    messageId =
                        app.synapse.privatechat.domain.chat
                            .PrivateMessageId(receipt.messageId.toString()),
                )
            } finally {
                plaintext.fill(0)
            }
        }

    suspend fun editMessage(command: EditPrivateMessageCommand): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageEdited> =
        execution.mutate(command.accountId) { session ->
            val roomId = command.roomId.canonical.requireUuid()
            val messageId = command.messageId.canonical.requireUuid()
            val mutationId = command.mutationId.canonical.requireUuid()
            val expectedServerRevision = command.expectedRevision.toServerRevision()
            val currentState = pollingRepository.load(session)
            val currentMessage =
                currentState.messages[messageId]
                    ?: rejectCommand("This message is no longer available.")
            if (
                currentMessage.record.roomId != roomId ||
                currentMessage.record.senderAccountId.toString() != command.accountId.canonical ||
                currentMessage.domainRevision != command.expectedRevision
            ) {
                rejectCommand("This message changed. Refresh and try again.")
            }
            val revisedDomainRevision = command.expectedRevision + 1L
            if (revisedDomainRevision > MAXIMUM_DOMAIN_MESSAGE_REVISION) {
                rejectCommand("This message cannot be edited again.")
            }
            val plaintext =
                PrivateChatPayloadCodec.encodeMessageRevision(
                    PrivateChatPlaintextPayload.MessageRevision(
                        accountId = command.accountId,
                        roomId = command.roomId,
                        mutationId = command.mutationId,
                        messageId = command.messageId,
                        revision = revisedDomainRevision.toInt(),
                        body = command.revisedBody,
                    ),
                )
            try {
                val receipt =
                    when (
                        val outcome =
                            encryptedMutationOutbox.execute(
                                session = session,
                                intent =
                                    PrivateEncryptedMutationIntent.EditMessage(
                                        messageId,
                                        mutationId,
                                        expectedServerRevision,
                                    ),
                                plaintext = plaintext,
                                recipients = backend.listRoomRecipientDevices(session, roomId),
                            )
                    ) {
                        is PrivateEncryptedMutationBackendReceipt.MessageEdited -> outcome.receipt
                        else -> throw PrivateEncryptedMutationOutboxException("Encrypted edit returned another receipt kind")
                    }
                payloadCache.purgeMessageContent(session, messageId, clock.instant())
                PrivateChatMutationReceipt.MessageEdited(
                    accountId = command.accountId,
                    roomId = command.roomId,
                    mutationId = command.mutationId,
                    messageId = command.messageId,
                    revision = receipt.serverRevision.toLong() + 1L,
                )
            } finally {
                plaintext.fill(0)
            }
        }

    suspend fun deleteMessageForEveryone(
        command: DeletePrivateMessageForEveryoneCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageDeletedForEveryone> =
        execution.mutate(command.accountId) { session ->
            val roomId = command.roomId.canonical.requireUuid()
            val messageId = command.messageId.canonical.requireUuid()
            val mutationId = command.mutationId.canonical.requireUuid()
            val expectedServerRevision = command.expectedRevision.toServerRevision()
            val currentState = pollingRepository.load(session)
            val currentMessage =
                currentState.messages[messageId]
                    ?: rejectCommand("This message is no longer available.")
            if (
                currentMessage.record.roomId != roomId ||
                currentMessage.record.senderAccountId.toString() != command.accountId.canonical ||
                currentMessage.domainRevision != command.expectedRevision
            ) {
                rejectCommand("This message changed. Refresh and try again.")
            }
            val receipt = backend.deleteMessage(session, messageId, mutationId, expectedServerRevision)
            if (receipt.deletionState != "DELETED") {
                rejectCommand("Message deletion is still being completed. Try again shortly.")
            }
            payloadCache.purgeMessage(session, messageId, clock.instant())
            PrivateChatMutationReceipt.MessageDeletedForEveryone(
                accountId = command.accountId,
                roomId = command.roomId,
                mutationId = command.mutationId,
                messageId = command.messageId,
            )
        }

    suspend fun changeReaction(
        command: ChangePrivateReactionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ReactionChanged> =
        execution.mutate(command.accountId) { session ->
            val roomId = command.roomId.canonical.requireUuid()
            val messageId = command.messageId.canonical.requireUuid()
            val mutationId = command.mutationId.canonical.requireUuid()
            val currentState = pollingRepository.load(session)
            if (currentState.messages[messageId]?.record?.roomId != roomId) {
                rejectCommand("This message is no longer available.")
            }
            val selectedReactionId =
                snapshotAssembler.currentAccountReactionId(
                    currentState,
                    roomId,
                    messageId,
                    command.reaction,
                )
            when (command.change) {
                PrivateReactionChange.ADD -> {
                    if (selectedReactionId != null) rejectCommand("That reaction is already selected.")
                    val plaintext =
                        PrivateChatPayloadCodec.encodeReaction(
                            PrivateChatPlaintextPayload.Reaction(
                                accountId = command.accountId,
                                roomId = command.roomId,
                                mutationId = command.mutationId,
                                messageId = command.messageId,
                                reaction = command.reaction,
                            ),
                        )
                    try {
                        val outcome =
                            encryptedMutationOutbox.execute(
                                session = session,
                                intent = PrivateEncryptedMutationIntent.AddReaction(messageId, mutationId),
                                plaintext = plaintext,
                                recipients = backend.listRoomRecipientDevices(session, roomId),
                            )
                        if (outcome !is PrivateEncryptedMutationBackendReceipt.ReactionAdded) {
                            throw PrivateEncryptedMutationOutboxException(
                                "Encrypted reaction returned another receipt kind",
                            )
                        }
                    } finally {
                        plaintext.fill(0)
                    }
                }

                PrivateReactionChange.REMOVE -> {
                    val reactionId = selectedReactionId ?: rejectCommand("That reaction is no longer selected.")
                    backend.removeReaction(session, reactionId, mutationId)
                    payloadCache.purgeReaction(session, reactionId, clock.instant())
                }
            }
            PrivateChatMutationReceipt.ReactionChanged(
                accountId = command.accountId,
                roomId = command.roomId,
                mutationId = command.mutationId,
                messageId = command.messageId,
                reaction = command.reaction,
                change = command.change,
            )
        }

    suspend fun changeRoomRetention(
        command: ChangePrivateRoomRetentionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RetentionChanged> =
        execution.mutate(command.accountId) { session ->
            val roomId = command.roomId.canonical.requireUuid()
            val receipt =
                backend.updateRoomRetention(
                    session,
                    roomId,
                    command.mutationId.canonical.requireUuid(),
                    command.retention,
                )
            PrivateChatMutationReceipt.RetentionChanged(
                accountId = command.accountId,
                roomId = command.roomId,
                mutationId = command.mutationId,
                retention = receipt.retention,
            )
        }

    suspend fun changeRoomPreferences(
        command: ChangePrivateRoomPreferencesCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomPreferencesChanged> =
        execution.mutate(command.accountId) { session ->
            val receipt =
                backend.updateRoomPreferences(
                    session = session,
                    roomId = command.roomId.canonical.requireUuid(),
                    clientMutationId = command.mutationId.canonical.requireUuid(),
                    archiveState = command.archiveState,
                    pinState = command.pinState,
                    muteState = command.muteState,
                )
            PrivateChatMutationReceipt.RoomPreferencesChanged(
                accountId = command.accountId,
                roomId = command.roomId,
                mutationId = command.mutationId,
                archiveState = receipt.archiveState,
                pinState = receipt.pinState,
                muteState = receipt.muteState,
            )
        }

    suspend fun changeActivitySharing(
        command: ChangePrivateActivitySharingCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ActivitySharingChanged> =
        execution.mutate(command.accountId) { session ->
            val profile = backend.updateActivitySharing(session, command.preferences)
            PrivateChatMutationReceipt.ActivitySharingChanged(
                accountId = command.accountId,
                mutationId = command.mutationId,
                preferences = profile.activitySharing,
            )
        }

    suspend fun acknowledgeRoomRead(
        command: AcknowledgePrivateRoomReadCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomReadAcknowledged> =
        execution.mutate(command.accountId) { session ->
            val roomId = command.roomId.canonical.requireUuid()
            command.mutationId.canonical.requireUuid()
            val state = pollingRepository.load(session)
            if (state.rooms[roomId] == null) rejectCommand("This conversation is no longer available.")
            val ownProfile = state.backend.profiles.single { profile -> profile.accountId == session.localSignalAddress.accountId }
            if (ownProfile.activitySharing.readReceipts != PrivateActivitySharingState.ENABLED) {
                rejectCommand("Enable read receipts before sharing that a conversation was read.")
            }
            backend.acknowledgeRoomRead(session, snapshotAssembler.unreadMessageIds(state, roomId))
            PrivateChatMutationReceipt.RoomReadAcknowledged(
                accountId = command.accountId,
                roomId = command.roomId,
                mutationId = command.mutationId,
            )
        }

    suspend fun publishTypingState(
        command: PublishPrivateTypingStateCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.TypingStatePublished> =
        execution.mutate(command.accountId) { session ->
            val roomId = command.roomId.canonical.requireUuid()
            command.mutationId.canonical.requireUuid()
            if (command.typingState == PrivateTypingState.ACTIVE) {
                val state = pollingRepository.load(session)
                if (state.rooms[roomId] == null) rejectCommand("This conversation is no longer available.")
                val ownProfile =
                    state.backend.profiles.single { profile -> profile.accountId == session.localSignalAddress.accountId }
                if (ownProfile.activitySharing.typingIndicators != PrivateActivitySharingState.ENABLED) {
                    rejectCommand("Enable typing indicators before publishing typing activity.")
                }
            }
            backend.publishTyping(session, roomId, command.typingState == PrivateTypingState.ACTIVE)
            PrivateChatMutationReceipt.TypingStatePublished(
                accountId = command.accountId,
                roomId = command.roomId,
                mutationId = command.mutationId,
                typingState = command.typingState,
            )
        }

    suspend fun createOneUseRoomInvitation(
        command: CreatePrivateOneUseRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.OneUseRoomInvitationCreated> =
        execution.mutate(command.accountId) { session ->
            val roomId = command.roomId.canonical.requireUuid()
            val receipt =
                backend.issueInvite(
                    session = session,
                    clientMutationId = command.mutationId.canonical.requireUuid(),
                    kind = PrivateBackendInviteKind.ROOM_MEMBERSHIP,
                    roomId = roomId,
                )
            PrivateChatMutationReceipt.OneUseRoomInvitationCreated(
                accountId = command.accountId,
                roomId = command.roomId,
                mutationId = command.mutationId,
                invitationId = PrivateRoomInvitationId(receipt.invitationId.toString()),
                invitationCode = PrivateRoomInvitationCode(receipt.code),
                expiresAt = receipt.expiresAt,
            )
        }
}

internal fun String.requireUuid(): UUID {
    if (this != lowercase(Locale.ROOT)) rejectCommand("This chat item is invalid. Refresh and try again.")
    val parsed =
        try {
            UUID.fromString(this)
        } catch (error: IllegalArgumentException) {
            rejectCommand("This chat item is invalid. Refresh and try again.")
        }
    if (parsed.toString() != this || parsed == NIL_UUID) {
        rejectCommand("This chat item is invalid. Refresh and try again.")
    }
    return parsed
}

private fun Long.toServerRevision(): Int {
    if (this !in 1L..MAXIMUM_DOMAIN_MESSAGE_REVISION) {
        rejectCommand("This message revision is unsupported. Refresh and try again.")
    }
    return toInt() - 1
}

private fun rejectCommand(userMessage: String): Nothing = throw PrivateChatCommandRejectedException(userMessage)

private const val MAXIMUM_DOMAIN_MESSAGE_REVISION = 101L
private val NIL_UUID = UUID(0L, 0L)
