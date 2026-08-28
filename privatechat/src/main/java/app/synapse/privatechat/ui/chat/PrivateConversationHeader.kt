package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    actionRunning: Boolean,
    invitationCreating: Boolean,
    navigationActions: PrivateChatNavigationActions,
    roomActions: PrivateRoomUiActions,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    Column(
        modifier = Modifier.fillMaxWidth().padding(tokens.spacing.large),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.small),
        ) {
            if (showBackButton) {
                TextButton(onClick = navigationActions.showRoomList) {
                    Text("Rooms")
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = room.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        when (room.metadataState) {
                            PrivateRoomMetadataState.AVAILABLE -> privateRoomMembershipLabel(room)
                            PrivateRoomMetadataState.PENDING ->
                                "Encrypted title pending · ${privateRoomMembershipLabel(room)}"

                            PrivateRoomMetadataState.UNAVAILABLE_ON_DEVICE ->
                                "Encrypted title unavailable on this device · ${privateRoomMembershipLabel(room)}"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (room.kind == PrivateRoomKind.GROUP) {
                OutlinedButton(
                    onClick = navigationActions.showGroupManagement,
                    enabled = !actionRunning,
                ) {
                    Text("Members")
                }
            }
            if (room.kind == PrivateRoomKind.GROUP || room.participantCount == 1) {
                OutlinedButton(
                    onClick = roomActions.createOneUseInvitation,
                    enabled = !actionRunning && !invitationCreating,
                ) {
                    if (invitationCreating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Invite")
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.small),
        ) {
            FilterChip(
                selected = room.pinState == PrivateRoomPinState.PINNED,
                onClick = {
                    roomActions.changePinState(
                        if (room.pinState == PrivateRoomPinState.PINNED) {
                            PrivateRoomPinState.UNPINNED
                        } else {
                            PrivateRoomPinState.PINNED
                        },
                    )
                },
                enabled = !actionRunning,
                label = { Text("Pinned") },
            )
            FilterChip(
                selected = room.muteState == PrivateRoomMuteState.MUTED,
                onClick = {
                    roomActions.changeMuteState(
                        if (room.muteState == PrivateRoomMuteState.MUTED) {
                            PrivateRoomMuteState.AUDIBLE
                        } else {
                            PrivateRoomMuteState.MUTED
                        },
                    )
                },
                enabled = !actionRunning,
                label = { Text("Muted") },
            )
            FilterChip(
                selected = room.archiveState == PrivateRoomArchiveState.ARCHIVED,
                onClick = {
                    roomActions.changeArchiveState(
                        if (room.archiveState == PrivateRoomArchiveState.ARCHIVED) {
                            PrivateRoomArchiveState.ACTIVE
                        } else {
                            PrivateRoomArchiveState.ARCHIVED
                        },
                    )
                },
                enabled = !actionRunning,
                label = { Text("Archived") },
            )
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
        Text("Keep messages", style = MaterialTheme.typography.labelMedium)
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
            if (room.participantCount == 1) "Direct conversation · waiting for invite" else "Direct conversation"

        PrivateRoomKind.GROUP -> "Group · ${room.participantCount} people"
    }
