package app.synapse.localllm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteGroupMember
import app.synapse.localllm.domain.remote.RemoteGroupRoomDetails
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import coil3.compose.AsyncImage

@Composable
internal fun RemoteGroupCreateDialog(
    profiles: List<RemoteCachedProfile>,
    currentAccountUid: String?,
    blockedProfileUids: Set<RemoteProfileUid>,
    isActionRunning: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, Set<RemoteProfileUid>) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var selectedUids by remember { mutableStateOf(emptySet<RemoteProfileUid>()) }
    val candidates = profiles.filter { profile ->
        profile.profileUid.raw != currentAccountUid && profile.profileUid !in blockedProfileUids
    }
    val normalizedTitle = title.trim()
    AlertDialog(
        onDismissRequest = { if (!isActionRunning) onDismiss() },
        title = { Text("New group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { value -> title = value.take(MAXIMUM_GROUP_TITLE_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Group name") },
                    enabled = !isActionRunning,
                    singleLine = true,
                )
                Text("Choose at least one approved person.", style = MaterialTheme.typography.bodySmall)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    candidates.forEach { profile ->
                        val selected = profile.profileUid in selectedUids
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isActionRunning) {
                                    selectedUids = if (selected) {
                                        selectedUids - profile.profileUid
                                    } else {
                                        selectedUids + profile.profileUid
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = null,
                                enabled = !isActionRunning,
                            )
                            RemoteProfileAvatar(profile, profile.displayName)
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                                Text("@${profile.username}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (candidates.isEmpty()) {
                        Text(
                            "No eligible approved people are available.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(normalizedTitle, selectedUids) },
                enabled = normalizedTitle.isNotEmpty() && selectedUids.isNotEmpty() && !isActionRunning,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isActionRunning) { Text("Cancel") }
        },
    )
}

@Composable
internal fun RemoteGroupDetailsPane(
    details: RemoteGroupRoomDetails?,
    profiles: List<RemoteCachedProfile>,
    blockedProfileUids: Set<RemoteProfileUid>,
    isLoading: Boolean,
    isActionRunning: Boolean,
    viewModel: RemoteGroupViewModel,
    modifier: Modifier = Modifier,
) {
    if (isLoading || details == null) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (isLoading) CircularProgressIndicator() else Text("Group details are unavailable.")
        }
        return
    }
    val context = LocalContext.current
    var editedTitle by rememberSaveable(details.roomId.raw, details.revision) { mutableStateOf(details.title) }
    var isMuted by rememberSaveable(details.roomId.raw, details.revision) { mutableStateOf(details.isMuted) }
    var isArchived by rememberSaveable(details.roomId.raw, details.revision) { mutableStateOf(details.isArchived) }
    var isPinned by rememberSaveable(details.roomId.raw, details.revision) { mutableStateOf(details.isPinned) }
    var showAddMembers by rememberSaveable(details.roomId.raw) { mutableStateOf(false) }
    var selectedNewMembers by remember(details.roomId.raw) { mutableStateOf(emptySet<RemoteProfileUid>()) }
    var memberPendingRemoval by remember { mutableStateOf<RemoteGroupMember?>(null) }
    var memberPendingTransfer by remember { mutableStateOf<RemoteGroupMember?>(null) }
    var showLeaveConfirmation by rememberSaveable(details.roomId.raw) { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable(details.roomId.raw) { mutableStateOf(false) }
    var deletionPassword by remember { mutableStateOf("") }
    var deletionTitle by remember { mutableStateOf("") }
    val profilesByUid = profiles.associateBy(RemoteCachedProfile::profileUid)
    val currentUid = details.accountUid.raw
    val isAdministrator = details.currentMemberRole in setOf(
        RemoteRoomMemberRole.OWNER,
        RemoteRoomMemberRole.ADMIN,
    )
    val currentMemberUids = details.members.mapTo(mutableSetOf(), RemoteGroupMember::profileUid)
    val addableProfiles = profiles.filter { profile ->
        profile.profileUid.raw != currentUid &&
            profile.profileUid !in currentMemberUids &&
            profile.profileUid !in blockedProfileUids
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        viewModel.setGroupAvatar(details.roomId, uri.toString(), mimeType)
    }
    ClearSensitiveInputsOnStop {
        deletionPassword = ""
        deletionTitle = ""
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        details.avatarUrl?.let { avatarUrl ->
            AsyncImage(
                model = avatarUrl,
                contentDescription = "Group photo for ${details.title}",
                modifier = Modifier.size(88.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Text(details.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Your role: ${details.currentMemberRole.name.lowercase().replaceFirstChar(Char::uppercaseChar)}")
        if (isAdministrator) {
            OutlinedTextField(
                value = editedTitle,
                onValueChange = { value -> editedTitle = value.take(MAXIMUM_GROUP_TITLE_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Group name") },
                singleLine = true,
                enabled = !isActionRunning,
            )
            Button(
                onClick = { viewModel.renameGroup(details.roomId, editedTitle) },
                enabled = editedTitle.trim().isNotEmpty() && editedTitle.trim() != details.title && !isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save group name")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { avatarPicker.launch("image/*") },
                    enabled = !isActionRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Choose photo")
                }
                OutlinedButton(
                    onClick = { viewModel.clearGroupAvatar(details.roomId) },
                    enabled = details.avatarObjectPath != null && !isActionRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Remove photo")
                }
            }
        }

        HorizontalDivider()
        Text("Your group preferences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        GroupPreferenceRow("Mute notifications", isMuted, !isActionRunning) { isMuted = it }
        GroupPreferenceRow("Archive group", isArchived, !isActionRunning) { isArchived = it }
        GroupPreferenceRow("Pin group", isPinned, !isActionRunning) { isPinned = it }
        Button(
            onClick = { viewModel.updatePreferences(details.roomId, isArchived, isMuted, isPinned) },
            enabled = !isActionRunning && (
                isMuted != details.isMuted || isArchived != details.isArchived || isPinned != details.isPinned
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save preferences")
        }

        HorizontalDivider()
        Text("Members (${details.members.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        details.members.forEachIndexed { index, member ->
            val profile = profilesByUid[member.profileUid]
            val displayName = when {
                member.profileUid.raw == currentUid -> "You"
                profile != null -> profile.displayName
                else -> "Approved member ${index + 1}"
            }
            ListItem(
                headlineContent = { Text(displayName, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text(member.role.name.lowercase().replaceFirstChar(Char::uppercaseChar))
                },
                leadingContent = { RemoteProfileAvatar(profile, displayName) },
            )
            if (member.profileUid.raw != currentUid) {
                val canRemove = when (details.currentMemberRole) {
                    RemoteRoomMemberRole.OWNER -> member.role != RemoteRoomMemberRole.OWNER
                    RemoteRoomMemberRole.ADMIN -> member.role == RemoteRoomMemberRole.MEMBER
                    RemoteRoomMemberRole.MEMBER -> false
                }
                if (details.currentMemberRole == RemoteRoomMemberRole.OWNER &&
                    member.role != RemoteRoomMemberRole.OWNER
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val role = if (member.role == RemoteRoomMemberRole.ADMIN) {
                                    RemoteRoomMemberRole.MEMBER
                                } else {
                                    RemoteRoomMemberRole.ADMIN
                                }
                                viewModel.setMemberRole(details.roomId, member.profileUid, role)
                            },
                            enabled = !isActionRunning,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (member.role == RemoteRoomMemberRole.ADMIN) "Make member" else "Make admin")
                        }
                        OutlinedButton(
                            onClick = { memberPendingTransfer = member },
                            enabled = !isActionRunning,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Transfer ownership")
                        }
                    }
                }
                if (canRemove) {
                    TextButton(
                        onClick = { memberPendingRemoval = member },
                        enabled = !isActionRunning,
                    ) {
                        Text("Remove $displayName", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider()
        }

        if (isAdministrator) {
            OutlinedButton(
                onClick = { showAddMembers = !showAddMembers },
                enabled = !isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAddMembers) "Cancel adding members" else "Add members")
            }
            if (showAddMembers) {
                addableProfiles.forEach { profile ->
                    val selected = profile.profileUid in selectedNewMembers
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isActionRunning) {
                                selectedNewMembers = if (selected) {
                                    selectedNewMembers - profile.profileUid
                                } else {
                                    selectedNewMembers + profile.profileUid
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected, onCheckedChange = null, enabled = !isActionRunning)
                        Text(profile.displayName)
                    }
                }
                if (addableProfiles.isEmpty()) Text("No eligible approved people are available.")
                Button(
                    onClick = {
                        viewModel.addMembers(details.roomId, selectedNewMembers)
                        selectedNewMembers = emptySet()
                        showAddMembers = false
                    },
                    enabled = selectedNewMembers.isNotEmpty() && !isActionRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add selected members")
                }
            }
        }

        HorizontalDivider()
        if (details.currentMemberRole != RemoteRoomMemberRole.OWNER) {
            OutlinedButton(
                onClick = { showLeaveConfirmation = true },
                enabled = !isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Leave group")
            }
        } else {
            Text(
                "Transfer ownership before leaving. Deleting the group requires your current password.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { showDeleteConfirmation = true },
                enabled = !isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete group", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    memberPendingRemoval?.let { member ->
        val displayName = profilesByUid[member.profileUid]?.displayName ?: "this approved member"
        ConfirmGroupActionDialog(
            title = "Remove member?",
            detail = "$displayName will immediately lose server access to this group.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.removeMember(details.roomId, member.profileUid)
                memberPendingRemoval = null
            },
            onDismiss = { memberPendingRemoval = null },
        )
    }
    memberPendingTransfer?.let { member ->
        val displayName = profilesByUid[member.profileUid]?.displayName ?: "this approved member"
        ConfirmGroupActionDialog(
            title = "Transfer ownership?",
            detail = "$displayName will become the owner and you will become an admin.",
            confirmLabel = "Transfer",
            onConfirm = {
                viewModel.transferOwnership(details.roomId, member.profileUid)
                memberPendingTransfer = null
            },
            onDismiss = { memberPendingTransfer = null },
        )
    }
    if (showLeaveConfirmation) {
        ConfirmGroupActionDialog(
            title = "Leave group?",
            detail = "This phone will remove its cached copy after the server confirms you left.",
            confirmLabel = "Leave",
            onConfirm = {
                viewModel.leaveGroup(details.roomId)
                showLeaveConfirmation = false
            },
            onDismiss = { showLeaveConfirmation = false },
        )
    }
    if (showDeleteConfirmation) {
        BlockScreenshotsWhileVisible()
        AlertDialog(
            onDismissRequest = {
                if (!isActionRunning) {
                    deletionPassword = ""
                    deletionTitle = ""
                    showDeleteConfirmation = false
                }
            },
            title = { Text("Delete group permanently?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Type ${details.title} and enter your current password.")
                    OutlinedTextField(
                        value = deletionTitle,
                        onValueChange = { deletionTitle = it },
                        label = { Text("Group name") },
                        enabled = !isActionRunning,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = deletionPassword,
                        onValueChange = { deletionPassword = it },
                        label = { Text("Current password") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !isActionRunning,
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteGroup(
                            details.roomId,
                            currentPassword = deletionPassword,
                            confirmTitle = deletionTitle,
                        )
                        deletionPassword = ""
                    },
                    enabled = deletionTitle == details.title && deletionPassword.isNotEmpty() && !isActionRunning,
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deletionPassword = ""
                        deletionTitle = ""
                        showDeleteConfirmation = false
                    },
                    enabled = !isActionRunning,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun GroupPreferenceRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun ConfirmGroupActionDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAXIMUM_GROUP_TITLE_LENGTH = 80
