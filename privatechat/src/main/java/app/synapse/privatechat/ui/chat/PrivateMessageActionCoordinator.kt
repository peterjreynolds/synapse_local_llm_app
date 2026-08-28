package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.ChangePrivateReactionCommand
import app.synapse.privatechat.domain.chat.DeletePrivateMessageForEveryoneCommand
import app.synapse.privatechat.domain.chat.EditPrivateMessageCommand
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateClientMutationIdFactory
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageOwnership
import app.synapse.privatechat.domain.chat.PrivateMessageSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageTextValidation
import app.synapse.privatechat.domain.chat.PrivateReactionChange
import app.synapse.privatechat.domain.chat.PrivateReactionSelectionState
import app.synapse.privatechat.domain.chat.PrivateReactionValidation
import app.synapse.privatechat.domain.chat.PrivateReplyPreview
import app.synapse.privatechat.domain.chat.PrivateTypingState
import app.synapse.privatechat.domain.chat.SendPrivateMessageCommand
import app.synapse.privatechat.domain.chat.validatePrivateMessageText
import app.synapse.privatechat.domain.chat.validatePrivateReaction

internal class PrivateMessageActionCoordinator(
    private val gateway: PrivateChatGateway,
    private val mutationIdFactory: PrivateClientMutationIdFactory,
    private val stateStore: PrivateChatUiStateStore,
    private val mutationCoordinator: PrivateConfirmedMutationCoordinator,
    private val activitySharingCoordinator: PrivateActivitySharingCoordinator,
    private val activeAccountId: () -> PrivateAccountId?,
) {
    private var pendingMutationRecovery: PrivatePendingMessageMutationRecovery? = null

    fun updateComposerText(text: String) {
        if (text.length > PRIVATE_COMPOSER_INPUT_LIMIT) return
        val wasBlank = stateStore.current.composerText.isBlank()
        stateStore.update { state -> state.copy(composerText = text) }
        val isBlank = text.isBlank()
        if (wasBlank != isBlank && conversationTransportIsConnected()) {
            activitySharingCoordinator.publishTypingStateIfEnabled(
                if (isBlank) PrivateTypingState.INACTIVE else PrivateTypingState.ACTIVE,
            )
        }
    }

    fun beginReply(messageId: PrivateMessageId) {
        val message = findPresentedMessage(messageId) ?: return
        stateStore.update { state ->
            state.copy(
                composerMode =
                    PrivateComposerMode.ReplyingTo(
                        PrivateReplyPreview(
                            messageId = message.messageId,
                            senderDisplayName = message.senderDisplayName,
                            body = message.body,
                        ),
                    ),
            )
        }
    }

    fun beginEdit(messageId: PrivateMessageId) {
        val message = findPresentedMessage(messageId) ?: return
        if (message.ownership != PrivateMessageOwnership.CURRENT_ACCOUNT) {
            mutationCoordinator.rejectAction("Only your own messages can be edited.")
            return
        }
        val previousDraft = stateStore.current.composerText
        stateStore.update { state ->
            state.copy(
                composerText = message.body.plaintext,
                composerMode =
                    PrivateComposerMode.Editing(
                        messageId = message.messageId,
                        expectedRevision = message.revision,
                        originalBody = message.body,
                        draftBeforeEdit = previousDraft,
                    ),
            )
        }
        if (conversationTransportIsConnected()) {
            activitySharingCoordinator.publishTypingStateIfEnabled(PrivateTypingState.ACTIVE)
        }
    }

    fun cancelComposerContext() {
        stateStore.update { state ->
            val restoredText =
                when (val mode = state.composerMode) {
                    is PrivateComposerMode.Editing -> mode.draftBeforeEdit
                    PrivateComposerMode.NewMessage,
                    is PrivateComposerMode.ReplyingTo,
                    -> state.composerText
                }
            state.copy(
                composerText = restoredText,
                composerMode = PrivateComposerMode.NewMessage,
            )
        }
        publishInactiveTypingForBlankComposer()
    }

    fun submitComposer() {
        val accountId = activeAccountId() ?: return
        val submittedState = stateStore.current
        val submittedComposer =
            PrivateSubmittedComposerState(
                text = submittedState.composerText,
                mode = submittedState.composerMode,
            )
        val conversation = connectedConversationSnapshotOrReject(submittedState) ?: return
        when (val validation = validatePrivateMessageText(submittedState.composerText)) {
            is PrivateMessageTextValidation.Rejected ->
                mutationCoordinator.rejectChatInput(validation.field, validation.userMessage)

            is PrivateMessageTextValidation.Accepted ->
                when (val mode = submittedState.composerMode) {
                    PrivateComposerMode.NewMessage,
                    is PrivateComposerMode.ReplyingTo,
                    -> {
                        val command =
                            SendPrivateMessageCommand(
                                accountId = accountId,
                                roomId = conversation.room.roomId,
                                mutationId = mutationIdFactory.createMutationId(),
                                body = validation.message,
                                replyToMessageId = (mode as? PrivateComposerMode.ReplyingTo)?.preview?.messageId,
                            )
                        mutationCoordinator.requestConfirmedMutation(
                            kind = PrivateChatOperationKind.SEND_MESSAGE,
                            request = { gateway.sendMessage(command) },
                            receiptMatches = { receipt -> PrivateChatReceiptValidator.matches(receipt, command) },
                            onConfirmed = { clearSubmittedComposer(submittedComposer) },
                            onTransportUnavailable = {
                                pendingMutationRecovery =
                                    PrivatePendingMessageMutationRecovery.Composer(
                                        mutationId = command.mutationId,
                                        kind = PrivateChatOperationKind.SEND_MESSAGE,
                                        submittedComposer = submittedComposer,
                                    )
                                markConversationReconnecting()
                            },
                        )
                    }

                    is PrivateComposerMode.Editing ->
                        submitMessageEdit(
                            accountId = accountId,
                            roomId = conversation.room.roomId,
                            mode = mode,
                            validation = validation,
                            submittedComposer = submittedComposer,
                        )
                }
        }
    }

    fun toggleReaction(
        messageId: PrivateMessageId,
        reactionInput: String,
    ) {
        val accountId = activeAccountId() ?: return
        val message = findConnectedMessageOrReject(messageId) ?: return
        when (val validation = validatePrivateReaction(reactionInput)) {
            is PrivateReactionValidation.Rejected ->
                mutationCoordinator.rejectChatInput(validation.field, validation.userMessage)

            is PrivateReactionValidation.Accepted -> {
                val selectedReaction =
                    message.reactions.firstOrNull { summary -> summary.reaction == validation.reaction }
                val change =
                    if (selectedReaction?.selectionState == PrivateReactionSelectionState.SELECTED) {
                        PrivateReactionChange.REMOVE
                    } else {
                        PrivateReactionChange.ADD
                    }
                val command =
                    ChangePrivateReactionCommand(
                        accountId = accountId,
                        roomId = message.roomId,
                        messageId = message.messageId,
                        mutationId = mutationIdFactory.createMutationId(),
                        reaction = validation.reaction,
                        change = change,
                    )
                mutationCoordinator.requestConfirmedMutation(
                    kind = PrivateChatOperationKind.CHANGE_REACTION,
                    request = { gateway.changeReaction(command) },
                    receiptMatches = { receipt -> PrivateChatReceiptValidator.matches(receipt, command) },
                    onTransportUnavailable = {
                        pendingMutationRecovery =
                            PrivatePendingMessageMutationRecovery.WithoutComposer(
                                mutationId = command.mutationId,
                                kind = PrivateChatOperationKind.CHANGE_REACTION,
                            )
                        markConversationReconnecting()
                    },
                )
            }
        }
    }

    fun deleteMessageForEveryone(messageId: PrivateMessageId) {
        val accountId = activeAccountId() ?: return
        val message = findConnectedMessageOrReject(messageId) ?: return
        if (message.ownership != PrivateMessageOwnership.CURRENT_ACCOUNT) {
            mutationCoordinator.rejectAction("Only your own messages can be deleted for everyone.")
            return
        }
        val command =
            DeletePrivateMessageForEveryoneCommand(
                accountId = accountId,
                roomId = message.roomId,
                messageId = message.messageId,
                mutationId = mutationIdFactory.createMutationId(),
                expectedRevision = message.revision,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.DELETE_MESSAGE_FOR_EVERYONE,
            request = { gateway.deleteMessageForEveryone(command) },
            receiptMatches = { receipt -> PrivateChatReceiptValidator.matches(receipt, command) },
            onTransportUnavailable = ::markConversationReconnecting,
        )
    }

    fun clearComposer() {
        stateStore.update { state ->
            state.copy(composerText = "", composerMode = PrivateComposerMode.NewMessage)
        }
    }

    /** Drops account-scoped plaintext and recovery correlation before the ViewModel changes owners. */
    fun resetForAccountTransition() {
        pendingMutationRecovery = null
        clearComposer()
    }

    fun acceptRecoveredMutations(recoveredMutationIds: Set<PrivateClientMutationId>) {
        val pendingRecovery = pendingMutationRecovery ?: return
        if (pendingRecovery.mutationId !in recoveredMutationIds) return
        pendingMutationRecovery = null
        if (pendingRecovery is PrivatePendingMessageMutationRecovery.Composer) {
            clearSubmittedComposer(pendingRecovery.submittedComposer)
        }
        stateStore.update { state ->
            if (state.operation is PrivateChatOperationUiState.TransportUnavailable) {
                state.copy(operation = PrivateChatOperationUiState.Recovered(pendingRecovery.kind))
            } else {
                state
            }
        }
    }

    private fun submitMessageEdit(
        accountId: PrivateAccountId,
        roomId: app.synapse.privatechat.domain.chat.PrivateRoomId,
        mode: PrivateComposerMode.Editing,
        validation: PrivateMessageTextValidation.Accepted,
        submittedComposer: PrivateSubmittedComposerState,
    ) {
        if (validation.message == mode.originalBody) {
            mutationCoordinator.rejectAction("Change the message before saving it.")
            return
        }
        val command =
            EditPrivateMessageCommand(
                accountId = accountId,
                roomId = roomId,
                messageId = mode.messageId,
                mutationId = mutationIdFactory.createMutationId(),
                expectedRevision = mode.expectedRevision,
                revisedBody = validation.message,
            )
        mutationCoordinator.requestConfirmedMutation(
            kind = PrivateChatOperationKind.EDIT_MESSAGE,
            request = { gateway.editMessage(command) },
            receiptMatches = { receipt -> PrivateChatReceiptValidator.matches(receipt, command) },
            onConfirmed = { clearSubmittedComposer(submittedComposer) },
            onTransportUnavailable = {
                pendingMutationRecovery =
                    PrivatePendingMessageMutationRecovery.Composer(
                        mutationId = command.mutationId,
                        kind = PrivateChatOperationKind.EDIT_MESSAGE,
                        submittedComposer = submittedComposer,
                    )
                markConversationReconnecting()
            },
        )
    }

    private fun clearSubmittedComposer(submittedComposer: PrivateSubmittedComposerState) {
        stateStore.update { state ->
            if (
                state.composerText == submittedComposer.text &&
                state.composerMode == submittedComposer.mode
            ) {
                state.copy(composerText = "", composerMode = PrivateComposerMode.NewMessage)
            } else {
                state
            }
        }
        publishInactiveTypingForBlankComposer()
    }

    private fun publishInactiveTypingForBlankComposer() {
        if (stateStore.current.composerText.isBlank() && conversationTransportIsConnected()) {
            activitySharingCoordinator.publishTypingStateIfEnabled(PrivateTypingState.INACTIVE)
        }
    }

    private fun connectedConversationSnapshotOrReject(
        state: PrivateChatUiState = stateStore.current,
    ): app.synapse.privatechat.domain.chat.PrivateConversationSnapshot? {
        val snapshot = PrivateChatMutationAvailability.connectedConversationSnapshot(state)
        if (snapshot == null) {
            mutationCoordinator.rejectAction(PRIVATE_RECONNECT_BEFORE_MUTATION_MESSAGE)
        }
        return snapshot
    }

    private fun findConnectedMessageOrReject(messageId: PrivateMessageId): PrivateMessageSnapshot? =
        connectedConversationSnapshotOrReject()
            ?.messages
            ?.firstOrNull { message -> message.messageId == messageId }

    private fun conversationTransportIsConnected(): Boolean =
        PrivateChatMutationAvailability.connectedConversationSnapshot(stateStore.current) != null

    private fun markConversationReconnecting() {
        stateStore.update(PrivateChatUiReducer::markConversationTransportUnavailable)
    }

    private fun findPresentedMessage(messageId: PrivateMessageId): PrivateMessageSnapshot? =
        PrivateChatUiReducer.findPresentedMessage(stateStore.current, messageId)
}

internal const val PRIVATE_COMPOSER_INPUT_LIMIT = 4_096
internal const val PRIVATE_RECONNECT_BEFORE_MUTATION_MESSAGE =
    "Reconnect before sending or changing conversation data."

private sealed interface PrivatePendingMessageMutationRecovery {
    val mutationId: PrivateClientMutationId
    val kind: PrivateChatOperationKind

    data class Composer(
        override val mutationId: PrivateClientMutationId,
        override val kind: PrivateChatOperationKind,
        val submittedComposer: PrivateSubmittedComposerState,
    ) : PrivatePendingMessageMutationRecovery

    data class WithoutComposer(
        override val mutationId: PrivateClientMutationId,
        override val kind: PrivateChatOperationKind,
    ) : PrivatePendingMessageMutationRecovery
}

private data class PrivateSubmittedComposerState(
    val text: String,
    val mode: PrivateComposerMode,
) {
    override fun toString(): String = "PrivateSubmittedComposerState(text=[REDACTED], mode=${mode::class.simpleName})"
}
