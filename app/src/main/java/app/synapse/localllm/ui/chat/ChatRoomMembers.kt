package app.synapse.localllm.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import app.synapse.localllm.domain.chat.AiResponsePolicy
import app.synapse.localllm.domain.chat.BuiltInParticipantIds
import app.synapse.localllm.domain.chat.ChatRoomRecord
import app.synapse.localllm.domain.chat.ParticipantKind
import app.synapse.localllm.domain.chat.RoomKind
import app.synapse.localllm.domain.chat.RoomMemberRecord
import app.synapse.localllm.domain.chat.RoomMemberRole

@Composable
internal fun ChatRoomHeader(
    room: ChatRoomRecord,
    members: List<RoomMemberRecord>,
    onOpenMembers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeMembers = members.filter(RoomMemberRecord::isActive)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = room.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${room.kind.toDisplayLabel()} · " +
                        activeMembers.joinToString(", ") { member -> member.participant.displayName }
                            .ifBlank { "No active members" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpenMembers) {
                Icon(Icons.Rounded.Group, contentDescription = null)
                Text("Members")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatRoomMemberSheet(
    room: ChatRoomRecord,
    members: List<RoomMemberRecord>,
    onDismiss: () -> Unit,
    onAddHuman: (String) -> Unit,
    onRemoveMember: (RoomMemberRecord) -> Unit,
    onSynapseEnabledChanged: (Boolean) -> Unit,
    onAiAutoResponseChanged: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var humanNameDraft by remember(room.id.raw) { mutableStateOf("") }
    val orderedMembers = members.sortedWith(
        compareByDescending<RoomMemberRecord> { member -> member.isActive }
            .thenBy { member -> member.joinedAt },
    )
    val activeSynapseMember = members.firstOrNull { member ->
        member.isActive && member.participant.id == BuiltInParticipantIds.SYNAPSE_LOCAL_AI
    }
    val canAddHuman = room.kind == RoomKind.GROUP
    val canManageSynapseMembership = room.kind == RoomKind.DIRECT || room.kind == RoomKind.GROUP
    val aiAutoResponseEnabled = room.kind == RoomKind.AI_CHAT ||
        activeSynapseMember?.aiResponsePolicy == AiResponsePolicy.AUTOMATIC

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Room members", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "${room.title} · ${room.kind.toDisplayLabel()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            orderedMembers.forEach { member ->
                ChatRoomMemberRow(
                    member = member,
                    allowRemoval = member.isActive &&
                        member.role != RoomMemberRole.OWNER &&
                        member.participant.id != BuiltInParticipantIds.SYNAPSE_LOCAL_AI,
                    onRemove = { onRemoveMember(member) },
                )
            }
            HorizontalDivider()
            if (canAddHuman) {
                OutlinedTextField(
                    value = humanNameDraft,
                    onValueChange = { value -> humanNameDraft = value },
                    label = { Text("Placeholder human name") },
                    supportingText = { Text("Placeholder profiles stay local to Synapse Chat.") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                onAddHuman(humanNameDraft.trim())
                                humanNameDraft = ""
                            },
                            enabled = humanNameDraft.isNotBlank(),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add human member")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = if (room.kind == RoomKind.AI_CHAT) {
                        "AI chats contain the local owner and Synapse. Create a direct or group room for other people."
                    } else {
                        "Direct rooms stay bound to their original human member. Create a new direct room for someone else."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (canManageSynapseMembership) {
                Button(
                    onClick = { onSynapseEnabledChanged(activeSynapseMember == null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (activeSynapseMember == null) "Add Synapse" else "Remove Synapse")
                }
            }
            if (activeSynapseMember != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic AI responses", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (room.kind == RoomKind.AI_CHAT) {
                                "Synapse always responds in AI chat rooms."
                            } else if (aiAutoResponseEnabled) {
                                "Synapse responds to every human message."
                            } else {
                                "Synapse responds only when @Synapse is mentioned."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = aiAutoResponseEnabled,
                        onCheckedChange = onAiAutoResponseChanged,
                        enabled = room.kind != RoomKind.AI_CHAT,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ChatRoomMemberRow(
    member: RoomMemberRecord,
    allowRemoval: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ParticipantAvatar(participant = member.participant)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.participant.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = buildString {
                    append(member.participant.kind.name.replace('_', ' ').lowercase())
                    append(" · ")
                    append(member.role.name.lowercase())
                    if (!member.isActive) append(" · left room")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (allowRemoval) {
            TextButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}
