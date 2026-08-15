package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.remote.CreateRemoteInvitationCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteInvitationCreatedReceipt
import app.synapse.localllm.domain.remote.RemoteInvitationGateway
import app.synapse.localllm.domain.remote.RemotePrivacyGateway
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRegisteredDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteAccountUiState(
    val accountUid: RemoteAccountUid? = null,
    val usernameNormalized: String? = null,
    val blockedProfileUids: Set<RemoteProfileUid> = emptySet(),
    val privacyStateVerified: Boolean = false,
    val deletionRequestPending: Boolean = false,
    val registeredDevices: List<RemoteRegisteredDevice> = emptyList(),
    val generatedInvitation: RemoteInvitationCreatedReceipt? = null,
    val isRefreshing: Boolean = false,
    val isActionRunning: Boolean = false,
    val notice: String? = null,
)

class RemoteAccountViewModel(
    private val authenticationGateway: RemoteAuthenticationGateway,
    private val privacyGateway: RemotePrivacyGateway,
    private val deviceRegistrationGateway: RemoteDeviceRegistrationGateway,
    private val invitationGateway: RemoteInvitationGateway,
    private val sessionController: RemoteAccountSessionController,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RemoteAccountUiState())
    private var accountActionJob: Job? = null
    private var accountRefreshJob: Job? = null
    val uiState: StateFlow<RemoteAccountUiState> = mutableUiState

    init {
        observeAccountSession()
    }

    fun refresh() {
        val accountUid = mutableUiState.value.accountUid ?: return
        if (mutableUiState.value.isRefreshing) return
        accountRefreshJob?.cancel()
        accountRefreshJob = viewModelScope.launch {
            sessionController.activeSession
                .filter { token -> token?.accountUid == accountUid }
                .first()
            loadAccountControls(accountUid, includeRegisteredDevices = true)
        }
    }

    fun setUserBlocked(
        targetUid: RemoteProfileUid,
        blocked: Boolean,
    ) = launchAccountAction(if (blocked) "Account blocked." else "Account unblocked.") { accountUid ->
        privacyGateway.setUserBlocked(targetUid, blocked)
        updateForAccount(accountUid) { state ->
            state.copy(
                blockedProfileUids = if (blocked) {
                    state.blockedProfileUids + targetUid
                } else {
                    state.blockedProfileUids - targetUid
                },
            )
        }
    }

    fun requestAccountDeletion(
        currentPassword: String,
        confirmUsername: String,
    ) = launchAccountAction("Account deletion request submitted.") { accountUid ->
        val expectedUsername = mutableUiState.value.usernameNormalized
            ?: throw RemoteChatException("The active account changed. Try again.")
        require(confirmUsername.trim().removePrefix("@") == expectedUsername) {
            "Type @$expectedUsername exactly to confirm this request."
        }
        require(currentPassword.isNotEmpty()) { "Current password cannot be empty." }
        authenticationGateway.reauthenticate(currentPassword)
        privacyGateway.requestAccountDeletion()
        updateForAccount(accountUid) { state -> state.copy(deletionRequestPending = true) }
    }

    fun cancelAccountDeletionRequest() =
        launchAccountAction("Account deletion request cancelled.") { accountUid ->
            privacyGateway.cancelAccountDeletionRequest()
            updateForAccount(accountUid) { state -> state.copy(deletionRequestPending = false) }
        }

    fun removeOwnDevice(deviceId: RemoteDeviceId) =
        launchAccountAction("Registered device removed.") { accountUid ->
            deviceRegistrationGateway.removeOwnDevice(accountUid, deviceId)
            updateForAccount(accountUid) { state ->
                state.copy(registeredDevices = state.registeredDevices.filterNot { it.deviceId == deviceId })
            }
        }

    fun createInvitation() = launchAccountAction("Invite code created.") { accountUid ->
        val receipt = invitationGateway.createInvitation(
            CreateRemoteInvitationCommand(
                intendedLabel = null,
                lifetimeHours = STANDARD_INVITATION_LIFETIME_HOURS,
                maximumUses = 1,
            ),
        )
        updateForAccount(accountUid) { state -> state.copy(generatedInvitation = receipt) }
    }

    fun clearGeneratedInvitation() {
        mutableUiState.update { state -> state.copy(generatedInvitation = null) }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun observeAccountSession() {
        viewModelScope.launch {
            authenticationGateway.authenticationState.collectLatest { authenticationState ->
                accountActionJob?.cancel()
                accountActionJob = null
                accountRefreshJob?.cancel()
                accountRefreshJob = null
                val account = (authenticationState as? RemoteAuthenticationState.SignedIn)?.account
                if (
                    account == null ||
                    account.state != RemoteAccountState.ACTIVE ||
                    account.mustChangePassword
                ) {
                    mutableUiState.value = RemoteAccountUiState()
                    return@collectLatest
                }
                mutableUiState.value = RemoteAccountUiState(
                    accountUid = account.accountUid,
                    usernameNormalized = account.usernameNormalized,
                    isRefreshing = true,
                )
                sessionController.activeSession
                    .filter { token -> token?.accountUid == account.accountUid }
                    .first()
                loadAccountControls(account.accountUid, includeRegisteredDevices = false)
            }
        }
    }

    private suspend fun loadAccountControls(
        accountUid: RemoteAccountUid,
        includeRegisteredDevices: Boolean,
    ) {
        updateForAccount(accountUid) { state -> state.copy(isRefreshing = true, notice = null) }
        var loadFailure: Exception? = null
        try {
            val privacy = privacyGateway.getOwnPrivacyState()
            updateForAccount(accountUid) { state ->
                state.copy(
                    blockedProfileUids = privacy.blockedProfileUids,
                    deletionRequestPending = privacy.deletionRequestPending,
                    privacyStateVerified = true,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            loadFailure = exception
        }
        if (includeRegisteredDevices) {
            try {
                val devices = deviceRegistrationGateway.listOwnDevices(accountUid)
                updateForAccount(accountUid) { state ->
                    state.copy(
                        registeredDevices = devices.sortedWith(
                            compareByDescending<RemoteRegisteredDevice> { it.isCurrentDevice }
                                .thenByDescending { it.updatedAtMillis ?: Long.MIN_VALUE },
                        ),
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                loadFailure = loadFailure ?: exception
            }
        }
        updateForAccount(accountUid) { state ->
            state.copy(
                isRefreshing = false,
                notice = loadFailure?.let { failure ->
                    (failure as? RemoteChatException)?.userMessage
                        ?: "Could not load all account controls. Try again."
                },
            )
        }
    }

    private fun launchAccountAction(
        successNotice: String,
        action: suspend (RemoteAccountUid) -> Unit,
    ) {
        val accountUid = mutableUiState.value.accountUid ?: return
        if (mutableUiState.value.isActionRunning) return
        val launchedJob = viewModelScope.launch {
            updateForAccount(accountUid) { state -> state.copy(isActionRunning = true, notice = null) }
            try {
                action(accountUid)
                updateForAccount(accountUid) { state ->
                    state.copy(isActionRunning = false, notice = successNotice)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateForAccount(accountUid) { state ->
                    state.copy(
                        isActionRunning = false,
                        notice = (exception as? RemoteChatException)?.userMessage
                            ?: exception.message
                            ?: "Account operation failed.",
                    )
                }
            }
        }
        accountActionJob = launchedJob
        launchedJob.invokeOnCompletion {
            if (accountActionJob === launchedJob) accountActionJob = null
        }
    }

    private fun updateForAccount(
        accountUid: RemoteAccountUid,
        transform: (RemoteAccountUiState) -> RemoteAccountUiState,
    ) {
        mutableUiState.update { state ->
            if (state.accountUid == accountUid) transform(state) else state
        }
    }
}

class RemoteAccountViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RemoteAccountViewModel::class.java)) {
            return modelClass.cast(
                RemoteAccountViewModel(
                    authenticationGateway = graph.remoteAuthenticationGateway,
                    privacyGateway = graph.remotePrivacyGateway,
                    deviceRegistrationGateway = graph.remoteDeviceRegistrationGateway,
                    invitationGateway = graph.remoteInvitationGateway,
                    sessionController = graph.remoteAccountSessionController,
                ),
            ) ?: throw IllegalArgumentException("Unable to create RemoteAccountViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}

private const val STANDARD_INVITATION_LIFETIME_HOURS = 24 * 7
