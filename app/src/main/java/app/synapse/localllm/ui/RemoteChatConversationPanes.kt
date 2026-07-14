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
import androidx.compose.material3.OutlinedButton
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
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomKind
import coil3.compose.AsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun RemoteChatsPane(
    state: RemoteChatUiState,
    viewModel: RemoteChatViewModel,
    accountState: RemoteAccountUiState,
    groupState: RemoteGroupUiState,
    groupViewModel: RemoteGroupViewModel,
) {
    var showGroupCreation by rememberSaveable { mutableStateOf(false) }
    val selectedRoomId = state.selectedRoomId
    LaunchedEffect(groupState.roomToOpen, groupState.roomToClose) {
        groupState.roomToOpen?.let(viewModel::selectRoom)
        if (groupState.roomToClose == selectedRoomId) viewModel.selectRoom(null)
        if (groupState.roomToOpen != null || groupState.roomToClose != null) {
            showGroupCreation = false
            groupViewModel.consumeNavigation()
        }
    }
    if (selectedRoomId == null) {
        RemoteRoomList(
            rooms = state.rooms,
            profiles = state.profiles,
            onRoomSelected = { room -> viewModel.selectRoom(room.roomId) },
            onCreateGroup = { showGroupCreation = true },
            isActionRunning = groupState.isActionRunning,
        )
    } else {
        val room = state.rooms.firstOrNull { candidate -> candidate.roomId == selectedRoomId }
        RemoteMessageThread(
            state = state,
            room = room,
            onBack = { viewModel.selectRoom(null) },
            onSend = viewModel::sendMessage,
            accountState = accountState,
            groupState = groupState,
            groupViewModel = groupViewModel,
        )
    }
    if (showGroupCreation) {
        RemoteGroupCreateDialog(
            profiles = state.profiles,
            currentAccountUid = state.account?.accountUid?.raw,
            blockedProfileUids = accountState.blockedProfileUids,
            isActionRunning = groupState.isActionRunning,
            onDismiss = { showGroupCreation = false },
            onCreate = groupViewModel::createGroup,
        )
    }
}

@Composable
private fun RemoteRoomList(
    rooms: List<RemoteCachedRoom>,
    profiles: List<RemoteCachedProfile>,
    onRoomSelected: (RemoteCachedRoom) -> Unit,
    onCreateGroup: () -> Unit,
    isActionRunning: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = onCreateGroup,
            enabled = !isActionRunning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text("New group")
        }
        if (rooms.isEmpty()) {
            EmptyRemotePane(
                title = "No synced conversations yet",
                detail = "Start a private chat from People or create a group.",
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(orderRemoteRoomsForList(rooms), key = { room -> room.roomId.raw }) { room ->
            val peer = profiles.firstOrNull { profile -> profile.profileUid == room.peerUid }
            val displayName = if (room.kind == RemoteRoomKind.GROUP) room.title else peer?.displayName ?: room.title
            ListItem(
                headlineContent = {
                    Text(displayName, fontWeight = FontWeight.SemiBold)
                },
                supportingContent = {
                    Text(
                        room.latestMessagePreview ?: if (room.kind == RemoteRoomKind.GROUP) {
                            "Group conversation"
                        } else {
                            "Private synced conversation"
                        },
                    )
                },
                leadingContent = {
                    RemoteProfileAvatar(
                        profile = if (room.kind == RemoteRoomKind.DIRECT) peer else null,
                        displayName = displayName,
                    )
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        if (room.isPinned) Text("Pinned", style = MaterialTheme.typography.labelSmall)
                        if (room.isArchived) Text("Archived", style = MaterialTheme.typography.labelSmall)
                        if (room.unreadCount > 0) Badge { Text(room.unreadCount.toString()) }
                    }
                },
                modifier = Modifier.clickable { onRoomSelected(room) },
            )
            HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RemoteMessageThread(
    state: RemoteChatUiState,
    room: RemoteCachedRoom?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    accountState: RemoteAccountUiState,
    groupState: RemoteGroupUiState,
    groupViewModel: RemoteGroupViewModel,
) {
    var composerText by rememberSaveable(state.selectedRoomId?.raw) { mutableStateOf("") }
    var showRoomMembers by rememberSaveable(state.selectedRoomId?.raw) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val peer = state.profiles.firstOrNull { profile -> profile.profileUid == room?.peerUid }
    val currentProfile = state.profiles.firstOrNull { profile ->
        profile.profileUid.raw == state.account?.accountUid?.raw
    }
    val title = if (room?.kind == RemoteRoomKind.GROUP) room.title else peer?.displayName ?: room?.title ?: "Private conversation"

    LaunchedEffect(showRoomMembers, room?.roomId) {
        if (showRoomMembers && room?.kind == RemoteRoomKind.GROUP) {
            groupViewModel.loadGroupDetails(room.roomId)
        }
    }

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
                    text = if (room?.kind == RemoteRoomKind.GROUP) "Group conversation" else remotePresenceLabel(peer),
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
            if (room?.kind == RemoteRoomKind.GROUP) {
                RemoteGroupDetailsPane(
                    details = groupState.details?.takeIf { details -> details.roomId == room.roomId },
                    profiles = state.profiles,
                    blockedProfileUids = accountState.blockedProfileUids,
                    isLoading = groupState.isLoading,
                    isActionRunning = groupState.isActionRunning,
                    viewModel = groupViewModel,
                    modifier = Modifier.weight(1f),
                )
            } else {
                RemoteRoomMembers(
                    profiles = listOfNotNull(currentProfile, peer).distinctBy { profile -> profile.profileUid },
                    currentAccountUid = state.account?.accountUid?.raw,
                    modifier = Modifier.weight(1f),
                )
            }
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
                        senderDisplayName = if (room?.kind == RemoteRoomKind.GROUP) {
                            state.profiles.firstOrNull { profile -> profile.profileUid == message.senderUid }
                                ?.displayName
                                ?: "Group member"
                        } else {
                            null
                        },
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
    senderDisplayName: String?,
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
                if (!isCurrentAccount && senderDisplayName != null) {
                    Text(
                        senderDisplayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                }
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
    accountState: RemoteAccountUiState,
    onOpenDirectRoom: (RemoteProfileUid) -> Unit,
    onSetUserBlocked: (RemoteProfileUid, Boolean) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedProfileUid by rememberSaveable { mutableStateOf<String?>(null) }
    val currentUid = state.account?.accountUid?.raw
    LaunchedEffect(currentUid) {
        selectedProfileUid = null
        searchQuery = ""
    }
    val presentation = remember(state.profiles, state.rooms, searchQuery, currentUid) {
        buildRemotePeoplePresentation(state.profiles, state.rooms, currentUid, searchQuery)
    }
    val selectedProfile = state.profiles.firstOrNull { profile ->
        profile.profileUid.raw == selectedProfileUid && profile.profileUid.raw != currentUid
    }
    if (selectedProfile != null) {
        val isBlocked = selectedProfile.profileUid in accountState.blockedProfileUids
        val accountControlsAvailable = accountState.accountUid?.raw == currentUid &&
            accountState.privacyStateVerified &&
            !accountState.isRefreshing
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconButton(onClick = { selectedProfileUid = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to people")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RemoteProfileAvatar(selectedProfile, selectedProfile.displayName)
                Column {
                    Text(selectedProfile.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text("@${selectedProfile.username}")
                    Text(
                        remotePresenceLabel(selectedProfile),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selectedProfile.bio.isNotBlank()) Text(selectedProfile.bio)
            if (isBlocked) {
                Text(
                    "You blocked this account. New conversations are unavailable until you unblock it.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = { onOpenDirectRoom(selectedProfile.profileUid) },
                modifier = Modifier.fillMaxWidth(),
                enabled = accountControlsAvailable &&
                    !isBlocked &&
                    !state.isActionRunning &&
                    !accountState.isActionRunning,
            ) {
                Text("Start chat")
            }
            OutlinedButton(
                onClick = { onSetUserBlocked(selectedProfile.profileUid, !isBlocked) },
                modifier = Modifier.fillMaxWidth(),
                enabled = accountControlsAvailable &&
                    !state.isActionRunning &&
                    !accountState.isActionRunning,
            ) {
                Text(if (isBlocked) "Unblock account" else "Block account")
            }
        }
        return
    }
    val visibleProfiles = presentation.recentContacts + presentation.directory
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
                if (presentation.recentContacts.isNotEmpty()) {
                    item {
                        Text(
                            "Recent contacts",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                items(
                    items = presentation.recentContacts,
                    key = { profile -> "recent-${profile.profileUid.raw}" },
                ) { profile ->
                    RemotePeopleListItem(
                        profile = profile,
                        isBlocked = profile.profileUid in accountState.blockedProfileUids,
                        onSelected = { selectedProfileUid = profile.profileUid.raw },
                    )
                }
                if (presentation.directory.isNotEmpty()) {
                    item {
                        Text(
                            if (presentation.recentContacts.isEmpty()) "People" else "Directory",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                items(
                    items = presentation.directory,
                    key = { profile -> "directory-${profile.profileUid.raw}" },
                ) { profile ->
                    RemotePeopleListItem(
                        profile = profile,
                        isBlocked = profile.profileUid in accountState.blockedProfileUids,
                        onSelected = { selectedProfileUid = profile.profileUid.raw },
                    )
                }
            }
        }
    }
}

internal data class RemotePeoplePresentation(
    val recentContacts: List<RemoteCachedProfile>,
    val directory: List<RemoteCachedProfile>,
)

internal fun buildRemotePeoplePresentation(
    profiles: List<RemoteCachedProfile>,
    rooms: List<RemoteCachedRoom>,
    currentAccountUid: String?,
    searchQuery: String,
): RemotePeoplePresentation {
    val normalizedQuery = searchQuery.trim().lowercase()
    val candidates = profiles.filter { profile ->
        profile.profileUid.raw != currentAccountUid &&
            (
                normalizedQuery.isEmpty() ||
                    profile.displayName.lowercase().contains(normalizedQuery) ||
                    profile.username.lowercase().contains(normalizedQuery)
            )
    }
    if (normalizedQuery.isNotEmpty()) {
        return RemotePeoplePresentation(
            recentContacts = emptyList(),
            directory = candidates.sortedBy { profile -> profile.displayName.lowercase() },
        )
    }
    val profilesByUid = candidates.associateBy(RemoteCachedProfile::profileUid)
    val recentContacts = rooms
        .sortedByDescending(RemoteCachedRoom::remoteUpdatedAt)
        .mapNotNull { room -> profilesByUid[room.peerUid] }
        .distinctBy(RemoteCachedProfile::profileUid)
    val recentUids = recentContacts.mapTo(mutableSetOf(), RemoteCachedProfile::profileUid)
    return RemotePeoplePresentation(
        recentContacts = recentContacts,
        directory = candidates
            .filterNot { profile -> profile.profileUid in recentUids }
            .sortedBy { profile -> profile.displayName.lowercase() },
    )
}

internal fun orderRemoteRoomsForList(rooms: List<RemoteCachedRoom>): List<RemoteCachedRoom> =
    rooms.sortedWith(
        compareByDescending<RemoteCachedRoom> { room -> room.isPinned }
            .thenBy { room -> room.isArchived }
            .thenByDescending { room -> room.remoteUpdatedAt },
    )

@Composable
private fun RemotePeopleListItem(
    profile: RemoteCachedProfile,
    isBlocked: Boolean,
    onSelected: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(profile.displayName, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column {
                Text("@${profile.username}")
                if (profile.bio.isNotBlank()) Text(profile.bio)
                if (isBlocked) Text("Blocked", color = MaterialTheme.colorScheme.error)
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
        modifier = Modifier.clickable(onClick = onSelected),
    )
    HorizontalDivider()
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
