package app.synapse.privatechat.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.chat.PrivateChatMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt

@Composable
internal fun PrivateChatOperationNotice(
    operation: PrivateChatOperationUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice = privateOperationNotice(operation) ?: return
    val confirmed =
        operation is PrivateChatOperationUiState.Confirmed ||
            operation is PrivateChatOperationUiState.Recovered
    Surface(
        onClick = onDismiss,
        modifier = modifier.fillMaxWidth(),
        color =
            if (confirmed) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
            },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = notice,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (confirmed) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
    }
}

@Composable
internal fun PrivateRoomInvitationDialog(
    invitationState: PrivateRoomInvitationUiState,
    onDismiss: () -> Unit,
) {
    when (invitationState) {
        is PrivateRoomInvitationUiState.Confirmed ->
            PrivateConfirmedInvitationDialog(
                title = "One-use conversation invitation",
                detail = "Share this code with one person. It expires and cannot be reused after redemption.",
                transferContent =
                    PrivateInvitationTransferContent.forConversation(
                        invitationState.receipt.invitationCode,
                    ),
                expiryLabel = privateRemainingTimeLabel(invitationState.receipt.expiresAt),
                onDismiss = onDismiss,
            )

        is PrivateRoomInvitationUiState.Rejected ->
            PrivateInvitationFailureDialog(invitationState.userMessage, onDismiss)

        PrivateRoomInvitationUiState.TransportUnavailable ->
            PrivateInvitationFailureDialog("Invitation transport is unavailable. No invitation was created.", onDismiss)

        PrivateRoomInvitationUiState.UnexpectedFailure ->
            PrivateInvitationFailureDialog("The invitation could not be confirmed. No code is being shown.", onDismiss)

        PrivateRoomInvitationUiState.Creating,
        PrivateRoomInvitationUiState.Hidden,
        -> Unit
    }
}

@Composable
internal fun PrivateAccountInvitationDialog(
    invitationState: PrivateAccountInvitationUiState,
    onDismiss: () -> Unit,
) {
    when (invitationState) {
        is PrivateAccountInvitationUiState.Confirmed ->
            PrivateConfirmedInvitationDialog(
                title = "One-use account invitation",
                detail = "Share this code privately. One person can use it to create a Synapse Private account.",
                transferContent =
                    PrivateInvitationTransferContent.forAccount(
                        invitationState.receipt.invitationCode,
                    ),
                expiryLabel = privateRemainingTimeLabel(invitationState.receipt.expiresAt),
                onDismiss = onDismiss,
            )

        is PrivateAccountInvitationUiState.Rejected ->
            PrivateInvitationFailureDialog(invitationState.userMessage, onDismiss)

        PrivateAccountInvitationUiState.TransportUnavailable ->
            PrivateInvitationFailureDialog("Account invitation transport is unavailable. No invitation was created.", onDismiss)

        PrivateAccountInvitationUiState.UnexpectedFailure ->
            PrivateInvitationFailureDialog("The account invitation could not be confirmed. No code is being shown.", onDismiss)

        PrivateAccountInvitationUiState.Creating,
        PrivateAccountInvitationUiState.Hidden,
        -> Unit
    }
}

@Composable
private fun PrivateConfirmedInvitationDialog(
    title: String,
    detail: String,
    transferContent: PrivateInvitationTransferContent,
    expiryLabel: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(detail)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = transferContent.exposeCodeForUserAction(),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = "Expires in $expiryLabel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val message =
                                when (copyPrivateInvitationCode(context, transferContent)) {
                                    PrivateInvitationCopyOutcome.COPIED -> "Invitation code copied."
                                    PrivateInvitationCopyOutcome.CLIPBOARD_UNAVAILABLE ->
                                        "The invitation code could not be copied."
                                }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Copy code")
                    }
                    OutlinedButton(
                        onClick = {
                            if (
                                sharePrivateInvitationCode(context, transferContent) ==
                                PrivateInvitationShareOutcome.SHARE_UNAVAILABLE
                            ) {
                                Toast
                                    .makeText(
                                        context,
                                        "No app is available to share the invitation code.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Share code")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun PrivateInvitationFailureDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invitation not created") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
internal fun PrivateConversationStatus(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    showProgress: Boolean = false,
    showBackButton: Boolean = false,
    onShowRoomList: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showBackButton) {
            OutlinedButton(onClick = onShowRoomList) {
                Text("Back to rooms")
            }
            Spacer(Modifier.height(16.dp))
        }
        if (showProgress) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun privateOperationNotice(operation: PrivateChatOperationUiState): String? =
    when (operation) {
        is PrivateChatOperationUiState.Confirmed ->
            when (operation.receipt) {
                is PrivateChatMutationReceipt.ActivitySharingChanged -> "Activity sharing updated."
                is PrivateChatMutationReceipt.MessageDeletedForEveryone -> "Message deleted for everyone."
                is PrivateChatMutationReceipt.MessageEdited -> "Message edit confirmed."
                is PrivateChatMutationReceipt.MessageSent -> "Message send confirmed."
                is PrivateChatMutationReceipt.ReactionChanged -> "Reaction updated."
                is PrivateChatMutationReceipt.RetentionChanged -> "Message retention updated."
                is PrivateChatMutationReceipt.RoomPreferencesChanged -> "Conversation preferences updated."
                is PrivateSocialMutationReceipt.ProfileUpdated -> "Profile updated."
                is PrivateSocialMutationReceipt.RoomCreated -> "Conversation created. Invite its first peer from the conversation."
                is PrivateSocialMutationReceipt.RoomInvitationRedeemed ->
                    "Conversation joined. Its encrypted title may take a moment to arrive."
                is PrivateSocialMutationReceipt.GroupMemberRoleChanged -> "Group member role updated."
                is PrivateSocialMutationReceipt.GroupMemberRemoved -> "Group member removed."
                is PrivateSocialMutationReceipt.PresenceSharingChanged -> "Online presence preference updated."
                is PrivateChatMutationReceipt.OneUseRoomInvitationCreated,
                is PrivateChatMutationReceipt.RoomReadAcknowledged,
                is PrivateChatMutationReceipt.TypingStatePublished,
                is PrivateSocialMutationReceipt.OneUseAccountInvitationCreated,
                is PrivateSocialMutationReceipt.PresencePublished,
                -> null
            }

        is PrivateChatOperationUiState.InvalidInput -> operation.userMessage
        is PrivateChatOperationUiState.Recovered ->
            when (operation.kind) {
                PrivateChatOperationKind.SEND_MESSAGE -> "Message send recovered after reconnecting."
                PrivateChatOperationKind.EDIT_MESSAGE -> "Message edit recovered after reconnecting."
                PrivateChatOperationKind.CHANGE_REACTION -> "Reaction recovered after reconnecting."
                PrivateChatOperationKind.CREATE_ROOM -> "Conversation creation recovered after reconnecting."
                else -> "Earlier request recovered after reconnecting."
            }

        is PrivateChatOperationUiState.Rejected -> operation.userMessage
        PrivateChatOperationUiState.TransportUnavailable ->
            "The request was not confirmed because conversation transport is unavailable."
        PrivateChatOperationUiState.UnexpectedFailure ->
            "The request could not be confirmed. No success was recorded."
        PrivateChatOperationUiState.Idle,
        is PrivateChatOperationUiState.Running,
        -> null
    }
