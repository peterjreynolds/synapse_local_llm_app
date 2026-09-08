package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.chat.PrivateActivityFeedAvailability
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.ui.theme.SynapsePrivateDesignSystem
import java.text.Normalizer
import java.util.Locale

@Composable
internal fun PrivateRoomListPane(
    roomFeedState: PrivateRoomFeedUiState,
    socialState: PrivateSocialUiState,
    selectedRoomId: PrivateRoomId?,
    navigationActions: PrivateChatNavigationActions,
    modifier: Modifier = Modifier,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    var searchQuery by remember { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var archivedOnly by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = tokens.spacing.large),
        ) {
            Spacer(Modifier.height(tokens.spacing.small))
            PrivateChatsHeader(
                socialState = socialState,
                onOpenProfile = navigationActions.showProfile,
            )
            Spacer(Modifier.height(tokens.spacing.small))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { changedQuery ->
                    searchQuery = changedQuery.take(PRIVATE_ROOM_SEARCH_LIMIT)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search conversations") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon =
                    if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    } else {
                        null
                    },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = tokens.spacing.compact),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.small),
            ) {
                FilterChip(
                    selected = unreadOnly,
                    onClick = { unreadOnly = !unreadOnly },
                    label = { Text("Unread") },
                )
                FilterChip(
                    selected = archivedOnly,
                    onClick = { archivedOnly = !archivedOnly },
                    label = { Text("Archived") },
                )
            }
            PrivateAvailablePresenceSummary(socialState)

            when (roomFeedState) {
                is PrivateRoomFeedUiState.Available -> {
                    if (roomFeedState.connectionState == PrivateChatConnectionUiState.RECONNECTING) {
                        PrivateChatReconnectingBanner(
                            modifier = Modifier.padding(vertical = tokens.spacing.small),
                        )
                    }
                    AvailableRoomFeed(
                        roomFeedState = roomFeedState,
                        selectedRoomId = selectedRoomId,
                        searchQuery = searchQuery,
                        unreadOnly = unreadOnly,
                        archivedOnly = archivedOnly,
                        navigationActions = navigationActions,
                        modifier = Modifier.weight(1f),
                    )
                }

                PrivateRoomFeedUiState.Loading ->
                    PrivateRoomFeedStatus(
                        title = "Loading conversations",
                        detail = "Connecting to your private chats.",
                        showProgress = true,
                        modifier = Modifier.weight(1f),
                    )

                PrivateRoomFeedUiState.TransportUnavailable ->
                    PrivateRoomFeedStatus(
                        title = "Connection unavailable",
                        detail = "Synapse could not load any confirmed chats yet. It will reconnect automatically.",
                        modifier = Modifier.weight(1f),
                    )

                PrivateRoomFeedUiState.UnexpectedFailure ->
                    PrivateRoomFeedStatus(
                        title = "Chats could not be loaded",
                        detail = "The returned chat list could not be verified. Nothing was changed.",
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
        FloatingActionButton(
            onClick = navigationActions.showCreateConversation,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(tokens.spacing.spacious)
                    .semantics { contentDescription = "Start or join a conversation" },
        ) {
            Icon(Icons.Default.Edit, contentDescription = null)
        }
    }
}

@Composable
private fun PrivateChatsHeader(
    socialState: PrivateSocialUiState,
    onOpenProfile: () -> Unit,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    val socialSnapshot = (socialState as? PrivateSocialUiState.Available)?.snapshot
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.medium),
    ) {
        Surface(
            onClick = onOpenProfile,
            modifier =
                Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Open profile and privacy" },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = privateAvatarInitial(socialSnapshot?.profile?.displayName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Chats",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = socialSnapshot?.profile?.username?.let { username -> "@$username" } ?: "Synapse Private",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrivateAvailablePresenceSummary(socialState: PrivateSocialUiState) {
    val snapshot = (socialState as? PrivateSocialUiState.Available)?.snapshot ?: return
    if (
        snapshot.presenceAvailability != PrivateActivityFeedAvailability.AVAILABLE ||
        snapshot.visiblePresence.isEmpty()
    ) {
        return
    }
    Text(
        text = privateVisiblePresenceLabel(snapshot.visiblePresence),
        modifier = Modifier.padding(bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AvailableRoomFeed(
    roomFeedState: PrivateRoomFeedUiState.Available,
    selectedRoomId: PrivateRoomId?,
    searchQuery: String,
    unreadOnly: Boolean,
    archivedOnly: Boolean,
    navigationActions: PrivateChatNavigationActions,
    modifier: Modifier,
) {
    val snapshot = roomFeedState.snapshot
    val visibleRooms =
        filterPrivateRooms(
            rooms = snapshot.rooms,
            searchQuery = searchQuery,
            unreadOnly = unreadOnly,
            archivedOnly = archivedOnly,
        )

    if (visibleRooms.isEmpty()) {
        PrivateRoomFeedStatus(
            title = if (snapshot.rooms.isEmpty()) "No conversations yet" else "No conversations match",
            detail =
                if (snapshot.rooms.isEmpty()) {
                    "Tap the pencil button to start a direct chat, create a group, or join with a code."
                } else {
                    "Clear the search or change the filters."
                },
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        itemsIndexed(
            items = visibleRooms,
            key = { _, room -> room.roomId.canonical },
        ) { index, room ->
            PrivateRoomRow(
                room = room,
                selected = room.roomId == selectedRoomId,
                onClick = { navigationActions.selectRoom(room.roomId) },
            )
            if (index < visibleRooms.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 68.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
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
    ListItem(
        headlineContent = {
            Text(
                text = room.title,
                fontWeight = if (room.unreadMessageCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text =
                    room.latestMessagePreview?.let { preview ->
                        "${preview.senderDisplayName}: ${preview.body.plaintext}"
                    } ?: privateEmptyRoomPreview(room),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { PrivateRoomAvatar(room) },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (room.unreadMessageCount > 0) {
                    Badge { Text(room.unreadMessageCount.toString()) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (room.pinState == PrivateRoomPinState.PINNED) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    if (room.muteState == PrivateRoomMuteState.MUTED) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Muted",
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    if (room.archiveState == PrivateRoomArchiveState.ARCHIVED) {
                        Icon(
                            Icons.Default.Archive,
                            contentDescription = "Archived",
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        },
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .semantics {
                    stateDescription = privateRoomMembershipLabel(room)
                    this.selected = selected
                },
        colors =
            ListItemDefaults.colors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                    },
            ),
    )
}

@Composable
private fun PrivateRoomAvatar(room: PrivateRoomSummary) {
    Surface(
        modifier = Modifier.size(52.dp),
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

internal fun filterPrivateRooms(
    rooms: List<PrivateRoomSummary>,
    searchQuery: String,
    unreadOnly: Boolean,
    archivedOnly: Boolean,
): List<PrivateRoomSummary> {
    val normalizedQuery = normalizePrivateRoomSearchQuery(searchQuery)
    val visibleArchiveState =
        if (archivedOnly) PrivateRoomArchiveState.ARCHIVED else PrivateRoomArchiveState.ACTIVE
    return rooms
        .asSequence()
        .filter { room ->
            room.archiveState == visibleArchiveState &&
                (!unreadOnly || room.unreadMessageCount > 0) &&
                room.matchesPrivateRoomSearch(normalizedQuery)
        }.sortedWith(
            compareByDescending<PrivateRoomSummary> { room -> room.pinState == PrivateRoomPinState.PINNED }
                .thenBy { room -> room.archiveState == PrivateRoomArchiveState.ARCHIVED }
                .thenBy { room -> normalizePrivateRoomSearchQuery(room.title) },
        ).toList()
}

private fun PrivateRoomSummary.matchesPrivateRoomSearch(normalizedQuery: String): Boolean {
    if (normalizedQuery.isEmpty()) return true
    return sequenceOf(
        title,
        latestMessagePreview?.senderDisplayName,
        latestMessagePreview?.body?.plaintext,
    ).filterNotNull().any { candidate ->
        normalizePrivateRoomSearchQuery(candidate).contains(normalizedQuery)
    }
}

private fun normalizePrivateRoomSearchQuery(query: String): String =
    Normalizer
        .normalize(query, Normalizer.Form.NFKC)
        .trim()
        .lowercase(Locale.ROOT)

private fun privateEmptyRoomPreview(room: PrivateRoomSummary): String =
    when (room.kind) {
        PrivateRoomKind.DIRECT ->
            if (room.participantCount == 1) "Waiting for your friend to join" else "Private conversation"

        PrivateRoomKind.GROUP -> "${room.participantCount} people"
    }

internal fun privateAvatarInitial(displayName: String?): String {
    val normalizedName = displayName?.trim().orEmpty()
    if (normalizedName.isEmpty()) return "S"
    return String(Character.toChars(normalizedName.codePointAt(0))).uppercase(Locale.ROOT)
}

@Composable
private fun PrivateRoomFeedStatus(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    showProgress: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

private const val PRIVATE_ROOM_SEARCH_LIMIT = 96
