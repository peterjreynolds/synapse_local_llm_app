package app.synapse.localllm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.remote.RemoteCachedDirectRoom
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteProfileUid
import coil3.compose.AsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun RemoteChatsPane(
    state: RemoteChatUiState,
    viewModel: RemoteChatViewModel,
) {
    val selectedRoomId = state.selectedRoomId
    if (selectedRoomId == null) {
        RemoteRoomList(
            rooms = state.rooms,
            profiles = state.profiles,
            onRoomSelected = { room -> viewModel.selectRoom(room.roomId) },
        )
    } else {
        val room = state.rooms.firstOrNull { candidate -> candidate.roomId == selectedRoomId }
        RemoteMessageThread(
            state = state,
            room = room,
            onBack = { viewModel.selectRoom(null) },
            onSend = viewModel::sendMessage,
        )
    }
}

@Composable
private fun RemoteRoomList(
    rooms: List<RemoteCachedDirectRoom>,
    profiles: List<RemoteCachedProfile>,
    onRoomSelected: (RemoteCachedDirectRoom) -> Unit,
) {
    if (rooms.isEmpty()) {
        EmptyRemotePane(
            title = "No synced conversations yet",
            detail = "Open People and choose an approved account to start a private chat.",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rooms, key = { room -> room.roomId.raw }) { room ->
            val peer = profiles.firstOrNull { profile -> profile.profileUid == room.peerUid }
            ListItem(
                headlineContent = {
                    Text(peer?.displayName ?: room.title, fontWeight = FontWeight.SemiBold)
                },
                supportingContent = {
                    Text(room.latestMessagePreview ?: "Private synced conversation")
                },
                leadingContent = {
                    RemoteProfileAvatar(
                        profile = peer,
                        displayName = peer?.displayName ?: room.title,
                    )
                },
                trailingContent = {
                    if (room.unreadCount > 0) Badge { Text(room.unreadCount.toString()) }
                },
                modifier = Modifier.clickable { onRoomSelected(room) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun RemoteMessageThread(
    state: RemoteChatUiState,
    room: RemoteCachedDirectRoom?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    var composerText by rememberSaveable(state.selectedRoomId?.raw) { mutableStateOf("") }
    var showRoomMembers by rememberSaveable(state.selectedRoomId?.raw) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val peer = state.profiles.firstOrNull { profile -> profile.profileUid == room?.peerUid }
    val currentProfile = state.profiles.firstOrNull { profile ->
        profile.profileUid.raw == state.account?.accountUid?.raw
    }
    val title = peer?.displayName ?: room?.title ?: "Private conversation"

    fun submit() {
        if (composerText.isNotBlank() && !state.isActionRunning) {
            onSend(composerText)
            composerText = ""
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to conversations")
            }
            RemoteProfileAvatar(profile = peer, displayName = title)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = remotePresenceLabel(peer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showRoomMembers = !showRoomMembers }) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = if (showRoomMembers) "Show messages" else "Show room members",
                )
            }
        }
        HorizontalDivider()
        if (showRoomMembers) {
            RemoteRoomMembers(
                profiles = listOfNotNull(currentProfile, peer).distinctBy { profile -> profile.profileUid },
                currentAccountUid = state.account?.accountUid?.raw,
                modifier = Modifier.weight(1f),
            )
        } else if (state.messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "Messages will synchronize here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(state.messages, key = { message -> message.messageId.raw }) { message ->
                    RemoteMessageBubble(
                        message = message,
                        isCurrentAccount = message.senderUid.raw == state.account?.accountUid?.raw,
                    )
                }
            }
        }
        if (!showRoomMembers) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = composerText,
                    onValueChange = { value -> composerText = value.take(MAXIMUM_MESSAGE_LENGTH) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 5,
                    enabled = !state.isActionRunning,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                FilledIconButton(
                    onClick = ::submit,
                    enabled = composerText.isNotBlank() && !state.isActionRunning,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                }
            }
        }
    }
}

@Composable
private fun RemoteMessageBubble(
    message: RemoteCachedMessage,
    isCurrentAccount: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCurrentAccount) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isCurrentAccount) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(0.82f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(message.body)
                if (isCurrentAccount || message.deliveryState != RemoteMessageDeliveryState.SENT) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (message.deliveryState) {
                            RemoteMessageDeliveryState.PENDING -> "Sending…"
                            RemoteMessageDeliveryState.SENT -> "Sent"
                            RemoteMessageDeliveryState.FAILED -> message.failureReason ?: "Send failed"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.deliveryState == RemoteMessageDeliveryState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemotePeoplePane(
    state: RemoteChatUiState,
    onOpenDirectRoom: (RemoteProfileUid) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val currentUid = state.account?.accountUid?.raw
    val visibleProfiles = remember(state.profiles, searchQuery, currentUid) {
        val normalizedQuery = searchQuery.trim().lowercase()
        state.profiles.filter { profile ->
            profile.profileUid.raw != currentUid &&
                (
                    normalizedQuery.isEmpty() ||
                        profile.displayName.lowercase().contains(normalizedQuery) ||
                        profile.username.lowercase().contains(normalizedQuery)
                )
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { value -> searchQuery = value },
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            label = { Text("Search approved people") },
            singleLine = true,
        )
        if (visibleProfiles.isEmpty()) {
            EmptyRemotePane(
                title = "No approved people found",
                detail = "The private directory only shows accounts enabled by the app owner.",
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visibleProfiles, key = { profile -> profile.profileUid.raw }) { profile ->
                    ListItem(
                        headlineContent = { Text(profile.displayName, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Column {
                                Text("@${profile.username}")
                                if (profile.bio.isNotBlank()) Text(profile.bio)
                            }
                        },
                        leadingContent = {
                            RemoteProfileAvatar(profile = profile, displayName = profile.displayName)
                        },
                        trailingContent = {
                            Text(
                                text = remotePresenceLabel(profile),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (profile.isOnline) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        modifier = Modifier.clickable { onOpenDirectRoom(profile.profileUid) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RemoteRoomMembers(
    profiles: List<RemoteCachedProfile>,
    currentAccountUid: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = "Room members",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        profiles.forEach { profile ->
            ListItem(
                headlineContent = { Text(profile.displayName, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Column {
                        Text(
                            if (profile.profileUid.raw == currentAccountUid) {
                                "You · @${profile.username}"
                            } else {
                                "@${profile.username}"
                            },
                        )
                        Text(remotePresenceLabel(profile))
                        if (profile.bio.isNotBlank()) Text(profile.bio)
                    }
                },
                leadingContent = {
                    RemoteProfileAvatar(profile = profile, displayName = profile.displayName)
                },
            )
        }
    }
}

@Composable
internal fun RemoteProfileAvatar(
    profile: RemoteCachedProfile?,
    displayName: String,
) {
    Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        RemoteInitialsAvatar(displayName)
        profile?.avatarUrl?.takeIf(String::isNotBlank)?.let { avatarUrl ->
            AsyncImage(
                model = avatarUrl,
                contentDescription = "Profile photo for $displayName",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun RemoteInitialsAvatar(displayName: String) {
    val initials = displayName
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(2)
        .mapNotNull { word -> word.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifBlank { "?" }
    Surface(
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(initials, fontWeight = FontWeight.Bold)
        }
    }
}

internal fun remotePresenceLabel(profile: RemoteCachedProfile?): String =
    when {
        profile == null -> "Private synced chat"
        profile.isOnline -> "Online"
        profile.lastSeenAt != null -> "Last seen ${REMOTE_PRESENCE_FORMATTER.format(profile.lastSeenAt)}"
        else -> "Offline"
    }

@Composable
internal fun EmptyRemotePane(
    title: String,
    detail: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MAXIMUM_MESSAGE_LENGTH = 4_000
private val REMOTE_PRESENCE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
