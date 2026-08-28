package app.synapse.privatechat.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.ui.theme.SynapsePrivateDesignSystem

@Composable
internal fun PrivateConversationPane(
    state: PrivateChatUiState,
    showBackButton: Boolean,
    navigationActions: PrivateChatNavigationActions,
    messageActions: PrivateMessageUiActions,
    roomActions: PrivateRoomUiActions,
    modifier: Modifier = Modifier,
) {
    when (val conversation = state.conversation) {
        is PrivateConversationUiState.Available ->
            AvailableConversation(
                state = state,
                conversation = conversation,
                showBackButton = showBackButton,
                navigationActions = navigationActions,
                messageActions = messageActions,
                roomActions = roomActions,
                modifier = modifier,
            )

        PrivateConversationUiState.Loading ->
            PrivateConversationStatus(
                title = "Loading conversation",
                detail = "Waiting for current messages and room settings.",
                showProgress = true,
                modifier = modifier,
            )

        PrivateConversationUiState.TransportUnavailable ->
            PrivateConversationStatus(
                title = "Conversation unavailable",
                detail = "No message data was loaded because the transport connection is unavailable.",
                showBackButton = showBackButton,
                onShowRoomList = navigationActions.showRoomList,
                modifier = modifier,
            )

        PrivateConversationUiState.UnexpectedFailure ->
            PrivateConversationStatus(
                title = "Conversation could not be verified",
                detail = "The returned data was not accepted. No messages or settings were changed.",
                showBackButton = showBackButton,
                onShowRoomList = navigationActions.showRoomList,
                modifier = modifier,
            )

        PrivateConversationUiState.NotSelected ->
            PrivateConversationStatus(
                title = "Choose a conversation",
                detail = "Select a direct conversation or group from the room list.",
                modifier = modifier,
            )
    }
}

@Composable
private fun AvailableConversation(
    state: PrivateChatUiState,
    conversation: PrivateConversationUiState.Available,
    showBackButton: Boolean,
    navigationActions: PrivateChatNavigationActions,
    messageActions: PrivateMessageUiActions,
    roomActions: PrivateRoomUiActions,
    modifier: Modifier,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    val actionRunning = state.operation is PrivateChatOperationUiState.Running
    val localInteractionsEnabled = !actionRunning
    val transportMutationsEnabled =
        localInteractionsEnabled &&
            PrivateChatMutationAvailability.connectedConversationSnapshot(state) != null
    val snapshot = conversation.snapshot
    val context = LocalContext.current
    var selectedMessageId by remember(snapshot.room.roomId) { mutableStateOf<PrivateMessageId?>(null) }
    var pendingDeletion by remember(snapshot.room.roomId) { mutableStateOf<PrivateMessageId?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        PrivateConversationHeader(
            room = snapshot.room,
            showBackButton = showBackButton,
            mutationEnabled = transportMutationsEnabled,
            invitationCreating = state.roomInvitation is PrivateRoomInvitationUiState.Creating,
            navigationActions = navigationActions,
            roomActions = roomActions,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (conversation.connectionState == PrivateChatConnectionUiState.RECONNECTING) {
            PrivateChatReconnectingBanner(
                modifier = Modifier.padding(horizontal = tokens.spacing.large, vertical = tokens.spacing.small),
            )
        }
        PrivateMessageTimeline(
            snapshot = snapshot,
            interactionEnabled = localInteractionsEnabled,
            reactionEnabled = transportMutationsEnabled,
            onSelectMessage = { messageId -> selectedMessageId = messageId },
            onReact = messageActions.toggleReaction,
            modifier = Modifier.weight(1f),
        )
        key(snapshot.room.roomId) {
            PrivateMessageComposer(
                text = state.composerText,
                mode = state.composerMode,
                inputEnabled = localInteractionsEnabled,
                submitEnabled = transportMutationsEnabled,
                onTextChanged = messageActions.changeComposerText,
                onSubmit = messageActions.submitComposer,
                onCancelContext = messageActions.cancelComposerContext,
                modifier = Modifier.padding(tokens.spacing.large),
            )
        }
    }

    snapshot.messages.firstOrNull { message -> message.messageId == selectedMessageId }?.let { message ->
        PrivateMessageActionsDialog(
            message = message,
            localActionsEnabled = localInteractionsEnabled,
            transportActionsEnabled = transportMutationsEnabled,
            onDismiss = { selectedMessageId = null },
            onReply = { messageActions.beginReply(message.messageId) },
            onCopy = {
                val clipboardOwner = privateSensitiveClipboardOwner(context)
                val copyOutcome = clipboardOwner?.copyMessageText(message.body)
                val copyMessage =
                    if (copyOutcome == PrivateSensitiveClipboardCopyOutcome.COPIED) {
                        "Message copied. Synapse will clear it after one minute if unchanged."
                    } else {
                        "The message could not be copied."
                    }
                Toast.makeText(context, copyMessage, Toast.LENGTH_SHORT).show()
            },
            onEdit = { messageActions.beginEdit(message.messageId) },
            onReact = { emoji -> messageActions.toggleReaction(message.messageId, emoji) },
            onDeleteForEveryone = { pendingDeletion = message.messageId },
        )
    }

    pendingDeletion?.let { messageId ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete for everyone?") },
            text = { Text("This requests deletion for every participant and cannot be undone in this app.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeletion = null
                        messageActions.deleteForEveryone(messageId)
                    },
                    enabled = transportMutationsEnabled,
                ) {
                    Text("Delete for everyone")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
