package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.application.RemoteChatSessionSynchronizer
import app.synapse.localllm.application.RemoteRoomVisibilityTracker
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.remote.EnqueueRemoteMessageCommand
import app.synapse.localllm.domain.remote.OpenRemoteDirectRoomCommand
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDirectoryGateway
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageOutboxOperation
import app.synapse.localllm.domain.remote.RemoteOutboxState
import app.synapse.localllm.domain.remote.RemotePasswordChangeCommand
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteSignInCommand
import app.synapse.localllm.domain.remote.UpdateRemoteProfileCommand
import app.synapse.localllm.domain.remote.UploadRemoteAvatarCommand
import app.synapse.localllm.domain.time.SynapseClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatViewModel(
    private val authenticationGateway: RemoteAuthenticationGateway,
    private val directoryGateway: RemoteDirectoryGateway,
    private val conversationGateway: RemoteConversationGateway,
    private val deviceRegistrationGateway: RemoteDeviceRegistrationGateway,
    private val cacheRepository: RemoteChatCacheRepository,
    private val sessionSynchronizer: RemoteChatSessionSynchronizer,
    private val roomVisibilityTracker: RemoteRoomVisibilityTracker,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RemoteChatUiState())
    private val selectedRoomId = MutableStateFlow<RemoteRoomId?>(null)
    private val roomsBeingMarkedRead = mutableSetOf<RemoteRoomId>()
    private var pendingNotificationRoomId: RemoteRoomId? = null

    val uiState: StateFlow<RemoteChatUiState> = mutableUiState

    init {
        observeAuthentication()
    }

    fun signIn(
        username: String,
        password: String,
    ) = launchAction {
        authenticationGateway.signIn(RemoteSignInCommand(username, password))
    }

    fun signOut() = launchAction {
        val accountUid = requireSignedInAccount().accountUid
        runCatching { directoryGateway.updatePresence(accountUid, online = false) }
        deviceRegistrationGateway.removeCurrentDevice(accountUid)
        authenticationGateway.signOut()
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
        selectedRoomId.value = roomId
        roomVisibilityTracker.setSelectedRoom(roomId)
        mutableUiState.update { state -> state.copy(selectedRoomId = roomId, notice = null) }
        roomId?.let(::markSelectedRoomRead)
    }

    fun openNotificationRoom(rawRoomId: String?) {
        val roomId = rawRoomId
            ?.takeIf(REMOTE_DIRECT_ROOM_PATTERN::matches)
            ?.let(::RemoteRoomId)
            ?: return
        pendingNotificationRoomId = roomId
        if (mutableUiState.value.account != null) {
            pendingNotificationRoomId = null
            selectRoom(roomId)
        }
    }

    fun sendMessage(body: String) = launchAction {
        val normalizedBody = body.trim()
        require(normalizedBody.isNotEmpty() && normalizedBody.length <= MESSAGE_BODY_LIMIT) {
            "Message must contain 1-$MESSAGE_BODY_LIMIT characters."
        }
        val account = requireSignedInAccount()
        val roomId = selectedRoomId.value ?: throw IllegalArgumentException("Open a conversation first.")
        val messageId = RemoteMessageId(idFactory.createChatMessageId().raw)
        val idempotencyKey = RemoteIdempotencyKey(messageId.raw)
        val createdAt = clock.now()
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
                    state = RemoteOutboxState.PENDING,
                    attemptCount = 0,
                    createdAt = createdAt,
                    lastAttemptAt = null,
                    failureReason = null,
                ),
            ),
        )
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun observeAuthentication() {
        viewModelScope.launch {
            authenticationGateway.authenticationState.collectLatest { authenticationState ->
                when (authenticationState) {
                    RemoteAuthenticationState.SignedOut -> handleSignedOut()
                    is RemoteAuthenticationState.SignedIn -> runSignedInSession(authenticationState.account)
                }
            }
        }
    }

    private suspend fun handleSignedOut() {
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
        pendingNotificationRoomId?.let { roomId ->
            pendingNotificationRoomId = null
            selectRoom(roomId)
        }
        try {
            supervisorScope {
                launch { cacheRepository.observeProfiles().collect { profiles ->
                    mutableUiState.update { state -> state.copy(profiles = profiles) }
                } }
                launch { cacheRepository.observeDirectRooms().collect { rooms ->
                    mutableUiState.update { state -> state.copy(rooms = rooms) }
                    rooms.firstOrNull { room ->
                        room.roomId == selectedRoomId.value && room.unreadCount > 0
                    }?.roomId?.let(::markSelectedRoomRead)
                } }
                launch {
                    selectedRoomId.flatMapLatest { roomId ->
                        roomId?.let(cacheRepository::observeMessages) ?: flowOf(emptyList())
                    }.collect { messages ->
                        mutableUiState.update { state -> state.copy(messages = messages) }
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
                launchStartupMutation("Could not enable notifications for this device.") {
                    deviceRegistrationGateway.registerCurrentDevice(account.accountUid)
                }
                awaitCancellation()
            }
        } finally {
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
        mutableUiState.value.account ?: throw RemoteChatException("Sign in before using remote chat.")

    private fun markSelectedRoomRead(roomId: RemoteRoomId) {
        val accountUid = mutableUiState.value.account?.accountUid ?: return
        if (!roomsBeingMarkedRead.add(roomId)) return
        viewModelScope.launch {
            try {
                conversationGateway.markRoomRead(accountUid, roomId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                publishFailure(exception)
            } finally {
                roomsBeingMarkedRead.remove(roomId)
            }
        }
    }

    private fun publishFailure(failure: Throwable) {
        publishFailureMessage(
            (failure as? RemoteChatException)?.userMessage ?: "Remote chat operation failed.",
        )
    }

    private fun publishFailureMessage(userMessage: String) {
        mutableUiState.update { state -> state.copy(notice = userMessage) }
    }

    private companion object {
        const val HUMAN_AUTHOR_KIND = "HUMAN"
        const val MESSAGE_BODY_LIMIT = 4_000
        val REMOTE_DIRECT_ROOM_PATTERN = Regex("^direct_[a-f0-9]{64}$")
    }
}

class RemoteChatViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RemoteChatViewModel::class.java)) {
            return modelClass.cast(
                RemoteChatViewModel(
                    authenticationGateway = graph.remoteAuthenticationGateway,
                    directoryGateway = graph.remoteDirectoryGateway,
                    conversationGateway = graph.remoteConversationGateway,
                    deviceRegistrationGateway = graph.remoteDeviceRegistrationGateway,
                    cacheRepository = graph.remoteChatCacheRepository,
                    sessionSynchronizer = graph.remoteChatSessionSynchronizer,
                    roomVisibilityTracker = graph.remoteRoomVisibilityTracker,
                    idFactory = graph.idFactory,
                    clock = graph.clock,
                ),
            ) ?: throw IllegalArgumentException("Unable to create RemoteChatViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
