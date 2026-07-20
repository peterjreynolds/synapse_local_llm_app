package app.synapse.localllm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synapse.localllm.R
import app.synapse.localllm.domain.chat.BuiltInParticipantIds
import app.synapse.localllm.domain.chat.PendingAttachment
import app.synapse.localllm.domain.runtime.RuntimeStartStatus
import app.synapse.localllm.domain.runtime.RuntimeStatus

@Composable
internal fun SynapseTopBar(
    state: SynapseUiState,
    onPanelSelected: (SynapsePanel) -> Unit,
    onOpenAppNavigation: (() -> Unit)?,
    onRoomDrawerOpen: () -> Unit,
    onRuntimeCheck: () -> Unit,
    onRuntimeStart: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val actionableRuntimeLabel = state.runtimeStatus.toActionableRuntimeLabel()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onOpenAppNavigation ?: onRoomDrawerOpen,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.Menu,
                contentDescription = if (onOpenAppNavigation == null) "Rooms" else "Open app navigation",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Icon(
            painter = painterResource(R.drawable.synapse_guild_mark),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Local AI",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (actionableRuntimeLabel != null) {
                Text(
                    text = actionableRuntimeLabel,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onOpenAppNavigation != null) {
            IconButton(
                onClick = onRoomDrawerOpen,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Chat,
                    contentDescription = "Local AI rooms",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "Local AI menu",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                localAiTopBarActions(actionableRuntimeLabel != null).forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label()) },
                        leadingIcon = { Icon(action.icon(), contentDescription = null) },
                        onClick = {
                            showMenu = false
                            when (action) {
                                LocalAiTopBarAction.START_LOCAL_AI -> onRuntimeStart()
                                LocalAiTopBarAction.RUNTIME_DIAGNOSTICS -> {
                                    onRuntimeCheck()
                                    onPanelSelected(SynapsePanel.SETTINGS)
                                }

                                LocalAiTopBarAction.LIBRARY -> onPanelSelected(SynapsePanel.LIBRARY)
                                LocalAiTopBarAction.MEMORY -> onPanelSelected(SynapsePanel.MEMORY)
                                LocalAiTopBarAction.SETTINGS -> onPanelSelected(SynapsePanel.SETTINGS)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ComposerBar(
    state: SynapseUiState,
    onComposerChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onStartSpeech: () -> Unit,
    onVoiceModeToggle: () -> Unit,
    onMentionSynapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val synapseIsActive = state.currentRoomMembers.any { member ->
        member.isActive && member.participant.id == BuiltInParticipantIds.SYNAPSE_LOCAL_AI
    }
    var showAddMenu by rememberSaveable(state.currentRoom?.id?.raw) { mutableStateOf(false) }
    val sendAndHideKeyboard = {
        keyboardController?.hide()
        onSend()
    }
    val canSend = localAiComposerCanSend(
        composerText = state.composerText,
        attachmentCount = state.pendingAttachments.size,
        isSending = state.isSending,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.voiceMode.status != VoiceModeStatus.OFF) {
            VoiceModeStatusBar(
                voiceMode = state.voiceMode,
                onVoiceModeToggle = onVoiceModeToggle,
            )
        }
        if (state.pendingAttachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = state.pendingAttachments,
                onRemoveAttachment = onRemoveAttachment,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFF111411),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { showAddMenu = true }) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "More message options",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = { showAddMenu = false },
                    ) {
                        localAiComposerMenuActions(synapseIsActive).forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label(state.voiceMode)) },
                                leadingIcon = { Icon(action.icon(state.voiceMode), contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    when (action) {
                                        LocalAiComposerMenuAction.ATTACH -> onAttach()
                                        LocalAiComposerMenuAction.MENTION_SYNAPSE -> onMentionSynapse()
                                        LocalAiComposerMenuAction.TOGGLE_HANDS_FREE_VOICE -> onVoiceModeToggle()
                                    }
                                },
                            )
                        }
                    }
                }
                TextField(
                    value = state.composerText,
                    onValueChange = onComposerChanged,
                    placeholder = { Text("Message room") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (canSend && !state.isSending) sendAndHideKeyboard()
                        },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                IconButton(
                    onClick = onStartSpeech,
                    enabled = !state.voiceMode.isActive,
                ) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = "Voice input",
                        tint = if (state.voiceMode.isActive) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                IconButton(
                    onClick = if (state.isSending) onStop else sendAndHideKeyboard,
                    enabled = canSend,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                ) {
                    Icon(
                        imageVector = if (state.isSending) {
                            Icons.Rounded.Stop
                        } else {
                            Icons.AutoMirrored.Rounded.Send
                        },
                        contentDescription = if (state.isSending) "Stop" else "Send",
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.onPrimary
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
private fun VoiceModeStatusBar(
    voiceMode: VoiceModeUiState,
    onVoiceModeToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (voiceMode.isActive) Icons.Rounded.Mic else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (voiceMode.status == VoiceModeStatus.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = voiceMode.toDisplayLabel(),
                modifier = Modifier.weight(1f),
                color = if (voiceMode.status == VoiceModeStatus.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onVoiceModeToggle) {
                Text(voiceMode.toActionLabel())
            }
        }
    }
}

@Composable
private fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEachIndexed { index, attachment ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = attachment.displayName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = attachment.byteCount?.let(::formatByteCount).orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = { onRemoveAttachment(index) }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

internal enum class LocalAiTopBarAction {
    START_LOCAL_AI,
    RUNTIME_DIAGNOSTICS,
    LIBRARY,
    MEMORY,
    SETTINGS,
}

internal fun localAiTopBarActions(hasRuntimeIssue: Boolean): List<LocalAiTopBarAction> = buildList {
    add(LocalAiTopBarAction.START_LOCAL_AI)
    if (hasRuntimeIssue) add(LocalAiTopBarAction.RUNTIME_DIAGNOSTICS)
    add(LocalAiTopBarAction.LIBRARY)
    add(LocalAiTopBarAction.MEMORY)
    add(LocalAiTopBarAction.SETTINGS)
}

private fun LocalAiTopBarAction.label(): String =
    when (this) {
        LocalAiTopBarAction.START_LOCAL_AI -> "Start local AI"
        LocalAiTopBarAction.RUNTIME_DIAGNOSTICS -> "Runtime diagnostics"
        LocalAiTopBarAction.LIBRARY -> "Library"
        LocalAiTopBarAction.MEMORY -> "Memory"
        LocalAiTopBarAction.SETTINGS -> "Settings"
    }

private fun LocalAiTopBarAction.icon(): ImageVector =
    when (this) {
        LocalAiTopBarAction.START_LOCAL_AI -> Icons.Rounded.PlayArrow
        LocalAiTopBarAction.RUNTIME_DIAGNOSTICS -> Icons.Rounded.ErrorOutline
        LocalAiTopBarAction.LIBRARY -> Icons.Rounded.FolderOpen
        LocalAiTopBarAction.MEMORY -> Icons.Rounded.Memory
        LocalAiTopBarAction.SETTINGS -> Icons.Rounded.Settings
    }

internal enum class LocalAiComposerMenuAction {
    ATTACH,
    MENTION_SYNAPSE,
    TOGGLE_HANDS_FREE_VOICE,
}

internal fun localAiComposerMenuActions(synapseIsActive: Boolean): List<LocalAiComposerMenuAction> = buildList {
    add(LocalAiComposerMenuAction.ATTACH)
    if (synapseIsActive) add(LocalAiComposerMenuAction.MENTION_SYNAPSE)
    add(LocalAiComposerMenuAction.TOGGLE_HANDS_FREE_VOICE)
}

private fun LocalAiComposerMenuAction.label(voiceMode: VoiceModeUiState): String =
    when (this) {
        LocalAiComposerMenuAction.ATTACH -> "Attach file"
        LocalAiComposerMenuAction.MENTION_SYNAPSE -> "Mention Synapse"
        LocalAiComposerMenuAction.TOGGLE_HANDS_FREE_VOICE -> when (voiceMode.status) {
            VoiceModeStatus.OFF -> "Start hands-free voice"
            VoiceModeStatus.ERROR -> "Retry hands-free voice"
            VoiceModeStatus.LISTENING,
            VoiceModeStatus.PROCESSING,
            VoiceModeStatus.SPEAKING,
            -> "Stop hands-free voice"
        }
    }

private fun LocalAiComposerMenuAction.icon(voiceMode: VoiceModeUiState): ImageVector =
    when (this) {
        LocalAiComposerMenuAction.ATTACH -> Icons.Rounded.AttachFile
        LocalAiComposerMenuAction.MENTION_SYNAPSE -> Icons.Rounded.AlternateEmail
        LocalAiComposerMenuAction.TOGGLE_HANDS_FREE_VOICE ->
            if (voiceMode.isActive) Icons.Rounded.Stop else Icons.Rounded.Mic
    }

internal fun localAiComposerCanSend(
    composerText: String,
    attachmentCount: Int,
    isSending: Boolean,
): Boolean = isSending || composerText.isNotBlank() || attachmentCount > 0

internal fun RuntimeStatus.toActionableRuntimeLabel(): String? =
    when (this) {
        is RuntimeStatus.Ready -> null
        is RuntimeStatus.Starting ->
            when (receipt.status) {
                RuntimeStartStatus.EMBEDDED_MODEL_MISSING,
                RuntimeStartStatus.EMBEDDED_RUNTIME_UNAVAILABLE,
                RuntimeStartStatus.TERMUX_UNAVAILABLE,
                RuntimeStartStatus.TERMUX_PERMISSION_MISSING,
                RuntimeStartStatus.FAILED,
                -> receipt.message

                RuntimeStartStatus.SENT_TO_TERMUX,
                RuntimeStartStatus.EMBEDDED_MODEL_READY,
                -> null
            }

        RuntimeStatus.Unknown -> null
        is RuntimeStatus.Unreachable -> reason
    }

internal fun VoiceModeUiState.toDisplayLabel(): String =
    when (status) {
        VoiceModeStatus.OFF -> "Hands-free voice off"
        VoiceModeStatus.LISTENING -> "Listening for you"
        VoiceModeStatus.PROCESSING -> "Synapse is thinking"
        VoiceModeStatus.SPEAKING -> "Synapse is speaking"
        VoiceModeStatus.ERROR -> errorMessage ?: "Hands-free voice paused"
    }

internal fun VoiceModeUiState.toActionLabel(): String =
    when (status) {
        VoiceModeStatus.OFF -> "Start"
        VoiceModeStatus.ERROR -> "Retry"
        VoiceModeStatus.LISTENING,
        VoiceModeStatus.PROCESSING,
        VoiceModeStatus.SPEAKING,
        -> "Stop"
    }
