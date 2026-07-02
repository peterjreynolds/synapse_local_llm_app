package app.synapse.localllm.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import app.synapse.localllm.domain.library.LibraryArtifactRecord
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryReviewFilter
import app.synapse.localllm.domain.memory.MemoryStatus
import app.synapse.localllm.domain.memory.RetrievedMemoryRef
import app.synapse.localllm.domain.runtime.ImportEmbeddedModelCommand
import app.synapse.localllm.domain.runtime.ModelCatalogEntry
import app.synapse.localllm.domain.runtime.ModelPromptProfile
import app.synapse.localllm.domain.runtime.RuntimeStartStatus
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.runtime.formatModelDownloadByteCount
import app.synapse.localllm.domain.runtime.formatModelDownloadProgressText
import app.synapse.localllm.domain.settings.InferenceRuntimeBackend
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.storage.StorageHealthState
import java.io.IOException
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SynapseApp(viewModel: SynapseViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val messageSpeechController = rememberMessageSpeechPlaybackController()
    val voiceModeSpeechController = rememberVoiceModeSpeechController(
        onSpeechFinished = viewModel::onVoiceModeSpeechPlaybackFinished,
        onSpeechError = viewModel::onVoiceModeSpeechPlaybackError,
    )
    var speechRecognitionTarget by remember {
        mutableStateOf(SpeechRecognitionTarget.COMPOSER)
    }
    var pendingCatalogDownload by remember {
        mutableStateOf<ModelCatalogEntry?>(null)
    }
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { activityResult ->
        val recognitionTarget = speechRecognitionTarget
        speechRecognitionTarget = SpeechRecognitionTarget.COMPOSER
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val matches = activityResult.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                .orEmpty()
            val transcript = matches.firstOrNull().orEmpty()
            when (recognitionTarget) {
                SpeechRecognitionTarget.COMPOSER ->
                    transcript.takeIf { text -> text.isNotBlank() }
                        ?.let(viewModel::appendComposerText)

                SpeechRecognitionTarget.VOICE_MODE ->
                    viewModel.onVoiceModeSpeechResult(transcript)
            }
        } else if (recognitionTarget == SpeechRecognitionTarget.VOICE_MODE) {
            viewModel.onVoiceModeSpeechError("No speech was recognized.")
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            speechLauncher.launch(createSpeechRecognitionIntent())
        } else if (speechRecognitionTarget == SpeechRecognitionTarget.VOICE_MODE) {
            speechRecognitionTarget = SpeechRecognitionTarget.COMPOSER
            viewModel.onVoiceModeSpeechError("Microphone permission denied.")
        } else {
            speechRecognitionTarget = SpeechRecognitionTarget.COMPOSER
            viewModel.publishNotice("Microphone permission denied.")
        }
    }
    val modelDownloadNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val entry = pendingCatalogDownload
        pendingCatalogDownload = null
        if (!granted) {
            viewModel.publishNotice("Model download will continue, but Android may hide its notification.")
        }
        entry?.let(viewModel::downloadCatalogModel)
    }
    val smsAutoReplyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = requiredSmsAutoReplyPermissions.all { permission ->
            permissions[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            viewModel.updateSmsAutoReplyEnabled(true)
        } else {
            viewModel.publishNotice("SMS auto-reply needs Receive SMS and Send SMS permissions.")
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
    val modelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val metadata = queryAttachmentMetadata(context, uri)
            viewModel.importEmbeddedModel(
                ImportEmbeddedModelCommand(
                    sourceUri = uri.toString(),
                    displayName = metadata.displayName,
                    byteCount = metadata.byteCount,
                ),
            )
        }
    }

    LaunchedEffect(uiState.voiceMode.recognitionRequestId) {
        if (
            uiState.voiceMode.status == VoiceModeStatus.LISTENING &&
            uiState.voiceMode.recognitionRequestId > 0
        ) {
            speechRecognitionTarget = SpeechRecognitionTarget.VOICE_MODE
            startSpeechRecognition(
                context = context,
                speechLauncher = speechLauncher,
                audioPermissionLauncher = audioPermissionLauncher,
            )
        }
    }
    LaunchedEffect(uiState.voiceMode.speechRequestId) {
        if (
            uiState.voiceMode.status == VoiceModeStatus.SPEAKING &&
            uiState.voiceMode.speechRequestId > 0 &&
            uiState.voiceMode.speechText.isNotBlank()
        ) {
            voiceModeSpeechController.speak(uiState.voiceMode.speechText)
        }
    }
    LaunchedEffect(uiState.voiceMode.status) {
        if (uiState.voiceMode.status != VoiceModeStatus.SPEAKING) {
            voiceModeSpeechController.stop()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.checkForAppUpdate(automatic = true)
    }

    SynapseScreen(
        state = uiState,
        onComposerChanged = viewModel::updateComposer,
        onSend = viewModel::sendComposerMessage,
        onStop = viewModel::cancelActiveSend,
        onAttach = { attachmentLauncher.launch(attachmentMimeTypes) },
        onRemoveAttachment = viewModel::removePendingAttachment,
        onStartSpeech = {
            speechRecognitionTarget = SpeechRecognitionTarget.COMPOSER
            startSpeechRecognition(
                context = context,
                speechLauncher = speechLauncher,
                audioPermissionLauncher = audioPermissionLauncher,
            )
        },
        onVoiceModeToggle = viewModel::toggleVoiceMode,
        messageSpeechController = messageSpeechController,
        onPanelSelected = viewModel::selectPanel,
        onThreadDrawerOpen = viewModel::openThreadDrawer,
        onThreadDrawerClose = viewModel::closeThreadDrawer,
        onCreateThread = viewModel::createNewThread,
        onThreadSelected = viewModel::selectThread,
        onThreadPinnedChanged = viewModel::setThreadPinned,
        onThreadRenamed = viewModel::renameThread,
        onThreadArchived = viewModel::archiveThread,
        onThreadDeleted = viewModel::deleteThread,
        onRuntimeCheck = viewModel::checkRuntimeStatus,
        onRuntimeStart = viewModel::startRuntime,
        onCheckAppUpdate = { viewModel.checkForAppUpdate(automatic = false) },
        onDownloadAppUpdate = {
            viewModel.downloadAvailableAppUpdate { updateUri ->
                if (!launchAppUpdateInstaller(context, updateUri)) {
                    viewModel.publishNotice("Android could not open the update installer.")
                }
            }
        },
        onInstallAppUpdate = { updateUri ->
            if (!launchAppUpdateInstaller(context, updateUri)) {
                viewModel.publishNotice("Android could not open the update installer.")
            }
        },
        onDismissAppUpdate = viewModel::dismissAppUpdate,
        onImportEmbeddedModel = { modelImportLauncher.launch(modelMimeTypes) },
        onDownloadCatalogModel = { entry ->
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingCatalogDownload = entry
                modelDownloadNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.downloadCatalogModel(entry)
            }
        },
        onLibraryTitleChanged = viewModel::updateLibraryDraftTitle,
        onLibraryMarkdownChanged = viewModel::updateLibraryDraftMarkdown,
        onCreateLibraryMarkdownArtifact = viewModel::createLibraryMarkdownArtifact,
        onRefreshLibraryArtifacts = viewModel::loadLibraryArtifacts,
        onExportLibraryDraftAsPdf = {
            viewModel.exportLibraryDraftAsPdf { pdfUri ->
                sharePdfExport(context, pdfUri)
            }
        },
        onExportLibraryArtifactAsPdf = { artifact ->
            viewModel.exportLibraryArtifactAsPdf(artifact) { pdfUri ->
                sharePdfExport(context, pdfUri)
            }
        },
        onMemoryQueryChanged = viewModel::updateMemorySearchQuery,
        onMemoryFilterChanged = viewModel::updateMemoryReviewFilter,
        onMemorySearch = viewModel::searchMemory,
        onActivateMemory = { memory -> viewModel.activateMemory(memory.memoryObjectId) },
        onTombstoneMemory = { memory -> viewModel.tombstoneMemory(memory.memoryObjectId) },
        onSettingsDraftChanged = viewModel::updateSettingsDraft,
        onSaveSettings = viewModel::saveSettingsDraft,
        onMemoryWritesChanged = viewModel::updateMemoryWritesEnabled,
        onSpeechPlaybackChanged = viewModel::updateSpeechPlaybackEnabled,
        onSmsAutoReplyChanged = { enabled ->
            if (!enabled) {
                viewModel.updateSmsAutoReplyEnabled(false)
            } else if (hasSmsAutoReplyPermissions(context)) {
                viewModel.updateSmsAutoReplyEnabled(true)
            } else {
                smsAutoReplyPermissionLauncher.launch(requiredSmsAutoReplyPermissions)
            }
        },
        onInspectStorage = viewModel::inspectStorageHealth,
        onExportDebugArchive = {
            viewModel.exportDebugArchive { archiveUri ->
                shareDebugArchive(context, archiveUri)
            }
        },
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
    onVoiceModeToggle: () -> Unit,
    messageSpeechController: MessageSpeechPlaybackController,
    onPanelSelected: (SynapsePanel) -> Unit,
    onThreadDrawerOpen: () -> Unit,
    onThreadDrawerClose: () -> Unit,
    onCreateThread: () -> Unit,
    onThreadSelected: (ChatThreadRecord) -> Unit,
    onThreadPinnedChanged: (ChatThreadRecord, Boolean) -> Unit,
    onThreadRenamed: (ChatThreadRecord, String) -> Unit,
    onThreadArchived: (ChatThreadRecord) -> Unit,
    onThreadDeleted: (ChatThreadRecord) -> Unit,
    onRuntimeCheck: () -> Unit,
    onRuntimeStart: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: (Uri) -> Unit,
    onDismissAppUpdate: () -> Unit,
    onImportEmbeddedModel: () -> Unit,
    onDownloadCatalogModel: (ModelCatalogEntry) -> Unit,
    onLibraryTitleChanged: (String) -> Unit,
    onLibraryMarkdownChanged: (String) -> Unit,
    onCreateLibraryMarkdownArtifact: () -> Unit,
    onRefreshLibraryArtifacts: () -> Unit,
    onExportLibraryDraftAsPdf: () -> Unit,
    onExportLibraryArtifactAsPdf: (LibraryArtifactRecord) -> Unit,
    onMemoryQueryChanged: (String) -> Unit,
    onMemoryFilterChanged: (MemoryReviewFilter) -> Unit,
    onMemorySearch: () -> Unit,
    onActivateMemory: (RetrievedMemoryRef) -> Unit,
    onTombstoneMemory: (RetrievedMemoryRef) -> Unit,
    onSettingsDraftChanged: (RuntimeSettingsDraft) -> Unit,
    onSaveSettings: () -> Unit,
    onMemoryWritesChanged: (Boolean) -> Unit,
    onSpeechPlaybackChanged: (Boolean) -> Unit,
    onSmsAutoReplyChanged: (Boolean) -> Unit,
    onInspectStorage: () -> Unit,
    onExportDebugArchive: () -> Unit,
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
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                SynapseTopBar(
                    state = state,
                    onPanelSelected = onPanelSelected,
                    onThreadDrawerOpen = onThreadDrawerOpen,
                    onRuntimeCheck = onRuntimeCheck,
                    onRuntimeStart = onRuntimeStart,
                )
            },
            bottomBar = {
                if (state.activePanel == SynapsePanel.CHAT) {
                    ComposerBar(
                        state = state,
                        onComposerChanged = onComposerChanged,
                        onSend = onSend,
                        onStop = onStop,
                        onAttach = onAttach,
                        onRemoveAttachment = onRemoveAttachment,
                        onStartSpeech = onStartSpeech,
                        onVoiceModeToggle = onVoiceModeToggle,
                        modifier = Modifier.imePadding(),
                    )
                }
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
                AppUpdateBanner(
                    appUpdate = state.appUpdate,
                    onCheckAppUpdate = onCheckAppUpdate,
                    onDownloadAppUpdate = onDownloadAppUpdate,
                    onInstallAppUpdate = onInstallAppUpdate,
                    onDismissAppUpdate = onDismissAppUpdate,
                )
                when (state.activePanel) {
                    SynapsePanel.CHAT ->
                        ChatWorkspace(
                            modifier = Modifier
                                .fillMaxSize(),
                            state = state,
                            messageSpeechController = messageSpeechController,
                        )

                    SynapsePanel.LIBRARY ->
                        LibraryPanel(
                            state = state,
                            onLibraryTitleChanged = onLibraryTitleChanged,
                            onLibraryMarkdownChanged = onLibraryMarkdownChanged,
                            onCreateLibraryMarkdownArtifact = onCreateLibraryMarkdownArtifact,
                            onRefreshLibraryArtifacts = onRefreshLibraryArtifacts,
                            onExportLibraryDraftAsPdf = onExportLibraryDraftAsPdf,
                            onExportLibraryArtifactAsPdf = onExportLibraryArtifactAsPdf,
                        )

                    SynapsePanel.MEMORY ->
                        MemoryPanel(
                            state = state,
                            onMemoryQueryChanged = onMemoryQueryChanged,
                            onMemoryFilterChanged = onMemoryFilterChanged,
                            onMemorySearch = onMemorySearch,
                            onActivateMemory = onActivateMemory,
                            onTombstoneMemory = onTombstoneMemory,
                            onInspectStorage = onInspectStorage,
                        )

                    SynapsePanel.SETTINGS ->
                        SettingsPanel(
                            state = state,
                            onSettingsDraftChanged = onSettingsDraftChanged,
                            onSaveSettings = onSaveSettings,
                            onImportEmbeddedModel = onImportEmbeddedModel,
                            onDownloadCatalogModel = onDownloadCatalogModel,
                            onMemoryWritesChanged = onMemoryWritesChanged,
                            onSpeechPlaybackChanged = onSpeechPlaybackChanged,
                            onSmsAutoReplyChanged = onSmsAutoReplyChanged,
                            onExportDebugArchive = onExportDebugArchive,
                        )
                }
            }
        }
        if (state.isThreadDrawerOpen) {
            ThreadDrawerOverlay(
                state = state,
                onClose = onThreadDrawerClose,
                onCreateThread = onCreateThread,
                onThreadSelected = onThreadSelected,
                onThreadPinnedChanged = onThreadPinnedChanged,
                onThreadRenamed = onThreadRenamed,
                onThreadArchived = onThreadArchived,
                onThreadDeleted = onThreadDeleted,
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
            .statusBarsPadding()
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
        val actionableRuntimeLabel = state.runtimeStatus.toActionableRuntimeLabel()
        if (actionableRuntimeLabel == null) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Text(
                text = actionableRuntimeLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
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
        if (actionableRuntimeLabel != null) {
            IconButton(
                onClick = {
                    onRuntimeCheck()
                    onPanelSelected(SynapsePanel.SETTINGS)
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = "Open runtime diagnostics",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        IconButton(
            onClick = { onPanelSelected(SynapsePanel.LIBRARY) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.FolderOpen,
                contentDescription = "Library",
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
    onThreadPinnedChanged: (ChatThreadRecord, Boolean) -> Unit,
    onThreadRenamed: (ChatThreadRecord, String) -> Unit,
    onThreadArchived: (ChatThreadRecord) -> Unit,
    onThreadDeleted: (ChatThreadRecord) -> Unit,
) {
    var actionThread by remember { mutableStateOf<ChatThreadRecord?>(null) }
    var renameThread by remember { mutableStateOf<ChatThreadRecord?>(null) }
    var deleteThread by remember { mutableStateOf<ChatThreadRecord?>(null) }

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
                            onThreadActionsRequested = { selectedThread ->
                                actionThread = selectedThread
                            },
                        )
                    }
                }
            }
        }
    }

    actionThread?.let { thread ->
        ThreadActionsDialog(
            thread = thread,
            onDismiss = { actionThread = null },
            onPinnedChanged = { pinned ->
                actionThread = null
                onThreadPinnedChanged(thread, pinned)
            },
            onRenameRequested = {
                actionThread = null
                renameThread = thread
            },
            onArchiveRequested = {
                actionThread = null
                onThreadArchived(thread)
            },
            onDeleteRequested = {
                actionThread = null
                deleteThread = thread
            },
        )
    }

    renameThread?.let { thread ->
        RenameThreadDialog(
            thread = thread,
            onDismiss = { renameThread = null },
            onRenamed = { title ->
                renameThread = null
                onThreadRenamed(thread, title)
            },
        )
    }

    deleteThread?.let { thread ->
        DeleteThreadDialog(
            thread = thread,
            onDismiss = { deleteThread = null },
            onDeleted = {
                deleteThread = null
                onThreadDeleted(thread)
            },
        )
    }
}

@Composable
private fun ThreadDrawerRow(
    thread: ChatThreadRecord,
    selected: Boolean,
    onThreadSelected: (ChatThreadRecord) -> Unit,
    onThreadActionsRequested: (ChatThreadRecord) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .pointerInput(thread.id.raw) {
                detectTapGestures(
                    onTap = { onThreadSelected(thread) },
                    onLongPress = { onThreadActionsRequested(thread) },
                )
            },
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thread.isPinned) {
                Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = "Pinned chat",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
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
private fun ThreadActionsDialog(
    thread: ChatThreadRecord,
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
                text = thread.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThreadActionDialogButton(
                    icon = Icons.Rounded.PushPin,
                    label = if (thread.isPinned) "Unpin chat" else "Pin chat",
                    onClick = { onPinnedChanged(!thread.isPinned) },
                )
                ThreadActionDialogButton(
                    icon = Icons.Rounded.Edit,
                    label = "Rename chat",
                    onClick = onRenameRequested,
                )
                ThreadActionDialogButton(
                    icon = Icons.Rounded.Archive,
                    label = "Archive chat",
                    onClick = onArchiveRequested,
                )
                ThreadActionDialogButton(
                    icon = Icons.Rounded.Delete,
                    label = "Delete chat",
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
private fun ThreadActionDialogButton(
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
private fun RenameThreadDialog(
    thread: ChatThreadRecord,
    onDismiss: () -> Unit,
    onRenamed: (String) -> Unit,
) {
    var draftTitle by remember(thread.id.raw) { mutableStateOf(thread.title) }
    val normalizedTitle = draftTitle.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
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
private fun DeleteThreadDialog(
    thread: ChatThreadRecord,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete chat?") },
        text = {
            Text(
                text = "This removes the chat and its messages from recent chats.",
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
private fun AppUpdateBanner(
    appUpdate: AppUpdateUiState,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: (Uri) -> Unit,
    onDismissAppUpdate: () -> Unit,
) {
    val update = appUpdate.availableUpdate
    if (
        update == null ||
        appUpdate.status !in setOf(
            AppUpdateStatus.AVAILABLE,
            AppUpdateStatus.DOWNLOADING,
            AppUpdateStatus.READY_TO_INSTALL,
        )
    ) {
        return
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "New Synapse update available",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Version code ${update.versionCode}. Updating preserves chats, memory, settings, and models " +
                    "when the APK package and signing key match.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            when (appUpdate.status) {
                AppUpdateStatus.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { appUpdate.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = formatModelDownloadProgressText(appUpdate.downloadedBytes, appUpdate.totalBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                AppUpdateStatus.READY_TO_INSTALL -> {
                    Text(
                        text = "Download complete. Android will ask you to approve the update install.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                else -> Unit
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (appUpdate.status) {
                    AppUpdateStatus.AVAILABLE ->
                        Button(
                            onClick = onDownloadAppUpdate,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Download now")
                        }

                    AppUpdateStatus.READY_TO_INSTALL ->
                        Button(
                            onClick = {
                                appUpdate.installerUri?.let(onInstallAppUpdate) ?: onCheckAppUpdate()
                            },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Install")
                        }

                    AppUpdateStatus.DOWNLOADING ->
                        Text(
                            text = "Downloading...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )

                    else -> Unit
                }
                TextButton(onClick = onDismissAppUpdate) {
                    Text("Later")
                }
            }
        }
    }
}

@Composable
private fun ChatWorkspace(
    modifier: Modifier = Modifier,
    state: SynapseUiState,
    messageSpeechController: MessageSpeechPlaybackController,
) {
    Column(
        modifier = modifier,
    ) {
        ChatPanel(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            messages = state.messages,
            speechPlaybackEnabled = state.settings.speechPlaybackEnabled,
            messageSpeechController = messageSpeechController,
        )
    }
}

@Composable
private fun ChatPanel(
    modifier: Modifier = Modifier,
    messages: List<ChatMessageRecord>,
    speechPlaybackEnabled: Boolean,
    messageSpeechController: MessageSpeechPlaybackController,
) {
    if (messages.isEmpty()) {
        EmptyChat(modifier = modifier)
        return
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var autoFollowLatest by remember { mutableStateOf(true) }
    val bottomAnchorIndex = messages.size
    val isAtChatBottom by remember {
        derivedStateOf { listState.isAtChatBottom(bottomAnchorIndex) }
    }
    val isAssistantTyping = messages.any { message ->
        message.role == ConversationRole.ASSISTANT &&
            message.deliveryState == MessageDeliveryState.STREAMING
    }

    LaunchedEffect(isAtChatBottom, listState.isScrollInProgress) {
        if (isAtChatBottom) {
            autoFollowLatest = true
        } else if (listState.isScrollInProgress) {
            autoFollowLatest = false
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.body, autoFollowLatest) {
        if (autoFollowLatest && messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 18.dp,
                end = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { message -> message.id.raw }) { message ->
                MessageBubble(
                    message = message,
                    speechPlaybackEnabled = speechPlaybackEnabled,
                    messageSpeechController = messageSpeechController,
                )
            }
            item(key = "chat-bottom-anchor") {
                Spacer(modifier = Modifier.height(1.dp))
            }
        }
        if (isAssistantTyping && !isAtChatBottom && !autoFollowLatest) {
            DetachedTypingIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                onClick = {
                    autoFollowLatest = true
                    coroutineScope.launch {
                        listState.animateScrollToItem(bottomAnchorIndex)
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
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
            text = "Let's jump in",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
        )
    }
}

private fun LazyListState.isAtChatBottom(bottomAnchorIndex: Int): Boolean {
    if (bottomAnchorIndex <= 0) return true
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return true
    val bottomAnchor = visibleItems.firstOrNull { item -> item.index == bottomAnchorIndex } ?: return false
    return bottomAnchor.offset + bottomAnchor.size <= layoutInfo.viewportEndOffset
}

@Composable
private fun MessageBubble(
    message: ChatMessageRecord,
    speechPlaybackEnabled: Boolean,
    messageSpeechController: MessageSpeechPlaybackController,
) {
    val isUser = message.role == ConversationRole.USER
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
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
                if (
                    !isUser &&
                    message.deliveryState == MessageDeliveryState.STREAMING &&
                    message.body.isBlank()
                ) {
                    SynapseThinkingIndicator()
                } else {
                    SelectionContainer {
                        Text(
                            text = message.body,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (message.body.isNotBlank() || message.deliveryState == MessageDeliveryState.FAILED) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isUser && message.deliveryState == MessageDeliveryState.FAILED) {
                            Text(
                                text = message.failureReason ?: "Failed",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (message.body.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(
                                                ClipData.newPlainText(
                                                    "Synapse message",
                                                    message.body,
                                                ),
                                            ),
                                        )
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy message",
                                )
                            }
                        }
                        if (!isUser && speechPlaybackEnabled && message.body.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    messageSpeechController.toggleMessagePlayback(
                                        messageId = message.id.raw,
                                        text = message.body,
                                    )
                                },
                            ) {
                                Icon(
                                    imageVector = when {
                                        messageSpeechController.isMessagePlaying(message.id.raw) ->
                                            Icons.Rounded.Pause

                                        messageSpeechController.isMessagePaused(message.id.raw) ->
                                            Icons.Rounded.PlayArrow

                                        else -> Icons.AutoMirrored.Rounded.VolumeUp
                                    },
                                    contentDescription = when {
                                        messageSpeechController.isMessagePlaying(message.id.raw) ->
                                            "Pause message"

                                        messageSpeechController.isMessagePaused(message.id.raw) ->
                                            "Resume message"

                                        else -> "Speak message"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SynapseThinkingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "synapse-thinking")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "synapse-thinking-rotation",
    )
    var visiblePhrase by remember { mutableStateOf("") }
    var visibleDots by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        var phraseIndex = Random.nextInt(SynapseThinkingPhrases.size)
        while (true) {
            val phrase = SynapseThinkingPhrases[phraseIndex]
            visiblePhrase = ""
            visibleDots = ""
            phrase.indices.forEach { characterIndex ->
                visiblePhrase = phrase.take(characterIndex + 1)
                delay(THINKING_PHRASE_CHARACTER_DELAY_MILLIS)
            }
            repeat(THINKING_PHRASE_DOT_CYCLES) {
                THINKING_PHRASE_DOT_STATES.forEach { dots ->
                    visibleDots = dots
                    delay(THINKING_PHRASE_DOT_DELAY_MILLIS)
                }
            }
            phraseIndex = (phraseIndex + 1 + Random.nextInt(SynapseThinkingPhrases.lastIndex)) %
                SynapseThinkingPhrases.size
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.synapse_guild_mark),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier
                .size(24.dp)
                .rotate(rotation),
        )
        Text(
            text = "$visiblePhrase$visibleDots",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DetachedTypingIndicator(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SynapseThinkingIndicator()
            Text(
                text = "Jump to latest",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
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
    onVoiceModeToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val sendAndHideKeyboard = {
        keyboardController?.hide()
        onSend()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        VoiceModeControlRow(
            voiceMode = state.voiceMode,
            onVoiceModeToggle = onVoiceModeToggle,
        )
        Spacer(modifier = Modifier.height(8.dp))
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
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = "Attach",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                TextField(
                    value = state.composerText,
                    onValueChange = onComposerChanged,
                    placeholder = { Text("Ask Synapse") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendAndHideKeyboard() }),
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
                IconButton(onClick = onStartSpeech) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = "Voice input",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = if (state.isSending) onStop else sendAndHideKeyboard,
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
private fun VoiceModeControlRow(
    voiceMode: VoiceModeUiState,
    onVoiceModeToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                Icon(
                    imageVector = if (voiceMode.isActive) {
                        Icons.Rounded.Stop
                    } else {
                        Icons.Rounded.Mic
                    },
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(6.dp))
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
private fun LibraryPanel(
    state: SynapseUiState,
    onLibraryTitleChanged: (String) -> Unit,
    onLibraryMarkdownChanged: (String) -> Unit,
    onCreateLibraryMarkdownArtifact: () -> Unit,
    onRefreshLibraryArtifacts: () -> Unit,
    onExportLibraryDraftAsPdf: () -> Unit,
    onExportLibraryArtifactAsPdf: (LibraryArtifactRecord) -> Unit,
) {
    val draftIsComplete = state.libraryDraftTitle.isNotBlank() &&
        state.libraryDraftMarkdown.isNotBlank()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Create Markdown note",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = state.libraryDraftTitle,
                        onValueChange = onLibraryTitleChanged,
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.libraryDraftMarkdown,
                        onValueChange = onLibraryMarkdownChanged,
                        label = { Text("Markdown") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 12,
                    )
                    Button(
                        onClick = onCreateLibraryMarkdownArtifact,
                        enabled = draftIsComplete && !state.isCreatingLibraryArtifact,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(if (state.isCreatingLibraryArtifact) "Saving..." else "Save Markdown")
                    }
                    TextButton(
                        onClick = onExportLibraryDraftAsPdf,
                        enabled = draftIsComplete && !state.isExportingLibraryPdf,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isExportingLibraryPdf) "Exporting PDF..." else "Export Draft PDF")
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recent artifacts",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onRefreshLibraryArtifacts) {
                    Text("Refresh")
                }
            }
        }
        if (state.libraryArtifacts.isEmpty()) {
            item {
                Text(
                    text = "No library artifacts yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(state.libraryArtifacts, key = { artifact -> artifact.id.raw }) { artifact ->
            LibraryArtifactRow(
                artifact = artifact,
                exportEnabled = !state.isExportingLibraryPdf,
                onExportLibraryArtifactAsPdf = onExportLibraryArtifactAsPdf,
            )
        }
    }
}

@Composable
private fun LibraryArtifactRow(
    artifact: LibraryArtifactRecord,
    exportEnabled: Boolean,
    onExportLibraryArtifactAsPdf: (LibraryArtifactRecord) -> Unit,
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
                    text = artifact.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${artifact.displayName} | ${formatByteCount(artifact.byteCount)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                artifact.catalogSummary?.let { summary ->
                    Text(
                        text = summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (artifact.tags.isNotEmpty()) {
                    Text(
                        text = artifact.tags.joinToString(" | "),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                onClick = { onExportLibraryArtifactAsPdf(artifact) },
                enabled = exportEnabled,
            ) {
                Text("PDF")
            }
        }
    }
}

@Composable
private fun MemoryPanel(
    state: SynapseUiState,
    onMemoryQueryChanged: (String) -> Unit,
    onMemoryFilterChanged: (MemoryReviewFilter) -> Unit,
    onMemorySearch: () -> Unit,
    onActivateMemory: (RetrievedMemoryRef) -> Unit,
    onTombstoneMemory: (RetrievedMemoryRef) -> Unit,
    onInspectStorage: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemoryReviewFilter.values().forEach { filter ->
                    val selected = filter == state.memoryReviewFilter
                    TextButton(onClick = { onMemoryFilterChanged(filter) }) {
                        Text(
                            text = filter.toDisplayLabel(),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
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
        if (state.memorySearchResults.isEmpty()) {
            item {
                Text(
                    text = when (state.memoryReviewFilter) {
                        MemoryReviewFilter.ACTIVE -> "No active memories found."
                        MemoryReviewFilter.REVIEW_NEEDED -> "No memories need review."
                        MemoryReviewFilter.INACTIVE -> "No inactive memories found."
                        MemoryReviewFilter.ALL -> "No memories found."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(state.memorySearchResults, key = { memory -> memory.memoryVersionId.raw }) { memory ->
            MemoryResultRow(
                memory = memory,
                onActivateMemory = onActivateMemory,
                onTombstoneMemory = onTombstoneMemory,
            )
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
    onActivateMemory: (RetrievedMemoryRef) -> Unit,
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
                    text = buildMemoryMetadataLabel(memory),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                memory.sourceQuote
                    ?.takeIf { sourceQuote -> sourceQuote.isNotBlank() }
                    ?.let { sourceQuote ->
                        Text(
                            text = "Source: $sourceQuote",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
            if (memory.status in reviewNeededMemoryStatuses) {
                IconButton(onClick = { onActivateMemory(memory) }) {
                    Icon(Icons.Rounded.Check, contentDescription = "Activate memory")
                }
            }
            if (memory.status != MemoryStatus.TOMBSTONED) {
                IconButton(onClick = { onTombstoneMemory(memory) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete memory")
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    state: SynapseUiState,
    onSettingsDraftChanged: (RuntimeSettingsDraft) -> Unit,
    onSaveSettings: () -> Unit,
    onImportEmbeddedModel: () -> Unit,
    onDownloadCatalogModel: (ModelCatalogEntry) -> Unit,
    onMemoryWritesChanged: (Boolean) -> Unit,
    onSpeechPlaybackChanged: (Boolean) -> Unit,
    onSmsAutoReplyChanged: (Boolean) -> Unit,
    onExportDebugArchive: () -> Unit,
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
            RuntimeBackendSelector(
                selectedBackend = state.settingsDraft.runtimeBackend,
                onBackendSelected = { backend ->
                    onSettingsDraftChanged(state.settingsDraft.copy(runtimeBackend = backend))
                },
            )
        }
        if (state.settingsDraft.runtimeBackend == InferenceRuntimeBackend.EMBEDDED_LLAMA) {
            item {
                EmbeddedModelSettingsCard(
                    state = state,
                    onImportEmbeddedModel = onImportEmbeddedModel,
                    onDownloadCatalogModel = onDownloadCatalogModel,
                )
            }
            item {
                ModelPromptProfileSelector(
                    selectedProfile = state.settingsDraft.modelPromptProfile,
                    onProfileSelected = { profile ->
                        onSettingsDraftChanged(state.settingsDraft.copy(modelPromptProfile = profile))
                    },
                )
            }
        }
        if (state.settingsDraft.runtimeBackend == InferenceRuntimeBackend.LLAMA_SERVER) {
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
                value = state.settingsDraft.persona,
                onValueChange = { value ->
                    onSettingsDraftChanged(state.settingsDraft.copy(persona = value))
                },
                label = { Text("Persona") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
            )
        }
        item {
            OutlinedTextField(
                value = state.settingsDraft.customInstructions,
                onValueChange = { value ->
                    onSettingsDraftChanged(state.settingsDraft.copy(customInstructions = value))
                },
                label = { Text("Custom Instructions") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
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
            SettingsSwitchRow(
                label = "SMS auto-reply",
                supportingText = "When enabled, incoming SMS messages are sent to the local model and the reply is queued back to the sender automatically.",
                checked = state.settings.smsAutoReplyEnabled,
                onCheckedChange = onSmsAutoReplyChanged,
            )
        }
        item {
            DebugArchiveCard(onExportDebugArchive = onExportDebugArchive)
        }
        item {
            Button(onClick = onSaveSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
        }
        item {
            Spacer(
                modifier = Modifier
                    .height(16.dp)
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun DebugArchiveCard(onExportDebugArchive: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Diagnostics",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Exports private chats, memory, prompts, settings, and runtime metadata. " +
                    "The GGUF model file is not included.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onExportDebugArchive,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Export Debug ZIP")
            }
        }
    }
}

@Composable
private fun RuntimeBackendSelector(
    selectedBackend: InferenceRuntimeBackend,
    onBackendSelected: (InferenceRuntimeBackend) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { onBackendSelected(InferenceRuntimeBackend.EMBEDDED_LLAMA) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (selectedBackend == InferenceRuntimeBackend.EMBEDDED_LLAMA) {
                        "Embedded"
                    } else {
                        "Use embedded"
                    },
                )
            }
            TextButton(
                onClick = { onBackendSelected(InferenceRuntimeBackend.LLAMA_SERVER) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (selectedBackend == InferenceRuntimeBackend.LLAMA_SERVER) {
                        "Server"
                    } else {
                        "Use server"
                    },
                )
            }
        }
    }
}

@Composable
private fun EmbeddedModelSettingsCard(
    state: SynapseUiState,
    onImportEmbeddedModel: () -> Unit,
    onDownloadCatalogModel: (ModelCatalogEntry) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Embedded model",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val modelName = state.settings.embeddedModelDisplayName ?: "No GGUF model imported"
            val byteCount = state.settings.embeddedModelByteCount
            Text(
                text = if (byteCount == null) modelName else "$modelName | ${formatByteCount(byteCount)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onImportEmbeddedModel,
                enabled = !state.isImportingModel && state.activeModelDownload?.isActive != true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (state.isImportingModel) "Importing..." else "Import GGUF")
            }
            if (state.modelCatalogEntries.isNotEmpty()) {
                Text(
                    text = "Recommended downloads",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                state.modelCatalogEntries.forEach { entry ->
                    ModelCatalogEntryRow(
                        entry = entry,
                        activeDownload = state.activeModelDownload,
                        selectedDisplayName = state.settings.embeddedModelDisplayName,
                        onDownloadCatalogModel = onDownloadCatalogModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCatalogEntryRow(
    entry: ModelCatalogEntry,
    activeDownload: ModelDownloadUiState?,
    selectedDisplayName: String?,
    onDownloadCatalogModel: (ModelCatalogEntry) -> Unit,
) {
    val isSelected = selectedDisplayName == entry.fileName
    val isDownloading = activeDownload?.entryId == entry.id && activeDownload.isActive
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (entry.recommended) "${entry.name} | recommended" else entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${formatModelDownloadByteCount(entry.sizeBytes)} | ${entry.sourceLabel}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(
                    onClick = { onDownloadCatalogModel(entry) },
                    enabled = !isSelected && !isDownloading,
                ) {
                    Text(
                        when {
                            isSelected -> "Selected"
                            isDownloading -> "Downloading"
                            else -> "Download"
                        },
                    )
                }
            }
            Text(
                text = entry.compatibilityNotes,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (activeDownload?.entryId == entry.id) {
                LinearProgressIndicator(
                    progress = { activeDownload.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${activeDownload.statusText}: " +
                        formatModelDownloadProgressText(
                            activeDownload.downloadedBytes,
                            activeDownload.totalBytes,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (activeDownload.powerSaveMode) {
                    Text(
                        text = "Battery saver is on; Android may pause or throttle this background download.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPromptProfileSelector(
    selectedProfile: ModelPromptProfile,
    onProfileSelected: (ModelPromptProfile) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Prompt profile",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = selectedProfile.toPromptProfileLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModelPromptProfile.entries.forEach { profile ->
                    TextButton(
                        onClick = { onProfileSelected(profile) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = profile.toPromptProfileShortLabel(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    supportingText: String? = null,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label)
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private val requiredSmsAutoReplyPermissions = arrayOf(
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.SEND_SMS,
)

private fun hasSmsAutoReplyPermissions(context: Context): Boolean =
    requiredSmsAutoReplyPermissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun rememberMessageSpeechPlaybackController(): MessageSpeechPlaybackController {
    val context = LocalContext.current
    val controller = remember { MessageSpeechPlaybackController() }

    DisposableEffect(context) {
        val createdEngine = TextToSpeech(context) { status ->
            controller.updateEngineReadiness(status == TextToSpeech.SUCCESS)
        }
        createdEngine.language = Locale.getDefault()
        controller.attachEngine(createdEngine)
        onDispose {
            controller.dispose()
        }
    }

    return controller
}

private class MessageSpeechPlaybackController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var activeMessageText = ""
    private var resumeCharIndex = 0
    private var utteranceBaseCharIndex = 0
    private var activeUtteranceId = ""

    var playingMessageId by mutableStateOf<String?>(null)
        private set
    var pausedMessageId by mutableStateOf<String?>(null)
        private set

    fun attachEngine(createdEngine: TextToSpeech) {
        engine = createdEngine
        createdEngine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == activeUtteranceId) {
                        mainHandler.post {
                            playingMessageId = null
                            pausedMessageId = null
                            resumeCharIndex = 0
                        }
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == activeUtteranceId) {
                        mainHandler.post {
                            playingMessageId = null
                            pausedMessageId = null
                        }
                    }
                }

                override fun onRangeStart(
                    utteranceId: String?,
                    start: Int,
                    end: Int,
                    frame: Int,
                ) {
                    if (utteranceId == activeUtteranceId) {
                        resumeCharIndex = (utteranceBaseCharIndex + end)
                            .coerceAtMost(activeMessageText.length)
                    }
                }
            },
        )
    }

    fun updateEngineReadiness(isReady: Boolean) {
        ready = isReady
    }

    fun toggleMessagePlayback(messageId: String, text: String) {
        when {
            playingMessageId == messageId -> pauseActiveMessage()
            pausedMessageId == messageId -> resumePausedMessage()
            else -> startMessage(messageId, text)
        }
    }

    fun isMessagePlaying(messageId: String): Boolean = playingMessageId == messageId

    fun isMessagePaused(messageId: String): Boolean = pausedMessageId == messageId

    fun dispose() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        playingMessageId = null
        pausedMessageId = null
    }

    private fun startMessage(messageId: String, text: String) {
        if (!ready || text.isBlank()) return
        activeMessageText = text
        resumeCharIndex = 0
        pausedMessageId = null
        speakMessageFrom(messageId, 0)
    }

    private fun pauseActiveMessage() {
        val messageId = playingMessageId ?: return
        engine?.stop()
        playingMessageId = null
        pausedMessageId = messageId
    }

    private fun resumePausedMessage() {
        val messageId = pausedMessageId ?: return
        val resumeIndex = resumeCharIndex.coerceIn(0, activeMessageText.length)
        pausedMessageId = null
        speakMessageFrom(messageId, resumeIndex)
    }

    private fun speakMessageFrom(messageId: String, startIndex: Int) {
        val textToSpeak = activeMessageText.drop(startIndex).trimStart()
        if (textToSpeak.isBlank()) {
            playingMessageId = null
            pausedMessageId = null
            resumeCharIndex = 0
            return
        }
        resumeCharIndex = startIndex
        utteranceBaseCharIndex = startIndex
        activeUtteranceId = "synapse-message-$messageId-${System.nanoTime()}"
        playingMessageId = messageId
        engine?.speak(
            textToSpeak,
            TextToSpeech.QUEUE_FLUSH,
            null,
            activeUtteranceId,
        )
    }
}

@Composable
private fun rememberVoiceModeSpeechController(
    onSpeechFinished: () -> Unit,
    onSpeechError: (String) -> Unit,
): VoiceModeSpeechController {
    val context = LocalContext.current
    val latestOnSpeechFinished by rememberUpdatedState(onSpeechFinished)
    val latestOnSpeechError by rememberUpdatedState(onSpeechError)
    val controller = remember { VoiceModeSpeechController() }

    DisposableEffect(context) {
        val createdEngine = TextToSpeech(context) { status ->
            controller.updateEngineReadiness(status == TextToSpeech.SUCCESS)
        }
        createdEngine.language = Locale.getDefault()
        controller.attachEngine(
            createdEngine = createdEngine,
            onSpeechFinished = { latestOnSpeechFinished() },
            onSpeechError = { reason -> latestOnSpeechError(reason) },
        )
        onDispose {
            controller.dispose()
        }
    }

    return controller
}

private class VoiceModeSpeechController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var activeUtteranceId = ""
    private var onSpeechFinished: () -> Unit = {}
    private var onSpeechError: (String) -> Unit = {}

    fun attachEngine(
        createdEngine: TextToSpeech,
        onSpeechFinished: () -> Unit,
        onSpeechError: (String) -> Unit,
    ) {
        engine = createdEngine
        this.onSpeechFinished = onSpeechFinished
        this.onSpeechError = onSpeechError
        createdEngine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == activeUtteranceId) {
                        mainHandler.post { this@VoiceModeSpeechController.onSpeechFinished() }
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == activeUtteranceId) {
                        mainHandler.post {
                            this@VoiceModeSpeechController.onSpeechError("Voice Mode playback failed.")
                        }
                    }
                }
            },
        )
    }

    fun updateEngineReadiness(isReady: Boolean) {
        ready = isReady
    }

    fun speak(text: String) {
        if (!ready) {
            onSpeechError("Text-to-speech is not ready.")
            return
        }
        val textToSpeak = text.trim()
        if (textToSpeak.isBlank()) {
            onSpeechError("Synapse had no speakable reply.")
            return
        }
        activeUtteranceId = "synapse-voice-mode-${System.nanoTime()}"
        val speakStatus = engine?.speak(
            textToSpeak,
            TextToSpeech.QUEUE_FLUSH,
            null,
            activeUtteranceId,
        )
        if (speakStatus == TextToSpeech.ERROR) {
            onSpeechError("Voice Mode playback failed.")
        }
    }

    fun stop() {
        engine?.stop()
    }

    fun dispose() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
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
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
    }

private fun shareDebugArchive(context: Context, archiveUri: Uri) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, archiveUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Share Synapse debug archive"))
}

private fun sharePdfExport(context: Context, pdfUri: Uri) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, pdfUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Share Synapse PDF"))
}

private fun launchAppUpdateInstaller(context: Context, apkUri: Uri): Boolean =
    runCatching {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        true
    }.getOrDefault(false)

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

private fun RuntimeStatus.toActionableRuntimeLabel(): String? =
    when (this) {
        is RuntimeStatus.Ready -> null
        is RuntimeStatus.Starting ->
            when (receipt.status) {
                RuntimeStartStatus.EMBEDDED_MODEL_MISSING,
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

private fun VoiceModeUiState.toDisplayLabel(): String =
    when (status) {
        VoiceModeStatus.OFF -> "Voice Mode off"
        VoiceModeStatus.LISTENING -> "Voice Mode listening"
        VoiceModeStatus.PROCESSING -> "Voice Mode processing"
        VoiceModeStatus.SPEAKING -> "Voice Mode speaking"
        VoiceModeStatus.ERROR -> errorMessage ?: "Voice Mode paused after an error"
    }

private fun VoiceModeUiState.toActionLabel(): String =
    when (status) {
        VoiceModeStatus.OFF -> "Voice Mode"
        VoiceModeStatus.ERROR -> "Retry Voice"
        VoiceModeStatus.LISTENING,
        VoiceModeStatus.PROCESSING,
        VoiceModeStatus.SPEAKING,
        -> "Stop Voice"
    }

private fun MemoryKind.toDisplayLabel(): String =
    name.lowercase().replaceFirstChar { firstCharacter ->
        if (firstCharacter.isLowerCase()) {
            firstCharacter.titlecase()
        } else {
            firstCharacter.toString()
        }
    }

private fun MemoryReviewFilter.toDisplayLabel(): String =
    when (this) {
        MemoryReviewFilter.ACTIVE -> "Active"
        MemoryReviewFilter.REVIEW_NEEDED -> "Review"
        MemoryReviewFilter.INACTIVE -> "Inactive"
        MemoryReviewFilter.ALL -> "All"
    }

private fun buildMemoryMetadataLabel(memory: RetrievedMemoryRef): String =
    listOfNotNull(
        memory.kind.toDisplayLabel(),
        memory.status.name.lowercase(),
        memory.scope.name.lowercase(),
        memory.domain.name.lowercase(),
        memory.subject?.takeIf { subject -> subject.isNotBlank() },
        memory.predicate?.takeIf { predicate -> predicate.isNotBlank() },
        memory.claimKey?.takeIf { claimKey -> claimKey.isNotBlank() },
        memory.sensitivity.name.lowercase(),
        "${(memory.confidence * 100).toInt()}%",
        "evidence ${memory.sourceTraceEventIds.size}",
        memory.rankScore
            .takeIf { score -> score > 0.0 }
            ?.let { score -> "rank ${"%.1f".format(Locale.US, score)}" },
    ).joinToString(" | ")

private fun ModelPromptProfile.toPromptProfileLabel(): String =
    when (this) {
        ModelPromptProfile.AUTO -> "Auto: use GGUF template when available"
        ModelPromptProfile.QWEN_CHATML -> "Qwen ChatML"
        ModelPromptProfile.LLAMA_INSTRUCT -> "Llama 3 Instruct"
        ModelPromptProfile.PLAIN_COMPLETION -> "Plain transcript"
    }

private fun ModelPromptProfile.toPromptProfileShortLabel(): String =
    when (this) {
        ModelPromptProfile.AUTO -> "Auto"
        ModelPromptProfile.QWEN_CHATML -> "Qwen"
        ModelPromptProfile.LLAMA_INSTRUCT -> "Llama"
        ModelPromptProfile.PLAIN_COMPLETION -> "Plain"
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

private enum class SpeechRecognitionTarget {
    COMPOSER,
    VOICE_MODE,
}

private val attachmentMimeTypes =
    arrayOf(
        "text/*",
        "image/*",
        "application/json",
        "application/pdf",
        "application/octet-stream",
    )

private val modelMimeTypes =
    arrayOf(
        "application/octet-stream",
        "application/x-gguf",
        "*/*",
    )

private const val MAX_TEXT_ATTACHMENT_READ_CHARS = 64_000
private const val KIB = 1024L
private const val MIB = KIB * 1024L
private const val GIB = MIB * 1024L

private val reviewNeededMemoryStatuses = setOf(
    MemoryStatus.CONFLICTED,
    MemoryStatus.QUARANTINED,
)
