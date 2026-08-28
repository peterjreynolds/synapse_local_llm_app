package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
    transportMutationsEnabled: Boolean,
    onCreateRoom: (PrivateRoomKind, String, PrivateMessageRetention) -> Unit,
    onRedeemRoomInvitation: (String) -> Unit,
    onDismissOperationNotice: () -> Unit,
    onDismiss: () -> Unit,
) {
    var route by remember { mutableStateOf(PrivateNewConversationRoute.CHOOSER) }
    var roomTitle by remember { mutableStateOf("") }
    var retention by remember { mutableStateOf(PrivateMessageRetention.ONE_DAY) }
    var invitationCode by remember { mutableStateOf("") }
    val operationRunning = operation is PrivateChatOperationUiState.Running
    val actionsEnabled = !operationRunning && transportMutationsEnabled
    ClearConversationInvitationOnStop { invitationCode = "" }
    val navigateBack = {
        if (route == PrivateNewConversationRoute.CHOOSER) {
            onDismiss()
        } else {
            route = PrivateNewConversationRoute.CHOOSER
            invitationCode = ""
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!operationRunning) navigateBack()
        },
        title = { Text(privateNewConversationTitle(route)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrivateChatOperationNotice(
                    operation = operation,
                    onDismiss = onDismissOperationNotice,
                )
                if (!transportMutationsEnabled) {
                    Text(
                        text = "Reconnect before starting or joining a conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                when (route) {
                    PrivateNewConversationRoute.CHOOSER ->
                        PrivateNewConversationChooser(
                            enabled = actionsEnabled,
                            onSelect = { selectedRoute -> route = selectedRoute },
                        )

                    PrivateNewConversationRoute.CREATE_DIRECT,
                    PrivateNewConversationRoute.CREATE_GROUP,
                    ->
                        PrivateCreateConversationForm(
                            route = route,
                            roomTitle = roomTitle,
                            retention = retention,
                            enabled = actionsEnabled,
                            onRoomTitleChanged = { changedTitle -> roomTitle = changedTitle },
                            onRetentionChanged = { changedRetention -> retention = changedRetention },
                        )

                    PrivateNewConversationRoute.JOIN_WITH_CODE ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Paste a one-use conversation invite code from a friend.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = invitationCode,
                                onValueChange = { changedCode -> invitationCode = changedCode },
                                label = { Text("Conversation invite code") },
                                enabled = actionsEnabled,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                }
            }
        },
        confirmButton = {
            when (route) {
                PrivateNewConversationRoute.CHOOSER -> Unit
                PrivateNewConversationRoute.CREATE_DIRECT ->
                    Button(
                        onClick = { onCreateRoom(PrivateRoomKind.DIRECT, roomTitle, retention) },
                        enabled = actionsEnabled && roomTitle.isNotBlank(),
                    ) {
                        Text("Create chat")
                    }

                PrivateNewConversationRoute.CREATE_GROUP ->
                    Button(
                        onClick = { onCreateRoom(PrivateRoomKind.GROUP, roomTitle, retention) },
                        enabled = actionsEnabled && roomTitle.isNotBlank(),
                    ) {
                        Text("Create group")
                    }

                PrivateNewConversationRoute.JOIN_WITH_CODE ->
                    Button(
                        onClick = { onRedeemRoomInvitation(invitationCode) },
                        enabled = actionsEnabled && invitationCode.isNotBlank(),
                    ) {
                        Text("Join")
                    }
            }
        },
        dismissButton = {
            TextButton(
                onClick = navigateBack,
                enabled = !operationRunning,
            ) {
                Text(if (route == PrivateNewConversationRoute.CHOOSER) "Cancel" else "Back")
            }
        },
    )
}

@Composable
private fun PrivateNewConversationChooser(
    enabled: Boolean,
    onSelect: (PrivateNewConversationRoute) -> Unit,
) {
    Column {
        PrivateNewConversationChoice(
            title = "New direct chat",
            detail = "Start a private one-to-one conversation",
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
            enabled = enabled,
            onClick = { onSelect(PrivateNewConversationRoute.CREATE_DIRECT) },
        )
        PrivateNewConversationChoice(
            title = "New group",
            detail = "Create a conversation for several people",
            icon = { Icon(Icons.Default.GroupAdd, contentDescription = null) },
            enabled = enabled,
            onClick = { onSelect(PrivateNewConversationRoute.CREATE_GROUP) },
        )
        PrivateNewConversationChoice(
            title = "Join with code",
            detail = "Use a one-use conversation invite",
            icon = { Icon(Icons.Default.Key, contentDescription = null) },
            enabled = enabled,
            onClick = { onSelect(PrivateNewConversationRoute.JOIN_WITH_CODE) },
        )
    }
}

@Composable
private fun PrivateNewConversationChoice(
    title: String,
    detail: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = icon,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
    )
}

@Composable
private fun PrivateCreateConversationForm(
    route: PrivateNewConversationRoute,
    roomTitle: String,
    retention: PrivateMessageRetention,
    enabled: Boolean,
    onRoomTitleChanged: (String) -> Unit,
    onRetentionChanged: (PrivateMessageRetention) -> Unit,
) {
    val isDirect = route == PrivateNewConversationRoute.CREATE_DIRECT
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = roomTitle,
            onValueChange = onRoomTitleChanged,
            label = { Text(if (isDirect) "Friend or chat name" else "Group name") },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Disappearing messages", style = MaterialTheme.typography.titleSmall)
        PrivateRetentionSelector(
            selectedRetention = retention,
            enabled = enabled,
            onChangeRetention = onRetentionChanged,
        )
        Text(
            text =
                if (isDirect) {
                    "After creating the chat, open its menu and choose Invite person."
                } else {
                    "After creating the group, use its menu to invite people and manage members."
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal enum class PrivateNewConversationRoute {
    CHOOSER,
    CREATE_DIRECT,
    CREATE_GROUP,
    JOIN_WITH_CODE,
}

private fun privateNewConversationTitle(route: PrivateNewConversationRoute): String =
    when (route) {
        PrivateNewConversationRoute.CHOOSER -> "New conversation"
        PrivateNewConversationRoute.CREATE_DIRECT -> "New direct chat"
        PrivateNewConversationRoute.CREATE_GROUP -> "New group"
        PrivateNewConversationRoute.JOIN_WITH_CODE -> "Join a conversation"
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
