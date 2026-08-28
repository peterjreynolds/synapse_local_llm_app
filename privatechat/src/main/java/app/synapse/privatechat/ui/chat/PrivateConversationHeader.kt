package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMetadataState
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.ui.theme.SynapsePrivateDesignSystem

@Composable
internal fun PrivateConversationHeader(
    room: PrivateRoomSummary,
    showBackButton: Boolean,
    mutationEnabled: Boolean,
    invitationCreating: Boolean,
    navigationActions: PrivateChatNavigationActions,
    roomActions: PrivateRoomUiActions,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    var showMenu by remember(room.roomId) { mutableStateOf(false) }
    var showRetentionDialog by remember(room.roomId) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.spacing.compact, vertical = tokens.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.compact),
    ) {
        if (showBackButton) {
            IconButton(onClick = navigationActions.showRoomList) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to chats",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        PrivateConversationAvatar(room)
        Column(
            modifier = Modifier.weight(1f).clickable { showMenu = true },
        ) {
            Text(
                text = room.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = privateConversationSubtitle(room),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(
                onClick = { showMenu = true },
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Conversation menu",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                if (room.kind == PrivateRoomKind.GROUP || room.participantCount == 1) {
                    DropdownMenuItem(
                        text = {
                            Text(if (room.kind == PrivateRoomKind.GROUP) "Invite people" else "Invite person")
                        },
                        onClick = {
                            showMenu = false
                            roomActions.createOneUseInvitation()
                        },
                        leadingIcon = {
                            if (invitationCreating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                            }
                        },
                        enabled = mutationEnabled && !invitationCreating,
                    )
                }
                if (room.kind == PrivateRoomKind.GROUP) {
                    DropdownMenuItem(
                        text = { Text("Group members") },
                        onClick = {
                            showMenu = false
                            navigationActions.showGroupManagement()
                        },
                        leadingIcon = { Icon(Icons.Default.Groups, contentDescription = null) },
                        enabled = mutationEnabled,
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Disappearing messages · ${room.retention.label}") },
                    onClick = {
                        showMenu = false
                        showRetentionDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                    enabled = mutationEnabled,
                )
                DropdownMenuItem(
                    text = { Text(if (room.pinState == PrivateRoomPinState.PINNED) "Unpin chat" else "Pin chat") },
                    onClick = {
                        showMenu = false
                        roomActions.changePinState(
                            if (room.pinState == PrivateRoomPinState.PINNED) {
                                PrivateRoomPinState.UNPINNED
                            } else {
                                PrivateRoomPinState.PINNED
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                    enabled = mutationEnabled,
                )
                DropdownMenuItem(
                    text = { Text(if (room.muteState == PrivateRoomMuteState.MUTED) "Unmute chat" else "Mute chat") },
                    onClick = {
                        showMenu = false
                        roomActions.changeMuteState(
                            if (room.muteState == PrivateRoomMuteState.MUTED) {
                                PrivateRoomMuteState.AUDIBLE
                            } else {
                                PrivateRoomMuteState.MUTED
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.NotificationsOff, contentDescription = null) },
                    enabled = mutationEnabled,
                )
                DropdownMenuItem(
                    text = {
                        Text(if (room.archiveState == PrivateRoomArchiveState.ARCHIVED) "Unarchive chat" else "Archive chat")
                    },
                    onClick = {
                        showMenu = false
                        roomActions.changeArchiveState(
                            if (room.archiveState == PrivateRoomArchiveState.ARCHIVED) {
                                PrivateRoomArchiveState.ACTIVE
                            } else {
                                PrivateRoomArchiveState.ARCHIVED
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                    enabled = mutationEnabled,
                )
            }
        }
    }

    if (showRetentionDialog) {
        AlertDialog(
            onDismissRequest = { showRetentionDialog = false },
            title = { Text("Disappearing messages") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.medium)) {
                    Text(
                        text = "Choose how long confirmed messages stay in this conversation.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivateRetentionSelector(
                        selectedRetention = room.retention,
                        enabled = mutationEnabled,
                        onChangeRetention = { selectedRetention ->
                            showRetentionDialog = false
                            roomActions.changeRetention(selectedRetention)
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRetentionDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun PrivateConversationAvatar(room: PrivateRoomSummary) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (room.kind == PrivateRoomKind.GROUP) {
                Icon(Icons.Default.Group, contentDescription = null)
            } else {
                Text(
                    text = privateAvatarInitial(room.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun PrivateRetentionSelector(
    selectedRetention: PrivateMessageRetention,
    enabled: Boolean,
    onChangeRetention: (PrivateMessageRetention) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Delete after", style = MaterialTheme.typography.labelMedium)
        PrivateMessageRetention.entries.forEach { retention ->
            FilterChip(
                selected = retention == selectedRetention,
                onClick = { onChangeRetention(retention) },
                enabled = enabled,
                label = { Text(retention.label) },
            )
        }
    }
}

internal fun privateRoomMembershipLabel(room: PrivateRoomSummary): String =
    when (room.kind) {
        PrivateRoomKind.DIRECT ->
            if (room.participantCount == 1) "Waiting for one person" else "Direct chat"

        PrivateRoomKind.GROUP -> "${room.participantCount} people"
    }

private fun privateConversationSubtitle(room: PrivateRoomSummary): String =
    when (room.metadataState) {
        PrivateRoomMetadataState.AVAILABLE -> privateRoomMembershipLabel(room)
        PrivateRoomMetadataState.PENDING -> "Loading encrypted details…"
        PrivateRoomMetadataState.UNAVAILABLE_ON_DEVICE -> "Encrypted details unavailable on this device"
    }
