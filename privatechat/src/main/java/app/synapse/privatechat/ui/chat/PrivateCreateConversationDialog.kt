package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomKind

@Composable
internal fun PrivateCreateConversationDialog(
    operation: PrivateChatOperationUiState,
    onCreateRoom: (PrivateRoomKind, String, PrivateMessageRetention) -> Unit,
    onRedeemRoomInvitation: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var roomKind by remember { mutableStateOf(PrivateRoomKind.DIRECT) }
    var roomTitle by remember { mutableStateOf("") }
    var retention by remember { mutableStateOf(PrivateMessageRetention.ONE_DAY) }
    var invitationCode by remember { mutableStateOf("") }
    val operationRunning = operation is PrivateChatOperationUiState.Running
    ClearConversationInvitationOnStop { invitationCode = "" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrivateRoomKind.entries.forEach { kind ->
                        FilterChip(
                            selected = roomKind == kind,
                            onClick = { roomKind = kind },
                            enabled = !operationRunning,
                            label = { Text(if (kind == PrivateRoomKind.DIRECT) "Direct" else "Group") },
                        )
                    }
                }
                OutlinedTextField(
                    value = roomTitle,
                    onValueChange = { changedTitle -> roomTitle = changedTitle },
                    label = { Text(if (roomKind == PrivateRoomKind.DIRECT) "Conversation name" else "Group name") },
                    enabled = !operationRunning,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PrivateRetentionSelector(
                    selectedRetention = retention,
                    enabled = !operationRunning,
                    onChangeRetention = { selectedRetention -> retention = selectedRetention },
                )
                Text(
                    text =
                        if (roomKind == PrivateRoomKind.DIRECT) {
                            "After creation, open the conversation and share its one-use invite with exactly one person."
                        } else {
                            "After creation, add people with one-use conversation invites and manage their roles from Members."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Join a conversation", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = invitationCode,
                    onValueChange = { changedCode -> invitationCode = changedCode },
                    label = { Text("One-use invitation code") },
                    enabled = !operationRunning,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { onRedeemRoomInvitation(invitationCode) },
                    enabled = !operationRunning && invitationCode.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Join with code")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreateRoom(roomKind, roomTitle, retention) },
                enabled = !operationRunning && roomTitle.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operationRunning) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ClearConversationInvitationOnStop(clearInvitation: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentClearInvitation by rememberUpdatedState(clearInvitation)
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) currentClearInvitation()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            currentClearInvitation()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
