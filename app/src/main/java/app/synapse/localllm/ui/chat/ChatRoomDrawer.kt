package app.synapse.localllm.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.chat.ChatRoomRecord
import app.synapse.localllm.domain.chat.CreateRoomCommand
import app.synapse.localllm.domain.chat.RoomId
import app.synapse.localllm.domain.chat.RoomKind

@Composable
internal fun ChatRoomDrawerOverlay(
    rooms: List<ChatRoomRecord>,
    currentRoomId: RoomId?,
    onClose: () -> Unit,
    onCreateRoom: (CreateRoomCommand) -> Unit,
    onRoomSelected: (ChatRoomRecord) -> Unit,
    onRoomPinnedChanged: (ChatRoomRecord, Boolean) -> Unit,
    onRoomRenamed: (ChatRoomRecord, String) -> Unit,
    onRoomArchived: (ChatRoomRecord) -> Unit,
    onRoomDeleted: (ChatRoomRecord) -> Unit,
) {
    var actionRoom by remember { mutableStateOf<ChatRoomRecord?>(null) }
    var renameRoom by remember { mutableStateOf<ChatRoomRecord?>(null) }
    var deleteRoom by remember { mutableStateOf<ChatRoomRecord?>(null) }
    var isCreateRoomOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .clickable(onClick = onClose),
        )
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.9f),
            color = Color(0xFF070907),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Rooms",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close rooms",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                Button(
                    onClick = { isCreateRoomOpen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create room")
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(rooms, key = { room -> room.id.raw }) { room ->
                        ChatRoomDrawerRow(
                            room = room,
                            selected = room.id == currentRoomId,
                            onRoomSelected = onRoomSelected,
                            onRoomActionsRequested = { selectedRoom -> actionRoom = selectedRoom },
                        )
                    }
                }
            }
        }
    }

    if (isCreateRoomOpen) {
        CreateChatRoomDialog(
            onDismiss = { isCreateRoomOpen = false },
            onCreate = { command ->
                isCreateRoomOpen = false
                onCreateRoom(command)
            },
        )
    }

    actionRoom?.let { room ->
        ChatRoomActionsDialog(
            room = room,
            onDismiss = { actionRoom = null },
            onPinnedChanged = { pinned ->
                actionRoom = null
                onRoomPinnedChanged(room, pinned)
            },
            onRenameRequested = {
                actionRoom = null
                renameRoom = room
            },
            onArchiveRequested = {
                actionRoom = null
                onRoomArchived(room)
            },
            onDeleteRequested = {
                actionRoom = null
                deleteRoom = room
            },
        )
    }

    renameRoom?.let { room ->
        RenameChatRoomDialog(
            room = room,
            onDismiss = { renameRoom = null },
            onRenamed = { title ->
                renameRoom = null
                onRoomRenamed(room, title)
            },
        )
    }

    deleteRoom?.let { room ->
        DeleteChatRoomDialog(
            room = room,
            onDismiss = { deleteRoom = null },
            onDeleted = {
                deleteRoom = null
                onRoomDeleted(room)
            },
        )
    }
}

@Composable
private fun ChatRoomDrawerRow(
    room: ChatRoomRecord,
    selected: Boolean,
    onRoomSelected: (ChatRoomRecord) -> Unit,
    onRoomActionsRequested: (ChatRoomRecord) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(room.id.raw) {
                detectTapGestures(
                    onTap = { onRoomSelected(room) },
                    onLongPress = { onRoomActionsRequested(room) },
                )
            },
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (room.isPinned) {
                Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = "Pinned room",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = room.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${room.kind.toDisplayLabel()} · ${room.memberSummary.ifBlank { "No active members" }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CreateChatRoomDialog(
    onDismiss: () -> Unit,
    onCreate: (CreateRoomCommand) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var roomKind by remember { mutableStateOf(RoomKind.AI_CHAT) }
    var humanNameDraft by remember { mutableStateOf("") }
    var humanNames by remember { mutableStateOf(emptyList<String>()) }
    var includeSynapseAi by remember { mutableStateOf(true) }
    var synapseAiAutoResponse by remember { mutableStateOf(true) }
    val normalizedTitle = title.trim()
    val pendingHumanName = humanNameDraft.trim()
    val requestedHumanNames = (humanNames + pendingHumanName)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
    val hasValidHumanMemberCount = when (roomKind) {
        RoomKind.AI_CHAT -> requestedHumanNames.isEmpty()
        RoomKind.DIRECT -> requestedHumanNames.size == 1
        RoomKind.GROUP -> requestedHumanNames.isNotEmpty()
    }

    fun addPendingHumanName() {
        val normalizedName = humanNameDraft.trim()
        if (normalizedName.isBlank()) return
        if (humanNames.none { name -> name.equals(normalizedName, ignoreCase = true) }) {
            humanNames = humanNames + normalizedName
        }
        humanNameDraft = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create room") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { value -> title = value },
                    label = { Text("Room title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Room kind", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RoomKind.entries.forEach { kind ->
                        FilterChip(
                            selected = roomKind == kind,
                            onClick = {
                                roomKind = kind
                                if (kind == RoomKind.AI_CHAT) {
                                    humanNameDraft = ""
                                    humanNames = emptyList()
                                    includeSynapseAi = true
                                    synapseAiAutoResponse = true
                                } else {
                                    synapseAiAutoResponse = false
                                }
                            },
                            label = { Text(kind.toDisplayLabel()) },
                        )
                    }
                }
                if (roomKind != RoomKind.AI_CHAT) {
                    OutlinedTextField(
                        value = humanNameDraft,
                        onValueChange = { value -> humanNameDraft = value },
                        label = { Text("Placeholder human name") },
                        supportingText = {
                            Text(
                                if (roomKind == RoomKind.DIRECT) {
                                    "A direct room requires exactly one other human."
                                } else {
                                    "A group room requires at least one other human."
                                },
                            )
                        },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = ::addPendingHumanName, enabled = humanNameDraft.isNotBlank()) {
                                Icon(Icons.Rounded.Add, contentDescription = "Add human")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    humanNames.forEach { humanName ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(humanName, modifier = Modifier.weight(1f))
                            IconButton(onClick = { humanNames = humanNames - humanName }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove $humanName")
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Add Synapse", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (roomKind == RoomKind.AI_CHAT) {
                                "AI chats always start with Synapse."
                            } else {
                                "Synapse responds when mentioned unless automatic response is enabled."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = includeSynapseAi,
                        onCheckedChange = { enabled ->
                            includeSynapseAi = enabled
                            if (!enabled) synapseAiAutoResponse = false
                        },
                        enabled = roomKind != RoomKind.AI_CHAT,
                    )
                }
                if (includeSynapseAi && roomKind != RoomKind.AI_CHAT) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Automatic AI responses", modifier = Modifier.weight(1f))
                        Switch(
                            checked = synapseAiAutoResponse,
                            onCheckedChange = { enabled -> synapseAiAutoResponse = enabled },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        CreateRoomCommand(
                            title = normalizedTitle,
                            kind = roomKind,
                            placeholderHumanDisplayNames = requestedHumanNames,
                            includeSynapseAi = includeSynapseAi,
                            synapseAiAutoResponseEnabled = synapseAiAutoResponse,
                        ),
                    )
                },
                enabled = normalizedTitle.isNotEmpty() && hasValidHumanMemberCount,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ChatRoomActionsDialog(
    room: ChatRoomRecord,
    onDismiss: () -> Unit,
    onPinnedChanged: (Boolean) -> Unit,
    onRenameRequested: () -> Unit,
    onArchiveRequested: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = room.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChatRoomActionButton(
                    icon = Icons.Rounded.PushPin,
                    label = if (room.isPinned) "Unpin room" else "Pin room",
                    onClick = { onPinnedChanged(!room.isPinned) },
                )
                ChatRoomActionButton(
                    icon = Icons.Rounded.Edit,
                    label = "Rename room",
                    onClick = onRenameRequested,
                )
                ChatRoomActionButton(
                    icon = Icons.Rounded.Archive,
                    label = "Archive room",
                    onClick = onArchiveRequested,
                )
                ChatRoomActionButton(
                    icon = Icons.Rounded.Delete,
                    label = "Delete room",
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = onDeleteRequested,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ChatRoomActionButton(
    icon: ImageVector,
    label: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Text(text = label, color = contentColor)
        }
    }
}

@Composable
private fun RenameChatRoomDialog(
    room: ChatRoomRecord,
    onDismiss: () -> Unit,
    onRenamed: (String) -> Unit,
) {
    var draftTitle by remember(room.id.raw) { mutableStateOf(room.title) }
    val normalizedTitle = draftTitle.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename room") },
        text = {
            OutlinedTextField(
                value = draftTitle,
                onValueChange = { value -> draftTitle = value },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRenamed(normalizedTitle) },
                enabled = normalizedTitle.isNotEmpty(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DeleteChatRoomDialog(
    room: ChatRoomRecord,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete room?") },
        text = {
            Text(
                text = "This removes ${room.title} and its messages from Synapse Chat.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onDeleted) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
