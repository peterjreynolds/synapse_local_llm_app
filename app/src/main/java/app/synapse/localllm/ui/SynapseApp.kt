package app.synapse.localllm.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.synapse.localllm.R
import app.synapse.localllm.domain.chat.AttachmentKind
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ChatThreadRecord
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.chat.PendingAttachment
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.RetrievedMemoryRef
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.storage.StorageHealthState
import java.io.IOException
import java.util.Locale

@Composable
fun SynapseApp(viewModel: SynapseViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val speak = rememberTextToSpeechSpeaker()
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val matches = activityResult.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                .orEmpty()
            matches.firstOrNull()?.let(viewModel::appendComposerText)
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            speechLauncher.launch(createSpeechRecognitionIntent())
        } else {
            viewModel.publishNotice("Microphone permission denied.")
        }
    }
    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val attachment = buildPendingAttachment(context, uri)
            if (attachment != null) {
                viewModel.addPendingAttachment(attachment)
            } else {
                viewModel.publishNotice("Attachment could not be opened.")
            }
        }
    }

    SynapseScreen(
        state = uiState,
        onComposerChanged = viewModel::updateComposer,
        onSend = viewModel::sendComposerMessage,
        onStop = viewModel::cancelActiveSend,
        onAttach = { attachmentLauncher.launch(attachmentMimeTypes) },
        onRemoveAttachment = viewModel::removePendingAttachment,
        onStartSpeech = {
            startSpeechRecognition(
                context = context,
                speechLauncher = speechLauncher,
                audioPermissionLauncher = audioPermissionLauncher,
            )
        },
        onSpeak = speak,
        onPanelSelected = viewModel::selectPanel,
        onThreadDrawerOpen = viewModel::openThreadDrawer,
        onThreadDrawerClose = viewModel::closeThreadDrawer,
        onCreateThread = viewModel::createNewThread,
        onThreadSelected = viewModel::selectThread,
        onRuntimeCheck = viewModel::checkRuntimeStatus,
        onRuntimeStart = viewModel::startRuntime,
        onMemoryQueryChanged = viewModel::updateMemorySearchQuery,
        onMemorySearch = viewModel::searchMemory,
        onTombstoneMemory = { memory -> viewModel.tombstoneMemory(memory.memoryObjectId) },
        onSettingsDraftChanged = viewModel::updateSettingsDraft,
        onSaveSettings = viewModel::saveSettingsDraft,
        onMemoryWritesChanged = viewModel::updateMemoryWritesEnabled,
        onSpeechPlaybackChanged = viewModel::updateSpeechPlaybackEnabled,
        onInspectStorage = viewModel::inspectStorageHealth,
        onDismissNotice = viewModel::clearNotice,
    )
}

@Composable
private fun SynapseScreen(
    state: SynapseUiState,
    onComposerChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onStartSpeech: () -> Unit,
    onSpeak: (String) -> Unit,
    onPanelSelected: (SynapsePanel) -> Unit,
    onThreadDrawerOpen: () -> Unit,
    onThreadDrawerClose: () -> Unit,
    onCreateThread: () -> Unit,
    onThreadSelected: (ChatThreadRecord) -> Unit,
    onRuntimeCheck: () -> Unit,
    onRuntimeStart: () -> Unit,
    onMemoryQueryChanged: (String) -> Unit,
    onMemorySearch: () -> Unit,
    onTombstoneMemory: (RetrievedMemoryRef) -> Unit,
    onSettingsDraftChanged: (RuntimeSettingsDraft) -> Unit,
    onSaveSettings: () -> Unit,
    onMemoryWritesChanged: (Boolean) -> Unit,
    onSpeechPlaybackChanged: (Boolean) -> Unit,
    onInspectStorage: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF050505),
                        Color(0xFF050505),
                        Color(0xFF06120B),
                    ),
                ),
            ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                SynapseTopBar(
                    state = state,
                    onPanelSelected = onPanelSelected,
                    onThreadDrawerOpen = onThreadDrawerOpen,
                    onRuntimeCheck = onRuntimeCheck,
                    onRuntimeStart = onRuntimeStart,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                NoticeBanner(
                    notice = state.lastNotice,
                    storageHealthSnapshot = state.storageHealthSnapshot,
                    onDismissNotice = onDismissNotice,
                )
                when (state.activePanel) {
                    SynapsePanel.CHAT ->
                        ChatPanel(
                            modifier = Modifier.imePadding(),
                            messages = state.messages,
                            speechPlaybackEnabled = state.settings.speechPlaybackEnabled,
                            onSpeak = onSpeak,
                        )

                    SynapsePanel.MEMORY ->
                        MemoryPanel(
                            state = state,
                            onMemoryQueryChanged = onMemoryQueryChanged,
                            onMemorySearch = onMemorySearch,
                            onTombstoneMemory = onTombstoneMemory,
                            onInspectStorage = onInspectStorage,
                        )

                    SynapsePanel.SETTINGS ->
                        SettingsPanel(
                            state = state,
                            onSettingsDraftChanged = onSettingsDraftChanged,
                            onSaveSettings = onSaveSettings,
                            onMemoryWritesChanged = onMemoryWritesChanged,
                            onSpeechPlaybackChanged = onSpeechPlaybackChanged,
                        )
                }
            }
        }
        if (state.activePanel == SynapsePanel.CHAT) {
            ComposerBar(
                state = state,
                onComposerChanged = onComposerChanged,
                onSend = onSend,
                onStop = onStop,
                onAttach = onAttach,
                onRemoveAttachment = onRemoveAttachment,
                onStartSpeech = onStartSpeech,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (state.isThreadDrawerOpen) {
            ThreadDrawerOverlay(
                state = state,
                onClose = onThreadDrawerClose,
                onCreateThread = onCreateThread,
                onThreadSelected = onThreadSelected,
            )
        }
    }
}

@Composable
private fun SynapseTopBar(
    state: SynapseUiState,
    onPanelSelected: (SynapsePanel) -> Unit,
    onThreadDrawerOpen: () -> Unit,
    onRuntimeCheck: () -> Unit,
    onRuntimeStart: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onThreadDrawerOpen,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.Menu,
                contentDescription = "Recent chats",
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
        Text(
            text = "Synapse",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = state.runtimeStatus.toRuntimeLabel(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onRuntimeStart,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = "Start llama-server",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(
            onClick = onRuntimeCheck,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = "Check runtime",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(
            onClick = { onPanelSelected(SynapsePanel.MEMORY) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.Memory,
                contentDescription = "Memory",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(
            onClick = { onPanelSelected(SynapsePanel.SETTINGS) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun ThreadDrawerOverlay(
    state: SynapseUiState,
    onClose: () -> Unit,
    onCreateThread: () -> Unit,
    onThreadSelected: (ChatThreadRecord) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .clickable(onClick = onClose),
        )
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.86f),
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
                        text = "Recent chats",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close recent chats",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                Button(
                    onClick = onCreateThread,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New chat")
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.threads, key = { thread -> thread.id.raw }) { thread ->
                        ThreadDrawerRow(
                            thread = thread,
                            selected = thread.id == state.currentThread?.id,
                            onThreadSelected = onThreadSelected,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadDrawerRow(
    thread: ChatThreadRecord,
    selected: Boolean,
    onThreadSelected: (ChatThreadRecord) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onThreadSelected(thread) },
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = thread.title,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoticeBanner(
    notice: String?,
    storageHealthSnapshot: StorageHealthSnapshot?,
    onDismissNotice: () -> Unit,
) {
    val storageNotice = storageHealthSnapshot
        ?.takeIf { snapshot -> snapshot.state != StorageHealthState.HEALTHY }
        ?.reason
    val visibleNotice = notice ?: storageNotice ?: return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = visibleNotice,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
            if (notice != null) {
                TextButton(onClick = onDismissNotice) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun ChatPanel(
    modifier: Modifier = Modifier,
    messages: List<ChatMessageRecord>,
    speechPlaybackEnabled: Boolean,
    onSpeak: (String) -> Unit,
) {
    if (messages.isEmpty()) {
        EmptyChat()
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.body) {
        listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 18.dp,
            end = 16.dp,
            bottom = 112.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { message -> message.id.raw }) { message ->
            MessageBubble(
                message = message,
                speechPlaybackEnabled = speechPlaybackEnabled,
                onSpeak = onSpeak,
            )
        }
    }
}

@Composable
private fun EmptyChat() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.synapse_guild_mark),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(54.dp),
        )
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = "Let's jump in, Peter",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessageRecord,
    speechPlaybackEnabled: Boolean,
    onSpeak: (String) -> Unit,
) {
    val isUser = message.role == ConversationRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) Color(0xFF10351A) else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (isUser) 22.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 22.dp,
            ),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.84f else 0.92f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.body.ifBlank {
                        if (message.deliveryState == MessageDeliveryState.STREAMING) "..." else ""
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!isUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (message.deliveryState == MessageDeliveryState.FAILED) {
                            Text(
                                text = message.failureReason ?: "Failed",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (speechPlaybackEnabled && message.body.isNotBlank()) {
                            IconButton(onClick = { onSpeak(message.body) }) {
                                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Speak")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerBar(
    state: SynapseUiState,
    onComposerChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onStartSpeech: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (state.pendingAttachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = state.pendingAttachments,
                onRemoveAttachment = onRemoveAttachment,
            )
            Spacer(modifier = Modifier.height(8.dp))
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
                IconButton(onClick = onAttach) {
                    Icon(Icons.Rounded.Add, contentDescription = "Attach")
                }
                TextField(
                    value = state.composerText,
                    onValueChange = onComposerChanged,
                    placeholder = { Text("Ask Synapse") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                IconButton(onClick = onStartSpeech) {
                    Icon(Icons.Rounded.Mic, contentDescription = "Voice input")
                }
                IconButton(
                    onClick = if (state.isSending) onStop else onSend,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        imageVector = if (state.isSending) {
                            Icons.Rounded.Stop
                        } else {
                            Icons.AutoMirrored.Rounded.Send
                        },
                        contentDescription = if (state.isSending) "Stop" else "Send",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
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
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
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

@Composable
private fun MemoryPanel(
    state: SynapseUiState,
    onMemoryQueryChanged: (String) -> Unit,
    onMemorySearch: () -> Unit,
    onTombstoneMemory: (RetrievedMemoryRef) -> Unit,
    onInspectStorage: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Memory",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            StorageHealthCard(
                snapshot = state.storageHealthSnapshot,
                onInspectStorage = onInspectStorage,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.memorySearchQuery,
                    onValueChange = onMemoryQueryChanged,
                    label = { Text("Search") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onMemorySearch) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search memory")
                }
            }
        }
        items(state.memorySearchResults, key = { memory -> memory.memoryVersionId.raw }) { memory ->
            MemoryResultRow(memory = memory, onTombstoneMemory = onTombstoneMemory)
        }
    }
}

@Composable
private fun StorageHealthCard(
    snapshot: StorageHealthSnapshot?,
    onInspectStorage: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Text(
                text = snapshot?.reason ?: "Not checked yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (snapshot != null) {
                Text(
                    text = "Free ${formatByteCount(snapshot.availableBytes)} | " +
                        "DB ${formatByteCount(snapshot.memoryDatabaseBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(onClick = onInspectStorage) {
                Text("Check")
            }
        }
    }
}

@Composable
private fun MemoryResultRow(
    memory: RetrievedMemoryRef,
    onTombstoneMemory: (RetrievedMemoryRef) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${memory.kind.toDisplayLabel()} | ${(memory.confidence * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = { onTombstoneMemory(memory) }) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete memory")
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    state: SynapseUiState,
    onSettingsDraftChanged: (RuntimeSettingsDraft) -> Unit,
    onSaveSettings: () -> Unit,
    onMemoryWritesChanged: (Boolean) -> Unit,
    onSpeechPlaybackChanged: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            OutlinedTextField(
                value = state.settingsDraft.baseUrl,
                onValueChange = { value ->
                    onSettingsDraftChanged(state.settingsDraft.copy(baseUrl = value))
                },
                label = { Text("Server") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = state.settingsDraft.modelName,
                onValueChange = { value ->
                    onSettingsDraftChanged(state.settingsDraft.copy(modelName = value))
                },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.settingsDraft.temperature,
                    onValueChange = { value ->
                        onSettingsDraftChanged(state.settingsDraft.copy(temperature = value))
                    },
                    label = { Text("Temp") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.settingsDraft.maxTokens,
                    onValueChange = { value ->
                        onSettingsDraftChanged(state.settingsDraft.copy(maxTokens = value))
                    },
                    label = { Text("Tokens") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
        }
        item {
            OutlinedTextField(
                value = state.settingsDraft.systemPrompt,
                onValueChange = { value ->
                    onSettingsDraftChanged(state.settingsDraft.copy(systemPrompt = value))
                },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 8,
            )
        }
        item {
            SettingsSwitchRow(
                label = "Memory writes",
                checked = state.settings.memoryWritesEnabled,
                onCheckedChange = onMemoryWritesChanged,
            )
        }
        item {
            SettingsSwitchRow(
                label = "Speaker playback",
                checked = state.settings.speechPlaybackEnabled,
                onCheckedChange = onSpeechPlaybackChanged,
            )
        }
        item {
            Button(onClick = onSaveSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun rememberTextToSpeechSpeaker(): (String) -> Unit {
    val context = LocalContext.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }
    var isReady by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val createdEngine = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS
        }
        createdEngine.language = Locale.getDefault()
        engine = createdEngine
        onDispose {
            createdEngine.stop()
            createdEngine.shutdown()
            engine = null
        }
    }

    return { text ->
        if (isReady && text.isNotBlank()) {
            engine?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "synapse-${text.hashCode()}",
            )
        }
    }
}

private fun startSpeechRecognition(
    context: Context,
    speechLauncher: ActivityResultLauncher<Intent>,
    audioPermissionLauncher: ActivityResultLauncher<String>,
) {
    val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
    if (permission == PackageManager.PERMISSION_GRANTED) {
        speechLauncher.launch(createSpeechRecognitionIntent())
    } else {
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

private fun createSpeechRecognitionIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
    }

private fun buildPendingAttachment(context: Context, uri: Uri): PendingAttachment? {
    val resolver = context.contentResolver
    val metadata = queryAttachmentMetadata(context, uri)
    val mimeType = resolver.getType(uri)
    val kind = inferAttachmentKind(mimeType, metadata.displayName)
    val extractedText = if (kind == AttachmentKind.TEXT) {
        readTextAttachment(context, uri)
    } else {
        null
    }

    return PendingAttachment(
        displayName = metadata.displayName,
        mimeType = mimeType,
        uri = uri.toString(),
        byteCount = metadata.byteCount,
        kind = kind,
        extractedText = extractedText,
    )
}

private fun queryAttachmentMetadata(context: Context, uri: Uri): AttachmentMetadata {
    val resolver = context.contentResolver
    resolver.takePersistableUriPermissionIfPossible(uri)
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val displayName = cursor.readStringColumn(OpenableColumns.DISPLAY_NAME)
                ?: uri.lastPathSegment
                ?: "attachment"
            val byteCount = cursor.readLongColumn(OpenableColumns.SIZE)
            return AttachmentMetadata(displayName = displayName, byteCount = byteCount)
        }
    }
    return AttachmentMetadata(displayName = uri.lastPathSegment ?: "attachment", byteCount = null)
}

private fun android.content.ContentResolver.takePersistableUriPermissionIfPossible(uri: Uri) {
    try {
        takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        return
    }
}

private fun android.database.Cursor.readStringColumn(columnName: String): String? {
    val columnIndex = getColumnIndex(columnName)
    return if (columnIndex >= 0) getString(columnIndex) else null
}

private fun android.database.Cursor.readLongColumn(columnName: String): Long? {
    val columnIndex = getColumnIndex(columnName)
    return if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else null
}

private fun readTextAttachment(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            reader.readText().take(MAX_TEXT_ATTACHMENT_READ_CHARS)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private fun inferAttachmentKind(mimeType: String?, displayName: String): AttachmentKind =
    when {
        mimeType?.startsWith("text/") == true -> AttachmentKind.TEXT
        mimeType == "application/json" -> AttachmentKind.TEXT
        displayName.endsWith(".md", ignoreCase = true) -> AttachmentKind.TEXT
        displayName.endsWith(".txt", ignoreCase = true) -> AttachmentKind.TEXT
        mimeType?.startsWith("image/") == true -> AttachmentKind.IMAGE
        else -> AttachmentKind.FILE
    }

private fun RuntimeStatus.toRuntimeLabel(): String =
    when (this) {
        is RuntimeStatus.Ready -> "Ready"
        is RuntimeStatus.Starting -> "Starting"
        RuntimeStatus.Unknown -> "Unknown"
        is RuntimeStatus.Unreachable -> "Offline"
    }

private fun MemoryKind.toDisplayLabel(): String =
    name.lowercase().replaceFirstChar { firstCharacter ->
        if (firstCharacter.isLowerCase()) {
            firstCharacter.titlecase()
        } else {
            firstCharacter.toString()
        }
    }

private fun formatByteCount(byteCount: Long): String =
    when {
        byteCount >= GIB -> "${byteCount / GIB} GB"
        byteCount >= MIB -> "${byteCount / MIB} MB"
        byteCount >= KIB -> "${byteCount / KIB} KB"
        else -> "$byteCount B"
    }

private data class AttachmentMetadata(
    val displayName: String,
    val byteCount: Long?,
)

private val attachmentMimeTypes =
    arrayOf(
        "text/*",
        "image/*",
        "application/json",
        "application/pdf",
        "application/octet-stream",
    )

private const val MAX_TEXT_ATTACHMENT_READ_CHARS = 64_000
private const val KIB = 1024L
private const val MIB = KIB * 1024L
private const val GIB = MIB * 1024L
