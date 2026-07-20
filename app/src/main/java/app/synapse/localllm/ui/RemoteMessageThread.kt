package app.synapse.localllm.ui

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.emoji2.emojipicker.EmojiPickerView
import app.synapse.localllm.application.RemoteLocalAiHostStatus
import app.synapse.localllm.domain.appearance.ChatBackground
import app.synapse.localllm.domain.appearance.clampChatMessageScale
import app.synapse.localllm.domain.remote.RemoteAssistantAvailability
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteCinderParticipationMode
import app.synapse.localllm.domain.remote.RemoteCinderWorkState
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.remote.RemoteRoomId
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
internal fun RemoteMessageThread(
    state: RemoteChatUiState,
    room: RemoteCachedRoom?,
    onBack: () -> Unit,
    onOpenSharedContent: () -> Unit,
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
    onStartDirectCall: (RemoteRoomId, RemoteDirectCallMediaKind) -> Unit,
    onCallPermissionDenied: (RemoteDirectCallMediaKind) -> Unit,
    directCallActionEnabled: Boolean,
    feedbackPreferences: ChatFeedbackPreferences,
    onReply: (RemoteMessageId) -> Unit,
    onToggleReaction: (RemoteCachedMessage, String) -> Unit,
    onCancelReply: () -> Unit,
    onEdit: (RemoteCachedMessage, String) -> Unit,
    onDeleteForMe: (RemoteCachedMessage) -> Unit,
    onDeleteForEveryone: (RemoteCachedMessage) -> Unit,
    onLoadOlder: () -> Unit,
    onJumpToMessage: (RemoteMessageId) -> Unit,
    onMessageRevealed: () -> Unit,
    onLocalAiConfigurationChanged: (Boolean, Boolean) -> Unit,
    onCinderParticipationChanged: (Boolean) -> Unit,
    onCinderModeChanged: (RemoteCinderParticipationMode) -> Unit,
    onMentionSynapse: () -> Unit,
    onMentionCinder: () -> Unit,
    appearanceState: ChatAppearanceUiState,
    onBackgroundSelected: (ChatBackground) -> Unit,
    onMessageScaleSelected: (Float) -> Unit,
    onResetAppearance: () -> Unit,
    accountState: RemoteAccountUiState,
    groupState: RemoteGroupUiState,
    groupViewModel: RemoteGroupViewModel,
) {
    var showRoomMembers by rememberSaveable(state.selectedRoomId?.raw) { mutableStateOf(false) }
    var showAppearance by rememberSaveable(state.selectedRoomId?.raw) { mutableStateOf(false) }
    val feedbackController = rememberChatFeedbackController()
    val feedbackHaptics = LocalHapticFeedback.current
    val deletionEffectScope = rememberCoroutineScope()
    var observedMessageIds by remember(state.selectedRoomId?.raw) { mutableStateOf<Set<String>?>(null) }
    var observedReadyAttachmentIds by remember(state.selectedRoomId?.raw) { mutableStateOf(emptySet<String>()) }
    var observedDeletedMessageIds by remember(state.selectedRoomId?.raw) { mutableStateOf<Set<String>?>(null) }
    var activeDeletionEffectIds by remember(state.selectedRoomId?.raw) { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()
    var scrollInitialized by rememberSaveable(state.selectedRoomId?.raw) { mutableStateOf(false) }
    var lastScrollObservedMessageId by remember(state.selectedRoomId?.raw) { mutableStateOf<String?>(null) }
    var pendingNewMessageCount by rememberSaveable(state.selectedRoomId?.raw) { mutableIntStateOf(0) }
    val isNearMessageEnd by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount == 0 ||
                (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 3
        }
    }
    var displayedMessageScale by rememberSaveable(state.selectedRoomId?.raw) {
        mutableFloatStateOf(appearanceState.appearance.messageScale)
    }
    val persistMessageScale by rememberUpdatedState(onMessageScaleSelected)
    val baseDensity = LocalDensity.current
    val scaledMessageDensity = remember(baseDensity, displayedMessageScale) {
        Density(
            density = baseDensity.density * displayedMessageScale,
            fontScale = baseDensity.fontScale,
        )
    }
    val peer = state.profiles.firstOrNull { profile -> profile.profileUid == room?.peerUid }
    val context = LocalContext.current
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            room?.roomId?.let { roomId -> onStartDirectCall(roomId, RemoteDirectCallMediaKind.AUDIO) }
        } else {
            onCallPermissionDenied(RemoteDirectCallMediaKind.AUDIO)
        }
    }
    val videoCallPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (VIDEO_CALL_PERMISSIONS.all { permission -> grants[permission] == true }) {
            room?.roomId?.let { roomId -> onStartDirectCall(roomId, RemoteDirectCallMediaKind.VIDEO) }
        } else {
            onCallPermissionDenied(RemoteDirectCallMediaKind.VIDEO)
        }
    }
    val currentProfile = state.profiles.firstOrNull { profile ->
        profile.profileUid.raw == state.account?.accountUid?.raw
    }
    val title = when (room?.kind) {
        RemoteRoomKind.DIRECT -> peer?.displayName ?: room.title
        RemoteRoomKind.GROUP,
        RemoteRoomKind.ASSISTANT,
        -> room.title
        null -> "Private conversation"
    }
    val localDeletionEffectIds = state.confirmedLocalDeletionEffects
        .mapTo(linkedSetOf()) { message -> message.messageId.raw }
    val presentationMessages = remember(state.messages, state.confirmedLocalDeletionEffects) {
        (state.messages + state.confirmedLocalDeletionEffects)
            .distinctBy { message -> message.messageId }
            .sortedBy(RemoteCachedMessage::displayInstant)
    }
    val initialUnreadCutoff = remember(room?.roomId) { room?.lastReadAt ?: room?.joinedAt }
    val initialUnreadCount = remember(room?.roomId) { room?.unreadCount ?: 0 }
    var unreadDividerMessageId by rememberSaveable(room?.roomId?.raw) { mutableStateOf<String?>(null) }
    var unreadDividerResolved by rememberSaveable(room?.roomId?.raw) { mutableStateOf(false) }

    LaunchedEffect(showRoomMembers, room?.roomId) {
        if (showRoomMembers && room?.kind == RemoteRoomKind.GROUP) {
            groupViewModel.loadGroupDetails(room.roomId)
        }
    }
    LaunchedEffect(appearanceState.appearance.messageScale) {
        displayedMessageScale = appearanceState.appearance.messageScale
    }
    LaunchedEffect(state.messages) {
        val currentIds = state.messages.mapTo(linkedSetOf()) { message -> message.messageId.raw }
        val previousIds = observedMessageIds
        if (previousIds != null) {
            val newMessages = state.messages.filter { message -> message.messageId.raw !in previousIds }
            when {
                newMessages.any { message -> message.senderUid.raw != state.account?.accountUid?.raw } ->
                    feedbackController.play(ChatSoundCue.INCOMING, feedbackPreferences.soundsEnabled)
                newMessages.isNotEmpty() -> {
                    feedbackController.play(ChatSoundCue.SENT, feedbackPreferences.soundsEnabled)
                    if (feedbackPreferences.hapticsEnabled) {
                        feedbackHaptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm,
                        )
                    }
                }
            }
        }
        observedMessageIds = currentIds
    }
    LaunchedEffect(state.pendingAttachments) {
        val readyIds = state.pendingAttachments
            .filter { pending -> pending.state == RemoteAttachmentTransferState.READY }
            .mapTo(linkedSetOf()) { pending -> pending.selection.attachmentId.raw }
        if ((readyIds - observedReadyAttachmentIds).isNotEmpty()) {
            feedbackController.play(ChatSoundCue.UPLOAD_COMPLETE, feedbackPreferences.soundsEnabled)
        }
        observedReadyAttachmentIds = readyIds
    }

    LaunchedEffect(state.messages.map { message -> message.messageId.raw to message.deletedAt }) {
        val currentDeletedIds = state.messages
            .filter { message -> message.deletedAt != null }
            .mapTo(linkedSetOf()) { message -> message.messageId.raw }
        observedDeletedMessageIds?.let { previousDeletedIds ->
            (currentDeletedIds - previousDeletedIds).forEach { messageId ->
                activeDeletionEffectIds = activeDeletionEffectIds + messageId
                deletionEffectScope.launch {
                    delay(CONFIRMED_DELETION_EFFECT_DURATION_MILLIS.toLong())
                    activeDeletionEffectIds = activeDeletionEffectIds - messageId
                }
            }
        }
        observedDeletedMessageIds = currentDeletedIds
    }
    LaunchedEffect(presentationMessages, initialUnreadCutoff, initialUnreadCount) {
        if (!unreadDividerResolved && presentationMessages.isNotEmpty()) {
            unreadDividerMessageId = if (initialUnreadCount > 0) {
                remoteUnreadDividerMessageId(
                    messages = presentationMessages,
                    currentAccountUid = state.account?.accountUid?.raw,
                    lastReadAt = initialUnreadCutoff,
                )?.raw
            } else {
                null
            }
            unreadDividerResolved = true
        }
    }
    LaunchedEffect(state.messages.lastOrNull()?.messageId) {
        val newestMessage = state.messages.lastOrNull() ?: return@LaunchedEffect
        val newestMessageId = newestMessage.messageId.raw
        if (!scrollInitialized) {
            listState.scrollToItem(presentationMessages.lastIndex.coerceAtLeast(0) + 1)
            scrollInitialized = true
        } else if (lastScrollObservedMessageId != null && newestMessageId != lastScrollObservedMessageId) {
            val sentByCurrentAccount = newestMessage.senderUid.raw == state.account?.accountUid?.raw
            if (sentByCurrentAccount || isNearMessageEnd) {
                listState.animateScrollToItem(presentationMessages.lastIndex.coerceAtLeast(0) + 1)
                pendingNewMessageCount = 0
            } else {
                pendingNewMessageCount += 1
            }
        }
        lastScrollObservedMessageId = newestMessageId
    }
    LaunchedEffect(isNearMessageEnd) {
        if (isNearMessageEnd) pendingNewMessageCount = 0
    }
    LaunchedEffect(state.messageToRevealId, state.messages) {
        val messageId = state.messageToRevealId ?: return@LaunchedEffect
        val index = presentationMessages.indexOfFirst { message -> message.messageId == messageId }
        if (index >= 0) listState.animateScrollToItem(index + 1)
        onMessageRevealed()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to conversations")
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = room?.kind != RemoteRoomKind.ASSISTANT) {
                        showRoomMembers = !showRoomMembers
                    }
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteProfileAvatar(profile = peer, displayName = title, size = 36.dp)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (showRoomMembers) {
                            "Tap to return to messages"
                        } else {
                            remoteConversationActivityLabel(
                                typingNames = state.typingParticipantUids.mapNotNull { uid ->
                                    state.profiles.firstOrNull { profile -> profile.profileUid == uid }?.displayName
                                },
                                cinderWorkState = state.cinderParticipant?.workState,
                            )
                                ?: when (room?.kind) {
                                    RemoteRoomKind.ASSISTANT -> remoteAssistantAvailabilityLabel(
                                        state.selectedAssistantAvailability,
                                    )
                                    RemoteRoomKind.GROUP -> "Group conversation"
                                    RemoteRoomKind.DIRECT,
                                    null,
                                    -> remotePresenceLabel(peer)
                                }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenSharedContent) {
                Icon(Icons.Default.Collections, contentDescription = "Shared media, links, and files")
            }
            if (room?.kind == RemoteRoomKind.DIRECT) {
                IconButton(
                    onClick = {
                        if (
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            onStartDirectCall(room.roomId, RemoteDirectCallMediaKind.AUDIO)
                        } else {
                            callPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = directCallActionEnabled,
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Start voice call")
                }
                IconButton(
                    onClick = {
                        if (
                            VIDEO_CALL_PERMISSIONS.all { permission ->
                                ContextCompat.checkSelfPermission(context, permission) ==
                                    PackageManager.PERMISSION_GRANTED
                            }
                        ) {
                            onStartDirectCall(room.roomId, RemoteDirectCallMediaKind.VIDEO)
                        } else {
                            videoCallPermissionLauncher.launch(VIDEO_CALL_PERMISSIONS)
                        }
                    },
                    enabled = directCallActionEnabled,
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = "Start video call")
                }
            }
        }
        HorizontalDivider()
        if (showRoomMembers) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onOpenSharedContent) {
                        Icon(Icons.Default.Collections, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Shared content")
                    }
                    TextButton(onClick = { showAppearance = true }) {
                        Icon(Icons.Default.Palette, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Appearance")
                    }
                }
                RemoteAiParticipantControls(
                    state = state,
                    room = room,
                    onConfigurationChanged = onLocalAiConfigurationChanged,
                    onCinderParticipationChanged = onCinderParticipationChanged,
                    onCinderModeChanged = onCinderModeChanged,
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
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(room?.roomId) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var scaleChanged = false
                            var pointersPressed: Boolean
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { change -> change.pressed } >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom.isFinite() && zoom != 1f) {
                                        displayedMessageScale = clampChatMessageScale(displayedMessageScale * zoom)
                                        scaleChanged = true
                                    }
                                    event.changes.forEach { change -> change.consume() }
                                }
                                pointersPressed = event.changes.any { change -> change.pressed }
                            } while (pointersPressed)
                            if (scaleChanged) persistMessageScale(displayedMessageScale)
                        }
                    },
            ) {
                ChatBackgroundLayer(appearanceState.appearance.background)
                if (presentationMessages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = (state.selectedAssistantAvailability as? RemoteAssistantAvailability.Unavailable)
                                ?.userMessage
                                ?: "Messages will synchronize here.",
                            color = appearanceState.appearance.bubblePalette.presentation().contentColor,
                        )
                    }
                } else {
                    CompositionLocalProvider(LocalDensity provides scaledMessageDensity) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            item(key = "load-older") {
                                OutlinedButton(
                                    onClick = onLoadOlder,
                                    enabled = !state.hasReachedMessageStart && !state.isLoadingOlderMessages,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (state.hasReachedMessageStart) {
                                            "Start of conversation"
                                        } else {
                                            "Load earlier messages"
                                        },
                                    )
                                }
                            }
                            itemsIndexed(
                                items = presentationMessages,
                                key = { _, message -> message.messageId.raw },
                            ) { index, message ->
                                val previousDate = presentationMessages.getOrNull(index - 1)
                                    ?.displayInstant()
                                    ?.atZone(ZoneId.systemDefault())
                                    ?.toLocalDate()
                                val messageDate = message.displayInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                                val isCurrentAccount = message.senderUid.raw == state.account?.accountUid?.raw
                                val groupPresentation = remoteMessageGroupPresentation(
                                    messages = presentationMessages,
                                    index = index,
                                    currentAccountUid = state.account?.accountUid?.raw,
                                    showGroupIdentities = room?.kind == RemoteRoomKind.GROUP,
                                )
                                val bubblePalette = appearanceState.appearance.bubblePalette.presentation()
                                val isLocallyHostedAiMessage = message.authorKind == "SYNAPSE_AI" &&
                                    state.roomAiConfiguration?.localAiHostUid == state.account?.accountUid
                                val isLocalDeletionEffect = message.messageId.raw in localDeletionEffectIds
                                val supportsRemoteInteractions = room?.kind != RemoteRoomKind.ASSISTANT &&
                                    !isLocalDeletionEffect
                                val canDeleteForEveryone = supportsRemoteInteractions &&
                                    (isCurrentAccount || isLocallyHostedAiMessage)
                                val senderProfile = state.profiles.firstOrNull { profile ->
                                    profile.profileUid == message.senderUid
                                }
                                val senderDisplayName = when {
                                    message.authorKind == "SYNAPSE_AI" -> "Synapse • Phone-local AI"
                                    message.authorKind == "REMOTE_AI" -> remoteAiSenderDisplayName(message)
                                    room?.kind == RemoteRoomKind.GROUP -> senderProfile?.displayName ?: "Group member"
                                    else -> null
                                }
                                val repliedMessage = message.replyToMessageId?.let { replyId ->
                                    state.messages.firstOrNull { candidate -> candidate.messageId == replyId }
                                }
                                Column(
                                    modifier = Modifier.padding(
                                        top = if (groupPresentation.beginsNewVisualGroup) 9.dp else 2.dp,
                                    ),
                                ) {
                                    if (messageDate != previousDate) {
                                        RemoteMessageDateDivider(
                                            label = remoteMessageDateLabel(messageDate),
                                            contentColor = bubblePalette.contentColor,
                                        )
                                    }
                                    if (message.messageId.raw == unreadDividerMessageId) {
                                        RemoteUnreadMessageDivider()
                                    }
                                    RemoteMessageBubble(
                                        message = message,
                                        repliedMessage = repliedMessage,
                                        repliedSenderDisplayName = repliedMessage?.let { replied ->
                                            remoteMessageSenderDisplayName(
                                                message = replied,
                                                currentAccountUid = state.account?.accountUid?.raw,
                                                profiles = state.profiles,
                                            )
                                        },
                                        isCurrentAccount = isCurrentAccount,
                                        bubbleColor = if (isCurrentAccount) {
                                            bubblePalette.outgoingBubbleColor
                                        } else {
                                            bubblePalette.incomingBubbleColor
                                        },
                                        bubbleContentColor = bubblePalette.contentColor,
                                        bubbleShape = remoteMessageBubbleShape(
                                            position = groupPresentation.position,
                                            isCurrentAccount = isCurrentAccount,
                                        ),
                                        canDeleteForEveryone = canDeleteForEveryone,
                                        supportsRemoteInteractions = supportsRemoteInteractions,
                                        selectedReaction = state.ownReactionSelections[message.messageId],
                                        senderDisplayName = senderDisplayName,
                                        senderNameColor = remoteParticipantNameColor(
                                            roomId = message.roomId.raw,
                                            senderUid = message.senderUid.raw,
                                        ),
                                        senderProfile = senderProfile,
                                        showIdentityGutter = room?.kind == RemoteRoomKind.GROUP &&
                                            !isCurrentAccount,
                                        showSenderName = groupPresentation.showSenderName,
                                        showAvatar = groupPresentation.showAvatar,
                                        renderAsDeleted = message.deletedAt != null || isLocalDeletionEffect,
                                        playDeletionEffect = message.messageId.raw in activeDeletionEffectIds ||
                                            isLocalDeletionEffect,
                                        isLocalDeletionEffect = isLocalDeletionEffect,
                                        reducedMotion = feedbackPreferences.reducedMotionEnabled,
                                        onReply = { onReply(message.messageId) },
                                        onToggleReaction = { emoji -> onToggleReaction(message, emoji) },
                                        onEdit = { body -> onEdit(message, body) },
                                        onDeleteForMe = { onDeleteForMe(message) },
                                        onDeleteForEveryone = { onDeleteForEveryone(message) },
                                        attachmentDownloads = state.attachmentDownloads,
                                        onDownloadAttachment = { attachmentId, thumbnail ->
                                            onDownloadAttachment(message, attachmentId, thumbnail)
                                        },
                                        onDownloadReplyThumbnail = { replied, attachmentId ->
                                            onDownloadAttachment(replied, attachmentId, true)
                                        },
                                        onCancelAttachmentDownload = onCancelAttachmentDownload,
                                        onJumpToReply = { replyId -> onJumpToMessage(replyId) },
                                        feedbackPreferences = feedbackPreferences,
                                        feedbackController = feedbackController,
                                    )
                                }
                            }
                        }
                        if (pendingNewMessageCount > 0) {
                            Surface(
                                onClick = {
                                    deletionEffectScope.launch {
                                        listState.animateScrollToItem(presentationMessages.lastIndex + 1)
                                        pendingNewMessageCount = 0
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                tonalElevation = 6.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(14.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("$pendingNewMessageCount new")
                                }
                            }
                        }
                    }
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
                onMentionCinder = onMentionCinder,
                reducedMotion = feedbackPreferences.reducedMotionEnabled,
            )
        }
    }
    if (showAppearance) {
        ChatAppearanceDialog(
            state = appearanceState,
            onBackgroundSelected = onBackgroundSelected,
            onReset = onResetAppearance,
            onDismiss = { showAppearance = false },
        )
    }
}

@Composable
private fun RemoteAiParticipantControls(
    state: RemoteChatUiState,
    room: RemoteCachedRoom?,
    onConfigurationChanged: (Boolean, Boolean) -> Unit,
    onCinderParticipationChanged: (Boolean) -> Unit,
    onCinderModeChanged: (RemoteCinderParticipationMode) -> Unit,
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
        val cinderParticipant = state.cinderParticipant
        when {
            cinderParticipant == null -> {
                Text("Loading Cinder participant status…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            cinderParticipant.active -> {
                Text("Cinder • OpenClaw remote AI", fontWeight = FontWeight.SemiBold)
                Text("Choose how Cinder participates in this room.")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RemoteCinderParticipationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = cinderParticipant.mode == mode,
                            onClick = { onCinderModeChanged(mode) },
                            enabled = cinderParticipant.canManage && !state.isActionRunning,
                            label = { Text(mode.cinderModeLabel()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    cinderParticipant.mode.cinderModeExplanation(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Removing Cinder stops future response jobs and keeps existing room history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cinderParticipant.canManage) {
                    OutlinedButton(
                        onClick = { onCinderParticipationChanged(false) },
                        enabled = !state.isActionRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Remove Cinder from conversation")
                    }
                }
            }
            else -> {
                Text("Cinder is not in this conversation.")
                if (cinderParticipant.canManage) {
                    OutlinedButton(
                        onClick = { onCinderParticipationChanged(true) },
                        enabled = !state.isActionRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add Cinder")
                    }
                }
            }
        }
        HorizontalDivider()
        if (configuration == null) {
            Text("Loading phone-local AI settings…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    }
}

private fun RemoteCinderParticipationMode.cinderModeLabel(): String = when (this) {
    RemoteCinderParticipationMode.SILENT -> "Silent"
    RemoteCinderParticipationMode.MENTION -> "Mention"
    RemoteCinderParticipationMode.AUTO -> "Auto"
}

private fun RemoteCinderParticipationMode.cinderModeExplanation(): String = when (this) {
    RemoteCinderParticipationMode.SILENT ->
        "Cinder stays present and receives authorized room context, but does not reply."
    RemoteCinderParticipationMode.MENTION ->
        "Cinder replies to a normalized @Cinder mention or a direct reply to one of Cinder's messages."
    RemoteCinderParticipationMode.AUTO ->
        "Cinder may join naturally and can send authorized proactive room messages."
}

@Composable
private fun RemoteMessageBubble(
    message: RemoteCachedMessage,
    repliedMessage: RemoteCachedMessage?,
    repliedSenderDisplayName: String?,
    isCurrentAccount: Boolean,
    bubbleColor: Color,
    bubbleContentColor: Color,
    bubbleShape: Shape,
    canDeleteForEveryone: Boolean,
    supportsRemoteInteractions: Boolean,
    selectedReaction: String?,
    senderDisplayName: String?,
    senderNameColor: Color,
    senderProfile: RemoteCachedProfile?,
    showIdentityGutter: Boolean,
    showSenderName: Boolean,
    showAvatar: Boolean,
    renderAsDeleted: Boolean,
    playDeletionEffect: Boolean,
    isLocalDeletionEffect: Boolean,
    reducedMotion: Boolean,
    onReply: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    attachmentDownloads: Map<String, RemoteAttachmentDownloadUi>,
    onDownloadAttachment: (RemoteAttachmentId, Boolean) -> Unit,
    onDownloadReplyThumbnail: (RemoteCachedMessage, RemoteAttachmentId) -> Unit,
    onCancelAttachmentDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onJumpToReply: (RemoteMessageId) -> Unit,
    feedbackPreferences: ChatFeedbackPreferences,
    feedbackController: AndroidChatFeedbackController,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val availableActions = if (isLocalDeletionEffect) {
        emptyList()
    } else {
        remoteMessageActions(
            messageDeleted = renderAsDeleted,
            isCurrentAccount = isCurrentAccount,
            supportsRemoteInteractions = supportsRemoteInteractions,
        )
    }
    var showMessageActions by rememberSaveable(message.messageId.raw) { mutableStateOf(false) }
    var showEmojiPicker by rememberSaveable(message.messageId.raw) { mutableStateOf(false) }
    var showEditDialog by rememberSaveable(message.messageId.raw) { mutableStateOf(false) }
    var editText by rememberSaveable(message.messageId.raw) { mutableStateOf(message.body) }
    var showDeleteDialog by rememberSaveable(message.messageId.raw) { mutableStateOf(false) }
    val toggleReactionWithFeedback: (String) -> Unit = { emoji ->
        feedbackController.play(ChatSoundCue.REACTION, feedbackPreferences.soundsEnabled)
        if (feedbackPreferences.hapticsEnabled) {
            hapticFeedback.performHapticFeedback(
                androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm,
            )
        }
        onToggleReaction(emoji)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maximumBubbleWidth = maxWidth * 0.82f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isCurrentAccount) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (showIdentityGutter) {
                Box(
                    modifier = Modifier.width(32.dp).height(32.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (showAvatar) {
                        RemoteProfileAvatar(
                            profile = senderProfile,
                            displayName = senderDisplayName ?: "Group member",
                            size = 30.dp,
                        )
                    }
                }
                Spacer(Modifier.width(5.dp))
            }
            Box(modifier = Modifier.widthIn(max = maximumBubbleWidth)) {
                Column(
                    horizontalAlignment = if (isCurrentAccount) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = bubbleShape,
                        color = bubbleColor,
                        contentColor = bubbleContentColor,
                        modifier = Modifier.combinedClickable(
                            enabled = availableActions.isNotEmpty(),
                            onClick = {},
                            onLongClickLabel = "Message reactions and options",
                            onLongClick = {
                                if (feedbackPreferences.hapticsEnabled) {
                                    hapticFeedback.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                }
                                showMessageActions = true
                            },
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                            if (renderAsDeleted) {
                                RemoteConfirmedDeletedMessageEffect(
                                    playEffect = playDeletionEffect,
                                    reducedMotion = reducedMotion,
                                )
                                if (!isLocalDeletionEffect) {
                                    Text(
                                        text = remoteMessageTimestamp(message),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = bubbleContentColor.copy(alpha = 0.72f),
                                    )
                                }
                            } else {
                                if (showSenderName && senderDisplayName != null) {
                                    Text(
                                        text = senderDisplayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = senderNameColor,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                }
                                message.replyToMessageId?.let { replyId ->
                                    RemoteMessageReplyPreview(
                                        repliedMessage = repliedMessage,
                                        senderDisplayName = repliedSenderDisplayName,
                                        downloads = attachmentDownloads,
                                        onDownloadThumbnail = onDownloadReplyThumbnail,
                                        onClick = { onJumpToReply(replyId) },
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                if (message.body.isNotBlank()) Text(message.body)
                                RemoteMessageAttachmentGallery(
                                    attachments = message.attachments,
                                    downloads = attachmentDownloads,
                                    onDownload = onDownloadAttachment,
                                    onCancelDownload = onCancelAttachmentDownload,
                                )
                                if (message.editedAt != null) {
                                    Text("Edited", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    text = remoteMessageTimestamp(message),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = bubbleContentColor.copy(alpha = 0.72f),
                                )
                                if (isCurrentAccount || message.deliveryState != RemoteMessageDeliveryState.SENT) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = remoteMessageDeliveryLabel(message),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (message.deliveryState == RemoteMessageDeliveryState.FAILED) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            bubbleContentColor.copy(alpha = 0.72f)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (!renderAsDeleted && supportsRemoteInteractions) {
                        RemoteMessageReactionSummary(
                            reactionCounts = message.reactionCounts,
                            selectedReaction = selectedReaction,
                            onToggleReaction = toggleReactionWithFeedback,
                        )
                    }
                }
                DropdownMenu(
                    expanded = showMessageActions,
                    onDismissRequest = { showMessageActions = false },
                ) {
                    if (!renderAsDeleted && supportsRemoteInteractions) {
                    RemoteQuickReactionBar(
                        selectedReaction = selectedReaction,
                        onReactionSelected = { emoji ->
                            showMessageActions = false
                            toggleReactionWithFeedback(emoji)
                        },
                        onShowAllReactions = {
                            showMessageActions = false
                            showEmojiPicker = true
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }
                    availableActions.forEach { action ->
                    val actionIcon = when (action) {
                        RemoteMessageAction.REPLY -> Icons.AutoMirrored.Filled.Reply
                        RemoteMessageAction.COPY -> Icons.Default.ContentCopy
                        RemoteMessageAction.EDIT -> Icons.Default.Edit
                        RemoteMessageAction.DELETE -> Icons.Default.Delete
                    }
                        DropdownMenuItem(
                        text = { Text(action.label) },
                        leadingIcon = {
                            Icon(
                                imageVector = actionIcon,
                                contentDescription = null,
                                tint = if (action == RemoteMessageAction.DELETE) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        onClick = {
                            showMessageActions = false
                            when (action) {
                                RemoteMessageAction.REPLY -> onReply()
                                RemoteMessageAction.COPY -> coroutineScope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("Synapse message", message.body)),
                                    )
                                }
                                RemoteMessageAction.EDIT -> {
                                    editText = message.body
                                    showEditDialog = true
                                }
                                RemoteMessageAction.DELETE -> showDeleteDialog = true
                            }
                        },
                        )
                    }
                }
            }
        }
    }
    if (showEmojiPicker) {
        RemoteEmojiReactionPicker(
            onDismiss = { showEmojiPicker = false },
            onEmojiPicked = { emoji ->
                showEmojiPicker = false
                toggleReactionWithFeedback(emoji)
            },
        )
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
            title = { Text("Delete message") },
            text = {
                Text(
                    if (canDeleteForEveryone) {
                        "Remove it only from this phone, or replace it with a deletion marker for everyone."
                    } else {
                        "This removes the message only from this phone. Other people keep their copy."
                    },
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (canDeleteForEveryone) {
                        TextButton(onClick = {
                            onDeleteForEveryone()
                            showDeleteDialog = false
                        }) { Text("Delete for everyone") }
                    }
                    TextButton(onClick = {
                        onDeleteForMe()
                        showDeleteDialog = false
                    }) { Text("Delete for me") }
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RemoteQuickReactionBar(
    selectedReaction: String?,
    onReactionSelected: (String) -> Unit,
    onShowAllReactions: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DEFAULT_REMOTE_QUICK_REACTIONS.forEach { emoji ->
            val isSelected = emoji == selectedReaction
            Surface(
                onClick = { onReactionSelected(emoji) },
                modifier = Modifier
                    .size(38.dp)
                    .semantics {
                        contentDescription = "React with ${remoteReactionAccessibilityLabel(emoji)}"
                        selected = isSelected
                    },
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = emoji, fontSize = 21.sp)
                }
            }
        }
        IconButton(
            onClick = onShowAllReactions,
            modifier = Modifier.size(38.dp),
        ) {
            Icon(Icons.Default.AddReaction, contentDescription = "Choose another reaction")
        }
    }
}

@Composable
private fun RemoteMessageReactionSummary(
    reactionCounts: Map<String, Int>,
    selectedReaction: String?,
    onToggleReaction: (String) -> Unit,
) {
    val presentation = remoteReactionSummaryPresentation(reactionCounts, selectedReaction)
    if (presentation.reactions.isEmpty()) return
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presentation.reactions.forEach { reaction ->
            Surface(
                onClick = { onToggleReaction(reaction.emoji) },
                modifier = Modifier.semantics {
                    contentDescription = buildString {
                        append(remoteReactionAccessibilityLabel(reaction.emoji))
                        append(" reaction, ")
                        append(reaction.count)
                    }
                    selected = reaction.isSelected
                },
                shape = RoundedCornerShape(12.dp),
                color = if (reaction.isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                border = if (reaction.isSelected) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
            ) {
                Text(
                    text = "${reaction.emoji} ${reaction.count}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (presentation.hiddenReactionTypeCount > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "+${presentation.hiddenReactionTypeCount}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RemoteEmojiReactionPicker(
    onDismiss: () -> Unit,
    onEmojiPicked: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "Choose a reaction",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        AndroidView(
            factory = { context ->
                EmojiPickerView(context).apply {
                    emojiGridColumns = 9
                }
            },
            update = { picker ->
                picker.setOnEmojiPickedListener { selection ->
                    onEmojiPicked(selection.emoji)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

internal data class RemoteReactionPresentation(
    val emoji: String,
    val count: Int,
    val isSelected: Boolean,
)

internal data class RemoteReactionSummaryPresentation(
    val reactions: List<RemoteReactionPresentation>,
    val hiddenReactionTypeCount: Int,
)

internal fun remoteReactionSummaryPresentation(
    reactionCounts: Map<String, Int>,
    selectedReaction: String?,
): RemoteReactionSummaryPresentation {
    val orderedReactions = reactionCounts.asSequence()
        .filter { (emoji, count) -> emoji.isNotBlank() && count > 0 }
        .map { (emoji, count) ->
            RemoteReactionPresentation(
                emoji = emoji,
                count = count,
                isSelected = emoji == selectedReaction,
            )
        }
        .sortedWith(
            compareByDescending<RemoteReactionPresentation> { reaction -> reaction.isSelected }
                .thenByDescending { reaction -> reaction.count }
                .thenBy { reaction -> reaction.emoji },
        )
        .toList()
    return RemoteReactionSummaryPresentation(
        reactions = orderedReactions.take(MAXIMUM_VISIBLE_REACTION_TYPES),
        hiddenReactionTypeCount = (orderedReactions.size - MAXIMUM_VISIBLE_REACTION_TYPES).coerceAtLeast(0),
    )
}

internal val DEFAULT_REMOTE_QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "😡")

private fun remoteReactionAccessibilityLabel(emoji: String): String = when (emoji) {
    "👍" -> "thumbs up"
    "❤️" -> "heart"
    "😂" -> "laughing"
    "😮" -> "surprised"
    "😢" -> "sad"
    "😡" -> "angry"
    else -> emoji
}

internal enum class RemoteMessageAction(val label: String) {
    REPLY("Reply"),
    COPY("Copy"),
    EDIT("Edit"),
    DELETE("Delete…"),
}

internal fun remoteMessageActions(
    messageDeleted: Boolean,
    isCurrentAccount: Boolean,
    supportsRemoteInteractions: Boolean = true,
): List<RemoteMessageAction> {
    if (messageDeleted) return listOf(RemoteMessageAction.DELETE)
    return buildList {
        if (supportsRemoteInteractions) add(RemoteMessageAction.REPLY)
        add(RemoteMessageAction.COPY)
        if (isCurrentAccount && supportsRemoteInteractions) add(RemoteMessageAction.EDIT)
        add(RemoteMessageAction.DELETE)
    }
}

internal fun RemoteCachedMessage.displayInstant() = serverCreatedAt ?: clientCreatedAt

internal fun remoteAiSenderDisplayName(message: RemoteCachedMessage): String =
    message.aiParticipantId
        ?.let(RemoteAssistantConversationCatalog::findByParticipantId)
        ?.displayName
        ?: "Remote assistant"

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

internal fun remoteConversationActivityLabel(
    typingNames: List<String>,
    cinderWorkState: RemoteCinderWorkState?,
): String? {
    val uniqueTypingNames = typingNames.filter(String::isNotBlank).distinct()
    val typingLabel = when (uniqueTypingNames.size) {
        0 -> null
        1 -> "${uniqueTypingNames.single()} is typing…"
        2 -> "${uniqueTypingNames[0]} and ${uniqueTypingNames[1]} are typing…"
        else -> "${uniqueTypingNames[0]}, ${uniqueTypingNames[1]} +${uniqueTypingNames.size - 2} are typing…"
    }
    val cinderLabel = when (cinderWorkState) {
        RemoteCinderWorkState.QUEUED,
        RemoteCinderWorkState.THINKING,
        -> "Cinder is thinking…"
        RemoteCinderWorkState.IDLE,
        null,
        -> null
    }
    return listOfNotNull(typingLabel, cinderLabel).takeIf { labels -> labels.isNotEmpty() }?.joinToString(" · ")
}

private const val MAXIMUM_MESSAGE_LENGTH = 4_000
private const val MAXIMUM_VISIBLE_REACTION_TYPES = 4
private val VIDEO_CALL_PERMISSIONS = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.CAMERA,
)
