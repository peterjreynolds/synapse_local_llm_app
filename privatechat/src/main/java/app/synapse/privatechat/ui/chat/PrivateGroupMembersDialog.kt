package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMemberSnapshot

@Composable
internal fun PrivateGroupMembersDialog(
    conversationState: PrivateConversationUiState,
    operation: PrivateChatOperationUiState,
    onChangeRole: (PrivateRoomMemberSnapshot, PrivateRoomMemberRole) -> Unit,
    onRemoveMember: (PrivateRoomMemberSnapshot) -> Unit,
    onDismiss: () -> Unit,
) {
    val conversation = conversationState as? PrivateConversationUiState.Available
    val snapshot = conversation?.snapshot?.takeIf { it.room.kind == PrivateRoomKind.GROUP }
    val operationRunning = operation is PrivateChatOperationUiState.Running
    val memberMutationsEnabled =
        !operationRunning && conversation?.connectionState == PrivateChatConnectionUiState.CONNECTED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group members") },
        text = {
            if (snapshot == null) {
                Text("Verified group membership is unavailable.")
            } else {
                val actorRole =
                    snapshot.members.first { member -> member.accountId == snapshot.accountId }.role
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = snapshot.members,
                        key = { member -> member.accountId.canonical },
                    ) { member ->
                        PrivateGroupMemberRow(
                            member = member,
                            currentAccountId = snapshot.accountId,
                            actorRole = actorRole,
                            enabled = memberMutationsEnabled,
                            onChangeRole = onChangeRole,
                            onRemoveMember = onRemoveMember,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !operationRunning) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun PrivateGroupMemberRow(
    member: PrivateRoomMemberSnapshot,
    currentAccountId: PrivateAccountId,
    actorRole: PrivateRoomMemberRole,
    enabled: Boolean,
    onChangeRole: (PrivateRoomMemberSnapshot, PrivateRoomMemberRole) -> Unit,
    onRemoveMember: (PrivateRoomMemberSnapshot) -> Unit,
) {
    val isCurrentAccount = member.accountId == currentAccountId
    val ownerCanManage = actorRole == PrivateRoomMemberRole.OWNER && member.role != PrivateRoomMemberRole.OWNER
    val adminCanRemove = actorRole == PrivateRoomMemberRole.ADMIN && member.role == PrivateRoomMemberRole.MEMBER
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(member.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text =
                        buildString {
                            append(
                                member.role.name
                                    .lowercase()
                                    .replaceFirstChar(Char::uppercase),
                            )
                            if (isCurrentAccount) append(" · You")
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (!isCurrentAccount && (ownerCanManage || adminCanRemove)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (ownerCanManage) {
                        TextButton(
                            onClick = {
                                onChangeRole(
                                    member,
                                    if (member.role == PrivateRoomMemberRole.ADMIN) {
                                        PrivateRoomMemberRole.MEMBER
                                    } else {
                                        PrivateRoomMemberRole.ADMIN
                                    },
                                )
                            },
                            enabled = enabled,
                        ) {
                            Text(if (member.role == PrivateRoomMemberRole.ADMIN) "Make member" else "Make admin")
                        }
                    }
                    TextButton(
                        onClick = { onRemoveMember(member) },
                        enabled = enabled,
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}
