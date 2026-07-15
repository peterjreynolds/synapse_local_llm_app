package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.application.RemoteChatSessionSynchronizer
import app.synapse.localllm.application.RemoteLocalAiHostStatus
import app.synapse.localllm.application.RemoteLocalAiResponseHost
import app.synapse.localllm.application.RemoteRoomVisibilityTracker
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.remote.AcknowledgeRemoteMessagesCommand
import app.synapse.localllm.domain.remote.CacheRemoteMessagesCommand
import app.synapse.localllm.domain.remote.EnqueueRemoteMessageCommand
import app.synapse.localllm.domain.remote.LoadRemoteMessagesPageCommand
import app.synapse.localllm.domain.remote.OpenRemoteDirectRoomCommand
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAiParticipantGateway
import app.synapse.localllm.domain.remote.RemoteAttachmentGateway
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDirectoryGateway
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteInviteRegistrationCommand
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageDraft
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageOutboxOperation
import app.synapse.localllm.domain.remote.RemoteMessageSearchResult
import app.synapse.localllm.domain.remote.RemoteNotificationPreferences
import app.synapse.localllm.domain.remote.RemoteOutboxState
import app.synapse.localllm.domain.remote.RemotePasswordChangeCommand
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomMuteDuration
import app.synapse.localllm.domain.remote.RemoteSignInCommand
import app.synapse.localllm.domain.remote.RemoteVoiceNoteRecorder
import app.synapse.localllm.domain.remote.ReviseRemoteMessageCommand
import app.synapse.localllm.domain.remote.SearchRemoteMessagesCommand
import app.synapse.localllm.domain.remote.ToggleRemoteReactionCommand
import app.synapse.localllm.domain.remote.UpdateRemoteProfileCommand
import app.synapse.localllm.domain.remote.UpdateRemoteRoomAiConfigurationCommand
import app.synapse.localllm.domain.remote.UpdateRemoteRoomPreferencesCommand
import app.synapse.localllm.domain.remote.UploadRemoteAvatarCommand
import app.synapse.localllm.domain.time.SynapseClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatViewModel(
    private val authenticationGateway: RemoteAuthenticationGateway,
    private val attachmentGateway: RemoteAttachmentGateway,
    private val directoryGateway: RemoteDirectoryGateway,
    private val conversationGateway: RemoteConversationGateway,
    private val remoteAiParticipantGateway: RemoteAiParticipantGateway,
    private val deviceRegistrationGateway: RemoteDeviceRegistrationGateway,
    private val cacheRepository: RemoteChatCacheRepository,
    private val sessionSynchronizer: RemoteChatSessionSynchronizer,
    private val roomVisibilityTracker: RemoteRoomVisibilityTracker,
    private val remoteLocalAiResponseHost: RemoteLocalAiResponseHost,
    private val voiceNoteRecorder: RemoteVoiceNoteRecorder,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
    private val remoteLogoutCleanupTimeoutMillis: Long = REMOTE_LOGOUT_CLEANUP_TIMEOUT_MILLIS,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RemoteChatUiState())
    private val selectedRoomId = MutableStateFlow<RemoteRoomId?>(null)
    private val roomsBeingMarkedRead = mutableSetOf<RemoteRoomId>()
    private var pendingNotificationRoomId: RemoteRoomId? = null
    private var draftSaveJob: Job? = null
    private var readAcknowledgementJob: Job? = null
    private var messageSearchJob: Job? = null
    private var roomAiConfigurationJob: Job? = null
    private var typingHeartbeatJob: Job? = null
    private var typingRoomId: RemoteRoomId? = null
    private val attachmentTransferController = RemoteAttachmentTransferController(
        coroutineScope = viewModelScope,
        attachmentGateway = attachmentGateway,
        voiceNoteRecorder = voiceNoteRecorder,
        idFactory = idFactory,
        clearNotice = { mutableUiState.update { state -> state.copy(notice = null) } },
        publishFailureMessage = ::publishFailureMessage,
    )

    val uiState: StateFlow<RemoteChatUiState> = mutableUiState

    init {
        require(remoteLogoutCleanupTimeoutMillis > 0L) {
            "Remote logout cleanup timeout must be positive."
        }
        viewModelScope.launch {
            attachmentTransferController.state.collect { attachmentState ->
                mutableUiState.update { state ->
                    state.copy(
                        pendingAttachments = attachmentState.pendingAttachments,
                        attachmentDownloads = attachmentState.downloads,
                        isRecordingVoiceNote = attachmentState.isRecordingVoiceNote,
                    )
                }
            }
        }
        observeAuthentication()
    }

    fun signIn(
        username: String,
        password: String,
    ) = launchAction {
        authenticationGateway.signIn(RemoteSignInCommand(username, password))
    }

    fun registerWithInvite(
        username: String,
        displayName: String,
        password: String,
        invitationCode: String,
    ) = launchAction {
        authenticationGateway.registerWithInvite(
            RemoteInviteRegistrationCommand(
                username = username,
                displayName = displayName,
                password = password,
                invitationCode = invitationCode,
            ),
        )
    }

    fun refreshAccountAccess() = launchAction {
        authenticationGateway.refreshAccount()
    }

    fun signOut() = launchAction {
        val account = mutableUiState.value.account
        if (account?.state != RemoteAccountState.ACTIVE) {
            authenticationGateway.signOut()
            return@launchAction
        }
        runCatching { directoryGateway.updatePresence(account.accountUid, online = false) }
        try {
            try {
                withTimeout(remoteLogoutCleanupTimeoutMillis) {
                    deviceRegistrationGateway.removeCurrentDevice(account.accountUid)
                }
            } catch (exception: TimeoutCancellationException) {
                throw RemoteChatException(
                    "Signed out locally, but notification cleanup timed out.",
                    exception,
                )
            }
        } finally {
            withContext(NonCancellable) {
                authenticationGateway.signOut()
            }
        }
    }

    fun changePassword(
        currentPassword: String,
        newPassword: String,
    ) = launchAction(successNotice = "Password changed.") {
        authenticationGateway.changePassword(
            RemotePasswordChangeCommand(currentPassword, newPassword),
        )
    }

    fun updateProfile(
        displayName: String,
        bio: String,
    ) = launchAction(successNotice = "Profile saved.") {
        directoryGateway.updateProfile(
            UpdateRemoteProfileCommand(
                accountUid = requireSignedInAccount().accountUid,
                displayName = displayName,
                bio = bio,
            ),
        )
    }

    fun uploadAvatar(
        sourceUri: String,
        mimeType: String,
    ) = launchAction(successNotice = "Profile photo updated.") {
        directoryGateway.uploadAvatar(
            UploadRemoteAvatarCommand(
                accountUid = requireSignedInAccount().accountUid,
                sourceUri = sourceUri,
                mimeType = mimeType,
            ),
        )
    }

    fun openDirectRoom(targetUid: RemoteProfileUid) = launchAction {
        val accountUid = requireSignedInAccount().accountUid
        require(targetUid.raw != accountUid.raw) { "Choose another person for a direct chat." }
        val receipt = conversationGateway.openDirectRoom(
            OpenRemoteDirectRoomCommand(accountUid, targetUid),
        )
        selectRoom(receipt.roomId)
    }

    fun selectRoom(roomId: RemoteRoomId?) {
        val previousRoomId = selectedRoomId.value
        if (previousRoomId != roomId) {
            resetAttachmentTransfers()
            stopTyping(previousRoomId)
            draftSaveJob?.cancel()
            readAcknowledgementJob?.cancel()
            roomAiConfigurationJob?.cancel()
        }
        selectedRoomId.value = roomId
        roomVisibilityTracker.setSelectedRoom(roomId)
        mutableUiState.update { state ->
            state.copy(
                selectedRoomId = roomId,
                composerText = "",
                pendingAttachments = emptyList(),
                attachmentDownloads = emptyMap(),
                isRecordingVoiceNote = false,
                replyToMessageId = null,
                typingParticipantUids = emptyList(),
                hasReachedMessageStart = false,
                messageToRevealId = null,
                roomAiConfiguration = null,
                notice = null,
            )
        }
        roomId?.let(::markSelectedRoomRead)
        roomId?.let(::loadRoomAiConfiguration)
    }

    fun openNotificationRoom(roomId: RemoteRoomId?) {
        roomId ?: return
        pendingNotificationRoomId = roomId
        openPendingNotificationRoomIfAvailable(mutableUiState.value.rooms)
    }

    fun searchCachedMessages(query: String) {
        val boundedQuery = query.take(MAXIMUM_MESSAGE_SEARCH_QUERY_LENGTH)
        messageSearchJob?.cancel()
        mutableUiState.update { state ->
            state.copy(
                messageSearchQuery = boundedQuery,
                messageSearchResults = emptyList(),
                isSearchingMessages = boundedQuery.isNotBlank(),
            )
        }
        if (boundedQuery.isBlank()) return
        val accountUid = mutableUiState.value.account?.accountUid
        if (accountUid == null) {
            mutableUiState.update { state -> state.copy(isSearchingMessages = false) }
            return
        }
        messageSearchJob = viewModelScope.launch {
            delay(MESSAGE_SEARCH_DEBOUNCE_MILLIS)
            try {
                val results = cacheRepository.searchMessages(
                    SearchRemoteMessagesCommand(accountUid, boundedQuery),
                )
                if (mutableUiState.value.messageSearchQuery == boundedQuery) {
                    mutableUiState.update { state ->
                        state.copy(
                            messageSearchResults = results,
                            isSearchingMessages = false,
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (mutableUiState.value.messageSearchQuery == boundedQuery) {
                    mutableUiState.update { state -> state.copy(isSearchingMessages = false) }
                    publishFailure(exception)
                }
            }
        }
    }

    fun openMessageSearchResult(result: RemoteMessageSearchResult) {
        selectRoom(result.roomId)
        jumpToMessage(result.messageId)
    }

    fun updateRoomPreferences(
        room: RemoteCachedRoom,
        isArchived: Boolean,
        isPinned: Boolean,
        muteDuration: RemoteRoomMuteDuration?,
    ) = launchAction(successNotice = "Conversation preferences saved.") {
        conversationGateway.updateRoomPreferences(
            UpdateRemoteRoomPreferencesCommand(
                accountUid = requireSignedInAccount().accountUid,
                roomId = room.roomId,
                isArchived = isArchived,
                isPinned = isPinned,
                muteDuration = muteDuration,
            ),
        )
    }

    fun updateNotificationPreferences(preferences: RemoteNotificationPreferences) =
        launchAction(successNotice = "Notification preferences saved.") {
            val accountUid = requireSignedInAccount().accountUid
            val savedPreferences = conversationGateway.updateNotificationPreferences(accountUid, preferences)
            mutableUiState.update { state -> state.copy(notificationPreferences = savedPreferences) }
        }

    fun updateRoomAiConfiguration(
        localAiEnabled: Boolean,
        localAiAutoResponse: Boolean,
    ) = launchAction(successNotice = if (localAiEnabled) "Synapse AI participant updated." else "Synapse AI removed.") {
        val state = mutableUiState.value
        val account = requireSignedInAccount()
        val roomId = state.selectedRoomId ?: throw RemoteChatException("Select a conversation first.")
        val designatedDeviceId = state.roomAiConfiguration
            ?.takeIf { configuration -> configuration.localAiHostUid == account.accountUid }
            ?.localAiHostDeviceId
            ?: state.currentDeviceId
        if (localAiEnabled && designatedDeviceId == null) {
            throw RemoteChatException("Register this device for notifications before making it the local AI host.")
        }
        val configuration = remoteAiParticipantGateway.updateRoomConfiguration(
            UpdateRemoteRoomAiConfigurationCommand(
                accountUid = account.accountUid,
                roomId = roomId,
                localAiEnabled = localAiEnabled,
                localAiAutoResponse = localAiEnabled && localAiAutoResponse,
                localAiHostDeviceId = if (localAiEnabled) designatedDeviceId else null,
            ),
        )
        if (selectedRoomId.value == roomId) {
            mutableUiState.update { current -> current.copy(roomAiConfiguration = configuration) }
        }
    }

    fun insertRemoteSynapseMention() {
        if (mutableUiState.value.roomAiConfiguration?.localAiEnabled != true) {
            publishFailureMessage("Add Synapse to this conversation before mentioning it.")
            return
        }
        updateComposerText(
            mutableUiState.value.composerText
                .takeIf { text -> text.trimStart().startsWith("@Synapse", ignoreCase = true) }
                ?: "@Synapse ${mutableUiState.value.composerText.trimStart()}",
        )
    }

    fun sendMessage(body: String) = launchAction {
        val normalizedBody = body.trim()
        val pendingAttachments = attachmentTransferController.state.value.pendingAttachments
        require(
            normalizedBody.length <= MESSAGE_BODY_LIMIT &&
                (normalizedBody.isNotEmpty() || pendingAttachments.isNotEmpty()),
        ) {
            "Message must contain text or an attachment."
        }
        require(pendingAttachments.all { attachment -> attachment.state == RemoteAttachmentTransferState.READY }) {
            "Wait for every attachment upload to finish or remove failed uploads."
        }
        val account = requireSignedInAccount()
        val roomId = selectedRoomId.value ?: throw IllegalArgumentException("Open a conversation first.")
        val attachments = attachmentTransferController.readyAttachments()
        require(attachments.size == pendingAttachments.size) { "A ready attachment is missing its upload receipt." }
        val messageId = attachmentTransferController.messageIdForSend {
            RemoteMessageId(idFactory.createChatMessageId().raw)
        }
        val idempotencyKey = RemoteIdempotencyKey(messageId.raw)
        val createdAt = clock.now()
        val replyToMessageId = mutableUiState.value.replyToMessageId
        cacheRepository.enqueueMessage(
            EnqueueRemoteMessageCommand(
                message = RemoteCachedMessage(
                    accountUid = account.accountUid,
                    roomId = roomId,
                    messageId = messageId,
                    idempotencyKey = idempotencyKey,
                    senderUid = RemoteProfileUid(account.accountUid.raw),
                    authorKind = HUMAN_AUTHOR_KIND,
                    body = normalizedBody,
                    attachments = attachments,
                    replyToMessageId = replyToMessageId,
                    editedAt = null,
                    deletedAt = null,
                    revision = 1,
                    reactionCounts = emptyMap(),
                    deliveredToCount = 0,
                    readByCount = 0,
                    deliveryState = RemoteMessageDeliveryState.PENDING,
                    clientCreatedAt = createdAt,
                    serverCreatedAt = null,
                    failureReason = null,
                ),
                outboxOperation = RemoteMessageOutboxOperation(
                    accountUid = account.accountUid,
                    operationId = "send-${messageId.raw}",
                    roomId = roomId,
                    messageId = messageId,
                    idempotencyKey = idempotencyKey,
                    senderUid = RemoteProfileUid(account.accountUid.raw),
                    body = normalizedBody,
                    attachments = attachments,
                    replyToMessageId = replyToMessageId,
                    state = RemoteOutboxState.PENDING,
                    attemptCount = 0,
                    createdAt = createdAt,
                    lastAttemptAt = null,
                    failureReason = null,
                ),
            ),
        )
        cacheRepository.clearDraft(account.accountUid, roomId)
        attachmentTransferController.completeSend()
        mutableUiState.update { state ->
            state.copy(composerText = "", replyToMessageId = null)
        }
        stopTyping(roomId)
    }

    fun addAttachment(
        sourceUri: String,
        audioDurationMillis: Long? = null,
        isVoiceNote: Boolean = false,
    ) {
        attachmentTransferController.addAttachment(
            accountUid = mutableUiState.value.account?.accountUid,
            roomId = selectedRoomId.value,
            sourceUri = sourceUri,
            audioDurationMillis = audioDurationMillis,
            isVoiceNote = isVoiceNote,
        )
    }

    fun startVoiceNoteRecording() {
        attachmentTransferController.startVoiceNoteRecording(selectedRoomId.value)
    }

    fun finishVoiceNoteRecording() {
        attachmentTransferController.finishVoiceNoteRecording(
            accountUid = mutableUiState.value.account?.accountUid,
            roomId = selectedRoomId.value,
        )
    }

    fun cancelVoiceNoteRecording() {
        attachmentTransferController.cancelVoiceNoteRecording()
    }

    fun reportVoiceNotePermissionDenied() {
        publishFailureMessage("Microphone permission was denied. Grant it to record voice notes.")
    }

    fun retryAttachment(attachmentId: RemoteAttachmentId) {
        attachmentTransferController.retryAttachment(
            accountUid = mutableUiState.value.account?.accountUid,
            roomId = selectedRoomId.value,
            attachmentId = attachmentId,
        )
    }

    fun cancelAttachment(attachmentId: RemoteAttachmentId) {
        attachmentTransferController.cancelAttachment(
            accountUid = mutableUiState.value.account?.accountUid,
            roomId = selectedRoomId.value,
            attachmentId = attachmentId,
        )
    }

    fun downloadAttachment(
        message: RemoteCachedMessage,
        attachmentId: RemoteAttachmentId,
        thumbnail: Boolean,
    ) {
        attachmentTransferController.downloadAttachment(
            accountUid = mutableUiState.value.account?.accountUid,
            message = message,
            attachmentId = attachmentId,
            thumbnail = thumbnail,
        )
    }

    fun cancelAttachmentDownload(
        attachmentId: RemoteAttachmentId,
        thumbnail: Boolean,
    ) {
        attachmentTransferController.cancelDownload(attachmentId, thumbnail)
    }

    fun updateComposerText(body: String) {
        val normalizedBody = body.take(MESSAGE_BODY_LIMIT)
        mutableUiState.update { state -> state.copy(composerText = normalizedBody) }
        val accountUid = mutableUiState.value.account?.accountUid ?: return
        val roomId = selectedRoomId.value ?: return
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DEBOUNCE_MILLIS)
            try {
                if (normalizedBody.isBlank()) {
                    cacheRepository.clearDraft(accountUid, roomId)
                } else {
                    cacheRepository.saveDraft(RemoteMessageDraft(accountUid, roomId, normalizedBody, clock.now()))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                publishFailure(exception)
            }
        }
        if (normalizedBody.isBlank()) stopTyping(roomId) else startTypingHeartbeat(accountUid, roomId)
    }

    fun replyToMessage(messageId: RemoteMessageId) {
        mutableUiState.update { state -> state.copy(replyToMessageId = messageId) }
    }

    fun cancelReply() {
        mutableUiState.update { state -> state.copy(replyToMessageId = null) }
    }

    fun editMessage(
        message: RemoteCachedMessage,
        body: String,
    ) = launchAction {
        val normalizedBody = body.trim()
        require(normalizedBody.isNotEmpty() && normalizedBody.length <= MESSAGE_BODY_LIMIT) {
            "Message must contain 1-$MESSAGE_BODY_LIMIT characters."
        }
        conversationGateway.editMessage(
            ReviseRemoteMessageCommand(
                accountUid = requireSignedInAccount().accountUid,
                roomId = message.roomId,
                messageId = message.messageId,
                mutationId = "edit-${idFactory.createChatMessageId().raw}",
                expectedRevision = message.revision,
                body = normalizedBody,
            ),
        )
    }

    fun deleteMessage(message: RemoteCachedMessage) = launchAction {
        conversationGateway.deleteMessage(
            ReviseRemoteMessageCommand(
                accountUid = requireSignedInAccount().accountUid,
                roomId = message.roomId,
                messageId = message.messageId,
                mutationId = "delete-${idFactory.createChatMessageId().raw}",
                expectedRevision = message.revision,
            ),
        )
    }

    fun toggleReaction(
        message: RemoteCachedMessage,
        emoji: String,
    ) = launchAction {
        val reacted = emoji !in mutableUiState.value.ownReactions[message.messageId].orEmpty()
        conversationGateway.toggleReaction(
            ToggleRemoteReactionCommand(
                accountUid = requireSignedInAccount().accountUid,
                roomId = message.roomId,
                messageId = message.messageId,
                emoji = emoji,
                reacted = reacted,
            ),
        )
        mutableUiState.update { state ->
            val reactions = state.ownReactions[message.messageId].orEmpty().toMutableSet()
            if (reacted) reactions += emoji else reactions -= emoji
            state.copy(ownReactions = state.ownReactions + (message.messageId to reactions))
        }
    }

    fun loadOlderMessages() = launchAction {
        val state = mutableUiState.value
        if (state.hasReachedMessageStart || state.isLoadingOlderMessages) return@launchAction
        val accountUid = requireSignedInAccount().accountUid
        val roomId = selectedRoomId.value ?: return@launchAction
        val oldestMessage = state.messages.firstOrNull { message -> message.serverCreatedAt != null }
            ?: return@launchAction
        mutableUiState.update { current -> current.copy(isLoadingOlderMessages = true) }
        try {
            val page = conversationGateway.loadMessagesBefore(
                LoadRemoteMessagesPageCommand(
                    accountUid = accountUid,
                    roomId = roomId,
                    beforeCreatedAt = requireNotNull(oldestMessage.serverCreatedAt),
                    beforeMessageId = oldestMessage.messageId,
                    limit = MESSAGE_PAGE_LIMIT,
                ),
            )
            cacheRepository.cacheMessages(CacheRemoteMessagesCommand(accountUid, page.messages))
            mutableUiState.update { current -> current.copy(hasReachedMessageStart = page.reachedStart) }
        } finally {
            mutableUiState.update { current -> current.copy(isLoadingOlderMessages = false) }
        }
    }

    fun jumpToMessage(messageId: RemoteMessageId) = launchAction {
        val state = mutableUiState.value
        if (state.messages.any { message -> message.messageId == messageId }) {
            mutableUiState.update { current -> current.copy(messageToRevealId = messageId) }
            return@launchAction
        }
        val accountUid = requireSignedInAccount().accountUid
        val roomId = selectedRoomId.value ?: return@launchAction
        val message = conversationGateway.loadMessage(accountUid, roomId, messageId)
            ?: throw RemoteChatException("The replied message is no longer available.")
        cacheRepository.cacheMessages(CacheRemoteMessagesCommand(accountUid, listOf(message)))
        mutableUiState.update { current -> current.copy(messageToRevealId = messageId) }
    }

    fun consumeMessageReveal() {
        mutableUiState.update { state -> state.copy(messageToRevealId = null) }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun observeAuthentication() {
        viewModelScope.launch {
            authenticationGateway.authenticationState.collectLatest { authenticationState ->
                when (authenticationState) {
                    RemoteAuthenticationState.SignedOut -> handleSignedOut()
                    RemoteAuthenticationState.Resolving -> handleResolvingAuthentication()
                    is RemoteAuthenticationState.InvalidSession ->
                        handleInvalidSession(authenticationState)
                    is RemoteAuthenticationState.SignedIn -> {
                        if (
                            authenticationState.account.state == RemoteAccountState.ACTIVE &&
                            !authenticationState.account.mustChangePassword
                        ) {
                            runSignedInSession(authenticationState.account)
                        } else {
                            runRestrictedAccountSession(authenticationState.account)
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleResolvingAuthentication() {
        clearAttachmentSession()
        cacheRepository.clearActiveAccount()
        mutableUiState.value = RemoteChatUiState(
            authenticationState = RemoteAuthenticationState.Resolving,
        )
    }

    private suspend fun handleInvalidSession(state: RemoteAuthenticationState.InvalidSession) {
        clearAttachmentSession()
        cacheRepository.clearActiveAccount()
        mutableUiState.value = RemoteChatUiState(
            authenticationState = state,
            notice = state.userMessage,
        )
    }

    private suspend fun handleSignedOut() {
        clearAttachmentSession()
        cacheRepository.clearActiveAccount()
        selectedRoomId.value = null
        roomVisibilityTracker.setSelectedRoom(null)
        mutableUiState.value = RemoteChatUiState(
            authenticationState = RemoteAuthenticationState.SignedOut,
        )
    }

    private suspend fun runSignedInSession(account: RemoteAuthenticatedAccount): Nothing {
        cacheRepository.activateAccount(account.accountUid)
        mutableUiState.update { state ->
            state.copy(
                authenticationState = RemoteAuthenticationState.SignedIn(account),
                account = account,
                notice = null,
            )
        }
        try {
            supervisorScope {
                launch { cacheRepository.observeProfiles().collect { profiles ->
                    mutableUiState.update { state -> state.copy(profiles = profiles) }
                } }
                launch { cacheRepository.observeRooms().collect { rooms ->
                    val currentSelectedRoomId = selectedRoomId.value
                    val selectedRoomWasRemoved = currentSelectedRoomId != null &&
                        mutableUiState.value.rooms.any { room -> room.roomId == currentSelectedRoomId } &&
                        rooms.none { room -> room.roomId == currentSelectedRoomId }
                    mutableUiState.update { state -> state.copy(rooms = rooms) }
                    if (selectedRoomWasRemoved) selectRoom(null)
                    openPendingNotificationRoomIfAvailable(rooms)
                    rooms.firstOrNull { room ->
                        room.roomId == selectedRoomId.value && room.unreadCount > 0
                    }?.roomId?.let(::markSelectedRoomRead)
                } }
                launch {
                    selectedRoomId.flatMapLatest { roomId ->
                        roomId?.let(cacheRepository::observeMessages) ?: flowOf(emptyList())
                    }.collect { messages ->
                        mutableUiState.update { state -> state.copy(messages = messages) }
                        selectedRoomId.value?.let { roomId ->
                            scheduleReadAcknowledgement(account.accountUid, roomId, messages)
                        }
                    }
                }
                launch {
                    selectedRoomId.flatMapLatest { roomId ->
                        roomId?.let(cacheRepository::observeDraft) ?: flowOf(null)
                    }.collect { draft ->
                        mutableUiState.update { state -> state.copy(composerText = draft?.body.orEmpty()) }
                    }
                }
                launch {
                    selectedRoomId.flatMapLatest { roomId ->
                        if (roomId == null) flowOf(emptyList()) else {
                            conversationGateway.observeTypingParticipants(account.accountUid, roomId)
                        }
                    }.catch { failure ->
                        if (failure is CancellationException) throw failure
                        publishFailure(failure)
                        emit(emptyList())
                    }.collect { participants ->
                        mutableUiState.update { state ->
                            state.copy(typingParticipantUids = participants.map { participant -> participant.profileUid })
                        }
                    }
                }
                launch {
                    sessionSynchronizer.synchronize(
                        accountUid = account.accountUid,
                        selectedRoomId = selectedRoomId,
                        reportFailure = ::publishFailureMessage,
                    )
                }
                launchStartupMutation("Could not update online presence.") {
                    directoryGateway.updatePresence(account.accountUid, online = true)
                }
                launch {
                    try {
                        val registration = deviceRegistrationGateway.registerCurrentDevice(account.accountUid)
                        mutableUiState.update { state -> state.copy(currentDeviceId = registration.deviceId) }
                        remoteLocalAiResponseHost.synchronize(
                            accountUid = account.accountUid,
                            deviceId = registration.deviceId,
                            reportStatus = { status ->
                                mutableUiState.update { state -> state.copy(localAiHostStatus = status) }
                            },
                        )
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        publishFailureMessage(
                            (exception as? RemoteChatException)?.userMessage
                                ?: "Could not enable notifications or phone-local AI hosting for this device.",
                        )
                    }
                }
                launch {
                    try {
                        val preferences = conversationGateway.getNotificationPreferences(account.accountUid)
                        mutableUiState.update { state -> state.copy(notificationPreferences = preferences) }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        publishFailureMessage("Could not load notification preferences.")
                    }
                }
                awaitCancellation()
            }
        } finally {
            resetAttachmentTransfers()
            attachmentGateway.clearAccountCache(account.accountUid)
            stopTyping(selectedRoomId.value)
            draftSaveJob?.cancel()
            messageSearchJob?.cancel()
            roomAiConfigurationJob?.cancel()
            readAcknowledgementJob?.cancel()
            cacheRepository.clearActiveAccount()
            selectedRoomId.value = null
            roomVisibilityTracker.setSelectedRoom(null)
            mutableUiState.update { state ->
                state.copy(
                    account = null,
                    profiles = emptyList(),
                    rooms = emptyList(),
                    selectedRoomId = null,
                    messages = emptyList(),
                    composerText = "",
                    pendingAttachments = emptyList(),
                    attachmentDownloads = emptyMap(),
                    replyToMessageId = null,
                    ownReactions = emptyMap(),
                    typingParticipantUids = emptyList(),
                    messageSearchQuery = "",
                    messageSearchResults = emptyList(),
                    isSearchingMessages = false,
                    notificationPreferences = RemoteNotificationPreferences(),
                    currentDeviceId = null,
                    roomAiConfiguration = null,
                    localAiHostStatus = RemoteLocalAiHostStatus.Idle,
                    isActionRunning = false,
                )
            }
        }
    }

    private suspend fun runRestrictedAccountSession(account: RemoteAuthenticatedAccount): Nothing {
        cacheRepository.clearActiveAccount()
        selectedRoomId.value = null
        roomVisibilityTracker.setSelectedRoom(null)
        mutableUiState.value = RemoteChatUiState(
            authenticationState = RemoteAuthenticationState.SignedIn(account),
            account = account,
        )
        try {
            awaitCancellation()
        } finally {
            mutableUiState.update { state ->
                state.copy(
                    account = null,
                    isActionRunning = false,
                )
            }
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchStartupMutation(
        fallbackMessage: String,
        mutation: suspend () -> Unit,
    ) = launch {
        try {
            mutation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            publishFailureMessage((exception as? RemoteChatException)?.userMessage ?: fallbackMessage)
        }
    }

    private fun loadRoomAiConfiguration(roomId: RemoteRoomId) {
        val accountUid = mutableUiState.value.account?.accountUid ?: return
        roomAiConfigurationJob?.cancel()
        roomAiConfigurationJob = viewModelScope.launch {
            var reportedUnavailable = false
            while (selectedRoomId.value == roomId) {
                try {
                    val configuration = remoteAiParticipantGateway.getRoomConfiguration(accountUid, roomId)
                    if (selectedRoomId.value == roomId) {
                        mutableUiState.update { state -> state.copy(roomAiConfiguration = configuration) }
                    }
                    reportedUnavailable = false
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    mutableUiState.update { state ->
                        state.copy(
                            roomAiConfiguration = state.roomAiConfiguration?.copy(localAiHostAvailable = false),
                        )
                    }
                    if (!reportedUnavailable) {
                        publishFailureMessage(
                            (exception as? RemoteChatException)?.userMessage
                                ?: "Could not refresh this conversation's AI participant status.",
                        )
                        reportedUnavailable = true
                    }
                }
                delay(ROOM_AI_CONFIGURATION_REFRESH_MILLIS)
            }
        }
    }

    private fun launchAction(
        successNotice: String? = null,
        action: suspend () -> Unit,
    ) {
        if (mutableUiState.value.isActionRunning) return
        viewModelScope.launch {
            mutableUiState.update { state -> state.copy(isActionRunning = true, notice = null) }
            try {
                action()
                mutableUiState.update { state ->
                    state.copy(isActionRunning = false, notice = successNotice)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(
                        isActionRunning = false,
                        notice = (exception as? RemoteChatException)?.userMessage
                            ?: exception.message
                            ?: "Remote chat operation failed.",
                    )
                }
            }
        }
    }

    private fun requireSignedInAccount(): RemoteAuthenticatedAccount =
        mutableUiState.value.account
            ?.takeIf { account -> account.state == RemoteAccountState.ACTIVE }
            ?.takeIf { account -> !account.mustChangePassword }
            ?: throw RemoteChatException("An active account is required to use remote chat.")

    private fun openPendingNotificationRoomIfAvailable(rooms: List<RemoteCachedRoom>) {
        val roomId = resolveAuthorizedNotificationRoom(pendingNotificationRoomId, rooms) ?: return
        pendingNotificationRoomId = null
        selectRoom(roomId)
    }

    private fun markSelectedRoomRead(roomId: RemoteRoomId) {
        val accountUid = mutableUiState.value.account?.accountUid ?: return
        if (!roomsBeingMarkedRead.add(roomId)) return
        viewModelScope.launch {
            try {
                conversationGateway.markRoomRead(accountUid, roomId)
                acknowledgeMessagesAsRead(accountUid, roomId, mutableUiState.value.messages)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                publishFailure(exception)
            } finally {
                roomsBeingMarkedRead.remove(roomId)
            }
        }
    }

    private fun resetAttachmentTransfers() {
        attachmentTransferController.reset(
            accountUid = mutableUiState.value.account?.accountUid,
            roomId = selectedRoomId.value,
        )
    }

    private suspend fun clearAttachmentSession() {
        val accountUid = mutableUiState.value.account?.accountUid
        resetAttachmentTransfers()
        if (accountUid != null) attachmentTransferController.clearAccountCache(accountUid)
    }

    private fun publishFailure(failure: Throwable) {
        publishFailureMessage(
            (failure as? RemoteChatException)?.userMessage ?: "Remote chat operation failed.",
        )
    }

    private fun publishFailureMessage(userMessage: String) {
        mutableUiState.update { state -> state.copy(notice = userMessage) }
    }

    private fun startTypingHeartbeat(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ) {
        if (typingRoomId == roomId && typingHeartbeatJob?.isActive == true) return
        typingHeartbeatJob?.cancel()
        typingRoomId = roomId
        typingHeartbeatJob = viewModelScope.launch {
            while (selectedRoomId.value == roomId && mutableUiState.value.composerText.isNotBlank()) {
                try {
                    conversationGateway.setTyping(accountUid, roomId, isTyping = true)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    publishFailure(exception)
                    return@launch
                }
                delay(TYPING_HEARTBEAT_MILLIS)
            }
        }
    }

    private fun scheduleReadAcknowledgement(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        messages: List<RemoteCachedMessage>,
    ) {
        readAcknowledgementJob?.cancel()
        readAcknowledgementJob = viewModelScope.launch {
            try {
                acknowledgeMessagesAsRead(accountUid, roomId, messages)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                publishFailure(exception)
            }
        }
    }

    private suspend fun acknowledgeMessagesAsRead(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        messages: List<RemoteCachedMessage>,
    ) {
        messages
            .filter { message -> message.senderUid.raw != accountUid.raw }
            .map { message -> message.messageId }
            .chunked(MAXIMUM_ACKNOWLEDGEMENT_SIZE)
            .filter { messageIds -> messageIds.isNotEmpty() }
            .forEach { messageIds ->
                conversationGateway.acknowledgeMessages(
                    AcknowledgeRemoteMessagesCommand(accountUid, roomId, messageIds, read = true),
                )
            }
    }

    private fun stopTyping(roomId: RemoteRoomId?) {
        typingHeartbeatJob?.cancel()
        typingHeartbeatJob = null
        typingRoomId = null
        val accountUid = mutableUiState.value.account?.accountUid ?: return
        roomId ?: return
        viewModelScope.launch {
            try {
                conversationGateway.setTyping(accountUid, roomId, isTyping = false)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Typing documents expire server-side; cleanup is best effort during navigation.
            }
        }
    }

    private companion object {
        const val HUMAN_AUTHOR_KIND = "HUMAN"
        const val MESSAGE_BODY_LIMIT = 4_000
        const val MESSAGE_PAGE_LIMIT = 50
        const val MAXIMUM_MESSAGE_SEARCH_QUERY_LENGTH = 100
        const val MESSAGE_SEARCH_DEBOUNCE_MILLIS = 250L
        const val MAXIMUM_ACKNOWLEDGEMENT_SIZE = 50
        const val DRAFT_SAVE_DEBOUNCE_MILLIS = 300L
        const val TYPING_HEARTBEAT_MILLIS = 5_000L
        const val REMOTE_LOGOUT_CLEANUP_TIMEOUT_MILLIS = 10_000L
        private const val ROOM_AI_CONFIGURATION_REFRESH_MILLIS = 60_000L
    }
}

internal fun resolveAuthorizedNotificationRoom(
    pendingRoomId: RemoteRoomId?,
    rooms: List<RemoteCachedRoom>,
): RemoteRoomId? = pendingRoomId?.takeIf { roomId -> rooms.any { room -> room.roomId == roomId } }

class RemoteChatViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RemoteChatViewModel::class.java)) {
            return modelClass.cast(
                RemoteChatViewModel(
                    authenticationGateway = graph.remoteAuthenticationGateway,
                    attachmentGateway = graph.remoteAttachmentGateway,
                    directoryGateway = graph.remoteDirectoryGateway,
                    conversationGateway = graph.remoteConversationGateway,
                    remoteAiParticipantGateway = graph.remoteAiParticipantGateway,
                    deviceRegistrationGateway = graph.remoteDeviceRegistrationGateway,
                    cacheRepository = graph.remoteChatCacheRepository,
                    sessionSynchronizer = graph.remoteChatSessionSynchronizer,
                    roomVisibilityTracker = graph.remoteRoomVisibilityTracker,
                    remoteLocalAiResponseHost = graph.remoteLocalAiResponseCoordinator,
                    voiceNoteRecorder = graph.remoteVoiceNoteRecorder,
                    idFactory = graph.idFactory,
                    clock = graph.clock,
                ),
            ) ?: throw IllegalArgumentException("Unable to create RemoteChatViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
