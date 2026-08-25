package app.synapse.privatechat.ui.chat

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    val snapshot = conversation.snapshot
    var pendingDeletion by remember(snapshot.room.roomId) { mutableStateOf<PrivateMessageId?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        PrivateConversationHeader(
            room = snapshot.room,
            showBackButton = showBackButton,
            actionRunning = actionRunning,
            invitationCreating = state.roomInvitation is PrivateRoomInvitationUiState.Creating,
            navigationActions = navigationActions,
            roomActions = roomActions,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PrivateRetentionSelector(
            selectedRetention = snapshot.room.retention,
            enabled = !actionRunning,
            onChangeRetention = roomActions.changeRetention,
            modifier = Modifier.padding(horizontal = tokens.spacing.large, vertical = tokens.spacing.small),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PrivateMessageTimeline(
            snapshot = snapshot,
            enabled = !actionRunning,
            onReply = messageActions.beginReply,
            onEdit = messageActions.beginEdit,
            onReact = messageActions.toggleReaction,
            onDelete = { messageId -> pendingDeletion = messageId },
            modifier = Modifier.weight(1f),
        )
        PrivateMessageComposer(
            text = state.composerText,
            mode = state.composerMode,
            enabled = !actionRunning,
            onTextChanged = messageActions.changeComposerText,
            onSubmit = messageActions.submitComposer,
            onCancelContext = messageActions.cancelComposerContext,
            modifier = Modifier.padding(tokens.spacing.large),
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
