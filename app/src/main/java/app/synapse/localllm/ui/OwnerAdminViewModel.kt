package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.remote.CreateOwnerAccountCommand
import app.synapse.localllm.domain.remote.CreateRemoteInvitationCommand
import app.synapse.localllm.domain.remote.OwnerAccountSummary
import app.synapse.localllm.domain.remote.OwnerAdminGateway
import app.synapse.localllm.domain.remote.OwnerAuditEventSummary
import app.synapse.localllm.domain.remote.OwnerDeviceSummary
import app.synapse.localllm.domain.remote.OwnerInvitationSummary
import app.synapse.localllm.domain.remote.OwnerOperationsSummary
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteInvitationGateway
import app.synapse.localllm.domain.remote.RemoteMessageOutboxOperation
import app.synapse.localllm.domain.remote.RemoteOutboxState
import app.synapse.localllm.domain.remote.ResetOwnerAccountPasswordCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OwnerAdminUiState(
    val accounts: List<OwnerAccountSummary> = emptyList(),
    val invitations: List<OwnerInvitationSummary> = emptyList(),
    val selectedAccountUid: RemoteAccountUid? = null,
    val selectedAccountDevices: List<OwnerDeviceSummary> = emptyList(),
    val auditEvents: List<OwnerAuditEventSummary> = emptyList(),
    val operationsSummary: OwnerOperationsSummary? = null,
    val localOutbox: OwnerLocalOutboxSummary = OwnerLocalOutboxSummary(),
    val registrationApprovalRequired: Boolean = true,
    val isActionRunning: Boolean = false,
    val notice: String? = null,
)

data class OwnerLocalOutboxSummary(
    val pendingCount: Int = 0,
    val inFlightCount: Int = 0,
    val failedCount: Int = 0,
)

class OwnerAdminViewModel(
    private val authenticationGateway: RemoteAuthenticationGateway,
    private val ownerAdminGateway: OwnerAdminGateway,
    private val remoteInvitationGateway: RemoteInvitationGateway,
    private val remoteChatCacheRepository: RemoteChatCacheRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(OwnerAdminUiState())
    private var activeOwnerUid: RemoteAccountUid? = null
    private var isAdminSurfaceVisible = false
    private var outboxObservationJob: Job? = null

    val uiState: StateFlow<OwnerAdminUiState> = mutableUiState

    init {
        viewModelScope.launch {
            authenticationGateway.authenticationState.collectLatest { authenticationState ->
                when (authenticationState) {
                    is RemoteAuthenticationState.SignedIn -> {
                        val account = authenticationState.account
                        if (
                            account.role == RemoteAccountRole.OWNER &&
                            account.state == RemoteAccountState.ACTIVE &&
                            !account.mustChangePassword
                        ) {
                            if (activeOwnerUid != account.accountUid) {
                                activeOwnerUid = account.accountUid
                                mutableUiState.value = OwnerAdminUiState()
                                observeOwnerOutbox(account.accountUid)
                                if (isAdminSurfaceVisible) refresh()
                            }
                        } else {
                            clearOwnerState()
                        }
                    }
                    RemoteAuthenticationState.SignedOut,
                    is RemoteAuthenticationState.InvalidSession,
                    -> clearOwnerState()
                    RemoteAuthenticationState.Resolving -> Unit
                }
            }
        }
    }

    fun setAdminSurfaceVisible(visible: Boolean) {
        if (isAdminSurfaceVisible == visible) return
        isAdminSurfaceVisible = visible
        if (visible && activeOwnerUid != null) refresh()
    }

    fun refresh(searchPrefix: String? = null) = launchAction {
        val ownerUid = requireOwnerSession()
        val snapshot = coroutineScope {
            val accounts = async { ownerAdminGateway.listAccounts(searchPrefix) }
            val invitations = async { ownerAdminGateway.listInvitations() }
            val auditEvents = async { ownerAdminGateway.listAuditEvents() }
            val registrationApprovalRequired = async {
                ownerAdminGateway.getRegistrationApprovalRequired()
            }
            val operationsSummary = async { ownerAdminGateway.getOperationsSummary() }
            OwnerAdminSnapshot(
                accounts = accounts.await(),
                invitations = invitations.await(),
                auditEvents = auditEvents.await(),
                registrationApprovalRequired = registrationApprovalRequired.await(),
                operationsSummary = operationsSummary.await(),
            )
        }
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(
                accounts = snapshot.accounts,
                invitations = snapshot.invitations,
                auditEvents = snapshot.auditEvents,
                registrationApprovalRequired = snapshot.registrationApprovalRequired,
                operationsSummary = snapshot.operationsSummary,
            )
        }
    }

    fun selectAccount(accountUid: RemoteAccountUid?) = launchAction {
        val ownerUid = requireOwnerSession()
        val devices = accountUid?.let { ownerAdminGateway.listDevices(it) }.orEmpty()
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(selectedAccountUid = accountUid, selectedAccountDevices = devices)
        }
    }

    fun reviewRegistration(
        targetUid: RemoteAccountUid,
        approve: Boolean,
    ) = launchMutation(if (approve) "Registration approved." else "Registration rejected.") {
        ownerAdminGateway.reviewRegistration(targetUid, approve)
    }

    fun setAccountEnabled(
        targetUid: RemoteAccountUid,
        enabled: Boolean,
    ) = launchMutation(if (enabled) "Account enabled." else "Account disabled.") {
        ownerAdminGateway.setAccountEnabled(targetUid, enabled)
    }

    fun revokeAccountSessions(targetUid: RemoteAccountUid) =
        launchMutation("Account sessions revoked.") {
            ownerAdminGateway.revokeAccountSessions(targetUid)
        }

    fun createAccount(
        ownerPassword: String,
        command: CreateOwnerAccountCommand,
        onCreated: () -> Unit,
    ) = launchSensitiveMutation(ownerPassword, "Account created.", onCreated) {
        ownerAdminGateway.createAccount(command)
    }

    fun resetAccountPassword(
        ownerPassword: String,
        command: ResetOwnerAccountPasswordCommand,
        onReset: () -> Unit,
    ) = launchSensitiveMutation(ownerPassword, "Temporary password set.", onReset) {
        ownerAdminGateway.resetAccountPassword(command)
    }

    fun deleteAccount(
        ownerPassword: String,
        targetUid: RemoteAccountUid,
        confirmUsername: String,
        onDeleted: () -> Unit,
    ) = launchSensitiveMutation(ownerPassword, "Account deleted.", onDeleted) {
        ownerAdminGateway.deleteAccount(targetUid, confirmUsername)
        mutableUiState.update { state ->
            state.copy(selectedAccountUid = null, selectedAccountDevices = emptyList())
        }
    }

    fun createInvitation(
        command: CreateRemoteInvitationCommand,
        onCreated: (String) -> Unit,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        val receipt = remoteInvitationGateway.createInvitation(command)
        requireOwnerSession(ownerUid)
        onCreated(receipt.invitationCode)
        refreshAfterMutation("Invitation created.", ownerUid)
    }

    fun revokeInvitation(invitationId: String) = launchMutation("Invitation revoked.") {
        ownerAdminGateway.revokeInvitation(invitationId)
    }

    fun setRegistrationApprovalRequired(required: Boolean) = launchAction {
        val ownerUid = requireOwnerSession()
        ownerAdminGateway.setRegistrationApprovalRequired(required)
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(
                registrationApprovalRequired = required,
                notice = "Registration approval setting saved.",
            )
        }
    }

    fun removeDevice(
        targetUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        ownerAdminGateway.removeDevice(targetUid, deviceId)
        val devices = ownerAdminGateway.listDevices(targetUid)
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(selectedAccountDevices = devices, notice = "Device registration removed.")
        }
    }

    fun sendTestPush(
        targetUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        ownerAdminGateway.sendTestPush(targetUid, deviceId)
        requireOwnerSession(ownerUid)
        mutableUiState.update { state -> state.copy(notice = "Test notification sent.") }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun launchMutation(
        successNotice: String,
        mutation: suspend () -> Unit,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        mutation()
        requireOwnerSession(ownerUid)
        refreshAfterMutation(successNotice, ownerUid)
    }

    private fun launchSensitiveMutation(
        ownerPassword: String,
        successNotice: String,
        onSuccess: () -> Unit,
        mutation: suspend () -> Unit,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        authenticationGateway.reauthenticate(ownerPassword)
        requireOwnerSession(ownerUid)
        mutation()
        requireOwnerSession(ownerUid)
        onSuccess()
        refreshAfterMutation(successNotice, ownerUid)
    }

    private suspend fun refreshAfterMutation(
        successNotice: String,
        ownerUid: RemoteAccountUid,
    ) {
        requireOwnerSession(ownerUid)
        val accounts = ownerAdminGateway.listAccounts()
        val invitations = ownerAdminGateway.listInvitations()
        val auditEvents = ownerAdminGateway.listAuditEvents()
        val registrationApprovalRequired = ownerAdminGateway.getRegistrationApprovalRequired()
        val operationsSummary = ownerAdminGateway.getOperationsSummary()
        val selectedUid = mutableUiState.value.selectedAccountUid
        val selectedDevices = selectedUid?.let { ownerAdminGateway.listDevices(it) }.orEmpty()
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(
                accounts = accounts,
                invitations = invitations,
                auditEvents = auditEvents,
                selectedAccountDevices = selectedDevices,
                registrationApprovalRequired = registrationApprovalRequired,
                operationsSummary = operationsSummary,
                notice = successNotice,
            )
        }
    }

    private fun launchAction(action: suspend () -> Unit) {
        if (mutableUiState.value.isActionRunning) return
        mutableUiState.update { state -> state.copy(isActionRunning = true, notice = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(
                        notice = (exception as? RemoteChatException)?.userMessage
                            ?: "Owner administration action failed.",
                    )
                }
            } finally {
                mutableUiState.update { state -> state.copy(isActionRunning = false) }
            }
        }
    }

    private fun requireOwnerSession(): RemoteAccountUid =
        activeOwnerUid ?: throw RemoteChatException("Owner access is no longer active.")

    private fun requireOwnerSession(expectedOwnerUid: RemoteAccountUid) {
        if (activeOwnerUid != expectedOwnerUid) throw OwnerSessionChangedException()
    }

    private fun observeOwnerOutbox(ownerUid: RemoteAccountUid) {
        outboxObservationJob?.cancel()
        outboxObservationJob = viewModelScope.launch {
            remoteChatCacheRepository.observePendingOutbox().collect { operations ->
                if (activeOwnerUid != ownerUid) return@collect
                mutableUiState.update { state ->
                    state.copy(localOutbox = summarizeOwnerOutbox(ownerUid, operations))
                }
            }
        }
    }

    private fun clearOwnerState() {
        outboxObservationJob?.cancel()
        outboxObservationJob = null
        activeOwnerUid = null
        mutableUiState.value = OwnerAdminUiState()
    }
}

internal fun summarizeOwnerOutbox(
    ownerUid: RemoteAccountUid,
    operations: List<RemoteMessageOutboxOperation>,
): OwnerLocalOutboxSummary {
    val ownerOperations = operations.filter { operation -> operation.accountUid == ownerUid }
    return OwnerLocalOutboxSummary(
        pendingCount = ownerOperations.count { it.state == RemoteOutboxState.PENDING },
        inFlightCount = ownerOperations.count { it.state == RemoteOutboxState.IN_FLIGHT },
        failedCount = ownerOperations.count { it.state == RemoteOutboxState.FAILED },
    )
}

private class OwnerSessionChangedException : CancellationException("Owner session changed.")

private data class OwnerAdminSnapshot(
    val accounts: List<OwnerAccountSummary>,
    val invitations: List<OwnerInvitationSummary>,
    val auditEvents: List<OwnerAuditEventSummary>,
    val registrationApprovalRequired: Boolean,
    val operationsSummary: OwnerOperationsSummary,
)

class OwnerAdminViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OwnerAdminViewModel::class.java)) {
            return modelClass.cast(
                OwnerAdminViewModel(
                    authenticationGateway = graph.remoteAuthenticationGateway,
                    ownerAdminGateway = graph.ownerAdminGateway,
                    remoteInvitationGateway = graph.remoteInvitationGateway,
                    remoteChatCacheRepository = graph.remoteChatCacheRepository,
                ),
            ) ?: throw IllegalArgumentException("Unable to create OwnerAdminViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
