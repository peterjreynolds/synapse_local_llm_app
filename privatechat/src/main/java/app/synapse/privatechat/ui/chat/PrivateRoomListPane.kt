package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.ui.theme.SynapsePrivateDesignSystem

@Composable
internal fun PrivateRoomListPane(
    roomFeedState: PrivateRoomFeedUiState,
    socialState: PrivateSocialUiState,
    selectedRoomId: PrivateRoomId?,
    navigationActions: PrivateChatNavigationActions,
    socialActions: PrivateSocialUiActions,
    modifier: Modifier = Modifier,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    Column(modifier = modifier.padding(horizontal = tokens.spacing.large)) {
        Spacer(Modifier.height(tokens.spacing.large))
        Text(
            text = "SYNAPSE PRIVATE",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = "Conversations", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Direct and group messages from people in your private circle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(tokens.spacing.medium))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.small),
        ) {
            Button(
                onClick = navigationActions.showCreateConversation,
                modifier = Modifier.weight(1f),
            ) {
                Text("New chat")
            }
            OutlinedButton(
                onClick = navigationActions.showProfile,
                modifier = Modifier.weight(1f),
            ) {
                Text("Profile")
            }
        }
        Spacer(Modifier.height(tokens.spacing.medium))
        PrivatePresenceSummary(socialState = socialState)
        Spacer(Modifier.height(tokens.spacing.medium))

        when (roomFeedState) {
            is PrivateRoomFeedUiState.Available ->
                AvailableRoomFeed(
                    roomFeedState = roomFeedState,
                    selectedRoomId = selectedRoomId,
                    navigationActions = navigationActions,
                    socialActions = socialActions,
                    modifier = Modifier.weight(1f),
                )

            PrivateRoomFeedUiState.Loading ->
                PrivateRoomFeedStatus(
                    title = "Loading conversations",
                    detail = "Waiting for the conversation connection.",
                    showProgress = true,
                    modifier = Modifier.weight(1f),
                )

            PrivateRoomFeedUiState.TransportUnavailable ->
                PrivateRoomFeedStatus(
                    title = "Connection unavailable",
                    detail = "Conversation transport is not configured in this build.",
                    modifier = Modifier.weight(1f),
                )

            PrivateRoomFeedUiState.UnexpectedFailure ->
                PrivateRoomFeedStatus(
                    title = "Conversations could not be loaded",
                    detail = "No room or message data was accepted. Try again after the connection recovers.",
                    modifier = Modifier.weight(1f),
                )

            PrivateRoomFeedUiState.NotRequested ->
                PrivateRoomFeedStatus(
                    title = "Account required",
                    detail = "Sign in before loading conversations.",
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

@Composable
private fun AvailableRoomFeed(
    roomFeedState: PrivateRoomFeedUiState.Available,
    selectedRoomId: PrivateRoomId?,
    navigationActions: PrivateChatNavigationActions,
    socialActions: PrivateSocialUiActions,
    modifier: Modifier,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    val snapshot = roomFeedState.snapshot
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val visibleRooms =
        snapshot.rooms
            .filter { room -> showArchived || room.archiveState == PrivateRoomArchiveState.ACTIVE }
            .sortedWith(
                compareByDescending<PrivateRoomSummary> { room -> room.pinState == PrivateRoomPinState.PINNED }
                    .thenBy { room -> room.title.lowercase() },
            )

    Column(modifier = modifier) {
        PrivateActivitySharingCard(
            preferences = snapshot.activitySharingPreferences,
            socialActions = socialActions,
        )
        Spacer(Modifier.height(tokens.spacing.medium))
        FilterChip(
            selected = showArchived,
            onClick = { showArchived = !showArchived },
            label = { Text(if (showArchived) "Showing archived" else "Show archived") },
        )
        Spacer(Modifier.height(tokens.spacing.small))
        if (visibleRooms.isEmpty()) {
            PrivateRoomFeedStatus(
                title = if (snapshot.rooms.isEmpty()) "No conversations yet" else "No active conversations",
                detail =
                    if (snapshot.rooms.isEmpty()) {
                        "Create a direct conversation or group, then share its one-use invitation."
                    } else {
                        "Show archived conversations to find older rooms."
                    },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = tokens.spacing.large),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.small),
            ) {
                items(
                    items = visibleRooms,
                    key = { room -> room.roomId.canonical },
                ) { room ->
                    PrivateRoomRow(
                        room = room,
                        selected = room.roomId == selectedRoomId,
                        onClick = { navigationActions.selectRoom(room.roomId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivateRoomRow(
    room: PrivateRoomSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(tokens.radii.control),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.compact),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.small),
            ) {
                Text(
                    text = room.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (room.unreadMessageCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(
                            text = room.unreadMessageCount.toString(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Text(
                text = privateRoomMembershipLabel(room),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            room.latestMessagePreview?.let { preview ->
                Text(
                    text = "${preview.senderDisplayName}: ${preview.body.plaintext}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = privateRoomPreferenceLabel(room),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun privateRoomPreferenceLabel(room: PrivateRoomSummary): String =
    listOfNotNull(
        "Pinned".takeIf { room.pinState == PrivateRoomPinState.PINNED },
        "Archived".takeIf { room.archiveState == PrivateRoomArchiveState.ARCHIVED },
        "Muted".takeIf { room.muteState == PrivateRoomMuteState.MUTED },
        "Keeps ${room.retention.label}",
    ).joinToString(" · ")

@Composable
private fun PrivateRoomFeedStatus(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    showProgress: Boolean = false,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showProgress) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
