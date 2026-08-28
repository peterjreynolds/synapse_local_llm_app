package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.ChangePrivateReactionCommand
import app.synapse.privatechat.domain.chat.DeletePrivateMessageForEveryoneCommand
import app.synapse.privatechat.domain.chat.EditPrivateMessageCommand
import app.synapse.privatechat.domain.chat.PrivateChatGateway
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
    fun updateComposerText(text: String) {
        if (text.length > PRIVATE_COMPOSER_INPUT_LIMIT) return
        val wasBlank = stateStore.current.composerText.isBlank()
        stateStore.update { state -> state.copy(composerText = text) }
        val isBlank = text.isBlank()
        if (wasBlank != isBlank) {
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
        activitySharingCoordinator.publishTypingStateIfEnabled(PrivateTypingState.ACTIVE)
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
        val conversation =
            (submittedState.conversation as? PrivateConversationUiState.Available)?.snapshot ?: return
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
                            onConfirmed = { clearSubmittedComposer(submittedState) },
                        )
                    }

                    is PrivateComposerMode.Editing ->
                        submitMessageEdit(
                            accountId = accountId,
                            roomId = conversation.room.roomId,
                            mode = mode,
                            validation = validation,
                            submittedState = submittedState,
                        )
                }
        }
    }

    fun toggleReaction(
        messageId: PrivateMessageId,
        reactionInput: String,
    ) {
        val accountId = activeAccountId() ?: return
        val message = findPresentedMessage(messageId) ?: return
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
                )
            }
        }
    }

    fun deleteMessageForEveryone(messageId: PrivateMessageId) {
        val accountId = activeAccountId() ?: return
        val message = findPresentedMessage(messageId) ?: return
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
        )
    }

    fun clearComposer() {
        stateStore.update { state ->
            state.copy(composerText = "", composerMode = PrivateComposerMode.NewMessage)
        }
    }

    private fun submitMessageEdit(
        accountId: PrivateAccountId,
        roomId: app.synapse.privatechat.domain.chat.PrivateRoomId,
        mode: PrivateComposerMode.Editing,
        validation: PrivateMessageTextValidation.Accepted,
        submittedState: PrivateChatUiState,
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
            onConfirmed = { clearSubmittedComposer(submittedState) },
        )
    }

    private fun clearSubmittedComposer(submittedState: PrivateChatUiState) {
        stateStore.update { state ->
            if (
                state.composerText == submittedState.composerText &&
                state.composerMode == submittedState.composerMode
            ) {
                state.copy(composerText = "", composerMode = PrivateComposerMode.NewMessage)
            } else {
                state
            }
        }
        publishInactiveTypingForBlankComposer()
    }

    private fun publishInactiveTypingForBlankComposer() {
        if (stateStore.current.composerText.isBlank()) {
            activitySharingCoordinator.publishTypingStateIfEnabled(PrivateTypingState.INACTIVE)
        }
    }

    private fun findPresentedMessage(messageId: PrivateMessageId): PrivateMessageSnapshot? =
        PrivateChatUiReducer.findPresentedMessage(stateStore.current, messageId)
}

internal const val PRIVATE_COMPOSER_INPUT_LIMIT = 4_096
