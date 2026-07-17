package app.synapse.localllm.ui

import android.content.ClipData
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.application.RemoteLocalAiHostStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

@Composable
internal fun RemoteMessageThread(
    state: RemoteChatUiState,
    room: RemoteCachedRoom?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onComposerChanged: (String) -> Unit,
    onAttachmentSelected: (String) -> Unit,
    onRetryAttachment: (RemoteAttachmentId) -> Unit,
    onCancelAttachment: (RemoteAttachmentId) -> Unit,
    onDownloadAttachment: (
        RemoteCachedMessage,
        RemoteAttachmentId,
        Boolean,
    ) -> Unit,
    onCancelAttachmentDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onStartVoiceNote: () -> Unit,
    onFinishVoiceNote: () -> Unit,
    onCancelVoiceNote: () -> Unit,
    onVoicePermissionDenied: () -> Unit,
    onReply: (RemoteMessageId) -> Unit,
    onCancelReply: () -> Unit,
    onEdit: (RemoteCachedMessage, String) -> Unit,
    onDelete: (RemoteCachedMessage) -> Unit,
    onLoadOlder: () -> Unit,
    onJumpToMessage: (RemoteMessageId) -> Unit,
    onMessageRevealed: () -> Unit,
    onLocalAiConfigurationChanged: (Boolean, Boolean) -> Unit,
    onMentionSynapse: () -> Unit,
    accountState: RemoteAccountUiState,
    groupState: RemoteGroupUiState,
    groupViewModel: RemoteGroupViewModel,
) {
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

    LaunchedEffect(state.messages.lastOrNull()?.messageId) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    LaunchedEffect(state.messageToRevealId, state.messages) {
        val messageId = state.messageToRevealId ?: return@LaunchedEffect
        val index = state.messages.indexOfFirst { message -> message.messageId == messageId }
        if (index >= 0) listState.animateScrollToItem(index)
        onMessageRevealed()
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
                    text = state.typingParticipantUids.takeIf { typingUids -> typingUids.isNotEmpty() }?.let { typingUids ->
                        typingUids.mapNotNull { uid ->
                            state.profiles.firstOrNull { profile -> profile.profileUid == uid }?.displayName
                        }.joinToString().takeIf(String::isNotBlank)?.plus(" typing…")
                    } ?: if (room?.kind == RemoteRoomKind.GROUP) "Group conversation" else remotePresenceLabel(peer),
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
            Column(modifier = Modifier.weight(1f)) {
                RemoteAiParticipantControls(
                    state = state,
                    room = room,
                    onConfigurationChanged = onLocalAiConfigurationChanged,
                )
                HorizontalDivider()
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
                item(key = "load-older") {
                    OutlinedButton(
                        onClick = onLoadOlder,
                        enabled = !state.hasReachedMessageStart && !state.isLoadingOlderMessages,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.hasReachedMessageStart) "Start of conversation" else "Load earlier messages")
                    }
                }
                itemsIndexed(
                    items = state.messages,
                    key = { _, message -> message.messageId.raw },
                ) { index, message ->
                    val previousDate = state.messages.getOrNull(index - 1)?.displayInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()
                    val messageDate = message.displayInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                    if (messageDate != previousDate) {
                        Text(
                            text = remoteMessageDateLabel(messageDate),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    RemoteMessageBubble(
                        message = message,
                        repliedMessage = message.replyToMessageId?.let { replyId ->
                            state.messages.firstOrNull { candidate -> candidate.messageId == replyId }
                        },
                        isCurrentAccount = message.senderUid.raw == state.account?.accountUid?.raw,
                        canDelete = message.senderUid.raw == state.account?.accountUid?.raw ||
                            (
                                message.authorKind == "SYNAPSE_AI" &&
                                    state.roomAiConfiguration?.localAiHostUid == state.account?.accountUid
                                ),
                        senderDisplayName = if (message.authorKind == "SYNAPSE_AI") {
                            "Synapse • Phone-local AI"
                        } else if (room?.kind == RemoteRoomKind.GROUP) {
                            state.profiles.firstOrNull { profile -> profile.profileUid == message.senderUid }
                                ?.displayName
                                ?: "Group member"
                        } else {
                            null
                        },
                        onReply = { onReply(message.messageId) },
                        onEdit = { body -> onEdit(message, body) },
                        onDelete = { onDelete(message) },
                        attachmentDownloads = state.attachmentDownloads,
                        onDownloadAttachment = { attachmentId, thumbnail ->
                            onDownloadAttachment(message, attachmentId, thumbnail)
                        },
                        onCancelAttachmentDownload = onCancelAttachmentDownload,
                        onJumpToReply = { replyId -> onJumpToMessage(replyId) },
                    )
                }
            }
        }
        if (!showRoomMembers) {
            HorizontalDivider()
            RemoteMessageComposer(
                state = state,
                onSend = onSend,
                onComposerChanged = onComposerChanged,
                onAttachmentSelected = onAttachmentSelected,
                onRetryAttachment = onRetryAttachment,
                onCancelAttachment = onCancelAttachment,
                onStartVoiceNote = onStartVoiceNote,
                onFinishVoiceNote = onFinishVoiceNote,
                onCancelVoiceNote = onCancelVoiceNote,
                onVoicePermissionDenied = onVoicePermissionDenied,
                onCancelReply = onCancelReply,
                onMentionSynapse = onMentionSynapse,
            )
        }
    }
}

@Composable
private fun RemoteAiParticipantControls(
    state: RemoteChatUiState,
    room: RemoteCachedRoom?,
    onConfigurationChanged: (Boolean, Boolean) -> Unit,
) {
    val configuration = state.roomAiConfiguration
    val canRetainOrDesignateHost = state.currentDeviceId != null ||
        configuration?.localAiHostUid == state.account?.accountUid
    val canManage = room != null && (
        room.kind == RemoteRoomKind.DIRECT ||
            room.currentMemberRole == RemoteRoomMemberRole.OWNER ||
            room.currentMemberRole == RemoteRoomMemberRole.ADMIN
        )
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("AI participants", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (configuration == null) {
            Text("Loading AI participant settings…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (configuration.localAiEnabled) {
            val thisPhoneIsAvailableHost = configuration.localAiHostDeviceId == state.currentDeviceId &&
                state.localAiHostStatus !is RemoteLocalAiHostStatus.Unavailable
            val hostAvailable = configuration.localAiHostAvailable || thisPhoneIsAvailableHost
            Text("Synapse • Phone-local AI", fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    configuration.localAiHostDeviceId == state.currentDeviceId &&
                        state.localAiHostStatus is RemoteLocalAiHostStatus.Generating ->
                        "This phone is generating the room's one leased AI reply."

                    hostAvailable ->
                        "Designated host is online. Exactly one device may post each AI reply."

                    else ->
                        "Designated host is offline or unavailable. AI messages will wait without duplicate replies."
                },
                color = if (hostAvailable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                if (configuration.localAiAutoResponse) {
                    "Response policy: every human message"
                } else {
                    "Response policy: explicit @Synapse mentions only"
                },
            )
            if (canManage) {
                OutlinedButton(
                    onClick = { onConfigurationChanged(true, !configuration.localAiAutoResponse) },
                    enabled = !state.isActionRunning && canRetainOrDesignateHost,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (configuration.localAiAutoResponse) "Require @Synapse mention" else "Respond automatically")
                }
                OutlinedButton(
                    onClick = { onConfigurationChanged(false, false) },
                    enabled = !state.isActionRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove Synapse from conversation")
                }
            }
        } else {
            Text("Human-only conversation. Local inference will never run for messages in this room.")
            if (canManage) {
                OutlinedButton(
                    onClick = { onConfigurationChanged(true, false) },
                    enabled = !state.isActionRunning && state.currentDeviceId != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add Synapse using this phone")
                }
                if (state.currentDeviceId == null) {
                    Text(
                        "This phone must finish device registration before it can become the designated AI host.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Text(
            "Hosted AI is disabled: no provider or paid budget has been approved. Activation requires an approved " +
                "server provider and a Secret Manager credential; no API key belongs in this app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemoteMessageBubble(
    message: RemoteCachedMessage,
    repliedMessage: RemoteCachedMessage?,
    isCurrentAccount: Boolean,
    canDelete: Boolean,
    senderDisplayName: String?,
    onReply: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    attachmentDownloads: Map<String, RemoteAttachmentDownloadUi>,
    onDownloadAttachment: (RemoteAttachmentId, Boolean) -> Unit,
    onCancelAttachmentDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onJumpToReply: (RemoteMessageId) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var showEditDialog by rememberSaveable(message.messageId.raw) { mutableStateOf(false) }
    var editText by rememberSaveable(message.messageId.raw) { mutableStateOf(message.body) }
    var showDeleteDialog by rememberSaveable(message.messageId.raw) { mutableStateOf(false) }
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
                message.replyToMessageId?.let { replyId ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onJumpToReply(replyId) },
                    ) {
                        Text(
                            text = repliedMessage?.let { replied ->
                                if (replied.deletedAt != null) "Message deleted" else replied.body.take(100)
                            } ?: "Open replied message",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(if (message.deletedAt != null) "Message deleted" else message.body)
                if (message.deletedAt == null) {
                    message.attachments.forEach { attachment ->
                        RemoteMessageAttachmentCard(
                            attachment = attachment,
                            downloads = attachmentDownloads,
                            onDownload = onDownloadAttachment,
                            onCancelDownload = onCancelAttachmentDownload,
                        )
                    }
                }
                if (message.editedAt != null && message.deletedAt == null) {
                    Text("Edited", style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (message.deletedAt == null) {
                        TextButton(onClick = onReply) { Text("Reply") }
                        TextButton(onClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Synapse message", message.body)))
                            }
                        }) {
                            Text("Copy")
                        }
                    }
                    if (isCurrentAccount && message.deletedAt == null) {
                        TextButton(onClick = {
                            editText = message.body
                            showEditDialog = true
                        }) { Text("Edit") }
                    }
                    if (canDelete && message.deletedAt == null) {
                        TextButton(onClick = { showDeleteDialog = true }) { Text("Delete") }
                    }
                }
                Text(
                    text = remoteMessageTimestamp(message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isCurrentAccount || message.deliveryState != RemoteMessageDeliveryState.SENT) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = remoteMessageDeliveryLabel(message),
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
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { value -> editText = value.take(MAXIMUM_MESSAGE_LENGTH) },
                    maxLines = 6,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEdit(editText)
                        showEditDialog = false
                    },
                    enabled = editText.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancel") } },
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete message?") },
            text = { Text("The message will remain as a deletion marker for everyone in the room.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }
}

internal fun RemoteCachedMessage.displayInstant() = serverCreatedAt ?: clientCreatedAt

internal fun remoteMessageDateLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

internal fun remoteMessageTimestamp(message: RemoteCachedMessage): String =
    message.displayInstant()
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

internal fun remoteMessageDeliveryLabel(message: RemoteCachedMessage): String =
    when (message.deliveryState) {
        RemoteMessageDeliveryState.PENDING -> "Sending…"
        RemoteMessageDeliveryState.SENT -> "Sent"
        RemoteMessageDeliveryState.DELIVERED -> "Delivered"
        RemoteMessageDeliveryState.READ -> "Read"
        RemoteMessageDeliveryState.FAILED -> message.failureReason ?: "Send failed"
    }

private const val MAXIMUM_MESSAGE_LENGTH = 4_000
