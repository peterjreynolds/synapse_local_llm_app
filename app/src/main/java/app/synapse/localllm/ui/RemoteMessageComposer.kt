package app.synapse.localllm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.synapse.localllm.domain.remote.RemoteAssistantAvailability
import app.synapse.localllm.domain.remote.RemoteAttachmentId

@Composable
internal fun RemoteMessageComposer(
    state: RemoteChatUiState,
    onSend: (String) -> Unit,
    onComposerChanged: (String) -> Unit,
    onAttachmentSelected: (String) -> Unit,
    onRetryAttachment: (RemoteAttachmentId) -> Unit,
    onCancelAttachment: (RemoteAttachmentId) -> Unit,
    onStartVoiceNote: () -> Unit,
    onFinishVoiceNote: () -> Unit,
    onCancelVoiceNote: () -> Unit,
    onVoicePermissionDenied: () -> Unit,
    onCancelReply: () -> Unit,
    onMentionSynapse: () -> Unit,
    onMentionCinder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showAddMenu by rememberSaveable(state.selectedRoomId?.raw) { mutableStateOf(false) }
    val photoAndGifPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        onAttachmentSelected(uri.toString())
    }
    val fileAndAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        onAttachmentSelected(uri.toString())
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted ->
        if (granted) onStartVoiceNote() else onVoicePermissionDenied()
    }
    val assistantUnavailableReason =
        (state.selectedAssistantAvailability as? RemoteAssistantAvailability.Unavailable)?.userMessage
    val submissionEnabled = remoteComposerSubmissionEnabled(state.selectedAssistantAvailability)
    val isAssistantConversation = state.selectedAssistantEndpoint != null
    val voiceNotesEnabled = !isAssistantConversation
    val canAddAttachment = submissionEnabled &&
        !isAssistantConversation &&
        !state.isRecordingVoiceNote &&
        !state.isActionRunning &&
        state.pendingAttachments.size < MAXIMUM_PENDING_ATTACHMENTS
    val showMentionSynapse = state.roomAiConfiguration?.localAiEnabled == true
    val showMentionCinder = state.cinderParticipant?.active == true
    val canOpenAddMenu = submissionEnabled &&
        !state.isRecordingVoiceNote &&
        !state.isActionRunning &&
        (canAddAttachment || showMentionSynapse || showMentionCinder)
    val canSend = remoteComposerCanSend(
        composerText = state.composerText,
        attachmentStates = state.pendingAttachments.map(RemotePendingAttachmentUi::state),
        isRecordingVoiceNote = state.isRecordingVoiceNote,
        isActionRunning = state.isActionRunning,
        submissionEnabled = submissionEnabled,
    )
    val primaryAction = remoteComposerPrimaryAction(
        canSend = canSend,
        isRecordingVoiceNote = state.isRecordingVoiceNote,
    )

    fun submit() {
        if (canSend) onSend(state.composerText)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.replyToMessageId?.let { replyId ->
            val repliedMessage = state.messages.firstOrNull { message -> message.messageId == replyId }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Replying to ${repliedMessage?.body?.take(80) ?: "message"}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = onCancelReply) { Text("Cancel") }
            }
        }
        RemotePendingAttachmentList(
            attachments = state.pendingAttachments,
            onRetry = onRetryAttachment,
            onCancel = onCancelAttachment,
        )
        assistantUnavailableReason?.let { reason ->
            Text(
                text = reason,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (isAssistantConversation) {
            Text(
                text = "Cinder currently accepts text messages only.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.isRecordingVoiceNote) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Recording voice note", style = MaterialTheme.typography.labelLarge)
                        Text("Tap stop to attach it", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = onCancelVoiceNote) { Text("Cancel") }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box {
                    IconButton(
                        onClick = { showAddMenu = true },
                        enabled = canOpenAddMenu,
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "Add photos, GIFs, files, or audio",
                        )
                    }
                    DropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = { showAddMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Photos & GIFs") },
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                            enabled = canAddAttachment,
                            onClick = {
                                showAddMenu = false
                                photoAndGifPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Files & audio") },
                            leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                            enabled = canAddAttachment,
                            onClick = {
                                showAddMenu = false
                                fileAndAudioPicker.launch(REMOTE_FILE_AND_AUDIO_MIME_TYPES)
                            },
                        )
                        if (showMentionSynapse) {
                            DropdownMenuItem(
                                text = { Text("Mention Synapse") },
                                leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
                                enabled = !state.isActionRunning,
                                onClick = {
                                    showAddMenu = false
                                    onMentionSynapse()
                                },
                            )
                        }
                        if (showMentionCinder) {
                            DropdownMenuItem(
                                text = { Text("Mention Cinder") },
                                leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
                                enabled = !state.isActionRunning,
                                onClick = {
                                    showAddMenu = false
                                    onMentionCinder()
                                },
                            )
                        }
                    }
                }
                BasicTextField(
                    value = state.composerText,
                    onValueChange = onComposerChanged,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 14.dp),
                    enabled = !state.isActionRunning,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.composerText.isEmpty()) {
                                Text(
                                    "Message",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                when (primaryAction) {
                    RemoteComposerPrimaryAction.SEND -> FilledIconButton(
                        onClick = ::submit,
                        enabled = canSend,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                    }

                    RemoteComposerPrimaryAction.RECORD_VOICE,
                    RemoteComposerPrimaryAction.STOP_RECORDING,
                    -> IconButton(
                        onClick = {
                            if (primaryAction == RemoteComposerPrimaryAction.STOP_RECORDING) {
                                onFinishVoiceNote()
                            } else if (
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                onStartVoiceNote()
                            } else {
                                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = state.isRecordingVoiceNote ||
                            (!state.isActionRunning && submissionEnabled && voiceNotesEnabled),
                        modifier = if (state.isRecordingVoiceNote) {
                            Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                        } else {
                            Modifier
                        },
                    ) {
                        Icon(
                            imageVector = if (state.isRecordingVoiceNote) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (state.isRecordingVoiceNote) {
                                "Stop and attach voice note"
                            } else {
                                "Record voice note"
                            },
                            tint = if (state.isRecordingVoiceNote) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

internal enum class RemoteComposerPrimaryAction {
    RECORD_VOICE,
    SEND,
    STOP_RECORDING,
}

internal fun remoteComposerPrimaryAction(
    canSend: Boolean,
    isRecordingVoiceNote: Boolean,
): RemoteComposerPrimaryAction = when {
    isRecordingVoiceNote -> RemoteComposerPrimaryAction.STOP_RECORDING
    canSend -> RemoteComposerPrimaryAction.SEND
    else -> RemoteComposerPrimaryAction.RECORD_VOICE
}

internal fun remoteComposerCanSend(
    composerText: String,
    attachmentStates: List<RemoteAttachmentTransferState>,
    isRecordingVoiceNote: Boolean,
    isActionRunning: Boolean,
    submissionEnabled: Boolean = true,
): Boolean = submissionEnabled && !isRecordingVoiceNote && !isActionRunning && (
    composerText.isNotBlank() ||
        (
            attachmentStates.isNotEmpty() &&
                attachmentStates.all { state -> state == RemoteAttachmentTransferState.READY }
            )
    )

internal fun remoteComposerSubmissionEnabled(availability: RemoteAssistantAvailability?): Boolean =
    availability !is RemoteAssistantAvailability.Unavailable

private const val MAXIMUM_PENDING_ATTACHMENTS = 8
